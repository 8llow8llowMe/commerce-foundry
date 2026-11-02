package com.commercefoundry.global.config;

import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.global.properties.AiUsageProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;

/**
 * AI 를 켰는데 원장이 비용을 계산 · 회수할 수 없으면 기동을 멈춘다 (#435, {@code bootstrap-conventions.md} AI operations 연결).
 *
 * <ul>
 *   <li>월 한도 · 단가: {@code AiUsageProperties} 는 AI 가 꺼진 배포에서 값을 강요하지 않으려고 비어 있어도 바인딩된다. 그대로 켜면 모든 예약이
 *       {@code IllegalStateException} → {@code METERING_FAILED}(재시도)가 되어 작업이 재시도만 하다 끝난다 — 배포 때 드러나게 여기서 막는다.</li>
 *   <li>회수 시각: {@code operations.ai.reservation-sweep.stale-after} 가 호출 하나의 최악 시간보다 짧으면 진행 중인 호출을 회수해, 늦게 온 정산이
 *       반영되지 않는다(실측 대신 예상치가 비용으로 남는다). 최악 시간 = (1 + {@code ai.max-retries}) × {@code ai.request-timeout} + {@code ai.acquire-timeout}
 *       + SDK 재시도 백오프. 백오프는 SDK 가 정하고 {@code retry-after} 를 따르므로 정확한 상한을 계산하지 않고 {@link #BACKOFF_MARGIN} 의 여유를 요구한다.</li>
 * </ul>
 *
 * <p>꺼져 있으면 아무것도 보지 않는다. 메시지에는 키 이름만 싣는다(값 없음). ai-core 자체 설정(키 · 타임아웃 존재)은 {@code AiStartupGuard} 가 먼저 본다.
 */
@Configuration
public class AiMeteringReadinessConfig {

    /** SDK 재시도 백오프 · {@code retry-after} 대기에 남겨 둘 여유. 기본 설정(재시도 2)의 SDK 백오프는 수 초이고 {@code retry-after} 가 길어도 1분이면 덮는다. */
    static final Duration BACKOFF_MARGIN = Duration.ofMinutes(1);

    public AiMeteringReadinessConfig(AiProperties aiProperties, AiUsageProperties aiUsageProperties) {
        List<String> problems = problems(aiProperties, aiUsageProperties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("ai.enabled=true 인데 AI 사용량 원장 설정이 맞지 않습니다 (bootstrap-conventions.md AI)\n- " + String.join("\n- ", problems));
        }
    }

    static List<String> problems(AiProperties aiProperties, AiUsageProperties aiUsageProperties) {
        List<String> problems = new ArrayList<>();
        if (!aiProperties.enabled()) {
            return problems;
        }
        if (aiUsageProperties.budget().limitMicros().isEmpty()) {
            problems.add("operations.ai.budget.monthly-usd(환경변수 AI_MONTHLY_BUDGET_USD) 가 비었습니다 — 월 한도 없이 예약할 수 없습니다");
        }
        if (aiUsageProperties.pricing().models().isEmpty()) {
            problems.add("operations.ai.pricing.models 가 비었습니다 — 단가 없이 비용을 계산할 수 없습니다");
        }
        java.util.Set<String> openAiModels = new java.util.HashSet<>();
        if ("openai".equals(aiProperties.provider())) openAiModels.add(aiProperties.openai().model());
        aiProperties.features().keySet().stream().filter(name -> "openai".equals(aiProperties.providerFor(name)))
            .forEach(name -> openAiModels.add(aiProperties.resolve(name).model()));
        if (openAiModels.stream().filter(java.util.Objects::nonNull).anyMatch(model -> !aiUsageProperties.pricing().models().containsKey(model))) {
            problems.add("operations.ai.pricing.models requires an explicit price entry for every selected OpenAI model");
        }
        Duration worstCall = worstCallDuration(aiProperties);
        if (worstCall != null && aiUsageProperties.reservationSweep().staleAfter().compareTo(worstCall.plus(BACKOFF_MARGIN)) <= 0) {
            problems.add("operations.ai.reservation-sweep.stale-after 는 (1 + ai.max-retries) × ai.request-timeout + ai.acquire-timeout + 재시도 백오프 여유("
                + BACKOFF_MARGIN.toMinutes() + "분)보다 길어야 합니다 — 짧으면 진행 중인 호출을 회수합니다");
        }
        return problems;
    }

    /** 호출 하나가 예약 뒤 정산까지 걸릴 수 있는 시간(백오프 제외). 타임아웃이 비었으면 {@code null} — 그 위반은 ai-core 기동 가드가 먼저 알린다. */
    static Duration worstCallDuration(AiProperties aiProperties) {
        if (aiProperties.requestTimeout() == null || aiProperties.acquireTimeout() == null) {
            return null;
        }
        return aiProperties.requestTimeout().multipliedBy(1L + Math.max(0, aiProperties.maxRetries())).plus(aiProperties.acquireTimeout());
    }
}
