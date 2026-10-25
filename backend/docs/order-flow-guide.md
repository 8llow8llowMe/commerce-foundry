# Order Flow Guide

> 주문 · 재고 · 배송 · 클레임의 **상태 전이** 정본이다. order-service / product-service(재고) / delivery-service 가
> 이 문서를 따르고, `/order-flow-guard` 스킬이 이 문서로 점검한다. 결제 상태는 `payment-guide.md` §5.

## 1. 주문 상태

```text
PENDING_PAYMENT ──▶ PAID ──▶ PREPARING ──▶ SHIPPING ──▶ DELIVERED ──▶ PURCHASE_CONFIRMED
      │               │  └─▶ MAKING ─┘(주문 제작)            │
      ▼               ▼                                       ▼
   EXPIRED      CANCEL_REQUESTED ──▶ CANCELED         RETURN_REQUESTED ──▶ RETURNED
```

| 상태 | 뜻 | 누가 바꾸나 |
|------|-----|-------------|
| `PENDING_PAYMENT` | 주문 생성, 결제 대기 (재고 선점 중) | 고객 |
| `EXPIRED` | 결제 없이 선점 시간 초과 | 스케줄러 |
| `PAID` | 결제 완료 | payment-service (internal) |
| `MAKING` | 맞춤 제작 중 — `store.features.custom-order` 가 켜진 상품만 | 어드민 |
| `PREPARING` | 출고 준비 | 어드민 |
| `SHIPPING` | 송장 등록, 배송 중 | delivery-service / 어드민 |
| `DELIVERED` | 배송 완료 | 택배사 추적 / 어드민 |
| `PURCHASE_CONFIRMED` | 구매 확정 (고객 확정 또는 N일 경과 자동) | 고객 / 스케줄러 |
| `CANCEL_REQUESTED` → `CANCELED` | 출고 전 취소 (환불 포함) | 고객 요청 → 어드민/자동 승인 |
| `RETURN_REQUESTED` → `RETURNED` | 배송 후 반품 (회수 후 환불) | 고객 요청 → 어드민 |

### 1-1. 전이 규칙 (필수)

- **상태는 도메인 메서드로만 바꾼다** — `order.markPaid(...)`, `order.startPreparing()`, `order.ship(...)`.
  허용 전이 표를 도메인 안에 두고, 표에 없는 전이는 `ORDER_STATUS_TRANSITION_INVALID` 로 거절한다.
- 엔티티에 `setStatus()` 를 두지 않는다. 어드민의 "상태 변경" API 도 도메인 메서드를 거친다.
- **고객 취소 가능 범위**: `PAID` · `MAKING`(제작 착수 전 정책은 스토어 설정) · `PREPARING`. `SHIPPING` 부터는 반품 경로다.
- 맞춤 제작 상품은 **제작 착수 후 단순 변심 취소 불가**가 일반적이다 — 정책 문구와 함께 스토어 설정으로 둔다.
- 상태 변경 이력은 `order_status_history` 에 남긴다 (언제, 누가, 이전 → 이후, 사유). 어드민이 바꾼 것은 관리자 ID 를 남긴다.

### 1-2. 동시성

- 주문 행에 `@Version` 을 둔다. 고객 취소와 어드민 출고 처리가 동시에 오면 하나만 성공해야 한다.
- 결제 완료 알림(internal)은 재전송될 수 있다 — **이미 `PAID` 이후 상태면 성공으로 응답**하고 아무것도 바꾸지 않는다.

## 2. 주문 금액

- 주문 금액은 **order-service 가 서버에서 계산한다**: Σ(SKU 판매가(기본 판매가 + 옵션 추가금) × 수량) − 할인 + 배송비.
  SKU 판매가에 옵션 추가금이 이미 들어 있다(product-service `SkuInternalProcessor` · order `CatalogSku.salePrice`) — 추가금을 따로 더하지 않는다.
- 가격·옵션 추가금은 주문 시점에 product-service 에서 조회하고 **주문 항목에 스냅숏으로 저장**한다.
  나중에 상품 가격이 바뀌어도 지난 주문은 바뀌지 않는다. 상품명·옵션명도 스냅숏한다.
- 프론트가 보낸 금액·가격은 쓰지 않는다. 주문서 미리보기(`POST /api/v1/orders/quotes`)와 실제 주문 생성은
  같은 계산 코드를 쓴다 (§2-3).
- 배송비는 delivery-service 의 정책(국내 기본/도서산간/무료배송 기준, 해외 국가·무게별)으로 계산한다.
  order-service 는 `POST /internal/v1/shipping-fees/quotes` 의 `shippingFee` 를 더하고 주문에 스냅숏한다. 계산 계약(국내 #11 · 해외 #22):

  | 항목 | 규칙 |
  |------|------|
  | 범위 | `countryCode` 가 KR 이면 국내 정책, 그 밖이면 해외 정책. 응답 `scope` 가 `DOMESTIC` / `INTERNATIONAL` 이다 |
  | 기준 금액(`orderAmount`) | **할인 후 상품 합계(배송비 제외)**. delivery 는 받은 값을 비교만 한다 |
  | 무료배송 | 기준 금액 **이상**이면 국내는 기본 배송비 0, 해외는 기본료 + 무게 요금 0. 기준이 없으면(null) 무료배송 없음 |
  | 도서산간 추가금 (국내) | 우편번호(5자리, KR 필수) 구간에 들면 **무료배송이어도 별도 부과**. 해외에는 없다 |
  | 해외 배송비 | **기본료 + 무게 요금**. 국가가 든 국가 그룹에서 상한(`maxWeightGrams`)이 무게 이상인 가장 작은 구간의 요금 — **상한 포함**, 상한 + 1g 은 다음 구간 |
  | 해외 무게(`weightGrams`) | KR 외 국가면 **필수**(1g 이상, 없으면 400 `DELIVERY_118`). order-service 가 SKU 무게 × 수량 합으로 보낸다(#13). KR 이면 무시한다. 해외 우편번호는 선택(비거나 공백뿐이면 없는 값)이고 계산에 쓰지 않는다 |
  | 가장 큰 구간 초과 | 400 `DELIVERY_011` — 그 그룹으로는 배송할 수 없는 무게다 |
  | 정책 미설정 | 409 `DELIVERY_001` — 0원 배송으로 열리지 않게 주문을 막는다 |
  | 국가 코드 형식 오류 | 400 `DELIVERY_105`(없음) · `DELIVERY_106`(대문자 두 글자가 아님) — 본문 검증 |
  | 배송할 수 없는 국가 | 400 `DELIVERY_002` — 형식은 맞지만 배송할 수 없는 국가: 해외 배송 플래그(`store.features.international-shipping`)가 꺼졌거나, 어느 국가 그룹에도 없는 국가 |
  | 검증 순서 | 본문 검증(`105` · `106` · `118` · `119` · `120` 등)이 플래그 · 정책 확인보다 먼저다 — 플래그가 꺼져 있어도 무게 없는 해외 요청은 `118` 이다 |
  | 통화 불일치 | 400 `DELIVERY_003` — 아래 #70 결정 |

  **정책 통화가 견적 통화다 (#70).** 주문 통화가 그 범위 정책의 통화와 다르면 견적하지 않는다(`DELIVERY_003`) — **환산하지 않는다**.
  영문 스토어(USD) 주문 + 국내(KR) 배송지는 국내 정책(KRW)과 통화가 달라 거절된다. 국내 배송은 국내 정책 통화의 주문만 받는다.
  환율 기준(시점 · 출처)을 delivery 가 정하면 주문 금액을 다시 만들 수 없기 때문이다. 통화별 상품 가격은 #20 이 다룬다.
  같은 이유로 **저장된 정책의 통화는 PUT 으로 바꾸지 않는다**(409 `DELIVERY_012`) — 어드민 실수 한 번에 그 통화의 모든 주문이 003 으로 막히지 않게.
  통화를 바꾸려면 운영 절차(DDL)로 한다. 엔진 재사용을 위해 통화를 KRW 로 고정하지는 않는다 — 정책을 처음 만들 때 고른다.

  order-service 는 견적 응답의 기본 배송비 · 무료배송 기준 · 도서산간 추가금 · 정책 버전(해외는 국가 그룹 이름 · 무게 요금도)을 **주문에 스냅숏한다**.
  §5 의 "무료배송 기준 미달 시 재부과" 는 이 스냅숏(주문 시점 기준 · 기본 배송비)으로 order-service 가 계산하고,
  **현재 정책으로 다시 견적하지 않는다** — 주문 뒤 정책이 바뀌어도 지난 주문의 환불액은 바뀌지 않는다. 배송지가 그대로면 도서산간 추가금은 다시 매기지 않는다.

### 2-1. 장바구니 (#12)

장바구니는 주문 금액의 입력이 아니다 — 가격 · 상품명을 저장하지 않고, 조회할 때마다 product-service 의 현재 SKU 로 보여 준다. 주문 생성은 위 규칙대로 다시 조회 · 스냅숏한다.

| 항목 | 규칙 |
|------|------|
| 소유 | 로그인 회원 전용, 회원당 하나 (`/api/v1/carts/me/**`). 회원은 JWT 주체로만 정한다. 비로그인 장바구니는 프론트 로컬에 둔다 |
| 저장 | PostgreSQL `orders.cart_item` · `orders.cart_item_custom_option` (Redis 아님) |
| 줄 동일성 | (회원, SKU, 맞춤 제작 입력 해시). 같은 줄을 다시 담으면 수량을 더한다. 키 · 값은 앞뒤 공백을 자르고 Unicode NFC 로 정규화한다(저장값과 해시 모두 — NFD 로 온 한글도 같은 줄). 해시는 fieldKey 순으로 정렬한 정규화 입력의 SHA-256, 입력이 없으면 빈 문자열. 유니크 키가 동시 담기 중복을 막는다(409 `CART_007`) |
| 상한 | 줄 50개, 줄당 수량 1~99 이고 상품의 `maxQuantityPerOrder`(1회 주문 최대 수량 — 상품 컬럼)가 더 작으면 그것. 장바구니는 **줄 단위로만** 막는다 — 같은 상품의 다른 SKU(S · M) · 다른 맞춤 입력 줄의 합은 보지 않으므로, 담긴 그대로 주문하면 상품 단위 상한(§2-3 `ORDER_005`)에서 거절될 수 있다. **50줄은 느슨한 상한이다** — 줄 수를 센 뒤 넣으므로 동시 담기 · 병합에서 소폭 넘을 수 있다. SKU 조회는 100개씩 나눠 불러 넘어도 조회 · 쓰기 응답 · 삭제가 막히지 않는다 |
| 담기 거절 | 없는 SKU 404, `purchasable=false`(품절 · 판매 종료 · 숨김) 409, 맞춤 제작이 아닌데 입력이 있음 400, 수량 · 줄 상한 초과 409. 입력값이 상품 입력 항목 정의에 맞는지는 주문서 계산(#13 · #97)이 본다. 장바구니 담기에는 같은 대조를 하지 않는다 — 담긴 뒤 정의가 바뀌거나 치수가 빠지면 주문서에서 400 으로 거절된다 |
| 입력 문자 | 맞춤 제작 value 에 제어 문자(`\p{Cc}` — C0 · DEL · C1, NUL · 개행 · 탭 포함)나 짝 없는 서로게이트가 있으면 위치와 상관없이 400 `CART_115` 다(#232). 앞뒤 공백을 자르기 전 원문으로 본다 — `strip()` 은 탭 · 개행은 지우지만 NUL 은 남긴다. NUL 은 PostgreSQL text 가 거절해(22021) 저장이 500 이 되고, 짝 없는 서로게이트는 pgjdbc 가 `?` 로 바꿔 입력과 다른 값이 저장된다. 서식 문자(`\p{Cf}`)는 막지 않는다 — 이모지 ZWJ 시퀀스가 여기 든다. fieldKey 는 형식(`CART_109`)이 이미 막는다. 판정은 요청 DTO 검증기 `NoControlCharacters` 이고 도메인 `CustomOption` 은 다시 보지 않는다(길이 · fieldKey 형식과 같은 요청 형식 규칙). 병합도 요청 전체 400(`items[i].customOptions[j].value`)이고 항목 단위로 건너뛰지 않는다. **주문 생성(#13)의 맞춤 입력에는 아직 이 검사가 없다** |
| 수량 변경 · 삭제 | 품절 · 판매 종료 줄도 허용한다. 수량 변경은 트랜잭션 두 구간(소유 확인 → 원격 상한 조회 → 다시 읽어 쓰기)이라 사이의 변경은 마지막 쓰기가 이긴다. `@Version` 409(`CART_007`)는 쓰기 구간 안의 동시 수정에서만 난다 |
| 조회 상태 | SKU 없음 → `STOP_SELLING`, `purchasable` → `ON_SALE`, 판매 상태 `SOLD_OUT` → `SOLD_OUT`(숨긴 상품이라도 품절이면 품절), 그 밖(판매 종료 · 품절이 아닌 숨김) → `STOP_SELLING`. 합계는 `ON_SALE` 이고 수량이 현재 상한 안인 줄만 더한다(없으면 null) |
| 병합 | 로그인 직후 로컬 장바구니를 한 번 보낸다(최대 50개, 오래 전에 담은 것부터 — 새 줄은 요청 순서로 아이디가 커져 나중 항목이 위에 보인다). 담을 수 없는 항목은 **항목 단위로** 건너뛰어 `skippedItems: [{index, skuId, reason}]` 로 알린다 — reason 은 `SKU_NOT_FOUND` · `NOT_PURCHASABLE` · `CUSTOM_OPTION_NOT_ALLOWED` · `LINE_LIMIT_EXCEEDED` · `QUANTITY_LIMIT_EXCEEDED`(1회 주문 최대 수량 0) 순으로 판정한다. 같은 SKU 라도 항목마다 따로 판정한다. 같은 줄은 더한 뒤 상한에서 자른다(거절하지 않는다, 이미 넘은 줄을 줄이지 않는다). **멱등하지 않다** — 재전송하면 한 번 더 더해진다. 결제 · 재고와 무관하고 상한이 피해를 제한해 멱등 키를 두지 않는다 |
| 원격 호출 | SKU 는 `catalog` 컨텍스트(`CatalogSkuProcessor`, 주문 생성과 공용)가 `GET /internal/v1/skus` 로 몇 줄이든 100개씩 묶어 부른다(보통 한 번, 장바구니가 비면 호출하지 않는다). Facade 에 트랜잭션을 걸지 않고 DB 구간만 Processor 에서 좁힌다. product-service 장애는 503 `CART_008` 이다(`CATALOG_001` 을 `CartExceptionHandler` 가 번역) — 쓰기 뒤 장바구니 재조회가 실패해도 쓰기는 커밋돼 있다 |

### 2-2. 해외 주문의 SKU 무게 · HS 코드 (#80)

해외 배송비 견적(무게 구간)과 통관 신고(HS 코드 · 신고 금액)의 입력은 SKU 의 **배송 무게**와 **HS 코드**다. 둘 다 해외 판매 상품만 필수라 비어 있을 수 있다.
**비어 있는 값을 거절하는 쪽은 order-service 다** — product-service 는 null 을 그대로 내리고, 배송비 견적에서 delivery-service 는 order-service 가 만든 총 무게만 받는다(통관 품목은 아래 "통관 정보").

| 항목 | 규칙 |
|------|------|
| 저장 (product-service) | `product.sku.weight_grams` integer(그램, 1 이상) · `hs_code` varchar(10)(숫자 6~10자리), 둘 다 null 허용 — null 은 미입력이다. 도메인(`Sku` · `HsCode`)과 DB check(무게 양수 `ck_sku_weight_grams_positive`, HS 코드 숫자 6~10자리 `ck_sku_hs_code_format`)가 같은 규칙을 막는다 — 운영 SQL 로 형식이 틀린 값이 들어가 그 SKU 를 읽는 상세 · internal 조회 · 재고 선점(국내 주문 포함)이 500 이 되지 않게. 점 · 하이픈 · 공백(`6211.42.00`)은 고쳐 받지 않는다 — 정규화는 입력 쪽(#9 · #19)이 한다 |
| 내려주기 | `GET /internal/v1/skus` 항목의 `weightGrams`(number, nullable) · `hsCode`(string, nullable). 미입력이면 키를 빼지 않고 `null` 이다. 기본값으로 채우지 않고 거절하지도 않는다 — 국내 주문에는 필요 없는 값이다. 고객 API(상품 상세)에는 내리지 않는다 |
| 해외 주문 | **배송 국가 ≠ KR** 인 주문 하나로 판정한다(통화는 보지 않는다). delivery 견적이 해외 정책(응답 `scope` = `INTERNATIONAL`, #22)으로 계산하는 주문과 같다. 통화와 배송 국가의 관계는 #22 · #70 을 따른다 |
| 거절 (order-service, #13) | 해외 주문의 주문서 미리보기 · 주문 생성에서 항목 중 **무게 또는 HS 코드가 null 인 SKU 가 하나라도 있으면 거절**한다 — 장바구니 `purchasable=false` 와 같은 결의 409, 어느 SKU 인지 알 수 있게 한다(`ORDER_007`, §2-3). delivery 견적을 부르기 **전에** 거절한다 — 무게 없이 부르면 delivery 의 무게 필수 오류(`DELIVERY_118` 400, #22)가 나와 원인이 흐려진다. 다만 **배송할 수 없는 국가**(해외 배송 꺼짐 · 정책에 없는 국가 — delivery `DELIVERY_002`)라는 사유가 무게 미입력보다 앞선다 — 해외 판매 전에는 대부분의 SKU 가 미입력이라 순서가 바뀌면 엉뚱한 사유가 보인다. 판정 순서는 배송 가능 국가 → 무게 · HS 코드 미입력 → 총 무게 → 견적이고, 국가 판정은 미입력 SKU 가 있을 때만 확인용 견적(1g)으로 먼저 한다(§2-3). 국내 주문은 두 값이 비어 있어도 거절하지 않고 견적에 무게를 싣지 않는다 — 값이 있으면 형식은 위 저장 규칙대로 늘 맞다 |
| 총 무게 (#13 · #22) | order-service 가 **Σ(SKU `weightGrams` × 수량)** 을 `long` 으로 더해 견적 요청 `weightGrams`(Integer)로 보낸다. int 범위를 넘으면 견적을 부르지 않고 배송할 수 없는 무게로 거절한다 — delivery 의 가장 큰 구간 초과(`DELIVERY_011`)를 받았을 때와 같은 응답(`ORDER_015`)이다. 포장재 무게는 따로 더하지 않는다 |
| 스냅숏 (#13) | 주문 항목에 단위 무게 · HS 코드를 **주문 시점 값으로 저장**한다(가격 · 상품명과 같은 규칙, 국내 주문은 null 일 수 있다). 총 무게는 배송 견적 스냅숏과 함께 주문에 저장한다. 주문 뒤 SKU 값을 고쳐도 지난 주문은 바뀌지 않는다 |
| 통관 정보 (#18) | 송장 · 출고는 통관 신고 품목(HS 코드 · 수량 · 단위 무게 · **신고 금액 = 주문 항목의 주문 시점 금액**, 주문 통화)을 **주문 항목 스냅숏에서** 만든다 — delivery-service 가 order-service internal 로 읽고 product-service SKU 를 다시 읽지 않는다. 할인 반영 금액으로 신고할지는 #18 이 정한다 |
| 입력 | 어드민 상품 등록 · 수정(#9)에 입력 칸을 추가한다 — 그 전에는 운영 SQL 로 넣는다. 카페24 이관(#19)의 원천 필드는 **미정**이다 — 카페24 상품 무게 필드의 단위 · HS 코드 유무를 #19 에서 확인해 매핑하고, 없으면 이관 뒤 어드민이 채운다 |

### 2-3. 주문서 계산 (#13)

주문서 미리보기와 주문 생성은 **같은 계산**(`OrderQuoteProcessor#quote`)으로 금액을 만든다. SKU 는 Facade 가 `catalog`(`CatalogSkuProcessor#findSkus`)로 한 번 읽어
넘기고(컨텍스트 간 Processor 주입 금지 — `architecture-guide.md` §3), 계산 Processor 는 배송비 견적(delivery)만 부른다. DB 를 쓰지 않아 트랜잭션이 없다.

판정은 아래 순서로 **처음 걸리는 단계 하나**로 거절하고, 그 단계에 걸린 줄은 모두 모아 고객이 알아볼 이름("상품명(옵션명)", 옵션이 없으면 상품명, 없는 SKU 는
"찾을 수 없는 상품")으로 메시지에 싣는다. SKU 아이디 · product-service 메시지는 넣지 않는다. 품절이 아닌데 `purchasable=false` 인 SKU(숨김 · 판매 종료 —
응답으로는 둘을 가를 수 없다)는 "판매하지 않는 상품" 이다 — 아이디만 넣은 요청으로 숨긴 상품명이 새지 않게. 품절은 이름을 알린다(§2-1 조회 상태와 같은 경계).
중복 줄(1)이 판매 가능(2)보다 먼저라 중복 메시지도 이 규칙을 거친다.

1. 맞춤 제작 입력을 정규화(§2-1 "줄 동일성" 과 같은 규칙 — order 의 `OrderCustomOptions`)한 뒤 같은 (SKU, 입력 해시) 줄이 두 번 이상 → `ORDER_008`
2. 없는 SKU · `purchasable=false` → `ORDER_003`
3. 맞춤 제작 상품이 아닌데 입력이 있다 → `ORDER_004`
4. 맞춤 제작 상품이면 입력을 그 상품의 항목 정의와 대조한다(#97). 정의는 `GET /internal/v1/skus` 의 `customOrderFields`
   (`fieldKey` · `inputType` · `required` · `min` · `max` · `step`)이고, 맞춤 제작이 아니면 빈 배열이다. product-service 는 그 페이지의 맞춤 제작 상품 아이디로
   in 절 한 번만 읽는다. 거절은 400 이고 `fieldErrors[].field` 는 `items[i].customOptions[j].value` 다. 빠진 필수 항목은 그 줄에 있던 입력 개수 다음 칸 번호다.
   필수 누락 `ORDER_132`, 모르는 fieldKey `ORDER_133`, NUMBER 가 숫자 아님 `ORDER_134`, 범위 `ORDER_135`, step `ORDER_136`, TEXT 길이 `ORDER_137`.
   대표 코드는 요청 줄 순서 → 입력 순서 → 빠진 필수(정의 순서)의 첫 위반이다. 입력값(치수 · 메모)은 메시지 · 로그에 넣지 않는다. 장바구니는 이 단계를 하지 않는다(§2-1).
5. **상품 단위** 수량 합 > 상품의 `maxQuantityPerOrder`(null 이면 제한 없음) → `ORDER_005`. 상한이 상품 컬럼이라 SKU 별로 세면 S 2 + M 2 로 "1인 2벌" 이 우회된다.
   같은 SKU 를 다른 맞춤 입력으로 나눈 줄도 더한다. 장바구니는 줄 단위로만 막으므로(§2-1) 여기서 거절될 수 있다
6. SKU 판매가 통화가 섞였다 → `ORDER_006`. 주문 통화 = SKU 판매가 통화다(#20 전에는 단일 통화)
7. 줄 금액 = 단가 × 수량, 상품 합계 = Σ 줄 금액 (`Money.times` · `plus`)
8. 배송 국가로 국내 · 해외를 가른다(KR 이면 국내, 통화는 보지 않는다 — 판정은 shared-commerce `ShippingScope.ofCountry` 한 곳, #144).
   형식이 아니면 빈 값인데, 요청 검증이 먼저 거르므로 빈 값은 조립 버그(`IllegalArgumentException`)다. 해외면 §2-2 순서대로:
   1. 무게 또는 HS 코드가 null 인 SKU 가 있으면 **확인용 견적**(아래)으로 배송 가능 국가를 먼저 본다 — 배송할 수 없으면 `ORDER_013`, 있으면 `ORDER_007`.
      국가 판정 전 사유(정책 미설정 · 통화 불일치)로 거절되면 그 코드(`ORDER_012` · `ORDER_014`)다
   2. 무게 < 1 은 product-service 응답 계약 위반이라 error 로그 + `ORDER_002`(503). 모든 줄을 본 뒤 다음 단계로 간다
   3. Σ(무게 × 수량)을 `long` 으로 더해 int 를 넘으면 견적을 부르지 않고 `ORDER_015`

   국내면 무게 · HS 를 보지 않고(비어 있어도 통과) 견적에 무게를 싣지 않는다(SKU 에 값이 있어도).
9. 배송비 견적 — 기준 금액은 상품 합계. 국내는 우편번호, 해외는 총 무게와 (있으면) 우편번호를 싣는다. 견적 결과는 주문 배송비 스냅숏 값(`ShippingFeeQuote`, 해외는 총 무게 포함)으로 바꾼다.
   응답의 배송비가 delivery 산식(국내 (무료면 0, 아니면 기본 배송비) + 도서산간, 해외 무료면 0, 아니면 기본료 + 무게 요금)과 다르거나, 무료배송 판정이
   "기준이 있고 기준 금액 ≥ 기준" 과 맞지 않으면 계약 위반 `ORDER_011`(503)이다 — 배송비를 추측하지 않는다(`ShippingFeeInternalResponseSupport`, domain 생성자도 산식을 다시 본다)
10. 총액 = 상품 합계 + 배송비

**해외 국가 판정을 확인용 견적으로 하는 이유** — 해외 배송 플래그 · 국가 그룹은 delivery 에만 있고, delivery 견적은 본문 검증(`DELIVERY_118` 무게 필수)이 플래그 · 정책 확인보다
먼저라(§2 "검증 순서") 무게 없이 국가만 물을 수 없다. 그래서 **미입력 SKU 가 있을 때만** 같은 견적 엔드포인트에 총 무게 **1g** 으로 한 번 묻는다.
delivery 해외 견적의 판정 순서는 해외 배송 플래그(`002`) → 정책 없음(`001`) → 통화 불일치(`003`) → 국가 그룹(`002`) → 무게 구간(`011`)이다. 그래서
`DELIVERY_002`(→ `ORDER_013`)면 배송할 수 없는 국가, `DELIVERY_011` · 200 이면 국가는 배송할 수 있어 미입력(`ORDER_007` 409)으로 답한다.
`DELIVERY_001` · `003` 은 국가를 판정하기 전 사유라 그 코드(`ORDER_012` · `ORDER_014`)로 답한다 — SKU 를 채워도 실제 견적이 같은 이유로 실패한다.
그 밖의 실패(`ORDER_011` 503)는 그대로 던진다 — 배송 가능 여부를 추측하지 않는다. 해석은 `ShippingFeeClientAdapter#isDestinationSupported` 한 곳에 있고 Processor 는 1g 사실을 모른다.
delivery 의 번역 대상(`ShippingFeeInternalResponseSupport`)이 늘면 이 분류도 같이 본다.
무게 · HS 가 모두 있으면 확인 없이 바로 견적한다 — 앞 단계 사유가 없으니 배송할 수 없는 국가는 그 견적의 `DELIVERY_002` 로 드러난다.
무게 · HS 가 모두 있으면 무게 계약 위반(< 1, 503) · 총 무게 int 초과(`ORDER_015`)를 견적 전에 판정한다 — §2-2 순서의 "총 무게" 단계라 배송할 수 없는 국가(견적의 `002`)보다 앞선다.
delivery 에 배송 가능 국가 조회를 새로 여는 안(서비스 변경 · PR 추가)과 해외 배송 플래그를 order 에 복제하는 안(정책 외 국가를 못 가린다)은 쓰지 않았다.

| 코드 | HTTP | 뜻 |
|------|------|----|
| `ORDER_002` | 503 | product-service 장애(catalog `CATALOG_001` 번역) · SKU 응답 계약 위반(해외 주문의 무게 1 미만) |
| `ORDER_003` | 409 | 없는 SKU · 구매할 수 없는 SKU(품절 · 판매 종료 · 숨김) — 이름 목록 |
| `ORDER_004` | 400 | 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다 — 이름 목록 |
| `ORDER_005` | 409 | 상품 단위 수량 합이 1회 주문 최대 수량을 넘는다 — "상품명(최대 N개)" 목록 |
| `ORDER_006` | 409 | 판매가 통화가 섞였다 |
| `ORDER_007` | 409 | 해외 주문에 무게 · HS 코드 미입력 SKU 가 있다 — 이름 목록 |
| `ORDER_008` | 400 | 같은 (SKU, 맞춤 제작 입력) 줄이 요청 안에 중복 — 이름 목록 |
| `ORDER_011` | 503 | delivery 장애 · 번역하지 않는 4xx · 견적 응답 계약 위반 |
| `ORDER_012` | 409 | 배송비 정책 미설정 (`DELIVERY_001`) |
| `ORDER_013` | 400 | 배송할 수 없는 국가 (`DELIVERY_002`, 확인용 견적 포함) |
| `ORDER_014` | 400 | 주문 통화가 배송 범위 정책 통화와 다르다 (`DELIVERY_003`, #70) |
| `ORDER_015` | 400 | 배송할 수 있는 무게 초과 (`DELIVERY_011`, 총 무게 int 초과 포함) |

- `009` 는 비워 두고 `010` 은 주문 금액 변경(주문 생성의 `expectedTotalAmount`)이다. 요청 형식(줄 1~50 · 수량 1~99 · 배송지 형식, `ORDER_1xx`)은 진입 API 의 요청 DTO 가 본다 —
  배송지 형식이 delivery 견적 본문 규칙보다 느슨하면 고객 입력 오류가 503 `ORDER_011` 로 보인다.
- 로그에 배송지(우편번호) · 맞춤 제작 입력 값을 남기지 않는다 — 우편번호를 싣는 값(`ShippingFeeQuoteQuery` · `OrderQuoteCommand`)과 맞춤 입력(`OrderCustomOption`)의 `toString` 이 가린다.

## 3. 재고

재고는 product-service 의 SKU(상품 × 옵션 조합) 단위로 관리한다.

```text
available (판매 가능) ──선점──▶ reserved (결제 대기) ──결제 완료──▶ 차감 확정
                     ◀──해제── (만료 · 취소)
```

| 시점 | 동작 | 방법 |
|------|------|------|
| 주문 생성 | 선점 | `available -= qty` 조건부 UPDATE (`available >= qty`) + 선점 행(만료 시각) 생성 |
| 결제 완료 | 확정 | 선점 행을 `CONFIRMED` 로 |
| 결제 없이 만료 · 결제 전 취소 | 해제 | 선점 행을 `RELEASED` 로, `available += qty` |
| 결제 후 취소 · 반품 입고 | 복원 | 선점은 `CONFIRMED` 그대로, 클레임마다 복원 기록(`stock_restoration`)을 남기고 차감했던 SKU 만 `available += qty` (반품은 검수 후 어드민이 복원을 고른 경우만, #85) |

- **조회 후 차감(read-modify-write)을 쓰지 않는다** — 동시 주문에서 초과 판매가 난다. 조건부 UPDATE 한 문장으로 한다.
  영향 행 수가 0 이면 재고 부족(`STOCK_INSUFFICIENT`)이다.
- 선점 만료 시간은 **PG 결제 인증 제한 시간보다 길게** 잡는다 (결제창을 열어 둔 고객의 재고가 먼저 풀리지 않게).
  설정 키는 product-service 의 `stock.reservation.ttl` 이고 기본값 `30m` 은 **결제창 제한 시간을 확인하기 전 임시값**이다.
- 해제·복원은 **멱등**이어야 한다. 해제는 선점 행의 상태로, 결제 후 복원은 클레임 아이디(복원 기록의 유니크 키)로 이미 처리됐는지 판단한다 —
  같은 해제 · 복원이 두 번 오면 재고가 늘어난다.
- 여러 SKU 를 선점할 때는 **SKU ID 오름차순**으로 처리한다 (교착 방지). 하나라도 실패하면 앞서 잡은 것을 해제한다.
- 맞춤 제작 상품은 재고 무제한(`stock_managed = false`) 옵션을 둔다.

### 3-1. 재고 데이터 (product-service, #10)

| 테이블 | 내용 |
|--------|------|
| `stock` | SKU 와 1:1 (`sku_id` PK) — `stock_managed`, `available_quantity >= 0`. **행이 없는 SKU 는 품절**이다(닫힌 기본값). `@Version` 없음 — 모든 쓰기가 조건부 UPDATE |
| `stock_reservation` | 주문 하나에 한 행 — `status`, `expires_at`, `confirmed_at` · `released_at` · `release_reason`, `@Version`. `active_order_id` 는 RESERVED · CONFIRMED 동안만 `order_id` 이고 RELEASED 면 null — 유니크 키가 **주문당 활성 선점을 하나**로 막는다 |
| `stock_reservation_item` | 선점 × SKU — `quantity > 0`, `deducted`. 무제한 SKU 는 `deducted = false` 로 차감하지 않고 해제 때 되돌리지 않는다 |
| `stock_adjustment_history` | 어드민 조정 이력 (append-only) |
| `stock_restoration` | 결제 후 재고 복원 — 클레임 하나에 한 행(append-only, #85). `claim_id` 유니크(`uk_stock_restoration_claim_id`)가 **클레임당 복원을 하나**로 막는 멱등 키, `reservation_id`(확정 선점) · `order_id`(선점에서 복사) · `reason`(`CANCELLATION` / `RETURN`) |
| `stock_restoration_item` | 복원 × SKU — `quantity > 0`, `restored` = **되돌릴 대상(선점 때 차감)이었는가**. 선점 항목이 `deducted = true` 였던 SKU 만 `true` 이고 재고를 되돌린다 — 재고 행이 없으면 WARN 만 남기고 수량은 늘지 않는다(`true` 그대로). 무제한 SKU 는 `false` 로 기록만 남긴다. 기록 합이 복원 상한을 정한다 |

- 어드민 재고 조정(`PATCH /api/v1/admin/stocks/{skuId}`)은 수량 **절대값 + 기대 수량 CAS**(`expectedQuantity`) 다 — 그 사이 주문이 차감했으면 409 `PRODUCT_020`.
  재고 행이 없는 SKU 의 첫 등록은 INSERT 이고, 동시 첫 등록은 PK 충돌로 409 다.

#### 어드민 재고 조회 · 조정 이력 (#86)

| 경로 | 동작 |
|------|------|
| `GET /api/v1/admin/stocks?skuIds=1,2,3` | SKU 재고 목록 — `{ stocks: [{ skuId: String, stockManaged, availableQuantity, reservedQuantity, updatedAt }] }`. 항목은 PATCH 응답과 같은 모양이다 |
| `GET /api/v1/admin/stocks/{skuId}/adjustments?page=&size=` | 조정 이력 `PageResponse` — 최신순(아이디 내림차순). 항목 `{ id, beforeQuantity, afterQuantity, delta, beforeStockManaged, afterStockManaged, reason{code,name,description}, memo, adminMemberId, createdAt }` |

- **SKU 목록 방식이다 — 상품 단위 조회는 하지 않는다.** 어드민 상품 상세(#9)가 아직 없어 화면이 상품 → SKU 를 고르는 경로가 정해지지 않았다.
  #9 에서 상품 상세가 SKU 아이디를 내려 주면 화면은 그 묶음으로 이 API 를 부른다. 상품 단위가 필요해지면 그때 따로 연다.
- skuIds 는 1~100개 · 양수(`PRODUCT_124` 필수 · `PRODUCT_125` 상한 · `PRODUCT_126` 양수), 중복은 첫 등장 한 번이고 요청 순서대로 낸다.
  **없는 SKU 는 응답에서 빠진다** — 서비스 간 SKU 조회와 같은 관례이고, 지워진 SKU 하나 때문에 나머지 SKU 조정까지 막지 않는다.
- **재고 행이 없는 SKU 는 품절로 낸다** — `stockManaged=true · availableQuantity=0 · updatedAt=null`. `reservedQuantity` 는 재고 행이 없으면
  차감 선점이 생길 수 없어 정상이면 0 이다 (읽은 값을 그대로 낸다). 화면은 조회한
  `availableQuantity` 를 **그대로 PATCH 의 `expectedQuantity` 로 보낸다** — 행 없는 SKU 의 첫 등록이 기대 수량 0 을 요구하는 규칙과 맞물린다.
- 재고 목록은 `REPEATABLE_READ` 읽기 전용 트랜잭션이다 — 재고 행과 선점 합을 한 스냅숏으로 읽어 "실물 = 판매 가능 + 선점" 계산이 엇갈리지 않게 한다.
- 쿼리는 재고 목록이 SKU 존재(in 1) · 재고 행(in 1) · 선점 합(SKU 묶음 group by 1) 3번(SKU 수와 무관), 조정 이력이 SKU 존재 1 · 목록 1 ·
  전체 건수는 필요할 때만 1번(최대 3번 — Spring Data 는 첫 페이지가 size 보다 짧으면 count 를 생략한다)이다.
  선점 합은 PATCH 도 같은 묶음 쿼리를 SKU 하나로 부른다. 이력은 `idx_stock_adjustment_history_sku_id_id` 를 탄다.
- 조정 이력의 `adminMemberId` 는 내부 회원 아이디라 그대로 낸다(개인정보 아님). 어드민 이름 표시는 auth-service 조회가 필요해 범위 밖이다.
- 이력 조회의 없는 SKU 는 404 `PRODUCT_002`, 이력이 없는 SKU 는 빈 목록이다. 두 조회 모두 `hasRole('ADMIN')`, Facade 는 `@Transactional(readOnly = true)` 다.

#### 어드민 조정 운영 규칙

- **`availableQuantity`(요청의 `quantity`)는 선점분을 뺀 판매 가능 수량이다 — 실물 수량이 아니다.** 응답의 `reservedQuantity` 는 그 SKU 의
  RESERVED 선점 중 실제로 차감한(`deducted = true`) 수량 합이다. **실사 수량을 입력할 때는 `quantity = 실물 − reservedQuantity`** 로 보낸다.
  실물을 그대로 넣으면 결제 대기 중인 선점분만큼 초과 판매된다. 결제 완료(CONFIRMED) 뒤 아직 출고하지 않은 수량도 실물에 있으므로 함께 빼야 한다 —
  배송 연동 전까지는 운영자가 확인한다. `reservedQuantity` 는 응답 순간의 참고치이고 CAS 판정에는 쓰지 않는다.
- **무제한 → 관리 전환 직후의 선점** — 무제한일 때 만든 선점은 항목이 `deducted = false` 다. 그 선점이 RESERVED 로 남아 있는 동안 관리로 바꾸면,
  그 선점은 재고를 차감하지 않은 채 확정될 수 있다(확정은 재고를 다시 보지 않는다). 전환할 때는 **그 SKU 의 결제 대기 주문이 끝난 뒤(최대 `stock.reservation.ttl`)
  실사 수량을 다시 맞춘다.** 반대로 관리 → 무제한 전환 뒤 풀리는 선점은 차감했던 수량을 무제한 SKU 에 되돌린다 — 판매 판정에는 영향이 없다.

### 3-2. 서비스 간 API (호출자 order-service)

| 경로 | 동작 |
|------|------|
| `POST /internal/v1/stock-reservations` | 선점 — 요청 `{ orderId, items: [{ skuId, quantity }] }` (항목 1~100개, 수량 1~10000) |
| `POST /internal/v1/stock-reservations/{reservationId}/confirmation` | 확정 (결제 완료) |
| `POST /internal/v1/stock-reservations/{reservationId}/release` | 해제 (결제 전 취소 · 주문 생성 실패) |
| `POST /internal/v1/stock-restorations` | 결제 후 복원 (결제 후 취소 · 반품 입고, #85) — 아래 "결제 후 복원" |

선점 · 확정 · 해제 응답은 같은 모양이고 신규 · 기존 모두 200 이다 —
`{ reservationId: String, orderId: String, status, expiresAt(UTC ISO-8601), items: [{ skuId: String, quantity, deducted }] }`. 항목은 SKU 아이디 오름차순이다.

선점 처리 순서 (Facade 트랜잭션 하나, 기본 격리 READ COMMITTED):

1. 같은 `skuId` 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다.
2. 같은 주문의 활성 선점이 있으면 — SKU 별 수량이 같으면 그 선점을 그대로 200(멱등), 다르면 409 `PRODUCT_023`.
3. SKU · 상품(숨김 포함)을 in 절로 읽어 판매 가능 조건(상품 노출 · 판매중 && SKU 판매중 — internal SKU 조회의 `purchasable` 과 같은 규칙에서 재고만 뺀 것)을
   확인한다. 없는 SKU · 숨긴 상품 · 판매 종료는 모두 모아 409 `PRODUCT_022`.
4. 선점 **헤더만** INSERT 하고 flush 한다(RESERVED, `expires_at = now + ttl`) — 같은 주문의 동시 생성은 유니크 키에서 먼저 커밋된 쪽을 기다렸다가
   409 `PRODUCT_026` 로 끝나 **재고를 건드리지 않는다**. 차감을 먼저 하면 재고가 빠듯할 때 중복 요청이 409 대신 `PRODUCT_021` 을 받는다.
5. **모든 항목**에 SKU 오름차순으로 조건부 차감(`stock_managed = true and available_quantity >= qty`)을 시도한다. 1행이면 `deducted = true`.
6. 0행인 SKU 만 재고 행을 in 절로 **다시 읽는다**(같은 트랜잭션, READ COMMITTED 라 최신 커밋 값). 무제한이면 `deducted = false` 로 선점하고,
   관리 중 부족이거나 **행이 없으면(품절)** 부족이다. 차감 여부를 미리 읽은 스냅숏으로 정하지 않는다 — 읽은 뒤 어드민이 관리 여부를 바꾸면
   관리 SKU 를 차감 없이 선점하거나 무제한 SKU 를 부족으로 거절한다(TOCTOU).
7. 부족한 SKU 가 하나라도 있으면 모두 모아 409 `PRODUCT_021`("…: [1001, 1003]") — **예외가 같은 트랜잭션을 롤백해 앞서 차감한 재고와 헤더가 함께 사라진다(전체 해제)**.
   따로 보상 해제를 부르지 않는다. 없으면 `deducted` 가 정해진 항목을 INSERT 한다.

#### 결제 후 복원 (#85)

요청 `{ claimId, reservationId, reason: CANCELLATION | RETURN, items: [{ skuId, quantity }] }` (항목 1~100개, 수량 1~10000 — 선점과 같은 상한).
응답 200 `{ restorationId: String, claimId: String, reservationId: String, reason, items: [{ skuId: String, quantity, restored }] }` — 신규 · 같은 클레임의
재요청 모두 같은 모양이다. `reason` 은 선점 응답의 `status` 처럼 분기용 제어 코드 문자열이다. 항목은 SKU 아이디 오름차순이다.

- **확정된 선점의 상태는 바꾸지 않는다** (`CONFIRMED` 그대로 — 상태 전이 없음). 복원은 클레임마다 append-only 기록(`stock_restoration` · `_item`)으로 남기고,
  그 기록으로 멱등(클레임 아이디)과 상한(선점 수량 − 이미 복원한 수량)을 판정한다. **부분 클레임**(3개 중 1개 반품)이 한 선점에 여러 번 온다.
- **반품 복원 여부는 product-service 가 판단하지 않는다.** 검수 뒤 어드민이 "재고로 되돌림" 을 고른 반품만 order-service 가 `reason = RETURN` 으로 부르고,
  되돌리지 않는 반품(파손 · 불량)은 **부르지 않는다**.

처리 순서 (Facade 트랜잭션 하나, 기본 격리 READ COMMITTED):

1. 같은 `skuId` 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다.
2. 선점 헤더를 **비관적 쓰기 잠금**(`@Lock(PESSIMISTIC_WRITE)` 정적 JPQL, PostgreSQL 에서는 `for no key update`)으로 읽는다. 없으면 404 `PRODUCT_003`.
   잠금 대기는 2000ms(호출자 Feign read timeout 3000ms 보다 짧게)까지이고, 넘으면 아무것도 바꾸지 않고 409 `PRODUCT_031`(재시도)이다.
   복원은 상태 전이가 없어 `@Version` 이 경합을 잡지 못한다 — 잠금이 없으면 같은 선점의 서로 다른 클레임 둘이 "이미 복원한 수량" 을 각자 읽고 함께 상한을 넘긴다.
   같은 클레임의 동시 재요청도 여기서 줄을 서 늦은 쪽이 3단계에서 먼저 커밋된 복원을 본다. 잠금 순서는 선점 헤더 → 재고 행(SKU 오름차순)으로
   해제 · 만료(헤더 UPDATE → 재고 오름차순)와 같아 교착이 없다.
3. 같은 `claimId` 의 복원이 있으면 — 선점 · 사유 · SKU 별 수량이 같으면 그 복원을 그대로 200(멱등, 선점 상태와 무관), 다르면 409 `PRODUCT_029`.
4. 선점이 `CONFIRMED` 가 아니면 409 `PRODUCT_027` — 결제 전(`RESERVED`) · 이미 해제(`RELEASED`)는 복원이 아니라 해제 경로다.
5. 이 선점에서 SKU 별로 이미 복원한 수량을 group by 한 번으로 읽는다(`idx_stock_restoration_reservation_id` → 항목 유니크 키 접두).
   재고를 되돌리지 않은 무제한 항목도 합에 들어간다 — 상한은 선점 수량 기준이다.
6. 항목마다 (선점 항목 수량 − 이미 복원한 수량)을 넘으면 — 선점에 없는 SKU 포함 — 모두 모아 409 `PRODUCT_028`("…: [1001, 9999]"). 아무것도 쓰지 않는다.
7. 복원 기록(헤더 · 항목)을 INSERT 하고 flush 한다. 클레임 유니크 키 위반(2단계 잠금을 비켜 간 동시 생성 — 다른 `reservationId` 로 온 경우)은 409 `PRODUCT_030`.
8. 선점 항목이 `deducted = true` 였던 SKU 만 **SKU 오름차순 가산 UPDATE**(`available_quantity += qty` — 관리 여부 무관, 해제와 같은 쿼리)를 한다 —
   무제한 SKU 는 `restored = false` 로 기록만 남는다.
   재고 행이 없어 0행이면 해제 경로와 같이 WARN 로그만 남기고 막지 않는다(재고 행은 지우지 않으므로 데이터 오류다).

### 3-3. 선점 상태 전이

```text
RESERVED ──확정──▶ CONFIRMED
   └──────해제 · 만료──▶ RELEASED (release_reason = RELEASED_BY_CALLER / EXPIRED)
```

| 현재 | confirm | release |
|------|---------|---------|
| `RESERVED` (만료 시각 전 · 후, 스케줄러가 풀기 전 모두) | `CONFIRMED` 200 | `RELEASED` + 재고 복원 200 |
| `CONFIRMED` | 200 (그대로) | 409 `PRODUCT_024` |
| `RELEASED` | 409 `PRODUCT_025` | 200 (그대로) |
| 없음 | 404 `PRODUCT_003` | 404 `PRODUCT_003` |
| 동시 변경 (`@Version`) | 409 `PRODUCT_026` | 409 `PRODUCT_026` |

- 전이는 도메인 `StockReservation.confirm(now)` · `release(now, reason)` 만 한다. 상태를 저장(merge · 버전 검사 · flush)한 **뒤** `deducted` 항목을 SKU 오름차순으로
  복원한다 — 같은 해제가 동시에 와도 버전에서 진 쪽은 복원까지 가지 않아 재고가 한 번만 늘어난다.
- 만료 스케줄러(`StockReservationExpiryScheduler`)는 `stock.reservation.expiry-sweep.*`(기본 fixed-delay 60s · batch-size 100 · max-batches-per-run 10) 주기로
  `RESERVED && expires_at <= now` 를 `(expires_at, id)` 순으로 읽고(`idx_stock_reservation_status_expires_at_id`), **건마다 트랜잭션**으로 다시 읽어
  아직 만료 대상일 때만 `release(EXPIRED)` + 복원한다. 한 실행 안에서는 배치의 마지막 건 다음부터 이어 읽는다(keyset) — 실패한 건이 RESERVED 로 남아도
  같은 실행이 그 건으로 배치를 다시 채우지 않는다(head-of-line blocking 없음). 실패한 건은 다음 실행이 다시 읽는다.
  한 건의 실패는 로그만 남기고 다음 건으로 간다 — 409 `PRODUCT_026`(확정 · 다른 인스턴스와 경합)은 WARN, 그 밖의 업무 오류 · 예상 못 한 예외는 ERROR 이고
  업무 오류면 오류 코드를 남긴다. 확정과 만료가 겹치면 `@Version` 이 하나만 통과시킨다. 인스턴스가 여럿이어도 분산 락을 두지 않는다.
- **만료 후 확정** — 스케줄러가 먼저 풀었으면 확정은 409 `PRODUCT_025` 다. order-service 의 처리는 아래 §3-4 를 따른다.
- 허용 전이 표 밖의 요청은 도메인이 `StockReservationTransitionException`(현재 상태 · 시도한 동작)으로 알리고, `StockReservationProcessor` 가
  `PRODUCT_024` · `PRODUCT_025` 를 붙인다 — domain 은 application 의 오류 코드를 모른다 (`coding-conventions.md` §8-1).

| 코드 | HTTP | 뜻 |
|------|------|----|
| `PRODUCT_003` | 404 | 없는 재고 선점 |
| `PRODUCT_021` | 409 | 재고 부족 (행 없는 SKU 포함) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_022` | 409 | 판매 불가 SKU (없는 SKU · 숨긴 상품 · 판매 종료) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_023` | 409 | 같은 주문의 활성 선점과 항목이 다르다 |
| `PRODUCT_024` | 409 | 이미 확정된 선점의 해제 |
| `PRODUCT_025` | 409 | 이미 해제 · 만료된 선점의 확정 |
| `PRODUCT_026` | 409 | 동시 변경(`@Version`) · 같은 주문의 동시 선점 생성 — 다시 시도한다 |
| `PRODUCT_027` | 409 | 결제 후 복원 — 선점이 확정(`CONFIRMED`)이 아니다 |
| `PRODUCT_028` | 409 | 결제 후 복원 — (선점 수량 − 이미 복원한 수량)을 넘는 SKU(선점에 없는 SKU 포함) — 메시지에 SKU 아이디 목록 |
| `PRODUCT_029` | 409 | 결제 후 복원 — 같은 클레임의 기존 복원과 선점 · 사유 · 항목이 다르다 |
| `PRODUCT_030` | 409 | 결제 후 복원 — 같은 클레임의 동시 복원 생성 — 다시 시도한다 |
| `PRODUCT_031` | 409 | 결제 후 복원 — 선점 헤더 잠금 대기 상한 초과(같은 선점을 처리 중) — 다시 시도한다 |
| `PRODUCT_112`~`PRODUCT_130` | 400 | 요청 검증 (`ProductValidationMessage`) — 선점 수량 상한 `PRODUCT_122`, 어드민 조정 · 이력 경로의 `skuId` 양수 `PRODUCT_123`, 어드민 재고 조회 `skuIds` `PRODUCT_124`~`PRODUCT_126`, 복원 `claimId` 필수 · 양수 `PRODUCT_127` · `PRODUCT_128`, `reservationId` 필수 `PRODUCT_129`, `reason` 필수 `PRODUCT_130`. 복원의 `reservationId` 양수와 `items` 이하는 선점 코드(`PRODUCT_121` · `PRODUCT_114`~`PRODUCT_120` · `PRODUCT_122`)를 그대로 쓴다 |

- 확정된 선점은 해제하지 않는다(`PRODUCT_024`). 결제 후 취소 · 반품의 재고는 위 "결제 후 복원" 경로로 되돌린다 — 선점 상태는 `CONFIRMED` 그대로다.

### 3-4. order-service 호출 규칙

- **주문을 PAID 로 확정하기 전에 선점 확정(`/confirmation`)의 200 · `CONFIRMED` 응답을 받아야 한다.** 확정 응답 없이 PAID 로 두면 만료 스케줄러가 선점을 풀어
  돈은 받았는데 재고는 다른 주문에 팔린다. 결제 완료 알림이 다시 와도(웹훅 재전송 · 재조회) 확정을 **멱등으로 다시 부른다** — 이미 CONFIRMED 면 200 이다.
- **만료 후 확정(409 `PRODUCT_025`)** — 같은 `orderId` 로 다시 선점하고(해제된 주문은 활성 유니크 키에 걸리지 않는다) **곧바로 같은 흐름에서 확정**한다.
  재선점과 확정 사이를 비우면 새 선점도 `ttl` 뒤 다시 만료돼 같은 경합이 되풀이된다. 재선점이 `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면
  결제를 전액 취소한다 (`payment-guide.md` §6).
- **409 는 `resultCode` 로 분기한다.** `PRODUCT_026`(동시 변경 · 동시 생성) · `PRODUCT_030`(같은 클레임의 동시 복원 생성) ·
  `PRODUCT_031`(선점 잠금 대기 초과)만 재시도 대상이고 **새 트랜잭션(새 요청)** 으로 다시 부른다 — 다시 부르면 바뀐 상태를 기준으로 답한다.
  재시도는 **상한을 둔다(예: 3회, 간격을 늘려 가며)** — 넘으면 영속 재전송(아래 복원 보장)으로 넘긴다.
  `PRODUCT_030` 재시도는 같은 본문이면 먼저 끝난 복원으로 200, 다른 선점으로 보냈으면 `PRODUCT_029`(같은 claimId 를 두 선점에 쓴 호출자 버그 — 종결)다.
  `PRODUCT_021` · `PRODUCT_022` · `PRODUCT_023` · `PRODUCT_024` · `PRODUCT_025` · `PRODUCT_027` · `PRODUCT_028` · `PRODUCT_029` 는 재시도해도 결과가 같은 종결 오류다.
- **결제 후 복원(`/internal/v1/stock-restorations`)은 클레임이 확정될 때 부른다** — 결제 후 취소는 취소 확정(환불 요청과 같은 흐름), 반품은 검수 뒤
  어드민이 "재고로 되돌림" 을 고른 경우만(`reason = RETURN`). 되돌리지 않는 반품은 부르지 않는다. **`claimId` 가 멱등 키**다 — 타임아웃 · 재시도는
  같은 `claimId` · 같은 본문으로 다시 부른다(재고는 한 번만 늘고 200). 같은 클레임에 다른 수량을 보내면 `PRODUCT_029` 이므로, 클레임 수량을 바꿔야 하면
  새 클레임으로 다룬다. 항목 수량은 그 클레임의 SKU 별 수량이고 선점의 `reservationId` 는 주문에 저장한 확정 선점이다.
  `PRODUCT_028` 은 호출한 쪽의 클레임 수량 계산 오류(확정 · 이미 복원한 수량 초과)다 — 조용히 완료 처리하지 않는다.
- **복원의 클레임 아이디는 order-service 가 먼저 만든 클레임 행의 아이디다** (#16 · #17 이 지킬 호출자 계약).
  - 결제 후 취소는 **경로와 상관없이** — 고객 취소 · 어드민 취소 · PG 웹훅 / PG 콘솔 취소 · 자동 전액 취소(재선점 실패 등) — order-service 가 먼저 클레임 행을
    만들고 그 `claimId` 로 복원한다. 재시도마다 새 아이디를 만들지 않는다 — 새 아이디면 멱등이 깨져 재고가 두 번 는다(상한 안이면 409 도 나지 않는다).
  - **복원 호출은 영속적으로 보장한다.** 클레임에 "재고 복원 대기" 표시를 두고 200 을 받으면 지운다. 실패(타임아웃 · 서킷 오픈 · 재시도 대상 409 상한 초과)하면
    스케줄러가 **같은 claimId · 같은 본문**으로 다시 보낸다 — `payment-guide.md` §6 의 재전송 패턴과 같다. 종결 409 는 어드민에 노출한다.
  - **복원을 부른 클레임은 철회하지 않는다.** 환불이 실패(`REFUND_FAILED`)해도 환불만 재시도한다 — 재고를 다시 빼는(복원을 되돌리는) API 는 없다.
- **409 를 같은 트랜잭션 안에서 잡고 계속하지 않는다.** product-service 안에서도 어댑터가 409 로 바꾼 시점에는 flush 가 이미 실패해 트랜잭션이 rollback-only 다 —
  잡고 계속하면 커밋에서 실패한다. order-service 도 Feign 409 를 받은 트랜잭션에서 주문 상태를 바꾸고 재시도까지 하지 말고, 재시도는 새 트랜잭션에서 한다.
- **확정 · 해제 · 복원은 `reservationId` 로 대상 선점을 식별한다** — 서비스 토큰(`SERVICE_order-service`)을 가진 호출자를 신뢰한다는 전제다. order-service 는 자기 주문의
  `reservationId` 만 저장 · 사용한다.
- **오류 메시지를 고객 응답에 그대로 넘기지 않는다.** `PRODUCT_021` · `PRODUCT_022` · `PRODUCT_028` 메시지에는 SKU 아이디 목록이 실린다 — order-service 는 자기 오류 코드
  (예: `ORDER_*`)와 고객용 문구로 바꾸고, 필요한 SKU 정보는 자기 주문 항목에서 골라 보여 준다.

## 4. 배송

| 상태 | 뜻 |
|------|-----|
| `READY` | 출고 대기 (주문 `PREPARING`) |
| `SHIPPED` | 송장 등록 (주문 `SHIPPING`) |
| `IN_TRANSIT` | 택배사 이동 중 |
| `DELIVERED` | 배송 완료 (주문 `DELIVERED`) |

- 송장 등록은 어드민에서 단건/일괄(엑셀)로 한다. 택배사 코드는 enum 으로 관리한다.
- 배송 상태 추적은 외부 추적 API(`ShipmentTrackingPort`)로 하되, 실패해도 주문 흐름을 막지 않는다. 어드민이 수동으로도 바꿀 수 있다.
- 해외 배송비는 국가 그룹 · 무게 구간으로 정한다(§2, #22). 무게는 견적 요청이 싣고(SKU 무게 × 수량 합 — §2-2), 무게 · HS 코드는 product-service SKU 에 있다(#80).
  통관 정보(HS 코드, 신고 금액 = 주문 항목의 주문 시점 금액)는 **주문 항목 스냅숏**에서 송장 · 출고(#18)로 넘긴다 — §2-2.
- 배송지 정보는 주문 시점 스냅숏이다. 회원 주소록을 바꿔도 지난 주문 배송지는 바뀌지 않는다.
- **주문 생성 요청은 배송지 값 자체를 싣는다** (FE 가 주소록에서 골라 채우거나 새로 입력한다). order-service 는 그 값을 스냅숏으로 저장하고
  auth-service 주소록을 조회하지 않는다 (#7 결정). 그래서 지금은 auth-service 에 배송지 internal API 를 만들지 않는다 — 필요해지면 주문 생성(#13)에서 이슈로 연다.
  주문 화면의 "주소록에 저장" 은 FE 가 주소록 API(`POST /api/v1/members/me/shipping-addresses`, `api-design-guide.md` §2-4)를 따로 부른다.
  스냅숏의 형식 검증은 주소록과 같은 규칙(국가 코드 · 우편번호 · 연락처 · 해외 도시)을 order-service 가 다시 건다 — 클라이언트 값을 그대로 믿지 않는다.
- **미결 — 프리오더 상품 (#61 에서 상품 데이터만 추가, 주문 쪽은 구현하지 않음)**: 프리오더(`product.preorder` · 발송 예정일)는 지금 판매 · 재고 판정에 영향이 없다.
  아래는 주문 생성(#13) 이후 정한다.
  - 프리오더 + 일반 상품이 한 주문에 섞일 때 배송을 나누는가(분리 출고 · 배송비 이중 부과), 아니면 섞어 담지 못하게 막는가
  - 주문 항목 스냅숏에 프리오더 여부 · 발송 예정일을 남기는가
  - 발송 예정일이 바뀌면 이미 주문한 고객에게 알리는가

## 5. 클레임 (취소 · 반품 · 교환)

- 클레임은 `claim` 엔티티로 따로 둔다 (유형, 대상 주문 항목, 수량, 사유, 상태, 환불 예정 금액).
- **부분 클레임**을 전제로 설계한다 — 주문 3개 중 1개만 반품.
- 환불 금액 계산(배송비 차감, 무료배송 기준 미달 시 재부과)은 order-service 가 하고, 실행은 payment-service 가 한다.
- 환불 실행이 실패하면 클레임은 `REFUND_FAILED` 로 남기고 어드민에 노출한다. 조용히 완료 처리하지 않는다.
- **재고 복원은 클레임당 한 번이다** — `claimId` 가 product-service 복원 API 의 멱등 키다(§3-2 "결제 후 복원"). 결제 후 취소는 취소 확정 때,
  반품은 검수 뒤 어드민이 재고로 되돌림을 고른 경우만 부른다. 확정 수량을 넘는 복원은 product-service 가 409 로 거절한다.
  - 결제 후 취소는 경로(고객 · 어드민 · PG 웹훅 / 콘솔 취소 · 자동 전액 취소)와 상관없이 **클레임 행을 먼저 만들고** 그 아이디로 복원한다. 재시도마다 새 아이디를 만들지 않는다.
  - 클레임에 **"재고 복원 대기"** 를 두고, 복원이 실패하면 스케줄러가 같은 claimId · 같은 본문으로 다시 보낸다 (`payment-guide.md` §6 재전송과 같은 패턴).
  - 복원을 부른 클레임은 **철회하지 않는다** — `REFUND_FAILED` 면 환불만 재시도한다. 복원을 되돌리는 API 는 없다. 상세는 §3-4.
- 교환은 v1 에서 "반품 + 재주문" 으로 운영하고, 전용 흐름은 필요해질 때 만든다.

## 6. 어드민 운영 화면이 보는 것

어드민 API 는 위 상태를 그대로 다룬다. 최소 범위:

- 주문 목록(상태·기간·결제수단 필터, `PageResponse`), 주문 상세(항목·결제·배송·이력)
- 상태 일괄 변경(`PAID → PREPARING`), 송장 일괄 등록
- 취소·반품 요청 처리(승인/거절, 환불 실행)
- 결제 `UNKNOWN`·`REFUND_FAILED`·대사 불일치 건 목록 — **사람이 봐야 하는 건을 모아서 보여준다**
