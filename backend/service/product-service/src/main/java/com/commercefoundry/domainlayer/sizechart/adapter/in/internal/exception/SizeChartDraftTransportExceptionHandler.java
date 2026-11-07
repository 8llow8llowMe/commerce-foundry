package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 2)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.sizechart")
public class SizeChartDraftTransportExceptionHandler {
    @ExceptionHandler(SizeChartDraftTransportException.class)
    public ResponseEntity<Response<Void>> handle(SizeChartDraftTransportException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore())
            .body(Response.fail(code.getCode(), code.getMessage()));
    }
}
