-- 계획서 7장 "스키마와 인덱스 설계". 규모별 적재(scripts/load-data.sh) 직후에 실행한다.
-- 구성 전환/재적재마다 다시 돌릴 수 있도록 DROP IF EXISTS 후 CREATE 한다
-- (계획서 9장 "구성 전환 시 DB와 앱을 재시작하고 ANALYZE를 다시 실행").

-- 구성 A, B: 의도적으로 인덱스 없음 (측정 대상 자체가 "인덱스 없을 때")

-- 구성 C
DROP INDEX IF EXISTS uq_users_c_email_hash;
DROP INDEX IF EXISTS idx_map_c_email_hash_btree;
CREATE UNIQUE INDEX uq_users_c_email_hash ON users_c (email_hash);
CREATE INDEX idx_map_c_email_hash_btree ON map_c (email_hash);

-- 구성 D (hash 인덱스는 UNIQUE 불가 -> B-tree unique를 users_d에, hash 인덱스를 map_d에)
DROP INDEX IF EXISTS uq_users_d_email_hash_btree;
DROP INDEX IF EXISTS idx_map_d_email_hash_hash;
CREATE UNIQUE INDEX uq_users_d_email_hash_btree ON users_d (email_hash);
CREATE INDEX idx_map_d_email_hash_hash ON map_d USING hash (email_hash);

-- 구성 E
DROP INDEX IF EXISTS uq_users_e_email_hash;
CREATE UNIQUE INDEX uq_users_e_email_hash ON users_e (email_hash);

-- 구성 F (B-tree unique + hash 인덱스를 같은 컬럼에 함께)
DROP INDEX IF EXISTS uq_users_f_email_hash_btree;
DROP INDEX IF EXISTS idx_users_f_email_hash_hash;
CREATE UNIQUE INDEX uq_users_f_email_hash_btree ON users_f (email_hash);
CREATE INDEX idx_users_f_email_hash_hash ON users_f USING hash (email_hash);

-- 구성 C_HMAC
DROP INDEX IF EXISTS uq_users_c_hmac_email_hash;
DROP INDEX IF EXISTS idx_map_c_hmac_email_hash_btree;
CREATE UNIQUE INDEX uq_users_c_hmac_email_hash ON users_c_hmac (email_hash);
CREATE INDEX idx_map_c_hmac_email_hash_btree ON map_c_hmac (email_hash);

-- 구성 D_HMAC
DROP INDEX IF EXISTS uq_users_d_hmac_email_hash_btree;
DROP INDEX IF EXISTS idx_map_d_hmac_email_hash_hash;
CREATE UNIQUE INDEX uq_users_d_hmac_email_hash_btree ON users_d_hmac (email_hash);
CREATE INDEX idx_map_d_hmac_email_hash_hash ON map_d_hmac USING hash (email_hash);

-- 구성 E_HMAC
DROP INDEX IF EXISTS uq_users_e_hmac_email_hash;
CREATE UNIQUE INDEX uq_users_e_hmac_email_hash ON users_e_hmac (email_hash);

-- 구성 F_HMAC
DROP INDEX IF EXISTS uq_users_f_hmac_email_hash_btree;
DROP INDEX IF EXISTS idx_users_f_hmac_email_hash_hash;
CREATE UNIQUE INDEX uq_users_f_hmac_email_hash_btree ON users_f_hmac (email_hash);
CREATE INDEX idx_users_f_hmac_email_hash_hash ON users_f_hmac USING hash (email_hash);
