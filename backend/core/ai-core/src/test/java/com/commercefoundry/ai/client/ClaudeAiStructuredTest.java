package com.commercefoundry.ai.client;

import static com.commercefoundry.ai.support.StubMessagesServer.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.prompt.PromptTemplate;
import com.commercefoundry.ai.prompt.PromptValues;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Feature;
import com.commercefoundry.ai.support.StubMessagesServer;
import java.io.IOException;
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

/**
 * 구조화 출력을 루프백 스텁으로 SDK 를 거쳐 본다 — 스키마가 요청에 실리고, 응답을 파싱 · 검증하고, 쓸 수 없는 결과는 INVALID_RESPONSE 다.
 * 픽스처 값은 가짜다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClaudeAiStructuredTest {

    /** 사이즈표 — 결과 타입 예. */
    record SizeChart(List<Row> rows) {

    }

    record Row(String size, int chestCm, int lengthCm) {

    }

    private static final String VALID = "{\"rows\":[{\"size\":\"M\",\"chestCm\":102,\"lengthCm\":68},{\"size\":\"L\",\"chestCm\":108,\"lengthCm\":70}]}";
    private static final AiOutputValidator<SizeChart> POSITIVE = chart -> {
        List<String> problems = new ArrayList<>();
        chart.rows().forEach(row -> {
            if (row.chestCm() <= 0 || row.lengthCm() <= 0) {
                problems.add("치수가 양수가 아니다: " + row.size() + " ValidationProbe");
            }
        });
        return problems;
    };

    private StubMessagesServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new StubMessagesServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @DisplayName("결과 타입에서 만든 JSON 스키마와 기능 effort 가 함께 실리고, 응답을 파싱 · 검증해 돌려준다")
    void parsesAndValidates() {
        server.reply(ok(VALID, "end_turn"));

        AiStructuredResult<SizeChart> result = client(Map.of("size-chart", new Feature(null, 2000, "low"))).complete(
            AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class, POSITIVE);

        assertThat(result.value().rows()).extracting(Row::size).containsExactly("M", "L");
        assertThat(result.value().rows().getFirst().chestCm()).isEqualTo(102);
        assertThat(result.stopReason()).isEqualTo("end_turn");
        String body = server.received().getFirst().body();
        assertThat(body).contains("\"output_config\"").contains("\"json_schema\"").contains("\"chestCm\"").contains("\"effort\":\"low\"");
    }

    @Test
    @DisplayName("업무 검증에 실패하면 INVALID_RESPONSE(재시도 안 함)이고 문제 설명은 로그에 남지 않는다")
    void validationFailure(CapturedOutput output) {
        server.reply(ok("{\"rows\":[{\"size\":\"M\",\"chestCm\":-1,\"lengthCm\":68}]}", "end_turn"));

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class, POSITIVE));
        assertThat(output.getAll()).contains("ai structured output rejected. feature=size-chart, detail=validation problems=1")
            .doesNotContain("ValidationProbe").doesNotContain("chestCm");
    }

    @Test
    @DisplayName("validator 가 예외를 던지면 INVALID_RESPONSE 로 감싼다 — 예외 메시지(응답 값)는 로그에 없고 AiException 계약 밖으로 나가지 않는다")
    void validatorException(CapturedOutput output) {
        server.reply(ok(VALID, "end_turn"));
        AiOutputValidator<SizeChart> throwing = chart -> {
            throw new IllegalArgumentException("invalid size ValidatorLeakProbe " + chart.rows().getFirst().size());
        };

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class, throwing));
        assertThat(output.getAll()).contains("detail=validator IllegalArgumentException").doesNotContain("ValidatorLeakProbe");
    }

    @Test
    @DisplayName("validator 가 null 을 돌려주면 통과가 아니라 INVALID_RESPONSE 다")
    void validatorReturningNull() {
        server.reply(ok(VALID, "end_turn"));

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class, chart -> null));
    }

    @Test
    @DisplayName("JSON null 응답은 validator 를 부르기 전에 INVALID_RESPONSE 다")
    void nullValue() {
        server.reply(ok("null", "end_turn"));
        AiOutputValidator<SizeChart> mustNotRun = chart -> {
            throw new AssertionError("validator 가 불리면 안 된다");
        };

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class, mustNotRun));
    }

    @Test
    @DisplayName("최대 토큰에서 잘린 JSON 은 쓰지 않는다 — INVALID_RESPONSE")
    void truncated() {
        server.reply(ok("{\"rows\":[{\"size\":\"M\"", "max_tokens"));

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class));
    }

    @Test
    @DisplayName("파싱할 수 없는 응답은 INVALID_RESPONSE 이고 응답 조각이 로그 · 예외에 없다")
    void unparsable(CapturedOutput output) {
        server.reply(ok("not json ResponseLeakProbe", "end_turn"));

        assertInvalid(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class));
        assertThat(output.getAll()).doesNotContain("ResponseLeakProbe");
    }

    @Test
    @DisplayName("거절하면 REFUSED 다 — 스키마와 맞지 않을 수 있어 파싱하지 않는다")
    void refusal() {
        server.reply(ok("", "refusal"));

        assertThatThrownBy(() -> client(Map.of()).complete(AiRequest.of("size-chart", null, "치수 정리"), SizeChart.class))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.REFUSED));
    }

    @Test
    @DisplayName("템플릿으로 만든 요청은 개인정보 자리 값이 가려진 채 나가고, 로그에 프롬프트 버전이 남는다")
    void templateEndToEnd(CapturedOutput output) {
        server.reply(ok(VALID, "end_turn"));
        PromptTemplate template = new PromptTemplate("cs-reply", 2, null, "고객 {{pii:customerName}} 님 문의: {{inquiry}}");

        client(Map.of()).complete(template.render(PromptValues.create().putSensitive("customerName", "Hong Gildong").put("inquiry", "사이즈")),
            SizeChart.class);

        assertThat(server.received().getFirst().body()).contains("[PII]").doesNotContain("Hong Gildong");
        assertThat(output.getAll()).contains("promptVersion=2").doesNotContain("Hong Gildong");
    }

    @Test
    @DisplayName("결과의 toString 에 값이 없고, 꺼져 있으면 DISABLED 다")
    void toStringAndDisabled() {
        AiStructuredResult<SizeChart> result = new AiStructuredResult<>(new SizeChart(List.of(new Row("ResponseLeakProbe", 1, 1))), "m", 1, 1, "end_turn", "id");
        assertThat(result.toString()).doesNotContain("ResponseLeakProbe").contains("SizeChart");

        AiClient disabled = AiClients.create(new AiProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> disabled.complete(AiRequest.of("size-chart", null, "p"), SizeChart.class))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
    }

    private AiClient client(Map<String, Feature> features) {
        return AiClients.create(new AiProperties(true, "test-key", server.baseUrl(), null, null,
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), 0, 4, Duration.ofSeconds(1), false, features, null, null, true, true));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AiException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE);
            assertThat(e.reason().retryable()).isFalse();
            assertThat(e.getCause()).isNull();
        });
    }
}
