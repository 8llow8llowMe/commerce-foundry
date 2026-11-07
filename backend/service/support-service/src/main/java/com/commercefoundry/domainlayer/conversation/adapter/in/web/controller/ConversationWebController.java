package com.commercefoundry.domainlayer.conversation.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.request.ConversationMessageRequest;
import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response.ConversationWebResponses;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "고객 상담", description = "회원 또는 기존 비회원의 본인 대화")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/support/conversations")
public class ConversationWebController {
    private final ConversationWebUseCase useCase;

    @Operation(summary = "본인 상담 대화 조회", description = "회원 JWT가 우선하며 비회원은 기존 토큰을 사용한다. 없는 대화와 타인 대화는 동일한 404다.")
    @PreAuthorize("isAnonymous() or hasRole('CUSTOMER')")
    @GetMapping("/{conversationId}")
    public ResponseEntity<Response<ConversationWebResponses.Detail>> get(
        @Parameter(description = "대화 아이디") @PathVariable long conversationId,
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "기존 비회원 자격 증명") @RequestHeader(value = "X-Support-Guest-Token", required = false) String guestToken
    ) {
        return ResponseEntity.ok(Response.success(useCase.get(conversationId, member == null ? null : member.memberId(), guestToken)));
    }

    @Operation(summary = "본인 상담 메시지 조회", description = "cursor 다음 순번을 오름차순으로 반환한다. 다음 조회에는 nextCursor를 전달한다.")
    @PreAuthorize("isAnonymous() or hasRole('CUSTOMER')")
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<Response<ConversationWebResponses.MessagePage>> messages(
        @Parameter(description = "대화 아이디") @PathVariable long conversationId,
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "기존 비회원 자격 증명") @RequestHeader(value = "X-Support-Guest-Token", required = false) String guestToken,
        @Parameter(description = "메시지 커서, 기본 0") @RequestParam(defaultValue = "0") long cursor,
        @Parameter(description = "크기 1~100, 기본 20") @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(Response.success(useCase.messages(conversationId, member == null ? null : member.memberId(), guestToken, cursor, size)));
    }

    @Operation(summary = "고객 메시지 저장 또는 재생", description = "소문자 canonical UUID 멱등 키를 사용한다. 신규와 재생은 모두 200이다.")
    @PreAuthorize("isAnonymous() or hasRole('CUSTOMER')")
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<Response<ConversationWebResponses.Message>> append(
        @Parameter(description = "대화 아이디") @PathVariable long conversationId,
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "기존 비회원 자격 증명") @RequestHeader(value = "X-Support-Guest-Token", required = false) String guestToken,
        @Parameter(description = "소문자 canonical UUID 멱등 키") @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestBody ConversationMessageRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.append(conversationId, member == null ? null : member.memberId(), guestToken, key, request.content())));
    }
}
