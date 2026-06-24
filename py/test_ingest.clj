#!/usr/bin/env bb
;; Working Clojure port of py/test_ingest.py.
(ns kakaku.py.test-ingest
  "kakaku 価格 — offer ingest extraction tests (ingest.clj port).

  Verifies the tiered extraction (JSON-LD → selector → meta/regex) and the constitutional
  gates: live fetch is operator-gated (G11), source URLs are affiliate-stripped (G3), and
  the Murakumo LLM is a fallback only (G5; absent in dev → deterministic tiers still work).

  Run:  bb --classpath 20-actors 20-actors/kakaku/py/test_ingest.clj"
  (:require [kakaku.py.ingest :as ingest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private jsonld-page
  "<html><head>
<script type=\"application/ld+json\">
{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Vacuum Bottle 500ml\",
 \"offers\":{\"@type\":\"Offer\",\"price\":\"3200\",\"priceCurrency\":\"JPY\",
            \"availability\":\"https://schema.org/InStock\"}}
</script></head><body>...</body></html>")

(def ^:private meta-page
  "<html><head>
<meta property=\"og:title\" content=\"Thermo Mug &amp; Lid\"/>
</head><body><span class=\"price\">¥1,280</span></body></html>")

;; ── JSON-LD tier ──────────────────────────────────────────────────────────

(deftest jsonld-extracts-price-currency-availability
  (let [o (ingest/extract-offer jsonld-page)]
    (is (= (:price o) 320000))           ; 3200 JPY → minor units ×100
    (is (= (:currency o) "JPY"))
    (is (= (:availability o) "in-stock"))
    (is (= (:name o) "Vacuum Bottle 500ml"))
    (is (true? (:extracted o)))))

(deftest availability-normalization
  (is (= (ingest/normalize-availability "https://schema.org/OutOfStock") "out-of-stock"))
  (is (= (ingest/normalize-availability "PreOrder") "preorder"))
  (is (= (ingest/normalize-availability nil) "unknown")))

;; ── meta/regex tier ───────────────────────────────────────────────────────

(deftest meta-fallback-title-and-price
  (let [o (ingest/extract-offer meta-page)]
    (is (= (:name o) "Thermo Mug & Lid"))  ; html-unescaped
    (is (= (:price o) 128000))             ; ¥1,280 → minor
    (is (= (:currency o) "JPY"))))

;; ── selector tier ─────────────────────────────────────────────────────────

(deftest selector-profile-extraction
  (let [content "<div id=\"p\">PRICE: 4980 yen</div><h1 id=\"t\">Steel Kettle</h1>"
        prof    {"price" "PRICE:\\s*([0-9]+)" "name" "<h1[^>]*>(.*?)</h1>"}
        o       (ingest/extract-offer content prof)]
    (is (= (:price o) 498000))
    (is (= (:name o) "Steel Kettle"))))

;; ── affiliate stripping (G3) ──────────────────────────────────────────────

(deftest strip-affiliate-params
  (let [dirty "https://shop.example/p/123?tag=aff-22&utm_source=x&color=blue"
        clean (ingest/strip-affiliate dirty)]
    (is (not (str/includes? clean "tag=")))
    (is (not (str/includes? clean "utm_source")))
    (is (str/includes? clean "color=blue"))))

;; ── G11 operator-gated fetch ──────────────────────────────────────────────

(deftest live-fetch-refused-without-operator-g11
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p?tag=aff")]
    (is (= (:state out) "fetch-gated"))
    (is (str/includes? (:reason out) "G11"))
    (is (not (str/includes? (:productUrl out) "tag=")))))  ; affiliate stripped even on gated path

(deftest ingest-with-prefetched-content-extracts
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p?utm_source=x"
                                          jsonld-page)]
    (is (= (:state out) "extracted"))
    (is (= (:price out) 320000))
    (is (not (str/includes? (:productUrl out) "utm_source")))))

(deftest incomplete-content-marked
  (let [out (ingest/ingest-offer-from-url "https://shop.example/p" "<html>no price</html>")]
    (is (= (:state out) "incomplete"))
    (is (false? (:extracted out)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests 'kakaku.py.test-ingest)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
