package com.commercefoundry.domainlayer.sizechart.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 사이즈표 도메인 오류({@link SizeChartException})만 {@code Response.fail} 봉투로 바꾼다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> product-service 의 검증 예외는 {@code ProductExceptionHandler} 하나가 서비스 공용으로 처리한다.
 * 잡는 타입이 겹치지 않지만 서비스에 advice 가 여럿이라 좁은 이쪽에 {@code @Order} 를 단다 (선례: {@code DisplayExceptionHandler}).
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class SizeChartExceptionHandler {

    @ExceptionHandler(SizeChartException.class)
    public ResponseEntity<Response<Void>> handleSizeChartException(SizeChartException exception) {
        SizeChartErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
