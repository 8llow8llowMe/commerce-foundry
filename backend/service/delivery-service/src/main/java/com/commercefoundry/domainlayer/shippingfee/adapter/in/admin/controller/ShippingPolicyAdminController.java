package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request.ShippingPolicyReplaceRequest;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 국내 배송비 정책. 해외 배송비 정책(#22)은 {@code InternationalShippingPolicyAdminController}({@code /api/v1/admin/shipping-policies/international})가 나란히 둔다.
 *
 * <p>본문 검증은 {@code @Valid @RequestBody} 가 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 배송비 정책", description = "국내 기본 배송비 · 무료배송 기준 · 도서산간 구간, 해외 기본료 · 국가 그룹별 무게 요금 관리")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/shipping-policies/domestic")
public class ShippingPolicyAdminController {

    private final ShippingPolicyAdminUseCase shippingPolicyAdminUseCase;

    @Operation(
        summary = "국내 배송비 정책 조회",
        description = "정책이 아직 설정되지 않았으면 dataBody 가 null 이다 — 교체(PUT)를 version 없이 보내 처음 만든다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<ShippingPolicyAdminResponse>> getDomesticPolicy() {
        return ResponseEntity.ok().body(Response.success(shippingPolicyAdminUseCase.getDomesticPolicy()));
    }

    @Operation(
        summary = "국내 배송비 정책 전체 교체",
        description = "기본 배송비 · 무료배송 기준 · 도서산간 구간을 통째로 바꾼다(구간은 모두 지우고 새로 넣는다 — 구간 아이디가 새로 발급된다). "
            + "조회한 version 을 그대로 보내고, 그 사이 다른 곳에서 바꿨으면 409(DELIVERY_006)로 아무것도 바꾸지 않는다. 정책이 없을 때는 version 을 비워 처음 만든다. "
            + "금액의 통화가 섞이면 400(DELIVERY_003), 구간의 시작이 끝보다 크면 400(DELIVERY_004), 구간이 겹치면 400(DELIVERY_005)이다. 맞닿은 구간은 겹치지 않는다. "
            + "저장된 정책과 통화가 다르면 409(DELIVERY_012)다 — 정책 통화는 처음 만들 때 정한다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<Response<ShippingPolicyAdminResponse>> replaceDomesticPolicy(@Valid @RequestBody ShippingPolicyReplaceRequest request) {
        return ResponseEntity.ok().body(Response.success(shippingPolicyAdminUseCase.replaceDomesticPolicy(request.toCommand())));
    }
}
