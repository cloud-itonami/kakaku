(ns kakaku.viz.test-build-viz
  "kakaku 価格 — viz builder tests. 1:1 port of viz/test_build_viz.py: the viz payload mirrors the
  agent handlers (single source of truth), carries the G2 buyer-transparency intent, and the
  rendered HTML inlines the payload (self-contained, file:// — no external fetch). Seed is read +
  classified via kakaku.methods.kakaku-edn (load-edn/classify)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kakaku.methods.kakaku-edn :as edn]
            [kakaku.viz.build-viz-data :as b]))

(def seed (io/file "20-actors/kakaku/kotoba/seed.edn"))
(def template (io/file "20-actors/kakaku/viz/_template.htm"))

(defn- payload []
  (let [{:keys [products merchants offers ph]} (edn/classify (edn/load-edn seed))]
    (b/build-payload products merchants offers ph)))

(deftest test-one-card-per-product-with-offers
  (let [p (payload)
        card (first (get p "cards"))]
    (is (= 1 (count (get p "cards"))))
    (is (= "jan_4901777300443" (get card "productId")))
    (is (= 3 (count (get card "offers"))))))

(deftest test-spread-matches-agent-math
  (let [card (first (get (payload) "cards"))]
    ;; seed landed: a_com 3200, b_com 3900, c_com 3500 → spread 700, cheapest a_com
    (is (and (= 3200 (get card "minLanded")) (= 3900 (get card "maxLanded"))))
    (is (= 700 (get card "spread")))
    (is (= "a_com" (get card "cheapestMerchant")))))

(deftest test-offers-sorted-cheapest-first
  (let [offers (get (first (get (payload) "cards")) "offers")
        landeds (mapv #(get % "landed") offers)]
    (is (= landeds (vec (sort landeds))))
    (is (= "a_com" (get (first offers) "merchantId")))))

(deftest test-by-region-min-landed
  (let [card (first (get (payload) "cards"))]
    ;; jp: a_com(3200) & c_com(3500) → min 3200; us: b_com(3900)
    (is (= 3200 (get-in card ["byRegion" "jp" "minLanded"])))
    (is (= 3900 (get-in card ["byRegion" "us" "minLanded"])))))

(deftest test-supply-demand-present
  (let [card (first (get (payload) "cards"))]
    (is (contains? #{"scarcity" "balanced" "glut"} (get card "reading")))
    (is (<= -1.0 (get card "supplyDemandIndex") 1.0))))

(deftest test-g2-intent-is-carried-not-a-trade
  (let [p (payload)]
    (is (= "buyer-transparency+supply-resilience" (get p "intent")))
    (is (= "buyer-transparency+supply-resilience" (get (first (get p "cards")) "intent")))))

(deftest test-html-inlines-payload-self-contained
  (when (.exists template)
    (let [html (b/render-html (payload) template)]
      (is (not (str/includes? html "/*__PAYLOAD__*/null")))   ; placeholder replaced
      (is (str/includes? html "jan_4901777300443"))           ; data inlined
      (let [js (second (str/split html #"<script>"))]
        (is (and (not (str/includes? js "http://")) (not (str/includes? js "https://"))))))))
