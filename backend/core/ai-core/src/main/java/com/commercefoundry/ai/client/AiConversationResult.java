package com.commercefoundry.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * 대화 호출 한 번의 결과. 모델이 도구를 골랐으면 {@link #toolCalls()} 가 비어 있지 않다 — 호출자가 실행하고 결과를 붙여 다시 부른다:
 *
 * <pre>{@code
 * AiConversationResult result = aiClient.converse(request);
 * while (result.hasToolCalls()) {                       // 왕복 상한은 기능 규칙(ai-core 바깥 울타리는 AiConversationRequest.MAX_TOOL_ROUNDS_PER_TURN)
 *     List<AiContent.ToolResult> results = execute(result.toolCalls());   // 소유 검증 · 실행은 호출자 몫
 *     request = request.append(result.assistantMessage(), AiMessage.toolResults(results));
 *     result = aiClient.converse(request);
 * }
 * }</pre>
 *
 * @param text             assistant 텍스트 블록을 이어 붙인 것. 도구만 고르면 빈 문자열일 수 있다
 * @param toolCalls        모델이 요청한 도구 호출(인자는 스키마 최소 검증을 통과했다). 없으면 빈 목록
 * @param assistantMessage 다음 호출 이력에 그대로 붙일 assistant 메시지(텍스트 · 도구 호출 · Provider 블록, 응답 순서). 취소된 스트림이면 {@code null}.
 *                         서버에 보관하고 클라이언트로 내보내지 않는다(Provider 블록 — {@link AiContent.Opaque})
 * @param stopReason       멈춘 이유 — Provider 중립 어휘({@link AiStopReason}). 결과로 오는 값은 {@code END_TURN} · {@code TOOL_USE} · {@code MAX_TOKENS} ·
 *                         {@code CANCELLED} · {@code OTHER} 다
 * @param model            답한 모델 ID
 * @param messageId        API 메시지 ID. 응답을 받기 전에 취소했으면 {@code null}
 * @param usage            토큰 사용량. 취소면 그때까지 API 가 알려 준 만큼(없으면 0)
 */
public record AiConversationResult(String text, List<AiToolCall> toolCalls, AiMessage assistantMessage, AiStopReason stopReason, String model,
                                   String messageId, AiUsage usage) {

    public AiConversationResult {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 최대 토큰에서 잘렸다. 도구 호출이 잘린 응답은 결과가 되지 못하고 {@code INVALID_RESPONSE} 다 — 텍스트만 잘렸을 때 이 값이 참이다. */
    public boolean truncated() {
        return stopReason == AiStopReason.MAX_TOKENS;
    }

    /** 스트리밍을 호출자가 취소했다 — {@code text} 는 그때까지 받은 조각이다. */
    public boolean cancelled() {
        return stopReason == AiStopReason.CANCELLED;
    }

    /** 원문 · 도구 인자 없이 길이 · 도구 이름만 싣는다. */
    @Override
    public String toString() {
        return "AiConversationResult[textLength=" + text.length() + ", toolCalls=" + toolCalls.stream().map(AiToolCall::name).toList()
            + ", model=" + model + ", usage=" + usage + ", stopReason=" + stopReason + ", messageId=" + messageId + "]";
    }
}
