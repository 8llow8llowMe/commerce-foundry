package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 도메인 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link ProductValidationMessage} 에 둔다.
 *
 * <p>대역 — {@code 00x} 없는 리소스(404), {@code 02x} · {@code 03x} 재고(조정 · 선점 · 복원 충돌과 부족, 409), {@code 04x} 어드민 카테고리 관리,
 * {@code 1xx} 요청 검증(400).
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
