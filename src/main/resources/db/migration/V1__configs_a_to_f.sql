-- 계획서 7장 "스키마와 인덱스 설계" / 4장 "비교 구성" 표 그대로.
-- 구성 A~F (SHA-256 버전). HMAC 버전은 V2에서 별도 테이블로 추가한다.
--
-- email_hash: SHA-256/HMAC-SHA256 결과를 Base64로 인코딩한 32바이트 => 44자(패딩 포함)
-- email_aes256: jasypt PBEWITHHMACSHA256ANDAES_256 암호문. salt+iv를 포함하므로 길이가 가변적 -> TEXT
-- password_hash: bcrypt 결과 (60자) -> 여유를 두고 VARCHAR(100)
--
-- 인덱스는 여기서 만들지 않는다. 계획서 6장 "적재 절차": "적재 후 CREATE INDEX, ANALYZE 순서로
-- 실행한다" — 대량 적재 전에 인덱스를 만들면 삽입마다 인덱스를 갱신하는 비용이 끼어들어
-- 적재 자체의 측정(및 인덱스 빌드 비용 관찰)이 왜곡된다. 인덱스는 scripts/create-indexes.sh가
-- 규모별 적재 직후에 만든다 (sql/indexes.sql 참고).

-- ── 구성 A: 암호문만 저장, 인덱스 없음 (전체 스캔이 비교 기준) ──────────────
CREATE TABLE users_a (
    id            BIGSERIAL PRIMARY KEY,
    email_aes256  TEXT        NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 구성 B: user+map, 해시 인덱스 없음 (순차 탐색이 측정 대상) ──────────────
-- 의도적으로 인덱스를 만들지 않는다 — 구성 B의 측정 목적 자체가 "인덱스 없을 때"이기 때문.
CREATE TABLE users_b (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_b (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL
);

-- ── 구성 C: user+map, B-tree 인덱스 (블로그가 제안한 구조) ─────────────────
CREATE TABLE users_c (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_c (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL
);

-- ── 구성 D: user+map, hash 인덱스 (H5: B-tree와 비교) ──────────────────────
-- hash 인덱스는 UNIQUE 제약을 걸 수 없다. 중복 가입 방지(signup의 existsByHash)는 어차피
-- B-tree unique 인덱스가 필요하므로 적재 후 두 인덱스를 함께 만든다 (sql/indexes.sql).
CREATE TABLE users_d (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_d (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL
);

-- ── 구성 E: map 테이블 없이 user에 해시+암호문 (H6, B-tree) ────────────────
CREATE TABLE users_e (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 구성 F: E 구조 + hash 인덱스 (E에서의 hash 인덱스 효과) ────────────────
-- D와 같은 이유로 B-tree unique(무결성) + hash(조회 측정) 인덱스를 적재 후 함께 만든다.
CREATE TABLE users_f (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
