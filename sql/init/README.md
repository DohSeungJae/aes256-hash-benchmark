이 디렉터리는 docker-compose.yml의 initdb 마운트 대상입니다.
실제 스키마 관리는 Flyway(src/main/resources/db/migration)가 맡으므로 테이블/인덱스를
여기서 만들지 않습니다. 최초 기동(빈 볼륨) 시 1회만 실행되는 익스텐션 활성화만 둡니다.
