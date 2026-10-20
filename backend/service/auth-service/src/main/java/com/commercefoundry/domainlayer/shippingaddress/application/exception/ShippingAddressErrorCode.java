package com.commercefoundry.domainlayer.shippingaddress.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 배송지 오류 코드 ({@code coding-conventions.md} §8). 접두어는 서비스 단위 {@code AUTH_} 이고 배송지는 020~029 를 쓴다
 * (001~009 회원, 010~ 토큰). 필드별 검증 코드(120~)는 {@link ShippingAddressValidationMessage} 에 둔다.
 *
 * <p>요청 검증 실패(AUTH_100 · 120~ · 198 · 199)는 이 enum 이 아니라 서비스 공용 검증 처리({@code MemberExceptionHandler})가 봉투로 바꾼다.
 */
@Getter
@RequiredArgsConstructor
public enum ShippingAddressErrorCode {

    /** 없거나 다른 회원의 주소다. 둘을 구분하지 않는다 — 남의 주소가 있는지 드러내지 않는다 ({@code api-design-guide.md} §2-2). */
    NOT_FOUND_SHIPPING_ADDRESS("AUTH_020", "존재하지 않는 배송지입니다.", HttpStatus.NOT_FOUND),
    /** 회원당 상한(auth.shipping-address.max-per-member)에 닿았다. 요청 값은 올바르고 주소록 상태가 막으므로 409 다 — 하나를 지우면 다시 된다. */
    SHIPPING_ADDRESS_LIMIT_EXCEEDED("AUTH_021", "배송지는 최대 %s개까지 저장할 수 있습니다.", HttpStatus.CONFLICT),
    /** 같은 주소를 다른 요청이 먼저 고쳤다(낙관적 락), 또는 기본 배송지 유니크 키에 걸렸다. 다시 조회한 뒤 보낸다. */
    SHIPPING_ADDRESS_UPDATE_CONFLICT("AUTH_022", "배송지가 다른 요청으로 먼저 변경되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /**
     * 같은 회원의 다른 배송지 쓰기(추가 · 기본 지정 · 삭제)가 회원 잠금을 쥐고 있어 기다리다 트랜잭션 시간 상한을 넘겼다.
     * 서비스 전체가 아니라 그 회원의 요청끼리 겹친 것이라 503 이 아니라 409 다 — 잠시 뒤 다시 보내면 된다.
     */
    SHIPPING_ADDRESS_BUSY("AUTH_023", "같은 회원의 다른 배송지 변경이 처리 중입니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT),

    /**
     * 요청 검증을 지나왔는데 도메인 규칙에 걸렸다 — 검증과 도메인 규칙이 갈라진 경우다(호출 코드의 오류). 500 대신 400 으로 답한다.
     * 코드는 auth-service 공용 폴백 {@code AUTH_100}(MemberErrorCode.INVALID_REQUEST 와 같은 값)이다. 어느 칸인지는 밝히지 않는다(값이 개인정보).
     */
    INVALID_SHIPPING_ADDRESS("AUTH_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
