-- docker-entrypoint-initdb.d는 최초 기동(빈 볼륨)에서만 실행된다.
-- pg_stat_statements는 docker-compose.yml의 shared_preload_libraries 설정과 짝을 이룬다.
-- (Flyway V3__pg_stat_statements.sql에서도 IF NOT EXISTS로 한 번 더 보장하므로 재적재 시에도 안전하다.)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
