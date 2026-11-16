-- 계획서 9장 "DB 내부" 지표: pg_stat_statements로 쿼리별 소요 시간을 수집한다.
-- docker-compose.yml이 shared_preload_libraries=pg_stat_statements로 서버를 띄우므로
-- 여기서는 익스텐션만 활성화한다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
