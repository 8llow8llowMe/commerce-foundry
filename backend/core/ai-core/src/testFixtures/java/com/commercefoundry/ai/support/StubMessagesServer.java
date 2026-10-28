package com.commercefoundry.ai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Messages API 를 흉내 내는 루프백 HTTP 서버 — 실제 API 대신 SDK 의 요청 · 재시도 · 응답 해석을 그대로 거친다.
 * 응답을 차례로 쌓아 두고, 받은 요청(헤더 · 본문)을 기록한다. 쌓인 응답이 없으면 마지막 응답을 되풀이한다.
 *
 * <p>ai-core 테스트 픽스처다 — 다른 모듈은 {@code testImplementation testFixtures(project(':core:ai-core'))} 로 받는다. 클라이언트는
 * {@code ai.base-url=}{@link #baseUrl()} 과 {@code ai.allow-insecure-loopback=true} 로 이 서버를 가리킨다. 픽스처 값은 가짜다.
 */
public final class StubMessagesServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply last;

    public StubMessagesServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubMessagesServer reply(Reply reply) {
        replies.add(reply);
        return this;
    }

    public List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 성공 응답. 사용량은 입력 12 · 출력 5 토큰이고 캐시 토큰은 없다. */
    public static Reply ok(String text, String stopReason) {
        return okWithUsage(text, stopReason, "{\"input_tokens\":12,\"output_tokens\":5}");
    }

    /** 캐시 토큰까지 실은 성공 응답. */
    public static Reply ok(String text, String stopReason, long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
        return okWithUsage(text, stopReason, "{\"input_tokens\":%d,\"output_tokens\":%d,\"cache_creation_input_tokens\":%d,\"cache_read_input_tokens\":%d}"
            .formatted(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
    }

    /** 오류 응답. {@code retry-after-ms: 1} 을 실어 SDK 재시도가 기다리지 않게 한다. */
    public static Reply error(int status, String type) {
        return new Reply(status, """
            {"type":"error","error":{"type":"%s","message":"stub error body"}}""".formatted(type), Map.of("retry-after-ms", "1"), 0, null);
    }

    private static Reply okWithUsage(String text, String stopReason, String usage) {
        return new Reply(200, """
            {"id":"msg_stub","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":%s}],
             "stop_reason":"%s","stop_sequence":null,
             "usage":%s}""".formatted(quote(text), stopReason, usage), Map.of(), 0, null);
    }

    /**
     * 쌓아 둘 응답 하나.
     *
     * @param delayMillis 응답 전에 기다리는 시간 — 응답 타임아웃을 흉내 낸다
     * @param hold        풀릴 때까지(최대 10초) 응답을 붙잡는다 — 동시 상한을 채운다
     */
    public record Reply(int status, String body, Map<String, String> headers, long delayMillis, CountDownLatch hold) {

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis, hold);
        }

        public Reply heldUntil(CountDownLatch latch) {
            return new Reply(status, body, headers, delayMillis, latch);
        }
    }

    /** 받은 요청 하나. */
    public record Received(Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> String.join(",", entry.getValue())).findFirst().orElse(null);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = last;
        }
        last = reply;
        try {
            if (reply.hold() != null) {
                reply.hold().await(10, TimeUnit.SECONDS);
            }
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 끊었다
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
