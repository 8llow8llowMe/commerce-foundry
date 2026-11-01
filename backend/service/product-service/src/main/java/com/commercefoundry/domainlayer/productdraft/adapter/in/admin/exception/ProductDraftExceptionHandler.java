package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 상품 초안 도메인 오류({@link ProductDraftException})만 {@code Response.fail} 봉투로 바꾼다. 진입이 어드민뿐이라 {@code adapter/in/admin} 아래에 있다.
 *
 * <p><b>요청 검증 오류는 여기서 잡지 않는다.</b> product-service 의 검증 예외는 {@code ProductExceptionHandler} 하나가 서비스 공용으로 처리한다 —
 * 검증 메시지가 코드({@code PRODUCT_DRAFT_101:...})를 싣고 오므로 접두어가 달라도 그대로 나간다.
 * 잡는 타입이 겹치지 않지만 서비스에 advice 가 여럿이라 좁은 이쪽에 {@code @Order} 를 단다 (선례: {@code SkuCostExceptionHandler}).
 */
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer")
public class ProductDraftExceptionHandler {

    @ExceptionHandler(ProductDraftException.class)
    public ResponseEntity<Response<Void>> handleProductDraftException(ProductDraftException exception) {
        ProductDraftErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }
}
