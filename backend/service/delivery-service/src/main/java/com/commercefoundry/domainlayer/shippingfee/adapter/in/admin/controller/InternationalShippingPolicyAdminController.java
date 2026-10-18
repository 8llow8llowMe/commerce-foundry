package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.request.InternationalShippingPolicyReplaceRequest;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.InternationalShippingPolicyAdminUseCase;
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
 * 어드민 해외 배송비 정책. 국내({@code /domestic})와 같은 전체 교체 PUT + version 규칙이다 ({@code api-design-guide.md} §6-1).
 * 기능 플래그({@code store.features.international-shipping})와 무관하다 — 켜기 전에 미리 준비할 수 있게.
 *
 * <p>본문 검증은 {@code @Valid @RequestBody} 가 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 배송비 정책", description = "국내 기본 배송비 · 무료배송 기준 · 도서산간 구간, 해외 기본료 · 국가 그룹별 무게 요금 관리")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/shipping-policies/international")
public class InternationalShippingPolicyAdminController {

    private final InternationalShippingPolicyAdminUseCase internationalShippingPolicyAdminUseCase;

    @Operation(
        summary = "해외 배송비 정책 조회",
        description = "정책이 아직 설정되지 않았으면 dataBody 가 null 이다 — 교체(PUT)를 version 없이 보내 처음 만든다. 해외 배송 기능이 꺼진 스토어에서도 조회 · 교체할 수 있다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<InternationalShippingPolicyAdminResponse>> getInternationalPolicy() {
        return ResponseEntity.ok().body(Response.success(internationalShippingPolicyAdminUseCase.getInternationalPolicy()));
    }

    @Operation(
        summary = "해외 배송비 정책 전체 교체",
        description = "기본료 · 무료배송 기준 · 관세 안내 문구 · 국가 그룹(국가 · 무게 구간)을 통째로 바꾼다(하위 행은 모두 지우고 새로 넣는다 — 아이디가 새로 발급된다). "
            + "조회한 version 을 그대로 보내고, 그 사이 다른 곳에서 바꿨으면 409(DELIVERY_006)로 아무것도 바꾸지 않는다. 정책이 없을 때는 version 을 비워 처음 만든다. "
            + "금액의 통화가 섞이면 400(DELIVERY_003), 저장된 정책과 통화가 다르면 409(DELIVERY_012), 국가가 두 그룹에 들면 400(DELIVERY_007), "
            + "한 그룹의 구간 상한이 겹치면 400(DELIVERY_008), KR 을 넣으면 400(DELIVERY_009), 그룹 이름이 겹치면 400(DELIVERY_010), "
            + "remoteAreas(도서산간)를 보내면 400(DELIVERY_136)이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ResponseEntity<Response<InternationalShippingPolicyAdminResponse>> replaceInternationalPolicy(
        @Valid @RequestBody InternationalShippingPolicyReplaceRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(request.toCommand())));
    }
}
