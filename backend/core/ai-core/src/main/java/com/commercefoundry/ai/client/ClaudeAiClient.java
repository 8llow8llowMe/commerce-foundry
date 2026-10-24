package com.commercefoundry.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.ResolvedFeature;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 공식 Java SDK 로 Messages API 를 부른다. 순서가 고정이다 — 길이 상한 → 마스킹 → 동시 상한 자리 얻기 → 호출(SDK 재시도 포함) → 결과 · 오류 번역.
 * 마스킹은 길이에 비례하는 시간만 쓰고({@code PiiMasker}), 길이 상한이 그 앞에서 입력 크기를 묶는다.
 *
 * <ul>
 *   <li><b>재시도</b>는 SDK 가 한다({@code ai.max-retries}, 408 · 409 · 429 · 5xx · 연결 오류, {@code retry-after} 존중). 여기서 다시 감싸지 않는다.</li>
 *   <li><b>동시 상한</b>은 프로세스 단위 세마포어다. 자리를 {@code ai.acquire-timeout} 안에 못 얻으면 {@link Reason#BUSY} — 호출하지 않는다.</li>
 *   <li><b>거절 대체</b>({@code ai.refusal-fallback})는 API 의 server-side fallback 을 켠다. 대체 모델까지 거절하면 {@link Reason#REFUSED} 다.</li>
 *   <li><b>로그</b>에는 기능 · 모델 · 토큰 수 · 멈춘 이유 · 상태 코드만 남긴다. 프롬프트 · 응답 원문 · SDK 예외 메시지는 어떤 레벨에도 남기지 않는다
 *       ({@code coding-conventions.md} §8). SDK 의 자체 요청 로그는 {@code AiClients} 가 끈다 — {@code ANTHROPIC_LOG} 환경변수가 있어도 본문이 stderr 로 나가지 않는다.</li>
 * </ul>
 */
@Slf4j
final class ClaudeAiClient implements AiClient, AutoCloseable {

    /** server-side fallback 베타. {@code fallbacks: "default"} 는 거절 분류에 맞춰 API 가 대체 모델을 고른다. */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final String BETA_HEADER = "anthropic-beta";

    private final AnthropicClient client;
    private final AiProperties properties;
    private final PiiMasker masker;
    private final Semaphore permits;

    ClaudeAiClient(AnthropicClient client, AiProperties properties, PiiMasker masker) {
        this.client = client;
        this.properties = properties;
        this.masker = masker;
        this.permits = new Semaphore(properties.maxConcurrency(), true);
    }

    @Override
    public AiResult complete(AiRequest request) {
        String feature = request.feature();
        long inputChars = (request.system() == null ? 0L : request.system().length()) + request.prompt().length();
        if (inputChars > properties.maxInputChars()) {
            log.warn("ai call rejected. feature={}, reason={}, inputChars={}, maxInputChars={}", feature, Reason.INPUT_TOO_LONG, inputChars, properties.maxInputChars());
            throw new AiException(Reason.INPUT_TOO_LONG, feature);
        }
        ResolvedFeature settings = properties.resolve(feature);
        MessageCreateParams params = params(request, settings);

        acquire(feature);
        long startedAt = System.nanoTime();
        try {
            AiResult result = toResult(client.messages().create(params), feature);
            log.info("ai call completed. feature={}, model={}, inputTokens={}, outputTokens={}, stopReason={}, elapsedMs={}",
                feature, result.model(), result.inputTokens(), result.outputTokens(), result.stopReason(), elapsedMs(startedAt));
            return result;
        } catch (AnthropicServiceException e) {
            throw failed(feature, reasonOf(e.statusCode()), e.statusCode(), e.errorType().map(Object::toString).orElse(null), startedAt);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw failed(feature, Reason.UNAVAILABLE, null, null, startedAt);
        } catch (AnthropicException e) {
            // 응답을 해석하지 못했다(AnthropicInvalidDataException 등)
            throw failed(feature, Reason.INVALID_RESPONSE, null, null, startedAt);
        } finally {
            permits.release();
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** 마스킹은 여기서 한다 — 실패하면 보내지 않는다. 원인 예외는 잇지 않는다(메시지에 입력 조각이 들어갈 수 있다). */
    private MessageCreateParams params(AiRequest request, ResolvedFeature settings) {
        String system;
        String prompt;
        try {
            system = masker.mask(request.system(), request.sensitiveValues());
            prompt = masker.mask(request.prompt(), request.sensitiveValues());
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("ai masking failed. feature={}, cause={}", request.feature(), e.getClass().getName());
            throw new AiException(Reason.MASKING_FAILED, request.feature());
        }

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model(settings.model())
            .maxTokens(settings.maxTokens())
            .addUserMessage(prompt);
        if (system != null && !system.isBlank()) {
            builder.system(system);
        }
        if (settings.effort() != null) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort())).build());
        }
        if (properties.refusalFallback()) {
            builder.putAdditionalHeader(BETA_HEADER, FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    private void acquire(String feature) {
        try {
            if (!permits.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("ai call rejected. feature={}, reason={}, maxConcurrency={}", feature, Reason.BUSY, properties.maxConcurrency());
                throw new AiException(Reason.BUSY, feature);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Reason.BUSY, feature);
        }
    }

    private static AiResult toResult(Message message, String feature) {
        String stopReason = message.stopReason().map(StopReason::asString).orElse(null);
        if (StopReason.REFUSAL.asString().equals(stopReason)) {
            throw new AiException(Reason.REFUSED, feature);
        }
        String text = message.content().stream()
            .flatMap(block -> block.text().stream())
            .map(TextBlock::text)
            .collect(Collectors.joining());
        if (text.isEmpty()) {
            throw new AiException(Reason.INVALID_RESPONSE, feature);
        }
        return new AiResult(text, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens(), stopReason, message.id());
    }

    /** 상태 코드로 나눈다. 529(과부하)는 5xx 로 함께 본다. */
    static Reason reasonOf(int statusCode) {
        if (statusCode == 429) {
            return Reason.RATE_LIMITED;
        }
        if (statusCode == 401 || statusCode == 402 || statusCode == 403) {
            return Reason.UNAUTHORIZED;
        }
        if (statusCode >= 500 || statusCode == 408 || statusCode == 409) {
            return Reason.UNAVAILABLE;
        }
        return Reason.REJECTED;
    }

    /** 실패 로그는 원인 구분 · 상태만 — SDK 예외(메시지에 API 오류 본문)는 넘기지 않는다. */
    private static AiException failed(String feature, Reason reason, Integer statusCode, String errorType, long startedAt) {
        log.warn("ai call failed. feature={}, reason={}, status={}, errorType={}, elapsedMs={}", feature, reason, statusCode, errorType, elapsedMs(startedAt));
        return new AiException(reason, feature, statusCode, errorType);
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
