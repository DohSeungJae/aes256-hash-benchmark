#!/usr/bin/env bash
# 측정 당시 환경을 기록한다 (재현·검증용). 키/비밀번호는 절대 출력하지 않는다.
#   - docker inspect 는 HostConfig(한도) 만 읽고 Config.Env 는 읽지 않는다.
#   - .env 는 PG_ 항목만 읽는다.
# 사용: k6/lib/snapshot-env.sh > env.txt
set -uo pipefail
cd "$(dirname "$0")/../.."

PG=ah-bench
APP=ah-bench-app
psqlq() { docker exec -i "$PG" psql -U bench -d ahbench -tAc "$1" 2>&1; }

echo "# 측정 환경 스냅샷"
echo "captured_at:        $(date '+%Y-%m-%d %H:%M:%S %z')"
echo "host:               $(hostname)  kernel=$(uname -r)"
echo "wsl_mem_total:      $(free -h | awk '/^Mem:/{print $2}')  nproc=$(nproc)"
echo "loadavg:            $(cut -d' ' -f1-3 /proc/loadavg)"
echo "k6:                 $(k6 version 2>&1 | head -1)"
echo "git_commit:         $(git rev-parse --short HEAD 2>/dev/null)$( [ -n "$(git status --porcelain 2>/dev/null)" ] && echo ' (dirty: 커밋 안 된 변경 있음)')"
echo
echo "## 컨테이너 한도"
for c in "$PG" "$APP"; do
  docker inspect "$c" --format "$c: image={{.Config.Image}} mem_limit={{.HostConfig.Memory}} nano_cpus={{.HostConfig.NanoCpus}} shm={{.HostConfig.ShmSize}} started={{.State.StartedAt}} health={{if .State.Health}}{{.State.Health.Status}}{{end}}" 2>&1
done
echo
echo "## .env (PG_ 항목만)"
grep -E '^PG_' .env 2>/dev/null | tr -d '\r'
echo
echo "## PostgreSQL 설정"
for s in server_version shared_buffers effective_cache_size work_mem max_connections; do
  printf '%-22s %s\n' "$s" "$(psqlq "SHOW $s")"
done
echo
echo "## JVM 힙 (앱 컨테이너)"
docker exec "$APP" sh -c 'java -XX:+PrintFlagsFinal -version 2>/dev/null' 2>&1 \
  | awk '$2=="InitialHeapSize"||$2=="MaxHeapSize"{printf "%-22s %d MiB\n",$2,$4/1048576}'
echo
echo "## 행 수"
for t in users_a users_f_hmac staging.members_hmac; do
  printf '%-22s %s\n' "$t" "$(psqlq "SELECT count(*) FROM $t")"
done
echo
echo "## 테이블 크기 (힙+인덱스)"
psqlq "SELECT relname || ' ' || pg_size_pretty(pg_total_relation_size(oid)) FROM pg_class WHERE relname IN ('users_a','users_f_hmac') ORDER BY 1"
echo
echo "## 앱 상태"
echo "actuator_health:    $(curl -s -m 5 http://localhost:8080/actuator/health)"
