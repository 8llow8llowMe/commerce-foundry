package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.MemberDisplayNameInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 회원 표시 이름 벌크 조회 (#322). 호출자는 delivery-service 하나다. 원문 이름은 내리지 않는다.
 *
 * <p>서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다
 * ({@code security-guide.md} §9). 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다.
 * 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/member-display-names")
public class MemberDisplayNameInternalController {

    /** 한 번에 받는 아이디 상한 — 중복을 포함한 원문 개수로 센다 ({@code api-design-guide.md} §1-3). */
    private static final int MAX_IDS = 100;

    private final MemberDisplayNameInternalUseCase memberDisplayNameInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_delivery-service')")
    @GetMapping
    public ResponseEntity<Response<MemberDisplayNameInternalResponse>> getDisplayNames(
        @RequestParam
        @NotEmpty(message = MemberValidationMessage.DISPLAY_NAME_IDS_REQUIRED)
        @Size(max = MAX_IDS, message = MemberValidationMessage.DISPLAY_NAME_IDS_SIZE_MAX)
        List<@NotNull(message = MemberValidationMessage.DISPLAY_NAME_ID_POSITIVE) @Positive(message = MemberValidationMessage.DISPLAY_NAME_ID_POSITIVE) Long> ids
    ) {
        return ResponseEntity.ok().body(Response.success(memberDisplayNameInternalUseCase.getDisplayNames(ids)));
    }
}
