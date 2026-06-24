(ns kakaku.kotoba.test-ingest-mcp
  "kakaku 価格 — ingest_mcp.cljc tests. ADR-2605091200.
  Port of the `ingest_mcp.py` behaviour (the .py has no Python test file of its own;
  these assertions mirror its scanners + CLI surface, and are pinned against the
  committed `seed.edn`: 10 entities / ~72 datoms — the exact numbers the Python
  `--dry-run` prints). No CID parity here: ingest_mcp does NOT content-address
  (it is a textual seed scan, not the kotoba.datom commit-DAG)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kakaku.kotoba.ingest-mcp :as mcp]))

;; ── strip-comments ────────────────────────────────────────────────────────

(deftest strip-comments-drops-line-comments
  ;; the scan stops AT the ';' (leaving the space before it), like the Python port
  (is (= "abc \n" (mcp/strip-comments "abc ; trailing comment\n")))
  (is (= "\nkeep" (mcp/strip-comments ";; full line\nkeep"))))

(deftest strip-comments-keeps-semicolon-inside-strings
  (is (= "\"a;b\" " (mcp/strip-comments "\"a;b\" ; gone")))
  ;; an escaped quote does not close the string, so the ; inside stays
  (is (= "\"a\\\";b\" " (mcp/strip-comments "\"a\\\";b\" ; gone"))))

;; ── top-level-entities ────────────────────────────────────────────────────

(deftest top-level-entities-splits-outer-vector
  (let [s "[ {:a 1} {:b 2 :c {:nested 3}} ]"]
    (is (= ["{:a 1}" "{:b 2 :c {:nested 3}}"] (mcp/top-level-entities s)))))

(deftest top-level-entities-ignores-braces-in-strings
  (let [s "[ {:url \"http://x/{y}\"} ]"]
    (is (= ["{:url \"http://x/{y}\"}"] (mcp/top-level-entities s)))))

(deftest top-level-entities-strips-comments-first
  (let [s "[ ;; a comment with { brace\n {:a 1} ]"]
    (is (= ["{:a 1}"] (mcp/top-level-entities s)))))

(deftest top-level-entities-empty-when-no-vector
  (is (= [] (mcp/top-level-entities "no brackets here")))
  (is (= [] (mcp/top-level-entities ""))))

;; ── count-datoms heuristic (Python parity) ────────────────────────────────

(deftest count-datoms-matches-python-heuristic
  ;; "{:" leading attr counts 1; each subsequent " :" counts 1.
  (is (= 1 (mcp/count-datoms "{:a 1}")))                 ;; just the leading {:
  (is (= 2 (mcp/count-datoms "{:a 1 :b 2}")))            ;; {: + one " :"
  (is (= 1 (mcp/count-datoms "{ :a 1}")))                ;; no "{:" start, but one " :a"
  (is (= 3 (mcp/count-datoms "{:a :kw :b 2}"))))         ;; {: + " :kw" + " :b"

;; ── summarize against the committed seed ──────────────────────────────────

(def ^:private seed-raw
  (slurp (clojure.java.io/file "20-actors/kakaku/kotoba/seed.edn")))

(deftest summarize-seed-counts-match-python
  (let [{:keys [n-entities n-datoms]} (mcp/summarize seed-raw)]
    (is (= 10 n-entities))   ;; same as `ingest_mcp.py --dry-run`
    (is (= 72 n-datoms))))

;; ── parse-args (argparse port) ────────────────────────────────────────────

(deftest parse-args-defaults
  (let [o (mcp/parse-args [])]
    (is (= "http://127.0.0.1:8077" (:url o)))
    (is (= "com.etzhayyim.kakaku" (:graph o)))
    (is (= "mcp" (:via o)))
    (is (false? (:dry-run o)))))

(deftest parse-args-space-and-equals-and-flag
  (let [o (mcp/parse-args ["--graph" "g1" "--url=http://h:9" "--dry-run"])]
    (is (= "g1" (:graph o)))
    (is (= "http://h:9" (:url o)))
    (is (true? (:dry-run o)))))

;; ── plan (dry-run vs live; G11 outward gate) ──────────────────────────────

(deftest plan-dry-run-when-flag
  (let [r (mcp/plan seed-raw (mcp/parse-args ["--dry-run"]) true)]
    (is (= :dry-run (:state r)))
    (is (= 10 (:n-entities r)))
    (is (= 72 (:n-datoms r)))
    (is (some #(str/includes? % "DRY RUN") (:lines r)))))

(deftest plan-dry-run-when-no-token
  (let [r (mcp/plan seed-raw (mcp/parse-args []) false)]
    (is (= :dry-run (:state r)))))

(deftest plan-live-requested-with-token-and-no-dry-run
  (let [r (mcp/plan seed-raw (mcp/parse-args ["--graph" "g2"]) true)]
    (is (= :live-requested (:state r)))      ;; G11 scaffold — never a silent outward write
    (is (= "g2" (:graph r)))
    (is (some #(str/includes? % "G11") (:lines r)))))

(deftest plan-parsed-line-mentions-graph-and-counts
  (let [r (mcp/plan seed-raw (mcp/parse-args ["--graph" "myg"]) false)]
    (is (some #(and (str/includes? % "myg")
                    (str/includes? % "10 entities")
                    (str/includes? % "72 datoms"))
              (:lines r)))))
