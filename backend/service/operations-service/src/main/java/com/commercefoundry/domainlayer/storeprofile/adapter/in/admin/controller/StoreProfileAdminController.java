package com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.request.StoreProfileSaveRequest;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.response.StoreProfileAdminResponse;
import com.commercefoundry.domainlayer.storeprofile.application.port.in.StoreProfileAdminUseCase;
import com.commercefoundry.security.audit.AdminAudited;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 쇼핑몰 공개 정보", description = "공개 사업자·고객센터 정보")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/store/profile")
public class StoreProfileAdminController {

    private final StoreProfileAdminUseCase useCase;

    @Operation(summary = "쇼핑몰 공개 정보 조회", description = "미등록은 404입니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<StoreProfileAdminResponse>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.get()));
    }

    @Operation(summary = "쇼핑몰 공개 정보 전체 교체", description = "저장 즉시 공개됩니다. 최초 생성은 expectedVersion=null, 수정은 조회한 version을 보냅니다. 경합은 409입니다.")
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "STORE_PROFILE_UPDATE")
    @PutMapping
    public ResponseEntity<Response<StoreProfileAdminResponse>> save(
        @AuthenticationPrincipal MemberPrincipal member,
        @Valid @RequestBody StoreProfileSaveRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.save(member.memberId(), request.toCommand())));
    }
}
