(ns kakaku.methods.ingest
  "kakaku 価格 — offer ingest from page content (extraction pipeline). 1:1 port of py/ingest.py.

  Turns an already-fetched page payload into a canonical offer record via a tiered extraction
  strategy: (1) JSON-LD schema.org Product/Offer → (2) merchant-specific selector regex → (3)
  meta/regex og:title + currency-symbol price. The network FETCH itself is the only operator-gated
  step (G11, no-server-key): ingest-offer-from-url refuses to fetch live without an operator ref —
  extraction runs only on already-fetched/test content. Source URLs are affiliate-stripped (G3).

  Pure-stdlib (re/json/html + a hand-rolled urllib.parse-equivalent for strip-affiliate). The
  Murakumo `llm` host binding (tier 4 _llm_fill) is the omitted leg: as in the local-dev fallback
  (llm = None), the LLM fill never runs and the deterministic tiers stand alone."
  (:require [clojure.string :as str]
            #?(:clj [cheshire.core :as json])))

;; schema.org availability → kakaku enum
(def AVAIL
  {"instock" "in-stock" "in_stock" "in-stock" "available" "in-stock"
   "outofstock" "out-of-stock" "soldout" "out-of-stock"
   "preorder" "preorder" "presale" "preorder"
   "backorder" "backorder" "limitedavailability" "in-stock"})
;; affiliate / tracking params stripped from any source URL (mirrors okaimono G3 denylist).
(def AFFILIATE-PARAMS
  #{"tag" "aff" "affid" "aff_id" "affiliate" "affiliate_id" "partner" "pid"
    "click_id" "clickid" "ascsubtag" "linkcode" "linkid" "scid" "ref" "ref_"
    "gclid" "fbclid" "msclkid" "yclid" "dclid"})
(def AFFILIATE-PREFIXES ["utm_" "aff_" "pk_"])

(defn- html-unescape [s]
  (-> s
      (str/replace #"&#(\d+);" (fn [[_ n]] (str (char (Integer/parseInt n)))))
      (str/replace #"&#[xX]([0-9a-fA-F]+);" (fn [[_ n]] (str (char (Integer/parseInt n 16)))))
      (str/replace "&lt;" "<") (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'") (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn normalize-availability [raw]
  (if (or (nil? raw) (= raw "") (false? raw))
    "unknown"
    (let [s (-> (str raw)
                (str/replace #"https?://schema\.org/" "")
                str/trim str/lower-case
                (str/replace "-" "") (str/replace " " ""))]
      (get AVAIL s "unknown"))))

(defn strip-affiliate
  "Remove affiliate/tracking params from a source URL (G3); functional params kept."
  [url]
  (if (or (nil? url) (= url ""))
    ""
    (let [[_ scheme netloc path query]
          (re-matches #"(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#.*)?" url)
          pairs (if (and query (not= query ""))
                  (map (fn [p] (let [i (str/index-of p "=")]
                                 (if i [(subs p 0 i) (subs p (inc i))] [p ""])))
                       (str/split query #"&"))
                  [])
          kept (filter (fn [[k _]]
                         (let [kl (str/lower-case k)]
                           (and (not (AFFILIATE-PARAMS kl))
                                (not (some #(str/starts-with? kl %) AFFILIATE-PREFIXES)))))
                       pairs)
          q (str/join "&" (map (fn [[k v]] (str k "=" v)) kept))
          base (cond (and (seq scheme) (seq netloc)) (str scheme "://" netloc path)
                     (seq scheme) (str scheme ":" path)
                     :else (str netloc path))]
      (if (= q "") base (str base "?" q)))))

;; ── 1. JSON-LD schema.org Product/Offer ──────────────────────────────────────
(def ^:private LD-RE
  #"(?is)<script[^>]+type=[\"']application/ld\+json[\"'][^>]*>(.*?)</script>")

(defn- walk-for-offer
  "Find the first object carrying an offer (price) anywhere in a JSON-LD tree."
  [node]
  (cond
    (map? node)
    (let [t (str/lower-case (str (get node "@type" "")))
          nm (get node "name")
          offers (get node "offers")
          cand (cond (map? offers) offers
                     (and (sequential? offers) (seq offers)) (first offers)
                     :else nil)
          src (cond (map? cand) cand
                    (or (contains? node "price") (= t "offer")) node
                    :else nil)]
      (if (and (map? src) (some? (get src "price")))
        {"name" (or nm (get node "name")) "price" (get src "price")
         "currency" (get src "priceCurrency") "availability" (get src "availability")}
        (loop [vs (vals node)]
          (if (empty? vs)
            (if nm {"name" nm} {})
            (let [sub (walk-for-offer (first vs))]
              (if (some? (get sub "price"))
                (if (contains? sub "name") sub (assoc sub "name" nm))
                (recur (rest vs))))))))
    (sequential? node)
    (loop [vs node]
      (if (empty? vs) {}
          (let [sub (walk-for-offer (first vs))]
            (if (some? (get sub "price")) sub (recur (rest vs))))))
    :else {}))

(defn extract-jsonld [content]
  (loop [blocks (map second (re-seq LD-RE (or content "")))]
    (if (empty? blocks)
      {}
      (let [got (try (walk-for-offer (json/parse-string (str/trim (first blocks))))
                     (catch #?(:clj Exception :cljs :default) _ nil))]
        (if (and got (some? (get got "price"))) got (recur (rest blocks)))))))

;; ── 2. selector profile (regex)  +  3. meta/regex fallback ───────────────────
(defn extract-selector [content profile]
  (reduce (fn [out [field pat]]
            (let [m (re-find (re-pattern pat) (or content ""))]
              (cond (nil? m) out
                    (vector? m) (assoc out field (second m))   ; has groups → group 1
                    :else (assoc out field m))))               ; no groups → whole match
          {} (or profile {})))

(def ^:private PRICE-RE #"[¥$€£]\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)")
(def ^:private OG-TITLE-RE
  #"(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"']")
(def ^:private CURRENCY-SYM {\¥ "JPY" \$ "USD" \€ "EUR" \£ "GBP"})

(defn extract-meta [content]
  (let [c (or content "")
        mt (re-find OG-TITLE-RE c)
        mp (re-find PRICE-RE c)]
    (cond-> {}
      mt (assoc "name" (html-unescape (second mt)))
      mp (assoc "price" (str/replace (second mp) "," "")
                "currency" (CURRENCY-SYM (first (first mp)))))))

(defn- to-minor
  "Convert a price string/number to integer minor units (×100 for 2-dp currencies)."
  [price]
  (try
    (long (Math/round (* (Double/parseDouble (str/replace (str price) "," "")) 100.0)))
    (catch #?(:clj Exception :cljs :default) _ 0)))

;; ── orchestration ────────────────────────────────────────────────────────────
(def ^:private REQUIRED ["name" "price" "currency" "availability"])

(defn extract-offer
  "Run the tiered extraction over already-fetched content. Deterministic tiers only — the Murakumo
  LLM fill (tier 4) is the omitted leg (llm = None in the port)."
  ([content] (extract-offer content nil false))
  ([content selector-profile _use-llm]
   (let [merged (reduce (fn [m tier]
                          (reduce (fn [mm [k v]]
                                    (if (and (some? v) (contains? #{nil ""} (get mm k)))
                                      (assoc mm k v) mm))
                                  m tier))
                        {} [(extract-jsonld content)
                            (extract-selector content (or selector-profile {}))
                            (extract-meta content)])
         price-minor (to-minor (get merged "price"))]
     {"name" (str/trim (or (get merged "name") ""))
      "price" price-minor                                ; minor units
      "currency" (or (get merged "currency") "unknown")
      "availability" (normalize-availability (get merged "availability"))
      "extracted" (> price-minor 0)
      "tiers" (if (seq (extract-jsonld content)) ["jsonld"] [])})))

(defn ingest-offer-from-url
  "Ingest an offer. The network FETCH is operator-gated (G11, no-server-key): when no content is
  supplied a live fetch is required, which is REFUSED without an operator ref. With content
  (pre-fetched or test) it extracts deterministically. The source URL is affiliate-stripped (G3)."
  ([url] (ingest-offer-from-url url nil nil nil false))
  ([url content] (ingest-offer-from-url url content nil nil false))
  ([url content selector-profile operator-ref use-llm]
   (let [clean-url (strip-affiliate url)]
     (if (nil? content)
       {"state" "fetch-gated" "productUrl" clean-url
        "reason" (if-not operator-ref
                   "live fetch requires an operator ref (G11 no-server-key)"
                   "operator present — wire the live fetcher before use (G11)")}
       (let [offer (extract-offer content selector-profile use-llm)]
         (assoc offer "productUrl" clean-url
                "state" (if (get offer "extracted") "extracted" "incomplete")))))))
