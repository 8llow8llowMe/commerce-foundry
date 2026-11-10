package com.commercefoundry.domainlayer.order.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 도메인 오류 코드 ({@code coding-conventions.md} §8). 번호 대역은 001 주문 조회(#16) · 002~009 주문 항목 검증 · 010 주문 금액 변경 ·
 * 011~015 배송비 견적 · 016~ 생성 · 멱등 · 재고 — 필드별 검증 코드(1xx)는 {@code OrderValidationMessage} 에 둔다.
 * 002~009 중 002 는 상품 서비스 장애(catalog {@code CATALOG_001} 번역, 503) 자리이고 009 는 비워 둔다.
 *
 * <p>주문 항목 검증 코드(003~008)는 {@code OrderQuoteProcessor} 가 판정 순서대로 처음 걸리는 하나만 낸다 ({@code order-flow-guide.md} §2-3).
 * 메시지의 {@code %s} 는 걸린 줄 모두를 고객이 알아볼 이름("상품명(옵션명)")으로 이은 것이다 — SKU 아이디 · product-service 메시지 · 숨긴 상품명은 넣지 않는다.
 *
 * <p>배송비 견적 코드(011~015)는 delivery-service 의 업무 거절({@code DELIVERY_001/002/003/011})을 주문 계약으로 번역한 것이다 —
 * delivery 의 코드는 주문 응답에 그대로 나가지 않는다. 번역은 {@code ShippingFeeInternalResponseSupport} 가 한다.
 * 응답 봉투로 바꾸는 것은 {@code OrderExceptionHandler} 다(주문 진입 API, #13).
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode {

    /** 없거나 다른 회원의 주문 — 존재를 숨기려고 둘 다 404 다 ({@code api-design-guide.md} §2-2). 주문 생성의 재생(같은 키 재요청)도 이 조회를 쓴다. */
    NOT_FOUND_ORDER("ORDER_001", "존재하지 않는 주문입니다.", HttpStatus.NOT_FOUND),
    /**
     * product-service 서킷 오픈 · 전송 실패 · 5xx — catalog 의 {@code CATALOG_001} 을 {@code OrderExceptionHandler} 가 이 코드로 번역한다
     * ({@code architecture-guide.md} §3). SKU 응답이 계약과 다를 때(해외 주문의 SKU 무게가 1 미만)도 이 코드다 — 값을 추측하지 않는다.
     * 주문 생성의 재고 선점 결과 모름(타임아웃 · 5xx · 서킷 · 응답 계약 위반) · 예상하지 못한 거절도 이 코드다 — 같은 키로 다시 보낸다 (#13).
     */
    PRODUCT_SERVICE_UNAVAILABLE("ORDER_002", "상품 정보를 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /**
     * 없는 SKU · 품절 · 판매 종료 · 숨김({@code purchasable=false}). {@code %s} 는 그 줄들의 이름이고, 없는 SKU 는 "찾을 수 없는 상품",
     * 품절이 아닌 숨김 · 판매 종료는 "판매하지 않는 상품" 이다 — 숨긴 상품명을 내보내지 않는다.
     */
    ITEM_NOT_PURCHASABLE("ORDER_003", "지금은 구매할 수 없는 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다. {@code %s} 는 그 줄들의 이름이다. */
    CUSTOM_OPTION_NOT_ALLOWED("ORDER_004", "맞춤 제작 상품이 아니어서 맞춤 제작 입력을 받을 수 없습니다: %s", HttpStatus.BAD_REQUEST),
    /**
     * 상품 단위 수량 합이 상품의 1회 주문 최대 수량을 넘는다 — 상품 컬럼이라 SKU 별로 세면 S 2 + M 2 로 "1인 2벌" 이 우회된다.
     * 장바구니는 줄 단위로만 막아 여기서 거절될 수 있다. {@code %s} 는 "상품명(최대 N개)" 목록이다.
     */
    QUANTITY_EXCEEDED("ORDER_005", "한 번에 주문할 수 있는 수량을 넘은 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** SKU 판매가의 통화가 섞였다 — 통화별 상품 가격(#20) 전에는 한 주문이 한 통화다. 환산하지 않는다. */
    CURRENCY_MIXED("ORDER_006", "통화가 다른 상품은 함께 주문할 수 없습니다.", HttpStatus.CONFLICT),
    /**
     * 해외 주문인데 무게 또는 HS 코드가 없는 SKU 가 있다 ({@code order-flow-guide.md} §2-2, #80). 배송할 수 없는 국가({@code ORDER_013})가 이 사유보다 앞선다.
     * {@code %s} 는 그 줄들의 이름이다.
     */
    INTERNATIONAL_SHIPPING_INFO_MISSING("ORDER_007", "해외 배송 정보가 준비되지 않아 해외로 주문할 수 없는 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** 같은 SKU · 같은 맞춤 제작 입력(정규화한 값)의 줄이 요청 안에 두 번 이상 있다 — 프론트가 한 줄로 합쳐 보낸다. {@code %s} 는 그 줄들의 이름이다. */
    DUPLICATE_LINE("ORDER_008", "같은 상품이 주문에 중복되어 있습니다: %s", HttpStatus.BAD_REQUEST),
    /** 주문 생성 요청의 {@code expectedTotalAmount} 가 지금 계산한 총액과 다르다 — 가격 · 배송비가 그 사이 바뀌었다. 재고를 잡기 전에 거절한다. */
    ORDER_AMOUNT_CHANGED("ORDER_010", "주문 금액이 변경되었습니다. 주문서를 다시 확인해 주세요.", HttpStatus.CONFLICT),
    /**
     * delivery-service 서킷 오픈 · 전송 실패 · 타임아웃 · 5xx, 번역하지 않는 4xx(우리 요청 · 서비스 토큰 설정 문제), 응답 계약 위반
     * ({@code coding-conventions.md} §10). 같은 요청을 다시 보내면 풀릴 수 있다.
     */
    DELIVERY_SERVICE_UNAVAILABLE("ORDER_011", "배송비를 잠시 계산할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 배송비 정책이 없다({@code DELIVERY_001}) — 0원 배송으로 열리지 않게 주문을 막는다. 운영자가 정책을 등록해야 풀리므로 error 로그를 남긴다. */
    SHIPPING_UNAVAILABLE("ORDER_012", "지금은 이 배송지로 주문할 수 없습니다. 고객센터에 문의해 주세요.", HttpStatus.CONFLICT),
    /** 배송할 수 없는 국가({@code DELIVERY_002}) — 해외 배송이 꺼져 있거나 어느 해외 국가 그룹에도 없다. */
    DESTINATION_NOT_SUPPORTED("ORDER_013", "배송할 수 없는 국가입니다.", HttpStatus.BAD_REQUEST),
    /** 주문 통화가 그 배송 범위 정책의 통화와 다르다({@code DELIVERY_003}) — 환산하지 않는다. 예: 영문 스토어(USD) 주문 + 국내(KR) 배송지 (#70). */
    DESTINATION_CURRENCY_NOT_SUPPORTED("ORDER_014", "주문 통화로는 이 배송지에 배송할 수 없습니다.", HttpStatus.BAD_REQUEST),
    /**
     * 총 무게가 가장 큰 무게 구간을 넘는다({@code DELIVERY_011}). 총 무게가 int 범위를 넘어 견적을 부르지 않을 때도 같은 코드다
     * ({@code order-flow-guide.md} §2-2).
     */
    SHIPPING_WEIGHT_NOT_SUPPORTED("ORDER_015", "배송할 수 있는 무게를 넘었습니다. 주문 수량을 줄여 주세요.", HttpStatus.BAD_REQUEST),

    /** 재고 부족(PRODUCT_021). product-service 가 선점 전체를 한 트랜잭션으로 되돌려 선점이 남지 않는다. */
    STOCK_INSUFFICIENT("ORDER_016", "재고가 부족한 상품이 있습니다.", HttpStatus.CONFLICT),
    /** 같은 주문 아이디의 활성 선점과 항목이 다르다(PRODUCT_023) — 같은 요청 지문이면 항목도 같아야 하므로 버그 · 데이터 이상이다. error 로그를 남긴다. */
    STOCK_RESERVATION_INCONSISTENT("ORDER_017", "주문을 접수하지 못했습니다. 고객센터에 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 같은 키의 주문 생성이 처리 중이다(lease 안) · 같은 주문의 재고 선점이 동시에 겹쳤다(PRODUCT_026). 같은 키로 잠시 뒤 다시 보낸다. */
    IDEMPOTENCY_IN_PROGRESS("ORDER_018", "같은 주문이 처리 중입니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 같은 키를 다른 본문으로 다시 썼다 — 상태와 무관하다. 새 키를 쓴다. */
    IDEMPOTENCY_KEY_REUSED("ORDER_019", "같은 Idempotency-Key 로 다른 주문을 요청할 수 없습니다.", HttpStatus.CONFLICT),
    /** 허용 전이 표 밖 상태 변경 ({@code order-flow-guide.md} §1-1). {@code %s} 는 현재 상태다. */
    ORDER_STATUS_TRANSITION_INVALID("ORDER_020", "현재 주문 상태(%s)에서는 할 수 없는 요청입니다.", HttpStatus.CONFLICT),
    /** DB 가 주문 저장을 거절했다(제약 위반 등 버그 · 데이터 이상). 원인 메시지(제약 위반 행 값)는 싣지 않는다 — 개인정보가 로그 · 응답에 남지 않게. */
    ORDER_SAVE_FAILED("ORDER_021", "주문을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 회원이 선점 TTL 창 안에 새 키로 시작한 주문 생성 시도가 상한을 넘었다 (#231, {@code order.creation.max-active-requests-per-member}).
     * 시도마다 재고 선점이 생겨 계정 하나로 재고를 묶어 두는 것을 막는다. 원격 호출 전에 거절한다. 같은 키 재시도는 세지 않는다.
     */
    ACTIVE_ORDER_REQUESTS_EXCEEDED("ORDER_022", "주문 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /**
     * 전이 표에는 있지만 어드민 화면에서 바꾸는 단계가 아니다 (#17) — 결제 완료 · 만료 · 출고(송장 등록) · 취소 · 반품은 각자의 흐름이 바꾼다.
     * {@code %s} 는 바꾸려던 상태 이름이다.
     */
    ADMIN_STATUS_CHANGE_NOT_ALLOWED("ORDER_023", "'%s' 상태는 이 화면에서 바꿀 수 없습니다.", HttpStatus.CONFLICT),
    /** 맞춤 제작 항목이 없는 주문을 제작 중(MAKING)으로 바꾸려 했다 (#17). */
    MAKING_REQUIRES_CUSTOM_ORDER_ITEM("ORDER_024", "맞춤 제작 상품이 없는 주문은 제작 중으로 바꿀 수 없습니다.", HttpStatus.CONFLICT),
    /** 읽은 뒤 다른 곳(고객 · 결제 · 다른 운영자)이 먼저 주문 상태를 바꿨다 — {@code @Version} 충돌 (#17). 새로 읽어 다시 판단한다. */
    ORDER_CONCURRENTLY_CHANGED("ORDER_025", "다른 곳에서 주문이 먼저 바뀌었습니다. 새로고침한 뒤 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 상태 변경 저장이 예상하지 못한 이유(DB 장애 등)로 실패했다 — 일괄 변경의 한 건 결과에 쓴다 (#17). 원인 메시지는 싣지 않는다. */
    ORDER_STATUS_CHANGE_FAILED("ORDER_026", "주문 상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 배송 완료는 고객의 반품 · 구매 확정 기간에 닿는다 — 운영자가 수동으로 바꿀 때는 확인한 근거를 사유로 남긴다 (#17). */
    DELIVERED_REASON_REQUIRED("ORDER_027", "배송 완료로 바꿀 때는 배송을 확인한 근거를 사유로 남겨 주세요.", HttpStatus.BAD_REQUEST),

    /** 결제 반영 기록이 없다. 현재 주문의 존재 · 상태와는 별개다 (#308 C1a). */
    ORDER_PAYMENT_RESULT_NOT_FOUND("ORDER_028", "저장된 결제 반영 결과가 없습니다.", HttpStatus.NOT_FOUND),
    RETURN_ITEM_INVALID("ORDER_029", "반품 항목이 주문에 없거나 중복되었습니다.", HttpStatus.BAD_REQUEST),
    RETURN_QUANTITY_EXCEEDED("ORDER_030", "이미 접수한 반품을 포함하면 주문 수량을 넘습니다.", HttpStatus.CONFLICT),
    RETURN_KEY_REUSED("ORDER_031", "같은 Idempotency-Key 로 다른 반품을 요청할 수 없습니다.", HttpStatus.CONFLICT),
    NOT_FOUND_CLAIM("ORDER_032", "존재하지 않는 클레임입니다.", HttpStatus.NOT_FOUND),
    /** 결제 완료 계열 주문인데 결제 반영 기록(order_payment PAID)이 없다 — 데이터 이상이라 취소를 접수하지 않는다 (#16 PR-2). */
    CANCELLATION_PAYMENT_MISSING("ORDER_033", "결제 정보를 확인할 수 없어 취소를 접수하지 못했습니다. 고객센터에 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 전체 취소 환불액(주문 스냅숏 총액)과 결제 반영 기록의 승인 금액 · 통화가 다르다 — 데이터 이상이라 취소를 접수하지 않는다 (#16 PR-2). */
    CANCELLATION_AMOUNT_MISMATCH("ORDER_034", "결제 금액을 확인할 수 없어 취소를 접수하지 못했습니다. 고객센터에 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 결제 후 취소가 닫혀 있다 — 환불 · 재고 복원 재시도({@code order.cancellation.retry.enabled})가 꺼진 동안은 결제 후 취소를 접수하지 않는다 (#16 PR-2).
     * 이미 접수된 취소의 재요청(현재 상세)과 결제 전 취소는 그대로 된다.
     */
    CANCELLATION_UNAVAILABLE("ORDER_035", "지금은 결제 후 취소를 처리할 수 없습니다. 고객센터에 문의해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 환불 성공 응답은 받았지만 주문 DB 반영을 확정하지 못했다. 환불 실패나 취소 접수 롤백을 뜻하지 않는다. */
    CANCELLATION_REFUND_RECORDING_UNAVAILABLE("ORDER_036", "결제 취소 결과를 주문에 반영하지 못했습니다. 잠시 후 주문 상태를 확인해 주세요.",
        HttpStatus.SERVICE_UNAVAILABLE),
    /** 운영자가 본 클레임 버전(expectedVersion)과 지금 저장된 버전이 다르다 — 스케줄러 · 고객 · 다른 운영자가 먼저 바꿨다 (#17 A2b). */
    CLAIM_CONCURRENTLY_CHANGED("ORDER_037", "다른 곳에서 클레임이 먼저 바뀌었습니다. 새로고침한 뒤 다시 확인해 주세요.", HttpStatus.CONFLICT),
    /** 재시도할 수 없는 클레임 — 처리 완료 · 반품 · 주문 상태 확인이 필요한 접수 (#17 A2b). */
    CLAIM_RETRY_NOT_ALLOWED("ORDER_038", "이 클레임은 지금 다시 시도할 수 없습니다. 처리 상황을 확인해 주세요.", HttpStatus.CONFLICT),
    /** 자동 처리(고객 요청 · 재시도 스케줄러)가 아직 이 클레임을 쥐고 있다 — 겹쳐 보내면 같은 환불 · 복원이 동시에 나간다 (#17 A2b). */
    CLAIM_AUTO_PROCESSING("ORDER_039", "자동 처리가 진행 중입니다. 다음 자동 시도 시각 뒤에 다시 확인해 주세요.", HttpStatus.CONFLICT),
    /** 재시도 스케줄러가 꺼져 있다 — 다시 열어도 미확정 결과를 따라갈 쪽이 없다. 고객 접수의 {@code ORDER_035} 와 같은 게이트다 (#17 A2b). */
    CLAIM_RETRY_UNAVAILABLE("ORDER_040", "지금은 자동 환불 처리가 꺼져 있어 다시 시도할 수 없습니다. 시스템 관리자에게 확인해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패의 폴백. */
    INVALID_REQUEST("ORDER_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 타입 불일치 — {@code Idempotency-Key} 가 UUID 가 아닌 경우도 여기다. */
    PARAMETER_TYPE_INVALID("ORDER_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 필수 파라미터 · 헤더 누락 — {@code Idempotency-Key} 헤더가 없는 경우도 여기다. */
    PARAMETER_REQUIRED("ORDER_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
