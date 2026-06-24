#!/usr/bin/env bash
# kakaku 価格 — kotoba deploy (self-driving)
# ADR-2605091200
#
# Runs the agent test gate, ingests schema-shaped seed datoms into a running kotoba
# node, and (optionally) builds the langgraph WASM actor. Writes to the canonical
# Datom journal require an authorized operator session token (no-server-key posture,
# substrate boundary). Without KOTOBA_TOKEN the ingest is a dry-run. Live retailer
# scraping ingest and live social broadcast stay G11-gated (not performed here).
#
# Usage:
#   KOTOBA_URL=http://127.0.0.1:8077 KOTOBA_TOKEN=<at-session-jwt> ./deploy.sh
#   SKIP_TESTS=1 ./deploy.sh        # skip the test gate (not recommended)
set -euo pipefail

KOTOBA_URL="${KOTOBA_URL:-http://127.0.0.1:8077}"
GRAPH="${KAKAKU_GRAPH:-com.etzhayyim.kakaku}"
ACTOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> kakaku kotoba deploy → ${KOTOBA_URL} (graph ${GRAPH})"

# 0. test gate — deploy is blocked on a green suite (deploy-autonomy invariant).
# Runs the full suite (agent + offer-ingest + viz) via run_tests.sh.
if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  echo "--> full test gate"
  bash "${ACTOR_DIR}/run_tests.sh"
fi

# 1. health
if ! curl -fsS -m 5 "${KOTOBA_URL}/health" >/dev/null 2>&1; then
  echo "!! kotoba node not reachable at ${KOTOBA_URL} — start it with: kotoba serve" >&2
  echo "   (seed parse + test gate still ran above)"
  exit 1
fi

# 2. seed ingest
if [[ -z "${KOTOBA_TOKEN:-}" ]]; then
  echo "--> KOTOBA_TOKEN unset → DRY RUN (no writes). Set an operator AT-session-JWT to ingest."
  python3 "${ACTOR_DIR}/kotoba/ingest_mcp.py" --url "${KOTOBA_URL}" --graph "${GRAPH}" --dry-run
else
  echo "--> ingesting seed datoms via MCP (operator token present)"
  KOTOBA_TOKEN="${KOTOBA_TOKEN}" python3 "${ACTOR_DIR}/kotoba/ingest_mcp.py" \
    --url "${KOTOBA_URL}" --graph "${GRAPH}" --via mcp
  echo "--> sealing hot arrangement (kotoba commit)"
  kotoba --url "${KOTOBA_URL}" --token "${KOTOBA_TOKEN}" commit
fi

# 3. wasm actor build
echo "--> langgraph actor build (componentize-py)"
if command -v componentize-py >/dev/null 2>&1; then
  ( cd "${ACTOR_DIR}/py" && componentize-py -w kotoba-actor componentize agent -o agent.wasm )
  echo "    built py/agent.wasm — deploy via the node's invoke.run with an operator token"
else
  echo "    (componentize-py absent — skipping wasm build)"
fi

echo "==> done"
