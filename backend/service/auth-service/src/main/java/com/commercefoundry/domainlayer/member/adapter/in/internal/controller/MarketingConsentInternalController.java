package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.request.MarketingConsentQueryInternalRequest;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MarketingConsentInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** operations가 발송 직전에 확인할 동의 상태 조회. 조회 후 철회와 발송을 원자적으로 묶는 허가는 아니다 (#230). */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/marketing-consents")
public class MarketingConsentInternalController {

    private final MarketingConsentInternalUseCase marketingConsentInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @PostMapping("/query")
    public ResponseEntity<Response<MarketingConsentInternalResponse>> query(@Valid @RequestBody MarketingConsentQueryInternalRequest request) {
        return ResponseEntity.ok(Response.success(marketingConsentInternalUseCase.query(request.memberIds())));
    }
}
