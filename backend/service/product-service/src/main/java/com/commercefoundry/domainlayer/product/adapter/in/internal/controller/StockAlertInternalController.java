package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockAlertScanInternalResponse;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.StockAlertInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/stocks")
public class StockAlertInternalController {
    private final StockAlertInternalUseCase useCase;

    // 클래스 @Validated 없이 MVC 메서드 검증을 사용한다. 인증된 요청은 검증 후 호출자 권한을 확인한다.
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/low")
    public ResponseEntity<Response<StockAlertScanInternalResponse>> getLowStocks(
        @RequestParam(defaultValue = "0") @PositiveOrZero(message = StockAlertValidationMessage.AFTER_SKU_ID_NON_NEGATIVE) long afterSkuId,
        @RequestParam(defaultValue = "100")
        @Min(value = 1, message = StockAlertValidationMessage.SIZE_RANGE) @Max(value = 100, message = StockAlertValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(useCase.getLowStocks(afterSkuId, size)));
    }
}
