package com.commercefoundry.domainlayer.productdraft.adapter.in.internal.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.controller.ProductDraftInternalController;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * internal 생성 입력의 초안 오류를 JSON 봉투로 고정한다 (선례: {@code SizeChartDraftTransportExceptionHandler}). 이미지 바이트 호출자가
 * {@code Accept: application/octet-stream} 만 보내도 형식 협상에 막혀 처리되지 않은 예외(컨테이너 500, 재시도 대상)로 바뀌지 않고 원래 상태 · 코드의 봉투가 나간다.
 * internal 컨트롤러에만 걸고, 어드민 {@code ProductDraftExceptionHandler}(도메인 전체)보다 앞에 선다.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 3)
@RestControllerAdvice(assignableTypes = ProductDraftInternalController.class)
public class ProductDraftInternalExceptionHandler {

    @ExceptionHandler(ProductDraftException.class)
    public ResponseEntity<Response<Void>> handle(ProductDraftException exception) {
        ProductDraftErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
            .body(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }
}
