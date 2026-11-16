#!/usr/bin/env bash
# 계획서 8장 "규모별 실행 계획" 표를 그대로 도는 러너.
# 사용: ./k6/run-all.sh <scenario.js> [config1 config2 ...]
#   예: ./k6/run-all.sh s1_login.js c e           # 필수 구성만
#       ./k6/run-all.sh s1_login.js                # 전체 10개 구성
#
# 결과는 k6 --out json=... 으로 results/ 아래에 구성별로 저장한다.
# 계획서 8장: "같은 조건을 3회 반복하고 중앙값을 쓴다" — RUNS 환경변수로 반복 횟수 지정.
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO="${1:?사용법: run-all.sh <scenario.js> [config...]}"
shift || true
CONFIGS=("$@")
if [ ${#CONFIGS[@]} -eq 0 ]; then
  CONFIGS=(a b c d e f c_hmac d_hmac e_hmac f_hmac)
fi

RUNS="${RUNS:-3}"
mkdir -p results

for cfg in "${CONFIGS[@]}"; do
  for run in $(seq 1 "$RUNS"); do
    TS="$(date +%Y%m%d-%H%M%S)"
    OUT="results/${SCENARIO%.js}-${cfg}-run${run}-${TS}"
    echo "=== ${SCENARIO} config=${cfg} run=${run}/${RUNS} ==="
    k6 run --env CONFIG="${cfg}" --summary-export="${OUT}.summary.json" "k6/${SCENARIO}" \
      | tee "${OUT}.log"
  done
done

echo "[run-all] 완료. results/ 에서 .summary.json의 p50/p95/p99를 모아 중앙값을 계산하세요."
