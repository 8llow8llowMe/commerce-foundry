# 저장된 결제 반영 결과 조회 (#14 · #308 C1a)

`GET /internal/v1/order-payments/{paymentId}`는 order-service가 저장한 **최초 결제 적용 결과**의 읽기 전용 원천 API다.
`@Hidden`, 허용 호출자는 `SERVICE_payment-service` 하나다. 고객·어드민 API와 FE 응답 계약에는 영향이 없다.
회원 필터는 받지 않으며, payment-service는 후속 회신 처리에서 자체 결제 원본과 대조해야 한다.

성공은 공통 `Response` 봉투와 `Cache-Control: no-store`를 반환한다. `dataBody`는 아래 7개 필드만 가진다.

| 필드 | 형식 · 의미 |
|---|---|
| paymentId | 문자열 식별자 |
| orderId | 최초 반영 대상 주문의 문자열 식별자 |
| memberId | 최초 반영 시 확인한 결제 회원의 문자열 식별자 |
| result | `PAID`, `REJECTED_ORDER_CANCELED`, `REJECTED_ORDER_NOT_FOUND`, `REJECTED_PAYMENT_MISMATCH`, `REJECTED_STOCK_UNAVAILABLE`, `REJECTED_ALREADY_PAID`, `REJECTED_PAYMENT_NOT_DONE` 중 하나인 제어 코드 |
| amount | `{amount: long, currency: String}` — 최초 반영 시 확인한 결제 금액, 통화 최소 단위 |
| approvedAt | UTC ISO 승인 시각. 거절 결과에서 null 가능 |
| stockReservationId | 확정한 선점의 문자열 식별자. `PAID`는 필수, `REJECTED_ORDER_CANCELED`는 null 가능, 나머지는 null |

거절의 승인 시각·확정 선점은 저장된 값을 그대로 반환한다. 복원 클레임·복원 완료 시각·기록 ID·이벤트 ID·생성 시각·주문명·PG 키·개인정보는 포함하지 않는다.
취소 주문 거절에 확정 선점이 있어도 복원 완료를 뜻하지 않는다.

| 오류 | HTTP · 코드 |
|---|---|
| 아직 저장된 결과 없음 | 404 `ORDER_028` — 현재 주문의 존재와 별개 |
| paymentId가 0 이하 | 400 `ORDER_162` |
| 숫자가 아니거나 long 범위 초과 | 400 `ORDER_198` |
| 서비스 토큰 없음·사용자 토큰·잘못된 audience | 401 `SECURITY_007` |
| 유효한 다른 서비스 토큰 | 403 `SECURITY_005` |

`Controller → OrderPaymentResultInternalUseCase → Facade → Processor → OrderPaymentRepositoryPort.findByPaymentId`로 한 번 조회한다.
DB만 읽으므로 Facade는 읽기 전용·5초 트랜잭션이다. Presenter가 식별자와 `MoneyItem`을 변환한다.
현재 주문이 출고·취소 등으로 진행했거나 주문 행이 없어도 저장된 결과는 그대로다(`REJECTED_ORDER_NOT_FOUND` 기록도 유효하다).
현재 주문 상태·재고·PG를 재조회하거나 결과를 다시 판정하지 않으며, 버전·업무 상태·복원 진행을 쓰지 않는다. DDL 변경은 없다.

H2·PostgreSQL 공통 영속성 계약은 일곱 결과와 현재 주문 상태의 독립성, 한 SELECT, 쓰기 없음 및 전체 저장 행 보존을 검증한다.
PostgreSQL은 기존 운영 DDL 스냅숏을 `validate`하며 Docker 없는 로컬에서는 skip한다. 실제 JWT MVC는 응답 7필드·큰 식별자·null·금액·인가·검증 오류를 확인한다.

이 범위는 **C1a 원천 조회 API만** 구현한다. payment의 ACK 소비·inbox·회신 검증·주문 동기화 상태 변경, order 회신 outbox와 재고 확정 뒤 중단 창 복구는 후속이다.
#308 C 및 #15가 끝나기 전 운영 결제 게이트를 열지 않으며, 기존 결제·복구·소비 게이트 값과 수동 sandbox 승인 대기 상태는 바꾸지 않는다.
