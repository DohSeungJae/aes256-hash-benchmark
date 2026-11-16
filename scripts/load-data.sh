#!/usr/bin/env bash
# 계획서 6장 "적재 절차" 구현.
#
# 사용: ./scripts/load-data.sh <SCALE> [CHUNK_SIZE]
#   SCALE      : 적재할 규모 (예: 10000, 100000, 1000000, 10000000)
#   CHUNK_SIZE : 생성기가 쓴 chunk-size와 반드시 같아야 함 (기본 1000000).
#                data/users_000.csv, users_001.csv, ... 파일 경계를 계산하는 데 쓰인다.
#
# 전제:
#  - ./scripts/generate-data.sh로 CSV가 ./data/ 밑에 이미 만들어져 있어야 한다.
#  - 서버(ahbench.jar)가 이미 떠 있어야 한다 — Flyway가 테이블을 만들어두고,
#    /api/admin/bcrypt로 더미 비밀번호 bcrypt 해시를 받아오기 때문.
#  - docker-compose의 postgres 컨테이너 이름은 ah-bench (docker-compose.yml 참고).
#
# 절차 (계획서 6장 그대로):
#  1) staging 테이블에 COPY (인덱스 없음)
#  2) 10가지 구성 테이블에 INSERT ... SELECT ... WHERE seq <= N
#  3) CREATE INDEX (sql/indexes.sql)
#  4) ANALYZE
#  5) pg_total_relation_size로 크기 기록 (scripts/measure-sizes.sh)
set -euo pipefail
cd "$(dirname "$0")/.."

SCALE="${1:?사용법: load-data.sh <SCALE> [CHUNK_SIZE]}"
CHUNK_SIZE="${2:-1000000}"
CONTAINER="${PG_CONTAINER:-ah-bench}"
DB_NAME="${DB_NAME:-ahbench}"
DB_USER="${DB_USER:-bench}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:8080}"
DUMMY_PASSWORD="${DUMMY_PASSWORD:-BenchPassword123!}"

psql_exec() {
  docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME"
}

echo "[load-data] scale=${SCALE} chunk_size=${CHUNK_SIZE} container=${CONTAINER}"

# ── 0) 모든 구성이 같은 더미 비밀번호를 쓰도록 bcrypt 해시를 서버에서 받아온다 ───────
BCRYPT_HASH="$(curl -sf --get --data-urlencode "raw=${DUMMY_PASSWORD}" "${APP_BASE_URL}/api/admin/bcrypt" \
  | sed -n 's/.*"hash":"\([^"]*\)".*/\1/p')"
if [ -z "${BCRYPT_HASH}" ]; then
  echo "bcrypt 해시를 받아오지 못했습니다. 서버(${APP_BASE_URL})가 떠 있는지 확인하세요." >&2
  exit 1
fi
echo "[load-data] dummy password = '${DUMMY_PASSWORD}' (모든 시드 계정 로그인용)"

# ── 1) staging 테이블 준비 + 필요한 청크 파일만 COPY ────────────────────────────
psql_exec <<SQL
DROP TABLE IF EXISTS staging_emails;
CREATE TABLE staging_emails (
    seq                BIGINT      NOT NULL,
    email              TEXT        NOT NULL,
    email_hash_sha256  VARCHAR(44) NOT NULL,
    email_hash_hmac    VARCHAR(44) NOT NULL,
    email_aes256       TEXT        NOT NULL
);
SQL

NUM_FILES=$(( (SCALE + CHUNK_SIZE - 1) / CHUNK_SIZE ))
for i in $(seq 0 $((NUM_FILES - 1))); do
  FILE=$(printf "data/users_%03d.csv" "$i")
  if [ ! -f "$FILE" ]; then
    echo "필요한 CSV가 없습니다: $FILE (generate-data.sh를 먼저 실행하세요)" >&2
    exit 1
  fi
  echo "[load-data] staging <- $FILE"
  docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" \
    -c "\copy staging_emails FROM STDIN WITH (FORMAT csv, HEADER true)" < "$FILE"
done

# ── 2) 구성별 테이블 재적재 (TRUNCATE 후 INSERT ... SELECT ... WHERE seq <= N) ──
psql_exec <<SQL
-- A
TRUNCATE users_a;
INSERT INTO users_a (email_aes256, password_hash)
SELECT email_aes256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};

-- B (SHA-256, 인덱스 없음)
TRUNCATE users_b, map_b;
INSERT INTO users_b (email_hash, password_hash)
SELECT email_hash_sha256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
INSERT INTO map_b (email_hash, email_aes256)
SELECT email_hash_sha256, email_aes256 FROM staging_emails WHERE seq <= ${SCALE};

-- C (SHA-256, B-tree)
TRUNCATE users_c, map_c;
INSERT INTO users_c (email_hash, password_hash)
SELECT email_hash_sha256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
INSERT INTO map_c (email_hash, email_aes256)
SELECT email_hash_sha256, email_aes256 FROM staging_emails WHERE seq <= ${SCALE};

-- D (SHA-256, hash)
TRUNCATE users_d, map_d;
INSERT INTO users_d (email_hash, password_hash)
SELECT email_hash_sha256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
INSERT INTO map_d (email_hash, email_aes256)
SELECT email_hash_sha256, email_aes256 FROM staging_emails WHERE seq <= ${SCALE};

-- E (SHA-256, map 없음, B-tree)
TRUNCATE users_e;
INSERT INTO users_e (email_hash, email_aes256, password_hash)
SELECT email_hash_sha256, email_aes256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};

-- F (SHA-256, map 없음, hash)
TRUNCATE users_f;
INSERT INTO users_f (email_hash, email_aes256, password_hash)
SELECT email_hash_sha256, email_aes256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};

-- C_HMAC
TRUNCATE users_c_hmac, map_c_hmac;
INSERT INTO users_c_hmac (email_hash, password_hash)
SELECT email_hash_hmac, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
INSERT INTO map_c_hmac (email_hash, email_aes256)
SELECT email_hash_hmac, email_aes256 FROM staging_emails WHERE seq <= ${SCALE};

-- D_HMAC
TRUNCATE users_d_hmac, map_d_hmac;
INSERT INTO users_d_hmac (email_hash, password_hash)
SELECT email_hash_hmac, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
INSERT INTO map_d_hmac (email_hash, email_aes256)
SELECT email_hash_hmac, email_aes256 FROM staging_emails WHERE seq <= ${SCALE};

-- E_HMAC
TRUNCATE users_e_hmac;
INSERT INTO users_e_hmac (email_hash, email_aes256, password_hash)
SELECT email_hash_hmac, email_aes256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};

-- F_HMAC
TRUNCATE users_f_hmac;
INSERT INTO users_f_hmac (email_hash, email_aes256, password_hash)
SELECT email_hash_hmac, email_aes256, '${BCRYPT_HASH}' FROM staging_emails WHERE seq <= ${SCALE};
SQL

# ── 3) 인덱스 생성 (적재 후) ────────────────────────────────────────────────
echo "[load-data] creating indexes..."
psql_exec < sql/indexes.sql

# ── 4) ANALYZE ──────────────────────────────────────────────────────────────
echo "[load-data] ANALYZE..."
psql_exec <<SQL
ANALYZE users_a;
ANALYZE users_b; ANALYZE map_b;
ANALYZE users_c; ANALYZE map_c;
ANALYZE users_d; ANALYZE map_d;
ANALYZE users_e;
ANALYZE users_f;
ANALYZE users_c_hmac; ANALYZE map_c_hmac;
ANALYZE users_d_hmac; ANALYZE map_d_hmac;
ANALYZE users_e_hmac;
ANALYZE users_f_hmac;
SQL

# ── 5) staging 정리 + 크기 기록 ─────────────────────────────────────────────
psql_exec <<SQL
DROP TABLE IF EXISTS staging_emails;
SQL

echo "[load-data] 완료. 크기 기록: ./scripts/measure-sizes.sh"
./scripts/measure-sizes.sh
