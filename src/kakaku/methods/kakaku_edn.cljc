(ns kakaku.methods.kakaku-edn
  "kakaku 価格 — minimal EDN-subset reader + seed classifier
  (1:1 Clojure port of methods/kakaku_edn.py, ADR-2605091200).

  Reader: parses the EDN subset the kakaku seed/output files use — a top-level
  vector of maps whose values are strings, longs, doubles (incl. scientific),
  keywords, nil/true/false, and nested vectors. No tagged literals/sets/symbols.
  Keywords are kept as \":ns/name\" strings (Python parity); maps preserve
  insertion order. Self-contained + dependency-free, mirroring the
  kasa/kabuto/kanjo `*_edn` family so each actor reads its own substrate.

  Classifier: `classify` splits the flat seed vector into products / merchants /
  offers / price-history with the agent-facing field names (string keys, exactly
  like kakaku_edn.py's dicts); `kw` strips a leading ':' off keyword values."
  (:require [clojure.string :as str]))

;; ── reader (subset; same proven char-cursor reader as the *_edn family) ──────
;; Keywords keep the leading ':' (a string, not a Clojure keyword). Maps preserve
;; first-touch insertion order so structure matches the Python dict order.

(def ^:private eof ::eof)

(defn- ws? [c]
  (or (= c \space) (= c \tab) (= c \return) (= c \newline) (= c \,)))

(defn- delim? [c]
  (or (ws? c) (= c \[) (= c \]) (= c \{) (= c \}) (= c \")))

(defn- ordered-assoc
  "assoc preserving first-touch insertion order past 8 keys (array-map silently
  becomes an unordered hash-map beyond 8 entries; we track order in ::order meta)."
  [m k v]
  (if (and (instance? clojure.lang.PersistentArrayMap m) (< (count m) 8) (not (contains? m k)))
    (assoc m k v)
    (let [had? (contains? m k)
          base (if (::order (meta m)) m (with-meta (into (array-map) m) {::order (vec (keys m))}))
          m' (assoc base k v)]
      (if had? (with-meta m' (meta base))
          (with-meta m' (update (meta base) ::order (fnil conj []) k))))))

(defn- skip-ws
  "Advance i past whitespace, commas, and ; line comments. Returns new i."
  [^String s n i]
  (loop [i i]
    (if (>= i n)
      i
      (let [c (.charAt s i)]
        (cond
          (= c \;) (recur (loop [j i] (if (and (< j n) (not= (.charAt s j) \newline)) (recur (inc j)) j)))
          (ws? c) (recur (inc i))
          :else i)))))

(declare read-form)

(defn- read-vec [^String s n i]
  (loop [i i, out []]
    (let [i (skip-ws s n i)]
      (cond
        (>= i n) (throw (ex-info "unterminated vector" {}))
        (= (.charAt s i) \]) [out (inc i)]
        :else (let [[v i] (read-form s n i)]
                (recur i (conj out v)))))))

(defn- read-map [^String s n i]
  (loop [i i, out (array-map)]
    (let [i (skip-ws s n i)]
      (cond
        (>= i n) (throw (ex-info "unterminated map" {}))
        (= (.charAt s i) \}) [out (inc i)]
        :else (let [[k i] (read-form s n i)
                    [v i] (read-form s n i)]
                (recur i (ordered-assoc out k v)))))))

(defn- read-str [^String s n i]
  (let [sb (StringBuilder.)]
    (loop [i i]
      (if (>= i n)
        (throw (ex-info "unterminated string" {}))
        (let [c (.charAt s i)]
          (cond
            (= c \\) (let [nxt (if (< (inc i) n) (.charAt s (inc i)) \space)
                           rep (case nxt \n \newline \t \tab \r \return nxt)]
                       (.append sb rep)
                       (recur (+ i 2)))
            (= c \") [(.toString sb) (inc i)]
            :else (do (.append sb c) (recur (inc i)))))))))

(defn- read-kw [^String s n i]
  (let [j i]
    (loop [i (inc i)]
      (if (and (< i n) (not (delim? (.charAt s i))))
        (recur (inc i))
        [(subs s j i) i]))))

(defn- numeric-token?
  "Token has one of .eE AND, stripped of sign/.eE chars, is all digits."
  [^String tok]
  (and (some #(or (= % \.) (= % \e) (= % \E)) tok)
       (let [stripped (-> tok
                          (str/replace #"^[-+]+" "")
                          (str/replace "." "")
                          (str/replace "e" "")
                          (str/replace "E" "")
                          (str/replace "-" "")
                          (str/replace "+" ""))]
         (and (seq stripped) (every? #(Character/isDigit ^char %) stripped)))))

(defn- read-atom [^String s n i]
  (let [j i]
    (loop [i i]
      (if (and (< i n) (not (delim? (.charAt s i))))
        (recur (inc i))
        (let [tok (subs s j i)]
          [(cond
             (= tok "nil") nil
             (= tok "true") true
             (= tok "false") false
             (numeric-token? tok)
             (try (Double/parseDouble tok) (catch #?(:clj Exception :cljs :default) _ tok))
             :else
             (let [as-long (try (Long/parseLong tok) (catch #?(:clj Exception :cljs :default) _ ::nan))]
               (if (not= as-long ::nan) as-long tok)))
           i])))))

(defn- read-form
  "Read one form at index i; return [value next-i] or [eof i] at EOF."
  [^String s n i]
  (let [i (skip-ws s n i)]
    (if (>= i n)
      [eof i]
      (let [c (.charAt s i)]
        (cond
          (= c \[) (read-vec s n (inc i))
          (= c \{) (read-map s n (inc i))
          (= c \") (read-str s n (inc i))
          (= c \:) (read-kw s n i)
          :else (read-atom s n i))))))

(defn read-all
  "Read every top-level form; return the first vector found (the dataset), else
  first form, else []."
  [text]
  (let [s (str text), n (count s)]
    (loop [i 0, forms []]
      (let [[v i] (read-form s n i)]
        (if (= v eof)
          (or (first (filter vector? forms)) (first forms) [])
          (recur i (conj forms v)))))))

#?(:clj
   (defn read-file
     "Read + parse an EDN file → the top-level vector. File I/O only at this edge."
     [path]
     (read-all (slurp (str path)))))

;; ── classifier (1:1 port of kakaku_edn.classify / _kw) ───────────────────────

(defn kw
  "Strip a leading ':' off an EDN keyword value (\":in-stock\" → \"in-stock\")."
  [v]
  (if (and (string? v) (str/starts-with? v ":")) (subs v 1) v))

(defn classify
  "Split the flat seed vector into {:products :merchants :offers :price-history},
  normalizing to the agent-facing field names (string keys, like the Python dicts).
  Keyword values (region/status/availability) are stripped of their leading ':'."
  [rows]
  (reduce
   (fn [acc r]
     (cond
       (not (map? r)) acc

       (contains? r ":product/id")
       (update acc :products assoc (get r ":product/id")
               {"productId" (get r ":product/id") "name" (get r ":product/name")
                "brand" (get r ":product/brand") "jan" (get r ":product/jan")
                "category" (get r ":product/category")})

       (contains? r ":merchant/id")
       (update acc :merchants assoc (get r ":merchant/id")
               {"merchantId" (get r ":merchant/id") "name" (get r ":merchant/name")
                "region" (kw (get r ":merchant/region"))
                "reputationScore" (get r ":merchant/reputation-score")
                "status" (kw (get r ":merchant/status"))})

       (contains? r ":offer/id")
       (update acc :offers conj
               (let [mid (first (str/split (get r ":offer/id") #":" 2))]
                 {"offerId" (get r ":offer/id") "merchantId" mid
                  "price" (get r ":offer/price" 0) "shippingFee" (get r ":offer/shipping-fee" 0)
                  "totalPrice" (get r ":offer/total-price" 0)
                  "availability" (kw (get r ":offer/availability" "unknown"))
                  "deliveryEtaDays" (get r ":offer/delivery-eta-days" 14)
                  "productUrl" (get r ":offer/product-url")}))

       (contains? r ":ph/total-price")
       (update acc :price-history conj
               {"totalPrice" (get r ":ph/total-price")
                "availability" (kw (get r ":ph/availability" "unknown"))
                "observedAt" (get r ":ph/observed-at")})

       :else acc))
   {:products (array-map) :merchants (array-map) :offers [] :price-history []}
   rows))
