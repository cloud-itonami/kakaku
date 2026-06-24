#!/usr/bin/env bb
;; LIVE cross-language py↔clj parity for the kakaku price-intel core.
(ns kakaku.py.test-agent-parity
  "test_agent_parity.clj — kakaku agent py↔clj LIVE parity (ADR-2606073201 lineage).

  kakaku's price-intel handlers (landed-price ranking, cross-merchant/region arbitrage SPREAD,
  bounded supply/demand index) drive the buyer-transparency signal that feeds meyasu/mitooshi.
  The existing clj test pins values captured once from agent.py; this runs the ACTUAL agent.py
  via a python3 subprocess and the clj impl over the SAME offers/merchants fixture, then
  deep-compares a normalized DERIVED summary — rank (cheapest / best-overall / suspicious
  merchant-ids), arbitrage (minLanded / maxLanded / spread / spreadFraction / notable /
  cheapest+dearest merchant / per-region table), supply-demand (index / in-stock ratio /
  price-velocity / reading) — to 1e-6, catching drift in EITHER impl.

  (The echoed input offers are deliberately NOT compared — py uses string keys, clj keywords;
  only the COMPUTED fields are, normalized to string keys both sides.)

  Gracefully SKIPS if python3 is unavailable (red only on a genuine py↔clj divergence).

  Run:  bb --classpath 20-actors 20-actors/kakaku/py/test_agent_parity.clj"
  (:require [kakaku.py.agent :as a]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private py-dir "20-actors/kakaku/py")

;; ── shared fixture: 3 offers (incl. a too-good "scam") + 3 merchants ──
(def ^:private clj-offers
  [{:merchantId "a_com" :price 10000 :shippingFee 500 :availability "in-stock"
    :deliveryEtaDays 2 :productUrl "https://a.example/p" :region "jp"}
   {:merchantId "b_com" :price 9000 :shippingFee 2000 :availability "in-stock"
    :deliveryEtaDays 7 :productUrl "https://b.example/p" :region "us"}
   {:merchantId "scam_com" :price 100 :shippingFee 0 :availability "in-stock"
    :deliveryEtaDays 1 :productUrl "https://scam.example/p" :region "jp"}])
(def ^:private clj-merchants
  {"a_com" {:reputationScore 0.9 :status "active"}
   "b_com" {:reputationScore 0.6 :status "active"}
   "scam_com" {:reputationScore 0.2 :status "suspended"}})

(def ^:private py-src
  (str "import json, agent as a\n"
       "merchants={'a_com':{'reputationScore':0.9,'status':'active'},"
       "'b_com':{'reputationScore':0.6,'status':'active'},"
       "'scam_com':{'reputationScore':0.2,'status':'suspended'}}\n"
       "offers=[{'merchantId':'a_com','price':10000,'shippingFee':500,'availability':'in-stock','deliveryEtaDays':2,'productUrl':'https://a.example/p','region':'jp'},"
       "{'merchantId':'b_com','price':9000,'shippingFee':2000,'availability':'in-stock','deliveryEtaDays':7,'productUrl':'https://b.example/p','region':'us'},"
       "{'merchantId':'scam_com','price':100,'shippingFee':0,'availability':'in-stock','deliveryEtaDays':1,'productUrl':'https://scam.example/p','region':'jp'}]\n"
       "st={'offers':offers,'merchants':merchants}\n"
       "rank=a.handle_rank(st); arb=a.handle_arbitrage(st); sd=a.handle_supply_demand(st)\n"
       "ak=['minLanded','maxLanded','spread','spreadFraction','intent','notable','cheapestMerchant','dearestMerchant','byRegion']\n"
       "sk=['supplyDemandIndex','inStockRatio','priceVelocity','reading']\n"
       "summary={'rank':{'cheapest':rank['cheapest']['merchantId'],'bestOverall':rank['bestOverall']['merchantId'],"
       "'suspicious':[o['merchantId'] for o in rank['suspicious']]},"
       "'arb':{k:arb[k] for k in ak if k in arb},'sd':{k:sd[k] for k in sk if k in sd}}\n"
       "print(json.dumps(summary))\n"))

(defn- py-summary []
  (try
    (let [r (sh "python3" "-c" py-src :dir py-dir)]
      (when (and (= 0 (:exit r)) (seq (:out r)))
        (json/parse-string (:out r) false)))   ; string keys both sides
    (catch Exception _ nil)))

(defn- clj-summary []
  (let [st {:offers clj-offers :merchants clj-merchants}
        rank (a/handle-rank st)
        arb (a/handle-arbitrage st)
        sd (a/handle-supply-demand st)
        pick (fn [m ks] (reduce (fn [acc k] (if (contains? m k)
                                              (assoc acc (name k) (get m k)) acc)) {} ks))]
    {"rank" {"cheapest" (:merchantId (:cheapest rank))
             "bestOverall" (:merchantId (:bestOverall rank))
             "suspicious" (mapv :merchantId (:suspicious rank))}
     "arb" (pick arb [:minLanded :maxLanded :spread :spreadFraction :intent :notable
                      :cheapestMerchant :dearestMerchant :byRegion])
     "sd" (pick sd [:supplyDemandIndex :inStockRatio :priceVelocity :reading])}))

;; clj nested maps (e.g. byRegion's per-region records) carry keyword keys; py (via JSON,
;; keywords:false) carries strings. Normalize clj keys to strings recursively before comparing.
(defn- stringify-keys [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) (stringify-keys v)]) x))
    (sequential? x) (mapv stringify-keys x)
    :else x))

(defn- deep-close? [a b]
  (cond
    (and (number? a) (number? b)) (< (Math/abs (- (double a) (double b))) 1e-6)
    (and (map? a) (map? b)) (and (= (set (keys a)) (set (keys b)))
                                 (every? #(deep-close? (get a %) (get b %)) (keys a)))
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b))
                                               (every? true? (map deep-close? a b)))
    :else (= a b)))

(deftest clj-price-intel-is-sane
  ;; runs regardless of python: landed = price+shipping; the too-good scam is flagged suspicious.
  (is (= 11000 (a/landed-price {:price 9000 :shippingFee 2000})))
  (let [s (clj-summary)]
    (is (some #{"scam_com"} (get-in s ["rank" "suspicious"])) "too-good offer is suspicious")
    ;; with the scam offer (landed 100) included, spread = 11000 − 100 = 10900
    (is (= 10900 (get-in s ["arb" "spread"])) "landed spread maxLanded 11000 − minLanded 100")
    (is (= "scam_com" (get-in s ["arb" "cheapestMerchant"])) "cheapest landed = the scam offer")
    (is (contains? #{"glut" "balanced" "tight"} (get-in s ["sd" "reading"])))))

(deftest price-intel-matches-python
  (let [py (py-summary)]
    (if-not py
      (is true "python3 unavailable — kakaku price-intel cross-language parity skipped")
      (let [clj (stringify-keys (clj-summary))]
        (is (deep-close? py clj) (str "summary drift: py " py " clj " clj))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kakaku.py.test-agent-parity)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
