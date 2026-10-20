package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 배송지 도메인 오류({@link ShippingAddressException})만 {@code Response.fail} 봉투로 바꾼다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> auth-service 의 검증 예외(본문 · 파라미터 · 타입 불일치)는 {@code MemberExceptionHandler} 하나가
 * 서비스 공용으로 처리한다 — 검증 메시지가 코드({@code AUTH_120:...})를 싣고 오므로 컨텍스트를 몰라도 되고, 두 advice 가 같은 예외 타입을
 * 잡지 않으니 어느 쪽이 먼저 고르는지에 따라 응답이 바뀌지 않는다.
 *
 * <p>잡는 타입이 겹치지 않지만 서비스에 advice 가 둘이라 좁은 이쪽에 {@code @Order} 를 달아 순서를 확정한다 ({@code coding-conventions.md} §8-1).
 * security-core 의 {@code SecurityExceptionAdvice}(가장 높은 우선순위)보다는 뒤다.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class ShippingAddressExceptionHandler {

    @ExceptionHandler(ShippingAddressException.class)
    public ResponseEntity<Response<Void>> handleShippingAddressException(ShippingAddressException exception) {
        ShippingAddressErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
