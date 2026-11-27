package com.commercefoundry.domainlayer.guestsession.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response.GuestSessionWebResponses;
import com.commercefoundry.domainlayer.guestsession.application.port.in.GuestSessionWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "고객 상담 비회원 자격", description = "로그인하지 않은 고객의 상담 자격 증명 발급")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support/guest-sessions")
public class GuestSessionWebController {
    private final GuestSessionWebUseCase useCase;

    @Operation(summary = "비회원 상담 자격 발급",
        description = "본문 없이 요청한다. 토큰은 이 응답에만 한 번 실리며 7일 뒤 만료된다. 유효한 회원·운영자 JWT는 403, 잘못된 bearer는 401이다. "
            + "게이트웨이 필수 요청 제한을 넘으면 429 GATEWAY_006, 보호 설정 부재·저장소 장애면 503 GATEWAY_002다.",
        responses = @ApiResponse(responseCode = "201", description = "발급됨. 응답은 캐시하지 않는다(no-store)"))
    @PreAuthorize("isAnonymous()")
    @PostMapping
    public ResponseEntity<Response<GuestSessionWebResponses.Issued>> issue() {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(useCase.issue()));
    }
}
