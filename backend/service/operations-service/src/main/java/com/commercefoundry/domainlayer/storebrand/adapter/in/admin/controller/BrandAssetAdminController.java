package com.commercefoundry.domainlayer.storebrand.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.request.BrandAssetAdminRequests;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.support.BrandUploadBody;
import com.commercefoundry.domainlayer.storebrand.application.port.in.BrandAssetAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 스토어 브랜드 이미지", description = "브랜드 이미지 업로드, 초안 저장, 게시·복구")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping(BrandAssetAdminResponses.PATH)
public class BrandAssetAdminController {
    private final BrandAssetAdminUseCase useCase;

    @Operation(summary = "브랜드 이미지 초안과 현재 게시본 조회")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping
    public ResponseEntity<Response<BrandAssetAdminResponses.Detail>> detail() {
        return ok(useCase.detail());
    }

    @Operation(summary = "브랜드 이미지 초안 전체 교체")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_BRAND_DRAFT_SAVE")
    @PutMapping
    public ResponseEntity<Response<BrandAssetAdminResponses.Detail>> save(
        @AuthenticationPrincipal MemberPrincipal member, @RequestBody BrandAssetAdminRequests.Draft request
    ) {
        return ok(useCase.save(member.memberId(), request));
    }

    @Operation(summary = "초안 게시 또는 과거 게시본 복구")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_BRAND_PUBLISH")
    @PostMapping("/publication")
    public ResponseEntity<Response<BrandAssetAdminResponses.Detail>> publish(
        @AuthenticationPrincipal MemberPrincipal member, @RequestBody BrandAssetAdminRequests.Publication request
    ) {
        return ok(useCase.publish(member.memberId(), request));
    }

    @Operation(summary = "브랜드 이미지 게시·복구 이력 조회")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping("/publications")
    public ResponseEntity<Response<PageResponse<BrandAssetAdminResponses.Publication>>> history(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size
    ) {
        return ok(useCase.history(page, size));
    }

    @Operation(summary = "브랜드 PNG 업로드", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true, content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary"))))
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_BRAND_UPLOAD")
    @PostMapping("/uploads/{kind}")
    public ResponseEntity<Response<BrandAssetAdminResponses.Upload>> upload(
        @AuthenticationPrincipal MemberPrincipal member, @PathVariable String kind, HttpServletRequest request
    ) {
        return ok(useCase.upload(member.memberId(), kind, request.getContentType(), BrandUploadBody.read(request)));
    }

    @Operation(summary = "관리자용으로 업로드한 PNG 미리보기")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping(value = "/uploads/{kind}/{assetId}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable String kind, @PathVariable UUID assetId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body(useCase.preview(kind, assetId));
    }

    private static <T> ResponseEntity<Response<T>> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(body));
    }
}
