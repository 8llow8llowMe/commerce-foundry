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
- CI(`backend-ci`)는 실행된 테스트 건수를 세고, 0건이면 통과로 보지 않는다.

## 5. 서비스 실행

서비스 모듈은 #3 에서 생긴다. 실행 방법(`bootRun`, 포트, `.env` 를 읽는 방식)은 그때 이 절에 적는다.
Vault 주입 방식은 #32 에서 정한다.

## 6. 문제 해결

| 증상 | 원인 · 조치 |
|------|-------------|
| 한글이 깨진 파일이 diff 에 잡힌다 | CP949 로 저장됐다. UTF-8(no BOM)로 다시 저장한다 (`.editorconfig`) |
| `docker compose up` 이 `DB_USERNAME 을 채우세요` 로 멈춘다 | `.env` 가 없거나 칸이 비었다. §2 |
| 6379 포트가 이미 쓰이고 있다 | 다른 프로젝트의 Redis 다. `.env` 의 `REDIS_PORT` 를 바꾼다 |
| 새 스키마가 안 보인다 | init 스크립트는 첫 기동에만 돈다. `down -v` 후 다시 올린다 |
| `./gradlew: Permission denied` | 실행 비트가 빠졌다. `git update-index --chmod=+x backend/gradlew` 로 고쳐 커밋한다 (`chmod` 로 덮지 않는다) |
