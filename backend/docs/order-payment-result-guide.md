# 저장된 결제 반영 결과 조회 (#14 · #308 C1a · C1b1)

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

원천 API는 **C1a**, payment의 조회·응답 계약 검증은 아래 **C1b1** 범위다. ACK 소비·inbox·이벤트 대조·주문 동기화 상태 변경과 재고 확정 뒤 중단 창 복구는 후속이다.
#308 C 및 #15가 끝나기 전 운영 결제 게이트를 열지 않으며, 기존 결제·복구·소비 게이트 값과 수동 sandbox 승인 대기 상태는 바꾸지 않는다.

## payment 호출 경계 (C1b1)

payment-service의 `OrderPaymentResultQueryPort`는 위 API를 호출해 검증된 스냅숏만 반환한다.
`OrderPaymentResultClient`는 고유 contextId와 `InternalFeignClientConfiguration`을 사용하고 기존 `order-service` 서킷,
연결 2초·응답 3초 기본 타임아웃을 재사용한다. 사용자 토큰을 전달하지 않으며 어댑터·Feign 계층의 자동 재시도를 추가하지 않는다.
하부 HTTP 클라이언트는 연결 reset 시 읽기 전용 GET을 투명하게 재접속할 수 있다. 이를 업무 재전송이나 ACK 소비로 취급하지 않는다.

성공 봉투·요청 paymentId 일치·양수 식별자와 금액·알려진 통화/결과를 검증한다.
PAID는 승인 시각과 선점 ID가 필수이며, 취소 주문 거절만 선택적 선점 ID를 허용한다.
다른 거절의 선점 ID는 null이어야 하고 모든 거절에서 승인 시각은 null일 수 있다.
다른 주문의 현재 상태나 PG 상태를 추정하지 않는다. 읽기 결과는 payment 소유 타입이며 order-service 구현 클래스에 의존하지 않는다.

| 원격 결과 | 조회 실패 분류 | 후속 소비자의 처리 기준 |
|---|---|---|
| 404 `ORDER_028` | `NOT_READY` | 아직 영속 반영 결과가 없으므로 제한된 재시도 대상 |
| 연결·타임아웃·5xx·서킷 오픈 | `UNAVAILABLE` | 장애 복구 후 제한된 재시도 대상 |
| 401·403 | `UNAUTHORIZED` | 서비스 토큰·허용 호출자 설정 확인, 토큰 즉시 재발급 재시도 없음 |
| 그 밖의 4xx | `REJECTED` | 입력·배포 계약 점검, 잘못된 경로 404를 결과 없음으로 취급하지 않음 |
| 성공 봉투·본문·필드·역직렬화 계약 위반 | `CONTRACT_VIOLATION` | 원본·배포 계약 점검, 불완전한 응답을 동기화 성공으로 취급하지 않음 |

실패 예외에는 분류만 담으며 원격 응답 본문·Feign 원인 사슬을 전파하지 않는다. 로그도 정제한 오류 코드·status·예외 타입으로 제한한다.
404 코드 해석은 서킷 밖에서 수행한다. Retry-After가 붙은 4xx도 서킷 내부에서 무시 대상 예외로 정규화해 공유 서킷의 실패로 세지 않는다.
이때 Feign이 내부 cause에 보관한 응답은 코드 판별에만 사용하고 최종 예외·로그에는 남기지 않는다.
어댑터 자체는 결과를 재조회하거나 DLT에 보내지 않는다.

이 단계는 **원천 조회와 응답 계약 검증만** 완료한다. 이벤트와 원천의 일치, 원천과 로컬 payment의 회원·주문·금액·승인 시각 대조는 C1b2의 책임이다.
`order-paid.paidAt`는 주문 처리 시각이고 `approvedAt`는 PG 승인 시각이다. 서로 같은 값으로 비교하지 않는다.
로컬 승인 시각과 원천 승인 시각의 대조는 기존 마이크로초 저장 정밀도로 수행해야 한다.
ACK 소비·inbox·결제 플래그 저장·취소 경합·주문 재고 복원·거절 취소 정책은 후속 범위이며 운영 게이트는 그대로 닫아 둔다.
