package com.commercefoundry.domainlayer.display.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 메인 전시 도메인 오류({@link DisplayException})만 {@code Response.fail} 봉투로 바꾼다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> product-service 의 검증 예외(본문 · 파라미터 · 타입 불일치)는 {@code ProductExceptionHandler} 하나가
 * 서비스 공용으로 처리한다 — 검증 메시지가 코드({@code PRODUCT_140:...})를 싣고 오므로 컨텍스트를 몰라도 되고, 두 advice 가 같은 예외 타입을
 * 잡지 않으니 어느 쪽이 먼저 고르는지에 따라 응답이 바뀌지 않는다 ({@code coding-conventions.md} §8-1, 선례: auth {@code ShippingAddressExceptionHandler}).
 *
 * <p>잡는 타입이 겹치지 않지만 서비스에 advice 가 둘이라 좁은 이쪽에 {@code @Order} 를 달아 순서를 확정한다.
 * security-core 의 {@code SecurityExceptionAdvice}(가장 높은 우선순위)보다는 뒤다.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class DisplayExceptionHandler {

    @ExceptionHandler(DisplayException.class)
    public ResponseEntity<Response<Void>> handleDisplayException(DisplayException exception) {
        DisplayErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
