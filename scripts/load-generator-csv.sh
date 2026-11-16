#!/usr/bin/env bash
# generator/ 독립 생성기 CSV(seq,email,email_hash,email_aes256)를 구성별 테이블에 적재한다.
#
# 사용: ./scripts/load-generator-csv.sh <CSV 파일 또는 폴더> <sha256|hmac> [--replace]
#   CSV        : members_chunk_001.csv 하나, 또는 members_chunk_*.csv가 든 폴더
#   해시 방식  : 생성할 때 쓴 HASH_MODE와 같아야 한다.
#                sha256 → A, B, C, D, E, F   (A는 해시를 쓰지 않으므로 sha256 쪽에 포함)
#                hmac   → C_HMAC, D_HMAC, E_HMAC, F_HMAC
#   --replace  : 대상 테이블을 TRUNCATE한 뒤 적재 (기본: 기존 행 유지하고 추가)
#
# 전제:
#  - CSV는 서버와 같은 키로 만들어야 한다:
#      ENCRYPTION_KEY = .env의 AES_ENCRYPTION_KEY,  HASH_KEY = .env의 HMAC_SECRET_KEY
#    (다르면 적재는 되지만 서버가 조회/복호화하지 못한다)
#  - 서버가 떠 있어야 한다 — /api/admin/bcrypt로 더미 비밀번호 bcrypt 해시를 받아온다.
#  - CSV에는 비밀번호가 없으므로 모든 행이 같은 더미 비밀번호(DUMMY_PASSWORD)를 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:?사용법: load-generator-csv.sh <CSV 파일 또는 폴더> <sha256|hmac> [--replace]}"
MODE="${2:?해시 방식(sha256|hmac)을 지정하세요}"
REPLACE="${3:-}"
CONTAINER="${PG_CONTAINER:-ah-bench}"
DB_NAME="${DB_NAME:-ahbench}"
DB_USER="${DB_USER:-bench}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
DUMMY_PASSWORD="${DUMMY_PASSWORD:-BenchPassword123!}"

case "$MODE" in
  sha256) CONFIGS=(a b c d e f);              LOGIN_CFG=c ;;
  hmac)   CONFIGS=(c_hmac d_hmac e_hmac f_hmac); LOGIN_CFG=c_hmac ;;
  *) echo "해시 방식은 sha256 또는 hmac이어야 합니다: $MODE" >&2; exit 1 ;;
esac
if [ -n "$REPLACE" ] && [ "$REPLACE" != "--replace" ]; then
  echo "알 수 없는 옵션: $REPLACE" >&2; exit 1
fi

if [ -d "$SRC" ]; then
  FILES=("$SRC"/members_chunk_*.csv)
  [ -e "${FILES[0]}" ] || { echo "폴더에 members_chunk_*.csv가 없습니다: $SRC" >&2; exit 1; }
else
  [ -f "$SRC" ] || { echo "파일이 없습니다: $SRC" >&2; exit 1; }
  FILES=("$SRC")
fi

# generator의 generation.meta에 기록된 hashMode와 인자가 다르면 막는다
META="$(dirname "${FILES[0]}")/generation.meta"
if [ -f "$META" ] && ! grep -qx "hashMode=${MODE}" "$META"; then
  echo "CSV는 $(grep '^hashMode=' "$META")로 만들어졌습니다. 인자($MODE)와 다릅니다." >&2
  exit 1
fi

psql_exec() {
  docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME"
}

echo "[load-generator-csv] mode=${MODE} files=${#FILES[@]} configs=${CONFIGS[*]} ${REPLACE:-(append)}"

# ── 0) 더미 비밀번호 bcrypt 해시 (load-data.sh와 같은 방식) ─────────────────────
BCRYPT_HASH="$(curl -sf --get --data-urlencode "raw=${DUMMY_PASSWORD}" "${APP_BASE_URL}/api/admin/bcrypt" \
  | sed -n 's/.*"hash":"\([^"]*\)".*/\1/p')"
if [ -z "${BCRYPT_HASH}" ]; then
  echo "bcrypt 해시를 받아오지 못했습니다. 서버(${APP_BASE_URL})가 떠 있는지 확인하세요." >&2
  exit 1
fi
echo "[load-generator-csv] dummy password = '${DUMMY_PASSWORD}' (적재한 모든 계정 로그인용)"

# ── 1) staging 테이블에 CSV를 그대로 COPY ───────────────────────────────────────
psql_exec <<SQL
DROP TABLE IF EXISTS staging_generator;
CREATE TABLE staging_generator (
    seq          BIGINT      NOT NULL,
    email        TEXT        NOT NULL,
    email_hash   VARCHAR(44) NOT NULL,
    email_aes256 TEXT        NOT NULL
);
SQL

for FILE in "${FILES[@]}"; do
  echo "[load-generator-csv] staging <- $FILE"
  docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" \
    -c "\copy staging_generator FROM STDIN WITH (FORMAT csv, HEADER true)" < "$FILE"
done

# ── 2) 구성별 테이블에 INSERT ... SELECT (한 트랜잭션) ────────────────────────────
# 테이블 모양 (V1/V2 마이그레이션):
#   a              : users_a(email_aes256, password_hash)
#   b, c, d, *_hmac: users_x(email_hash, password_hash) + map_x(email_hash, email_aes256)
#   e, f, *_hmac   : users_x(email_hash, email_aes256, password_hash)
SQL_BODY="BEGIN;"
for cfg in "${CONFIGS[@]}"; do
  case "$cfg" in
    a)
      if [ -n "$REPLACE" ]; then SQL_BODY+=" TRUNCATE users_a;"; fi
      SQL_BODY+=" INSERT INTO users_a (email_aes256, password_hash)
        SELECT email_aes256, '${BCRYPT_HASH}' FROM staging_generator ORDER BY seq;"
      ;;
    b|c|d|c_hmac|d_hmac)
      if [ -n "$REPLACE" ]; then SQL_BODY+=" TRUNCATE users_${cfg}, map_${cfg};"; fi
      SQL_BODY+=" INSERT INTO users_${cfg} (email_hash, password_hash)
        SELECT email_hash, '${BCRYPT_HASH}' FROM staging_generator ORDER BY seq;
      INSERT INTO map_${cfg} (email_hash, email_aes256)
        SELECT email_hash, email_aes256 FROM staging_generator ORDER BY seq;"
      ;;
    e|f|e_hmac|f_hmac)
      if [ -n "$REPLACE" ]; then SQL_BODY+=" TRUNCATE users_${cfg};"; fi
      SQL_BODY+=" INSERT INTO users_${cfg} (email_hash, email_aes256, password_hash)
        SELECT email_hash, email_aes256, '${BCRYPT_HASH}' FROM staging_generator ORDER BY seq;"
      ;;
  esac
done
SQL_BODY+=" COMMIT;"
echo "$SQL_BODY" | psql_exec

# ── 3) 인덱스 재생성 + ANALYZE + staging 정리 ───────────────────────────────────
echo "[load-generator-csv] creating indexes..."
psql_exec < sql/indexes.sql

ANALYZE_SQL=""
for cfg in "${CONFIGS[@]}"; do
  ANALYZE_SQL+="ANALYZE users_${cfg};"
  case "$cfg" in b|c|d|c_hmac|d_hmac) ANALYZE_SQL+="ANALYZE map_${cfg};" ;; esac
done
echo "${ANALYZE_SQL} DROP TABLE IF EXISTS staging_generator;" | psql_exec

echo "[load-generator-csv] 완료. 로그인 확인 예:"
FIRST_EMAIL="$(sed -n '2p' "${FILES[0]}" | cut -d, -f2)"
echo "  curl -i -X POST ${APP_BASE_URL}/api/bench/${LOGIN_CFG}/login -H 'Content-Type: application/json' \\"
echo "    -d '{\"email\":\"${FIRST_EMAIL}\",\"password\":\"${DUMMY_PASSWORD}\"}'"
