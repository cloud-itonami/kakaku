(ns kakaku.methods.test-ingest
  "kakaku 価格 — offer ingest extraction tests. 1:1 port of py/test_ingest.py. Verifies the tiered
  extraction (JSON-LD → selector → meta/regex) and the gates: live fetch is operator-gated (G11),
  source URLs are affiliate-stripped (G3), and the Murakumo LLM is a fallback only (G5; absent in
  the port → the deterministic tiers still work)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kakaku.methods.ingest :as ingest]))

(def jsonld-page
  (str "\n<html><head>\n"
       "<script type=\"application/ld+json\">\n"
       "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Vacuum Bottle 500ml\",\n"
       " \"offers\":{\"@type\":\"Offer\",\"price\":\"3200\",\"priceCurrency\":\"JPY\",\n"
       "           \"availability\":\"https://schema.org/InStock\"}}\n"
       "</script></head><body>...</body></html>\n"))

(def meta-page
  (str "\n<html><head>\n"
       "<meta property=\"og:title\" content=\"Thermo Mug &amp; Lid\"/>\n"
       "</head><body><span class=\"price\">¥1,280</span></body></html>\n"))

;; ── JSON-LD tier ──────────────────────────────────────────────────────────
(deftest test-jsonld-extracts-price-currency-availability
  (let [o (ingest/extract-offer jsonld-page)]
    (is (= 320000 (get o "price")))          ; 3200 JPY → minor units ×100
    (is (= "JPY" (get o "currency")))
    (is (= "in-stock" (get o "availability")))
    (is (= "Vacuum Bottle 500ml" (get o "name")))
    (is (= true (get o "extracted")))))

(deftest test-availability-normalization
  (is (= "out-of-stock" (ingest/normalize-availability "https://schema.org/OutOfStock")))
  (is (= "preorder" (ingest/normalize-availability "PreOrder")))
  (is (= "unknown" (ingest/normalize-availability nil))))

;; ── meta/regex tier ───────────────────────────────────────────────────────
(deftest test-meta-fallback-title-and-price
  (let [o (ingest/extract-offer meta-page)]
    (is (= "Thermo Mug & Lid" (get o "name")))   ; html-unescaped
    (is (= 128000 (get o "price")))              ; ¥1,280 → minor
    (is (= "JPY" (get o "currency")))))

;; ── selector tier ─────────────────────────────────────────────────────────
(deftest test-selector-profile-extraction
  (let [content "<div id=\"p\">PRICE: 4980 yen</div><h1 id=\"t\">Steel Kettle</h1>"
        prof {"price" "PRICE:\\s*([0-9]+)" "name" "<h1[^>]*>(.*?)</h1>"}
        o (ingest/extract-offer content prof false)]
    (is (= 498000 (get o "price")))
    (is (= "Steel Kettle" (get o "name")))))

;; ── affiliate stripping (G3) ──────────────────────────────────────────────
(deftest test-strip-affiliate-params
  (let [clean (ingest/strip-affiliate "https://shop.example/p/123?tag=aff-22&utm_source=x&color=blue")]
    (is (and (not (str/includes? clean "tag=")) (not (str/includes? clean "utm_source"))))
    (is (str/includes? clean "color=blue"))))

;; ── G11 operator-gated fetch ──────────────────────────────────────────────
(deftest test-live-fetch-refused-without-operator-g11
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p?tag=aff" nil)]
    (is (= "fetch-gated" (get out "state")))
    (is (str/includes? (get out "reason") "G11"))
    (is (not (str/includes? (get out "productUrl") "tag=")))))   ; affiliate stripped on gated path

(deftest test-ingest-with-prefetched-content-extracts
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p?utm_source=x" jsonld-page)]
    (is (= "extracted" (get out "state")))
    (is (= 320000 (get out "price")))
    (is (not (str/includes? (get out "productUrl") "utm_source")))))

(deftest test-incomplete-content-marked
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p" "<html>no price</html>")]
    (is (= "incomplete" (get out "state")))
    (is (= false (get out "extracted")))))
