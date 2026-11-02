package com.commercefoundry.ai.client;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.LogLevel;
import com.anthropic.core.Timeout;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.properties.AiProperties;

/** 기존 Claude SDK 생성 설정을 보존하는 Provider. 서비스에서 직접 만들지 않는다. */
final class ClaudeAiProvider implements AiProvider {

    @Override
    public AiClient create(AiProperties properties, PiiMasker masker, AiCallInterceptor interceptor) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
            // SDK 빌더는 fromEnv() 없이도 ANTHROPIC_LOG 를 읽는다. 요청 · 응답 본문이 stderr 로 나가지 않게 명시적으로 끈다.
            .logLevel(LogLevel.OFF)
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
        return new ClaudeAiClient(builder.build(), properties, masker, interceptor);
    }
}
