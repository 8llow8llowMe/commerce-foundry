package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.request.MediaImageUploadRequest;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadValidationMessage;
import com.commercefoundry.domainlayer.mediaupload.application.port.in.MediaImageUploadAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 전시 이미지 업로드", description = "전시 · 룩 공용 JPEG · PNG · WebP 업로드")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/displays/image-uploads")
public class MediaImageUploadAdminController {
    private final MediaImageUploadAdminUseCase useCase;

    @Operation(summary = "이미지 업로드 발급", description = "1바이트~10MiB. 응답의 메서드 · 헤더로 PUT 후 확정 API를 호출한다.")
    @AdminAudited(action = "MEDIA_IMAGE_UPLOAD_ISSUE")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @PostMapping
    public ResponseEntity<Response<MediaImageUploadResponse.Ticket>> issue(
        @AuthenticationPrincipal MemberPrincipal member, @RequestBody @Valid MediaImageUploadRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.issue(member.memberId(), request.contentType(), request.contentLength())));
    }

    @Operation(summary = "이미지 업로드 확정", description = "JWT 소유자의 객체를 검증하고 공개 URL을 발급한다. 없으면 404, 변경되면 409다. 본문은 없다.")
    @AdminAudited(action = "MEDIA_IMAGE_UPLOAD_CONFIRM")
    @PreAuthorize(AdminAuthority.CATALOG_MANAGE)
    @PostMapping("/{uploadKey}/confirm")
    public ResponseEntity<Response<MediaImageUploadResponse.Confirmed>> confirm(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "발급받은 UUID.ext basename") @PathVariable
        @Pattern(regexp = MediaUploadValidationMessage.KEY_REGEX, message = MediaUploadValidationMessage.KEY_FORMAT) String uploadKey
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.confirm(member.memberId(), uploadKey)));
    }
}
