package com.commercefoundry.ai.client;

import com.commercefoundry.ai.client.AiMessage.Role;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 다중 턴 대화 호출 한 번의 입력 ({@link AiClient#converse}). 호출 한 번 = 응답 한 번이다 — 도구 루프(호출 → 실행 → 결과 첨부 → 재호출)는 호출자가 돈다.
 *
 * <p><b>이력은 서버가 보관한 것만 쓴다</b> — 클라이언트(브라우저)가 보낸 이력을 받지 않는다. 클라이언트는 새 사용자 메시지만 보내고, assistant 메시지 ·
 * 도구 호출 · 도구 결과 · Provider 블록({@link AiContent.Opaque})은 서버 저장소에서 꺼낸다. 모양 검사가 내용의 출처까지 보장하지는 않는다.
 *
 * <p>만들 때 이력 모양을 검사하고, 어기면 {@link IllegalArgumentException}(보내지 않는다, 메시지에 원문 없음):
 * <ul>
 *   <li>메시지 1~{@link #MAX_MESSAGES}개, 첫 메시지와 마지막 메시지는 {@code USER} (assistant 미리 채우기는 최신 모델이 거절한다)</li>
 *   <li>{@link AiToolCall} · {@link AiContent.Opaque} 는 {@code ASSISTANT} 에만, {@link AiContent.ToolResult} 는 {@code USER} 에만</li>
 *   <li>도구 호출이 있는 assistant 메시지 바로 다음은 그 호출 id 마다 결과가 정확히 하나씩 있는 {@code USER} 메시지다. 결과는 그 메시지에서 텍스트보다 앞이다.
 *       짝 없는 결과 · 남는 결과 · 중복 결과는 거절한다</li>
 *   <li>도구 호출 id 는 대화 안에서 겹치지 않고, 호출한 도구는 {@code tools} 에 있다</li>
 *   <li>도구는 {@link #MAX_TOOLS}개 이하, 이름이 겹치지 않는다</li>
 *   <li><b>도구 왕복 상한</b> — 마지막 일반 사용자 메시지(도구 결과가 없는 것) 뒤에 쌓인 도구 호출 왕복이 {@link #MAX_TOOL_ROUNDS_PER_TURN}회 이하.
 *       호출자 루프가 이 횟수에 닿으면 도구 없이 답하게 하거나 멈춘다</li>
 * </ul>
 *
 * @param feature         기능 이름({@link AiRequest#FEATURE_NAME}). 모델 · 토큰 설정과 원장 키
 * @param system          시스템 프롬프트. 없으면 {@code null}. 고객 텍스트를 넣지 않는다
 * @param messages        대화 이력(시간 순). 모든 텍스트 · 도구 결과 · 도구 인자가 보내기 전에 가려지고, 합이 {@code ai.max-input-chars} 를 넘지 않아야 한다
 * @param tools           모델에 넘길 도구. 없으면 빈 목록
 * @param sensitiveValues 호출자가 아는 개인정보 값 — 이력 전체에 적용한다({@link AiRequest#sensitiveValues()} 와 같은 규칙)
 * @param promptVersion   시스템 프롬프트 템플릿 버전. 없으면 {@code null}
 * @param callerRef       원장 식별자({@link AiRequest#CALLER_REF}). 없으면 {@code null}
 * @param maxTokens       최대 출력 토큰. {@code null} 이면 기능 설정값
 */
public record AiConversationRequest(String feature, String system, List<AiMessage> messages, List<AiTool> tools, List<String> sensitiveValues,
                                    Integer promptVersion, String callerRef, Integer maxTokens) {

    public static final int MAX_MESSAGES = 100;
    public static final int MAX_TOOLS = 32;
    public static final int MAX_TOOL_ROUNDS_PER_TURN = 8;

    public AiConversationRequest {
        Objects.requireNonNull(feature, "feature");
        if (!AiRequest.FEATURE_NAME.matcher(feature).matches()) {
            throw new IllegalArgumentException("feature 는 소문자 · 숫자 · '-' 로 1~63자여야 합니다");
        }
        if (callerRef != null && (callerRef.length() > AiRequest.CALLER_REF_MAX_LENGTH || !AiRequest.CALLER_REF.matcher(callerRef).matches())) {
            throw new IllegalArgumentException("callerRef 는 소문자 접두어 뒤에 :토막(영문 · 숫자 · _ · -) 1~3개, 100자 이하여야 합니다 (예: job:123)");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens 는 1 이상이어야 합니다");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        sensitiveValues = sensitiveValues == null ? List.of() : List.copyOf(sensitiveValues.stream().filter(Objects::nonNull).toList());
        checkTools(tools);
        checkHistory(messages, tools.stream().map(AiTool::name).collect(Collectors.toSet()));
    }

    /** 템플릿 · 도구 · 개인정보 값 없이. */
    public static AiConversationRequest of(String feature, String system, List<AiMessage> messages) {
        return new AiConversationRequest(feature, system, messages, List.of(), List.of(), null, null, null);
    }

    /** 도구와 함께. 도구 호출이 쌓인 이력은 이 형태로 만든다 — 이력의 호출이 {@code tools} 에 있는지 만들 때 본다. */
    public static AiConversationRequest of(String feature, String system, List<AiMessage> messages, List<AiTool> tools) {
        return new AiConversationRequest(feature, system, messages, tools, List.of(), null, null, null);
    }

    public AiConversationRequest withTools(List<AiTool> tools) {
        return new AiConversationRequest(feature, system, messages, tools, sensitiveValues, promptVersion, callerRef, maxTokens);
    }

    public AiConversationRequest withSensitiveValues(List<String> sensitiveValues) {
        return new AiConversationRequest(feature, system, messages, tools, sensitiveValues, promptVersion, callerRef, maxTokens);
    }

    public AiConversationRequest withCallerRef(String callerRef) {
        return new AiConversationRequest(feature, system, messages, tools, sensitiveValues, promptVersion, callerRef, maxTokens);
    }

    /** 이력 끝에 메시지를 붙인 새 요청 — 도구 루프에서 assistant 응답과 도구 결과를 이어 붙일 때 쓴다. */
    public AiConversationRequest append(AiMessage... next) {
        List<AiMessage> appended = new java.util.ArrayList<>(messages);
        appended.addAll(List.of(next));
        return new AiConversationRequest(feature, system, appended, tools, sensitiveValues, promptVersion, callerRef, maxTokens);
    }

    /** 이름으로 도구를 찾는다. */
    AiTool tool(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
    }

    /** 원문 없이 개수 · 길이만 싣는다. 도구 이름은 서버가 정한 값이라 싣는다. */
    @Override
    public String toString() {
        return "AiConversationRequest[feature=" + feature + ", promptVersion=" + promptVersion + ", callerRef=" + callerRef
            + ", systemLength=" + (system == null ? 0 : system.length()) + ", messages=" + messages.size()
            + ", tools=" + tools.stream().map(AiTool::name).toList() + ", sensitiveValues=" + sensitiveValues.size() + ", maxTokens=" + maxTokens + "]";
    }

    private static void checkTools(List<AiTool> tools) {
        if (tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("도구는 " + MAX_TOOLS + "개 이하여야 합니다");
        }
        Set<String> names = new HashSet<>();
        for (AiTool tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("도구 이름이 겹칩니다: " + tool.name());
            }
        }
    }

    private static void checkHistory(List<AiMessage> messages, Set<String> toolNames) {
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("메시지는 1~" + MAX_MESSAGES + "개여야 합니다");
        }
        if (messages.getFirst().role() != Role.USER || messages.getLast().role() != Role.USER) {
            throw new IllegalArgumentException("첫 메시지와 마지막 메시지는 USER 여야 합니다");
        }
        Set<String> seenCallIds = new HashSet<>();
        Set<String> pending = Set.of();
        int roundsInTurn = 0;
        for (int i = 0; i < messages.size(); i++) {
            AiMessage message = messages.get(i);
            if (message.role() == Role.ASSISTANT) {
                if (!pending.isEmpty()) {
                    throw new IllegalArgumentException("도구 호출 뒤에 결과 메시지가 없습니다 (index=" + i + ")");
                }
                checkAssistant(message, i, toolNames);
                Set<String> calls = new HashSet<>();
                for (AiToolCall call : message.toolCalls()) {
                    if (!seenCallIds.add(call.id()) || !calls.add(call.id())) {
                        throw new IllegalArgumentException("도구 호출 id 가 겹칩니다 (index=" + i + ")");
                    }
                }
                pending = calls;
                if (!calls.isEmpty()) {
                    roundsInTurn++;
                }
            } else {
                List<AiContent.ToolResult> results = checkUser(message, i);
                Set<String> answered = new HashSet<>();
                for (AiContent.ToolResult result : results) {
                    if (!answered.add(result.callId())) {
                        throw new IllegalArgumentException("같은 호출의 결과가 둘 이상입니다 (index=" + i + ")");
                    }
                }
                if (!answered.equals(pending)) {
                    throw new IllegalArgumentException("도구 결과가 바로 앞 assistant 메시지의 호출과 짝이 맞지 않습니다 (index=" + i + ")");
                }
                if (results.isEmpty()) {
                    roundsInTurn = 0;
                }
                pending = Set.of();
            }
        }
        if (roundsInTurn > MAX_TOOL_ROUNDS_PER_TURN) {
            throw new IllegalArgumentException("한 턴의 도구 왕복이 " + MAX_TOOL_ROUNDS_PER_TURN + "회를 넘었습니다");
        }
    }

    private static void checkAssistant(AiMessage message, int index, Set<String> toolNames) {
        for (AiContent content : message.contents()) {
            if (content instanceof AiContent.ToolResult) {
                throw new IllegalArgumentException("도구 결과는 USER 메시지에만 둡니다 (index=" + index + ")");
            }
            if (content instanceof AiToolCall call && !toolNames.contains(call.name())) {
                throw new IllegalArgumentException("tools 에 없는 도구를 호출한 이력입니다: " + call.name() + " (index=" + index + ")");
            }
        }
    }

    /** 결과 블록이 텍스트보다 앞인지 보고 결과 목록을 돌려준다. */
    private static List<AiContent.ToolResult> checkUser(AiMessage message, int index) {
        boolean textSeen = false;
        for (AiContent content : message.contents()) {
            switch (content) {
                case AiToolCall ignored -> throw new IllegalArgumentException("도구 호출은 ASSISTANT 메시지에만 둡니다 (index=" + index + ")");
                case AiContent.Opaque ignored -> throw new IllegalArgumentException("Provider 블록은 ASSISTANT 메시지에만 둡니다 (index=" + index + ")");
                case AiContent.Text ignored -> textSeen = true;
                case AiContent.ToolResult ignored -> {
                    if (textSeen) {
                        throw new IllegalArgumentException("도구 결과는 같은 메시지의 텍스트보다 앞에 둡니다 (index=" + index + ")");
                    }
                }
            }
        }
        return message.toolResults();
    }
}
