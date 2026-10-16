package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 SKU 벌크 조회 — 장바구니 · 주문 생성이 SKU 마다 따로 부르지 않게 한 번에 받는다 ({@code coding-conventions.md} §9-6).
 *
 * <p>호출자는 order-service 하나다. 권한은 서비스 간 인증(#51)의 {@code SERVICE_<spring.application.name>} 이고, 허용 호출자가 아니면 거절한다
 * (닫힌 쪽). 서비스 토큰이 없는 요청은 #51 이후 internal 보안 체인이 이 컨트롤러에 닿기 전에 먼저 막는다.
 *
 * <p>검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 * 서비스 토큰 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize}(허용 호출자)보다 먼저 돈다 — 허용되지 않은 호출자도 형식이 틀리면 400 이다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/skus")
public class SkuInternalController {

    /** 한 번에 받는 아이디 상한 — 중복을 포함한 원문 개수로 센다. 장바구니 · 주문 항목 상한보다 넉넉하다. */
    private static final int MAX_IDS = 100;

    private final SkuInternalUseCase skuInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @GetMapping
    public ResponseEntity<Response<SkuInternalResponse>> getSkus(
        @RequestParam
        @NotEmpty(message = ProductValidationMessage.IDS_REQUIRED)
        @Size(max = MAX_IDS, message = ProductValidationMessage.IDS_SIZE_MAX)
        List<@NotNull(message = ProductValidationMessage.ID_POSITIVE) @Positive(message = ProductValidationMessage.ID_POSITIVE) Long> ids
    ) {
        return ResponseEntity.ok().body(Response.success(skuInternalUseCase.getSkus(ids)));
    }
}
