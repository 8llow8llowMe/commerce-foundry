package com.commercefoundry.ai.client;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Violation;
import java.util.List;
import java.util.Objects;

/** {@link AiClient} 를 만든다. 구현은 패키지 밖에 드러내지 않는다 — SDK 타입이 서비스로 새지 않게. */
public final class AiClients {

    private AiClients() {
    }

    /**
     * 켜져 있으면 Claude API 클라이언트, 꺼져 있으면 언제나 {@code DISABLED} 인 클라이언트다.
     *
     * <p>{@code AiStartupGuard} 와 같은 규칙({@link AiProperties#violations()})을 여기서도 본다 — 공개 메서드라 가드를 거치지 않고 부를 수 있다.
     * SDK 클라이언트는 키를 명시해 만든다 — 환경변수({@code ANTHROPIC_API_KEY}) · 로그인 프로필을 스스로 찾지 않게.
     *
     * @throws IllegalArgumentException 규칙에 어긋나면. 메시지에는 프로퍼티 이름과 설명만 있고 설정값은 없다
     */
    public static AiClient create(AiProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<Violation> violations = properties.violations();
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("AI 설정이 규칙에 맞지 않습니다 (bootstrap-conventions.md AI)\n- "
                + String.join("\n- ", violations.stream().map(Violation::message).toList()));
        }
        if (!properties.enabled()) {
            return new DisabledAiClient();
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
            .apiKey(properties.apiKey())
            .maxRetries(properties.maxRetries())
            .timeout(Timeout.builder()
                .connect(properties.connectTimeout())
                .read(properties.readTimeout())
                .write(properties.readTimeout())
                .request(properties.requestTimeout())
                .build());
        if (properties.baseUrl() != null) {
            builder.baseUrl(properties.baseUrl());
        }
        return new ClaudeAiClient(builder.build(), properties, new PiiMasker(properties.masking().patterns()));
    }
}
