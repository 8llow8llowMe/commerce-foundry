# 품절 임박 기준과 조회 (#229 PR-1)

이 단계는 SKU별 기준 수량 관리와 어드민 조회만 제공한다. 정기 점검·알림 센터 전달, 판매 속도·납기 기반 재주문 추천,
고객 재입고 신청·발송은 후속이다. 이 기능만으로 #229 전체 완료나 자동 발주를 의미하지 않는다.

## API

모든 API는 ADMIN 전용이며 고객·익명 요청은 기존 보안 봉투로 거절한다. 기존 `/api/v1/admin/stocks/**` 게이트웨이 경로를 사용한다.

| 경로 | 결과 |
|---|---|
| `GET /api/v1/admin/stocks/{skuId}/low-stock-policy` | `{skuId,enabled,thresholdQuantity,updatedAt}` |
| `PUT /api/v1/admin/stocks/{skuId}/low-stock-policy` | 본문 `{thresholdQuantity}`로 전체 기준 교체 후 같은 응답 |
| `DELETE /api/v1/admin/stocks/{skuId}/low-stock-policy` | 기준 해제, 200 `dataBody:null`. 이미 미설정이어도 성공 |
| `GET /api/v1/admin/stocks/low?page=0&size=20` | `PageResponse` 품절 임박 목록. size 1~100 |

- 정책 미설정은 `enabled:false`, `thresholdQuantity:null`, `updatedAt:null`이다. 기준 수량은 long 정수 0 이상이며 0은 품절만 점검한다.
- SKU가 없거나 부모 상품이 없거나 삭제됐으면 정책 조회·설정·해제는 404 `STOCK_ALERT_001`이다. 숨김·판매 중지·무제한 SKU에도 미리 기준을 설정할 수 있다.
- 목록은 정책이 설정된 SKU 중 `판매 가능 수량 <= 기준 수량`인 항목이다. 재고 행이 없으면 기존 재고 규칙대로 관리=true·수량=0이다.
  무제한 재고와 삭제 상품은 제외한다. 숨김·판매 중지 상품/SKU는 운영자가 확인하도록 포함하고 노출·판매 상태를 함께 반환한다.
- 목록 칸은 `skuId`, `productId`, `productName`, `optionValueKey`, `availableQuantity`, `thresholdQuantity`,
  `productVisible`, `productSaleStatus`, `skuSaleStatus`, `policyUpdatedAt`이다. ID는 문자열, 판매 상태는 metadata 객체다.
  `optionValueKey`는 SKU의 옵션 값 ID 조합이며 옵션이 없으면 빈 문자열이다.
- 판매 가능 수량은 이미 결제 전 선점분을 뺀 값이다. 이 API에서 선점량을 다시 차감하거나 실물 수량으로 표시하지 않는다.
- 정렬은 판매 가능 수량 → SKU ID 오름차순으로 고정한다. 상품명·SKU 수와 무관하게 목록 1회·count 1회로 조회하며 count는 같은 조건을 쓴다.
  페이지·count는 읽기 전용 REPEATABLE_READ 트랜잭션의 같은 스냅숏이다. 화면의 수량은 조회 시점 값이며 구매 가능 여부를 예약하지 않는다.
- 오류: `STOCK_ALERT_002` 잠금/트랜잭션 시간 초과 409. 필드 검증은 `STOCK_ALERT_101` 필수 기준,
  `102` 기준 0 이상, `103` SKU ID 양수, `104` page 0 이상, `105` size 1~100이다.
  깨진 JSON·타입 불일치는 기존 서비스 공용 `PRODUCT_100/198` 봉투다.

## 저장·동시성·감사

`stock_alert_policy`는 `sku_id` PK, `threshold_quantity >= 0`, 감사 시각을 보관한다. 원본 재고 수량·관리 여부는 기존 `stock`이 계속 소유한다.
정책 설정·해제는 재고 행을 INSERT/UPDATE하거나 선점·확정·해제·복원을 호출하지 않는다. 신규 보조 인덱스는 없다.
목록은 정책→SKU→상품 PK JOIN과 재고 PK LEFT JOIN이며 각 SKU당 최대 한 행이다.

모든 PUT/DELETE는 기존 SKU 행을 먼저 잠근 뒤 부모 상품 삭제 여부를 읽고 정책을 저장/삭제한다. `@Version` 대신 이 공통 진입점으로
첫 동시 등록·수정·삭제를 직렬화한다. 잠금 대기 2초, Facade 트랜잭션 상한 5초이며 원가 기준 쓰기와도 같은 SKU 잠금에 줄을 선다.
상품 행은 추가로 잠그지 않는다. 기존 상품 변경의 상품→SKU 잠금과 역순이 되는 것을 피한다.
검증 직후 부모 상품이 삭제되면 정책이 남을 수 있지만 목록에서 제외한다. 정책은 구매/재고 정합성의 근거가 아니다.

PUT/DELETE는 설정 상태 기준 멱등이라 별도 멱등 키가 없다. 같은 값 재전송도 마지막 설정 시각과 감사 줄은 갱신될 수 있다.
Facade는 JWT 운영자와 SKU ID로 `STOCK_ALERT_POLICY_SET`·`STOCK_ALERT_POLICY_CLEAR`를 기록하고,
`@AdminAudited`가 선언을 검증한다. `StockAlertAuditProcessor`는 기존 상품 감사 파일과 분리한다.
`AdminAuditLog`는 커밋 후에만 성공을 기록하고 롤백/업무 실패는 FAILURE로 기록한다. 기준 값·상품명·수량·요청 본문은 로그에 넣지 않는다.

운영에는 신규 테이블 DDL을 먼저 적용한 뒤 앱을 배포한다. DDL의 정본은 Hibernate PostgreSQL 17 스냅숏이며 PR 본문에 적용문을 포함한다.
기존 재고 데이터의 변환·복사는 필요하지 않다. 최초 배포 시 정책은 없으며 운영자가 기준을 설정한 SKU만 목록에 나타난다.

## 검증 범위

H2 웹 흐름은 실제 저장·교체·해제, 정책 변경 전후 재고 수량 보존, ADMIN 인가 및 검증 오류 봉투를 확인한다.
PostgreSQL 테스트는 재고 미등록·0·기준과 같음·기준 초과·무제한·숨김·판매 중지·삭제·미설정,
페이지/count·정렬과 첫 동시 등록·설정 후 삭제의 SKU 잠금을 실제로 실행한다. 감사 단위 테스트는 성공/실패·운영자 전달을 확인한다.
Docker가 없는 로컬에서는 PostgreSQL 테스트를 건너뛰며 CI에서 실행한다.
