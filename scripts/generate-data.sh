#!/usr/bin/env bash
# 계획서 6장 "데이터 생성" 실행 래퍼.
# 사용: ./scripts/generate-data.sh [TOTAL_ROWS] [CHUNK_SIZE] [THREADS]
#
# 10만 건으로 먼저 돌려 실제 소요 시간을 측정한 뒤 1000만 건 전체를 돌리는 것을 권장
# (계획서 6장 "예상 소요 시간" 절). 서버와 생성기는 반드시 같은 encryption.key/hmac.key를 써야
# 한다 — 아래 환경변수를 .env와 동일하게 맞춰서 실행할 것.
set -euo pipefail
cd "$(dirname "$0")/.."

TOTAL_ROWS="${1:-10000000}"
CHUNK_SIZE="${2:-1000000}"
THREADS="${3:-8}"

if [ -f .env ]; then
  set -a; source .env; set +a
fi

echo "[generate-data] total=${TOTAL_ROWS} chunk=${CHUNK_SIZE} threads=${THREADS}"

java -jar target/ahbench.jar \
  --spring.profiles.active=generate \
  --bench.generator.total-rows="${TOTAL_ROWS}" \
  --bench.generator.chunk-size="${CHUNK_SIZE}" \
  --bench.generator.thread-count="${THREADS}" \
  --bench.generator.output-dir=./data \
  --bench.encryption.key="${AES_ENCRYPTION_KEY:?AES_ENCRYPTION_KEY를 .env에 설정하세요}" \
  --bench.hmac.key="${HMAC_SECRET_KEY:?HMAC_SECRET_KEY를 .env에 설정하세요}"
