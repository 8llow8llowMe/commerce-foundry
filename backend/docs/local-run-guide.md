# Local Run Guide

> 백엔드를 로컬에서 빌드·테스트하고 미들웨어를 띄우는 절차다. 명령은 모두 `backend/` 에서 실행한다.

## 1. 준비물

| 도구 | 버전 | 비고 |
|------|------|------|
| JDK | 21 | Gradle toolchain 이 21 을 요구한다. 자동 내려받기는 켜 두지 않았다 |
| Docker | Compose v2 | PostgreSQL · Redis 용. 빌드·단위 테스트만 할 때는 없어도 된다 |

Gradle 은 따로 설치하지 않는다. Wrapper(`./gradlew`, Windows 는 `gradlew.bat`)가 정해진 버전(9.7.1)을 받는다.

## 2. 환경변수

```bash
cp .env.example .env
```

- `.env` 는 커밋되지 않는다 (`.gitignore`). 값의 정본은 Vault 이고, 로컬에는 **테스트 값만** 넣는다 (`secret-management.md`).
- 미들웨어만 띄울 때 필요한 칸은 `DB_USERNAME` · `DB_PASSWORD` · `REDIS_PASSWORD` 셋이다. 로컬 전용이니 아무 값이나 정해 넣는다.
- 토스 키는 `test_sk_` 로 시작하는 테스트 키만 쓴다. 라이브 키는 로컬에 두지 않는다.
- JWT 키(`JWT_PRIVATE_KEY` · `JWT_PUBLIC_KEY`)는 로컬에서 openssl 로 직접 만든 RSA 2048 쌍을 넣는다. dev · prod 키를 가져오지 않는다.
  만들기와 한 줄 변환 명령은 `security-guide.md` §6-1 · §6-2 다. 저장소 밖 디렉터리에서 만들고, `.env` 에 넣은 뒤 개인키 파일은 지운다.
  `JWT_PUBLIC_KEY_PREVIOUS` 는 비워 둔다.

## 3. 미들웨어 (PostgreSQL · Redis)

```bash
docker compose -f docker-compose-local.yml up -d --wait   # healthcheck 통과까지 기다린다
docker compose -f docker-compose-local.yml down           # 종료 (데이터 유지)
docker compose -f docker-compose-local.yml down -v        # 데이터까지 삭제
```

| 서비스 | 주소 | 비고 |
|--------|------|------|
| PostgreSQL 17 | `localhost:5432/commerce_foundry` | 스키마 `auth` · `product` · `orders` · `payment` · `delivery` |
| Redis 7.4 | `localhost:${REDIS_PORT}` (기본 6379) | 비밀번호 필수 |

- 스키마는 `docker/postgres/init/01-create-schemas.sql` 이 **빈 볼륨으로 처음 뜰 때만** 만든다.
  스키마를 추가했다면 `down -v` 후 다시 올린다.
- compose 파일이 바뀐 PR 은 `backend-local-infra` 워크플로가 실제로 띄워 스키마·Redis 인증을 확인한다.

## 4. 빌드 · 테스트

```bash
./gradlew compileJava
./gradlew check
./gradlew :core:shared-commerce:cleanTest :core:shared-commerce:test --no-build-cache   # 테스트가 실제로 돈 결과
```

- `gradle.properties` 가 빌드 캐시를 켜 둔다. 테스트가 **실제로 돌았는지** 확인할 때는 `cleanTest` + `--no-build-cache` 를 붙인다.
  그냥 `test` 는 `UP-TO-DATE` · `FROM-CACHE` 로 건너뛰고도 성공으로 끝난다.
- CI(`backend-ci`)는 **테스트가 있어야 하는 모듈마다**(core 가 아닌 실행 모듈 전부 + `src/test/java` 가 있는 모듈) 실제 실행 건수(건너뜀 제외)를 세고,
  0건인 모듈이 있으면 실패시킨다. 로컬에서 같은 검사: `python ../.github/scripts/backend-test-count.py .`
- 서비스(`service/*`)의 컨텍스트 로딩 테스트(`*ApplicationTests`)는 H2(PostgreSQL 모드) test 프로파일로 돈다. Docker 없이 로컬에서도 실행된다.
  락 · `timestamptz` · 인덱스처럼 엔진에 기대는 동작은 여기서 검증하지 않는다. 리포지토리 테스트는 Testcontainers(PostgreSQL)로 따로 두고
  CI 에서 돌린다 (`bootstrap-conventions.md` "컨텍스트 로딩 테스트").

## 5. 서비스 실행

Eureka → 게이트웨이 → 서비스 순서로 터미널을 하나씩 열어 띄운다. Eureka · 게이트웨이는 DB · Redis 가 필요 없고,
도메인 서비스는 PostgreSQL(§3)과 `backend/.env` 의 `DB_URL` · `DB_USERNAME` · `DB_PASSWORD`(§2)가 필요하다.

```bash
./gradlew :cloud:service-discovery:bootRun    # Eureka 서버 — 8761 (local 은 127.0.0.1 에만 묶인다)
./gradlew :cloud:api-gateway:bootRun          # API 게이트웨이 — 8000
./gradlew :service:auth-service:bootRun       # 8081
./gradlew :service:product-service:bootRun    # 8082
./gradlew :service:order-service:bootRun      # 8083
./gradlew :service:payment-service:bootRun    # 8084
./gradlew :service:delivery-service:bootRun   # 8085
```

- **`bootRun` 은 `SPRING_PROFILES_ACTIVE` 가 없거나 비어 있으면 `local` 프로파일로 뜬다** (`backend/build.gradle`). 빌드한 jar 를 `java -jar` 로
  띄울 때는 적용되지 않는다 — 배포 환경이 프로파일을 빠뜨려도 로컬 설정으로 뜨지 않게 하려는 것이다.
- 로컬 전용 설정은 모듈의 `application-local.yml` 에 있다.

| 모듈 | `application-local.yml` 이 하는 일 |
|------|-------------------------------------|
| service-discovery | `server.address: 127.0.0.1` (같은 LAN 에서 레지스트리를 조작하지 못하게), self-preservation 끔 |
| api-gateway | CORS 허용 origin 기본값 `http://localhost:3000`. 공통 `application.yml` 의 기본값은 비어 있다(닫힘). dev 라우트(헬스 · Swagger UI) 켬 |
| 서비스 5종 | `backend/.env` 읽기, `ddl-auto: update`, Snowflake 고정값(`bootstrap-conventions.md` 배정표), Swagger 열기 |

| 확인 URL | 기대 |
|----------|------|
| `http://localhost:8761` | Eureka 대시보드. 띄우고 30초쯤 지나면 `API-GATEWAY` 와 서비스 5종이 등록된다 |
| `http://localhost:8761/actuator/health` | `"status":"UP"` |
| `http://localhost:8000/actuator/health` | `"status":"UP"` (actuator 는 `health` 만 노출한다) |
| `http://localhost:8000/health/{service-id}` (예: `/health/product-service`) | `"status":"UP"` — 게이트웨이 경유 서비스 헬스 체크. local · dev 만 |
| `http://localhost:8000/swagger-ui.html` | Swagger UI. 상단 목록에서 서비스 · 고객 / 어드민 문서를 고른다. local · dev 만 |
| `http://localhost:8000/api/v1/products` | 서비스가 떠 있지 않으면 `503` + `GATEWAY_002` 봉투 (`api-design-guide.md` §2-1). 떠 있으면 아직 API 가 없어 서비스가 낸 `404` 를 그대로 중계한다 |

- 게이트웨이가 읽는 `EUREKA_DEFAULT_ZONE` · `GATEWAY_CORS_ALLOWED_ORIGINS` 는 기본값(yml · `application-local.yml`)이 로컬 값과 같아
  `.env` 없이 뜬다. 바꿀 때는 환경변수로 넘긴다: `GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:3001 ./gradlew :cloud:api-gateway:bootRun`
- 서비스는 `application-local.yml` 의 `spring.config.import: optional:file:../../.env[.properties]` 로 `backend/.env` 를 읽는다.
  OS 환경변수가 `.env` 값보다 우선한다.
  - 경로는 **작업 디렉터리 기준**이다. `bootRun` 은 작업 디렉터리가 모듈 디렉터리라 맞는다.
  - IDE 로 띄울 때는 실행 구성의 작업 디렉터리를 모듈 디렉터리(`backend/service/<서비스>`)로 둔다.
  - 작업 디렉터리가 다르면 `optional` 이라 조용히 건너뛰고 §6 의 `'url' must start with "jdbc"` 로 실패한다.
- 헬스 체크 · Swagger 통합은 게이트웨이의 `gateway.dev-routes.enabled` 로 열고 닫는다(local · dev 만 `true`).
  운영의 헬스 체크는 게이트웨이를 거치지 않고 내부망에서 서비스에 직접 붙는다.
- Vault 주입 방식은 #32 에서 정한다.

## 6. 문제 해결

| 증상 | 원인 · 조치 |
|------|-------------|
| 한글이 깨진 파일이 diff 에 잡힌다 | CP949 로 저장됐다. UTF-8(no BOM)로 다시 저장한다 (`.editorconfig`) |
| `docker compose up` 이 `DB_USERNAME 을 채우세요` 로 멈춘다 | `.env` 가 없거나 칸이 비었다. §2 |
| 6379 포트가 이미 쓰이고 있다 | 다른 프로젝트의 Redis 다. `.env` 의 `REDIS_PORT` 를 바꾼다 |
| 서비스가 `Redis 설정이 기동 조건에 맞지 않습니다` 로 뜨지 않는다 | `REDIS_PASSWORD` 가 비었거나 yml 에 `timeout` · `connect-timeout` 이 없다. 메시지가 가리키는 항목을 채운다 (`coding-conventions.md` §9-7) |
| 서비스가 `security.jwt.public-key(환경변수 JWT_PUBLIC_KEY)` 로 시작하는 메시지로 뜨지 않는다 | 공개키가 비었거나 형식이 틀렸다. `BEGIN PUBLIC KEY` 공개키의 본문 한 줄을 넣는다 — 개인키 · PKCS#1 · 2048비트 미만은 받지 않는다 (`security-guide.md` §6) |
| auth 가 `JWT_PRIVATE_KEY 와 JWT_PUBLIC_KEY 가 한 쌍이 아닙니다` 로 뜨지 않는다 | 두 값이 다른 키 쌍에서 왔다. 같은 `jwt-private.pem` 에서 만든 공개키를 넣는다 (`security-guide.md` §6-3) |
| 새 스키마가 안 보인다 | init 스크립트는 첫 기동에만 돈다. `down -v` 후 다시 올린다 |
| 서비스가 `'url' must start with "jdbc"` 로 안 뜬다 | `DB_URL` 을 받지 못했다. `.env` 는 local 프로파일에서만 읽는다 — `backend/.env` 가 있는지, 작업 디렉터리가 모듈 디렉터리인지(§5), `SPRING_PROFILES_ACTIVE` 가 local 인지 본다 |
| 서비스가 `Failed to bind properties under 'snowflake.datacenter-id'` 로 안 뜬다 | local 이 아닌 프로파일로 떴고 `SNOWFLAKE_DATACENTER_ID` · `SNOWFLAKE_WORKER_ID` 가 없다. dev · prod 는 인스턴스마다 환경변수로 넣는다 (local 은 yml 고정값) |
| 게이트웨이 경유 `/health/{service-id}` 가 `503` + `GATEWAY_002` | 서비스가 꺼져 있거나 아직 Eureka 에 등록되지 않았다(기동 후 30초쯤 걸린다) |
| 게이트웨이 경유 `/health/{service-id}` 가 `503` + `{"status":"DOWN"}` | 서비스는 떠 있지만 DB 같은 구성 요소가 내려갔다. 헬스는 상세를 숨기므로 서비스 로그를 본다 |
| 게이트웨이 경유 `/health/{service-id}` · `/swagger-ui.html` 이 `404` | local · dev 프로파일이 아니라 dev 라우트가 꺼져 있다 (`gateway.dev-routes.enabled`) |
| Gradle 데몬·테스트 JVM 이 `EXCEPTION_ACCESS_VIOLATION`(jvm.dll)으로 죽는다 | JDK 21.0.4(Windows)에서 여러 번 관찰됐다(C1 컴파일러 스레드). `./gradlew --stop` 후 다시 돌리고, 재발하면 JDK 21 최신 패치로 올린다. 같은 코드가 재실행에서 통과해 코드 원인으로 보지 않는다 |
| `./gradlew: Permission denied` | 실행 비트가 빠졌다. `git update-index --chmod=+x backend/gradlew` 로 고쳐 커밋한다 (`chmod` 로 덮지 않는다) |
