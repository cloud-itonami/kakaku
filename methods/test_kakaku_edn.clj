#!/usr/bin/env bb
;; Clojure test for methods/kakaku_edn.cljc — reader + classify parity with
;; kakaku_edn.py (no Python test existed for kakaku_edn; this is fresh coverage).
(ns kakaku.methods.test-kakaku-edn
  "Guards the kakaku 価格 EDN reader + seed classifier against the kakaku_edn.py
  baseline: products 1 / merchants 3 / offers 3 / price-history 3 on the canonical
  seed (kotoba/seed.edn), keyword-value stripping (:jp → \"jp\"), offer merchantId
  split off the offer id, and numeric typing (reputation 0.9 double).

  Run:  bb --classpath 20-actors 20-actors/kakaku/methods/test_kakaku_edn.clj"
  (:require [kakaku.methods.kakaku-edn :as ke]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private this-file *file*)
(defn- actor-root [] (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile))
(defn- seed-path [] (str (io/file (actor-root) "kotoba" "seed.edn")))

(deftest reads-and-classifies-seed
  (let [rows (ke/read-file (seed-path))
        {:keys [products merchants offers price-history]} (ke/classify rows)]
    (is (vector? rows) "top-level seed form is a vector")
    (is (= 1 (count products))   "1 product (parity with kakaku_edn.py)")
    (is (= 3 (count merchants))  "3 merchants")
    (is (= 3 (count offers))     "3 offers")
    (is (= 3 (count price-history)) "3 price-history rows")))

(deftest merchant-keyword-values-stripped
  (let [{:keys [merchants]} (ke/classify (ke/read-file (seed-path)))
        a (get merchants "a_com")]
    (is (= "jp" (get a "region"))            ":jp keyword stripped to \"jp\"")
    (is (= "active" (get a "status"))        ":active → \"active\"")
    (is (= 0.9 (get a "reputationScore"))    "reputation parsed as double 0.9")
    (is (= "A Store" (get a "name")))))

(deftest offer-merchant-id-split
  (let [{:keys [offers]} (ke/classify (ke/read-file (seed-path)))
        o (first (sort-by #(get % "offerId") offers))]
    (is (= "a_com" (get o "merchantId"))     "merchantId = offerId split on ':'")
    (is (= "in-stock" (get o "availability")) ":in-stock keyword stripped")))

(deftest classify-ignores-non-maps
  ;; comments are stripped by the reader, but classify must also no-op on stray non-maps
  (let [{:keys [products]} (ke/classify [42 "x" {:product/id-not "y"}
                                         {":product/id" "p1" ":product/name" "N"}])]
    (is (= 1 (count products)))
    (is (= "N" (get-in products ["p1" "name"])))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kakaku.methods.test-kakaku-edn)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
