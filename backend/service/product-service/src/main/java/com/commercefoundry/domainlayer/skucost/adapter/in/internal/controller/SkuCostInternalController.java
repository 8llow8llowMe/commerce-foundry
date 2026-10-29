package com.commercefoundry.domainlayer.skucost.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.request.SkuCostAtQueryRequest;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 시점 원가 일괄 조회 — (SKU, 시각) 쌍마다 그 시각에 적용되는 유형별 원가를 한 번에 낸다 ({@code api-design-guide.md} §2-12).
 * 쌍 목록이 본문이라 GET 쿼리 대신 {@code POST .../query} 다. 아무것도 바꾸지 않아 다시 보내도 같다(미래 시각의 결과와, 원가 미입력이던 유형은 이후 원가 추가로 바뀔 수 있다).
 *
 * <p>호출자는 operations-service 하나다(공헌이익 집계 #362). 원가는 고객에게 보이면 안 되는 값이라 다른 서비스 권한은 거절한다(닫힌 쪽).
 * 권한 · 검증 순서는 {@code SkuInternalController} 와 같다 — 서비스 토큰이 없거나 틀리면 internal 체인이 401({@code SECURITY_007}),
 * 인증을 통과한 뒤에는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/sku-costs")
public class SkuCostInternalController {

    private final SkuCostInternalUseCase skuCostInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @PostMapping("/query")
    public ResponseEntity<Response<SkuCostAtQueryInternalResponse>> getCostsAt(@RequestBody @Valid SkuCostAtQueryRequest request) {
        return ResponseEntity.ok().body(Response.success(skuCostInternalUseCase.getCostsAt(request.toCommand())));
    }
}
