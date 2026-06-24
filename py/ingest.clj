#!/usr/bin/env bb
;; Working Clojure port of py/ingest.py.
(ns kakaku.py.ingest
  "kakaku 価格 — offer ingest from page content (extraction pipeline, ADR-2605091200).

  Tiered extraction strategy:
    1. JSON-LD   schema.org Product/Offer (<script type=\"application/ld+json\">)
    2. selector  merchant-specific regex profile (per-merchant override)
    3. meta/regex og:title + currency-symbol price patterns
    4. Murakumo  LLM host-binding fill for STILL-missing fields (G5, nil in dev/offline)

  The network FETCH is the only operator-gated step (G11, no-server-key): ingest-offer-from-url
  refuses to fetch live without an operator ref — extraction runs on already-fetched or test
  content. Affiliate params are never kept (G3): the source URL is normalized.

  Run:  bb --classpath 20-actors 20-actors/kakaku/py/ingest.clj"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-file *file*)

;; schema.org availability → kakaku enum
(def ^:private avail-map
  {"instock" "in-stock" "in_stock" "in-stock" "available" "in-stock"
   "outofstock" "out-of-stock" "soldout" "out-of-stock"
   "preorder" "preorder" "presale" "preorder"
   "backorder" "backorder" "limitedavailability" "in-stock"})

;; Affiliate/tracking params stripped from source URLs (G3, mirrors okaimono denylist)
(def ^:private affiliate-params
  #{"tag" "aff" "affid" "aff_id" "affiliate" "affiliate_id" "partner" "pid"
    "click_id" "clickid" "ascsubtag" "linkcode" "linkid" "scid" "ref" "ref_"
    "gclid" "fbclid" "msclkid" "yclid" "dclid"})

(def ^:private affiliate-prefixes ["utm_" "aff_" "pk_"])

(def ^:private currency-sym {"¥" "JPY" "$" "USD" "€" "EUR" "£" "GBP"})

;; ── availability normalizer ────────────────────────────────────────────────

(defn normalize-availability
  "Normalise a raw schema.org availability string to the kakaku enum."
  [raw]
  (if (nil? raw)
    "unknown"
    (let [s (-> (str raw)
                (str/replace #"https?://schema\.org/" "")
                str/trim
                str/lower-case
                (str/replace #"-" "")
                (str/replace #" " ""))]
      (get avail-map s "unknown"))))

;; ── URL helper ────────────────────────────────────────────────────────────

(defn- parse-query
  "Split a query string 'a=1&b=2' into pairs [[k v] ...]."
  [qs]
  (when (seq qs)
    (for [pair (str/split qs #"&") :when (seq pair)]
      (let [[k v] (str/split pair #"=" 2)]
        [(or k "") (or v "")]))))

(defn- encode-query
  "Reassemble [[k v] ...] pairs into a query string."
  [pairs]
  (str/join "&" (map (fn [[k v]] (if (seq v) (str k "=" v) k)) pairs)))

(defn strip-affiliate
  "Remove affiliate/tracking params from a source URL (G3); functional params kept."
  [url]
  (if (or (nil? url) (str/blank? url))
    ""
    (let [uri     (java.net.URI. url)
          qs      (.getRawQuery uri)
          kept    (filter (fn [[k _]]
                            (let [lk (str/lower-case k)]
                              (and (not (contains? affiliate-params lk))
                                   (not (some #(str/starts-with? lk %) affiliate-prefixes)))))
                          (parse-query qs))
          new-qs  (when (seq kept) (encode-query kept))
          rebuilt (java.net.URI. (.getScheme uri) (.getAuthority uri) (.getPath uri) new-qs nil)]
      (.toASCIIString rebuilt))))

;; ── 1. JSON-LD schema.org Product/Offer ──────────────────────────────────

(def ^:private ld-re
  ;; Finds content of <script type="application/ld+json">…</script> blocks
  #"(?is)<script[^>]+type=[\"']application/ld\+json[\"'][^>]*>(.*?)</script>")

(declare walk-for-offer)

(defn- walk-for-offer-map
  "Find the first offer-bearing node in a JSON-decoded map (string keys)."
  [node]
  (let [found (when-let [n (get node "name")] {"name" n})
        t     (str/lower-case (str (get node "@type" "")))
        offers (get node "offers")
        cand  (cond (map? offers) offers
                    (and (sequential? offers) (seq offers)) (first offers)
                    :else nil)
        src   (if (and (map? cand) (some? (get cand "price")))
                cand
                (when (or (contains? node "price") (= t "offer")) node))]
    (if (and (map? src) (some? (get src "price")))
      {"name"         (or (get found "name") (get node "name"))
       "price"        (get src "price")
       "currency"     (get src "priceCurrency")
       "availability" (get src "availability")}
      ;; recurse into values
      (reduce (fn [_ v]
                (let [sub (walk-for-offer v)]
                  (when (some? (get sub "price"))
                    (reduced (if (nil? (get sub "name"))
                               (assoc sub "name" (get found "name"))
                               sub)))))
              (or found {})
              (vals node)))))

(defn walk-for-offer
  "Recursively search JSON-LD node (maps/vectors) for an offer-bearing object."
  [node]
  (cond
    (map? node)        (walk-for-offer-map node)
    (sequential? node) (or (some (fn [v]
                                   (let [s (walk-for-offer v)]
                                     (when (some? (get s "price")) s)))
                                 node)
                           {})
    :else {}))

(defn extract-jsonld
  "Extract the first schema.org Product/Offer from JSON-LD blocks in HTML content."
  [content]
  (or (some (fn [[_ block]]
              (try
                (let [data (json/parse-string (str/trim block))
                      got  (walk-for-offer data)]
                  (when (some? (get got "price")) got))
                (catch Exception _ nil)))
            (re-seq ld-re (str content)))
      {}))

;; ── 2. merchant-specific selector (regex) profile ─────────────────────────

(defn extract-selector
  "Apply a map of {field regex-string} against content; returns matched values."
  [content profile]
  (reduce (fn [out [field pat]]
            (let [m (re-find (re-pattern pat) (str content))]
              (if m
                (assoc out field (if (string? m) m (second m)))
                out)))
          {}
          (or profile {})))

;; ── 3. meta / regex fallback ──────────────────────────────────────────────

(def ^:private price-re   #"[¥$€£]\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)")
(def ^:private og-title-re #"(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"']")

(defn- html-unescape [s]
  (-> s
      (str/replace "&amp;"  "&")
      (str/replace "&lt;"   "<")
      (str/replace "&gt;"   ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;"  "'")))

(defn extract-meta
  "Extract og:title and first currency-symbol price from HTML content."
  [content]
  (let [c   (str content)
        out (atom {})]
    (when-let [m (re-find og-title-re c)]
      (swap! out assoc "name" (html-unescape (second m))))
    (when-let [m (re-find price-re c)]
      (let [sym      (str (first (first m)))
            price-s  (-> (second m) (str/replace "," ""))]
        (swap! out assoc "price" price-s "currency" (get currency-sym sym))))
    @out))

;; ── price → minor units ───────────────────────────────────────────────────

(defn- to-minor
  "Convert a price string/number to integer minor units (×100)."
  [price]
  (try
    (int (Math/round (* (Double/parseDouble (-> (str (or price "0"))
                                               (str/replace "," "")))
                        100.0)))
    (catch Exception _ 0)))

;; ── orchestration ─────────────────────────────────────────────────────────

(def ^:private required-fields ["name" "price" "currency" "availability"])

(defn extract-offer
  "Run the tiered extraction over already-fetched content.
  Deterministic tiers first; Murakumo LLM (when use-llm and available) fills only still-missing
  fields (G5). Returns a canonical offer map with minor-unit price."
  ([content] (extract-offer content nil false))
  ([content selector-profile] (extract-offer content selector-profile false))
  ([content selector-profile use-llm]
   (let [tiers   [(extract-jsonld content)
                  (extract-selector content selector-profile)
                  (extract-meta content)]
         merged  (reduce (fn [m tier]
                           (reduce (fn [m [k v]]
                                     (if (and (some? v) (not= v "")
                                              (or (nil? (get m k)) (= (get m k) "")))
                                       (assoc m k v)
                                       m))
                                   m tier))
                         {} tiers)
         ;; LLM fill: omitted in this port (no kotoba llm binding in bb dev)
         _       (when (and use-llm
                            (some #(or (nil? (get merged %)) (= "" (get merged %)))
                                  required-fields))
                   ;; G5: would call Murakumo llm host binding here; silently skip when absent
                   nil)
         price-minor (to-minor (get merged "price"))]
     {:name         (str/trim (str (get merged "name" "")))
      :price        price-minor
      :currency     (or (get merged "currency") "unknown")
      :availability (normalize-availability (get merged "availability"))
      :extracted    (pos? price-minor)
      :tiers        (vec (for [[t present]
                               [["jsonld" (some? (get (extract-jsonld content) "price"))]]
                               :when present] t))})))

;; ── top-level ingest ──────────────────────────────────────────────────────

(defn ingest-offer-from-url
  "Ingest an offer. Live network fetch is operator-gated (G11, no-server-key): when no
  `content` is supplied a live fetch is required, which is REFUSED without an operator ref.
  With `content` (pre-fetched or test) it extracts deterministically.
  Source URL is affiliate-stripped (G3)."
  ([url] (ingest-offer-from-url url nil nil nil false))
  ([url content] (ingest-offer-from-url url content nil nil false))
  ([url content selector-profile] (ingest-offer-from-url url content selector-profile nil false))
  ([url content selector-profile operator-ref use-llm]
   (let [clean-url (strip-affiliate (str url))]
     (if (nil? content)
       (if (not (seq operator-ref))
         {:state      "fetch-gated"
          :productUrl clean-url
          :reason     "live fetch requires an operator ref (G11 no-server-key)"}
         {:state      "fetch-gated"
          :productUrl clean-url
          :reason     "operator present — wire the live fetcher before use (G11)"})
       (let [offer (extract-offer content selector-profile use-llm)]
         (assoc offer
                :productUrl clean-url
                :state      (if (:extracted offer) "extracted" "incomplete")))))))

(defn main [& _]
  (println "kakaku ingest: extraction pipeline ready (deterministic tiers; live fetch G11-gated)."))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
