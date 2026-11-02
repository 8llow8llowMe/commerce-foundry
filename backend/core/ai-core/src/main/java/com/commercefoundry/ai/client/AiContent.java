package com.commercefoundry.ai.client;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 대화 메시지 안의 블록 하나. Provider 중립이다 — SDK 타입을 담지 않는다.
 *
 * <ul>
 *   <li>{@link Text} — 사용자 · assistant 텍스트</li>
 *   <li>{@link AiToolCall} — assistant 가 요청한 도구 호출(모델 출력). assistant 메시지에만</li>
 *   <li>{@link ToolResult} — 호출자가 도구를 실행한 결과. 사용자 메시지에만, 바로 앞 assistant 의 호출과 짝</li>
 *   <li>{@link Opaque} — Provider 가 다음 호출에 그대로 돌려받아야 하는 블록(Claude 의 thinking 서명 등). assistant 메시지에만, 호출자는 열지 않고
 *       {@link AiConversationResult#assistantMessage()} 그대로 이력에 붙인다</li>
 * </ul>
 *
 * <p>{@code toString} 은 원문 없이 길이만 싣는다.
 */
public sealed interface AiContent permits AiContent.Text, AiToolCall, AiContent.ToolResult, AiContent.Opaque {

    static Text text(String text) {
        return new Text(text);
    }

    static ToolResult toolResult(String callId, String content) {
        return new ToolResult(callId, content, false);
    }

    static ToolResult toolError(String callId, String content) {
        return new ToolResult(callId, content, true);
    }

    /** 텍스트 블록. 비어 있으면 안 된다(API 가 빈 텍스트 블록을 거절한다). 보내기 전에 가린다. */
    record Text(String text) implements AiContent {

        public Text {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("텍스트 블록이 비었습니다");
            }
        }

        @Override
        public String toString() {
            return "Text[length=" + text.length() + "]";
        }
    }

    /**
     * 도구 실행 결과.
     *
     * @param callId  짝이 되는 {@link AiToolCall#id()}
     * @param content 결과 본문 — JSON 문자열을 권한다. 모델에는 텍스트로 간다. 보내기 전에 가린다 — 주문 · 배송 조회 결과처럼 개인정보가 섞이면
     *                요청의 {@code sensitiveValues} 로 그 값을 넘긴다
     * @param error   실행이 실패했으면 {@code true} — 모델이 다른 방법을 고르게 한다. 실패 사유에 내부 정보(스택 · SQL)를 넣지 않는다
     */
    record ToolResult(String callId, String content, boolean error) implements AiContent {

        public ToolResult {
            if (callId == null || !AiToolCall.ID.matcher(callId).matches()) {
                throw new IllegalArgumentException("도구 결과의 callId 가 규칙에 맞지 않습니다");
            }
            Objects.requireNonNull(content, "content");
        }

        @Override
        public String toString() {
            return "ToolResult[callId=" + callId + ", error=" + error + ", contentLength=" + content.length() + "]";
        }
    }

    /**
     * Provider 전용 블록. 모델이 만든 그대로 다음 호출에 돌려준다 — 가리지도 고치지도 않는다(서명이 깨진다). 다른 Provider 의 블록은 보내지 않고 버린다.
     *
     * <p><b>보안</b>: 가리지 않고 그대로 보내는 유일한 블록이라 위조되면 마스킹 · 짝 검사를 피하는 통로가 된다. 그래서 만들 때 JSON 객체 · 길이
     * ({@value #MAX_PAYLOAD_CHARS}자) · 종류(Provider 가 아는 것만 — {@code anthropic} 은 {@code thinking} · {@code redacted_thinking})를 보고,
     * Provider 가 보낼 때 다시 본다. 이력(특히 이 블록)은 <b>서버가 보관한 것만</b> 쓴다 — 클라이언트(브라우저)가 보낸 이력을 받지 않는다.
     * 보관은 고객 텍스트와 같은 등급으로 하고, 응답 · 로그로 내보내지 않는다. 입력 길이 상한 · 예상 비용에 길이가 들어간다.
     *
     * @param provider 만든 Provider({@code anthropic})
     * @param payload  Provider 가 해석하는 직렬화 값(JSON 객체, {@code type} 필드). 호출자는 열지 않는다
     */
    record Opaque(String provider, String payload) implements AiContent {

        public static final int MAX_PAYLOAD_CHARS = 100_000;
        /** Provider 별로 받아 주는 블록 종류. 여기 없는 Provider 의 블록은 받되 그 Provider 가 아닌 클라이언트는 보내지 않는다. */
        static final Map<String, Set<String>> ALLOWED_TYPES = Map.of("anthropic", Set.of("thinking", "redacted_thinking"));

        public Opaque {
            if (provider == null || !AiTool.NAME.matcher(provider).matches()) {
                throw new IllegalArgumentException("Provider 블록의 provider 가 규칙에 맞지 않습니다");
            }
            Objects.requireNonNull(payload, "payload");
            if (payload.length() > MAX_PAYLOAD_CHARS) {
                throw new IllegalArgumentException("Provider 블록이 " + MAX_PAYLOAD_CHARS + "자를 넘습니다");
            }
            // 값을 싣지 않는다 — 위조된 블록은 고객 텍스트를 품을 수 있다
            String type = AiJson.object(payload).map(node -> node.path("type").asText(null))
                .orElseThrow(() -> new IllegalArgumentException("Provider 블록이 JSON 객체가 아닙니다"));
            Set<String> allowed = ALLOWED_TYPES.get(provider);
            if (type == null || (allowed != null && !allowed.contains(type))) {
                throw new IllegalArgumentException("Provider 블록의 종류가 허용되지 않습니다 (provider=" + provider + ")");
            }
        }

        @Override
        public String toString() {
            return "Opaque[provider=" + provider + ", payloadLength=" + payload.length() + "]";
        }
    }
}
