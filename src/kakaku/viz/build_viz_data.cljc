(ns kakaku.viz.build-viz-data
  "kakaku 価格 — price-difference / supply-demand visualization payload + viewer. 1:1 port of the
  PURE functions of viz/build_viz_data.py: build-payload (classified seed → viz cards via
  kakaku.methods.agent handlers, the single source of truth — the viz re-implements no math) and
  render-html (inline the payload JSON into the self-contained template). The __main__
  load-edn/classify/write CLI is the omitted I/O leg.

  Note: kakaku.methods.kakaku-edn/classify emits STRING-keyed maps (the agent-facing field names,
  same shape kakaku.methods.agent — a 1:1 port of the string-keyed Python dicts — expects), so
  build-payload reads them directly; the only join it adds is merchant -> region.

  A BUYER price-transparency + supply-resilience surface, never a trading signal (kakaku G2)."
  (:require [clojure.string :as str]
            [kakaku.methods.agent :as agent]
            #?(:clj [cheshire.core :as json])))

(defn- str-offer [o]
  {"merchantId" (get o "merchantId") "price" (get o "price") "shippingFee" (get o "shippingFee")
   "totalPrice" (get o "totalPrice") "availability" (get o "availability")
   "deliveryEtaDays" (get o "deliveryEtaDays") "productUrl" (get o "productUrl")
   "region" (get o "region")})

(defn- str-ph [h]
  {"totalPrice" (get h "totalPrice") "availability" (get h "availability") "observedAt" (get h "observedAt")})

(defn build-payload
  "One viz record per product: ranked offers (landed) + spread + supply/demand, all via the agent
  handlers. Region is joined from the merchant registry."
  [products merchants offers price-history]
  (let [region-of (into {} (map (fn [[_ m]] [(get m "merchantId") (or (get m "region") "unknown")]) merchants))
        soffers (mapv (fn [o] (str-offer (assoc o "region" (get region-of (get o "merchantId") "unknown")))) offers)
        sph (mapv str-ph price-history)
        cards (mapv (fn [[pid p]]
                      (let [arb (agent/handle-arbitrage {"offers" soffers})
                            sd (agent/handle-supply-demand {"offers" soffers "priceHistory" sph})]
                        {"productId" pid
                         "name" (or (get p "name") pid)
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
