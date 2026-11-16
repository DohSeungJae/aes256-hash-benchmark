#!/usr/bin/env bash
# 규모별 적재 스크립트: 스테이징(staging.members_hmac)에서 seq <= N 만 본 테이블로 옮긴다.
#
# 사용법:  ./load.sh <a|f_hmac> <N>
#   예)    ./load.sh a 10000
#          ./load.sh f_hmac 10000
#
# 동작:
#   a      : users_a      (email_aes256, password_hash)                     인덱스는 PK만
#   f_hmac : users_f_hmac (email_hash, email_aes256, password_hash)         적재 후 Hash 인덱스 생성
# 규모를 바꿀 때마다 대상 테이블을 비우고(TRUNCATE ... RESTART IDENTITY) 다시 채운다.
# 주의: 대상 테이블의 기존 행은 모두 삭제된다.

set -euo pipefail

CFG="${1:?사용법: $0 <a|f_hmac> <N>}"
N="${2:?사용법: $0 <a|f_hmac> <N>}"

CONTAINER="${CONTAINER:-ah-bench}"
DB_USER="${DB_USER:-bench}"
DB_NAME="${DB_NAME:-ahbench}"
PW_PLAIN="${PW_PLAIN:-test1234!}"   # 모든 행에 쓸 로그인 비밀번호(평문). bcrypt는 한 번만 계산한다.

if ! [[ "$N" =~ ^[0-9]+$ ]] || [ "$N" -lt 1 ]; then
  echo "N은 1 이상의 정수여야 합니다: $N" >&2
  exit 2
fi

psql_run() {
  docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# 1) 스테이징에 N건이 있는지 확인
have="$(psql_run -tA -c "SELECT count(*) FROM staging.members_hmac WHERE seq <= $N;")"
if [ "$have" != "$N" ]; then
  echo "스테이징에 seq <= $N 행이 $have건뿐입니다(필요: $N). 스테이징 적재를 확인하세요." >&2
  exit 1
fi

# 2) 비밀번호 해시를 한 번만 만든다(이미 있으면 재사용). 값이 바뀌면 staging.pw를 지우고 다시 실행.
psql_run -q -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" \
         -c "CREATE TABLE IF NOT EXISTS staging.pw AS SELECT crypt('${PW_PLAIN//\'/\'\'}', gen_salt('bf', 10)) AS h;"

echo "== 구성: $CFG / 규모: $N =="
start=$(date +%s)

case "$CFG" in
  a)
    psql_run -v n="$N" <<'SQL'
\timing on
TRUNCATE users_a RESTART IDENTITY;
INSERT INTO users_a (email_aes256, password_hash)
  SELECT email_aes256, (SELECT h FROM staging.pw)
  FROM staging.members_hmac WHERE seq <= :n ORDER BY seq;
VACUUM (ANALYZE) users_a;
\timing off
SELECT 'users_a' AS 테이블, count(*) AS 행수,
       pg_size_pretty(pg_total_relation_size('users_a')) AS 전체크기
FROM users_a;
SQL
    ;;
  f_hmac)
    psql_run -v n="$N" <<'SQL'
\timing on
DROP INDEX IF EXISTS users_f_hmac_hash_idx;
TRUNCATE users_f_hmac RESTART IDENTITY;
INSERT INTO users_f_hmac (email_hash, email_aes256, password_hash)
  SELECT email_hash, email_aes256, (SELECT h FROM staging.pw)
  FROM staging.members_hmac WHERE seq <= :n ORDER BY seq;
SET maintenance_work_mem = '512MB';
CREATE INDEX users_f_hmac_hash_idx ON users_f_hmac USING hash (email_hash);
VACUUM (ANALYZE) users_f_hmac;
\timing off
SELECT 'users_f_hmac' AS 테이블, count(*) AS 행수,
       pg_size_pretty(pg_relation_size('users_f_hmac')) AS 테이블크기,
       pg_size_pretty(pg_relation_size('users_f_hmac_hash_idx')) AS 인덱스크기
FROM users_f_hmac;
-- 실제로 Hash 인덱스를 타는지 확인 (Index Scan using users_f_hmac_hash_idx 가 나와야 한다)
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM users_f_hmac
WHERE email_hash = (SELECT email_hash FROM staging.members_hmac WHERE seq = 1);
SQL
    ;;
  *)
    echo "알 수 없는 구성: $CFG (a 또는 f_hmac)" >&2
    exit 2
    ;;
esac

end=$(date +%s)
echo "== 완료: $CFG / $N 건 / 총 $((end - start))초 =="
