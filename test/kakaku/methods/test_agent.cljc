(ns kakaku.methods.test-agent
  "kakaku 価格 — agent logic tests. 1:1 port of py/test_agent.py. Pure-logic over the handlers (no
  kotoba host bindings): landed price (price+shipping) is the comparison basis (G3); too-good-to-
  be-true offers are flagged suspicious, never #1; arbitrage reports a buyer/resilience SPREAD,
  never a trade (G2); supply/demand is a bounded present-state index, not a forecast (G2); intel +
  social default to aggregate-first, no affiliate/nudge (G3/G4); live broadcast is operator-gated."
  (:require [clojure.test :refer [deftest is]]
            [kakaku.methods.agent :as agent]))

(def MERCHANTS
  {"a_com" {"reputationScore" 0.9 "status" "active"}
   "b_com" {"reputationScore" 0.6 "status" "active"}
   "scam_com" {"reputationScore" 0.2 "status" "suspended"}})

(defn- offers []
  [{"merchantId" "a_com" "price" 10000 "shippingFee" 500 "availability" "in-stock"
    "deliveryEtaDays" 2 "productUrl" "https://a.example/p" "region" "jp"}
   {"merchantId" "b_com" "price" 9000 "shippingFee" 2000 "availability" "in-stock"
    "deliveryEtaDays" 7 "productUrl" "https://b.example/p" "region" "us"}])

;; ── landed price + ranking ────────────────────────────────────────────────
(deftest test-landed-price-includes-shipping
  (is (= 11000 (agent/landed-price {"price" 9000 "shippingFee" 2000}))))

(deftest test-cheapest-ranks-on-landed-not-sticker
  ;; b has the lower sticker (9000) but higher landed (11000); a wins on landed (10500)
  (let [out (agent/handle-rank {"offers" (offers) "merchants" MERCHANTS})]
    (is (= "a_com" (get (get out "cheapest") "merchantId")))))

(deftest test-suspicious-offer-flagged-and-excluded
  (let [offs (conj (offers)
                   {"merchantId" "scam_com" "price" 100 "shippingFee" 0 "availability" "in-stock"
                    "deliveryEtaDays" 1 "productUrl" "https://scam.example/p" "region" "jp"})
        out (agent/handle-rank {"offers" offs "merchants" MERCHANTS})
        sus-ids (set (map #(get % "merchantId") (get out "suspicious")))]
    (is (contains? sus-ids "scam_com"))
    (is (not= "scam_com" (get (get out "cheapest") "merchantId")))))  ; never ranked #1

;; ── arbitrage / spread ────────────────────────────────────────────────────
(deftest test-arbitrage-spread-and-regions
  (let [out (agent/handle-arbitrage {"offers" (offers)})]
    ;; landed: a=10500, b=11000 → spread 500, cheapest a_com
    (is (= 500 (get out "spread")))
    (is (= "a_com" (get out "cheapestMerchant")))
    (is (= #{"jp" "us"} (set (keys (get out "byRegion")))))
    (is (= "buyer-transparency+supply-resilience" (get out "intent")))))  ; G2: never a trade

(deftest test-arbitrage-notable-threshold
  (let [offs [{"merchantId" "a_com" "price" 10000 "shippingFee" 0 "availability" "in-stock"}
              {"merchantId" "b_com" "price" 13000 "shippingFee" 0 "availability" "in-stock"}]
        out (agent/handle-arbitrage {"offers" offs})]
    (is (= 0.3 (get out "spreadFraction")))
    (is (= true (get out "notable")))))

(deftest test-arbitrage-single-offer-is-zero
  (let [out (agent/handle-arbitrage {"offers" (vec (take 1 (offers)))})]
    (is (and (= 0 (get out "spread")) (= false (get out "notable"))))))

;; ── supply / demand ───────────────────────────────────────────────────────
(deftest test-supply-demand-scarcity-when-low-stock-and-rising
  (let [offs [{"merchantId" "a_com" "availability" "out-of-stock"}
              {"merchantId" "b_com" "availability" "backorder"}]
        history [{"observedAt" "2026-06-01" "totalPrice" 10000}
                 {"observedAt" "2026-06-07" "totalPrice" 13000}]
        out (agent/handle-supply-demand {"offers" offs "priceHistory" history})]
    (is (= "scarcity" (get out "reading")))
    (is (> (get out "supplyDemandIndex") 0.33))))

(deftest test-supply-demand-glut-when-ample-and-falling
  (let [offs [{"merchantId" "a_com" "availability" "in-stock"}
              {"merchantId" "b_com" "availability" "in-stock"}]
        history [{"observedAt" "2026-06-01" "totalPrice" 13000}
                 {"observedAt" "2026-06-07" "totalPrice" 10000}]
        out (agent/handle-supply-demand {"offers" offs "priceHistory" history})]
    (is (= "glut" (get out "reading")))
    (is (< (get out "supplyDemandIndex") -0.33))))

(deftest test-supply-demand-index-bounded
  (let [offs [{"merchantId" "a_com" "availability" "out-of-stock"}]
        history [{"observedAt" "2026-06-01" "totalPrice" 1}
                 {"observedAt" "2026-06-07" "totalPrice" 1000000}]
        out (agent/handle-supply-demand {"offers" offs "priceHistory" history})]
    (is (<= -1.0 (get out "supplyDemandIndex") 1.0))))

;; ── demand proxy ──────────────────────────────────────────────────────────
(deftest test-demand-is-present-proxy-not-forecast
  (let [history [{"merchantId" "a_com" "totalPrice" 10000}
                 {"merchantId" "b_com" "totalPrice" 11000}
                 {"merchantId" "a_com" "totalPrice" 10500}]
        out (agent/handle-demand {"priceHistory" history "cohortObservationTotal" 12})]
    (is (= 3 (get out "observationCount")))
    (is (= 2 (get out "merchantCount")))
    (is (= 0.25 (get out "demandShare")))
    (is (= "present-interest-proxy" (get out "kind")))))  ; G2: not a forecast

;; ── intel (aggregate-first) ───────────────────────────────────────────────
(deftest test-intel-is-aggregate-first
  (let [out (agent/handle-intel {"productId" "jan_4901777300443"
                                 "offers" (offers)
                                 "priceHistory" [{"observedAt" "2026-06-01" "totalPrice" 10500}]})]
    (is (= "aggregate" (get (get out "intel") "shape")))
    (is (contains? (get out "intel") "spread"))))

;; ── social (charter-clean, operator-gated) ────────────────────────────────
(deftest test-social-default-is-draft-and-clean
  (let [out (agent/handle-social {"productId" "jan_4901777300443"
                                  "offers" (offers)
                                  "priceHistory" [{"observedAt" "2026-06-01" "totalPrice" 10500}]})]
    (is (= "draft" (get out "state")))                  ; G11
    (is (= false (get (get out "post") "affiliate")))   ; G3
    (is (= false (get (get out "post") "nudge")))       ; G4
    (is (= "aggregate" (get (get out "post") "shape")))))

(deftest test-social-posts-with-operator
  (let [out (agent/handle-social {"productId" "jan_4901777300443"
                                  "offers" (offers)
                                  "priceHistory" [{"observedAt" "2026-06-01" "totalPrice" 10500}]
                                  "operatorRef" "op:council-attest-123"})]
    (is (= "posted" (get out "state")))))

(deftest test-social-weekly-ceiling-enforced
  (let [out (agent/handle-social {"productId" "x" "offers" (offers) "priceHistory" []
                                  "postsThisWeek" agent/SOCIAL-WEEKLY-CEILING
                                  "operatorRef" "op:council-attest-123"})]
    (is (= true (get out "refused")))))
