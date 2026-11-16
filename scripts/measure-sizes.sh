#!/usr/bin/env bash
# 계획서 9장 "저장: 테이블·인덱스 크기" 기록. /api/admin/{config}/size를 모든 구성에 대해 호출해
# results/sizes-<timestamp>.csv로 저장한다.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
CONFIGS=(a b c d e f c_hmac d_hmac e_hmac f_hmac)

mkdir -p results
OUT="results/sizes-$(date +%Y%m%d-%H%M%S).csv"
echo "config,totalBytes" > "$OUT"

for cfg in "${CONFIGS[@]}"; do
  JSON="$(curl -sf "${APP_BASE_URL}/api/admin/${cfg}/size")"
  BYTES="$(echo "$JSON" | sed -n 's/.*"totalBytes":\([0-9]*\).*/\1/p')"
  echo "${cfg},${BYTES}" >> "$OUT"
  echo "$JSON"
done

echo "[measure-sizes] saved -> $OUT"
