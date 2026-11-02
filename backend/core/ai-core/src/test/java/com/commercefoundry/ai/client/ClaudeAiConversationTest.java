package com.commercefoundry.ai.client;

import static com.commercefoundry.ai.support.StubMessagesServer.ok;
import static com.commercefoundry.ai.support.StubMessagesServer.okContent;
import static com.commercefoundry.ai.support.StubMessagesServer.okToolUse;
import static com.commercefoundry.ai.support.StubMessagesServer.textBlock;
import static com.commercefoundry.ai.support.StubMessagesServer.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.support.StubMessagesServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 다중 턴 대화 · 도구 호출을 루프백 스텁으로 본다 (#467). 픽스처 값은 가짜다. */
@ExtendWith(OutputCaptureExtension.class)
class ClaudeAiConversationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PHONE = "010-1234-5678";
    private static final String NAME = "홍길동";
    private static final AiTool LOOKUP = new AiTool("lookup_order", "주문 번호로 주문 상태를 조회한다", """
        {"type":"object","properties":{"orderId":{"type":"string"},"phone":{"type":"string"},"limit":{"type":"integer"}},
         "required":["orderId"],"additionalProperties":false}""");

    private StubMessagesServer server;

    @BeforeEach
    void startServer() {
        server = new StubMessagesServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("3턴 이력이 순서대로 가고 모든 턴이 가려진다 — 컨텍스트에는 마스킹 뒤 합 · 메시지 수 · 도구 수가 실린다")
    void threeTurnHistoryIsSentInOrderAndMasked(CapturedOutput output) throws Exception {
        server.reply(ok("확인했습니다", "end_turn", 40, 6, 0, 100));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiConversationRequest request = new AiConversationRequest("cs-chat", "상담원 규칙", List.of(
            AiMessage.user("제 번호는 " + PHONE + " 입니다"),
            AiMessage.assistant("무엇을 도와드릴까요?"),
            AiMessage.user(NAME + " 이름으로 주문했어요")), List.of(LOOKUP), List.of(NAME), 2, "chat:7:3", null);

        AiConversationResult result = client(interceptor).converse(request);

        assertThat(result.text()).isEqualTo("확인했습니다");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.stopReason()).isEqualTo(AiStopReason.END_TURN);
        assertThat(result.usage()).isEqualTo(new AiUsage(40, 6, 0, 100));
        assertThat(result.assistantMessage().role()).isEqualTo(AiMessage.Role.ASSISTANT);

        JsonNode body = JSON.readTree(server.received().getFirst().body());
        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").get(0).get("text").asText()).isEqualTo("제 번호는 [PHONE] 입니다");
        assertThat(messages.get(1).get("content").get(0).get("text").asText()).isEqualTo("무엇을 도와드릴까요?");
        assertThat(messages.get(2).get("content").get(0).get("text").asText()).isEqualTo("[PII] 이름으로 주문했어요");
        assertThat(body.get("system").toString()).contains("상담원 규칙");
        assertThat(body.get("tools").get(0).get("name").asText()).isEqualTo("lookup_order");
        assertThat(body.get("tools").get(0).get("input_schema").get("required").get(0).asText()).isEqualTo("orderId");
        assertThat(body.get("tools").get(0).get("input_schema").get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        assertThat(body.toString()).doesNotContain(PHONE).doesNotContain(NAME);

        AiCallOutcome outcome = interceptor.single();
        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.usage()).isEqualTo(new AiUsage(40, 6, 0, 100));
        AiCallContext context = interceptor.contexts.getFirst();
        assertThat(context.messageCount()).isEqualTo(3);
        assertThat(context.toolCount()).isEqualTo(1);
        assertThat(context.streaming()).isFalse();
        assertThat(context.structured()).isFalse();
        assertThat(context.callerRef()).isEqualTo("chat:7:3");
        assertThat(context.promptVersion()).isEqualTo(2);
        assertThat(context.inputChars()).isEqualTo("상담원 규칙".length() + "제 번호는 [PHONE] 입니다".length() + "무엇을 도와드릴까요?".length()
            + "[PII] 이름으로 주문했어요".length() + LOOKUP.name().length() + LOOKUP.description().length() + LOOKUP.inputSchemaJson().length());
        assertThat(output.getAll()).contains("ai call completed. feature=cs-chat").doesNotContain(PHONE).doesNotContain(NAME);
    }

    @Test
    @DisplayName("tool_use 응답은 AiToolCall 로 돌아오고, 결과를 붙여 다시 부르면 도구 호출 · 결과가 짝지어 가고 인자 · 결과도 가려진다 — 호출마다 원장 한 행")
    void toolLoop(CapturedOutput output) throws Exception {
        server.reply(okToolUse("toolu_01", "lookup_order", "{\"orderId\":\"42\",\"phone\":\"" + PHONE + "\"}"))
            .reply(ok("배송 중입니다", "end_turn"));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor);
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("주문 42 어디쯤인가요"))).withTools(List.of(LOOKUP));

        AiConversationResult first = client.converse(request);

        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.stopReason()).isEqualTo(AiStopReason.TOOL_USE);
        assertThat(first.text()).isEqualTo("조회해 볼게요.");
        AiToolCall call = first.toolCalls().getFirst();
        assertThat(call.id()).isEqualTo("toolu_01");
        assertThat(call.name()).isEqualTo("lookup_order");
        assertThat(JSON.readTree(call.argumentsJson()).get("orderId").asText()).isEqualTo("42");
        assertThat(first.assistantMessage().contents()).hasSize(2).last().isEqualTo(call);
        assertThat(call.toString()).doesNotContain(PHONE);

        AiConversationRequest next = request.append(first.assistantMessage(),
            AiMessage.toolResults(List.of(AiContent.toolResult(call.id(), "{\"status\":\"SHIPPED\",\"receiverPhone\":\"" + PHONE + "\"}"))));
        AiConversationResult second = client.converse(next);

        assertThat(second.text()).isEqualTo("배송 중입니다");
        assertThat(second.hasToolCalls()).isFalse();
        JsonNode messages = JSON.readTree(server.received().get(1).body()).get("messages");
        assertThat(messages).hasSize(3);
        JsonNode toolUse = messages.get(1).get("content").get(1);
        assertThat(toolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.get("id").asText()).isEqualTo("toolu_01");
        assertThat(toolUse.get("input").get("orderId").asText()).isEqualTo("42");
        assertThat(toolUse.get("input").get("phone").asText()).isEqualTo("[PHONE]");
        JsonNode toolResult = messages.get(2).get("content").get(0);
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("toolu_01");
        assertThat(toolResult.get("is_error").asBoolean()).isFalse();
        assertThat(toolResult.get("content").toString()).contains("[PHONE]").doesNotContain(PHONE);

        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.COMPLETED, Status.COMPLETED);
        assertThat(interceptor.contexts).extracting(AiCallContext::callId).doesNotHaveDuplicates().hasSize(2);
        assertThat(interceptor.handles).containsExactlyElementsOf(interceptor.issued);
        assertThat(output.getAll()).contains("toolCalls=[lookup_order]").doesNotContain(PHONE);
    }

    @Test
    @DisplayName("인자가 스키마 최소 검증에 맞지 않거나 · 없는 도구거나 · 도구 호출이 잘리면 INVALID_RESPONSE(재시도 안 함)이고 UNUSABLE 실측이다")
    void invalidToolCalls(CapturedOutput output) {
        server.reply(okToolUse("toolu_a", "lookup_order", "{\"phone\":\"" + PHONE + "\"}"))
            .reply(okToolUse("toolu_b", "lookup_order", "{\"orderId\":42}"))
            .reply(okToolUse("toolu_c", "lookup_order", "{\"orderId\":\"42\",\"extra\":true}"))
            .reply(okToolUse("toolu_d", "lookup_order", "{\"orderId\":\"42\",\"limit\":1.5}"))
            .reply(okToolUse("toolu_e", "drop_tables", "{}"))
            .reply(okContent("[{\"type\":\"tool_use\",\"id\":\"toolu_f\",\"name\":\"lookup_order\",\"input\":{\"orderId\":\"4\"}}]", "max_tokens", usage(30, 9)));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor);
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("주문 조회"))).withTools(List.of(LOOKUP));

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.converse(request))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        }

        assertThat(Reason.INVALID_RESPONSE.retryable()).isFalse();
        assertThat(interceptor.outcomes).hasSize(6).extracting(AiCallOutcome::status).containsOnly(Status.UNUSABLE);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::usage).containsOnly(new AiUsage(30, 9, 0, 0));
        assertThat(output.getAll()).contains("detail=tool arguments missing required tool=lookup_order", "detail=tool arguments type mismatch",
                "detail=tool arguments unknown property", "detail=unknown tool", "detail=truncated tool call")
            .doesNotContain(PHONE).doesNotContain("drop_tables");
    }

    @Test
    @DisplayName("이력 모양이 틀리면 요청을 만들 때 거절한다 — 보내지 않는다")
    void historyShapeIsCheckedBeforeSending() {
        AiToolCall call = new AiToolCall("toolu_1", "lookup_order", "{\"orderId\":\"1\"}");
        AiMessage assistantWithCall = new AiMessage(AiMessage.Role.ASSISTANT, List.of(call));
        List<List<AiMessage>> invalids = List.of(
            // 결과 id 가 호출과 다르다
            List.of(AiMessage.user("q"), assistantWithCall, AiMessage.toolResults(List.of(AiContent.toolResult("toolu_2", "{}")))),
            // 호출 뒤에 결과 없이 텍스트
            List.of(AiMessage.user("q"), assistantWithCall, AiMessage.user("그냥 답해")),
            // 결과가 둘
            List.of(AiMessage.user("q"), assistantWithCall, AiMessage.toolResults(List.of(AiContent.toolResult("toolu_1", "{}"), AiContent.toolResult("toolu_1", "{}")))),
            // 호출 없이 결과
            List.of(AiMessage.user("q"), AiMessage.assistant("a"), AiMessage.toolResults(List.of(AiContent.toolResult("toolu_1", "{}")))),
            // 결과가 텍스트 뒤
            List.of(AiMessage.user("q"), assistantWithCall, new AiMessage(AiMessage.Role.USER, List.of(AiContent.text("t"), AiContent.toolResult("toolu_1", "{}")))),
            // 마지막이 assistant(미리 채우기)
            List.of(AiMessage.user("q"), AiMessage.assistant("a")),
            // 첫 메시지가 assistant
            List.of(AiMessage.assistant("a"), AiMessage.user("q")),
            // 도구 결과를 assistant 에
            List.of(AiMessage.user("q"), new AiMessage(AiMessage.Role.ASSISTANT, List.of(AiContent.toolResult("toolu_1", "{}"))), AiMessage.user("q")),
            // 비었다
            List.of());
        for (List<AiMessage> messages : invalids) {
            assertThatThrownBy(() -> withLookup(messages)).isInstanceOf(IllegalArgumentException.class).message().doesNotContain("tools 에 없는");
        }
        // tools 에 없는 도구를 부른 이력
        assertThatThrownBy(() -> AiConversationRequest.of("cs-chat", null,
            List.of(AiMessage.user("q"), assistantWithCall, AiMessage.toolResults(List.of(AiContent.toolResult("toolu_1", "{}"))))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lookup_order");
        // 인자가 객체가 아닌 도구 호출 · 규칙 밖 id · 도구 이름
        assertThatThrownBy(() -> new AiToolCall("toolu_1", "lookup_order", "[1]")).isInstanceOf(IllegalArgumentException.class)
            .message().doesNotContain("[1]");
        assertThatThrownBy(() -> new AiToolCall("toolu 1", "lookup_order", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTool("주문조회", "d", "{\"type\":\"object\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTool("t", "d", "{\"type\":\"array\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTool("t", "d", "{\"type\":\"object\",\"required\":\"x\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTool("t", "d", "{\"type\":\"object\"} trailing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("q"))).withTools(List.of(LOOKUP, LOOKUP)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.received()).isEmpty();
    }

    @Test
    @DisplayName("한 턴의 도구 왕복은 MAX_TOOL_ROUNDS_PER_TURN 까지 — 새 사용자 질문이 오면 다시 센다")
    void toolRoundLimit() {
        List<AiMessage> messages = new ArrayList<>(List.of(AiMessage.user("q")));
        for (int round = 0; round < AiConversationRequest.MAX_TOOL_ROUNDS_PER_TURN; round++) {
            messages.add(new AiMessage(AiMessage.Role.ASSISTANT, List.of(new AiToolCall("toolu_" + round, "lookup_order", "{\"orderId\":\"1\"}"))));
            messages.add(AiMessage.toolResults(List.of(AiContent.toolResult("toolu_" + round, "{}"))));
        }
        assertThat(withLookup(messages).messages()).hasSize(17);

        List<AiMessage> over = new ArrayList<>(messages);
        over.add(new AiMessage(AiMessage.Role.ASSISTANT, List.of(new AiToolCall("toolu_x", "lookup_order", "{\"orderId\":\"1\"}"))));
        over.add(AiMessage.toolResults(List.of(AiContent.toolResult("toolu_x", "{}"))));
        assertThatThrownBy(() -> new AiConversationRequest("cs-chat", null, over, List.of(LOOKUP), null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("도구 왕복");

        List<AiMessage> nextTurn = new ArrayList<>(messages);
        nextTurn.add(AiMessage.assistant("답"));
        nextTurn.add(AiMessage.user("다음 질문"));
        nextTurn.add(new AiMessage(AiMessage.Role.ASSISTANT, List.of(new AiToolCall("toolu_y", "lookup_order", "{\"orderId\":\"1\"}"))));
        nextTurn.add(AiMessage.toolResults(List.of(AiContent.toolResult("toolu_y", "{}"))));
        assertThat(new AiConversationRequest("cs-chat", null, nextTurn, List.of(LOOKUP), null, null, null, null).messages()).hasSize(21);
    }

    @Test
    @DisplayName("thinking 블록은 Provider 블록으로 돌아오고, 다음 호출에 서명 그대로 간다")
    void thinkingBlockRoundTrips() throws Exception {
        server.reply(okContent("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-stub-1\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_t\",\"name\":\"lookup_order\",\"input\":{\"orderId\":\"7\"}}]", "tool_use", usage(10, 3)))
            .reply(ok("끝", "end_turn"));
        AiClient client = client(new RecordingInterceptor());
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("q"))).withTools(List.of(LOOKUP));

        AiConversationResult first = client.converse(request);
        assertThat(first.assistantMessage().contents().getFirst()).isInstanceOfSatisfying(AiContent.Opaque.class,
            opaque -> assertThat(opaque.provider()).isEqualTo("anthropic"));
        client.converse(request.append(first.assistantMessage(), AiMessage.toolResults(List.of(AiContent.toolResult("toolu_t", "{}")))));

        JsonNode assistant = JSON.readTree(server.received().get(1).body()).get("messages").get(1).get("content");
        assertThat(assistant.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(assistant.get(0).get("signature").asText()).isEqualTo("sig-stub-1");
        assertThat(assistant.get(1).get("type").asText()).isEqualTo("tool_use");
    }

    @Test
    @DisplayName("거절은 REFUSED · UNUSABLE, 빈 응답은 INVALID_RESPONSE 다")
    void refusalAndEmpty() {
        server.reply(ok("", "refusal", 20, 1, 0, 0)).reply(okContent("[" + textBlock(" ") + "]", "end_turn", usage(20, 1)));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiClient client = client(interceptor);
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("q")));

        assertThatThrownBy(() -> client.converse(request)).isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.REFUSED));
        assertThatThrownBy(() -> client.converse(request))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::status).containsExactly(Status.UNUSABLE, Status.UNUSABLE);
        assertThat(interceptor.outcomes).extracting(AiCallOutcome::reason).containsExactly(Reason.REFUSED, Reason.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("이력 전체 길이가 상한을 넘으면 INPUT_TOO_LONG 이고 before · after · HTTP 가 없다. 꺼져 있으면 DISABLED")
    void inputLimitAndDisabled() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiProperties tight = new AiProperties(true, "test-key", server.baseUrl(), null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, Map.of(), null, 20, true, false);
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null,
            List.of(AiMessage.user("열 글자 질문입니다"), AiMessage.assistant("열 글자 답변입니다"), AiMessage.user("한 번 더")));

        assertThatThrownBy(() -> AiClients.create(tight, List.of(interceptor)).converse(request))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INPUT_TOO_LONG));
        assertThat(interceptor.contexts).isEmpty();
        assertThat(interceptor.outcomes).isEmpty();
        assertThat(server.received()).isEmpty();

        AiClient disabled = AiClients.create(new AiProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> disabled.converse(request)).isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
        assertThatThrownBy(() -> disabled.converseStreaming(request, delta -> true))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
    }

    @Test
    @DisplayName("toString 에는 원문 · 인자가 없다")
    void toStringHasNoContent() {
        AiConversationRequest request = AiConversationRequest.of("cs-chat", "sys " + PHONE, List.of(AiMessage.user(NAME + " " + PHONE)))
            .withTools(List.of(LOOKUP)).withSensitiveValues(List.of(NAME));
        AiConversationResult result = new AiConversationResult(NAME, List.of(new AiToolCall("toolu_1", "lookup_order", "{\"p\":\"" + PHONE + "\"}")),
            null, AiStopReason.TOOL_USE, "m", "id", AiUsage.ZERO);

        assertThat(request.toString()).doesNotContain(PHONE).doesNotContain(NAME).contains("lookup_order");
        assertThat(result.toString()).doesNotContain(PHONE).doesNotContain(NAME).contains("lookup_order");
        assertThat(AiMessage.user(NAME).toString()).doesNotContain(NAME);
        assertThat(AiContent.toolResult("toolu_1", PHONE).toString()).doesNotContain(PHONE);
    }

    @Test
    @DisplayName("위조한 Provider 블록(text · tool_result · image 종류 · JSON 아님 · 길이 초과)은 만들 때 거절한다 — 마스킹 · 짝 검사를 피하는 통로가 되지 않는다")
    void forgedOpaqueIsRejected() {
        List<String> forged = List.of(
            "{\"type\":\"text\",\"text\":\"" + PHONE + "\"}",
            "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_x\",\"content\":\"" + PHONE + "\"}",
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AA==\"}}",
            "{\"thinking\":\"\",\"signature\":\"s\"}",
            "not json",
            "{\"type\":\"thinking\",\"thinking\":\"" + "a".repeat(AiContent.Opaque.MAX_PAYLOAD_CHARS) + "\",\"signature\":\"s\"}");
        for (String payload : forged) {
            assertThatThrownBy(() -> new AiContent.Opaque("anthropic", payload)).isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain(PHONE);
        }
        assertThatThrownBy(() -> new AiContent.Opaque("anthropic provider", "{\"type\":\"thinking\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AiContent.Opaque("anthropic", "{\"type\":\"redacted_thinking\",\"data\":\"x\"}").provider()).isEqualTo("anthropic");
        assertThat(server.received()).isEmpty();
    }

    @Test
    @DisplayName("다른 Provider 의 블록은 보내지 않고 버리되 입력 길이 · 예상치에는 센다")
    void otherProviderOpaqueIsDropped() throws Exception {
        server.reply(ok("네", "end_turn"));
        RecordingInterceptor interceptor = new RecordingInterceptor();
        String payload = "{\"type\":\"reasoning\",\"encrypted\":\"zz\"}";
        AiConversationRequest request = AiConversationRequest.of("cs-chat", null, List.of(AiMessage.user("질문"),
            new AiMessage(AiMessage.Role.ASSISTANT, List.of(new AiContent.Opaque("openai", payload), AiContent.text("답"))), AiMessage.user("또 질문")));

        client(interceptor).converse(request);

        JsonNode assistant = JSON.readTree(server.received().getFirst().body()).get("messages").get(1).get("content");
        assertThat(assistant).hasSize(1);
        assertThat(assistant.get(0).get("type").asText()).isEqualTo("text");
        assertThat(server.received().getFirst().body()).doesNotContain("encrypted");
        assertThat(interceptor.single().status()).isEqualTo(Status.COMPLETED);
        assertThat(interceptor.contexts.getFirst().inputChars()).isEqualTo("질문".length() + payload.length() + "답".length() + "또 질문".length());
    }

    @Test
    @DisplayName("요청의 maxTokens 는 기능 설정을 넘을 수 없다 — 보내지 않는다")
    void maxTokensCannotExceedFeature() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        AiConversationRequest request = new AiConversationRequest("cs-chat", null, List.of(AiMessage.user("q")), null, null, null, null,
            AiProperties.DEFAULT_MAX_TOKENS_VALUE + 1);

        assertThatThrownBy(() -> client(interceptor).converse(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-tokens");
        assertThat(interceptor.contexts).isEmpty();
        assertThat(server.received()).isEmpty();
    }

    private static AiConversationRequest withLookup(List<AiMessage> messages) {
        return new AiConversationRequest("cs-chat", null, messages, List.of(LOOKUP), null, null, null, null);
    }

    private AiClient client(RecordingInterceptor interceptor) {
        AiProperties properties = new AiProperties(true, "test-key", server.baseUrl(), null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, Map.of(), null, null, true, false);
        return AiClients.create(properties, List.of(interceptor));
    }
}
