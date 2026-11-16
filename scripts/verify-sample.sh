#!/usr/bin/env bash
# 계획서 6장 "적재 절차 1. 100건으로 생성기를 검증한다 (복호화 성공 여부, 해시가 서비스
# 코드와 동일한지)". 10가지 구성 전체에 대해 로그인 라운드트립으로 확인한다.
#
# 사용: ./scripts/verify-sample.sh
# 전제: 서버가 떠 있을 것 (./mvnw spring-boot:run 또는 java -jar target/ahbench.jar)
set -euo pipefail
cd "$(dirname "$0")/.."

APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
DUMMY_PASSWORD="${DUMMY_PASSWORD:-BenchPassword123!}"
SAMPLE_SIZE=100
CONFIGS=(a b c d e f c_hmac d_hmac e_hmac f_hmac)

echo "[verify-sample] 1) ${SAMPLE_SIZE}건 생성 (./data-sample)"
rm -rf data-sample
mkdir -p data-sample
java -jar target/ahbench.jar \
  --spring.profiles.active=generate \
  --bench.generator.total-rows="${SAMPLE_SIZE}" \
  --bench.generator.chunk-size="${SAMPLE_SIZE}" \
  --bench.generator.thread-count=2 \
  --bench.generator.output-dir=./data-sample \
  --bench.encryption.key="${AES_ENCRYPTION_KEY:?.env에 AES_ENCRYPTION_KEY 설정}" \
  --bench.hmac.key="${HMAC_SECRET_KEY:?.env에 HMAC_SECRET_KEY 설정}" \
  > /tmp/verify-sample-gen.log 2>&1

CSV="data-sample/users_000.csv"
if [ ! -f "$CSV" ]; then
  echo "생성된 CSV를 찾지 못했습니다: $CSV (로그: /tmp/verify-sample-gen.log)" >&2
  exit 1
fi

echo "[verify-sample] 2) 임시로 ./data/users_000.csv 자리에 복사해 load-data.sh로 적재"
mkdir -p data
cp "$CSV" data/users_000.csv.verify-backup-if-exists 2>/dev/null || true
if [ -f data/users_000.csv ]; then
  mv data/users_000.csv data/users_000.csv.bak
fi
cp "$CSV" data/users_000.csv

./scripts/load-data.sh "$SAMPLE_SIZE" "$SAMPLE_SIZE"

echo "[verify-sample] 3) ${#CONFIGS[@]}개 구성 × 샘플 이메일로 로그인 라운드트립 확인"
FAIL=0
# CSV: seq,email,email_hash_sha256,email_hash_hmac,email_aes256 (헤더 1줄 skip, 5개만 샘플)
SAMPLE_EMAILS=$(tail -n +2 "$CSV" | cut -d',' -f2 | head -n 5)

for cfg in "${CONFIGS[@]}"; do
  for email in $SAMPLE_EMAILS; do
    RESP="$(curl -sf -X POST "${APP_BASE_URL}/api/bench/${cfg}/login" \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"${email}\",\"password\":\"${DUMMY_PASSWORD}\"}" || echo '{"success":false}')"
    SUCCESS="$(echo "$RESP" | sed -n 's/.*"success":\(true\|false\).*/\1/p')"
    RETURNED_EMAIL="$(echo "$RESP" | sed -n 's/.*"email":"\([^"]*\)".*/\1/p')"
    if [ "$SUCCESS" != "true" ] || [ "$RETURNED_EMAIL" != "$email" ]; then
      echo "  [FAIL] config=${cfg} email=${email} resp=${RESP}"
      FAIL=1
    fi
  done
  echo "  [ok] config=${cfg} 5건 확인"
done

echo "[verify-sample] 4) 원래 데이터 복원"
rm -f data/users_000.csv
if [ -f data/users_000.csv.bak ]; then
  mv data/users_000.csv.bak data/users_000.csv
fi
rm -f data/users_000.csv.verify-backup-if-exists

if [ "$FAIL" -eq 0 ]; then
  echo "[verify-sample] 모든 구성에서 복호화·해시 일치 확인 완료 (PASS)."
else
  echo "[verify-sample] 일부 구성에서 실패했습니다. 위 로그를 확인하세요 (FAIL)." >&2
  exit 1
fi
