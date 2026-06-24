(ns kakaku.kotoba.ingest-mcp
  "kakaku 価格 — ingest seed.edn into a live kotoba node via MCP. ADR-2605091200.
  Clojure port of `20-actors/kakaku/kotoba/ingest_mcp.py`.

  Flattens each seed entity map into datoms and (live path) asserts them via the
  `kotoba_datom_create` MCP tool; `kotoba commit` seals them. Writes require an
  operator AT-session JWT (no-server-key posture). `--dry-run` parses + counts
  datoms only (no writes, no LLM; G5 untouched). Live retailer ingest is a
  separate G11-gated path.

  This module does NOT content-address: `ingest_mcp.py` is a naive textual scan of
  the committed `seed.edn` (a hand-edited EDN vector of maps), counting top-level
  `{…}` entities and a `\" :\"`-substring datom heuristic — it is NOT the
  canonical-JSON+sha256 commit-DAG of `kotoba.datom`, so that module is NOT reused
  here (no CID parity to assert). The port mirrors the Python scanners byte-for-byte.

  Pure fns (strip-comments / top-level-entities / count-datoms / summarize); I/O
  (file read, argv, stdout) at the #?(:clj) edge. argparse → a plain opts map in
  -main (no eval). Closed posture: the live ingest path is a G11 scaffold that
  returns a structured result rather than performing an outward write."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

;; ── seed path (Python `SEED = os.path.join(dirname(__file__), "seed.edn")`) ──

#?(:clj
   (def seed-path
     "Repo-relative path to the committed seed.edn (resolved from bb's cwd = worktree root)."
     (str "20-actors/kakaku/kotoba/seed.edn")))

;; ── _strip_comments ───────────────────────────────────────────────────────

(defn strip-comments
  "Drop EDN line comments (`;` to end-of-line) but keep `;` inside string literals.
  Faithful port of `_strip_comments`: a tiny string-aware scanner (a backslash before
  a `\\\"` keeps the string open)."
  [^String s]
  (let [n (count s)]
    (loop [i 0, out (transient []), in-str false]
      (if (>= i n)
        (apply str (persistent! out))
        (let [c (.charAt s i)]
          (cond
            in-str
            (recur (inc i)
                   (conj! out c)
                   (if (and (= c \") (not= (.charAt s (dec i)) \\)) false true))

            (= c \")
            (recur (inc i) (conj! out c) true)

            (= c \;)
            ;; skip to (but not past) the newline
            (let [j (loop [k i] (if (and (< k n) (not= (.charAt s k) \newline)) (recur (inc k)) k))]
              (recur j out in-str))

            :else
            (recur (inc i) (conj! out c) in-str)))))))

;; ── _top_level_entities ───────────────────────────────────────────────────

(defn top-level-entities
  "Each top-level `{…}` map literal inside the outer `[ … ]` vector, in order.
  Faithful port of `_top_level_entities`: comments are stripped first, then a
  brace-depth scan (string-aware) yields each balanced top-level map, trimmed."
  [^String s]
  (let [s (strip-comments s)
        start (str/index-of s "[")]
    (if (or (nil? start) (neg? start))
      []
      (loop [cs (seq (subs s (inc start)))
             depth 0
             buf (transient [])
             in-str false
             out (transient [])]
        (if (empty? cs)
          (persistent! out)
          (let [c (first cs)
                rest* (rest cs)]
            (cond
              in-str
              (recur rest* depth (conj! buf c) (if (= c \") false true) out)

              (= c \")
              (recur rest* depth (conj! buf c) true out)

              (= c \{)
              (recur rest* (inc depth) (conj! buf c) in-str out)

              (= c \})
              (let [depth* (dec depth)
                    buf* (conj! buf c)]
                (if (zero? depth*)
                  (recur rest* depth* (transient []) in-str
                         (conj! out (str/trim (apply str (persistent! buf*)))))
                  (recur rest* depth* buf* in-str out)))

              (pos? depth)
              (recur rest* depth (conj! buf c) in-str out)

              :else
              (recur rest* depth buf in-str out))))))))

;; ── datom count heuristic (Python sum-expression) ─────────────────────────

(defn- count-substr
  "Number of non-overlapping occurrences of `sub` in `s` (Python `str.count`)."
  [^String s ^String sub]
  (loop [from 0, n 0]
    (let [idx (str/index-of s sub from)]
      (if (nil? idx)
        n
        (recur (+ idx (count sub)) (inc n))))))

(defn count-datoms
  "Approximate datom count for one entity string: `e.count(\" :\") + (1 if e.startswith(\"{:\"))`.
  Mirrors the Python heuristic exactly (the leading `{:` attr plus each subsequent ` :`)."
  [^String e]
  (+ (count-substr e " :")
     (if (str/starts-with? e "{:") 1 0)))

(defn summarize
  "Pure summary of a raw seed string: {:entities [..] :n-entities n :n-datoms n}."
  [^String raw]
  (let [entities (top-level-entities raw)]
    {:entities entities
     :n-entities (count entities)
     :n-datoms (reduce + 0 (map count-datoms entities))}))

;; ── arg parse (argparse → plain opts map; no eval) ────────────────────────

(def defaults
  {:url "http://127.0.0.1:8077"
   :graph "com.etzhayyim.kakaku"
   :via "mcp"
   :dry-run false})

(defn parse-args
  "Parse the ingest_mcp CLI argv into an opts map merged over `defaults`.
  Supports `--key value` and `--key=value`; `--dry-run` is a boolean flag."
  [args]
  (loop [args (seq args), opts defaults]
    (if (empty? args)
      opts
      (let [a (first args)]
        (cond
          (= a "--dry-run")
          (recur (rest args) (assoc opts :dry-run true))

          (str/starts-with? a "--")
          (let [[k v] (if (str/includes? a "=")
                        (let [i (str/index-of a "=")] [(subs a 2 i) (subs a (inc i))])
                        [(subs a 2) (second args)])
                kw (keyword k)]
            (if (str/includes? a "=")
              (recur (rest args) (assoc opts kw v))
              (recur (nnext args) (assoc opts kw v))))

          :else
          (recur (rest args) opts))))))

;; ── core ingest planning (pure; the outward write stays a G11 scaffold) ───

(defn plan
  "Pure ingest plan from a raw seed string + opts + whether an operator token is present.
  Returns the structured result `-main` would print/act on (no I/O, deterministic)."
  [raw {:keys [graph dry-run] :as _opts} token-present?]
  (let [{:keys [n-entities n-datoms]} (summarize raw)
        parsed-line (str "   parsed " n-entities " entities (~" n-datoms
                         " datoms) from seed.edn → " graph)]
    (if (or dry-run (not token-present?))
      {:state :dry-run
       :n-entities n-entities
       :n-datoms n-datoms
       :graph graph
       :lines [parsed-line
               "   DRY RUN — no writes. Set KOTOBA_TOKEN (operator AT-session JWT) to ingest."]}
      ;; live ingest path (operator token present) — assert via MCP kotoba_datom_create.
      ;; R0 scaffold: wire to the live MCP endpoint when the operator session is
      ;; provisioned (G11 gates real outward writes). Kept explicit to avoid a silent no-op.
      {:state :live-requested
       :n-entities n-entities
       :n-datoms n-datoms
       :graph graph
       :lines [parsed-line
               "   live ingest requested — implement MCP kotoba_datom_create wiring before use (G11)."]})))

;; ── I/O edge ──────────────────────────────────────────────────────────────

#?(:clj
   (defn -main [& args]
     (let [opts (parse-args args)
           raw (slurp (io/file seed-path))
           token-present? (boolean (not (str/blank? (or (System/getenv "KOTOBA_TOKEN") ""))))
           {:keys [lines]} (plan raw opts token-present?)]
       (doseq [l lines] (println l))
       0)))
