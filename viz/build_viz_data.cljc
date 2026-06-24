(ns kakaku.viz.build-viz-data
  "kakaku 価格 — price-difference / supply-demand visualization payload + viewer. 1:1 port of the
  PURE functions of viz/build_viz_data.py: build-payload (classified seed → viz cards via
  kakaku.py.agent handlers, the single source of truth — the viz re-implements no math) and
  render-html (inline the payload JSON into the self-contained template). The __main__
  load-edn/classify/write CLI is the omitted I/O leg.

  Note: kakaku.methods.kakaku-edn/classify emits KEYWORD-keyed maps whereas kakaku.py.agent (a 1:1
  port of the string-keyed Python dicts) reads STRING keys; build-payload bridges the two — the
  same data flow the Python file gets for free (its classify emits string-keyed dicts).

  A BUYER price-transparency + supply-resilience surface, never a trading signal (kakaku G2)."
  (:require [clojure.string :as str]
            [kakaku.py.agent :as agent]
            #?(:clj [cheshire.core :as json])))

(defn- str-offer [o]
  {"merchantId" (:merchantId o) "price" (:price o) "shippingFee" (:shippingFee o)
   "totalPrice" (:totalPrice o) "availability" (:availability o)
   "deliveryEtaDays" (:deliveryEtaDays o) "productUrl" (:productUrl o)
   "region" (:region o)})

(defn- str-ph [h]
  {"totalPrice" (:totalPrice h) "availability" (:availability h) "observedAt" (:observedAt h)})

(defn build-payload
  "One viz record per product: ranked offers (landed) + spread + supply/demand, all via the agent
  handlers. Region is joined from the merchant registry."
  [products merchants offers price-history]
  (let [region-of (into {} (map (fn [[_ m]] [(:merchantId m) (or (:region m) "unknown")]) merchants))
        soffers (mapv (fn [o] (str-offer (assoc o :region (get region-of (:merchantId o) "unknown")))) offers)
        sph (mapv str-ph price-history)
        cards (mapv (fn [[pid p]]
                      (let [arb (agent/handle-arbitrage {"offers" soffers})
                            sd (agent/handle-supply-demand {"offers" soffers "priceHistory" sph})]
                        {"productId" pid
                         "name" (or (:name p) pid)
                         "offers" (mapv (fn [o] {"merchantId" (get o "merchantId")
                                                 "region" (get o "region")
                                                 "landed" (agent/landed-price o)
                                                 "availability" (get o "availability")})
                                        (sort-by agent/landed-price soffers))
                         "cheapestMerchant" (get arb "cheapestMerchant")
                         "minLanded" (get arb "minLanded")
                         "maxLanded" (get arb "maxLanded")
                         "spread" (get arb "spread")
                         "spreadFraction" (get arb "spreadFraction")
                         "notable" (get arb "notable")
                         "byRegion" (get arb "byRegion" {})
                         "supplyDemandIndex" (get sd "supplyDemandIndex")
                         "reading" (get sd "reading")
                         ;; G2 invariant, mirrored from agent/handle-arbitrage
                         "intent" (get arb "intent" "buyer-transparency+supply-resilience")}))
                    products)]
    {"generator" "kakaku/viz/build_viz_data.py"
     "intent" "buyer-transparency+supply-resilience"
     "cards" cards}))

(defn render-html
  "Inline the payload JSON into the self-contained template (mirror of render_html)."
  [payload template]
  (str/replace (slurp (str template)) "/*__PAYLOAD__*/null"
               #?(:clj (json/generate-string payload) :cljs (str payload))))
