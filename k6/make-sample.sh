#!/usr/bin/env bash
# k6용 로그인 샘플 만들기: seq <= N 범위에서 K개를 무작위로 뽑아 k6/data/sample-<N>.csv 로 저장한다.
#
# 사용법:  ./k6/make-sample.sh <N> [K] [CSV_DIR]
#   예)    ./k6/make-sample.sh 10000            # 1만 건 중 200개
#          ./k6/make-sample.sh 10000000 50
#
#   N        현재 DB에 적재한 규모 (load.sh 에 넣은 N과 같아야 함)
#   K        뽑을 개수 (기본 200, N보다 크면 N)
#   CSV_DIR  생성기 출력 폴더 (기본 generator/out-10m-hmac — staging.members_hmac 의 원본)
#
# A와 F_HMAC이 "똑같은 seq 목록"을 같은 순서로 쓰도록 시드를 고정한다(SEED 환경변수로 변경 가능).
set -euo pipefail
cd "$(dirname "$0")/.."

N="${1:?사용법: $0 <N> [K] [CSV_DIR]}"
K="${2:-200}"
CSV_DIR="${3:-generator/out-10m-hmac}"
SEED="${SEED:-20261115}"

if ! [[ "$N" =~ ^[0-9]+$ && "$K" =~ ^[0-9]+$ ]] || [ "$N" -lt 1 ] || [ "$K" -lt 1 ]; then
  echo "N, K는 1 이상의 정수여야 합니다." >&2
  exit 2
fi
if [ "$K" -gt "$N" ]; then K="$N"; fi

mkdir -p k6/data
OUT="k6/data/sample-${N}.csv"

# 청크 파일을 seq 순서대로 읽고, seq <= N 까지만 reservoir sampling 으로 K개를 고른다.
# (헤더 줄은 건너뛰고, N을 넘으면 즉시 종료 → 1만 건이면 첫 청크 일부만 읽는다)
awk -F, -v n="$N" -v k="$K" -v seed="$SEED" '
      BEGIN { srand(seed); c = 0 }
      $1 == "seq" { next }
      {
        s = $1 + 0
        if (s > n) exit
        c++
        if (c <= k) { pick[c] = s "," $2 }
        else { r = int(rand() * c) + 1; if (r <= k) pick[r] = s "," $2 }
      }
      END {
        if (c < n) { printf("CSV에 seq <= %d 행이 %d건뿐입니다.\n", n, c) > "/dev/stderr"; exit 1 }
        m = (c < k ? c : k)
        for (i = m; i > 1; i--) { j = int(rand() * i) + 1; t = pick[i]; pick[i] = pick[j]; pick[j] = t }  # 순서 섞기
        print "seq,email"
        for (i = 1; i <= m; i++) print pick[i]
      }' "$CSV_DIR"/members_chunk_*.csv > "$OUT" || { rm -f "$OUT"; exit 1; }

echo "[make-sample] $OUT ($(($(wc -l < "$OUT") - 1))개, seq <= $N, seed=$SEED)"
