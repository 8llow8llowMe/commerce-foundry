package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminAccountCreateRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminPasswordChangeRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminRoleChangeRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminStatusChangeRequest;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
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
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가게 운영자 계정 (#193 · #236). 상품을 파는 사업자의 직원 계정을 조회 · 추가하고 역할 · 사용 여부를 바꾸며, 로그인한 본인 비밀번호를 바꾼다.
 * 개발자 콘솔이 아니다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Tag(name = "[어드민] 운영자 계정", description = "가게 운영자 목록 · 추가 · 역할 변경 · 사용 여부 변경 · 본인 비밀번호 변경")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminAccountAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminAccountUseCase adminAccountUseCase;

    @Operation(summary = "운영자 목록", description = "운영자 역할 전체(ADMIN · OWNER · MANAGER · STAFF)를 최신순(아이디 내림차순)으로 준다. 이메일 · 이름은 마스킹한 값이다. "
        + "active 를 주면 사용 중(true) · 꺼진(false) 계정만. page 는 0부터, size 는 1~100(기본 20)이고 벗어나면 400(AUTH_133 · AUTH_134), "
        + "active 값이 틀리면 400(AUTH_198)이다. 조회는 감사 로그에 남는다.")
    @PreAuthorize(AdminAuthority.STAFF_READ)
    @GetMapping("/admins")
    public ResponseEntity<Response<PageResponse<AdminAccountItem>>> searchAdmins(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "사용 여부 — true · false. 없으면 전체") @RequestParam(required = false) Boolean active,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = MemberValidationMessage.ADMIN_PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = MemberValidationMessage.ADMIN_SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = MemberValidationMessage.ADMIN_SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(adminAccountUseCase.searchAdmins(admin.memberId(), active, page, size)));
    }

    @Operation(summary = "운영자 추가", description = "운영자 계정을 만든다. 역할(OWNER · MANAGER · STAFF)을 주지 않으면 관리자(ADMIN)다. "
        + "역할을 주면 역할 나누기가 켜져 있어야 한다(아니면 409 AUTH_049). 역할이 없으면 활성 대표 관리자만, 역할이 있으면 그 역할을 줄 수 있는 사람만 만든다(403 AUTH_046). "
        + "이미 있는 이메일(고객 포함)은 409(AUTH_002)이고 승격하지 않는다. 비밀번호는 응답에 없다. 201 {memberId}.")
    @PreAuthorize(AdminAuthority.STAFF_MANAGE)
    @AdminAudited(action = "ADMIN_CREATE")
    @PostMapping("/admins")
    public ResponseEntity<Response<AdminAccountCreatedResponse>> createAdmin(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminAccountCreateRequest request
    ) {
        AdminAccountCreatedResponse created = adminAccountUseCase.createAdmin(
            admin.memberId(), request.email(), request.password(), request.name(), request.assignedRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(created));
    }

    @Operation(summary = "운영자 역할 변경", description = "활성 운영자의 역할을 OWNER · MANAGER · STAFF 중 하나로 바꾼다. 같은 역할이어도 200 이다. "
        + "바꾼 계정의 기존 access 토큰은 게이트웨이에서 거절되므로 다시 로그인하면 새 역할이 적용된다. 역할 나누기가 꺼져 있으면 409(AUTH_049), "
        + "본인 계정은 400(AUTH_047), 없거나 고객이거나 꺼진 계정은 404(AUTH_033), 마지막 대표 관리자를 내리면 409(AUTH_048), "
        + "대표 관리자 계정을 대표 관리자가 아닌 사람이 바꾸거나 줄 수 없는 역할이면 403(AUTH_046), 토큰 차단 저장 실패는 503(AUTH_013) — 같은 요청을 다시 보낸다.")
    @PreAuthorize(AdminAuthority.STAFF_MANAGE)
    @AdminAudited(action = "ADMIN_ROLE_CHANGE")
    @PatchMapping("/admins/{memberId}/role")
    public ResponseEntity<Response<Void>> changeRole(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "바꿀 운영자 아이디") @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId,
        @Valid @RequestBody AdminRoleChangeRequest request
    ) {
        adminAccountUseCase.changeRole(admin.memberId(), memberId, request.assignedRole());
        return ResponseEntity.ok().body(Response.success());
    }

    /**
     * 감사 선언은 행위 하나만 담아 끄기({@code ADMIN_DEACTIVATE})로 둔다. 다시 켜기(true)는 Facade 가 {@code ADMIN_REACTIVATE} 줄로 남긴다.
     */
    @Operation(summary = "운영자 사용 여부 변경", description = "active=false 면 운영자 계정을 끄고, true 면 다시 켠다. 같은 상태여도 200 이다. "
        + "끈 계정의 기존 access 토큰은 게이트웨이에서 거절된다. 다시 켠 계정은 끄기 전 비밀번호 · OTP 로 로그인한다. "
        + "본인 계정은 400(AUTH_047), 없거나 고객인 계정은 404(AUTH_033), 대표 관리자 계정을 대표 관리자가 아닌 사람이 바꾸면 403(AUTH_046), "
        + "마지막 대표 관리자를 끄면 409(AUTH_031), 토큰 차단 저장 실패는 503(AUTH_013) — 같은 요청을 다시 보낸다.")
    @PreAuthorize(AdminAuthority.STAFF_MANAGE)
    @AdminAudited(action = "ADMIN_DEACTIVATE")
    @PatchMapping("/admins/{memberId}/status")
    public ResponseEntity<Response<Void>> changeStatus(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "바꿀 운영자 아이디") @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId,
        @Valid @RequestBody AdminStatusChangeRequest request
    ) {
        adminAccountUseCase.changeStatus(admin.memberId(), memberId, request.active());
        return ResponseEntity.ok().body(Response.success());
    }

    /** 어드민 화면 전환(#237) 동안 FE(#352) 호환으로 남긴다. #237 머지 뒤 지운다 — 새 화면은 {@code PATCH /admins/{memberId}/status} 를 쓴다. */
    @Deprecated
    @Operation(summary = "운영자 비활성화 (이전 API)", deprecated = true,
        description = "PATCH /admins/{memberId}/status {\"active\":false} 로 옮긴다. 활성 운영자 계정을 끈다. 대표 관리자(ADMIN · OWNER) 중 마지막 한 명은 409(AUTH_031). "
            + "없거나 고객이거나 이미 꺼진 계정은 404(AUTH_033), 대표 관리자 계정을 대표 관리자가 아닌 사람이 끄면 403(AUTH_046). "
            + "끈 계정의 기존 access 토큰은 게이트웨이에서 거절된다.")
    @PreAuthorize(AdminAuthority.STAFF_MANAGE)
    @AdminAudited(action = "ADMIN_DEACTIVATE")
    @PatchMapping("/admins/{memberId}")
    public ResponseEntity<Response<Void>> deactivateAdmin(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "끌 관리자 아이디") @PathVariable @Positive(message = MemberValidationMessage.ADMIN_ACCOUNT_ID_POSITIVE) long memberId
    ) {
        adminAccountUseCase.deactivateAdmin(admin.memberId(), memberId, admin.tokenId());
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(summary = "본인 비밀번호 변경", description = "로그인한 관리자 자신의 비밀번호만 바꾼다. 현재 비밀번호가 틀리면 400(AUTH_032). "
        + "바꾼 뒤 기존 access 토큰은 게이트웨이에서 거절되므로 다시 로그인한다.")
    @PreAuthorize(AdminAuthority.OPERATOR)
    @AdminAudited(action = "ADMIN_PASSWORD_CHANGE")
    @PatchMapping("/me/password")
    public ResponseEntity<Response<Void>> changePassword(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal admin,
        @Valid @RequestBody AdminPasswordChangeRequest request
    ) {
        adminAccountUseCase.changePassword(admin.memberId(), admin.tokenId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok().body(Response.success());
    }
}
