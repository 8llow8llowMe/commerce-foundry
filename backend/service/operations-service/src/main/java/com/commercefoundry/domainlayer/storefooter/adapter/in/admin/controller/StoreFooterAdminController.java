package com.commercefoundry.domainlayer.storefooter.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.request.StoreFooterAdminRequests;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.response.StoreFooterAdminResponses;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterValidationMessage;
import com.commercefoundry.domainlayer.storefooter.application.port.in.StoreFooterAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 스토어 푸터", description = "이중 언어 푸터 링크의 불변 초안·게시·이력 관리")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/store/footer")
public class StoreFooterAdminController {
    private final StoreFooterAdminUseCase useCase;

    @Operation(summary = "푸터 최신 초안 조회", description = "미등록은 404입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping
    public ResponseEntity<Response<StoreFooterAdminResponses.Detail>> detail() {
        return ok(useCase.detail());
    }

    @Operation(summary = "푸터 새 초안 저장", description = "전체 링크를 교체한 새 리비전을 만듭니다. 현재 게시본은 유지합니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_FOOTER_DRAFT_SAVE")
    @PutMapping
    public ResponseEntity<Response<StoreFooterAdminResponses.Detail>> save(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody StoreFooterAdminRequests.Draft request
    ) {
        return ok(useCase.save(member.memberId(), request.toCommand()));
    }

    @Operation(summary = "푸터 최신 초안 게시", description = "현재 게시본의 반복 요청은 변경 없이 성공합니다. 그 외 버전·리비전 충돌은 409입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_FOOTER_PUBLISH")
    @PostMapping("/publication")
    public ResponseEntity<Response<StoreFooterAdminResponses.Detail>> publish(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody StoreFooterAdminRequests.Publish request
    ) {
        return ok(useCase.publish(member.memberId(), request.toCommand()));
    }

    @Operation(summary = "푸터 리비전 이력", description = "리비전 번호 내림차순입니다. 복원은 이력 내용을 새 초안으로 저장한 뒤 게시합니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping("/revisions")
    public ResponseEntity<Response<PageResponse<StoreFooterAdminResponses.Revision>>> revisions(
        @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0")
        @Min(value = 0, message = StoreFooterValidationMessage.PAGE) int page,
        @Parameter(description = "페이지 크기 (1~20)") @RequestParam(defaultValue = "10")
        @Min(value = 1, message = StoreFooterValidationMessage.PAGE) @Max(value = 20, message = StoreFooterValidationMessage.PAGE) int size
    ) {
        return ok(useCase.revisions(page, size));
    }

    private <T> ResponseEntity<Response<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(data));
    }
}
