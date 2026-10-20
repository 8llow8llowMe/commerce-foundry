---
name: backend-feature-bootstrap
description: commerce-foundry 백엔드에 새 서비스 또는 기존 서비스의 새 컨텍스트(상품·주문·결제·배송 등)를 시작할 때 사용한다. architecture-guide / api-design-guide / done-checklist 기준으로 책임·고객/어드민 API·패키지 스켈레톤·영속성·서비스 간 호출·검증 계획을 묶어 제시한다.
---

# Backend Feature Bootstrap

프로젝트 컨벤션을 건너뛰지 않고 새 백엔드 컨텍스트/서비스를 시작한다.

## When to Use

- 새 서비스 또는 기존 서비스의 새 컨텍스트를 시작할 때 (예: product-service 에 `stock` 컨텍스트 추가)
- 기능의 1차 패키지 스켈레톤과 구현 체크리스트가 필요할 때
- `/issue-start` 로 [BE] 기능 이슈를 시작한 직후

## Read First

1. [backend/docs/architecture-guide.md](../../../backend/docs/architecture-guide.md)
2. [backend/docs/api-design-guide.md](../../../backend/docs/api-design-guide.md)
3. [backend/docs/coding-conventions.md](../../../backend/docs/coding-conventions.md)
4. [backend/docs/service-inventory.md](../../../backend/docs/service-inventory.md)
5. [backend/docs/done-checklist.md](../../../backend/docs/done-checklist.md)
6. 결제·주문·재고·배송이면 [payment-guide.md](../../../backend/docs/payment-guide.md), [order-flow-guide.md](../../../backend/docs/order-flow-guide.md)

## Procedure

1. **책임 정의**
   - 이 컨텍스트가 소유할 것 / 경계 밖에 둘 것 (`service-inventory.md` 의 서비스 책임과 대조)
   - 다른 서비스 데이터가 필요하면 **복제하지 말고 내부 API 로 받는다**. 스냅숏이 필요한 값(주문 시점 가격·상품명)만 저장한다.

2. **공개 인터페이스 정의** — 고객 / 어드민 / internal 을 나눠 적는다
   - 경로: `api-design-guide.md` §1 의 체계와 서비스별 경로 소유 표
   - 인증: 비로그인 허용 / `CUSTOMER` / `hasRole('ADMIN')`, 소유 확인 위치
   - 목록 응답: 고객 `SliceResponse`, 어드민 `PageResponse`
   - 쓰기 API 의 멱등성 필요 여부 (`Idempotency-Key`)
   - 금액 필드는 `{amount, currency}`

3. **패키지 스켈레톤**

   ```text
   domainlayer/<context>
     |- adapter
     |  |- in/web     { controller, dto/{request,response,item}, exception, presenter }
     |  |- in/admin   { controller, dto/{request,response,item}, presenter }     (어드민 API 가 있으면)
     |  |- in/internal{ controller }                                              (서비스 간 API 가 있으면)
     |  \- out        { persistence/{entity, repository/custom, *RepositoryAdapter}, client/{feign, support} }
     |- application
     |  |- command, exception, info, mapper, model
     |  |- port/{in, out, out/query}
     |  \- service    { *WebFacade, *AdminFacade, processor }
     \- domain/model
   ```

4. **도메인 · 영속성**
   - 상태가 있으면 상태 enum 과 **허용 전이 표**를 도메인 모델에 둔다 (`order-flow-guide.md` §1-1)
   - 엔티티 / DDL, 인덱스(`idx_`/`uk_` + 모든 컬럼, 63자 이내), `@Version` 필요 여부
   - 금액은 `long` + `CurrencyCode` 컬럼 쌍
   - 동적 조회가 있으면 QueryDSL `*CustomRepository` + `@DataJpaTest`

5. **서비스 간 / 외부 연동** (필요 시)
   - Feign `*Client` → `*ClientAdapter` → `*QueryResult`, `name` 프로퍼티 참조, 서킷 인스턴스명
   - 벌크 내부 엔드포인트가 필요한지 (N+1 방지)
   - 외부 I/O 가 섞이면 Facade 트랜잭션을 좁힐 위치

6. **검증 계획**
   - 단위: Processor·도메인 상태 전이 / 슬라이스: `@DataJpaTest` / 웹: `@WebMvcTest` (오류 봉투·검증 코드)
   - `./gradlew :service:<svc>:cleanTest :service:<svc>:check --no-build-cache`(바꾼 모듈만), 공용 모듈을 바꿨으면 `./gradlew compileTestJava`. 전 모듈은 CI
   - Swagger 확인, `service-inventory.md` 갱신

## Output Format

```text
BACKEND FEATURE BOOTSTRAP
=========================

Target: [service/context]   Issue: #N

Responsibility:
- In:  ...
- Out: ...

Public APIs:
- [고객]   GET  /api/v1/...            auth: 비로그인 허용      response: SliceResponse<...>
- [어드민] POST /api/v1/admin/...      auth: ADMIN             idempotent: no
- [내부]   GET  /internal/v1/...       caller: order-service

Domain / State:
- ...

Package Skeleton:
- ...

Persistence:
- table / columns / indexes / @Version

Integration:
- Feign: ... (or 없음)
- External: ... (or 없음)
- Transaction boundary: ...

Verification Plan:
- ...

Docs to Update:
- service-inventory.md ...
```
