#!/usr/bin/env bash
# 구성 하나(a | f_hmac)를 측정하고 결과를 모두 한 폴더에 남긴다.
#
# 사용법:  ./k6/run-one.sh <config> <N> [ITERATIONS] [RUN_DIR]
#   예)    ./k6/run-one.sh f_hmac 10000 200
#
# 결과 폴더 (RUN_DIR 기본: results/<시각>_N<N>):
#   env.txt                 측정 환경 스냅샷 (한도, PG 설정, JVM 힙, 행 수, git, k6 버전) — 1회만 기록
#   <config>/warmup.log     워밍업 k6 실행 출력 (통계에는 쓰지 않음)
#   <config>/k6.log         측정 k6 콘솔 출력 전체 (워밍업 요청 없음)
#   <config>/summary.json   k6 요약 (login_ms, decrypt_ms, login_success …)
#   <config>/raw.csv.gz     k6 모든 지표 원본 (--out csv)
#   <config>/requests.csv   측정 요청별 기록 (iter, phase, seq, status, duration_ms)
#   <config>/docker-stats.csv  측정 중 컨테이너 CPU/메모리 (시작 직전·종료 직후 + STATS_INTERVAL초 간격, 기본 5)
#   <config>/app.log        측정 구간의 서버 로그
#   <config>/pg_stat_statements.txt  측정 구간의 SQL 통계 (상위 10개)
#   <config>/meta.txt       시작/종료 시각, 소요 시간, k6 종료 코드, 실행 명령
# 전체 이력: results/index.csv 에 1줄 추가
#
# 순서: ① 워밍업 k6 실행(WARMUP회, 결과 버림) → ② SQL 통계 초기화·자원 샘플링 시작 → ③ 측정 k6 실행
#   → 측정 결과(k6 기본 지표, raw.csv, docker-stats, pg_stat_statements, app.log)에 워밍업이 섞이지 않는다.
# 워밍업 횟수 (측정 시작 위치는 항상 샘플 6번째로 고정 — 워밍업 횟수가 달라도 같은 seq를 측정):
#   a      : N < 10만 5회 / 10만 이상 3회 / 100만 이상 2회 / 1000만 이상 1회
#            (규모가 클수록 요청 1건이 길고, 첫 요청 안에서 JIT가 끝나므로)
#   f_hmac : 5회
#   덮어쓰기: WARMUP_A / WARMUP_F (구성별) 또는 WARMUP (둘 다). 0이면 워밍업 생략.
# 환경변수: STATS_INTERVAL(기본 5), 그 외 login_compare.js 의 변수는 그대로 전달.
set -euo pipefail
cd "$(dirname "$0")/.."

CFG="${1:?사용법: $0 <a|f_hmac> <N> [ITERATIONS] [RUN_DIR]}"
N="${2:?사용법: $0 <a|f_hmac> <N> [ITERATIONS] [RUN_DIR]}"
ITER="${3:-200}"
RUN_DIR="${4:-results/$(date +%Y%m%d-%H%M%S)_N${N}}"
if [ "$CFG" = a ]; then
  if   [ "$N" -ge 10000000 ]; then def_w=1
  elif [ "$N" -ge 1000000 ];  then def_w=2
  elif [ "$N" -ge 100000 ];   then def_w=3
  else                             def_w=5; fi
  WARMUP="${WARMUP_A:-${WARMUP:-$def_w}}"
else
  WARMUP="${WARMUP_F:-${WARMUP:-5}}"
fi
STATS_INTERVAL="${STATS_INTERVAL:-5}"

case "$CFG" in a|f_hmac) ;; *) echo "config는 a 또는 f_hmac" >&2; exit 2 ;; esac
command -v k6 >/dev/null || { echo "k6가 없습니다 (~/.local/bin/k6)" >&2; exit 1; }

# 샘플 준비 (워밍업 포함 개수)
SAMPLE="k6/data/sample-${N}.csv"
need=$(( ITER + 5 ))   # 샘플 0~4 워밍업용 + 측정 ITER개
if [ ! -f "$SAMPLE" ] || [ "$(($(wc -l < "$SAMPLE") - 1))" -lt "$need" ]; then
  ./k6/make-sample.sh "$N" "$need"
fi

OUT="$RUN_DIR/$CFG"
mkdir -p "$OUT"
[ -f "$RUN_DIR/env.txt" ] || ./k6/lib/snapshot-env.sh > "$RUN_DIR/env.txt" 2>&1
cp -n "$SAMPLE" "$RUN_DIR/" 2>/dev/null || true

# ① 워밍업 (별도 k6 프로세스 — 이 실행의 지표는 어디에도 집계하지 않는다)
WARMUP_RC=0
WARMUP_S=0
if [ "$WARMUP" -gt 0 ]; then
  echo "=== warmup config=${CFG} N=${N} warmup=${WARMUP} (통계 제외) ==="
  W_START=$(date +%s)
  set +e
  k6 run --quiet --env PHASE=warmup --env CONFIG="$CFG" --env N="$N" --env WARMUP="$WARMUP" \
    --no-thresholds --summary-mode=disabled k6/login_compare.js > "$OUT/warmup.log" 2>&1
  WARMUP_RC=$?
  set -e
  WARMUP_S=$(( $(date +%s) - W_START ))
  if [ "$WARMUP_RC" -ne 0 ] || grep -q '실패 seq=' "$OUT/warmup.log"; then
    echo "[run-one] 워밍업 중 오류/로그인 실패가 있었습니다 → $OUT/warmup.log (rc=$WARMUP_RC)" >&2
  fi
fi

# ② 측정 구간만 보이도록 SQL 통계 초기화
docker exec -i ah-bench psql -U bench -d ahbench -qtAc "SELECT pg_stat_statements_reset()" >/dev/null 2>&1 || true

# 자원 사용량 샘플링 (백그라운드)
# docker stats 1회 호출에 약 2초 걸린다 → 짧은 실행도 남도록 시작 전/종료 후에 1번씩 동기 기록
echo "time,tag,name,cpu_pct,mem_usage,mem_pct,net_io,block_io,pids" > "$OUT/docker-stats.csv"
stats_once() {
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' ah-bench ah-bench-app 2>/dev/null \
    | sed "s/^/$(date +%H:%M:%S),$1,/" >> "$OUT/docker-stats.csv"
}
stats_once before
(
  while :; do sleep "$STATS_INTERVAL"; stats_once during; done
) &
STATS_PID=$!
trap 'kill $STATS_PID 2>/dev/null || true' EXIT

START_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START_S=$(date +%s)
CMD=(k6 run --env PHASE=measure --env CONFIG="$CFG" --env N="$N" --env ITERATIONS="$ITER" --env WARMUP="$WARMUP"
     --summary-export="$OUT/summary.json" --out "csv=$OUT/raw.csv.gz" k6/login_compare.js)
echo "=== config=${CFG} N=${N} iterations=${ITER} warmup=${WARMUP} → ${OUT} ==="
set +e
"${CMD[@]}" 2>&1 | tee "$OUT/k6.log"
K6_RC=${PIPESTATUS[0]}
set -e
END_S=$(date +%s)

kill "$STATS_PID" 2>/dev/null || true
wait "$STATS_PID" 2>/dev/null || true
stats_once after

docker logs ah-bench-app --since "$START_ISO" > "$OUT/app.log" 2>&1 || true
docker exec -i ah-bench psql -U bench -d ahbench -c \
  "SELECT calls, round(total_exec_time::numeric,1) AS total_ms, round(mean_exec_time::numeric,3) AS mean_ms,
          rows, shared_blks_hit, shared_blks_read, left(regexp_replace(query,'\s+',' ','g'),120) AS query
     FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10" > "$OUT/pg_stat_statements.txt" 2>&1 || true

{
  echo "config:      $CFG"
  echo "N:           $N"
  echo "iterations:  $ITER (측정)"
  echo "warmup:      $WARMUP (별도 k6 실행, ${WARMUP_S}s, rc=$WARMUP_RC — 통계 제외)"
  echo "start:       $(date -d "@$START_S" '+%Y-%m-%d %H:%M:%S')"
  echo "end:         $(date -d "@$END_S" '+%Y-%m-%d %H:%M:%S')"
  echo "elapsed_s:   $((END_S - START_S))  (측정 구간만)"
  echo "k6_exit:     $K6_RC  (0=정상, 99=threshold 실패=로그인 실패 있음)"
  echo "command:     ${CMD[*]}"
} > "$OUT/meta.txt"

if [ -f "$OUT/summary.json" ] && [ -f "$OUT/raw.csv.gz" ]; then
  python3 k6/lib/summarize.py "$OUT" "$(basename "$RUN_DIR")" "$CFG" "$N" "$ITER" "$WARMUP"
else
  echo "[run-one] summary/raw 파일이 없어 index.csv 에 기록하지 못했습니다 (k6_exit=$K6_RC)" >&2
fi
exit "$K6_RC"
