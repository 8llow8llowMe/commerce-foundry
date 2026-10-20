# Commerce Foundry Backend Agent Guide

`backend/` 작업의 Codex·Cursor 초기 지침이다. 공통 규칙은 [루트 AGENTS.md](../AGENTS.md), 상세 규칙은 `docs/*.md`를 따른다.

## 먼저 읽을 문서

1. [docs/README.md](docs/README.md)
2. [architecture-guide.md](docs/architecture-guide.md) — 모듈·패키지·계층 책임
3. [coding-conventions.md](docs/coding-conventions.md) — 네이밍·예외·엔티티·쿼리·금액
4. [api-design-guide.md](docs/api-design-guide.md) — 경로·응답 봉투·멱등성
5. 결제·주문·재고는 [payment-guide.md](docs/payment-guide.md), [order-flow-guide.md](docs/order-flow-guide.md)
6. 토큰·인가·보안 설정·internal API는 [security-guide.md](docs/security-guide.md)
7. [done-checklist.md](docs/done-checklist.md)

## 핵심 규칙

```text
Controller → UseCase → Facade → Processor → Port(out) / Adapter
Info → Presenter → Response
```

- 루트 패키지는 `com.commercefoundry`, 서비스 내부는 `domainlayer.<context>`와 `global`이다.
- 고객 `adapter/in/web` (`/api/v1/**`), 어드민 `adapter/in/admin` (`/api/v1/admin/**`), 서비스 간 `adapter/in/internal` (`/internal/v1/**`)을 분리한다.
- 식별자 `long → String`은 Presenter에서 변환한다. Controller는 UseCase만 호출한다.
- JPA 연관관계 어노테이션·네이티브 쿼리·루프 안 단건 조회(N+1)를 쓰지 않는다.
- 금액은 `long` 최소 화폐 단위 + `CurrencyCode`다. `double`·`float`를 쓰지 않는다.
- PG·Feign·소셜 등 외부 I/O가 섞이면 Facade에 트랜잭션을 걸지 않고 Processor 단위로 좁힌다.
- 예외는 도메인별 `ErrorCode` / `Exception` / `ExceptionHandler` 3종 세트, 검증 코드는 필드별 `1xx` 대역이다.
- 시크릿은 Vault에서 주입한다. 새 키는 `.env.example`에 이름만 추가하고 PR에 `Vault 등록 필요: KEY_NAME`을 적는다.
- 서비스 책임을 바꾸면 `docs/service-inventory.md`, API 계약을 바꾸면 FE 영향과 리뷰어 `seonghoho`를 함께 기록한다.
- 엔티티 변경 PR은 `coding-conventions.md` §9-8에 따라 실제 운영 DDL을 포함한다. 운영 적용은 사용자가 수행한다.

## 검증과 로컬 실행

`backend/`에서 실행한다. `<svc>`는 `product-service` 같은 실제 모듈명으로 바꾼다.

```bash
./gradlew compileJava
./gradlew :service:<svc>:cleanTest :service:<svc>:test --no-build-cache
./gradlew check
docker compose -f docker-compose-local.yml up -d --wait
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용한다.
인프라 기동에는 `.env`가 필요하다. [local-run-guide.md](docs/local-run-guide.md)를 먼저 확인한다.
QueryDSL·JPQL은 실제 질의를 실행하는 테스트, 상태 전이는 허용·거절 테스트로 검증한다.
문서·AI 설정만 바꾼 경우에는 관련 경로·형식·인코딩 검증을 실행한다.

모든 파일은 UTF-8 no BOM이다. 루트 인코딩 설정과 Gradle의 `options.encoding = 'UTF-8'`을 보존한다.
