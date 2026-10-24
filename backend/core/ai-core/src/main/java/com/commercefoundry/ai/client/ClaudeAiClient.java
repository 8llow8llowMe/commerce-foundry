package com.commercefoundry.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlock;
import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.ResolvedFeature;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 공식 Java SDK 로 Messages API 를 부른다. 순서가 고정이다 — 길이 상한 → 마스킹 → 동시 상한 자리 얻기 → 호출(SDK 재시도 포함) → 결과 · 오류 번역.
 * 마스킹은 길이에 비례하는 시간만 쓰고({@code PiiMasker}), 길이 상한이 그 앞에서 입력 크기를 묶는다. 텍스트 · 구조화 호출이 같은 순서를 지난다.
 *
 * <ul>
 *   <li><b>재시도</b>는 SDK 가 한다({@code ai.max-retries}, 408 · 409 · 429 · 5xx · 연결 오류, {@code retry-after} 존중). 여기서 다시 감싸지 않는다.</li>
 *   <li><b>동시 상한</b>은 프로세스 단위 세마포어다. 자리를 {@code ai.acquire-timeout} 안에 못 얻으면 {@link Reason#BUSY} — 호출하지 않는다.</li>
 *   <li><b>구조화 출력</b>은 API 의 JSON 출력({@code output_config.format})이다 — 스키마는 SDK 가 결과 타입에서 만들고(타입마다 한 번, 캐시) 응답은 SDK 가 파싱한다.
 *       Opus 5.5 는 강제 도구 호출({@code tool_choice} any/tool)을 400 으로 거절하므로 도구 방식을 쓰지 않는다.</li>
 *   <li><b>거절 대체</b>({@code ai.refusal-fallback})는 API 의 server-side fallback 을 켠다. 대체 모델까지 거절하면 {@link Reason#REFUSED} 다.</li>
 *   <li><b>로그</b>에는 기능 · 프롬프트 버전 · 모델 · 토큰 수 · 멈춘 이유 · 상태 코드만 남긴다. 프롬프트 · 응답 원문 · SDK 예외 메시지 · 검증 문제 설명은
 *       어떤 레벨에도 남기지 않는다 ({@code coding-conventions.md} §8). SDK 의 자체 요청 로그는 {@code AiClients} 가 끈다.</li>
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
    /** 결과 타입 → SDK 가 만든 JSON 스키마 형식. 스키마 생성 · 지원 여부 검사는 타입마다 한 번만 한다. */
    private final Map<Class<?>, JsonOutputFormat> formats = new ConcurrentHashMap<>();

    ClaudeAiClient(AnthropicClient client, AiProperties properties, PiiMasker masker) {
        this.client = client;
        this.properties = properties;
        this.masker = masker;
        this.permits = new Semaphore(properties.maxConcurrency(), true);
    }

    @Override
    public AiResult complete(AiRequest request) {
        return call(request, null, (message, stopReason) -> {
            String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
            if (text.isEmpty()) {
                throw new AiException(Reason.INVALID_RESPONSE, request.feature());
            }
            return new AiResult(text, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens(), stopReason, message.id());
        });
    }

    @Override
    public <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator) {
        JsonOutputFormat format = formatOf(type);
        return call(request, format, (message, stopReason) -> {
            // 잘린 JSON 은 파싱할 수 없다 — 부분 결과를 쓰지 않는다
            if (AiResult.STOP_MAX_TOKENS.equals(stopReason)) {
                throw invalid(request.feature(), "truncated");
            }
            List<TextBlock> blocks = message.content().stream().flatMap(block -> block.text().stream()).toList();
            if (blocks.size() != 1) {
                throw invalid(request.feature(), "blocks=" + blocks.size());
            }
            T value;
            try {
                value = new StructuredTextBlock<>(type, blocks.getFirst()).text();
            } catch (RuntimeException e) {
                // SDK 의 파싱 예외 메시지에는 응답 조각이 들어 있다 — 타입만 남긴다
                throw invalid(request.feature(), "parse " + e.getClass().getSimpleName());
            }
            List<String> problems = validator.validate(value);
            if (value == null || (problems != null && !problems.isEmpty())) {
                // 문제 설명은 응답 값을 품을 수 있어 개수만 남긴다
                throw invalid(request.feature(), "validation problems=" + (problems == null ? 0 : problems.size()));
            }
            return new AiStructuredResult<>(value, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens(),
                stopReason, message.id());
        });
    }

    @Override
    public void close() {
        client.close();
    }

    /** 공통 순서. {@code mapper} 가 던진 {@link AiException} 은 그대로 나간다. */
    private <R> R call(AiRequest request, JsonOutputFormat format, BiFunction<Message, String, R> mapper) {
        String feature = request.feature();
        long inputChars = (request.system() == null ? 0L : request.system().length()) + request.prompt().length();
        if (inputChars > properties.maxInputChars()) {
            log.warn("ai call rejected. feature={}, reason={}, inputChars={}, maxInputChars={}", feature, Reason.INPUT_TOO_LONG, inputChars, properties.maxInputChars());
            throw new AiException(Reason.INPUT_TOO_LONG, feature);
        }
        MessageCreateParams params = params(request, properties.resolve(feature), format);

        acquire(feature);
        long startedAt = System.nanoTime();
        try {
            Message message = client.messages().create(params);
            String stopReason = message.stopReason().map(StopReason::asString).orElse(null);
            if (StopReason.REFUSAL.asString().equals(stopReason)) {
                throw new AiException(Reason.REFUSED, feature);
            }
            R result = mapper.apply(message, stopReason);
            log.info("ai call completed. feature={}, promptVersion={}, structured={}, model={}, inputTokens={}, outputTokens={}, stopReason={}, elapsedMs={}",
                feature, request.promptVersion(), format != null, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens(),
                stopReason, elapsedMs(startedAt));
            return result;
        } catch (AiException e) {
            log.warn("ai call failed. feature={}, promptVersion={}, reason={}, elapsedMs={}", feature, request.promptVersion(), e.reason(), elapsedMs(startedAt));
            throw e;
        } catch (AnthropicServiceException e) {
            throw failed(request, reasonOf(e.statusCode()), e.statusCode(), e.errorType().map(Object::toString).orElse(null), startedAt);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw failed(request, Reason.UNAVAILABLE, null, null, startedAt);
        } catch (AnthropicException e) {
            // 응답을 해석하지 못했다(AnthropicInvalidDataException 등)
            throw failed(request, Reason.INVALID_RESPONSE, null, null, startedAt);
        } finally {
            permits.release();
        }
    }

    /** 마스킹은 여기서 한다 — 실패하면 보내지 않는다. 원인 예외는 잇지 않는다(메시지에 입력 조각이 들어갈 수 있다). */
    private MessageCreateParams params(AiRequest request, ResolvedFeature settings, JsonOutputFormat format) {
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
        if (settings.effort() != null || format != null) {
            OutputConfig.Builder output = OutputConfig.builder();
            if (settings.effort() != null) {
                output.effort(OutputConfig.Effort.of(settings.effort()));
            }
            if (format != null) {
                output.format(format);
            }
            builder.outputConfig(output.build());
        }
        if (properties.refusalFallback()) {
            builder.putAdditionalHeader(BETA_HEADER, FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    /**
     * SDK 가 결과 타입에서 JSON 스키마를 만들고 API 가 받는 형태인지 로컬에서 검사한다(지원하지 않는 제약이면 예외 — 호스트 코드의 결함이다).
     * SDK 의 타입 빌더는 {@code output_config} 전체를 바꿔 effort 를 지우므로, 만든 형식만 꺼내 effort 와 함께 다시 싣는다.
     */
    private JsonOutputFormat formatOf(Class<?> type) {
        return formats.computeIfAbsent(type, key -> MessageCreateParams.builder()
            .model(properties.defaultModel()).maxTokens(1).addUserMessage("schema")
            .outputConfig(key)
            .build()
            .rawParams().outputConfig().flatMap(OutputConfig::format)
            .orElseThrow(() -> new IllegalStateException("SDK 가 결과 타입의 JSON 스키마를 만들지 않았습니다: " + key.getName())));
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

    /** 구조화 결과가 쓸 수 없다. {@code detail} 은 원인 구분만이다(응답 값 없음). */
    private static AiException invalid(String feature, String detail) {
        log.warn("ai structured output rejected. feature={}, detail={}", feature, detail);
        return new AiException(Reason.INVALID_RESPONSE, feature);
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
    private static AiException failed(AiRequest request, Reason reason, Integer statusCode, String errorType, long startedAt) {
        log.warn("ai call failed. feature={}, promptVersion={}, reason={}, status={}, errorType={}, elapsedMs={}",
            request.feature(), request.promptVersion(), reason, statusCode, errorType, elapsedMs(startedAt));
        return new AiException(reason, request.feature(), statusCode, errorType);
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
