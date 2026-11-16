# 아키텍처

이 문서는 `암호화된 회원정보 조회 성능 검증 계획서`(이하 "계획서")를 이 저장소가 어떻게
구현했는지 설명한다. 결과 해석이나 가설 검증 방법은 계획서 본문을 따르고, 여기서는
"코드가 계획서의 어느 부분을 어떻게 구현했는지"만 다룬다.

## 1. 전체 구조

하나의 Spring Boot 서버 안에 계획서 4장의 구성 A~F와, 11장 보안 권고(HMAC-SHA256)를
적용한 C_HMAC/D_HMAC/E_HMAC/F_HMAC까지 **10가지 구성을 모두 별도의 테이블로 동시에**
올려둔다. 사용자 요청("분리된 도메인/테이블로 모든 것을 동시에 테스트")에 따른 구조다.

```
k6 (S1~S5)  ──HTTP──>  /api/bench/{config}/login|signup|withdraw
                              │
                              ▼
                    BenchmarkHandlerRegistry
                     (ConfigId -> 핸들러 조회)
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                 ▼
   FullScanConfigHandler  MapTableConfigHandler  InlineConfigHandler
       (구성 A)           (구성 B/C/D, +HMAC)      (구성 E/F, +HMAC)
            │                 │                 │
            └─────────────────┼─────────────────┘
                              ▼
                     BenchRowRepository (JdbcTemplate)
                              │
                              ▼
                         PostgreSQL (users_*, map_*)
```

## 2. 왜 10개 구성이 3개의 "모양(shape)"으로 줄어드는가

계획서 4장의 A~F는 테이블 구조상 3가지로 묶인다 (`common/ConfigShape`):

| Shape | 해당 구성 | 구조 |
|---|---|---|
| `FULL_SCAN` | A | 암호문만 저장, 인덱스 없음, 전체 스캔 |
| `MAP_TABLE` | B, C, D, C_HMAC, D_HMAC | user(해시) + map(해시↔암호문) 두 테이블 |
| `INLINE` | E, F, E_HMAC, F_HMAC | user 한 테이블에 해시+암호문 |

같은 모양은 조회/가입/탈퇴 로직이 완전히 같고, **테이블 이름과 해시 알고리즘만** 다르다
(`common/ConfigRegistry.ALL`). 그래서 핸들러 클래스는 딱 3개(`FullScanConfigHandler`,
`MapTableConfigHandler`, `InlineConfigHandler`)만 있고, `BenchmarkHandlerRegistry`가
`ConfigRegistry.ALL`을 순회하며 10개 인스턴스를 만들어 등록한다.

B/C/D의 차이, E/F의 차이는 **이 코드에 없다** — 전부 인덱스 종류(없음/B-tree/hash)로만
갈라지고, 그 차이는 `sql/indexes.sql`에만 있다. 같은 코드로 여러 구성을 동시에 돌려야
"인덱스 종류"만 변수로 남는다는 계획서 9장의 공정성 조건을 지키기 위함이다.

## 3. JPA 엔티티 대신 JdbcTemplate을 쓴 이유

블로그 원본은 `@Entity`/`Repository` 조합을 썼지만, 이 구현은 `JdbcTemplate` 기반의
단일 `BenchRowRepository`가 테이블 이름을 파라미터로 받는 방식을 쓴다.

1. 계획서 7장이 지적하듯 Hibernate `@Index`는 `USING hash` 인덱스를 지원하지 않는다.
   DDL은 어차피 Flyway로 직접 관리해야 하므로 ORM 매핑의 이점이 줄어든다.
2. 이 프로젝트가 재는 것은 "쿼리·인덱스·복호화 비용"이지 ORM 오버헤드가 아니다.
   10개 구성이 서로 다른 ORM 매핑/영속성 컨텍스트 차이로 각기 다른 오버헤드를 갖게
   되면 비교의 공정성이 흔들린다. 10개 구성이 완전히 같은 방식으로 쿼리를 실행하게
   하는 것이 더 중요하다고 판단했다.

테이블/컬럼 이름은 항상 `ConfigRegistry`에 고정된 상수에서만 오고 사용자 입력이 SQL
식별자로 들어가는 경로는 없다 (SQL 인젝션 경로 없음). 값(해시·암호문·id)은 전부
`PreparedStatement` 파라미터로 바인딩한다.

## 4. 트랜잭션 경계는 왜 리포지토리에 있는가

`MapTableConfigHandler` 같은 핸들러는 `BenchmarkHandlerRegistry`가 `new`로 직접 만든
평범한 객체다 — Spring이 관리하는 빈이 아니다. Spring의 `@Transactional`은 AOP
프록시(빈)에만 적용되므로, 핸들러 메서드에 `@Transactional`을 붙여도 아무 효과가
없다. 그래서 user+map을 함께 insert/delete하는 원자성(계획서 7장, 11장 "삭제 정합성")은
실제로 Spring이 관리하는 빈인 `BenchRowRepository`의 `insertMapShapeRowPair` /
`deleteMapShapeRowPair`에 `@Transactional`로 걸려 있다.

## 5. 스레드 안전성

- AES256 암호화는 `AES256TextEncryptor`가 아니라 `PooledPBEStringEncryptor`를 직접
  써서 구현했다 (`Aes256CipherService`). 계획서 6장이 생성기에 대해 지적한
  "`AES256TextEncryptor`는 스레드 안전성이 보장되지 않는다"는 문제는 서버에도 똑같이
  적용된다 — k6 VU 여러 개가 같은 싱글턴 빈을 동시에 호출하기 때문이다.
- SHA-256/HMAC-SHA256은 호출마다 `MessageDigest.getInstance` / `Mac.getInstance`로
  새 인스턴스를 받아 쓰므로(둘 다 스레드 불안전 API) 동시성 문제가 없다.
- 데이터 생성기(`DataGeneratorRunner`)는 서버와 **같은 Spring 빈**을 재사용한다.
  따라서 "생성기와 서버가 같은 키/라이브러리를 써야 한다"(계획서 6장 위험 요소)는
  조건이 구조적으로 보장된다 — 같은 코드이기 때문이다. 실행 시 `--bench.encryption.key`,
  `--bench.hmac.key`를 서버와 동일하게 주면(즉, 같은 `.env`) 끝이다.

## 6. 인덱스를 Flyway가 아니라 적재 스크립트가 만드는 이유

Flyway 마이그레이션(`V1`, `V2`)은 **테이블만** 만들고 인덱스를 만들지 않는다. 인덱스는
`scripts/load-data.sh`가 대량 적재를 끝낸 뒤 `sql/indexes.sql`을 실행해서 만든다.

계획서 6장 "적재 절차": *"적재 후 CREATE INDEX, ANALYZE 순서로 실행한다"* — 대량
적재 전에 인덱스가 있으면 삽입마다 인덱스를 갱신하는 비용이 끼어들어 적재 자체의
측정이 왜곡된다. 서버가 뜬 시점(Flyway 실행 시점)에는 데이터가 없으므로, 인덱스
생성을 적재 스크립트 쪽으로 미뤄 계획서의 순서를 그대로 지켰다.

## 7. 각 구성이 공유하는 것 / 공유하지 않는 것

공유(공정성 조건, 계획서 9장):
- 같은 JVM, 같은 HikariCP 풀, 같은 `Aes256CipherService` 빈, 같은 `PasswordService`(bcrypt)
- 모든 시드 계정이 같은 더미 비밀번호를 씀 (`scripts/load-data.sh`의 `DUMMY_PASSWORD`)

공유하지 않음(측정 대상 변수):
- 테이블 구조(FULL_SCAN/MAP_TABLE/INLINE)
- 인덱스 종류(없음/B-tree/hash)
- 해시 알고리즘(SHA-256/HMAC-SHA256)

## 8. 계획서 10장 단계별 진행과 이 저장소의 대응

| 단계 | 계획서 내용 | 이 저장소에서 |
|---|---|---|
| 1단계 준비 | 노트북 환경, 서버 구성, DDL 작성 | `docker-compose.yml`, `pom.xml`, `src/main/java`, `src/main/resources/db/migration` |
| 2단계 파이프라인 검증 | 100건 생성, 복호화/해시 일치 확인, k6 동작 확인 | `scripts/verify-sample.sh`, 작은 scale로 `k6 run` |
| 3단계 본 측정 | 1만→10만→100만, 구성 A/C/E(+B/D/F) 측정 | `scripts/generate-data.sh` → `scripts/load-data.sh <N>` → `k6/run-all.sh` |
| 4단계 대규모 측정 | 1000만, 구성 C/E(+D/F), A는 타임아웃 지점만 | 동일 스크립트를 10,000,000 규모로 실행 (A는 작은 VU/짧은 duration으로 한계만 확인) |
| 5단계 정리 | 그래프, README, 블로그 후속 글 | `results/*.summary.json`을 모아 분석 (저장소 바깥, 사람이 하는 작업) |

이 저장소가 끝내는 지점은 "측정 가능한 상태로 만드는 것"까지다. 실제 1만~1000만 규모
멀티스케일·멀티머신 측정과 결과 분석은 계획서 5장이 명시한 환경(전용 노트북 + 별도
부하 생성기)에서 사용자가 직접 실행한다.

## 9. 알아둘 설계 결정 (한눈에)

- HMAC 변형은 C/D/E/F에만 둔다. A는 해시를 쓰지 않고, B는 "인덱스 없음" 자체가
  측정 목적이라 해시 알고리즘 종류가 결과에 영향을 주지 않기 때문이다.
- D/F에서 hash 인덱스는 UNIQUE를 지원하지 않으므로, 중복 가입 방지를 위한 B-tree
  unique 인덱스를 hash 인덱스와 함께 둔다 (계획서 7장이 미리 지적한 내용).
- S2(가입)는 실행할수록 데이터가 늘어난다. S3(탈퇴)와 S4(혼합)의 쓰기 부분은 가입 후
  즉시 탈퇴시켜 데이터 규모를 유지한 채 쓰기 비용만 측정한다.
