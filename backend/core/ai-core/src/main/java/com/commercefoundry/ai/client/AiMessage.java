package com.commercefoundry.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * 대화 이력의 메시지 하나.
 *
 * @param role     말한 쪽
 * @param contents 블록들. 하나 이상. 블록 종류와 역할의 짝은 {@link AiConversationRequest} 가 본다
 */
public record AiMessage(Role role, List<AiContent> contents) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public AiMessage {
        Objects.requireNonNull(role, "role");
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("메시지에 블록이 없습니다");
        }
        contents = List.copyOf(contents);
    }

    public static AiMessage user(String text) {
        return new AiMessage(Role.USER, List.of(AiContent.text(text)));
    }

    public static AiMessage assistant(String text) {
        return new AiMessage(Role.ASSISTANT, List.of(AiContent.text(text)));
    }

    /** 도구 결과를 담은 사용자 메시지 — 바로 앞 assistant 메시지의 호출마다 하나씩, 한 메시지에 모두 담는다. */
    public static AiMessage toolResults(List<AiContent.ToolResult> results) {
        return new AiMessage(Role.USER, List.copyOf(results));
    }

    public List<AiToolCall> toolCalls() {
        return contents.stream().filter(AiToolCall.class::isInstance).map(AiToolCall.class::cast).toList();
    }

    public List<AiContent.ToolResult> toolResults() {
        return contents.stream().filter(AiContent.ToolResult.class::isInstance).map(AiContent.ToolResult.class::cast).toList();
    }

    @Override
    public String toString() {
        return "AiMessage[role=" + role + ", contents=" + contents + "]";
    }
}
