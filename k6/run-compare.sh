#!/usr/bin/env bash
# 현재 적재 규모 N에서 A와 F_HMAC 로그인을 차례로 측정한다 (두 구성이 같은 결과 폴더를 공유).
#
# 사용법:  ./k6/run-compare.sh <N> [ITER_A] [ITER_F]
#   예)    ./k6/run-compare.sh 10000              # A 200회, F_HMAC 200회
#          ./k6/run-compare.sh 1000000 20 200     # A는 느리니 20회만
#
# 전제: ./load.sh a <N> 과 ./load.sh f_hmac <N> 으로 두 테이블에 같은 N건이 적재되어 있어야 한다.
# 결과: results/<시각>_N<N>/ (env.txt, a/, f_hmac/) + results/index.csv — 자세한 내용은 k6/run-one.sh 참고
set -uo pipefail
cd "$(dirname "$0")/.."

N="${1:?사용법: $0 <N> [ITER_A] [ITER_F]}"
ITER_A="${2:-200}"
ITER_F="${3:-200}"
RUN_DIR="results/$(date +%Y%m%d-%H%M%S)_N${N}"

# 두 구성이 같은 샘플(같은 seq, 같은 순서)을 쓰도록 큰 쪽 기준으로 한 번만 만든다
# (샘플 0~4는 워밍업용, 측정은 6번째부터 — 워밍업 횟수는 run-one.sh 참고: A는 1만 5·10만 3·100만 2·1000만 1회)
need=$(( (ITER_A > ITER_F ? ITER_A : ITER_F) + 5 ))
SAMPLE="k6/data/sample-${N}.csv"
if [ ! -f "$SAMPLE" ] || [ "$(($(wc -l < "$SAMPLE") - 1))" -lt "$need" ]; then
  ./k6/make-sample.sh "$N" "$need" || exit 1
fi

rc=0
./k6/run-one.sh a      "$N" "$ITER_A" "$RUN_DIR" || rc=$?
./k6/run-one.sh f_hmac "$N" "$ITER_F" "$RUN_DIR" || rc=$?

echo
echo "[run-compare] 결과 폴더: $RUN_DIR"
echo "[run-compare] 전체 이력 (최근 2줄):"
tail -n 2 results/index.csv | cut -d, -f1-13
exit "$rc"
