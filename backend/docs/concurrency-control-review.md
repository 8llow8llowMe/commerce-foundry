# BE 동시성 제어 점검 (#510)

2026-11-02 `origin/develop`의 완료된 구현을 점검했다. 기준 커밋은 `cc0318d9`이며 후속 커밋 `69987129`까지 확인했다.
이 문서는 코드·개발 이력에 근거한 설계 판단이다. 실제 트래픽의 락 대기 시간이나 처리량을 측정한 결과는 아니다.

## 1. 결정

- Redis 분산 락으로 일괄 전환하지 않는다. 현재 DB에 있는 불변식과 트랜잭션을 유지한다.
- 단조로운 시각 갱신·수량·실행자 소유권처럼 SQL 조건으로 불변식을 표현할 수 있는 쓰기는 조건부 UPDATE를 우선 검토한다.
  알림 단건 읽음은 `read_at is null` 조건으로 전체 읽음과 같은 방식으로 바꾼다.
- 복수 필드 편집·도메인 상태 전이에는 기존 `@Version`을 유지한다. 합계·자식 교체를 보호하는 부모 행 잠금도 유지한다.
- 충돌률·대기 시간·중복 스케줄 조회 비용이 확인되면 그 경로에 한해 Redis 조정이나 키별 직렬 실행을 비교한다.
  Redis 도입 자체를 완료 목표로 삼지 않는다.

낙관적 락은 조회 때 행을 잠그지 않는다. UPDATE의 버전 조건으로 오래된 쓰기를 거절한다. 조건부 UPDATE도
DB 내부의 쓰기 잠금을 사용하므로, 이 개선을 DB 락이 전혀 없는 방식으로 설명하지 않는다.

## 2. 서비스별 사용처와 판단

아래 클래스는 각 서비스의 `domainlayer` 아래에 있으며 outbox는 `global/messaging`이다.
버전 필드 존재와 실제 버전 비교 쓰기를 구분했다. `@Version`이 있어도 bulk UPDATE는 자동 버전 검사를 하지 않는다.

| 서비스·테이블 / 대표 클래스 | 기준 develop의 보장·쓰기 경로 | 결정·이유 | 관련 개발 이력 |
|---|---|---|---|
| auth `member` / `MemberEntity`, `MemberRepository` | 계정 변경 버전 검사, 회원 행 잠금, 버전 조건 UPDATE | 유지. 계정·소셜 연결·배송지 등 자식 쓰기를 Redis와 DB 두 저장소로 나누면 원자성이 추가로 필요하다 | #6, #238 |
| auth `admin_otp_credential` / `AdminOtpCredentialRepository` | 버전, 자격 행 잠금, 사용 단계 조건 UPDATE; 복구 코드는 `used_at is null` | 유지. 단일 사용과 교체·해제 동시 실행을 함께 보호해야 한다 | #238, PR #415/#429 |
| auth `shipping_address` / `ShippingAddressRepository` | 개별 편집 버전 검사, 기본 배송지 일괄 갱신 시 버전 증가 | 유지. 여러 주소 중 기본 주소 하나라는 불변식과 오래된 편집 거절을 보존한다 | 배송지 구현 |
| product `product` / `ProductRepositoryAdapter` | 상품 편집 merge + 버전 검사 | 유지. 복수 필드의 오래된 편집 덮어쓰기를 막는다. 이미지 연결 PR #509와 수정 범위를 분리한다 | #9, PR #447/#482/#509 |
| product `stock` / `StockRepository` | 관리 여부·수량 조건 UPDATE, `@Version` 없음 | 유지. 이미 수량 차감·조정에 적합한 방식이다. Redis 수량 원장을 추가하면 DB와의 복구·재조정이 필요하다 | #10, #426, PR #437 |
| product `stock_reservation` / `StockReservationRepositoryAdapter` | 확정·해제·만료 버전 검사, 활성 주문 유니크 키 | 유지. 헤더 승자 판정 뒤 수량 복원을 같은 트랜잭션에서 수행한다 | #10, #309, PR #431 |
| product 선점별 복원 / `StockRestorationProcessor` | 선점 헤더 비관적 잠금 뒤 복원 합계와 상한 판정 | 유지. 헤더 상태를 바꾸지 않는 복원은 버전 검사만으로 합계 경합을 막지 못한다 | #85 |
| product SKU 원가 / `SkuRepository` | SKU 부모 잠금 뒤 최신 원가 이력의 단조 규칙 판정 | 유지. 서로 다른 이력 INSERT의 공통 판정 지점을 보호한다 | #361 |
| product 카테고리·전시·룩·사이즈표 / 각 `*Repository#findByIdForUpdate` | 부모 잠금 뒤 자식 교체·순서 변경 | 유지. 자식 DELETE/INSERT가 섞여 결과가 유실되는 것을 막는다 | #9, #62, #70, PR #463 |
| product `product_draft` / `ProductDraftEntity` | 검수 상태 변경 버전 검사 | 유지. 조건부 상태 전이도 가능하지만 현재 충돌 문제 증거가 없다 | #359, PR #434 |
| order `cart_item` / `CartItemRepositoryAdapter` | 행 유니크 키 + 수량 편집 버전 검사 | 유지. 증감 요청과 수량 지정 요청의 의미를 먼저 분리해야 한다. Redis 직렬화만으로 오래된 사용자 편집까지 판별하지 못한다 | #11 |
| order `orders` / `OrderRepository` | 고객 취소·어드민·결제·출고·만료·자동 확정이 같은 헤더 버전 검사 | 유지. 이력·outbox·결제 반영의 선행 승자 판정이다. 동시성 검증 없이 제거하지 않는다 | #13/#16/#17/#18/#308, PR #449/#454/#459/#483/#498 |
| order `order_request` / `OrderRequestRepository` | 회원·멱등 키 유니크, 시도·lease 조건 갱신과 버전 증가 | 유지. 진행 중 시도 상한·수량 상한까지 다른 키의 동시 요청을 포함해 검사한다 | #231/#425/#436, PR #427/#464 |
| payment `payment` / `PaymentRepositoryAdapter` | 승인·재조회 등의 상태 확정 merge + 버전 검사 | 유지. PG 호출을 DB 트랜잭션 안에 넣지 않고 UNKNOWN과 재조회로 복구하는 경계를 보존한다 | #14/#15, PR #444/#505 |
| delivery `shipment` / `ShipmentRepositoryPort` | 송장 상태 저장 버전 검사, 주문당 유니크 키, 상태 이력 | 유지. 주문 출고·배송 완료 반영 대기와 재시도까지 포함한 전이다 | #18, PR #465/#481 |
| delivery `shipping_policy` / `ShippingPolicyRepository` | 정책 버전 검사와 자식 요율·지역 교체, 버전 조건 UPDATE | 유지. 전체 정책 교체의 오래된 편집 거절과 이력 원자성을 보존한다 | #69 |
| operations `job` / `JobRepository`, `JobClaimProcessor`, `JobLeaseProcessor` | claim·완료·회수 버전 검사, heartbeat·취소 요청은 소유권 조건 UPDATE | 유지. lease를 회수한 뒤 이전 워커의 저장을 막는 시도·소유권 검사를 없애면 안 된다 | #212/#304, PR #501 |
| operations `job_result_file` / `JobResultFileEntity` | 파일 저장·만료 상태 변경 버전 검사 | 유지. 오브젝트 I/O 실패와 DB 만료 상태의 복구 흐름까지 함께 판단한다. PR #507과 수정 범위를 분리한다 | #212 |
| operations `ai_usage_month` / `AiUsageMonthRepository` | 월 한도 예약·정산·경고 표시는 조건부 UPDATE, 호환 버전 증가 | 유지. 이미 원장과 함께 DB 트랜잭션으로 한도를 보호한다 | #215/#435, PR #403/#453 |
| operations `notification` / `NotificationAdminProcessor`, `NotificationRepository` | 기존 단건 merge, 전체 읽음 조건 UPDATE | **개선**. 단건도 ID·수신자·미읽음 조건 UPDATE로 통일. 승자의 시각 재조회. 버전 필드는 혼합 배포 호환용으로 남긴다 | #213, PR #336/#339, #510 |
| operations `onboarding_tour_progress` / `OnboardingTourProgressRepositoryAdapter` | 회원·가이드 유니크 키, 버전 충돌 후 새 트랜잭션에서 진행 상태 합치기 | 유지. reset·완료·건너뛰기·진행 병합의 순서 의미까지 보존해야 한다 | #357/#358 |
| operations/order/payment outbox / 각 `OutboxRepository` | 발행 표시는 미발행 조건 UPDATE, 버전 증가; 릴레이·정리는 JDBC ShedLock | 유지. 실제 쓰기는 이미 조건부 갱신이다. `@Version` 존재만으로 낙관적 충돌 병목이라고 판단하지 않는다 | #304/#306/#308/#476, PR #466/#502 |

product에도 outbox·ShedLock 엔티티가 있으며, messaging 설정에 따라 inbox 정리 등에 사용한다.
스케줄러 중 주문 만료·자동 구매 확정, 재고 선점 만료, 작업 lease 회수는 인스턴스마다 스캔하고 행별 승자를 DB에서 판정한다.
Redis mutex/Redisson 구현은 현재 없으며, auth의 SET NX·GETDEL·Lua는 일회용 인증 상태/제한 처리다.
이 인증용 키 처리를 업무 행 mutex와 혼동하지 않는다.

## 3. Redis 전환 재검토 조건

[Redis 공식 분산 락 문서](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)는
유효 시간 내 실행과 오래된 실행자를 차단할 fencing을 요구한다. 락을 얻었다는 사실만으로 DB 쓰기의 정합성이 보장되지 않는다.
[PostgreSQL READ COMMITTED](https://www.postgresql.org/docs/17/transaction-iso.html)는 경합한 UPDATE의 WHERE 조건을
최신 행에 다시 평가하므로 단조로운 읽음 시각은 한 문장으로 보호할 수 있다.

| 확인할 증거 | Redis 또는 다른 대안을 비교할 조건 | 계속 필요한 보장 |
|---|---|---|
| 경로별 충돌률·재시도 횟수·p95/p99·락 대기 시간 | 특정 업무 키의 경합이 실제 병목 | 최종 DB의 상태·소유권 조건 또는 fencing |
| 인스턴스 수 대비 중복 스케줄 조회 비용 | 분산 스캔 비용이 본 처리보다 큼 | 락이 만료되거나 작업자가 멈췄다 재개해도 중복 부작용 방지 |
| 부모 잠금 대기·자식 행 수·트랜잭션 길이 | 단일 부모에 쓰기가 집중 | 합계 상한·자식 교체 원자성·같은 키의 잠금 범위 |
| outbox 미발행 수·최고 지연·릴레이 처리량 | 단일 릴레이 처리량 한계를 실측 | 키별 순서·at-least-once·inbox 중복 흡수 |

Redis를 도입한다면 키 범위, 획득 대기 상한, TTL·연장, 소유 토큰 비교 해제, 장애 시 거절 정책, 트랜잭션 커밋 뒤 해제,
프로세스 중단·재개·failover·소유권 상실 테스트를 명세에 포함한다. 락 획득 실패 때 DB 쓰기로 우회하는 흐름은 만들지 않는다.
현재는 이런 추가 비용을 감수할 측정 근거가 없으므로 기존 JDBC ShedLock도 교체하지 않는다(#304/#476의 기존 결정과 일치).

## 4. 알림 읽음 변경의 실패·배포 경계

단건 읽음은 소유자 조회 → 도메인 `markRead` → 조건 UPDATE → DB 결과 재조회 순서다.
상태 enum 전이를 bulk UPDATE로 옮긴 것이 아니라 nullable 읽음 시각을 채우는 기존 예외 규칙(§9-5)을 적용했다.

| 상황 | 저장 결과·응답 |
|---|---|
| 단건끼리 / 단건과 전체 읽음 경쟁 | 미읽음인 행을 먼저 갱신한 요청만 1행 변경. 다른 요청도 성공하며 DB의 기존 읽음 시각을 반환 |
| 이미 읽음 재요청 | 최초 저장 시각·생성 시각 유지 |
| 타인·없는 알림 | 소유 조회에서 동일 404. UPDATE에도 수신자 조건을 포함 |
| 처음에 미읽음인 행이 갱신 시도·재조회 사이 정리로 삭제 | 재조회 404, merge로 삭제된 알림을 다시 만들지 않음 |
| DB 실패 | 트랜잭션 롤백. 재요청 가능 |
| 응답 유실 | 커밋됐다면 재요청으로 같은 읽음 상태 조회 |
| 구버전과 혼합 배포 | 새 UPDATE도 version을 올려 구버전 stale merge 거절. 구버전 요청 자체의 충돌 가능성은 완전히 제거되지 않음 |

엔티티·컬럼·응답 필드 변경은 없다. 생성 포트는 `insert`로 한정하며 새 행의 null version을 이용해 persist한다.
처음부터 읽은 행은 기존처럼 조회 결과를 바로 반환한다. 그 조회 뒤 정리와 겹치면 읽은 스냅숏을 응답할 수 있다.
`@Version`/version 컬럼을 삭제하는 것은 별도 배포 판단이다. 구버전 종료·롤백 가능 기간을 확인하기 전에 컬럼을 삭제하지 않는다.
Redis 도입도 이 알림 경로에는 필요하지 않다.
