#!/usr/bin/env bash
# kakaku 価格 — run the whole cljc test suite with one command.
# The Python agent/ingest/viz/ingest_mcp + tests were pruned once fully ported to .cljc (clj-port
# migration, ADR-2606160842); the cljc namespaces are the SSoT. Runs them via babashka from the
# repo root (bb.edn :paths includes 20-actors). Exits non-zero on any failure (deploy-gate friendly).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

bb -e "(def nss '(kakaku.methods.test-kakaku-edn
                  kakaku.kotoba.test-ingest-mcp
                  kakaku.py.test-agent
                  kakaku.py.test-ingest
                  kakaku.viz.test-build-viz))
       (apply require nss)
       (let [r (apply clojure.test/run-tests nss)]
         (println \"==> kakaku:\" (select-keys r [:test :pass :fail :error]))
         (System/exit (if (or (pos? (:fail r)) (pos? (:error r))) 1 0)))"
