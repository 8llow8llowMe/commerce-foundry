package com.commercefoundry.ai.client;

import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.ai.properties.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.openai.core.Timeout;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Stateless Responses transport. SDK types and error bodies stay inside ai-core. */
@Slf4j
final class OpenAiClient implements AiClient, AutoCloseable {
    private final OpenAIClient client;
    private final AiProperties properties;
    private final PiiMasker masker;
    private final AiCallInterceptor interceptor;
    private final Semaphore permits;

    OpenAiClient(AiProperties properties, PiiMasker masker, AiCallInterceptor interceptor, Semaphore permits) {
        this.properties = properties;
        this.masker = masker;
        this.interceptor = interceptor;
        this.permits = permits;
        OpenAIOkHttpClient.Builder builder =
                OpenAIOkHttpClient.builder()
                       .apiKey(properties.openai().apiKey())
                       .logLevel(LogLevel.OFF)
                       .maxRetries(properties.maxRetries())
                       .timeout(
                                Timeout.builder()
                                       .connect(properties.connectTimeout())
                                       .read(properties.readTimeout()).write(properties.readTimeout()).request(properties.requestTimeout()).build());
        if (properties.openai().baseUrl() != null) {
            builder.baseUrl(properties.openai().baseUrl());
        }
        client = builder.build();
    }

    @Override

    public AiResult complete(AiRequest request) {
        Prepared prepared = prepare(request, null);
        return execute(prepared.context(), exchange -> {
                    JsonNode response = send(prepared.params(), exchange);
                    AiConversationResult result = result(request.feature(), List.of(), response, exchange);
                    if (result.text().isEmpty()) {
                        throw invalid(request.feature());
                    }
                    return new AiResult(result.text(), exchange.model, exchange.usage, stop(result.stopReason()), exchange.id);
                });
    }

    @Override

    public <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(validator, "validator");
        var format = ResponseCreateParams.builder().text(type).build().rawParams().text().orElseThrow();
        Prepared prepared = prepare(request, tree(format));
        return execute(prepared.context(), exchange -> {
                    JsonNode response = send(prepared.params(), exchange);
                    AiConversationResult result = result(request.feature(), List.of(), response, exchange);
                    if (result.stopReason() == AiStopReason.MAX_TOKENS) {
                        throw invalid(request.feature());
                    }
                    T value;
                    try {
                        value = AiJson.MAPPER.readValue(result.text(), type);
                        List<String> problems = value == null ? null : validator.validate(value);
                        if (problems == null || !problems.isEmpty()) {
                            throw invalid(request.feature());
                        }
                    } catch (Exception failure) {
                        throw invalid(request.feature());
                    }
                    return new AiStructuredResult<>(value, exchange.model, exchange.usage, stop(result.stopReason()), exchange.id);
                });
    }

    @Override

    public AiConversationResult converse(AiConversationRequest request) {
        return conversation(request, null);
    }

    @Override

    public AiConversationResult converseStreaming(AiConversationRequest request, AiStreamListener listener) {
        return conversation(request, Objects.requireNonNull(listener, "listener"));
    }

    @Override

    public void close() {
        client.close();
    }

    private record Prepared(ResponseCreateParams params, AiCallContext context) {}

    private Prepared prepare(AiRequest request, JsonNode format) {
        long length = (request.system() == null ? 0L : request.system().length()) + request.prompt().length() + (format == null ? 0L : AiJson.write(format).length());
        limit(length, request.images().size(), request.feature());
        ObjectNode body = base(request.feature(), null);
        ObjectNode message = AiJson.MAPPER.createObjectNode().put("role", "user");
        ArrayNode content = message.putArray("content");
        for (AiImage image : request.images())
            content.addObject().put("type", "input_image").put("image_url", "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes()));
        content.addObject().put("type", "input_text").put("text", mask(request.prompt(), request.sensitiveValues(), request.feature()));
        body.putArray("input").add(message);
        if (request.system() != null) {
            body.put("instructions", mask(request.system(), request.sensitiveValues(), request.feature()));
        }
        if (format != null) {
            JsonNode maskedFormat = maskJson(format, request.sensitiveValues(), request.feature());
            validateSchemaMasking(format, maskedFormat, request.feature());
            body.set("text", maskedFormat);
        }
        long maskedLength =
                body.path("instructions").asText("").length()
                        + content.get(content.size() - 1).path("text").asText().length() + (format == null ? 0L : AiJson.write(body.path("text")).length());
        var settings = properties.resolve(request.feature());
        return new Prepared(
                params(body),
                new AiCallContext(
                        UUID.randomUUID().toString(),
                        request.feature(),
                        request.promptVersion(), request.callerRef(), settings.model(), settings.maxTokens(), maskedLength, request.images().size(), format != null));
    }

    private AiConversationResult conversation(AiConversationRequest request, AiStreamListener listener) {
        long length = request.system() == null ? 0L : request.system().length();
        for (AiMessage message : request.messages()) for (AiContent content : message.contents()) length += switch (content) {
                            case AiContent.Text text -> text.text().length();
                            case AiToolCall call -> call.argumentsJson().length();
                            case AiContent.ToolResult result -> result.content().length();
                            case AiContent.Opaque opaque -> opaque.payload().length();
                        };
        for (AiTool tool : request.tools()) length += tool.length();
        limit(length, 0, request.feature());
        ObjectNode body = base(request.feature(), request.maxTokens());
        List<String> known = request.sensitiveValues();
        if (request.system() != null) {
            body.put("instructions", mask(request.system(), known, request.feature()));
        }
        ArrayNode input = body.putArray("input");
        for (AiMessage message : request.messages()) for (AiContent content : message.contents()) {
                switch (content) {
                    case AiContent.Text text ->
                            input.addObject()
                                   .put("role", message.role() == AiMessage.Role.USER ? "user" : "assistant").put("content", mask(text.text(), known, request.feature()));
                    case AiToolCall call ->
                            input.addObject()
                                   .put("type", "function_call")
                                   .put("call_id", call.id())
                                   .put("name", call.name())
                                   .put("arguments", AiJson.write(maskJson(AiJson.object(call.argumentsJson()).orElseThrow(), known, request.feature())));
                    case AiContent.ToolResult result ->
                            input.addObject()
                                   .put("type", "function_call_output").put("call_id", result.callId()).put("output", mask(result.content(), known, request.feature()));
                    case AiContent.Opaque opaque -> {
                        if ("openai".equals(opaque.provider())) {
                            ObjectNode node = AiJson.object(opaque.payload()).orElseThrow(() -> new IllegalArgumentException("Invalid provider block"));
                            if (!"reasoning".equals(node.path("type").asText())) {
                                throw new IllegalArgumentException("Invalid provider block type");
                            }
                            input.add(node);
                        }
                    }
                }
            }
        ArrayNode tools = body.putArray("tools");
        for (AiTool tool : request.tools()) {
            ObjectNode entry =
                    tools.addObject()
                           .put("type", "function").put("name", tool.name()).put("description", mask(tool.description(), known, request.feature())).put("strict", false);
            entry.set("parameters", maskJson(AiJson.object(tool.inputSchemaJson()).orElseThrow(), known, request.feature()));
        }
        var settings = properties.resolve(request.feature());
        AiCallContext context =
                new AiCallContext(
                        UUID.randomUUID().toString(),
                        request.feature(),
                        request.promptVersion(),
                        request.callerRef(),
                        settings.model(),
                        body.path("max_output_tokens").asInt(), AiJson.write(body).length(), 0, false, request.messages().size(), request.tools().size(), listener != null);
        ResponseCreateParams params = params(body);
        return execute(
                context,
                exchange -> listener == null ? result(request.feature(), request.tools(), send(params, exchange), exchange) : stream(request, params, listener, exchange));
    }

    private ObjectNode base(String feature, Integer maxTokens) {
        var settings = properties.resolve(feature);
        if (maxTokens != null && maxTokens > settings.maxTokens()) {
            throw new IllegalArgumentException("maxTokens exceeds feature setting");
        }
        int maximum = maxTokens == null ? settings.maxTokens() : maxTokens;
        if (maximum < 16) {
            throw new IllegalArgumentException("OpenAI maxTokens must be at least 16");
        }
        ObjectNode body = AiJson.MAPPER.createObjectNode().put("model", settings.model()).put("max_output_tokens", maximum).put("store", false);
        body.putArray("include").add("reasoning.encrypted_content");
        if (settings.effort() != null) {
            body.putObject("reasoning").put("effort", settings.effort());
        }
        return body;
    }

    private static ResponseCreateParams params(ObjectNode body) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder();
        body.properties().forEach(field -> builder.putAdditionalBodyProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue())));
        return builder.build();
    }

    private JsonNode send(ResponseCreateParams params, Exchange exchange) {
        JsonNode response = tree(client.responses().create(params));
        capture(response, exchange);
        return response;
    }

    private static JsonNode tree(Object value) {
        try {
            return AiJson.MAPPER.readTree(ObjectMappers.jsonMapper().writeValueAsString(value));
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid SDK response");
        }
    }

    private void capture(JsonNode response, Exchange exchange) {
        JsonNode usage = response.path("usage");
        if (usage.isObject()) {
            long total = token(usage.path("input_tokens"), exchange.feature);
            JsonNode cachedNode = usage.path("input_tokens_details").path("cached_tokens");
            long cached = cachedNode.isMissingNode() ? 0 : token(cachedNode, exchange.feature);
            long output = token(usage.path("output_tokens"), exchange.feature);
            if (total < 0 || cached < 0 || output < 0 || cached > total) {
                throw invalid(exchange.feature);
            }
            exchange.usage = new AiUsage(total - cached, output, 0, cached);
        }
        exchange.model = response.path("model").asText(exchange.model);
        exchange.id = response.path("id").asText(null);
    }

    /** Only documented transient errors are retryable; unfamiliar codes fail closed. */
    private static Reason responseErrorReason(String code) {
        return switch (code) {
            case "server_error", "vector_store_timeout" -> Reason.UNAVAILABLE;
            case "rate_limit_exceeded" -> Reason.RATE_LIMITED;
            case "bio_policy", "misalignment_policy_violation", "image_content_policy_violation" -> Reason.REFUSED;
            case "invalid_prompt",
                    "data_residency_mismatch",
                    "invalid_image",
                    "invalid_image_format",
                    "invalid_base64_image",
                    "invalid_image_url",
                    "image_too_large",
                    "image_too_small",
                    "image_parse_error",
                    "invalid_image_mode",
                    "image_file_too_large", "unsupported_image_media_type", "empty_image_file", "failed_to_download_image", "image_file_not_found" -> Reason.REJECTED;
            default -> Reason.REJECTED;
        };
    }

    /**
     * Masking schema constraints could make Java deserialization incompatible; reject before
     * metering.
     */
    private static void validateSchemaMasking(JsonNode original, JsonNode masked, String feature) {
        if (original.isObject()) {
            original.properties().forEach(field -> {
                                if (Set.of("enum", "const", "required", "type", "$ref").contains(field.getKey()) && !field.getValue().equals(masked.path(field.getKey()))) {
                                    throw new AiException(Reason.MASKING_FAILED, feature);
                                }
                                validateSchemaMasking(field.getValue(), masked.path(field.getKey()), feature);
                            });
        } else if (original.isArray()) {
            for (int i = 0; i < original.size(); i++)
                validateSchemaMasking(original.get(i), masked.path(i), feature);
        }
    }

    private static long token(JsonNode value, String feature) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(feature);
        }
        return value.longValue();
    }

    private AiConversationResult result(String feature, List<AiTool> tools, JsonNode response, Exchange exchange) {
        if (response.path("error").isObject() || "failed".equals(response.path("status").asText())) {
            throw new AiException(responseErrorReason(response.path("error").path("code").asText()), feature);
        }
        if (exchange.usage == null) {
            throw invalid(feature);
        }
        String status = response.path("status").asText();
        boolean truncated = "incomplete".equals(status);
        if (truncated) {
            String reason = response.path("incomplete_details").path("reason").asText();
            if ("content_filter".equals(reason)) {
                throw new AiException(Reason.REFUSED, feature);
            }
            if (!"max_output_tokens".equals(reason)) {
                throw invalid(feature);
            }
        } else if (!"completed".equals(status)) {
            throw invalid(feature);
        }
        StringBuilder text = new StringBuilder();
        List<AiContent> contents = new ArrayList<>();
        List<AiToolCall> calls = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> {
                    for (JsonNode block : item.path("content")) {
                        if ("refusal".equals(block.path("type").asText())) {
                            throw new AiException(Reason.REFUSED, feature);
                        }
                        if ("output_text".equals(block.path("type").asText())) {
                            String value = block.path("text").asText();
                            text.append(value);
                            if (!value.isBlank()) {
                                contents.add(new AiContent.Text(value));
                            }
                        }
                    }
                }
                case "function_call" -> {
                    String name = item.path("name").asText(), id = item.path("call_id").asText(), arguments = item.path("arguments").asText();
                    AiTool tool = tools.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
                    if (truncated || tool == null || !AiToolCall.ID.matcher(id).matches() || tool.argumentProblem(arguments).isPresent()) {
                        throw invalid(feature);
                    }
                    AiToolCall call = new AiToolCall(id, name, arguments);
                    calls.add(call);
                    contents.add(call);
                }
                case "reasoning" -> {
                    try {
                        contents.add(new AiContent.Opaque("openai", AiJson.write(item)));
                    } catch (IllegalArgumentException failure) {
                        throw invalid(feature);
                    }
                }
                default -> throw invalid(feature);
            }
        }
        if (text.toString().isBlank() && calls.isEmpty()) {
            throw invalid(feature);
        }
        AiStopReason stop = truncated ? AiStopReason.MAX_TOKENS : calls.isEmpty() ? AiStopReason.END_TURN : AiStopReason.TOOL_USE;
        return new AiConversationResult(
                text.toString(),
                calls, new AiMessage(AiMessage.Role.ASSISTANT, contents), stop, exchange.model, exchange.id, exchange.usage == null ? AiUsage.ZERO : exchange.usage);
    }

    private AiConversationResult stream(AiConversationRequest request, ResponseCreateParams params, AiStreamListener listener, Exchange exchange) {
        Handle handle = new Handle();
        StringBuilder partial = new StringBuilder();
        exchange.notSent = true;
        callback(exchange, () -> {
                    listener.onStart(handle);
                    return true;
                });
        if (handle.cancelled()) {
            return cancelled(partial, exchange);
        }
        exchange.notSent = false;
        try (StreamResponse<ResponseStreamEvent> response = client.responses().createStreaming(params)) {
            handle.attach(response);
            Iterator<ResponseStreamEvent> events = response.stream().iterator();
            JsonNode terminal = null;
            while (!handle.cancelled() && events.hasNext()) {
                JsonNode event = tree(events.next());
                if (event.has("response")) {
                    capture(event.path("response"), exchange);
                }
                String type = event.path("type").asText();
                if ("response.output_text.delta".equals(type)) {
                    String delta = event.path("delta").asText();
                    partial.append(delta);
                    if (!callback(exchange, () -> listener.onTextDelta(delta))) {
                        handle.cancel();
                    }
                }
                if (Set.of("response.completed", "response.incomplete", "response.failed").contains(type)) {
                    terminal = event.path("response");
                    break;
                }
                if ("error".equals(type)) {
                    throw new AiException(responseErrorReason(event.path("code").asText()), request.feature());
                }
            }
            if (handle.cancelled()) {
                return cancelled(partial, exchange);
            }
            if (terminal == null) {
                throw new AiException(Reason.UNAVAILABLE, request.feature());
            }
            return result(request.feature(), request.tools(), terminal, exchange);
        } catch (RuntimeException failure) {
            if (handle.cancelled() && !exchange.listenerFailure) {
                return cancelled(partial, exchange);
            }
            throw failure;
        }
    }

    private static boolean callback(Exchange exchange, java.util.function.Supplier<Boolean> callback) {
        try {
            return callback.get();
        } catch (RuntimeException | Error failure) {
            exchange.listenerFailure = true;
            throw failure;
        }
    }

    private static AiConversationResult cancelled(StringBuilder text, Exchange exchange) {
        exchange.cancelled = true;
        return new AiConversationResult(
                text.toString(), List.of(), null, AiStopReason.CANCELLED, exchange.model, exchange.id, exchange.usage == null ? AiUsage.ZERO : exchange.usage);
    }

    private static final class Handle implements AiStreamHandle {
        private volatile boolean cancelled;
        private volatile StreamResponse<?> response;

        synchronized void attach(StreamResponse<?> response) {
            this.response = response;
            if (cancelled) {
                response.close();
            }
        }

        @Override

        public synchronized void cancel() {
            cancelled = true;
            if (response != null) {
                response.close();
            }
        }

        @Override

        public boolean cancelled() {
            return cancelled;
        }
    }

    private static final class Exchange {
        private final String feature;
        private String model, id;
        private AiUsage usage;
        private boolean notSent, cancelled, listenerFailure;

        Exchange(AiCallContext context) {
            feature = context.feature();
            model = context.requestedModel();
        }
    }

    private <T> T execute(AiCallContext context, Function<Exchange, T> body) {
        AiCallHandle handle = null;
        if (interceptor != null) {
            try {
                handle = interceptor.before(context);
            } catch (AiException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new AiException(Reason.METERING_FAILED, context.feature());
            }
        }
        Exchange exchange = new Exchange(context);
        Status status = Status.NOT_SENT;
        Reason reason = null;
        Integer code = null;
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            try {
                acquired = permits.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!acquired) {
                throw new AiException(Reason.BUSY, context.feature());
            }
            T result = body.apply(exchange);
            status = exchange.cancelled ? exchange.notSent ? Status.NOT_SENT : exchange.usage == null ? Status.TIMEOUT : Status.UNUSABLE : Status.COMPLETED;
            return result;
        } catch (OpenAIServiceException failure) {
            if (exchange.listenerFailure) {
                status = exchange.notSent ? Status.NOT_SENT : exchange.usage == null ? Status.TIMEOUT : Status.UNUSABLE;
                throw failure;
            }
            code = failure.statusCode();
            reason = ClaudeAiClient.reasonOf(code);
            status = exchange.usage == null ? Status.FAILED : Status.UNUSABLE;
            throw new AiException(reason, context.feature(), code, null);
        } catch (OpenAIException failure) {
            if (exchange.listenerFailure) {
                status = exchange.notSent ? Status.NOT_SENT : exchange.usage == null ? Status.TIMEOUT : Status.UNUSABLE;
                throw failure;
            }
            reason = failure instanceof OpenAIInvalidDataException ? Reason.INVALID_RESPONSE : Reason.UNAVAILABLE;
            status = exchange.usage == null ? Status.TIMEOUT : Status.UNUSABLE;
            throw new AiException(reason, context.feature());
        } catch (RuntimeException | Error failure) {
            status = !acquired || exchange.notSent ? Status.NOT_SENT : exchange.usage == null ? Status.TIMEOUT : Status.UNUSABLE;
            if (exchange.listenerFailure || failure instanceof Error) {
                throw failure;
            }
            if (failure instanceof AiException ai) {
                reason = ai.reason();
                throw ai;
            }
            reason = Reason.INVALID_RESPONSE;
            throw new AiException(reason, context.feature());
        } finally {
            if (acquired) {
                permits.release();
            }
            if (interceptor != null) {
                try {
                    interceptor.after(
                            handle,
                            new AiCallOutcome(
                                    status,
                                    exchange.usage,
                                    exchange.model, reason, code, exchange.id, status == Status.NOT_SENT ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                } catch (RuntimeException ignored) {
                    log.warn(
                            "ai metering failed. feature={}, stage=after, status={}, cause={}",
                            context.feature(), status, ignored.getClass().getName());
                }
            }
        }
    }

    private void limit(long chars, int images, String feature) {
        if (chars > properties.maxInputChars() || images > AiImage.MAX_COUNT) {
            throw new AiException(Reason.INPUT_TOO_LONG, feature);
        }
    }

    private String mask(String value, List<String> known, String feature) {
        try {
            return masker.mask(value, known);
        } catch (RuntimeException | StackOverflowError failure) {
            throw new AiException(Reason.MASKING_FAILED, feature);
        }
    }

    private JsonNode maskJson(JsonNode node, List<String> known, String feature) {
        if (node.isObject()) {
            ObjectNode result = AiJson.MAPPER.createObjectNode();
            node.properties().forEach(field -> {
                                if (!field.getKey().equals(mask(field.getKey(), known, feature))) {
                                    throw new AiException(Reason.MASKING_FAILED, feature);
                                }
                                result.set(field.getKey(), maskJson(field.getValue(), known, feature));
                            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = AiJson.MAPPER.createArrayNode();
            node.forEach(value -> result.add(maskJson(value, known, feature)));
            return result;
        }
        if (node.isTextual() || node.isNumber()) {
            String masked = mask(node.asText(), known, feature);
            return node.isNumber() && masked.equals(node.asText()) ? node : TextNode.valueOf(masked);
        }
        return node;
    }

    private static AiException invalid(String feature) {
        return new AiException(Reason.INVALID_RESPONSE, feature);
    }

    private static String stop(AiStopReason reason) {
        return reason == AiStopReason.MAX_TOKENS ? AiResult.STOP_MAX_TOKENS : "end_turn";
    }
}
