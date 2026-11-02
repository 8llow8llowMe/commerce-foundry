package com.commercefoundry.ai.client;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** {@link AiClient} 를 만든다. 구현은 패키지 밖에 드러내지 않는다 — SDK 타입이 서비스로 새지 않게. */
public final class AiClients {

    private AiClients() {
    }

    /** 계량 인터셉터 없이 만든다 — 켜져 있으면 {@code ai.allow-unmetered=true} 여야 한다. {@link #create(AiProperties, List)} 를 본다. */
    public static AiClient create(AiProperties properties) {
        return create(properties, List.of());
    }

    /**
     * 켜져 있으면 Claude API 클라이언트, 꺼져 있으면 언제나 {@code DISABLED} 인 클라이언트다.
     *
     * <p>{@code AiStartupGuard} 와 같은 규칙({@link AiProperties#violations()} · {@link AiProperties#meteringViolation(int)})을 여기서도 본다
     * — 공개 메서드라 가드를 거치지 않고 부를 수 있다. SDK 클라이언트는 키를 명시해 만든다 — 환경변수({@code ANTHROPIC_API_KEY}) · 로그인 프로필을 스스로 찾지 않게.
     *
     * @param interceptors 계량 인터셉터. 켜져 있으면 하나({@code allow-unmetered} 면 0 도 된다), 꺼져 있으면 무시한다
     * @throws IllegalArgumentException 규칙에 어긋나면. 메시지에는 프로퍼티 이름과 설명만 있고 설정값은 없다
     */
    public static AiClient create(AiProperties properties, List<AiCallInterceptor> interceptors) {
        return create(properties, interceptors, (p, m, i) -> {
            java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(p.maxConcurrency(), true);
            java.util.Map<String, AiClient> clients = new java.util.LinkedHashMap<>();
            try {
                for (String name : p.usedProviders()) {
                    clients.put(name, "anthropic".equals(name) ? new ClaudeAiProvider(permits).create(p, m, i)
                        : new OpenAiClient(p, m, i, permits));
                }
                return new RoutedAiClient(p, clients);
            } catch (RuntimeException | Error failure) {
                clients.values().forEach(RoutedAiClient::closeClient);
                throw failure;
            }
        });
    }

    /** Provider 선택은 내부 책임이다. 공통 가드는 Provider 를 만들기 전에 반드시 거친다. */
    static AiClient create(AiProperties properties, List<AiCallInterceptor> interceptors, AiProvider provider) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(interceptors, "interceptors");
        Objects.requireNonNull(provider, "provider");
        List<Violation> violations = new ArrayList<>(properties.violations());
        properties.meteringViolation(interceptors.size()).ifPresent(violations::add);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("AI 설정이 규칙에 맞지 않습니다 (bootstrap-conventions.md AI)\n- "
                + String.join("\n- ", violations.stream().map(Violation::message).toList()));
        }
        if (!properties.enabled()) {
            return new DisabledAiClient();
        }
        return Objects.requireNonNull(provider.create(properties, new PiiMasker(properties.masking().patterns()),
            interceptors.isEmpty() ? null : interceptors.getFirst()), "provider client");
    }
}
