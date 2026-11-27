package com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.request.StoreNavigationAdminRequests;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.response.StoreNavigationAdminResponses;
import com.commercefoundry.domainlayer.storenavigation.application.port.in.StoreNavigationAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 스토어 상단 메뉴", description = "이중 언어 상단 메뉴 트리의 불변 초안·게시 관리")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/store/navigation")
public class StoreNavigationAdminController {
    private final StoreNavigationAdminUseCase useCase;

    @Operation(summary = "상단 메뉴 최신 초안 조회", description = "최신 저장본과 최신·게시 리비전 번호, 버전을 돌려줍니다. 미등록은 404입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_READ)
    @GetMapping
    public ResponseEntity<Response<StoreNavigationAdminResponses.Detail>> detail() {
        return ok(useCase.detail());
    }

    @Operation(summary = "상단 메뉴 새 초안 저장",
        description = "전체 항목을 교체한 새 리비전을 만듭니다. 현재 게시본은 유지합니다. 버전이 다르면 409입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_NAVIGATION_DRAFT_SAVE")
    @PutMapping
    public ResponseEntity<Response<StoreNavigationAdminResponses.Detail>> save(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody StoreNavigationAdminRequests.Draft request
    ) {
        return ok(useCase.save(member.memberId(), request.toCommand()));
    }

    @Operation(summary = "상단 메뉴 최신 초안 게시",
        description = "최신 리비전만 게시합니다. 현재 게시본의 반복 요청은 변경 없이 성공하고, 그 외 버전·리비전 충돌은 409입니다. 미등록은 404입니다.")
    @PreAuthorize(AdminAuthority.SETTINGS_MANAGE)
    @AdminAudited(action = "STORE_NAVIGATION_PUBLISH")
    @PostMapping("/publication")
    public ResponseEntity<Response<StoreNavigationAdminResponses.Detail>> publish(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody StoreNavigationAdminRequests.Publish request
    ) {
        return ok(useCase.publish(member.memberId(), request.toCommand()));
    }

    private <T> ResponseEntity<Response<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(data));
    }
}
