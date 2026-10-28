package com.commercefoundry.domainlayer.look.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 룩 도메인 오류({@link LookException})만 {@code Response.fail} 봉투로 바꾼다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> product-service 의 검증 예외는 {@code ProductExceptionHandler} 하나가 서비스 공용으로 처리한다 —
 * 검증 메시지가 코드({@code LOOK_101:...})를 싣고 오므로 접두어가 달라도 그대로 나간다.
 * 잡는 타입이 겹치지 않지만 서비스에 advice 가 여럿이라 좁은 이쪽에 {@code @Order} 를 단다 (선례: {@code DisplayExceptionHandler} · {@code SizeChartExceptionHandler}).
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class LookExceptionHandler {

    @ExceptionHandler(LookException.class)
    public ResponseEntity<Response<Void>> handleLookException(LookException exception) {
        LookErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
