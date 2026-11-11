package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE - 2)
@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.sizechart")
public class SizeChartDraftTransportExceptionHandler {
    /**
     * Content-Type 을 JSON 으로 고정한다. 이미지 바이트 읽기 호출자가 {@code Accept: application/octet-stream} 만 보내도
     * 형식 협상에 막혀 처리되지 않은 예외(컨테이너 500, 재시도 대상)로 바뀌지 않고 원래 상태 · 코드의 봉투가 나간다.
     */
    @ExceptionHandler(SizeChartDraftTransportException.class)
    public ResponseEntity<Response<Void>> handle(SizeChartDraftTransportException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
            .body(Response.fail(code.getCode(), code.getMessage()));
    }
}
