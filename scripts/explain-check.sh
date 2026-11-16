#!/usr/bin/env bash
# 계획서 7장/9장: 구성별 EXPLAIN (ANALYZE, BUFFERS) 결과를 저장한다.
# 사용: ./scripts/explain-check.sh <config> <email>
#   예: ./scripts/explain-check.sh c user12345@bench.example.com
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="${1:?사용법: explain-check.sh <config> <email>}"
EMAIL="${2:?사용법: explain-check.sh <config> <email>}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"

mkdir -p results
OUT="results/explain-${CONFIG}-$(date +%Y%m%d-%H%M%S).json"

curl -sf --get --data-urlencode "email=${EMAIL}" "${APP_BASE_URL}/api/admin/${CONFIG}/explain" | tee "$OUT"
echo
echo "[explain-check] saved -> $OUT"
