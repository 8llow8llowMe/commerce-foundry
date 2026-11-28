# 품절 임박 기준과 조회 (#229)

현재 구현은 SKU별 기준 수량 관리와 어드민·서비스 간 조회, 정기 점검의 원천 소비·판정·상태 저장(자동 실행 전)을 제공한다.
정기 점검 자동 실행, 판매 속도·납기 기반 재주문 추천, 고객 재입고 신청·발송은 후속이다. 이 기능만으로 #229 전체 완료나 자동 발주를 의미하지 않는다.

## 어드민 API

아래 API는 조회가 `AdminAuthority.INVENTORY_READ`, 설정 · 해제가 `INVENTORY_MANAGE` 이며(ADMIN · OWNER 쓰기, MANAGER 조회, STAFF 불가) 고객·익명 요청은 기존 보안 봉투로 거절한다. 기존 `/api/v1/admin/stocks/**` 게이트웨이 경로를 사용한다.

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

## 서비스 간 커서 조회 (PR-2)

`GET /internal/v1/stocks/low?afterSkuId=0&size=100`은 operations-service의 조회 기반이다.
`@Hidden`이며 게이트웨이를 경유하지 않는다. `SERVICE_operations-service` 하나만 허용하고 쓰기 엔드포인트는 없다.
서비스 토큰 없음·잘못된 토큰·회원/ADMIN 액세스 토큰은 internal 체인이 401 `SECURITY_007`, 다른 유효 서비스 호출자는 403 `SECURITY_005`로 거절한다.
인증 통과 후 MVC 바인딩·검증이 메서드 인가보다 먼저 실행되는 기존 internal 규칙을 따른다.

- `afterSkuId`는 long 0 이상(기본 0), `size`는 1~100(기본 100)이다. 음수 커서는 400 `STOCK_ALERT_106`, 크기 오류는 `STOCK_ALERT_105`,
  숫자 형식·long 범위 오류는 기존 `PRODUCT_198`이다. `Long.MAX_VALUE` 커서는 정상 빈 결과다.
- `Response`의 `dataBody`는 `{observedAt,contents,hasNext,nextAfterSkuId}`다. 각 contents 항목의 칸·정책 포함 조건은 어드민 목록과 같지만
  internal DTO는 독립 정의한다. 아이디와 다음 커서는 문자열, 판매 상태는 metadata다.
- 정렬은 수량과 무관한 SKU 아이디 오름차순이다. `skuId > afterSkuId`로 `size+1`개(최대 101개)만 SELECT 한 번으로 읽고 count는 하지 않는다.
  추가 행이 있으면 `hasNext=true`, `nextAfterSkuId`는 마지막 **반환** 행의 아이디다. 끝·빈 결과는 `hasNext=false`, 다음 커서 null이다.
- `observedAt`은 Clock으로 SELECT 직전 기록한 **페이지 관측 시작 시각**이며 조회 종료 시각이나 데이터 버전이 아니다.
  각 페이지는 독립적인 현재 관측이며 전체 스캔의 단일 스냅숏을 보장하지 않는다. 동시 정책·수량 변경은 뒤 페이지의 포함 결과를 바꿀 수 있다.
  이미 지나간 아이디가 이후 품절 임박으로 바뀌면 이번 순회에서 빠질 수 있다.
- 소비자는 빈 페이지나 EOF만으로 모든 SKU의 재고 회복을 확정해서는 안 된다. 알림 해제 같은 전체 SKU 복구 판정은 후속 설계에서 별도 근거를 정한다.
  조회는 재고 쓰기·잠금·선점을 하지 않고 DDL·추가 인덱스도 필요 없다.

operations 소비 쪽은 PR-3a에서 클라이언트·판정 정책·상태 저장까지 있고, 스케줄러·수신자 조회·커밋 뒤 SSE 전달·활성화 게이트는 PR-3b다(아래 절).
재주문 추천·재입고 신청은 아직 없다. #229는 계속 열린 후속 작업이다. FE의 어드민 계약과 정렬·페이지/count 스냅숏은 그대로다.

## 정기 점검 → 어드민 알림 센터 (① PR-3a · PR-3b)

operations-service notification 컨텍스트가 위 커서 조회를 주기적으로 읽어 **새로 품절 임박이 된 SKU 건수**를 어드민 알림 센터에 남긴다.
product 변경은 없다. PR-3a는 원천 클라이언트·순수 판정·상태 저장만 있고 **자동 실행이 없다**. 스케줄러·ShedLock·설정·활성화 게이트는 PR-3b다.

- 원천: `LowStockObservationClient`(`afterSkuId`·`size` 최대 100). 응답에서 `observedAt`·`contents[].skuId`·`hasNext`·`nextAfterSkuId`만 읽는다.
  서킷 `product-service`는 다른 product 호출과 공유하므로 서킷 안에서는 원격 호출만 하고 **계약 검증은 서킷 밖**에서 한다. 4xx는 서킷 실패로 세지 않는다.
  원천 장애·서킷 열림·계약 위반(봉투 실패, 커서 앞 SKU, 비오름차순, `hasNext`와 커서 불일치, 문자열이 아닌 ID 등)은 모두 503 `NOTIFICATION_053`이며
  빈 결과나 정상으로 바꾸지 않고 재시도하지 않는다. 그 점검은 상태·알림을 쓰지 않는다.
- 판정(`LowStockAlertPolicy`): 커서 순서대로 `maxPages`(기본 20, 상한 40)까지 읽은 페이지를 묶는다. 각 페이지는 요청 커서를 함께 싣고,
  첫 페이지는 커서 0, 뒤 페이지는 앞 페이지의 `nextAfterSkuId`로 요청한 것이어야 한다(건너뜀·되감기는 계약 위반). 마지막 페이지 `hasNext=false`면 완전 스캔,
  아니면 잘린 스캔이다. 워터마크는 첫 페이지 `observedAt`만 쓴다(product 인스턴스 간 시계 차). 시각은 마이크로초로 내려 비교·저장한다(나노초 거부 없음).
  - 추적 행이 없는 SKU는 신규 — 추적 행을 만들고 신규 건수로 알린다. 추적 중이고 이번에도 본 SKU는 부재 표시가 있을 때만 지운다(계속 임박이면 쓰기 0).
  - **완전 스캔일 때만** 부재를 판정한다. 처음 빠지면 `missing_since`를 남기고, `resolve-after`(기본 6시간, 선점 TTL 30분보다 길게) 이상 지나 또 빠지면
    추적 행을 지운다. 그 뒤 다시 품절 임박이 되면 새 알림이다. 잘린 스캔은 본 SKU만 반영하고 부재 판정을 건너뛴다(WARN).
  - 새 SKU가 없고 품절 임박이 남아 있으며 마지막 알림 뒤 `reminder-interval`(기본 7일)이 지나면 재알림 1건이다. 해소 알림은 없다.
  - 첫 페이지 `observedAt`이 저장값보다 늦지 않거나, 로컬 시계가 마지막 수락보다 뒤로 갔거나, 점검 시작부터 `lock-at-most`(기본 5분)를 넘겼으면 쓰지 않는다.
- 저장(`LowStockAlertStateProcessor`, 15초 트랜잭션 하나): 워터마크 단일 행을 `PESSIMISTIC_WRITE NOWAIT`로 잠근 뒤 추적 행 전체를
  (`sku_id`, `missing_since`) 두 칸 projection으로 읽고, 바뀌는 행만 엔티티로 올려(IN 절 1000개씩) 상태·추적 행·알림을 함께 저장한다.
  첫 점검 경합은 PK 충돌로 한쪽만 커밋한다(upsert 없음). 추적 행 수는 기준을 설정한 SKU 수로 제한된다.
  점검 경과는 `lowStockAlertTicker`(단조 시계 빈) 차이로 잰다.
- 알림: kind `LOW_STOCK`, sourceType `LOW_STOCK_ALERT`, 링크 `/admin/stocks`, **점검당 수신자별 요약 1건**. 신규는 WARNING
  "품절 임박 상품 옵션 N개가 새로 생겼습니다. 재고를 확인해 주세요.", 재알림은 INFO "… N개(잘린 스캔이면 N개 이상)가 아직 남아 있습니다 …".
  상품명·SKU 아이디·수량·기준값은 알림·로그에 넣지 않는다. 로그는 건수와 고정 사유만 남긴다.
  수신자는 PR-3b에서 auth 활성 대표 관리자(ADMIN·OWNER)로 조회할 예정이다(MANAGER 제외). PR-3a 저장 경계는 1~1,000명의 수신자 ID를 받기만 한다.
- 테이블: `operations.low_stock_alert_state`(`source` PK, CHECK `'PRODUCT'`, `last_observed_at`·`last_accepted_local_at`·`last_alert_at`, `version`),
  `operations.low_stock_alert_sku`(`sku_id` PK — 외부 FK product `sku.id`, `missing_since`, `version`, 보조 인덱스 없음). 배포 순서는 `deploy-guide.md`.
- 알려진 한계
  - 품절 임박 SKU가 계속 `maxPages`×100개를 넘으면 완전 스캔이 오지 않는다. 그동안 부재·해소 판정이 멈추고, 커서 뒤쪽(아이디가 큰) SKU는
    점검 범위 밖이라 신규 알림을 받지 못한다. 매 점검마다 `low_stock_alert_scan_truncated` WARN 로그가 남는다.
  - 200 응답의 JSON 자체가 깨지면 Feign 디코딩이 서킷 안에서 실패해 `product-service` 서킷 실패로 집계된다(결제 이상 관측 선례와 같음).
    봉투·칸 수준의 계약 위반은 서킷 밖에서 판정하므로 집계되지 않는다.

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
