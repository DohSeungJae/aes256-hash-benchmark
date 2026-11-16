# aes256-hash-benchmark

비결정적 AES256 암호화 컬럼을 SHA-256 / HMAC-SHA256 해시 매핑(blind index)으로 조회하는
구조를, PostgreSQL에서 최대 1000만 건 규모까지 부하 테스트로 검증하는 프로젝트.

**측정 결과: [summary.md](summary.md)** — 1만~1000만 건 규모별 A(AES 전체 스캔) vs F_HMAC(해시 인덱스) 로그인 성능

> 이 저장소는 [`암호화된 회원정보 조회 성능 검증 계획서`](#참고)의 구현체다. 계획서의
> 가설(H1 부터 H6)과 측정 방법은 그대로 따르고, 여기서는 "계획서가 요구하는 시스템을 어떻게
> 만들었는지"와 "어떻게 실행하는지"를 설명한다.

## 측정 결과

시간 복잡도는 예상대로 A(AES 전체 스캔)는 **O(n)**, F_HMAC(해시 인덱스)은 **O(1)**이었다.
1000만 건에서 A는 평균 797.7초(13.3분), F_HMAC은 55.4ms로 약 14,408배 차이가 났다.

![규모별 평균 로그인 시간](docs/images/login_time_by_scale.png)

![A ÷ F_HMAC 배율](docs/images/slowdown_ratio.png)

자세한 수치는 [summary.md](summary.md) 참고.

## 비교 구성

| 구성 | 구조 | 인덱스 | 해시 알고리즘 |
|---|---|---|---|
| A | user에 AES256 암호문만 | 없음 (전체 스캔 기준선) | — |
| B | user + map | 없음 | SHA-256 |
| C | user + map | B-tree | SHA-256 |
| D | user + map | hash | SHA-256 |
| E | user (map 없음) | B-tree | SHA-256 |
| F | user (map 없음) | hash | SHA-256 |
| C_HMAC / D_HMAC / E_HMAC / F_HMAC | C/D/E/F와 동일 | 동일 | **HMAC-SHA256** |

10가지 구성이 전부 별도 테이블로 같은 서버에 동시에 올라간다. 설계 배경과 각 구성이
왜 이렇게 나뉘는지는 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)에 정리했다.

## 요구 사항

- Java 17, Maven
- Docker / Docker Compose (PostgreSQL 16)
- [k6](https://k6.io/) (부하 테스트)
- `curl` (적재/검증 스크립트가 서버 API를 호출)

## 빠른 시작

```bash
# 1) 환경변수 설정
cp .env.example .env
# .env를 열어 DB_PASSWORD, AES_ENCRYPTION_KEY, HMAC_SECRET_KEY를 원하는 값으로 바꾼다.

# 2) PostgreSQL 기동
docker compose up -d
docker compose ps   # healthy 확인

# 3) 서버 빌드 & 실행 (Flyway가 10개 구성의 테이블을 자동 생성)
./mvnw clean package -DskipTests
set -a; source .env; set +a
java -jar target/ahbench.jar \
  --bench.encryption.key="$AES_ENCRYPTION_KEY" \
  --bench.hmac.key="$HMAC_SECRET_KEY"

# 4) (서버가 뜬 상태에서, 새 터미널) 100건으로 생성기/서비스 일치 확인 — 계획서 6장 1단계
./scripts/verify-sample.sh

# 5) 실제 데이터 생성 (예: 10만 건으로 먼저 소요 시간 확인)
./scripts/generate-data.sh 100000 100000 8

# 6) 해당 규모로 적재 + 인덱스 + ANALYZE
./scripts/load-data.sh 100000

# 7) k6 부하 테스트 (예: 로그인 시나리오, 구성 c)
k6 run --env CONFIG=c k6/s1_login.js

# 모든 필수 구성(A, C, E)에 대해 반복 실행하고 싶다면
./k6/run-all.sh s1_login.js a c e
```

## 디렉터리 구조

```
src/main/java/com/doh/ahbench/
  common/     ConfigId, ConfigShape, ConfigSpec, ConfigRegistry — 10개 구성 정의
  crypto/     AES256(jasypt), SHA-256, HMAC-SHA256
  security/   비밀번호(bcrypt)
  repo/       JdbcTemplate 기반 데이터 접근 (BenchRowRepository)
  bench/      3가지 shape 핸들러 + 레지스트리
  web/        REST 컨트롤러 (k6가 호출하는 API), 관리용 API
  generator/  CSV 데이터 생성기 (generate 프로필)
src/main/resources/db/migration/   Flyway DDL (테이블만; 인덱스는 적재 후 별도)
sql/indexes.sql                    적재 후 실행하는 인덱스 DDL
scripts/                           생성 → 적재 → 인덱스 → 크기측정 → EXPLAIN 파이프라인
k6/                                S1~S5 시나리오
docs/ARCHITECTURE.md               설계 결정과 계획서 대응표
```

## 시나리오 (계획서 8장)

| 파일 | 시나리오 | 확인 가설 |
|---|---|---|
| `k6/s1_login.js` | S1 로그인 | H1, H2, H3 |
| `k6/s2_signup.js` | S2 회원가입 | H4 |
| `k6/s3_withdraw.js` | S3 회원탈퇴 (가입 후 탈퇴) | H4 |
| `k6/s4_mixed.js` | S4 혼합 (읽기:쓰기 95:5 / 70:30, `WRITE_RATIO` 환경변수) | H4 |
| `k6/s5_notfound.js` | S5 존재하지 않는 이메일 조회 | 실패 경로 비용 |

## 측정 보조 스크립트

| 스크립트 | 역할 |
|---|---|
| `scripts/generate-data.sh` | 데이터 생성기 실행 (청크 CSV 생성) |
| `scripts/load-data.sh <SCALE>` | staging COPY → 10개 구성 테이블 적재 → 인덱스 → ANALYZE → 크기 기록 |
| `scripts/verify-sample.sh` | 100건으로 생성기/서버 간 복호화·해시 일치 확인 |
| `scripts/measure-sizes.sh` | `/api/admin/{config}/size` 결과를 CSV로 저장 |
| `scripts/explain-check.sh <config> <email>` | `EXPLAIN (ANALYZE, BUFFERS)` 결과 저장 |
| `k6/run-all.sh <scenario.js> [config...]` | 구성별 × N회 반복 실행 (중앙값 계산은 결과 취합 시) |

## 한계 (계획서 12장)

- 이 저장소는 노트북 1대 기준 상대 비교·경향 측정용이며, 절대 성능 주장을 하지 않는다.
- 구성 A는 큰 규모에서 끝까지 돌리기 어렵다 — 1만/10만까지만 정식 측정하고, 100만 이상은
  타임아웃 지점 확인용으로만 돌린다 (계획서 4장).
- 데이터는 규칙적으로 생성한 이메일이라 실제 분포와 다를 수 있다. 다만 해시 분포는
  입력과 무관하게 균일하므로 인덱스 성능에는 영향이 작다.
- 이 구조는 blind index라는 기존 패턴이며, 새 기법을 제안하는 것이 아니라 계획서가
  세운 가설을 실측으로 검증하는 것이다.

## 참고

- 계획서: 암호화된 회원정보 조회 성능 검증 계획서 (2026. 10. 15.)
- 블로그: 「문제 해결 11 | 해시 값을 PK로 가지는 테이블을 통해」(2025. 1. 22.)
