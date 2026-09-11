(ns kakaku.methods.agent
  "kakaku 価格 — global price-difference / supply-demand intel actor. 1:1 port of py/agent.py.

  Handlers over one kotoba EAVT graph of products/merchants/offers + append-only priceHistory:
    handle-rank          cheapest / best-overall / suspicious over landed price (G3)
    handle-arbitrage     cross-merchant + cross-region price SPREAD for one product
    handle-supply-demand availability + price-velocity → a bounded supply/demand index [-1,1]
    handle-demand        observation-frequency demand proxy (never a forecast; mitooshi owns that)
    handle-intel         aggregate-first price-transparency report (G4, public-good)
    handle-social        compose a charter-clean aggregate social post (G4/G11, draft default)

  Constitutional posture: G2 non-speculative (price DIFFERENCE for the buyer, never a buy/sell
  signal) · G3 no ads/affiliate · G4 aggregate-first · G5 Murakumo-only · G11 outward-gated. The
  optional `from kotoba import datalog, llm` host binding is the omitted leg: as in the local-dev
  fallback (llm = None), narration is never produced — handle-intel returns narration nil.")

(defn- roundn [x n]
  (let [f (Math/pow 10.0 n)]
    (/ (Math/round (* (double x) f)) f)))

(defn- median [xs]
  (let [s (vec (sort xs)) n (count s)]
    (if (odd? n)
      (nth s (quot n 2))
      (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))

;; Per-week public-aggregate post ceiling (G4, mirrors ossekai aggregate_publisher).
(def SOCIAL-WEEKLY-CEILING 100)
;; A spread at/above this fraction of the cheapest landed price is "notable" for intel.
(def NOTABLE-SPREAD-FRACTION 0.15)
;; Trust/availability weights for best-overall ranking (CLAUDE.md "Ranking Rules").
(def AVAILABILITY-RANK {"in-stock" 2 "preorder" 1 "backorder" 0 "out-of-stock" -2 "unknown" -1})

(defn landed-price
  "price + shippingFee in minor units. Cross-site comparison ranks on this, not sticker price."
  [offer]
  (+ (long (get offer "price" 0)) (long (get offer "shippingFee" 0))))

(defn- best-overall-score
  "Weighted by landed price (lower better), availability, ETA, and merchant trust. Higher = better.
  Never weighted by paid placement (G3)."
  [offer merchants]
  (let [landed (landed-price offer)
        avail (get AVAILABILITY-RANK (get offer "availability" "unknown") -1)
        eta-days (double (get offer "deliveryEtaDays" 14))
        m (get merchants (get offer "merchantId") {})
        trust (double (get m "reputationScore" 0.5))
        price-reward (/ 1000000.0 (+ landed 1.0))]
    (- (+ price-reward (* avail 2.0) (* trust 3.0)) (* eta-days 0.05))))

(defn- suspicious?
  "Unusually low landed price vs the field, inactive merchant, missing stock state, or a broken
  source URL. Suspicious offers are flagged, never ranked #1."
  [offer landed-vals merchants]
  (let [m (get merchants (get offer "merchantId") {})]
    (cond
      (not (contains? #{nil "active"} (get m "status"))) true
      (or (not (get offer "availability")) (= (get offer "availability") "unknown")) true
      (not (get offer "productUrl")) true
      (>= (count landed-vals) 3) (let [med (median landed-vals)]
                                   (and (> med 0) (< (landed-price offer) (* med 0.4))))
      :else false)))

(defn handle-rank [state]
  (let [offers (vec (get state "offers" []))
        merchants (get state "merchants" {})]
    (if (empty? offers)
      (merge state {"cheapest" nil "bestOverall" nil "suspicious" []})
      (let [landed-vals (mapv landed-price offers)
            suspicious (vec (filter #(suspicious? % landed-vals merchants) offers))
            sus-set (set suspicious)
            clean (let [c (vec (remove #(contains? sus-set %) offers))] (if (seq c) c offers))
            cheapest (apply min-key landed-price clean)
            best (apply max-key #(best-overall-score % merchants) clean)]
        (merge state {"cheapest" cheapest "bestOverall" best "suspicious" suspicious})))))

(defn handle-arbitrage
  "Landed-price SPREAD for one product across merchants/regions. Buyer-facing transparency + a
  supply-resilience signal, NOT a trading instruction (G2)."
  [state]
  (let [offers (vec (remove #(get % "suspicious") (get state "offers" [])))]
    (if (< (count offers) 2)
      (merge state {"spread" 0 "spreadFraction" 0.0 "notable" false "byRegion" {}})
      (let [landed (mapv (fn [o] [(landed-price o) o]) offers)
            lo (apply min-key first landed)
            hi (apply max-key first landed)
            lo-val (first lo) hi-val (first hi)
            spread (- hi-val lo-val)
            frac (if (and lo-val (not (zero? lo-val))) (/ (double spread) lo-val) 0.0)
            by-region (reduce (fn [m [val o]]
                                (let [region (get o "region" "unknown")
                                      cur (get m region)]
                                  (if (or (nil? cur) (< val (get cur "minLanded")))
                                    (assoc m region {"minLanded" val "merchantId" (get o "merchantId")})
                                    m)))
                              {} landed)]
        (merge state {"minLanded" lo-val "maxLanded" hi-val
                      "cheapestMerchant" (get (second lo) "merchantId")
                      "dearestMerchant" (get (second hi) "merchantId")
                      "spread" spread "spreadFraction" (roundn frac 4)
                      "notable" (>= frac NOTABLE-SPREAD-FRACTION)
                      "byRegion" by-region
                      "intent" "buyer-transparency+supply-resilience"})))))

(defn- total-or-price [p]
  (long (if (contains? p "totalPrice") (get p "totalPrice") (get p "price" 0))))

(defn- price-velocity
  "Signed fractional change between the oldest and newest observation in a sorted priceHistory
  window. Positive = rising (demand-pressure proxy), negative = falling."
  [history]
  (let [pts (sort-by #(get % "observedAt" "") history)]
    (if (< (count pts) 2)
      0.0
      (let [first* (total-or-price (first pts))
            last* (total-or-price (last pts))]
        (if (zero? first*) 0.0 (/ (double (- last* first*)) first*))))))

(defn handle-supply-demand
  "Bounded supply/demand index in [-1,1] from current offer availability + recent price velocity.
  An OBSERVATION-derived index, not a forecast (G2)."
  [state]
  (let [offers (get state "offers" [])
        history (get state "priceHistory" [])]
    (if (empty? offers)
      (merge state {"supplyDemandIndex" 0.0 "inStockRatio" 0.0 "priceVelocity" 0.0})
      (let [in-stock (count (filter #(= (get % "availability") "in-stock") offers))
            in-stock-ratio (/ (double in-stock) (count offers))
            scarcity (- 1.0 in-stock-ratio)
            velocity (price-velocity history)
            scarcity-signed (* (- scarcity 0.5) 2.0)
            velocity-clamped (max -1.0 (min 1.0 (* velocity 4.0)))
            index (max -1.0 (min 1.0 (/ (+ scarcity-signed velocity-clamped) 2.0)))]
        (merge state {"supplyDemandIndex" (roundn index 4)
                      "inStockRatio" (roundn in-stock-ratio 4)
                      "priceVelocity" (roundn velocity 4)
                      "reading" (cond (> index 0.33) "scarcity"
                                      (< index -0.33) "glut"
                                      :else "balanced")})))))

(defn handle-demand
  "A demand PROXY from observation frequency in the window — present-tense interest, never a
  predicted future quantity (G2)."
  [state]
  (let [history (get state "priceHistory" [])
        obs (count history)
        cohort-total (long (get state "cohortObservationTotal" 0))
        share (if (zero? cohort-total) 0.0 (/ (double obs) cohort-total))]
    (merge state {"observationCount" obs
                  "merchantCount" (count (set (map #(get % "merchantId") history)))
                  "demandShare" (roundn share 4)
                  "kind" "present-interest-proxy"})))

(defn handle-intel
  "Aggregate-first intel record for one product (spread, cheapest landed, supply/demand reading).
  Aggregate is the DEFAULT shape (G4). Narration via Murakumo (G5) is the omitted leg → nil."
  [state]
  (let [arb (handle-arbitrage state)
        sd (handle-supply-demand state)
        summary {"productId" (get state "productId")
                 "minLanded" (get arb "minLanded")
                 "spread" (get arb "spread")
                 "spreadFraction" (get arb "spreadFraction")
                 "notable" (get arb "notable")
                 "supplyDemandIndex" (get sd "supplyDemandIndex")
                 "reading" (get sd "reading")
                 "shape" "aggregate"}]
    (merge state {"intel" summary "narration" nil})))

(defn handle-social
  "Build (not broadcast) an aggregate-first social post from an intel record. Never a purchase
  nudge/urgency/affiliate (G3/G4); live posting operator-gated (G11): without operatorRef the post
  is a :draft. The weekly aggregate ceiling (G4) is enforced against postsThisWeek."
  [state]
  (let [intel (or (get state "intel") (get (handle-intel state) "intel" {}))
        posts-this-week (long (get state "postsThisWeek" 0))]
    (if (>= posts-this-week SOCIAL-WEEKLY-CEILING)
      (merge state {"post" nil "refused" true
                    "reason" (str "weekly aggregate ceiling reached (" SOCIAL-WEEKLY-CEILING "/wk, G4)")})
      (let [frac-pct (roundn (* (double (get intel "spreadFraction" 0.0)) 100) 1)
            text (str "価格透明性: " (get intel "productId" "product") " の現在の最安 landed 価格差は "
                      frac-pct "% (" (get intel "reading" "balanced") ")。"
                      " 購買勧誘ではなく公共的な価格可視化です。")
            post {"text" text
                  "shape" "aggregate"           ; G4 aggregate-first
                  "lexicon" "app.bsky.feed.post"
                  "affiliate" false              ; G3
                  "nudge" false}                 ; G4: no urgency/purchase nudge
            operator-ref (get state "operatorRef")]
        (if-not operator-ref
          (merge state {"post" post "state" "draft" "reason" "live broadcast is operator-gated (G11)"})
          (merge state {"post" post "state" "posted" "operatorRef" operator-ref}))))))
