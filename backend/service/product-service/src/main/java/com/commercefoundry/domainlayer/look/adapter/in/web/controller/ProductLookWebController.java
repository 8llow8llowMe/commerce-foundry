package com.commercefoundry.domainlayer.look.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 상세의 "이 상품이 든 룩". 경로는 상품 하위 리소스({@code /api/v1/products/{productId}/looks})라 게이트웨이는 기존 {@code products/**} 로 보낸다.
 * 상품 경로 변수는 상품 상세 · 사이즈표와 같이 양수 검증을 두지 않는다 — 0 · 음수는 없는 상품이라 404 {@code PRODUCT_001} 이다. 캐시는 {@link LookWebController} 와 같다.
 */
@Tag(name = "룩", description = "스토어프론트 룩(Shop the Look) — 대표 이미지와 구성 상품 카드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{productId}/looks")
public class ProductLookWebController {

    private final LookWebUseCase lookWebUseCase;

    @Operation(
        summary = "상품이 든 룩 조회",
        description = "상품이 구성 상품으로 든 보이는 룩을 노출 순서대로 모두 낸다. 없거나 숨긴 상품은 404(PRODUCT_001)이고, 룩이 없거나 룩 기능이 꺼져 있으면 빈 배열이다. "
            + "로그인 없이 호출할 수 있고, 60초 동안 캐시해도 된다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<ProductLookListResponse>> getLooksOfProduct(@Parameter(description = "상품 아이디") @PathVariable long productId) {
        return ResponseEntity.ok().cacheControl(LookWebController.cacheControl()).body(Response.success(lookWebUseCase.getLooksOfProduct(productId)));
    }
}
