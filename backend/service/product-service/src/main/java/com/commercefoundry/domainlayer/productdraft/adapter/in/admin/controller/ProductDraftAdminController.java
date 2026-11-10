package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.request.ProductDraftCreateRequest;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftValidationMessage;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 AI 상품 초안 (#359). {@code /api/v1/admin/**} 은 URL 규칙이 운영자 역할을 먼저 요구하고, 메서드의 {@code AdminAuthority.CATALOG_*} 이 기능 권한으로 다시 막는다
 * ({@code security-guide.md} §5-1). 만든 · 거절한 어드민은 access 토큰의 memberId 로만 기록한다 — 본문으로 받지 않는다. 초안은 상품 조회 권한이 있는 운영자 모두가 본다.
 * 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] AI 상품 초안", description = "사진과 운영자 입력으로 상품 문구 초안을 만들고, 검토한 값만 상품 등록에 적용한다")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/product-drafts")
public class ProductDraftAdminController {

    private final ProductDraftAdminUseCase productDraftAdminUseCase;

    @Operation(
        summary = "초안 만들기",
        description = "운영자가 아는 사실과 근거 사진을 저장하고 생성 대기(REQUESTED) 초안을 돌려준다(201). 상품을 만들거나 바꾸지 않는다. "
            + "이미지는 스토어 미디어 저장소의 공개 주소만 받는다 — 밖이면 400(PRODUCT_DRAFT_030), 저장소 미설정은 503(PRODUCT_DRAFT_032), "
            + "대표 이미지 두 장 이상은 400(PRODUCT_DRAFT_034), 같은 주소 두 번은 400(PRODUCT_DRAFT_035)이다. 이미지 유형은 THUMBNAIL · GALLERY 만 받는다(영상 등은 400 PRODUCT_100)."
    )
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "PRODUCT_DRAFT_CREATE")
    @PostMapping
    public ResponseEntity<Response<ProductDraftAdminResponse>> create(
        @AuthenticationPrincipal MemberPrincipal member,
        @RequestBody @Valid ProductDraftCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(productDraftAdminUseCase.create(request.toCommand(member.memberId()))));
    }

    @Operation(summary = "초안 조회", description = "없는 초안은 404(PRODUCT_DRAFT_001)다. 보관 기간이 지난 진행 중 초안은 EXPIRED 로 보인다.")
    @PreAuthorize(AdminAuthority.CATALOG_READ)
    @GetMapping("/{draftId}")
    public ResponseEntity<Response<ProductDraftAdminResponse>> getDraft(
        @Parameter(description = "초안 아이디") @PathVariable @Positive(message = ProductDraftValidationMessage.DRAFT_ID_POSITIVE) long draftId
    ) {
        return ResponseEntity.ok().body(Response.success(productDraftAdminUseCase.getDraft(draftId)));
    }

    @Operation(
        summary = "초안 거절",
        description = "이 초안을 쓰지 않기로 한다(REJECTED). 이미 거절한 초안이면 그대로 200 이다. 적용한 초안은 409(PRODUCT_DRAFT_020), "
            + "보관 기간이 지났으면 409(PRODUCT_DRAFT_022), 다른 변경과 겹치면 409(PRODUCT_DRAFT_025)다. 없는 초안은 404(PRODUCT_DRAFT_001)다."
    )
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @AdminAudited(action = "PRODUCT_DRAFT_REJECT")
    @PostMapping("/{draftId}/reject")
    public ResponseEntity<Response<ProductDraftAdminResponse>> reject(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "초안 아이디") @PathVariable @Positive(message = ProductDraftValidationMessage.DRAFT_ID_POSITIVE) long draftId
    ) {
        return ResponseEntity.ok().body(Response.success(productDraftAdminUseCase.reject(draftId, member.memberId())));
    }
}
