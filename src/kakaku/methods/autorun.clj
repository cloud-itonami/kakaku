(ns kakaku.methods.autorun
  "kakaku 価格 — AUTONOMOUS price-difference / supply-demand heartbeat on the kotoba Datom log.
  clj-native SSoT (ADR-2606142300 D1: new logic-core authored in Clojure) + ADR-2605091200.

  Each heartbeat the actor runs its whole price-intel pipeline ITSELF, no human in the loop:
  observe (the OFFLINE product/merchant/offer/priceHistory snapshot — kotoba/seed.edn) → derive the
  cross-merchant price SPREAD (handle-arbitrage), the bounded supply/demand index
  (handle-supply-demand), and the present-interest proxy (handle-demand) → PERSIST one
  content-addressed transaction (the :kakaku.obs/* observations) to the append-only LOCAL kotoba
  Datom log, linking the previous CID into a verifiable commit-DAG.

  Constitution holds by construction (kakaku gates): only price-DIFFERENCE + supply/demand
  OBSERVATIONS are representable — never a buy/sell signal / price target / forecast (G2;
  forecasting is mitooshi's job); aggregate-first per product, no consumer PII (G4); every derived
  datom carries :sourcing :synthesized. The LIVE offer ingest (page fetch, ingest.clj) + the live
  social post stay operator-gated (G11, no-server-key) — this loop does NO external I/O and reads a
  LOCAL snapshot only. Deterministic / resume-safe (cycle drives tx-id + as-of; observed-at is a
  fixed snapshot stamp → same cycles produce the same commit-DAG)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kakaku.methods.agent :as agent]
            [kakaku.methods.kotoba :as k]))

(def base-as-of 20260616)
(def snapshot-stamp "snapshot")   ; deterministic observed-at (no wall clock)

(def ^:private here (-> (io/file *file*) .getParentFile .getParentFile))
(def seed-default (str (io/file here "kotoba" "seed.edn")))

(defn- kw->str [v] (when (some? v) (name v)))

(defn build-state
  "Adapt the EAVT seed rows (:product/* :merchant/* :offer/* :ph/*) into the camelCase `state`
  the kakaku.methods.agent handlers consume. Merchant id is the offer-id prefix (e.g. 'a_com:…' →
  'a_com'); region comes from the merchant. Keyword enums (:in-stock/:jp/:active) → strings."
  [rows]
  (let [merchants-raw (into {} (keep #(when (:merchant/id %) [(:merchant/id %) %]) rows))
        product (some #(when (:product/id %) %) rows)
        offers (filter :offer/id rows)
        ph (filter #(contains? % :ph/total-price) rows)]
    {:productId (:product/id product)
     :offers (mapv (fn [o]
                     (let [mid (first (str/split (:offer/id o) #":"))
                           m (get merchants-raw mid)]
                       {:merchantId mid
                        :price (:offer/price o)
                        :shippingFee (:offer/shipping-fee o)
                        :totalPrice (:offer/total-price o)
                        :availability (kw->str (:offer/availability o))
                        :deliveryEtaDays (:offer/delivery-eta-days o)
                        :productUrl (:offer/product-url o)
                        :region (kw->str (:merchant/region m))}))
                   offers)
     :merchants (into {} (map (fn [[id m]]
                                [id {:reputationScore (:merchant/reputation-score m)
                                     :status (kw->str (:merchant/status m))
                                     :region (kw->str (:merchant/region m))}])
                              merchants-raw))
     :priceHistory (mapv (fn [p] {:totalPrice (:ph/total-price p)
                                  :availability (kw->str (:ph/availability p))
                                  :observedAt (:ph/observed-at p)})
                         ph)}))

(defn observation-datoms
  "Derive the price-spread + supply/demand + interest observations and flatten them to family
  EAVT datoms ([:db/add e a v], string attrs). Every datom is :synthesized (G2 observation, never
  a signal/forecast). Per-region min-landed entities are emitted sorted (deterministic)."
  [state observed-at]
  (let [arb (agent/handle-arbitrage state)
        sd (agent/handle-supply-demand state)
        dem (agent/handle-demand state)
        pid (or (:productId state) "unknown")
        e (str "kakaku.obs." pid "." observed-at)
        base [(k/add e ":kakaku.obs/product" pid)
              (k/add e ":kakaku.obs/min-landed" (long (or (:minLanded arb) 0)))
              (k/add e ":kakaku.obs/max-landed" (long (or (:maxLanded arb) 0)))
              (k/add e ":kakaku.obs/spread" (long (or (:spread arb) 0)))
              (k/add e ":kakaku.obs/spread-fraction" (double (or (:spreadFraction arb) 0.0)))
              (k/add e ":kakaku.obs/notable" (boolean (:notable arb)))
              (k/add e ":kakaku.obs/cheapest-merchant" (or (:cheapestMerchant arb) "none"))
              (k/add e ":kakaku.obs/dearest-merchant" (or (:dearestMerchant arb) "none"))
              (k/add e ":kakaku.obs/supply-demand-index" (double (or (:supplyDemandIndex sd) 0.0)))
              (k/add e ":kakaku.obs/in-stock-ratio" (double (or (:inStockRatio sd) 0.0)))
              (k/add e ":kakaku.obs/price-velocity" (double (or (:priceVelocity sd) 0.0)))
              (k/add e ":kakaku.obs/reading" (str ":" (or (:reading sd) "balanced")))
              (k/add e ":kakaku.obs/observation-count" (long (or (:observationCount dem) 0)))
              (k/add e ":kakaku.obs/observed-at" observed-at)
              (k/add e ":kakaku.obs/intent" (or (:intent arb) "buyer-transparency+supply-resilience"))
              (k/add e ":kakaku.obs/sourcing" ":synthesized")]
        region (mapcat (fn [[reg info]]
                         (let [re (str e ".region." reg)]
                           [(k/add re ":kakaku.region/observation" e)
                            (k/add re ":kakaku.region/region" (str ":" reg))
                            (k/add re ":kakaku.region/min-landed" (long (or (:minLanded info) 0)))
                            (k/add re ":kakaku.region/cheapest-merchant" (or (:merchantId info) "none"))
                            (k/add re ":kakaku.region/sourcing" ":synthesized")]))
                       (sort-by key (or (:byRegion arb) {})))]
    {:datoms (vec (concat base region))
     :spread (or (:spread arb) 0) :notable (boolean (:notable arb))
     :reading (or (:reading sd) "balanced")}))

(defn run-cycle
  "One heartbeat: observe snapshot → derive observations → persist one content-addressed tx."
  [cycle seed-path log-path]
  (let [rows (edn/read-string (slurp (io/file seed-path)))
        state (build-state rows)
        {:keys [datoms spread notable reading]} (observation-datoms state snapshot-stamp)
        tx (k/make-tx datoms cycle (+ base-as-of cycle) (k/head-cid log-path))
        cid (k/append-tx tx log-path)]
    {:cycle cycle :offers (count (:offers state)) :spread spread :notable notable
     :reading reading :datoms (count datoms) :cid cid}))

(defn run-autonomous [cycles seed-path log-path]
  (let [beats (mapv #(run-cycle % seed-path log-path) (range 1 (inc cycles)))]
    {:cycles cycles :beats beats :log-length (count (k/read-log log-path))
     :head-cid (k/head-cid log-path) :chain (k/verify-chain log-path)}))

(defn -main [& argv]
  (let [argv (vec argv)
        opt (fn [f d] (let [i (.indexOf argv f)] (if (>= i 0) (nth argv (inc i)) d)))
        flag? (fn [f] (>= (.indexOf argv f) 0))
        cycles (Long/parseLong (str (opt "--cycles" "3")))
        seed-path (opt "--seed" seed-default)
        log-path (opt "--log" k/log-default)]
    (when (and (flag? "--fresh") (.exists (io/file log-path))) (.delete (io/file log-path)))
    (let [res (run-autonomous cycles seed-path log-path)]
      (println "# kakaku — AUTONOMOUS price-difference / supply-demand over the kotoba Datom log "
               "(offline snapshot, LOCAL persist; live offer ingest + social post stay operator-gated)\n")
      (doseq [bt (:beats res)]
        (println (str "  ♥ cycle " (:cycle bt) ": " (:offers bt) " offers · spread " (:spread bt)
                      " (notable " (:notable bt) ") · " (:reading bt) " +" (:datoms bt)
                      " datoms → cid " (subs (:cid bt) 0 (min 14 (count (:cid bt)))) "…")))
      (let [ch (:chain res)]
        (println (str "\n  log: " (:log-length res) " tx · head "
                      (subs (:head-cid res) 0 (min 14 (count (:head-cid res)))) "… · chain "
                      (if (:ok ch) "OK ✓" (str "BROKEN at " (:broken-at ch)))
                      " · price-difference observation, never a signal/forecast (G2)")))
      (System/exit (if (:ok (:chain res)) 0 1)))))
