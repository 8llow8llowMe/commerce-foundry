package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 도메인 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link ProductValidationMessage} 에 둔다.
 *
 * <p>대역 — {@code 00x} 없는 리소스(404), {@code 01x} 어드민 상품 쓰기(#9 — 삭제 010, 등록 012~016, 맞춤 제작 설정 011(#9 PR-B), 후속 015 · 017),
 * {@code 020~031} 재고(조정 · 선점 · 복원 충돌과 부족, 409), {@code 032~036} 상품 이미지 업로드 · 스토리지(#9 F), {@code 037} · {@code 038} 등록 필드 검증, {@code 039} 온보딩 memberId 검증(#448), {@code 040~045} 어드민 카테고리 관리,
 * {@code 074~099} · {@code 169} · {@code 1xx} 요청 검증(400). {@code 046~073} 은 메인 전시({@code DisplayErrorCode}) · 사이즈표({@code SizeChartErrorCode})가 쓴다 —
 * 2026-10-20 에 승인한 업로드 050~054 · 검증 131~ 은 먼저 쓰여 032~036 · 074~ 로 옮겼다.
 * 카테고리 컨텍스트는 업무 오류를 던지지 않는다(고객 트리는 실패하지 않고, 판정은 {@code CategoryPlacementViolation} 값으로 돌려준다). 어드민 카테고리 관리는
 * 상품 컨텍스트가 받고 상품 Processor 가 코드를 붙이므로 여기 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode {

    NOT_FOUND_PRODUCT("PRODUCT_001", "존재하지 않는 상품입니다.", HttpStatus.NOT_FOUND),
    NOT_FOUND_SKU("PRODUCT_002", "존재하지 않는 SKU 입니다.", HttpStatus.NOT_FOUND),
    NOT_FOUND_STOCK_RESERVATION("PRODUCT_003", "존재하지 않는 재고 선점입니다.", HttpStatus.NOT_FOUND),
    /** 어드민 카테고리 수정 · 삭제의 경로 아이디가 없다. 본문의 상위 아이디가 없으면 {@link #CATEGORY_PARENT_NOT_FOUND}(400)다. */
    NOT_FOUND_CATEGORY("PRODUCT_004", "존재하지 않는 카테고리입니다.", HttpStatus.NOT_FOUND),

    /** 읽은 상품 버전이 merge · flush 전에 바뀌었다. 현재 상태를 다시 읽고 재시도한다. */
    PRODUCT_MODIFICATION_CONFLICT("PRODUCT_010", "상품이 동시에 변경되었습니다. 다시 조회한 뒤 시도해 주세요.", HttpStatus.CONFLICT),
    /**
     * 맞춤 제작 입력 항목 구성이 맞지 않는다 (#9 PR-B) — 같은 키 두 번, 숫자 범위 · 증분, 글자 입력의 최대 글자 수.
     * 주문이 같은 정의로 입력을 검증하므로(order-flow-guide §2-2 4번) 주문이 받을 수 없는 정의를 저장하지 않는다. 메시지에 이유를 싣는다.
     */
    PRODUCT_CUSTOM_ORDER_INVALID("PRODUCT_011", "맞춤 제작 입력 항목 설정이 올바르지 않습니다: %s", HttpStatus.BAD_REQUEST),

    /** 상품 등록 본문의 대표 · 진열 카테고리 중 없는 것이 있다. 본문 참조라 400 이다 — 메시지에 없는 아이디를 모두 싣는다. */
    PRODUCT_CATEGORY_NOT_FOUND("PRODUCT_012", "존재하지 않는 카테고리가 있습니다: %s", HttpStatus.BAD_REQUEST),
    /** 옵션 · SKU 구성이 맞지 않는다 — 옵션 이름 중복, SKU 의 옵션 값 수 · 이름이 그룹과 다름, 옵션 없는 상품의 SKU 수. 메시지에 이유를 싣는다. */
    PRODUCT_OPTION_COMBINATION_INVALID("PRODUCT_013", "옵션 구성이 올바르지 않습니다: %s", HttpStatus.BAD_REQUEST),
    /** 같은 옵션 값 조합의 SKU 가 두 번 있다. */
    PRODUCT_SKU_COMBINATION_DUPLICATED("PRODUCT_014", "같은 옵션 조합의 SKU 가 두 번 있습니다: %s", HttpStatus.BAD_REQUEST),
    /**
     * 가격 규칙 위반 — 통화가 기본 판매가와 다르거나, 기본 판매가가 판매중 SKU 의 최저가가 아니다(판매중 SKU 중 추가금 0 이 없다).
     * 목록 · 상세 표시가와 주문 금액이 같은 규칙({@code ProductPricing})으로 나오게 등록에서 막는다.
     */
    PRODUCT_PRICE_RULE_VIOLATED("PRODUCT_016", "가격 설정이 올바르지 않습니다: %s", HttpStatus.BAD_REQUEST),

    /** 어드민 재고 조정의 기대 수량(CAS)이 현재 수량과 다르거나, 첫 재고 등록이 동시에 겹쳤다. 다시 읽고 조정한다. */
    STOCK_ADJUSTMENT_CONFLICT("PRODUCT_020", "재고가 그 사이 바뀌었습니다. 현재 재고를 다시 확인한 뒤 조정해 주세요.", HttpStatus.CONFLICT),
    /** 선점 수량만큼 재고가 없는 SKU 가 있다 — 재고 행이 없는 SKU(품절)도 여기다. 메시지에 부족한 SKU 아이디를 모두 싣는다. 선점 전체가 롤백된다. */
    STOCK_INSUFFICIENT("PRODUCT_021", "재고가 부족한 SKU 가 있습니다: %s", HttpStatus.CONFLICT),
    /** 없는 SKU · 없거나 숨긴 · 삭제된 상품 · 판매 종료(상품 또는 SKU). 메시지에 해당 SKU 아이디를 모두 싣는다. 판정은 {@code SaleAvailability#isOpenForSale} 이다. */
    STOCK_SKU_NOT_PURCHASABLE("PRODUCT_022", "주문할 수 없는 SKU 가 있습니다: %s", HttpStatus.CONFLICT),
    /** 같은 주문의 활성 선점이 이미 있는데 SKU · 수량이 다르다. 같으면 기존 선점을 그대로 돌려준다(멱등). */
    STOCK_RESERVATION_ITEMS_MISMATCH("PRODUCT_023", "이 주문에는 항목이 다른 재고 선점이 이미 있습니다.", HttpStatus.CONFLICT),
    /** 확정된 선점은 해제할 수 없다. 결제 후 취소 · 반품의 재고 복원은 별도 경로다. */
    STOCK_RESERVATION_CONFIRMED("PRODUCT_024", "이미 확정된 재고 선점입니다.", HttpStatus.CONFLICT),
    /** 해제 · 만료된 선점은 확정할 수 없다. 호출한 쪽이 새로 선점한다. */
    STOCK_RESERVATION_RELEASED("PRODUCT_025", "이미 해제된 재고 선점입니다. 재고를 다시 선점해 주세요.", HttpStatus.CONFLICT),
    /** 같은 선점을 동시에 바꿨거나(@Version) 같은 주문의 선점 생성이 동시에 겹쳤다. 다시 시도하면 바뀐 상태를 기준으로 답한다. */
    STOCK_RESERVATION_CONFLICT("PRODUCT_026", "재고 선점이 동시에 변경되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 결제 후 재고 복원은 확정(CONFIRMED)된 선점만 받는다. 결제 전(RESERVED)이거나 이미 해제(RELEASED)된 선점은 복원이 아니라 해제 경로다. */
    STOCK_RESTORATION_RESERVATION_NOT_CONFIRMED("PRODUCT_027", "확정된 재고 선점만 복원할 수 있습니다.", HttpStatus.CONFLICT),
    /**
     * 복원 수량이 (선점 수량 − 이미 복원한 수량)을 넘는 SKU 가 있다 — 선점에 없는 SKU 도 여기다. 메시지에 해당 SKU 아이디를 모두 싣는다.
     * 아무것도 복원하지 않는다.
     */
    STOCK_RESTORATION_QUANTITY_EXCEEDED("PRODUCT_028", "복원 가능 수량을 넘은 SKU 가 있습니다: %s", HttpStatus.CONFLICT),
    /** 같은 클레임의 복원이 이미 있는데 선점 · 사유 · SKU · 수량이 다르다. 같으면 기존 복원을 그대로 돌려준다(멱등). */
    STOCK_RESTORATION_MISMATCH("PRODUCT_029", "이 클레임에는 내용이 다른 재고 복원이 이미 있습니다.", HttpStatus.CONFLICT),
    /** 같은 클레임의 복원 생성이 동시에 겹쳤다. 다시 시도하면 먼저 끝난 복원을 기준으로 답한다. */
    STOCK_RESTORATION_CONFLICT("PRODUCT_030", "재고 복원이 동시에 요청되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /**
     * 선점 헤더 잠금을 대기 상한 안에 얻지 못했다 — 같은 선점의 다른 처리가 잠금을 오래 쥐고 있다. 아무것도 바꾸지 않았다.
     * {@code PRODUCT_026} · {@code PRODUCT_030} 과 같이 새 트랜잭션으로 다시 시도하는 오류다.
     */
    STOCK_RESERVATION_LOCK_TIMEOUT("PRODUCT_031", "같은 선점을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /**
     * 첨부할 업로드가 없다 — 키 형식이 틀렸거나, 이 어드민이 발급받은 키가 아니거나, 올리지 않았거나 만료돼 지워졌다(#9 F). 어느 경우인지 알리지 않는다 —
     * 남의 키가 있는지 떠보지 못하게.
     */
    IMAGE_CONFIGURATION_INVALID("PRODUCT_018", "이미지 유형 · 대표 개수 · 중복 업로드 키를 확인해 주세요.", HttpStatus.BAD_REQUEST),

    IMAGE_UPLOAD_NOT_FOUND("PRODUCT_032", "업로드한 이미지를 찾을 수 없습니다. 이미지를 다시 올려 주세요.", HttpStatus.BAD_REQUEST),
    /** JPEG · PNG · WebP 가 아니다 — 업로드 발급의 Content-Type, 또는 첨부 때 파일 앞부분(magic bytes)이 선언과 다르다. */
    IMAGE_FORMAT_NOT_ALLOWED("PRODUCT_033", "이미지는 JPEG · PNG · WebP 만 올릴 수 있습니다.", HttpStatus.BAD_REQUEST),
    /** 이미지가 비었거나 10MB 를 넘는다 — 업로드 발급의 크기 선언, 또는 첨부 때 실제 크기. */
    IMAGE_SIZE_NOT_ALLOWED("PRODUCT_034", "이미지는 10MB 이하로 올려 주세요.", HttpStatus.BAD_REQUEST),
    /** 스토리지가 응답하지 않거나 거절했다(연결 · 권한 · 설정). 서버 쪽 문제라 503 이다. 원인은 로그에만 남기고 메시지에 싣지 않는다. */
    IMAGE_STORAGE_UNAVAILABLE("PRODUCT_035", "이미지 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 스토리지 설정이 꺼져 있다({@code storage.enabled=false}, 닫힌 기본값). 이미지 없는 상품 등록은 된다. */
    IMAGE_UPLOAD_DISABLED("PRODUCT_036", "이미지 업로드가 아직 설정되지 않았습니다. 관리자에게 문의해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    /** 상위 아래에 두면 그 카테고리(하위 포함)의 깊이가 3(대·중·소)을 넘는다. 하위를 거느린 카테고리를 옮기면 하위도 같이 내려간다. */
    CATEGORY_DEPTH_EXCEEDED("PRODUCT_040", "카테고리는 3단계까지만 만들 수 있습니다.", HttpStatus.BAD_REQUEST),
    /** 자기 자신이나 자기 하위를 상위로 지정했다. */
    CATEGORY_CYCLE("PRODUCT_041", "자기 자신이나 하위 카테고리를 상위로 지정할 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 하위 카테고리가 있으면 지우지 않는다. 하위를 먼저 옮기거나 지운다. 메뉴에서만 감추려면 visible=false 다. */
    CATEGORY_HAS_CHILDREN("PRODUCT_042", "하위 카테고리가 있어 삭제할 수 없습니다.", HttpStatus.CONFLICT),
    /** 상품의 대표 또는 진열 카테고리로 연결돼 있으면 지우지 않는다 (자동 해제하지 않는다). 메뉴에서만 감추려면 visible=false 다. */
    CATEGORY_HAS_PRODUCTS("PRODUCT_043", "카테고리에 연결된 상품이 있어 삭제할 수 없습니다.", HttpStatus.CONFLICT),
    /** 본문의 상위 아이디(parentId)가 없는 카테고리다. 경로 리소스가 아니라 본문 참조라 404 가 아니라 400 이다. */
    CATEGORY_PARENT_NOT_FOUND("PRODUCT_044", "상위 카테고리가 존재하지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 다른 어드민의 카테고리 쓰기가 카테고리 잠금을 쥐고 있어 시간 상한(5초) 안에 끝나지 않았다. 요청끼리 겹친 것이라 503 이 아니다. */
    CATEGORY_BUSY("PRODUCT_045", "다른 카테고리 변경을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패의 폴백. */
    INVALID_REQUEST("PRODUCT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("PRODUCT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("PRODUCT_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
