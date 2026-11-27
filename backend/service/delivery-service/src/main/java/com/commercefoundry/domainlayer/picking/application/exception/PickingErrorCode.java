package com.commercefoundry.domainlayer.picking.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 출고 묶음(피킹 · 포장 검수) 컨텍스트 오류 코드 ({@code coding-conventions.md} §8). delivery-service 의 {@code DELIVERY_} 번호를 컨텍스트별로 나눠 쓴다 —
 * 001~013 배송비, 014~031 송장, 032~ 이 enum 이다. 필드별 검증 코드(150~)는 {@link PickingValidationMessage} 에 둔다.
 * 번호가 겹치지 않는지는 {@code DeliveryCodeUniquenessTest} 가 본다.
 *
 * <p>문구는 비개발자 운영자가 읽는다 — 출고 묶음 · 상품 옵션 · 상품 준비 중 같은 업무 말로 쓰고, 원격 오류 원문을 싣지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum PickingErrorCode {

    /** 요청 주문 목록이 비었거나 20건을 넘거나 같은 주문 · 0 이하 번호가 있다(HTTP 검증을 지나 들어온 경우의 방어선). 원격 조회 전에 거절한다. */
    INVALID_BATCH("DELIVERY_032", "출고 묶음에는 서로 다른 주문을 1건 이상 20건 이하로 담아 주세요.", HttpStatus.BAD_REQUEST),
    /** 요청한 주문이 모두 제외됐다 — 묶음을 만들지 않았다. 주문별 사유는 묶음이 없으니 싣지 않는다. */
    NO_ELIGIBLE_ORDER(
        "DELIVERY_033", "출고 묶음에 담을 수 있는 주문이 없습니다. 상품 준비 중인 국내 배송 주문만 담을 수 있고, 제작 상품이 있는 주문은 담을 수 없습니다.",
        HttpStatus.CONFLICT),
    /** 출고 묶음 아이디로 찾지 못했다. */
    BATCH_NOT_FOUND("DELIVERY_034", "존재하지 않는 출고 묶음입니다.", HttpStatus.NOT_FOUND),
    /** 검수 수량이 비었거나 상품 옵션 · 수량이 빠졌다(HTTP 검증을 지나 들어온 경우의 방어선). */
    INVALID_CHECK("DELIVERY_035", "검수할 상품 옵션과 수량을 확인해 주세요.", HttpStatus.BAD_REQUEST),
    /** order 조회가 타임아웃 · 5xx · 서킷 열림 · 계약과 다른 응답이다. 묶음을 만들지 않았다 — 같은 요청을 다시 보내면 된다. */
    SNAPSHOT_UNAVAILABLE("DELIVERY_036", "주문 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 같은 출고 묶음의 검수가 동시에 겹쳐 이 요청은 저장하지 않았다(버전 충돌). 새로고침한 뒤 다시 보내면 같은 수량은 이전 결과를 그대로 쓴다. */
    CONCURRENTLY_CHANGED("DELIVERY_037", "다른 곳에서 같은 출고 묶음을 먼저 검수했습니다. 새로고침한 뒤 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 같은 주문을 두 번 보내거나 묶음의 대상 주문 수를 넘으면 쓰기 전에 요청 전체를 거절한다. */
    INVALID_BULK_CHECK("DELIVERY_038", "서로 다른 주문을 출고 묶음의 대상 주문 수 이하로 보내 주세요.", HttpStatus.BAD_REQUEST),
    BARCODE_LOOKUP_UNAVAILABLE("DELIVERY_039", "상품 바코드를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
