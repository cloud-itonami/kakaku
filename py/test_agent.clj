#!/usr/bin/env bb
;; Working Clojure port of py/test_agent.py.
(ns kakaku.py.test-agent
  "kakaku 価格 — agent logic tests (clj port). Pure-logic over the handlers; verifies the
  invariants that distinguish kakaku from a trading/affiliate engine: landed price is the basis
  (G3), too-good offers flagged suspicious (never #1), arbitrage is a buyer/resilience SPREAD not
  a trade (G2), supply/demand is a bounded present-state index not a forecast (G2), intel/social
  aggregate-first no-affiliate no-nudge (G3/G4), social operator-gated default :draft (G11).

  Run:  bb --classpath 20-actors 20-actors/kakaku/py/test_agent.clj"
  (:require [kakaku.py.agent :as agent]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private merchants
  {"a_com" {:reputationScore 0.9 :status "active"}
   "b_com" {:reputationScore 0.6 :status "active"}
   "scam_com" {:reputationScore 0.2 :status "suspended"}})

(defn- offers []
  [{:merchantId "a_com" :price 10000 :shippingFee 500 :availability "in-stock"
    :deliveryEtaDays 2 :productUrl "https://a.example/p" :region "jp"}
   {:merchantId "b_com" :price 9000 :shippingFee 2000 :availability "in-stock"
    :deliveryEtaDays 7 :productUrl "https://b.example/p" :region "us"}])

(deftest landed-price-includes-shipping
  (is (= (agent/landed-price {:price 9000 :shippingFee 2000}) 11000)))

(deftest cheapest-ranks-on-landed-not-sticker
  ;; b lower sticker (9000) but higher landed (11000); a wins on landed (10500)
  (let [out (agent/handle-rank {:offers (offers) :merchants merchants})]
    (is (= (:merchantId (:cheapest out)) "a_com"))))

(deftest suspicious-offer-flagged-and-excluded
  (let [os (conj (offers) {:merchantId "scam_com" :price 100 :shippingFee 0 :availability "in-stock"
                           :deliveryEtaDays 1 :productUrl "https://scam.example/p" :region "jp"})
        out (agent/handle-rank {:offers os :merchants merchants})]
    (is (contains? (set (map :merchantId (:suspicious out))) "scam_com"))
    (is (not= (:merchantId (:cheapest out)) "scam_com"))))

(deftest arbitrage-spread-and-regions
  (let [out (agent/handle-arbitrage {:offers (offers)})]
    (is (= (:spread out) 500))                ; landed a=10500 b=11000
    (is (= (:cheapestMerchant out) "a_com"))
    (is (= (set (keys (:byRegion out))) #{"jp" "us"}))
    (is (= (:intent out) "buyer-transparency+supply-resilience"))))

(deftest arbitrage-notable-threshold
  (let [out (agent/handle-arbitrage {:offers [{:merchantId "a_com" :price 10000 :shippingFee 0 :availability "in-stock"}
                                              {:merchantId "b_com" :price 13000 :shippingFee 0 :availability "in-stock"}]})]
    (is (= (:spreadFraction out) 0.3))
    (is (true? (:notable out)))))

(deftest arbitrage-single-offer-is-zero
  (let [out (agent/handle-arbitrage {:offers (vec (take 1 (offers)))})]
    (is (and (= (:spread out) 0) (false? (:notable out))))))

(deftest supply-demand-scarcity-when-low-stock-and-rising
  (let [out (agent/handle-supply-demand
             {:offers [{:merchantId "a_com" :availability "out-of-stock"}
                       {:merchantId "b_com" :availability "backorder"}]
              :priceHistory [{:observedAt "2026-06-01" :totalPrice 10000}
                             {:observedAt "2026-06-07" :totalPrice 13000}]})]
    (is (= (:reading out) "scarcity"))
    (is (> (:supplyDemandIndex out) 0.33))))

(deftest supply-demand-glut-when-ample-and-falling
  (let [out (agent/handle-supply-demand
             {:offers [{:merchantId "a_com" :availability "in-stock"}
                       {:merchantId "b_com" :availability "in-stock"}]
              :priceHistory [{:observedAt "2026-06-01" :totalPrice 13000}
                             {:observedAt "2026-06-07" :totalPrice 10000}]})]
    (is (= (:reading out) "glut"))
    (is (< (:supplyDemandIndex out) -0.33))))

(deftest supply-demand-index-bounded
  (let [out (agent/handle-supply-demand
             {:offers [{:merchantId "a_com" :availability "out-of-stock"}]
              :priceHistory [{:observedAt "2026-06-01" :totalPrice 1}
                             {:observedAt "2026-06-07" :totalPrice 1000000}]})]
    (is (<= -1.0 (:supplyDemandIndex out) 1.0))))

(deftest demand-is-present-proxy-not-forecast
  (let [out (agent/handle-demand {:priceHistory [{:merchantId "a_com" :totalPrice 10000}
                                                 {:merchantId "b_com" :totalPrice 11000}
                                                 {:merchantId "a_com" :totalPrice 10500}]
                                  :cohortObservationTotal 12})]
    (is (= (:observationCount out) 3))
    (is (= (:merchantCount out) 2))
    (is (= (:demandShare out) 0.25))
    (is (= (:kind out) "present-interest-proxy"))))

(deftest intel-is-aggregate-first
  (let [out (agent/handle-intel {:productId "jan_4901777300443" :offers (offers)
                                 :priceHistory [{:observedAt "2026-06-01" :totalPrice 10500}]})]
    (is (= (:shape (:intel out)) "aggregate"))
    (is (contains? (:intel out) :spread))))

(deftest social-default-is-draft-and-clean
  (let [out (agent/handle-social {:productId "jan_4901777300443" :offers (offers)
                                  :priceHistory [{:observedAt "2026-06-01" :totalPrice 10500}]})]
    (is (= (:state out) "draft"))
    (is (false? (:affiliate (:post out))))
    (is (false? (:nudge (:post out))))
    (is (= (:shape (:post out)) "aggregate"))))

(deftest social-posts-with-operator
  (let [out (agent/handle-social {:productId "jan_4901777300443" :offers (offers)
                                  :priceHistory [{:observedAt "2026-06-01" :totalPrice 10500}]
                                  :operatorRef "op:council-attest-123"})]
    (is (= (:state out) "posted"))))

(deftest social-weekly-ceiling-enforced
  (let [out (agent/handle-social {:productId "x" :offers (offers) :priceHistory []
                                  :postsThisWeek agent/social-weekly-ceiling
                                  :operatorRef "op:council-attest-123"})]
    (is (true? (:refused out)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kakaku.py.test-agent)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
