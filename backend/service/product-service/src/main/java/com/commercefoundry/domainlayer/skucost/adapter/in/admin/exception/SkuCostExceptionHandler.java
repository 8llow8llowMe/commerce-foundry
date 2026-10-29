package com.commercefoundry.domainlayer.skucost.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SKU 원가 도메인 오류({@link SkuCostException})만 {@code Response.fail} 봉투로 바꾼다. 원가 컨텍스트는 어드민 진입만 있어 {@code adapter/in/admin} 아래에 둔다.
 *
 * <p><b>요청 검증 오류와 없는 SKU(PRODUCT_002)는 여기서 잡지 않는다.</b> product-service 의 검증 예외와 {@code ProductException} 은
 * {@code ProductExceptionHandler} 하나가 서비스 공용으로 처리한다. 잡는 타입이 겹치지 않지만 서비스에 advice 가 여럿이라 좁은 이쪽에 {@code @Order} 를 단다
 * (선례: {@code SizeChartExceptionHandler}).
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class SkuCostExceptionHandler {

    @ExceptionHandler(SkuCostException.class)
    public ResponseEntity<Response<Void>> handleSkuCostException(SkuCostException exception) {
        SkuCostErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
