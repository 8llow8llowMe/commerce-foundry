# Commerce Foundry Backend Claude Guide

## 목적

- `backend/` 작업 시 Claude Code 가 먼저 확인하는 요약 엔트리다.
- 실제 규칙의 단일 기준은 `backend/docs/*.md` 다.

## 우선 확인 문서

1. `docs/README.md`
2. `docs/architecture-guide.md` — 모듈 구성, 패키지, 계층 책임
3. `docs/coding-conventions.md` — 네이밍, 예외, 엔티티, 금액, 쿼리
4. `docs/api-design-guide.md` — 경로 체계, 응답 봉투, 멱등성
5. 결제·주문·재고 작업이면 `docs/payment-guide.md`, `docs/order-flow-guide.md`
6. 인증·인가(토큰·`@PreAuthorize`·보안 설정) 작업이면 `docs/security-guide.md`
7. `docs/done-checklist.md`

## 한눈에 보는 핵심 규칙

```text
Controller → UseCase → Facade → Processor → Port(out) / Adapter
Info → Presenter → Response            (식별자 long → String 은 Presenter 에서)
```

- 고객 API `adapter/in/web` (`/api/v1/**`), 어드민 API `adapter/in/admin` (`/api/v1/admin/**`), 서비스 간 `adapter/in/internal` (`/internal/v1/**`)
- JPA 연관관계 어노테이션 금지, 네이티브 쿼리 금지, 루프 안 단건 조회(N+1) 금지
- 금액은 `long` 최소 화폐 단위 + `CurrencyCode`. `double` 금지
- 외부 I/O(PG·Feign·소셜)가 섞인 유스케이스는 Facade 에 트랜잭션을 걸지 않는다
- 예외는 도메인별 `ErrorCode` / `Exception` / `ExceptionHandler` 3종 세트, 검증 코드는 필드별 `1xx`

## 명령

```bash
cd backend
./gradlew :service:<svc>:test --tests '*XxxTest'                          # 반복 중 — 바꾼 클래스만
./gradlew :service:<svc>:cleanTest :service:<svc>:check --no-build-cache  # 바꾼 모듈만, 테스트가 실제로 돈 결과
./gradlew compileTestJava                                                 # 공용 모듈(core 등)을 바꿨을 때 — 전 모듈 컴파일만
docker compose -f docker-compose-local.yml up -d --wait   # PostgreSQL + Redis (.env 필요 — docs/local-run-guide.md)
```

- 루트 `./gradlew check`(전 모듈 테스트)는 로컬 기본 검증이 아니다 — CI 가 그룹 병렬로 돌린다. 같은 PC 에서 여러 세션이 빌드하므로 범위를 좁힌다(`docs/local-run-guide.md` §4-1).
- 데몬을 쓴다. `--no-daemon` 은 JVM 크래시 직후 한 번만, **`./gradlew --stop` 은 쓰지 않는다**(다른 세션 빌드까지 끊긴다).

## 파일 인코딩 규칙 (필수)

- **모든 소스 / 설정 / 문서 파일은 `UTF-8` (no BOM)** 으로 저장한다.
- 루트 `.editorconfig`, `.gitattributes`, `build.gradle` 의 `options.encoding = 'UTF-8'` 설정을 덮어쓰지 않는다.

## 시크릿 (필수 — 공개 저장소)

- 실값은 Vault 에만 둔다. yml 에는 `${TOSS_SECRET_KEY}` 같은 플레이스홀더만 쓴다.
- 새 키는 `.env.example` 에 이름만 추가하고 PR 에 `Vault 등록 필요: KEY_NAME` 을 적는다.
- 정본: `docs/secret-management.md`

## 운영 원칙

- 구현 중 새 규칙이 생기면 이 파일보다 해당 `docs/*.md` 를 먼저 갱신한다.
- 서비스 책임이 바뀌면 `docs/service-inventory.md` 를 같은 PR 에서 갱신한다.
- API 계약(경로·필드·오류 코드)이 바뀌면 PR 에 FE 영향을 적고 `seonghoho` 에게 리뷰를 요청한다.
