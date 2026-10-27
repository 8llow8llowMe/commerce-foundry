# Deploy Guide

> 백엔드 7개 모듈(cloud 2 + service 5)을 컨테이너로 배포하는 규칙이다. Dockerfile · compose 는 모듈 디렉터리에 있다.
> 파이프라인은 #198, Infra 등록(포트 값 · 네트워크 이름)은 #197, Vault 주입은 #32 에서 정한다. 정해지면 이 문서를 갱신한다.
> **공개 저장소다.** 서버 주소 · 호스트 이름 · 도메인 · 포트 실값 · 시크릿을 이 문서와 compose 에 쓰지 않는다. 값은 env 로만 받는다.

## 1. 배포 모델

```text
GitHub (develop / main)
-> Jenkins 빌드 agent   ./gradlew :<group>:<module>:bootJar
-> Jenkins 배포 agent   모듈 디렉터리 + jar 를 배포 경로로 옮기고 jar 를 app.jar 로 둔다
                         Vault 값 → .env.runtime (KEY='value', chmod 600, up 뒤 삭제)
                         docker compose -p commerce-<module>-<env> --env-file .env.runtime \
                           -f docker-compose-<module>.yml up -d --build --wait <module>-<env>
-> 헬스 확인             --wait 가 healthcheck 가 healthy 가 될 때까지 기다린다(§5)
```

- 레지스트리를 쓰지 않는다. 이미지는 배포 호스트에서 `--build` 로 만든다. Dockerfile 은 `app.jar` 하나만 복사한다(싱글 스테이지, 이미지 안에서 Gradle 을 돌리지 않는다).
- bootJar 산출물은 `backend/<group>/<module>/build/libs/<module>-<version>.jar` 하나다(예: `service-discovery-0.0.1-SNAPSHOT.jar`). `-plain.jar` 는 만들지 않는다.
- 빌드 컨텍스트는 모듈 디렉터리다. `.dockerignore` 가 `app.jar` 말고는 보내지 않는다.
- 애플리케이션은 `.env` 파일을 읽지 않는다(local 프로파일만 읽는다). 값은 compose 의 `environment` 로만 들어간다.
- **`.env.runtime` 규칙** (#198 파이프라인도 이 형식으로 쓴다)
  - 값은 작은따옴표로 감싼다 — `KEY='value'`. compose 는 따옴표 없는 값의 `$` 를 보간하고 ` #` 뒤를 주석으로 잘라, 시크릿에 그 문자가 들면 값이 조용히 바뀐다.
    작은따옴표 안에서는 보간 · 주석 처리가 없다. 값에 작은따옴표가 들면 안 된다(만들 때 거른다).
  - 파일은 `chmod 600` 으로 만들고 `up` 이 끝나면(성공 · 실패 모두) 지운다. 배포 경로에 시크릿 파일을 남기지 않는다.
- **시크릿 노출 한계** — 값이 컨테이너 환경변수로 들어가므로 호스트에서 docker 권한이 있으면 `docker inspect` 로 보인다.
  배포 호스트의 docker 그룹 · sudo 권한을 시크릿 열람 권한으로 다룬다. docker secrets · 파일 마운트로 바꿀지는 #32 에서 검토한다.

## 2. 이름 규칙

| 항목 | 규칙 | 예 |
|------|------|----|
| Dockerfile | `<module>.Dockerfile` (모듈 디렉터리) | `service/auth-service/auth-service.Dockerfile` |
| compose 파일 | `docker-compose-<module>.yml` (모듈 디렉터리) | `service/auth-service/docker-compose-auth-service.yml` |
| compose 서비스 | `<module>-{dev\|prod}` | `auth-service-dev` |
| 이미지 | `commerce-<module>:{dev\|prod}` | `commerce-auth-service:dev` |
| 컨테이너 | `commerce-<module>-{dev\|prod}` | `commerce-auth-service-dev` |
| compose 프로젝트 | `commerce-<module>-{dev\|prod}` (`-p` 로 반드시 준다) | `commerce-auth-service-dev` |

- dev · prod 는 다른 서버에 올릴 수도, 한 호스트에 함께 올릴 수도 있다. 아래 규칙은 둘 다에서 안전하게 정했다.
- **이미지 이름은 dev · prod 가 같고 환경은 태그로 가른다.** 같은 호스트에 dev · prod 가 함께 있어도 dev 빌드가 prod 가 쓰는 이미지를 덮지 않는다
  (태그까지 같으면 prod 를 `--build` 없이 다시 올릴 때 dev jar 로 뜬다).
- **compose 프로젝트는 환경마다 다르다.** `up --remove-orphans` 가 다른 환경 · 다른 모듈의 컨테이너를 지우지 않는다.
  `-p` 를 빼면 디렉터리 이름(`auth-service`)이 프로젝트가 돼 같은 호스트의 다른 compose 와 섞인다 — compose 파일에 `name:` 을 두지 않으므로 반드시 준다.
- 프로파일은 서비스 이름에 묶여 있다(`<module>-dev` → `SPRING_PROFILES_ACTIVE=dev`, `-prod` → `prod`). env 파일 값에 맡기지 않는다 —
  prod 컨테이너가 dev 프로파일(`ddl-auto: update` · Swagger 열림)로 뜨는 일을 막는다. prod 프로파일 yml 은 없어 공통 `application.yml` 의 닫힌 기본값으로 뜬다.

## 3. 포트 · 메모리

컨테이너 안 포트는 `application.yml` 의 `server.port` 로 고정이고 dev · prod 가 같다(`service-inventory.md`). 호스트 포트만 env 로 다르다.

| 모듈 | 컨테이너 포트 | 호스트 포트 키 (값은 #197) | 호스트 바인딩 | 메모리 상한 키 | 기본값 dev / prod |
|------|---------------|----------------------------|----------------|----------------|-------------------|
| service-discovery | 8761 | `SERVICE_DISCOVERY_PORT_{DEV,PROD}` | 루프백 | `SERVICE_DISCOVERY_MEM_LIMIT_{DEV,PROD}` | 384m / 512m |
| api-gateway | 8000 | `API_GATEWAY_PORT_{DEV,PROD}` | `API_GATEWAY_BIND_{DEV,PROD}` (기본 루프백) | `API_GATEWAY_MEM_LIMIT_{DEV,PROD}` | 512m / 768m |
| auth-service | 8081 | `AUTH_SERVICE_PORT_{DEV,PROD}` | 루프백 | `AUTH_SERVICE_MEM_LIMIT_{DEV,PROD}` | 768m / 1g |
| product-service | 8082 | `PRODUCT_SERVICE_PORT_{DEV,PROD}` | 루프백 | `PRODUCT_SERVICE_MEM_LIMIT_{DEV,PROD}` | 768m / 1g |
| order-service | 8083 | `ORDER_SERVICE_PORT_{DEV,PROD}` | 루프백 | `ORDER_SERVICE_MEM_LIMIT_{DEV,PROD}` | 768m / 1g |
| payment-service | 8084 | `PAYMENT_SERVICE_PORT_{DEV,PROD}` | 루프백 | `PAYMENT_SERVICE_MEM_LIMIT_{DEV,PROD}` | 768m / 1g |
| delivery-service | 8085 | `DELIVERY_SERVICE_PORT_{DEV,PROD}` | 루프백 | `DELIVERY_SERVICE_MEM_LIMIT_{DEV,PROD}` | 768m / 1g |

- **외부에는 게이트웨이만 연다**(`architecture-guide.md` §6). Eureka · 서비스는 호스트 루프백(`127.0.0.1`)에만 포트를 연다 — 게이트웨이 라우팅 ·
  Eureka 등록 · 서비스 간 호출은 같은 docker 네트워크 안에서 닿으므로 호스트 포트는 호스트 안 점검용이다. Eureka 레지스트리는 HTTP Basic 이다(#248, `security-guide.md` §12).
  평문 HTTP 라 모듈을 다른 호스트로 나눠야 하면 바인딩과 전송 암호화를 같이 정한다(#197 · #32).
- 게이트웨이 바인드 주소는 `API_GATEWAY_BIND_{DEV,PROD}` 로 받고 기본값은 루프백이다(닫힌 쪽). 앞단(BFF · LB)이 닿아야 하는 주소만 넣는다 — 외부 노출 규칙은 §6.
- **한 compose 파일에 dev · prod 가 같이 있고 compose 는 파일 전체를 보간한다.** 포트 키는 `:?`(비면 실패)라 dev 배포의 env 에도 `_PROD` 키가 있어야 한다(반대도 같다).
  파이프라인(#198)은 반대 환경의 `<MODULE>_PORT_{DEV|PROD}` 를 `'0'` 자리값으로 채운다 — 그 서비스는 올리지 않아 포트를 잡지 않으므로
  **Vault 에는 자기 환경 포트만 넣는다.** `_BIND_` · `_MEM_LIMIT_` 은 compose 기본값이 있어 채우지 않는다(`jenkins-cicd-guide.md`). 손으로 배포할 때도 같은 방식으로 채운다.
- 힙은 Dockerfile 의 `-XX:MaxRAMPercentage=70.0` 이 메모리 상한의 70% 로 정한다. 나머지 30% 는 메타스페이스 · 스레드 · 다이렉트 버퍼 몫이다.
  기본값은 출발점이다 — 실제 RSS 를 보고 조정하고, 상한을 줄이면 비율 때문에 non-heap 이 같이 줄어드는 것을 감안한다. OOM 이면 `-XX:+ExitOnOutOfMemoryError` 로 끝나 `restart` 가 다시 띄운다.

## 4. 환경변수 계약

`backend/.env.example` 이 키 이름의 정본이다. **Vault 키 이름 = 환경변수 이름 = compose 의 키 이름**이다(`secret-management.md`).
compose 의 `environment` 에는 **그 모듈이 실제로 읽는 키만** 넘긴다. 아래 표는 각 모듈 `application.yml` · `application-dev.yml` 의 `${KEY}` 를 추출한 것이다.

### 4-1. 모듈별 키

| 키 | 시크릿 | compose 보간 | discovery | gateway | auth | product | order | payment | delivery |
|----|--------|--------------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `EUREKA_HOST` | - | `:?` 필수 (§4-2) | | O | O | O | O | O | O |
| `EUREKA_PASSWORD` | O | `:?` 필수 | O | O | O | O | O | O | O |
| `DB_URL` · `DB_USERNAME` | - | `:?` 필수 | | | O | O | O | O | O |
| `DB_PASSWORD` | O | `:?` 필수 | | | O | O | O | O | O |
| `REDIS_HOST` · `REDIS_PORT` | - | `:?` 필수 | | O | O | | | | |
| `REDIS_PASSWORD` | O | `:?` 필수 | | O | O | | | | |
| `SNOWFLAKE_DATACENTER_ID` | - | `:?` 필수 | | | O | O | O | O | O |
| `JWT_PUBLIC_KEY` | - (Vault 배포) | `:?` 필수 | | O | O | O | O | O | O |
| `JWT_PUBLIC_KEY_PREVIOUS` | - | `:-` 빈 값 | | O | O | O | O | O | O |
| `JWT_PRIVATE_KEY` | O | `:?` 필수 | | | O | | | | |
| `INTERNAL_JWT_SECRET` | O | `:?` 필수 | | | O | O | O | O | O |
| `INTERNAL_JWT_SECRET_PREVIOUS` | O | `:-` 빈 값 | | | O | O | O | O | O |
| `GATEWAY_CORS_ALLOWED_ORIGINS` · `GATEWAY_TRUSTED_PROXIES` | - | `:-` 빈 값 | | O | | | | | |
| `GATEWAY_CLIENT_IP_HASH_KEY` | O | `:-` 빈 값 | | O | | | | | |
| `ADMIN_SEED_EMAIL` · `ADMIN_SEED_PASSWORD` | O | `:-` 빈 값 | | | O | | | | |
| `ADMIN_SEED_NAME` | - | `:-` 빈 값 | | | O | | | | |
| `AUTH_LOGIN_GUARD_HASH_KEY` | O | `:-` 빈 값 | | | O | | | | |
| `AUTH_SOCIAL_ENABLED` · `KAKAO_LOGIN_ENABLED` · `NAVER_LOGIN_ENABLED` | - | `:-false` | | | O | | | | |
| `KAKAO_CLIENT_ID` · `NAVER_CLIENT_ID` · `KAKAO_REDIRECT_URI` · `NAVER_REDIRECT_URI` | - | `:-` 빈 값 | | | O | | | | |
| `KAKAO_CLIENT_SECRET` · `NAVER_CLIENT_SECRET` | O | `:-` 빈 값 | | | O | | | | |
| `STORE_FEATURES_CUSTOM_ORDER` · `STORE_FEATURES_PREORDER` · `STORE_FEATURES_PRODUCT_VIDEO` | - | `:-false` | | | | O | | | |
| `STORE_FEATURES_INTERNATIONAL_SHIPPING` | - | `:-false` | | | | | | | O |
| `TIME_ZONE` | - | `:-UTC` | O | O | O | O | O | O | O |
| `COMMERCE_DOCKER_NETWORK` · 포트 · 메모리 (§3) | - | 네트워크 · 포트 `:?`, 메모리 `:-` | O | O | O | O | O | O | O |

compose 가 env 파일과 무관하게 고정하는 값:

| 키 | 값 | 이유 |
|----|----|------|
| `SPRING_PROFILES_ACTIVE` | 서비스마다 `dev` / `prod` | §2 |
| `SNOWFLAKE_WORKER_ID` | auth 1 · product 2 · order 3 · payment 4 · delivery 5 | (datacenter, worker) 쌍이 인스턴스마다 달라야 한다. env 하나로 넣으면 서비스 5종이 같은 값을 받는다. local 배정표(`bootstrap-conventions.md`)와 같은 번호이고, 서비스당 컨테이너가 하나(`container_name` 고정)라 겹치지 않는다. 인스턴스를 늘리면 다시 정한다 |

- Eureka 에 IP 로 등록하는 설정(`eureka.instance.prefer-ip-address: true`)은 env 가 아니라 **각 모듈 `application.yml`** 에 있다 (#249).
  기본은 컨테이너 호스트 이름(컨테이너 ID)으로 등록돼 게이트웨이 `lb://` · Feign 이 이름을 풀지 못할 수 있어서, dev · prod 모두 docker 네트워크 IP 로 등록한다.
  compose 밖에서 띄워도 같다. local 만 `application-local.yml` 이 `false` 로 끈다(호스트 이름 등록).

### 4-2. 보간 규칙

| 형태 | 언제 | 이유 |
|------|------|------|
| `${KEY:?deploy-guide.md 4절}` | yml 에 기본값이 없는 키(필수), 포트 · 네트워크 | 빠지면 컨테이너가 재시작 루프에 빠지기 전에 `docker compose config` 단계에서 키 이름과 함께 멈춘다 |
| `${KEY:-}` | yml 기본값이 빈 값인 키 | yml 의 `${KEY:}` 와 같은 결과다. 시크릿이어도 "비면 그 기능을 끈다" 가 yml 의 계약이라(`ADMIN_SEED_*` · `*_HASH_KEY` · `*_PREVIOUS`) 빈 값만 허용한다 |
| `${KEY:-false}` 등 | yml 기본값이 있는 키 | **yml 기본값과 같은 값**만 쓴다. 다르게 두면 정본이 둘이 된다 |
| 시크릿 | - | **빈 값이 아닌 기본값을 두지 않는다.** |

- `EUREKA_HOST` 는 yml 기본값(`localhost`)이 있지만 compose 에서는 필수로 바꿨다. 컨테이너 안의 localhost 는 자기 자신이라
  기본값으로 뜨면 등록이 실패한다. 같은 네트워크면 Eureka 컨테이너 이름을 쓴다 — `commerce-service-discovery-<env>`.
  `defaultZone` URL(`http://eureka:${EUREKA_PASSWORD}@${EUREKA_HOST}:8761/eureka/`)은 yml 이 조립한다(#248 전의 `EUREKA_DEFAULT_ZONE` 은 없어졌다).
- `EUREKA_PASSWORD` 는 service-discovery · 게이트웨이 · 서비스 전부 **같은 값**이고 환경마다 다르다. `openssl rand -hex 32` 로 만든다 —
  영문 · 숫자 · `. _ ~ -` 밖의 문자가 있거나 32자 미만이면 Eureka 가 뜨지 않는다(`security-guide.md` §12).
- `GATEWAY_CORS_ALLOWED_ORIGINS` 는 비어도 뜨지만 어떤 origin 도 허용하지 않는다(닫힌 기본값). 배포 환경은 프론트 origin 을 채운다.
- `STORE_FEATURES_*` 는 dev 프로파일 yml 이 `true` 로 덮어 dev 에서는 env 를 읽지 않는다.
- 아직 어느 모듈의 yml 도 읽지 않는 `.env.example` 키(`TOSS_*` · `STORAGE_PUBLIC_BASE_URL` 밖의 `STORAGE_*` · `STORE_BUSINESS_*` · `MESSAGING_ENABLED` · `KAFKA_*`(§4-4))는 compose 에 넘기지 않는다.
  `STORAGE_PUBLIC_BASE_URL` 은 product-service 가 메인 전시 미디어 주소 판정에 읽는다(#63, `:-` 빈 값 — 비면 미디어 쓰기만 503).
  해당 기능이 yml(또는 `storage.*` 같은 relaxed binding)로 키를 읽기 시작하는 PR 에서 그 모듈 compose 의 `environment` 에 같이 더한다(§8).
  `KAFKA_PORT` 는 로컬 compose(`docker-compose-local.yml`) 전용이라 배포 compose 에 넘기지 않는다.

### 4-3. 배포 전 반드시 채울 것

| 키 | 없으면 |
|----|--------|
| `COMMERCE_DOCKER_NETWORK` · 자기 환경의 `<MODULE>_PORT_{DEV|PROD}` | compose 해석 실패로 배포 중단. 반대 환경 포트는 파이프라인이 `'0'` 자리값으로 채운다(§3) |
| `EUREKA_HOST` | compose 해석 실패 |
| `EUREKA_PASSWORD` | compose 해석 실패. 7개 모듈 같은 값, 형식이 틀리면 Eureka 가 기동하지 않는다: `security-guide.md` §12 |
| `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` | compose 해석 실패. dev 와 prod 는 다른 DB 서버라 값은 환경별 secret 에서 갈라 넣는다(키 이름은 같다). 서비스 5종은 한 DB 의 서비스별 스키마(`auth` · `product` · `orders` · `payment` · `delivery`, yml `hibernate.default_schema`)를 쓰므로 URL 은 환경당 하나다. 스키마는 미리 만들어 둔다 — JPA 는 스키마를 만들지 않는다. 정본은 [`docker/postgres/init/01-create-schemas.sql`](../docker/postgres/init/01-create-schemas.sql) 이다. 환경 분리 · 계정 원칙은 [`service-inventory.md`](service-inventory.md) §4-2 |
| `REDIS_*` | compose 해석 실패. auth 와 gateway 는 같은 Redis 여야 한다 |
| `SNOWFLAKE_DATACENTER_ID` | compose 해석 실패. 0~31 |
| `JWT_PUBLIC_KEY` · `JWT_PRIVATE_KEY` | compose 해석 실패. 만들기 · 교체: `security-guide.md` §6 |
| `INTERNAL_JWT_SECRET` | compose 해석 실패. 서비스 5종 같은 값: `security-guide.md` §9-6 |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | 뜨지만 브라우저 요청이 전부 CORS 로 막힌다 |

- **`ADMIN_SEED_*` 는 최초 시드 뒤 secret 에서 비우고 다시 배포한다**(`security-guide.md` §11-3). 관리자가 이미 있으면 시드는 아무것도 하지 않으므로,
  남겨 둘 이유 없이 비밀번호만 컨테이너 env 에 계속 노출된다.

### 4-4. Kafka (공유 클러스터, #310 · 설계 #304 A 절)

Infra 의 Kafka 는 3노드 KRaft 클러스터(apache/kafka 4.3 · RF 3 · min ISR 2)를 여러 프로젝트가 공유한다. 서비스는 messaging-core(#306)가 생긴 뒤
아래 키를 읽는다 — 그 PR 이 Kafka 를 쓰는 모듈의 yml · compose(§4-2)에 키를 더하고, Jenkins 필수 키는 compose 의 `:?` 에서 자동으로 잡힌다
(`jenkins-cicd-guide.md`). **주소 · 계정 값은 저장소에 쓰지 않는다.**

| 키 | 비밀 · 위치 | 비고 |
|----|-------------|------|
| `MESSAGING_ENABLED` | 아니오 · 배포 설정 | 기본 꺼짐. 켜면 아래 키가 모두 필수다 |
| `KAFKA_BOOTSTRAP_SERVERS` | Vault (dev/prod) | 공유 클러스터 주소(쉼표로 브로커 여럿) — `DB_URL` 과 같이 취급한다 |
| `KAFKA_NAMESPACE` | 아니오 · 배포 설정 | `commerce-foundry.<env>`. 마지막 토막이 실행 프로파일과 다르면 뜨지 않는다(프로파일 없음 = prod) |
| `KAFKA_SECURITY_PROTOCOL` · `KAFKA_SASL_MECHANISM` | 아니오 · 배포 설정 | 지금은 `PLAINTEXT` · 빈 값 |
| `KAFKA_SASL_USERNAME` · `KAFKA_SASL_PASSWORD` | Vault | 지금은 비운다. 둘 중 하나만 채우면 기동 실패 |

| 항목 | 정한 것 | 결정 대기 |
|------|---------|-----------|
| 이름 | **namespace `commerce-foundry.<env>`**(local · dev · prod). 토픽 `<ns>.<생산 서비스>.<aggregate>-<event>`(예: `commerce-foundry.dev.order.order-expired`), DLT `<토픽>.dlt`, 컨슈머 그룹 `<ns>.<소비 서비스>.<목적>`(예: `commerce-foundry.dev.order-service.payment-approved`). 다른 고객사가 같은 클러스터를 쓰면 namespace 만 바꾼다 — 코드에 스토어 이름을 넣지 않는다 | prefix 를 `commerce-foundry` 로 둘지 `cf` 로 줄일지(#304 Q7 — 권장 `commerce-foundry`) |
| 토픽 생성 | **앱은 만들지 않는다**(브로커 자동 생성 끔 · `spring.kafka.admin.auto-create=false` · `NewTopic` 빈 금지 — #306). 목록의 정본은 `backend/docker/kafka/topics.txt`(`<생산 서비스>.<aggregate>-<event> <보존 ms>`). 로컬은 `create-topics.sh` 가 만들고, dev · prod 는 `create-topics.sh dev` · `prod` 가 출력한 목록을 Infra 레포의 선언 스크립트가 `--if-not-exists` 로 만든다. 파티션 local 3 · dev 3 · prod 6, 복제 local 1 · dev · prod 3, DLT 는 같이 만든다 | prod 파티션 6 · 보존 168h 확정, dev · prod 가 한 클러스터를 namespace 로만 나눠 써도 되는지(#304 Q6) |
| 토픽 추가 절차 | **이벤트를 정의하는 PR(#307 · #308 · #309 …)이 `topics.txt` 에 그 토픽을 더하고**, PR 본문에 `create-topics.sh dev` · `prod` 출력을 붙여 Infra 반영을 요청한다. 앱은 기동 때 토픽이 있는지 보고 없으면 뜨지 않는다(#306) — Infra 반영이 배포보다 먼저다 | — |
| 접속 주소 | 키 이름 `KAFKA_BOOTSTRAP_SERVERS`, 값은 Vault | **결정 대기** — backend 컨테이너가 공유 클러스터에 닿는 방식(EXTERNAL 리스너 [권장] vs Infra 공유 네트워크 합류, #304 Q1) |
| 보안 | 로컬 · 지금은 `PLAINTEXT`, SASL 키는 예약만 | **결정 대기** — 공유 클러스터 ACL(SASL/SCRAM + prefix ACL)은 Infra 후속, 그 전까지 ACL 없이 출발할지(#304 Q6) |

## 5. 헬스 체크 · 기동 순서

**헬스 체크** — compose `healthcheck` 가 컨테이너 안에서 `curl -fsS http://localhost:<컨테이너 포트>/actuator/health` 를 부른다.

- 7개 모듈 모두 actuator 가 있고(`backend/build.gradle` 이 실행 모듈마다 넣는다) `management.endpoints.web.exposure.include: health` 다.
- 익명으로 열려 있다 — 서비스 5종은 security-core(`ResourceServerSecurityConfigurer`)가 `health` 만 `permitAll`, 게이트웨이는 `/api/v1/admin/**` 밖을 `permitAll`,
  Eureka 는 `health` 만 익명이고 나머지는 HTTP Basic 이다(#248). 응답은 상태만 낸다(`{"status":"UP"}`), DOWN 이면 503 이라 `curl -f` 가 실패한다.
- 서비스 헬스에는 DB 가 들어 있다(DB 가 내려가면 unhealthy). Redis 는 auth · 게이트웨이 모두 헬스에서 뺐다(`management.health.redis.enabled: false`) — Redis 장애로 컨테이너를 빼지 않는다.
- `curl` 은 베이스 이미지(§7)에 들어 있다. 간격 15초 · 제한 5초 · 4회, 기동 유예 120초.

**기동 순서** — Eureka → 게이트웨이 → 서비스 5종.

```text
1. service-discovery    등록 대상
2. api-gateway          라우팅 (lb://<service-id>)
3. auth-service         토큰 발급
4. product-service · delivery-service   order 가 부르는 대상(Feign)
5. order-service · payment-service
```

- compose 파일이 모듈마다 따로라 `depends_on` 으로 묶을 수 없다. **배포마다 `up -d --wait` 로 그 컨테이너가 healthy 가 될 때까지 기다리고 다음 모듈로 간다.**
  `--wait` 는 healthcheck 가 healthy 가 아니면 0 이 아닌 값으로 끝나므로 파이프라인이 거기서 멈춘다. 제한 시간은 `--wait-timeout 300` 처럼 준다.
- **Eureka 가 떠 있지 않으면 게이트웨이 · 서비스가 뜨지 않는다** — 기동 때 등록이 실패하면 멈춘다(`should-enforce-registration-at-init`, #248).
  비밀번호가 틀려도 같다. `restart: unless-stopped` 가 다시 띄우므로 Eureka 가 올라오면 이어서 뜨지만, 그 사이 `--wait` 는 실패할 수 있다.
  등록 전 게이트웨이는 그 서비스로 가는 요청에 503(`GATEWAY_002`)을 낸다. Eureka 등록 · 게이트웨이 레지스트리 갱신에 30초쯤 걸린다.
- 이미 떠 있는 게이트웨이 · 서비스는 Eureka 를 다시 띄워도 멈추지 않는다 — 등록 강제는 기동 때만이고, 이후에는 다시 시도해 재등록한다.
- 처음 띄울 때와 Eureka 를 다시 띄운 뒤에만 순서가 의미 있다. 서비스 하나만 다시 배포할 때는 그 모듈만 올린다.
- 다시 배포한 직후 게이트웨이가 Eureka 의 옛 인스턴스(옛 IP)를 잠시 볼 수 있다(lease 만료 기본 90초). 그동안 그 서비스로 가는 요청 일부가 실패할 수 있다.

## 6. 네트워크 · 외부 노출

**네트워크 (필수)**

- 백엔드 docker 네트워크는 **환경마다 따로 둔다** — dev · prod 가 한 네트워크를 공유하지 않는다.
- 그 네트워크에는 **이 프로젝트의 백엔드 컨테이너만** 붙인다.
- 이유: 가짜 인스턴스가 Eureka 에 등록되면 게이트웨이가 요청(토큰 포함)을 그쪽으로 보낸다. 레지스트리는 HTTP Basic 으로 닫혀 있지만(#248, `security-guide.md` §12)
  평문 HTTP · 공유 비밀번호라, 같은 네트워크에 낯선 컨테이너를 두지 않는 것이 1차 방어다.
- 백엔드 컨테이너는 외부 docker 네트워크 하나(`COMMERCE_DOCKER_NETWORK`, `external: true`)에 붙는다. 이름은 Infra 에서 정한다(#197).
  compose 는 네트워크를 만들지 않는다 — 배포 agent 가 없으면 만든다: `docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET"`.
- 같은 네트워크 안에서는 컨테이너 이름으로 닿는다(Eureka 주소 · DB · Redis 가 같은 네트워크의 컨테이너면 그 이름).
  다른 호스트의 DB · Redis 는 Vault 에 넣은 주소로 닿는다 — 그 값을 저장소에 쓰지 않는다.

**외부 노출**

- **호스트 방화벽(ufw · firewalld)으로는 Docker 가 게시한 포트를 막을 수 없다.** 게시 포트는 iptables `DOCKER` 체인으로 들어와 호스트 방화벽 규칙을 거치지 않는다.
  다음 중 하나로 막는다 — 클라우드 보안그룹 · 네트워크 ACL, iptables `DOCKER-USER` 체인, 바인드 주소 env(`API_GATEWAY_BIND_*`, Eureka · 서비스는 루프백 고정).
- **dev 게이트웨이는 IP 제한 뒤에 둔다.** dev 프로파일은 dev 라우트(`/health/**` · `/docs/**`)와 Swagger UI 가 인증 없이 열려 있다.
  dev 게이트웨이 앞단의 IP 제한은 #197 의 완료 기준이다.

## 7. 베이스 이미지

`eclipse-temurin:21.0.12.1_1-jre-noble` (Eclipse Temurin 21 JRE, Ubuntu 24.04).

- **멀티 아키텍처** — dev · prod 호스트 아키텍처가 다를 수 있다(arm64 · amd64 모두 지원). 같은 태그가 arm64 · amd64(+ ppc64le · s390x · riscv64)를 내므로
  Dockerfile 하나로 두 환경을 덮고, 이미지는 배포 호스트에서 빌드하므로 docker 가 그 호스트 아키텍처의 베이스를 받는다. jar 는 아키텍처와 무관하다.
  베이스를 바꿀 때는 arm64 · amd64 둘 다 있는 태그인지 먼저 확인한다.
- **CI 와 같은 JVM** — GitHub Actions 가 `setup-java` temurin 21 로 테스트한다. 테스트한 HotSpot 과 같은 배포판으로 운영한다.
- **curl 포함** — compose healthcheck 가 쓴다. 다른 베이스(distroless · alpine 계열)로 바꾸면 healthcheck 수단부터 다시 정한다.
- **정확한 태그로 고정한다** — `latest` · `21-jre` 를 쓰지 않는다. 같은 태그도 OS 패치로 다시 빌드될 수 있다(재현성보다 보안 패치를 택했다).
  올릴 때는 7개 Dockerfile 을 같이 올린다.
- 컨테이너는 root 가 아닌 `app` 사용자로 돈다. `exec java` 로 java 가 PID 1 이 돼 `docker stop` 의 SIGTERM 으로 graceful shutdown 하고,
  compose `stop_grace_period: 40s` 가 그동안 SIGKILL 을 미룬다.
- JVM 시간대는 `TIME_ZONE`(기본 UTC)이다. 업무 시각은 `Clock.systemUTC()` · `Instant` 라 로그 시각만 바뀐다.

## 8. 키를 추가 · 변경할 때

`secret-management.md` §4 의 순서에 **compose 를 더한다.**

1. `backend/.env.example` 에 이름 추가
2. 쓰는 모듈의 `application*.yml` 에 플레이스홀더
3. **그 모듈의 `docker-compose-<module>.yml` anchor(`x-<module>-environment`)에 키 추가** — §4-2 보간 규칙으로
4. 이 문서 §4-1 표 갱신
5. PR 본문에 `Vault 등록 필요: KEY (dev/prod)`

compose 에 빠뜨리면 컨테이너에는 그 키가 없다 — 필수 키면 기동 실패, 기본값이 있는 키면 **조용히 기본값으로 뜬다.**

## 9. 로컬에서 확인

Docker Compose v2 가 있어야 한다. 미들웨어는 `docker-compose-local.yml`(§`local-run-guide.md` 3)로 띄우고, 그 기본 네트워크에 백엔드 컨테이너를 붙인다.

```bash
cd backend
docker compose -f docker-compose-local.yml up -d --wait          # 네트워크 commerce-foundry-local_default 가 생긴다
./gradlew :cloud:service-discovery:bootJar                        # 모듈마다
cp cloud/service-discovery/build/libs/service-discovery-*.jar cloud/service-discovery/app.jar
```

배포용 env 파일을 **저장소 밖에 빈 파일로 새로 만들고** 필요한 키만 쓴다. `.env.example` · `backend/.env` 를 복사하지 않는다 —
거기 든 `localhost` 값(`EUREKA_HOST` · `DB_URL` · `REDIS_HOST`)은 컨테이너 안에서 자기 자신을 가리켜, Eureka 등록이 실패하고
게이트웨이가 503 을 낸다. 복사해서 쓰겠다면 이 세 키는 반드시 아래 값으로 덮는다. 값은 §1 의 `.env.runtime` 규칙대로 작은따옴표로 감싼다.

```text
COMMERCE_DOCKER_NETWORK='commerce-foundry-local_default'
EUREKA_HOST='commerce-service-discovery-dev'
EUREKA_PASSWORD — backend/.env 의 로컬 값(openssl rand -hex 32)
DB_URL='jdbc:postgresql://postgres:5432/commerce_foundry'
DB_USERNAME · DB_PASSWORD · REDIS_PASSWORD — backend/.env 의 로컬 테스트 값
REDIS_HOST='redis'
REDIS_PORT='6379'
SNOWFLAKE_DATACENTER_ID='1'
JWT_PUBLIC_KEY · JWT_PRIVATE_KEY · INTERNAL_JWT_SECRET — 로컬에서 만든 테스트 값(local-run-guide.md §2)
<MODULE>_PORT_DEV / _PROD 7쌍 — 로컬에서 비어 있는 아무 포트
```

```bash
docker compose -p commerce-service-discovery-dev --env-file <env 파일> \
  -f cloud/service-discovery/docker-compose-service-discovery.yml up -d --build --wait service-discovery-dev
# api-gateway → auth → product · delivery → order · payment 순서로 같은 명령
docker ps --format '{{.Names}} {{.Status}}'                       # (healthy) 확인
```

- `app.jar` 는 `.gitignore` 대상이다(`backend/**/app.jar`). 확인이 끝나면 지운다.
- 정리: `docker compose -p commerce-<module>-dev -f <compose> down` (모듈마다).

### 소셜 로그인 활성화 (#5)

auth 배포 compose만 소셜 키를 전달한다. 기본은 전체/제공자 플래그 모두 false다.
Vault에 활성 제공자의 CLIENT_ID · CLIENT_SECRET과 고정 REDIRECT_URI를 준비한 뒤 AUTH_SOCIAL_ENABLED와 해당 LOGIN_ENABLED를 함께 켠다.
활성 제공자의 값이 비었거나 미해결 플레이스홀더, 허용하지 않는 redirect URI이면 auth가 기동하지 않는다.
redirect URI는 제공자 콘솔과 정확히 일치해야 한다. HTTPS가 기본이며 로컬 개발만 loopback HTTP를 허용한다.
Kakao는 콘솔에서 Client Secret 사용을 활성화해야 한다. 클라이언트 비밀은 auth에만 전달하며 FE에는 전달하지 않는다.
운영 적용 전에 PR의 Hibernate 생성 DDL 중 auth.social_identity 테이블·제약·인덱스를 적용한다. 기본 ddl-auto=none이므로 자동 생성되지 않는다.
