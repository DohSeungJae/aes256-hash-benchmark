-- 계획서 11장 보안 권고(HMAC-SHA256)를 적용한 변형. C/D/E/F와 테이블 구조는 완전히
-- 동일하고, 서버 쪽에서 계산하는 해시 알고리즘만 다르다 (ConfigRegistry 참고).
-- B/A는 HMAC 변형을 두지 않는다 (common/ConfigId 주석 참고).
-- V1과 마찬가지로 인덱스는 적재 후 sql/indexes.sql로 만든다.

-- ── C_HMAC ──────────────────────────────────────────────────────────────
CREATE TABLE users_c_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_c_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL
);

-- ── D_HMAC ──────────────────────────────────────────────────────────────
CREATE TABLE users_d_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE map_d_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL
);

-- ── E_HMAC ──────────────────────────────────────────────────────────────
CREATE TABLE users_e_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── F_HMAC ──────────────────────────────────────────────────────────────
CREATE TABLE users_f_hmac (
    id            BIGSERIAL PRIMARY KEY,
    email_hash    VARCHAR(44) NOT NULL,
    email_aes256  TEXT        NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
