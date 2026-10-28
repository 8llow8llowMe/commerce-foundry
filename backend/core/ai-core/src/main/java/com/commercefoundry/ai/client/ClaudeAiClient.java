package com.commercefoundry.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.properties.AiProperties.ResolvedFeature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 공식 Java SDK 로 Messages API 를 부른다. 순서가 고정이다 — 글자 · 이미지 장수 상한 → 마스킹 → 계량 {@code before} → 동시 상한 자리 얻기 → 호출(SDK 재시도 포함)
 * → 결과 · 오류 번역 → 자리 반납 → 계량 {@code after}({@link AiCallInterceptor}).
 * 이미지는 사용자 메시지 앞의 base64 블록이다. 바이트는 가리지 못하고 로그에 싣지 않는다.
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
    private final AiCallInterceptor interceptor;
    private final Semaphore permits;
    /** 결과 타입 → SDK 가 만든 JSON 스키마 형식. 스키마 생성 · 지원 여부 검사는 타입마다 한 번만 한다. */
    private final Map<Class<?>, JsonOutputFormat> formats = new ConcurrentHashMap<>();

    /**
     * @param interceptor 계량 인터셉터. {@code null} 이면 계량하지 않는다 — {@code ai.allow-unmetered} 일 때만 {@code AiClients} 가 이렇게 만든다
     */
    ClaudeAiClient(AnthropicClient client, AiProperties properties, PiiMasker masker, AiCallInterceptor interceptor) {
        this.client = client;
        this.properties = properties;
        this.masker = masker;
        this.interceptor = interceptor;
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
            return new AiResult(text, message.model().asString(), usageOf(message), stopReason, message.id());
        });
    }

    @Override
    public <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator) {
        // 호출 전에 막는다 — null validator 면 비용을 쓴 뒤에야 실패한다
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(validator, "validator");
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
            if (value == null) {
                throw invalid(request.feature(), "null value");
            }
            List<String> problems;
            try {
                problems = validator.validate(value);
            } catch (RuntimeException e) {
                // validator 예외 메시지는 응답 값을 품을 수 있다 — 타입만 남기고 원인을 잇지 않는다(AiException 계약 밖으로 내보내지 않는다)
                throw invalid(request.feature(), "validator " + e.getClass().getSimpleName());
            }
            if (problems == null || !problems.isEmpty()) {
                // null 은 통과가 아니다(구현 실수로 검증이 빠지지 않게). 문제 설명은 응답 값을 품을 수 있어 개수만 남긴다
                throw invalid(request.feature(), "validation problems=" + (problems == null ? "null" : problems.size()));
            }
            return new AiStructuredResult<>(value, message.model().asString(), usageOf(message), stopReason, message.id());
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
        if (inputChars > properties.maxInputChars() || request.images().size() > AiImage.MAX_COUNT) {
            log.warn("ai call rejected. feature={}, reason={}, inputChars={}, maxInputChars={}, imageCount={}, maxImages={}",
                feature, Reason.INPUT_TOO_LONG, inputChars, properties.maxInputChars(), request.images().size(), AiImage.MAX_COUNT);
            throw new AiException(Reason.INPUT_TOO_LONG, feature);
        }
        ResolvedFeature settings = properties.resolve(feature);
        Masked masked = mask(request);
        MessageCreateParams params = params(request, masked, settings, format);
        AiCallContext context = new AiCallContext(UUID.randomUUID().toString(), feature, request.promptVersion(), request.callerRef(), settings.model(),
            settings.maxTokens(), masked.length(), request.images().size(), format != null);

        // 계량 → 자리 순서다. 한도에 막힌 호출이 동시 상한 자리를 잡지 않게 한다
        AiCallHandle handle = before(context);
        try {
            acquire(feature);
        } catch (AiException busy) {
            // 보내지 않았다 — 예약을 풀 수 있게 알린다(비용 0)
            after(handle, new AiCallOutcome(Status.NOT_SENT, null, settings.model(), busy.reason(), null, null, 0L), feature);
            throw busy;
        }

        long startedAt = System.nanoTime();
        Status status = null;
        Reason reason = null;
        Integer statusCode = null;
        AiUsage usage = null;
        String answeredModel = settings.model();
        String messageId = null;
        try {
            Message message = client.messages().create(params);
            // mapper 가 거절 · 잘림 · 형식 오류로 던지기 전에 사용량을 잡는다 — 그 응답도 과금됐다
            usage = usageOf(message);
            answeredModel = message.model().asString();
            messageId = message.id();
            String stopReason = message.stopReason().map(StopReason::asString).orElse(null);
            if (StopReason.REFUSAL.asString().equals(stopReason)) {
                throw new AiException(Reason.REFUSED, feature);
            }
            R result = mapper.apply(message, stopReason);
            status = Status.COMPLETED;
            log.info("ai call completed. feature={}, promptVersion={}, structured={}, imageCount={}, model={}, inputTokens={}, outputTokens={}, "
                    + "cacheCreationInputTokens={}, cacheReadInputTokens={}, stopReason={}, elapsedMs={}",
                feature, request.promptVersion(), format != null, request.images().size(), answeredModel, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(), stopReason, elapsedMs(startedAt));
            return result;
        } catch (AiException e) {
            // 응답은 받았지만 쓰지 못했다 — 과금됐다
            status = Status.UNUSABLE;
            reason = e.reason();
            log.warn("ai call failed. feature={}, promptVersion={}, reason={}, elapsedMs={}", feature, request.promptVersion(), e.reason(), elapsedMs(startedAt));
            throw e;
        } catch (AnthropicServiceException e) {
            status = Status.FAILED;
            reason = reasonOf(e.statusCode());
            statusCode = e.statusCode();
            throw failed(request, reason, statusCode, e.errorType().map(Object::toString).orElse(null), startedAt);
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            status = Status.TIMEOUT;
            reason = Reason.UNAVAILABLE;
            throw failed(request, reason, null, null, startedAt);
        } catch (AnthropicException e) {
            // 응답을 해석하지 못했다(AnthropicInvalidDataException 등). 사용량을 이미 읽었으면 과금된 응답이다(UNUSABLE, 실측),
            // 사용량부터 못 읽었으면 과금 여부를 모르니 타임아웃과 같이 예상치로 남긴다
            status = usage != null ? Status.UNUSABLE : Status.TIMEOUT;
            reason = Reason.INVALID_RESPONSE;
            throw failed(request, reason, null, null, startedAt);
        } finally {
            permits.release();
            // 자리를 반납한 뒤 알린다 — 원장 기록이 느려도 다른 호출을 막지 않는다. 예상 밖 예외(status 없음)는 응답을 받았는지로 나눈다
            Status resolved = status != null ? status : (usage != null ? Status.UNUSABLE : Status.TIMEOUT);
            AiUsage measured = resolved == Status.COMPLETED || resolved == Status.UNUSABLE ? usage : null;
            after(handle, new AiCallOutcome(resolved, measured, answeredModel, reason, statusCode, messageId, elapsedMs(startedAt)), feature);
        }
    }

    /** 인터셉터가 없으면(허용된 미계량) {@code null} 핸들이다. {@link AiException} 이 아닌 예외는 {@code METERING_FAILED} 로 바꾼다 — 타입만 남긴다. */
    private AiCallHandle before(AiCallContext context) {
        if (interceptor == null) {
            return null;
        }
        try {
            return interceptor.before(context);
        } catch (AiException e) {
            log.warn("ai call rejected. feature={}, promptVersion={}, reason={}", context.feature(), context.promptVersion(), e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai metering failed. feature={}, stage=before, cause={}", context.feature(), e.getClass().getName());
            throw new AiException(Reason.METERING_FAILED, context.feature());
        }
    }

    /** 결과 · 원래 예외를 깨지 않는다 — 기록 실패는 WARN(타입만)이고, 남은 예약은 호스트의 회수 스케줄러가 정리한다. */
    private void after(AiCallHandle handle, AiCallOutcome outcome, String feature) {
        if (interceptor == null) {
            return;
        }
        try {
            interceptor.after(handle, outcome);
        } catch (RuntimeException e) {
            log.warn("ai metering failed. feature={}, stage=after, status={}, cause={}", feature, outcome.status(), e.getClass().getName());
        }
    }

    /** SDK 의 사용량을 옮긴다. 캐시 토큰은 API 가 싣지 않으면 0 이다. */
    static AiUsage usageOf(Message message) {
        Usage usage = message.usage();
        return new AiUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L));
    }

    /** 가린 시스템 · 사용자 프롬프트. */
    private record Masked(String system, String prompt) {

        long length() {
            return (system == null ? 0L : system.length()) + prompt.length();
        }
    }

    /** 마스킹은 여기서 한다 — 실패하면 보내지 않는다. 원인 예외는 잇지 않는다(메시지에 입력 조각이 들어갈 수 있다). */
    private Masked mask(AiRequest request) {
        try {
            return new Masked(masker.mask(request.system(), request.sensitiveValues()), masker.mask(request.prompt(), request.sensitiveValues()));
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("ai masking failed. feature={}, cause={}", request.feature(), e.getClass().getName());
            throw new AiException(Reason.MASKING_FAILED, request.feature());
        }
    }

    private MessageCreateParams params(AiRequest request, Masked masked, ResolvedFeature settings, JsonOutputFormat format) {
        String system = masked.system();
        String prompt = masked.prompt();

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model(settings.model())
            .maxTokens(settings.maxTokens());
        if (request.images().isEmpty()) {
            builder.addUserMessage(prompt);
        } else {
            builder.addUserMessageOfBlockParams(imageThenText(prompt, request.images()));
        }
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

    /** 이미지 블록을 먼저 두고 가린 텍스트를 뒤에 둔다. base64 는 여기서만 만들고 로그로 나가지 않는다. */
    private static List<ContentBlockParam> imageThenText(String prompt, List<AiImage> images) {
        List<ContentBlockParam> blocks = new ArrayList<>(images.size() + 1);
        for (AiImage image : images) {
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                    .mediaType(mediaType(image.mediaType()))
                    .data(Base64.getEncoder().encodeToString(image.bytes()))
                    .build())
                .build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()));
        return blocks;
    }

    private static Base64ImageSource.MediaType mediaType(String mediaType) {
        return switch (mediaType) {
            case AiImage.JPEG -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case AiImage.PNG -> Base64ImageSource.MediaType.IMAGE_PNG;
            case AiImage.GIF -> Base64ImageSource.MediaType.IMAGE_GIF;
            case AiImage.WEBP -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("image mediaType");
        };
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
