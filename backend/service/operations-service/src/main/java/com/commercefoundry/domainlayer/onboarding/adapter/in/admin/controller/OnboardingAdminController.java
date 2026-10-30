package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.request.OnboardingTourUpdateRequest;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingValidationMessage;
import com.commercefoundry.domainlayer.onboarding.application.port.in.OnboardingAdminUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 온보딩 — 내 안내 투어 진행 (#357). 운영자는 access 토큰의 memberId 로만 정한다 — 경로 · 본문으로 받지 않아 남의 기록을 읽거나 쓸 수 없다.
 * 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 온보딩", description = "운영자별 안내 투어 진행과 실제 업무 준비 상태")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/onboarding/me")
public class OnboardingAdminController {

    private final OnboardingAdminUseCase onboardingAdminUseCase;

    @Operation(
        summary = "내 온보딩 조회",
        description = "배포된 안내 투어 전부를 설정 순서로 낸다. 기록이 없거나 기록 버전이 배포 버전과 다르면 NOT_STARTED 다. "
            + "tasks 는 실제 업무 완료로 담당 서비스의 사실로만 판정한다 — SHIPPING_POLICY_SAVED(국내 배송비 정책 저장), PRODUCT_REGISTERED(상품 1건 등록) 순서다. "
            + "스토어 준비(storeStatus)와 본인 실습(myStatus)을 따로 낸다. 담당 서비스 장애여도 200 이고 그 업무만 UNAVAILABLE 이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<OnboardingAdminResponse>> getMine(@AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(onboardingAdminUseCase.getMine(member.memberId())));
    }

    @Operation(
        summary = "안내 투어 진행 기록",
        description = "다른 기기 · 탭의 기록과 합친다 — 같은 버전이면 상태가 뒤로 가지 않고(완료 · 건너뜀 뒤 진행 중은 무시, 건너뜀 → 완료만 허용) 단계는 큰 쪽이다. "
            + "guideVersion 은 배포된 버전과 같아야 한다 — 기록 버전이 다르면 교체한다. 같은 본문을 다시 보내도 결과가 같다. "
            + "status=NOT_STARTED 는 재실행 — 버전과 관계없이 기록을 지우고 NOT_STARTED 를 낸다(실제 상품 · 설정은 그대로). "
            + "허용 목록에 없는 안내면 404(ONBOARDING_001) — 로컬 저장으로 돌아간다. 배포된 버전보다 오래된 버전이면 409(ONBOARDING_020), "
            + "다른 기기와 계속 겹치면 409(ONBOARDING_021) — 다시 시도한다. 배포되지 않은 버전이면 400(ONBOARDING_103)이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/tours/{guideKey}")
    public ResponseEntity<Response<OnboardingTourItem>> updateTour(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "안내 키 (영문 소문자로 시작하는 소문자 · 숫자 · 하이픈 1 ~ 64자)", example = "console")
        @PathVariable @Pattern(regexp = OnboardingValidationMessage.GUIDE_KEY_PATTERN_REGEXP, message = OnboardingValidationMessage.GUIDE_KEY_PATTERN) String guideKey,
        @Valid @RequestBody OnboardingTourUpdateRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(onboardingAdminUseCase.updateTour(member.memberId(), request.toCommand(guideKey))));
    }
}
