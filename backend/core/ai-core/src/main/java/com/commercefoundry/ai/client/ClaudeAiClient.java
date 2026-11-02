package com.commercefoundry.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 공식 Java SDK 로 Messages API 를 부른다. 순서가 고정이다 — 글자 · 이미지 장수 상한 → 마스킹 → 계량 {@code before} → 동시 상한 자리 얻기 → 호출(SDK 재시도 포함)
 * → 사용량 캡처 → 결과 · 오류 번역 → 자리 반납 → 계량 {@code after}({@link AiCallInterceptor}). 단일 · 구조화 · 대화 · 스트리밍 호출이 모두 {@link #execute} 를 지난다.
 * 이미지는 사용자 메시지 앞의 base64 블록이다. 바이트는 가리지 못하고 로그에 싣지 않는다.
 * 마스킹은 길이에 비례하는 시간만 쓰고({@code PiiMasker}), 길이 상한이 그 앞에서 입력 크기를 묶는다.
 *
 * <ul>
 *   <li><b>재시도</b>는 SDK 가 한다({@code ai.max-retries}, 408 · 409 · 429 · 5xx · 연결 오류, {@code retry-after} 존중). 여기서 다시 감싸지 않는다.
 *       스트리밍은 응답 헤더를 받기 전까지만 재시도된다 — 본문을 읽다 끊기면 다시 보내지 않는다.</li>
 *   <li><b>동시 상한</b>은 프로세스 단위 세마포어다. 자리를 {@code ai.acquire-timeout} 안에 못 얻으면 {@link Reason#BUSY} — 호출하지 않는다.
 *       스트리밍은 스트림이 닫힐 때까지 자리를 잡는다.</li>
 *   <li><b>구조화 출력</b>은 API 의 JSON 출력({@code output_config.format})이다 — 스키마는 SDK 가 결과 타입에서 만들고(타입마다 한 번, 캐시) 응답은 SDK 가 파싱한다.
 *       Opus 5.5 는 강제 도구 호출({@code tool_choice} any/tool)을 400 으로 거절하므로 도구 방식을 쓰지 않는다.</li>
 *   <li><b>대화 · 도구</b>는 {@code tool_choice} 를 보내지 않는다(auto). 도구는 실행하지 않고 {@link AiToolCall} 로 돌려준다. thinking 블록은
 *       {@link AiContent.Opaque} 로 감싸 돌려주고, 다음 호출에 받은 그대로 보낸다(도구 루프에서 서명이 필요하다).</li>
 *   <li><b>거절 대체</b>({@code ai.refusal-fallback})는 API 의 server-side fallback 을 켠다. 대체 모델까지 거절하면 {@link Reason#REFUSED} 다.</li>
 *   <li><b>로그</b>에는 기능 · 프롬프트 버전 · 모델 · 토큰 수 · 멈춘 이유 · 상태 코드 · 도구 이름만 남긴다. 프롬프트 · 응답 원문 · 도구 인자 · SDK 예외 메시지 ·
 *       검증 문제 설명은 어떤 레벨에도 남기지 않는다 ({@code coding-conventions.md} §8). SDK 의 자체 요청 로그는 {@code AiClients} 가 끈다.</li>
 * </ul>
 */
@Slf4j
final class ClaudeAiClient implements AiClient, AutoCloseable {

    /** server-side fallback 베타. {@code fallbacks: "default"} 는 거절 분류에 맞춰 API 가 대체 모델을 고른다. */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final String BETA_HEADER = "anthropic-beta";
    /** {@link AiContent.Opaque#provider()} — 이 클라이언트가 만든 블록. */
    static final String PROVIDER = "anthropic";

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
        this(client, properties, masker, interceptor, new Semaphore(properties.maxConcurrency(), true));
    }

    ClaudeAiClient(AnthropicClient client, AiProperties properties, PiiMasker masker, AiCallInterceptor interceptor, Semaphore permits) {
        this.client = client;
        this.properties = properties;
        this.masker = masker;
        this.interceptor = interceptor;
        this.permits = permits;
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
    public AiConversationResult converse(AiConversationRequest request) {
        return conversation(request, null);
    }

    @Override
    public AiConversationResult converseStreaming(AiConversationRequest request, AiStreamListener listener) {
        Objects.requireNonNull(listener, "listener");
        return conversation(request, listener);
    }

    @Override
    public void close() {
        client.close();
    }

    // ---------------------------------------------------------------- 단일 · 구조화 호출

    /** 단일 호출. {@code mapper} 가 던진 {@link AiException} 은 그대로 나간다. */
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
        return execute(context, exchange -> {
            Message message = client.messages().create(params);
            // mapper 가 거절 · 잘림 · 형식 오류로 던지기 전에 사용량을 잡는다 — 그 응답도 과금됐다
            exchange.capture(message);
            String stopReason = exchange.stopReason;
            if (StopReason.REFUSAL.asString().equals(stopReason)) {
                throw new AiException(Reason.REFUSED, feature);
            }
            return mapper.apply(message, stopReason);
        });
    }

    // ---------------------------------------------------------------- 공통 순서 · 계량

    /**
     * 호출 중 응답에서 읽어 낸 것. 스트리밍은 이벤트마다 채운다 — 중간에 끊기거나 취소돼도 받은 만큼 남는다.
     * {@code usage} 가 있으면 API 가 응답을 시작했다(과금된다).
     */
    private static final class Exchange {

        private AiUsage usage;
        private String model;
        private String messageId;
        private String stopReason;
        private boolean cancelled;
        /** 요청을 보내기 전에 끝났다(스트림 {@code onStart} 예외 · 보내기 전 취소) — 비용 0 이라 {@code NOT_SENT} 로 알린다. */
        private boolean notSent;
        private List<String> toolNames = List.of();

        private Exchange(String requestedModel) {
            this.model = requestedModel;
        }

        /** 완성된 메시지. 사용량을 먼저 읽는다 — 뒤 필드 해석이 실패해도 과금된 응답으로 남게. */
        private void capture(Message message) {
            usage = usageOf(message);
            model = message.model().asString();
            messageId = message.id();
            stopReason = message.stopReason().map(StopReason::asString).orElse(null);
        }

        /** {@code message_start} — 입력 토큰(캐시 포함)과 첫 출력 토큰. */
        private void start(Message message) {
            usage = usageOf(message);
            model = message.model().asString();
            messageId = message.id();
        }

        /** {@code message_delta} — 누적 출력 토큰. 실리지 않은 항목은 앞서 받은 값을 둔다. */
        private void delta(MessageDeltaUsage delta) {
            AiUsage previous = usage == null ? AiUsage.ZERO : usage;
            usage = new AiUsage(delta.inputTokens().orElse(previous.inputTokens()), delta.outputTokens(),
                delta.cacheCreationInputTokens().orElse(previous.cacheCreationInputTokens()), delta.cacheReadInputTokens().orElse(previous.cacheReadInputTokens()));
        }
    }

    /**
     * 계량 → 자리 → 호출 → 결과 분류 → 자리 반납 → 계량 순서. {@code body} 는 HTTP 호출과 결과 해석을 하고 {@link Exchange} 를 채운다.
     *
     * <p>분류: 결과를 돌려주면 {@code COMPLETED}(호출자가 취소했으면 사용량이 있을 때 {@code UNUSABLE} · 없을 때 {@code TIMEOUT}, 원인 없음),
     * {@link AiException} 은 사용량이 있으면 {@code UNUSABLE} · 없으면 {@code TIMEOUT}(스트림이 시작 전에 끊김), 오류 응답은 {@code FAILED}
     * (스트림 중 오류 이벤트면 사용량이 있을 때 {@code UNUSABLE}), 연결 오류 · 타임아웃은 사용량이 있으면 {@code UNUSABLE} · 없으면 {@code TIMEOUT}.
     * {@code after} 는 어떤 경우에도 정확히 한 번이다.
     */
    private <R> R execute(AiCallContext context, Function<Exchange, R> body) {
        String feature = context.feature();
        // 계량 → 자리 순서다. 한도에 막힌 호출이 동시 상한 자리를 잡지 않게 한다
        AiCallHandle handle = before(context);
        try {
            acquire(feature);
        } catch (AiException busy) {
            // 보내지 않았다 — 예약을 풀 수 있게 알린다(비용 0)
            after(handle, new AiCallOutcome(Status.NOT_SENT, null, context.requestedModel(), busy.reason(), null, null, 0L), feature);
            throw busy;
        }

        long startedAt = System.nanoTime();
        Exchange exchange = new Exchange(context.requestedModel());
        Status status = null;
        Reason reason = null;
        Integer statusCode = null;
        try {
            R result = body.apply(exchange);
            AiUsage usage = exchange.usage == null ? AiUsage.ZERO : exchange.usage;
            if (exchange.cancelled) {
                // 호출자 선택이라 실패 원인을 두지 않는다. 받은 사용량이 있으면 과금된 응답(실측), 없으면 과금 여부를 모르니 예상치로 남긴다.
                // 보내기 전에 취소했으면 비용 0 이다
                status = exchange.notSent ? Status.NOT_SENT : (exchange.usage != null ? Status.UNUSABLE : Status.TIMEOUT);
                log.info("ai call cancelled. feature={}, promptVersion={}, model={}, inputTokens={}, outputTokens={}, elapsedMs={}, messageCount={}, streaming={}",
                    feature, context.promptVersion(), exchange.model, usage.inputTokens(), usage.outputTokens(), elapsedMs(startedAt),
                    context.messageCount(), context.streaming());
            } else {
                status = Status.COMPLETED;
                log.info("ai call completed. feature={}, promptVersion={}, structured={}, imageCount={}, model={}, inputTokens={}, outputTokens={}, "
                        + "cacheCreationInputTokens={}, cacheReadInputTokens={}, stopReason={}, elapsedMs={}, messageCount={}, toolCount={}, streaming={}, "
                        + "toolCalls={}",
                    feature, context.promptVersion(), context.structured(), context.imageCount(), exchange.model, usage.inputTokens(), usage.outputTokens(),
                    usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(), exchange.stopReason, elapsedMs(startedAt),
                    context.messageCount(), context.toolCount(), context.streaming(), exchange.toolNames);
            }
            return result;
        } catch (AiException e) {
            // 응답을 받았지만 쓰지 못했다(과금됐다). 스트림이 사용량을 받기 전에 끊겼으면 과금 여부를 모른다
            status = exchange.usage != null ? Status.UNUSABLE : Status.TIMEOUT;
            reason = e.reason();
            log.warn("ai call failed. feature={}, promptVersion={}, reason={}, elapsedMs={}, streaming={}",
                feature, context.promptVersion(), e.reason(), elapsedMs(startedAt), context.streaming());
            throw e;
        } catch (AnthropicServiceException e) {
            // 스트림 중 오류 이벤트(SseException)는 HTTP 200 뒤에 온다 — 상태 코드가 아니라 오류 종류로 나눈다
            String errorType = e.errorType().map(ErrorType::asString).orElse(null);
            reason = e instanceof SseException ? reasonOfErrorType(errorType) : reasonOf(e.statusCode());
            statusCode = e instanceof SseException ? null : e.statusCode();
            status = exchange.usage != null ? Status.UNUSABLE : Status.FAILED;
            throw failed(context, reason, statusCode, errorType, startedAt);
        } catch (AnthropicIoException | AnthropicRetryableException | UncheckedIOException e) {
            status = exchange.usage != null ? Status.UNUSABLE : Status.TIMEOUT;
            reason = Reason.UNAVAILABLE;
            throw failed(context, reason, null, null, startedAt);
        } catch (AnthropicException e) {
            // 응답을 해석하지 못했다(AnthropicInvalidDataException 등). 사용량을 이미 읽었으면 과금된 응답이다(UNUSABLE, 실측),
            // 사용량부터 못 읽었으면 과금 여부를 모르니 타임아웃과 같이 예상치로 남긴다
            status = exchange.usage != null ? Status.UNUSABLE : Status.TIMEOUT;
            reason = Reason.INVALID_RESPONSE;
            throw failed(context, reason, null, null, startedAt);
        } finally {
            permits.release();
            // 자리를 반납한 뒤 알린다 — 원장 기록이 느려도 다른 호출을 막지 않는다. 예상 밖 예외(status 없음 — listener 예외 등, 원인 null)는
            // 보내기 전이면 NOT_SENT, 아니면 응답을 받았는지로 나눈다
            Status resolved = status != null ? status
                : exchange.notSent ? Status.NOT_SENT : (exchange.usage != null ? Status.UNUSABLE : Status.TIMEOUT);
            AiUsage measured = resolved == Status.COMPLETED || resolved == Status.UNUSABLE ? exchange.usage : null;
            after(handle, new AiCallOutcome(resolved, measured, exchange.model, reason, statusCode, exchange.messageId, elapsedMs(startedAt)), feature);
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

    // ---------------------------------------------------------------- 대화 · 스트리밍

    /** 대화 호출 공통 — 길이 → 마스킹 → 파라미터 → {@link #execute}. {@code listener} 가 있으면 스트리밍이다. */
    private AiConversationResult conversation(AiConversationRequest request, AiStreamListener listener) {
        Objects.requireNonNull(request, "request");
        String feature = request.feature();
        long rawChars = conversationLength(request.system(), request.messages(), request.tools());
        if (rawChars > properties.maxInputChars()) {
            log.warn("ai call rejected. feature={}, reason={}, inputChars={}, maxInputChars={}, messageCount={}, toolCount={}",
                feature, Reason.INPUT_TOO_LONG, rawChars, properties.maxInputChars(), request.messages().size(), request.tools().size());
            throw new AiException(Reason.INPUT_TOO_LONG, feature);
        }
        ResolvedFeature settings = properties.resolve(feature);
        if (request.maxTokens() != null && request.maxTokens() > settings.maxTokens()) {
            // 요청이 기능 설정보다 크게 잡아 예상 비용 · 생성 시간을 늘리지 못하게 한다 — 낮추는 것만 된다
            throw new IllegalArgumentException("maxTokens 는 기능 설정(ai.features." + feature + ".max-tokens)을 넘을 수 없습니다");
        }
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : settings.maxTokens();
        MaskedConversation masked = mask(request);
        MessageCreateParams params = conversationParams(request.tools(), masked, settings, maxTokens);
        AiCallContext context = new AiCallContext(UUID.randomUUID().toString(), feature, request.promptVersion(), request.callerRef(), settings.model(),
            maxTokens, masked.length(), 0, false, request.messages().size(), request.tools().size(), listener != null);
        return execute(context, exchange -> listener == null ? sendOnce(request, params, exchange) : stream(request, params, listener, exchange));
    }

    private AiConversationResult sendOnce(AiConversationRequest request, MessageCreateParams params, Exchange exchange) {
        Message message = client.messages().create(params);
        exchange.capture(message);
        return conversationResult(request, message, exchange);
    }

    /**
     * 스트림을 끝까지 읽어 SDK 의 {@link MessageAccumulator} 로 메시지를 조립한다. 텍스트 조각만 {@code listener} 로 넘긴다.
     * 스트림은 어떤 경우에도(완료 · 취소 · 끊김 · 오류 · listener 예외) try-with-resources 로 닫힌다.
     */
    private AiConversationResult stream(AiConversationRequest request, MessageCreateParams params, AiStreamListener listener, Exchange exchange) {
        String feature = request.feature();
        StreamHandle handle = new StreamHandle();
        StringBuilder partial = new StringBuilder();
        exchange.notSent = true;
        // onStart 예외 · 보내기 전 취소는 HTTP 요청이 없다 — NOT_SENT(비용 0)로 알린다. onStart 예외는 그대로 나간다(원인 null)
        listener.onStart(handle);
        if (handle.cancelled()) {
            return cancelled(partial, exchange);
        }
        exchange.notSent = false;
        StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params);
        StreamState state = new StreamState();
        try (response) {
            handle.attach(response);
            MessageAccumulator accumulator = MessageAccumulator.create();
            Iterator<RawMessageStreamEvent> events = response.stream().iterator();
            while (!state.cancelledBeforeStop(handle) && events.hasNext()) {
                RawMessageStreamEvent event = events.next();
                accumulator.accumulate(event);
                event.messageStart().ifPresent(start -> exchange.start(start.message()));
                event.messageDelta().ifPresent(delta -> exchange.delta(delta.usage()));
                state.stopped |= event.isMessageStop();
                Optional<String> text = event.contentBlockDelta().flatMap(delta -> delta.delta().text()).map(TextDelta::text);
                if (text.isPresent() && !text.get().isEmpty()) {
                    partial.append(text.get());
                    if (!deliver(listener, text.get())) {
                        handle.cancel();
                    }
                }
            }
            // message_stop 뒤의 취소는 무시한다 — 응답이 이미 끝까지 왔다
            if (state.cancelledBeforeStop(handle)) {
                return cancelled(partial, exchange);
            }
            if (!state.stopped) {
                // 서버가 message_stop 없이 스트림을 닫았다(연결 끊김). 받은 사용량이 있으면 UNUSABLE, 없으면 TIMEOUT 으로 남는다
                log.warn("ai stream ended early. feature={}, receivedUsage={}", feature, exchange.usage != null);
                throw new AiException(Reason.UNAVAILABLE, feature);
            }
            Message message = accumulator.message();
            AiUsage streamed = exchange.usage;
            exchange.capture(message);
            if (streamed != null) {
                // 이벤트로 받은 값이 정본이다(조립기의 사용량 병합 규칙에 기대지 않는다)
                exchange.usage = streamed;
            }
            return conversationResult(request, message, exchange);
        } catch (ListenerFailure e) {
            throw e.unwrap();
        } catch (AiException | AnthropicException | UncheckedIOException e) {
            if (state.cancelledBeforeStop(handle) && !(e instanceof AiException)) {
                // 다른 스레드의 취소가 읽기 중이던 스트림을 닫았다
                return cancelled(partial, exchange);
            }
            throw e;
        } catch (RuntimeException e) {
            if (state.cancelledBeforeStop(handle)) {
                return cancelled(partial, exchange);
            }
            // 조립기 · 이벤트 해석 실패 등. 메시지에 응답 조각이 있을 수 있어 타입만 남긴다
            if (hasIoCause(e)) {
                log.warn("ai stream interrupted. feature={}, cause={}", feature, e.getClass().getSimpleName());
                throw new AiException(Reason.UNAVAILABLE, feature);
            }
            throw conversationInvalid(feature, "stream " + e.getClass().getSimpleName());
        }
    }

    private static boolean deliver(AiStreamListener listener, String delta) {
        try {
            return listener.onTextDelta(delta);
        } catch (RuntimeException e) {
            throw new ListenerFailure(e);
        }
    }

    private static AiConversationResult cancelled(StringBuilder partial, Exchange exchange) {
        exchange.cancelled = true;
        return new AiConversationResult(partial.toString(), List.of(), null, AiStopReason.CANCELLED, exchange.model, exchange.messageId,
            exchange.usage == null ? AiUsage.ZERO : exchange.usage);
    }

    /** 스트림 읽기 상태. {@code message_stop} 을 받은 뒤의 취소는 결과를 바꾸지 않는다. */
    private static final class StreamState {

        private boolean stopped;

        private boolean cancelledBeforeStop(StreamHandle handle) {
            return handle.cancelled() && !stopped;
        }
    }

    private static boolean hasIoCause(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** listener 예외를 SDK · 조립 예외와 구분해 원래 예외로 내보낸다. */
    private static final class ListenerFailure extends RuntimeException {

        private ListenerFailure(RuntimeException cause) {
            super(null, cause, false, false);
        }

        private RuntimeException unwrap() {
            return (RuntimeException) getCause();
        }
    }

    /** 다른 스레드에서 스트림을 닫는 핸들. 스트림이 붙기 전에 취소하면 붙는 즉시 닫는다. */
    private static final class StreamHandle implements AiStreamHandle {

        private volatile boolean cancelled;
        private volatile StreamResponse<?> response;

        private void attach(StreamResponse<?> attached) {
            this.response = attached;
            if (cancelled) {
                closeQuietly(attached);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            StreamResponse<?> current = response;
            if (current != null) {
                closeQuietly(current);
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        private static void closeQuietly(StreamResponse<?> response) {
            try {
                response.close();
            } catch (RuntimeException ignored) {
                // 이미 닫혔다 — 읽던 쪽이 결과를 정한다
            }
        }
    }

    /**
     * 응답을 대화 결과로 옮긴다. 거절 · 잘린 도구 호출 · 없는 도구 · 인자 스키마 불일치 · 빈 응답은 {@code INVALID_RESPONSE}(거절은 {@code REFUSED})다.
     * thinking 블록은 {@link AiContent.Opaque} 로 감싸 assistant 메시지에 응답 순서대로 둔다.
     */
    private AiConversationResult conversationResult(AiConversationRequest request, Message message, Exchange exchange) {
        String feature = request.feature();
        String stopReason = exchange.stopReason;
        if (StopReason.REFUSAL.asString().equals(stopReason)) {
            throw new AiException(Reason.REFUSED, feature);
        }
        StringBuilder text = new StringBuilder();
        List<AiToolCall> calls = new ArrayList<>();
        List<AiContent> contents = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.text().isPresent()) {
                String part = block.text().get().text();
                text.append(part);
                if (!part.isBlank()) {
                    contents.add(AiContent.text(part));
                }
            } else if (block.toolUse().isPresent()) {
                AiToolCall call = toolCall(request, block.toolUse().get());
                calls.add(call);
                contents.add(call);
            } else if (block.thinking().isPresent() || block.redactedThinking().isPresent()) {
                contents.add(providerBlock(feature, block));
            }
        }
        exchange.toolNames = calls.stream().map(AiToolCall::name).toList();
        AiStopReason normalized = AiStopReason.fromClaude(stopReason);
        if (normalized == AiStopReason.MAX_TOKENS && !calls.isEmpty()) {
            // 잘린 도구 인자를 실행하게 하지 않는다
            throw conversationInvalid(feature, "truncated tool call");
        }
        if (text.toString().isBlank() && calls.isEmpty()) {
            throw conversationInvalid(feature, "empty");
        }
        AiMessage assistant = new AiMessage(AiMessage.Role.ASSISTANT, contents);
        return new AiConversationResult(text.toString(), calls, assistant, normalized, exchange.model, exchange.messageId, exchange.usage);
    }

    /** thinking 블록을 Provider 블록으로 감싼다. 길이 상한을 넘는 등 다시 보낼 수 없는 블록이면 쓸 수 없는 응답이다. */
    private static AiContent.Opaque providerBlock(String feature, ContentBlock block) {
        String payload = serialize(feature, block.toParam());
        try {
            return new AiContent.Opaque(PROVIDER, payload);
        } catch (IllegalArgumentException e) {
            throw conversationInvalid(feature, "provider block length=" + payload.length());
        }
    }

    private AiToolCall toolCall(AiConversationRequest request, ToolUseBlock block) {
        String feature = request.feature();
        AiTool tool = request.tool(block.name());
        if (tool == null) {
            // 넘기지 않은 도구 — 이름은 모델 출력이라 싣지 않는다
            throw conversationInvalid(feature, "unknown tool");
        }
        if (!AiToolCall.ID.matcher(block.id()).matches()) {
            throw conversationInvalid(feature, "tool call id tool=" + tool.name());
        }
        String arguments = serialize(feature, block._input());
        Optional<String> problem = tool.argumentProblem(arguments);
        if (problem.isPresent()) {
            throw conversationInvalid(feature, "tool arguments " + problem.get() + " tool=" + tool.name());
        }
        return new AiToolCall(block.id(), tool.name(), arguments);
    }

    private static String serialize(String feature, Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw conversationInvalid(feature, "serialize " + e.getClass().getSimpleName());
        }
    }

    /** 대화 결과가 쓸 수 없다. {@code detail} 은 원인 구분 · 도구 이름만이다(응답 값 · 인자 없음). */
    private static AiException conversationInvalid(String feature, String detail) {
        log.warn("ai conversation output rejected. feature={}, detail={}", feature, detail);
        return new AiException(Reason.INVALID_RESPONSE, feature);
    }

    /** 가린 시스템 프롬프트와 이력. {@code length} 는 마스킹 뒤 시스템 + 이력 + 도구 정의 글자 수다. */
    private record MaskedConversation(String system, List<AiMessage> messages, long length) {

    }

    /** 이력 전체를 가린다 — 텍스트 · 도구 결과는 글자로, 도구 인자는 JSON 값마다. Provider 블록은 서명이 깨지므로 가리지 않는다. 실패하면 보내지 않는다. */
    private MaskedConversation mask(AiConversationRequest request) {
        List<String> known = request.sensitiveValues();
        try {
            String system = masker.mask(request.system(), known);
            List<AiMessage> messages = new ArrayList<>(request.messages().size());
            for (AiMessage message : request.messages()) {
                List<AiContent> contents = new ArrayList<>(message.contents().size());
                for (AiContent content : message.contents()) {
                    contents.add(switch (content) {
                        case AiContent.Text text -> new AiContent.Text(masker.mask(text.text(), known));
                        case AiContent.ToolResult result -> new AiContent.ToolResult(result.callId(), masker.mask(result.content(), known), result.error());
                        case AiToolCall call -> new AiToolCall(call.id(), call.name(), AiJson.write(maskJson(AiJson.object(call.argumentsJson()).orElseThrow(), known)));
                        case AiContent.Opaque opaque -> opaque;
                    });
                }
                messages.add(new AiMessage(message.role(), contents));
            }
            return new MaskedConversation(system, messages, conversationLength(system, messages, request.tools()));
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("ai masking failed. feature={}, cause={}", request.feature(), e.getClass().getName());
            throw new AiException(Reason.MASKING_FAILED, request.feature());
        }
    }

    /** JSON 의 문자열 · 숫자 값을 가린다. 숫자가 가려지면 자리표시 문자열이 된다. 키는 스키마가 정한 이름이라 그대로 둔다. */
    private JsonNode maskJson(JsonNode node, List<String> known) {
        if (node instanceof ObjectNode object) {
            ObjectNode out = AiJson.MAPPER.createObjectNode();
            object.properties().forEach(field -> out.set(field.getKey(), maskJson(field.getValue(), known)));
            return out;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode out = AiJson.MAPPER.createArrayNode();
            array.forEach(element -> out.add(maskJson(element, known)));
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(masker.mask(node.asText(), known));
        }
        if (node.isNumber()) {
            String raw = node.asText();
            String masked = masker.mask(raw, known);
            return masked.equals(raw) ? node : TextNode.valueOf(masked);
        }
        return node;
    }

    /**
     * 입력 상한 · 예상 비용의 글자 수 — 시스템 + 텍스트 + 도구 결과 + 도구 인자 + Provider 블록 + 도구 정의. Provider 블록은 가리지 않고 그대로 가므로
     * 다른 Provider 의 것(보내지 않는다)까지 보수적으로 센다 — 위조 블록이 상한 · 예약 예상치를 피하지 못하게.
     */
    private static long conversationLength(String system, List<AiMessage> messages, List<AiTool> tools) {
        long length = system == null ? 0L : system.length();
        for (AiMessage message : messages) {
            for (AiContent content : message.contents()) {
                length += switch (content) {
                    case AiContent.Text text -> text.text().length();
                    case AiContent.ToolResult result -> result.content().length();
                    case AiToolCall call -> call.argumentsJson().length();
                    case AiContent.Opaque opaque -> opaque.payload().length();
                };
            }
        }
        for (AiTool tool : tools) {
            length += tool.length();
        }
        return length;
    }

    private MessageCreateParams conversationParams(List<AiTool> tools, MaskedConversation masked, ResolvedFeature settings, int maxTokens) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model(settings.model())
            .maxTokens(maxTokens);
        for (AiMessage message : masked.messages()) {
            List<ContentBlockParam> blocks = new ArrayList<>(message.contents().size());
            for (AiContent content : message.contents()) {
                blockParam(content).ifPresent(blocks::add);
            }
            if (blocks.isEmpty()) {
                // 다른 Provider 의 블록만 있던 메시지 — 보낼 것이 없다
                continue;
            }
            builder.addMessage(MessageParam.builder()
                .role(message.role() == AiMessage.Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build());
        }
        if (masked.system() != null && !masked.system().isBlank()) {
            builder.system(masked.system());
        }
        for (AiTool tool : tools) {
            builder.addTool(Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(inputSchema(tool))
                .build());
        }
        if (settings.effort() != null) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort())).build());
        }
        if (properties.refusalFallback()) {
            builder.putAdditionalHeader(BETA_HEADER, FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    private static Optional<ContentBlockParam> blockParam(AiContent content) {
        return switch (content) {
            case AiContent.Text text -> Optional.of(ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build()));
            case AiContent.ToolResult result -> Optional.of(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(result.callId())
                .content(result.content())
                .isError(result.error())
                .build()));
            case AiToolCall call -> Optional.of(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                .id(call.id())
                .name(call.name())
                .input(ToolUseBlockParam.Input.builder().putAllAdditionalProperties(jsonValues(AiJson.object(call.argumentsJson()).orElseThrow())).build())
                .build()));
            case AiContent.Opaque opaque -> PROVIDER.equals(opaque.provider()) ? Optional.of(opaqueParam(opaque)) : Optional.empty();
        };
    }

    private static Map<String, JsonValue> jsonValues(ObjectNode object) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        object.properties().forEach(field -> values.put(field.getKey(), JsonValue.fromJsonNode(field.getValue())));
        return values;
    }

    /**
     * Provider 블록을 SDK 블록으로 되돌린다. thinking · redacted_thinking 만 받는다 — 다른 종류(text · tool_result · image 등)로 위조된 블록은
     * 마스킹 · 짝 검사 · 길이 상한을 피하는 통로라 거절한다({@link AiContent.Opaque} 생성 때 한 번, 여기서 역직렬화 결과로 한 번 더).
     */
    private static ContentBlockParam opaqueParam(AiContent.Opaque opaque) {
        ContentBlockParam param;
        try {
            param = ObjectMappers.jsonMapper().readValue(opaque.payload(), ContentBlockParam.class);
        } catch (JsonProcessingException e) {
            // 호출자가 Provider 블록을 고쳤다 — 원인(본문 조각)은 잇지 않는다
            throw new IllegalArgumentException("Provider 블록을 읽지 못했습니다 — assistantMessage 를 받은 그대로 이력에 붙이세요");
        }
        if (!param.isThinking() && !param.isRedactedThinking()) {
            throw new IllegalArgumentException("Provider 블록은 thinking · redacted_thinking 만 받습니다");
        }
        return param;
    }

    private static Tool.InputSchema inputSchema(AiTool tool) {
        ObjectNode schema = AiJson.object(tool.inputSchemaJson()).orElseThrow();
        return JsonValue.fromJsonNode(schema).convert(Tool.InputSchema.class);
    }

    // ---------------------------------------------------------------- 단일 호출 파라미터 · 공통 도우미

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

    /** 스트림 중 오류 이벤트의 종류로 나눈다 — 상태 코드가 없다(HTTP 200 뒤). */
    static Reason reasonOfErrorType(String errorType) {
        if (errorType == null) {
            return Reason.UNAVAILABLE;
        }
        return switch (errorType) {
            case "rate_limit_error" -> Reason.RATE_LIMITED;
            case "overloaded_error", "api_error", "timeout_error" -> Reason.UNAVAILABLE;
            case "authentication_error", "permission_error", "billing_error" -> Reason.UNAUTHORIZED;
            default -> Reason.REJECTED;
        };
    }

    /** 실패 로그는 원인 구분 · 상태만 — SDK 예외(메시지에 API 오류 본문)는 넘기지 않는다. */
    private static AiException failed(AiCallContext context, Reason reason, Integer statusCode, String errorType, long startedAt) {
        log.warn("ai call failed. feature={}, promptVersion={}, reason={}, status={}, errorType={}, elapsedMs={}, streaming={}",
            context.feature(), context.promptVersion(), reason, statusCode, errorType, elapsedMs(startedAt), context.streaming());
        return new AiException(reason, context.feature(), statusCode, errorType);
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
