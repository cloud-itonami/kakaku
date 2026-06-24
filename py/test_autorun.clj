(ns kakaku.py.test-autorun
  "kakaku 価格 — autonomous price-difference / supply-demand heartbeat + kotoba Datom-log
  invariants (clojure.test). clj-native SSoT (ADR-2606142300 D1) + ADR-2605091200.

  Guards: one content-addressed tx per beat to an append-only verifiable commit-DAG; deterministic
  / resume-safe (same cycles → same CIDs); tamper detected; G2 non-speculative (no signal/forecast/
  buy-sell/price-target attr; intent is buyer-transparency, reading is an observation); G5 every
  derived observation carries :sourcing :synthesized; append-only :db/add; frozen golden head-CID."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [kakaku.py.autorun :as autorun]
            [kakaku.py.kotoba :as k]))

(def ^:private seed-path (str (io/file "20-actors/kakaku/kotoba/seed.edn")))

(defn- tmp [] (let [f (java.io.File/createTempFile "kakaku-test" ".edn")] (.delete f) (str f)))

(deftest heartbeat-persists
  (let [log (tmp)]
    (try
      (let [res (autorun/run-autonomous 3 seed-path log)]
        (is (= 3 (:log-length res)) "one tx per beat")
        (is (every? #(pos? (:datoms %)) (:beats res)) "every beat persists observations")
        (is (:ok (:chain res)) "commit-DAG verifies")
        (is (str/starts-with? (:head-cid res) "b") "head CID is content-addressed")
        ;; spread is a price DIFFERENCE — a non-negative observation
        (is (every? #(>= (:spread %) 0) (:beats res)) "spread is a non-negative price difference"))
      (finally (.delete (io/file log))))))

(deftest deterministic-resume-safe
  (let [a (tmp) b (tmp)]
    (try
      (is (= (map :cid (:beats (autorun/run-autonomous 3 seed-path a)))
             (map :cid (:beats (autorun/run-autonomous 3 seed-path b))))
          "same cycles → same CIDs")
      (finally (.delete (io/file a)) (.delete (io/file b))))))

(deftest append-only-and-tamper
  (let [log (tmp)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (autorun/run-cycle 2 seed-path log)
      (is (= 2 (count (k/read-log log))) "two beats append")
      (is (= (get (second (k/read-log log)) ":tx/prev") (get (first (k/read-log log)) ":tx/cid"))
          "tx 2 links tx 1 (commit-DAG)")
      (spit log (str/replace (slurp log) ":kakaku.obs/spread 700" ":kakaku.obs/spread 1"))
      (is (false? (:ok (k/verify-chain log))) "tamper detected")
      (finally (.delete (io/file log))))))

(deftest g2-non-speculative
  (let [log (tmp)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (let [tx (first (k/read-log log))
            datoms (get tx ":tx/datoms")
            attrs (set (map #(str (nth % 2)) datoms))
            ops (set (map first datoms))
            intent (some (fn [d] (when (= ":kakaku.obs/intent" (nth d 2)) (nth d 3))) datoms)
            reading (some (fn [d] (when (= ":kakaku.obs/reading" (nth d 2)) (nth d 3))) datoms)]
        (doseq [forbidden [":kakaku.obs/signal" ":kakaku.obs/forecast" ":kakaku.obs/buy"
                           ":kakaku.obs/sell" ":kakaku.obs/buy-sell" ":kakaku.obs/price-target"
                           ":kakaku.obs/recommendation" ":signal" ":trade" ":forecast"]]
          (is (not (contains? attrs forbidden)) (str "no speculative attr `" forbidden "` (G2)")))
        (is (= "buyer-transparency+supply-resilience" intent) "intent is buyer-transparency (G2)")
        (is (contains? #{":scarcity" ":glut" ":balanced"} reading)
            "reading is a bounded observation, not a signal")
        (is (= #{":db/add"} ops) "every datom is append-only :db/add"))
      (finally (.delete (io/file log))))))

(deftest g5-derived-synthesized
  (let [log (tmp)]
    (try
      (autorun/run-cycle 1 seed-path log)
      (let [datoms (get (first (k/read-log log)) ":tx/datoms")
            by-e (reduce (fn [m d] (assoc-in m [(nth d 1) (nth d 2)] (nth d 3))) {} datoms)
            obs (filter (fn [[_ at]] (some #(str/starts-with? (str %) ":kakaku.obs/") (keys at))) by-e)
            regs (filter (fn [[_ at]] (some #(str/starts-with? (str %) ":kakaku.region/") (keys at))) by-e)]
        (is (seq obs) "observation entity persisted")
        (doseq [[e at] obs]
          (is (= ":synthesized" (get at ":kakaku.obs/sourcing")) (str e " is :synthesized (G5)")))
        (doseq [[e at] regs]
          (is (= ":synthesized" (get at ":kakaku.region/sourcing")) (str e " region is :synthesized (G5)"))))
      (finally (.delete (io/file log))))))

(deftest cid-golden-stable
  (let [log (tmp)]
    (try
      (autorun/run-autonomous 3 seed-path log)
      (is (= "be5e5aa20d120199328ddde2dd8a404267e91d250b3c12ed9c9b5162409e68b40"
             (k/head-cid log)) "head CID stays byte-stable (frozen golden value)")
      (finally (.delete (io/file log))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [r (run-tests 'kakaku.py.test-autorun)]
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
