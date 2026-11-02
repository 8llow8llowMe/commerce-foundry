package com.commercefoundry.domainlayer.shipment.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 송장(배송) 컨텍스트 오류 코드 ({@code coding-conventions.md} §8). 업무 코드는 컨텍스트별 enum 이 {@code DELIVERY_} 번호를 나눠 쓴다 — 001~013 은 배송비
 * ({@code DeliveryErrorCode}), 014~ 는 이 enum 이다. 컨텍스트 간 예외 타입은 공유하지 않는다({@code architecture-guide.md} H8). 필드별 검증 코드(140~)는
 * {@link ShipmentValidationMessage} 에 둔다. 번호가 겹치지 않는지는 {@code DeliveryCodeUniquenessTest} 가 본다.
 *
 * <p>송장 일괄 등록(#18)은 건별 결과({@code results[].errorCode})로 이 코드를 싣는다 — HTTP 상태는 건별 결과에 나가지 않지만 단건 API 가 생기면 그대로 쓴다.
 * order 응답 번역(014~018)의 정본은 {@code order-flow-guide.md} §4 실패 표다.
 */
@Getter
@RequiredArgsConstructor
public enum ShipmentErrorCode {

    /** order 404 {@code ORDER_001}. 등록이면 대기 송장 행을 지웠고, 배송 완료면 송장을 직전 상태로 되돌렸다. */
    ORDER_NOT_FOUND("DELIVERY_014", "존재하지 않는 주문입니다.", HttpStatus.NOT_FOUND),
    /** order 409 {@code ORDER_020} — 출고 전 · 취소 요청 · 취소 등. {@code %s} 는 order 가 알려 준 현재 상태 이름이고, 읽지 못하면 "현재 주문" 이다. 대기 송장 행을 지웠다. */
    ORDER_NOT_SHIPPABLE("DELIVERY_015", "%s 상태라 출고할 수 없습니다.", HttpStatus.CONFLICT),
    /**
     * order 400 {@code ORDER_159} · {@code 160} · {@code 161} — 보낸 출고 시각이 order 의 허용 범위(지금 − 30일 ~ 지금 + 5분) 밖이다. 출고 시각은 처음 등록 시각(서버 시계)이라
     * 서비스 간 시계가 어긋났거나, 결과 모름으로 남은 송장을 30일이 지나 재시도했다는 뜻이다(재시도는 처음 출고 시각을 다시 보낸다). 대기 송장 행을 지웠다 —
     * 다시 보내면 새 송장(그때 시각)으로 등록된다. 시계 차이면 같은 결과다.
     */
    ORDER_SHIPPED_AT_REJECTED("DELIVERY_016", "출고 시각을 주문에 반영하지 못했습니다. 관리자에게 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** order 409 {@code ORDER_025} — 읽은 뒤 고객 취소 · 어드민 처리가 먼저 커밋했다. 송장 행을 남긴다 — 같은 요청을 다시 보내면 지금 주문 상태로 판정받는다. */
    ORDER_CONCURRENTLY_CHANGED("DELIVERY_017", "다른 곳에서 주문이 먼저 바뀌었습니다. 같은 송장으로 다시 등록해 주세요.", HttpStatus.CONFLICT),
    /** 타임아웃 · 5xx · 서킷 오픈 · 모르는 응답 — 주문에 반영됐는지 모른다. 송장 행을 남긴다 — 같은 요청 재시도가 order 멱등으로 수렴한다. */
    ORDER_SERVICE_UNAVAILABLE("DELIVERY_018", "주문에 출고를 반영하지 못했습니다. 잠시 후 같은 송장으로 다시 등록해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 이미 다른 택배사 · 송장 번호로 주문에 반영된 송장이 있다. 반영된 송장은 지우거나 바꾸지 않는다(§4 "주문당 송장 1건"). */
    SHIPMENT_ALREADY_SHIPPED("DELIVERY_019", "이미 다른 송장으로 출고된 주문입니다.", HttpStatus.CONFLICT),
    /**
     * 같은 주문의 송장 등록이 동시에 겹쳤다(유일 키 경합 · 버전 충돌). 아무것도 바꾸지 않았다. order 200 뒤 반영 완료 저장의 충돌은 지금 행에 맞춰 수렴하고,
     * 맞추기도 두 번 겹쳤을 때만 이 코드다.
     */
    SHIPMENT_CONCURRENTLY_REGISTERED("DELIVERY_020", "같은 주문의 송장이 동시에 등록되고 있습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 예상하지 못한 저장 오류(DB 장애 등) — 건별 결과에 쓴다. 원인 메시지는 싣지 않는다. */
    SHIPMENT_REGISTER_FAILED("DELIVERY_021", "송장을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 일괄 등록 요청이 처리 마감(요청 시작 + 8초, 게이트웨이 응답 제한 10초 안)을 넘겨 이 건은 시작하지 않았다. 송장 행을 만들지 않았고 order 를 부르지 않았다 —
     * 다시 보내면 처음 등록으로 처리된다. {@code DELIVERY_018}(행을 남긴 결과 모름)과 나눈다.
     */
    SHIPMENT_REGISTER_DEADLINE_EXCEEDED("DELIVERY_022", "시간 안에 처리하지 못했습니다. 다시 보내 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 배송 상태 변경 · 조회 (#18 PR-B2). 배송 완료의 order 응답 번역(027~030)의 정본은 order-flow-guide.md §4 "배송 완료" 실패 표다.
    // order 404 ORDER_001 은 등록과 같은 DELIVERY_014 다(메시지가 같다)

    /** 송장 아이디로 찾지 못했다 — 어드민 상세 · 상태 변경. */
    SHIPMENT_NOT_FOUND("DELIVERY_023", "존재하지 않는 배송입니다.", HttpStatus.NOT_FOUND),
    /** 허용 전이(출고 완료 → 배송 중 · 배송 완료, 배송 중 → 배송 완료) 밖이다. {@code %s} 는 지금 · 바꿀 상태 이름이다. */
    SHIPMENT_STATUS_TRANSITION_NOT_ALLOWED("DELIVERY_024", "%s 상태에서 %s 상태로 바꿀 수 없습니다.", HttpStatus.CONFLICT),
    /** 출고가 주문에 반영되지 않은 송장(등록 결과 모름 · 버전 충돌로 남은 송장)은 배송 중 · 배송 완료로 바꾸지 않는다 — 먼저 같은 등록을 다시 보내 출고를 반영한다. */
    SHIPMENT_NOT_SYNCED("DELIVERY_025", "주문에 출고가 반영되지 않은 송장입니다. 같은 송장으로 등록을 다시 보내 출고부터 반영해 주세요.", HttpStatus.CONFLICT),
    /** 읽은 뒤 다른 요청이 같은 송장을 먼저 바꿨다(버전 충돌). 아무것도 바꾸지 않았다. */
    SHIPMENT_CONCURRENTLY_CHANGED("DELIVERY_026", "다른 곳에서 배송 상태가 먼저 바뀌었습니다. 새로고침한 뒤 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** order 409 {@code ORDER_020} — 배송 완료로 바꿀 수 없는 주문 상태(취소 요청 · 취소 등). {@code %s} 는 order 가 알려 준 현재 상태 이름이다. 송장은 직전 상태로 되돌렸다. */
    ORDER_NOT_DELIVERABLE("DELIVERY_027", "%s 상태라 배송 완료로 바꿀 수 없습니다.", HttpStatus.CONFLICT),
    /**
     * order 400 {@code ORDER_159} · {@code 160} · {@code 161} — 보낸 배송 완료 시각이 order 의 허용 범위(지금 − 30일 ~ 지금 + 5분) 밖이거나 출고 시각보다 앞선다.
     * 서비스 간 시계 차이, 또는 결과 모름으로 남은 배송 완료를 30일이 지나 재시도했다는 뜻이다. 송장은 직전 상태로 되돌렸다 — 다시 누르면 그때 시각으로 보낸다.
     */
    ORDER_DELIVERED_AT_REJECTED("DELIVERY_028", "배송 완료 시각을 주문에 반영하지 못했습니다. 관리자에게 문의해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** order 409 {@code ORDER_025} — 읽은 뒤 다른 처리가 주문을 먼저 바꿨다. 배송 완료 반영 대기로 남는다 — 같은 변경을 다시 보내면 지금 주문 상태로 판정받는다. */
    ORDER_CONCURRENTLY_CHANGED_ON_COMPLETION("DELIVERY_029", "다른 곳에서 주문이 먼저 바뀌었습니다. 배송 완료를 다시 눌러 주세요.", HttpStatus.CONFLICT),
    /** 타임아웃 · 5xx · 서킷 오픈 · 모르는 응답 — 주문에 배송 완료가 반영됐는지 모른다. 반영 대기로 남는다 — 같은 변경 재시도가 order 멱등으로 수렴한다. */
    ORDER_SERVICE_UNAVAILABLE_ON_COMPLETION("DELIVERY_030", "주문에 배송 완료를 반영하지 못했습니다. 잠시 후 배송 완료를 다시 눌러 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 예상하지 못한 저장 오류(DB 장애 등) — 배송 상태 변경. 원인 메시지는 싣지 않는다. */
    SHIPMENT_STATUS_CHANGE_FAILED("DELIVERY_031", "배송 상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
