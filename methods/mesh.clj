;; mesh.clj — kakaku 価格 KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:kakaku (price observatory). Observatory on-kse
;; pattern (ADR-2606230001 §4): observes venue→product price edges as Datom
;; assertions and derives price-spread concentration via Datalog → feeds meyasu.
;; The full price-series analysis stays in the actor's existing methods.
;;
;; Posture: disclosed price LEVELS are facts, never a trade/forecast/signal;
;; observation-only (meyasu fuses, kakaku observes).
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns kakaku)

(defn observe []
  ;; observe — disclosed prices per product across venues (a fact, never a signal).
  (kqe-assert! "kakaku" "venue-a" "prices" "product-x")
  (kqe-assert! "kakaku" "venue-b" "prices" "product-x")
  (kqe-assert! "kakaku" "venue-c" "prices" "product-y")
  ;; derive — price-spread concentration per product (Datalog).
  (kqe-query "spread(?p) :- prices(?p)."))

(defn run [ctx] (observe))
(defn on-kse [topic payload] (observe))
