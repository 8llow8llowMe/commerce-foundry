package com.commercefoundry.apigateway.ratelimit;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 하류 서비스 자리에 띄우는 가짜 서버 — 게이트웨이가 실제로 보낸 요청 헤더를 그대로 기록한다 (#135 하류 헤더 계약 테스트).
 * 헤더 이름은 소문자로 바꿔 두고, 같은 이름이 여러 줄로 오면 줄마다 값 하나로 남긴다.
 */
final class UpstreamHeaderRecorder {

    private static final List<String> SERVICE_IDS = List.of("auth-service", "product-service", "order-service", "payment-service", "delivery-service");

    private final Queue<Map<String, List<String>>> requests = new ConcurrentLinkedQueue<>();
    private final HttpServer server;

    UpstreamHeaderRecorder() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            requests.add(headers);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** 서비스 5종의 인스턴스를 이 서버로 돌린다. */
    void register(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + server.getAddress().getPort();
        for (String serviceId : SERVICE_IDS) {
            registry.add("spring.cloud.discovery.client.simple.instances." + serviceId + "[0].uri", () -> uri);
        }
    }

    void clear() {
        requests.clear();
    }

    void stop() {
        server.stop(0);
    }

    /** 하류가 받은 요청 수. */
    int requestCount() {
        return requests.size();
    }

    /** 하류가 받은 요청 하나의 헤더 값들. 요청이 정확히 하나여야 한다. */
    List<String> onlyRequestHeader(String name) {
        if (requests.size() != 1) {
            throw new AssertionError("하류 요청이 하나가 아니다: " + requests.size());
        }
        return requests.peek().getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    /** 클라이언트가 전달 헤더를 꾸며 보낸다 — 하류에 무엇이 닿는지 볼 때 쓴다. */
    static void spoofForwarding(HttpHeaders headers) {
        headers.add("X-Forwarded-For", "198.51.100.7");
        headers.add("X-Forwarded-Host", "spoofed.example");
        headers.add("X-Forwarded-Proto", "https");
        headers.add("Forwarded", "for=198.51.100.7;host=spoofed.example");
    }

    /** 전달 헤더({@code X-Forwarded-*} · {@code Forwarded})인지 — 소문자 이름을 받는다. */
    static boolean isForwardingHeader(String lowerCaseName) {
        return lowerCaseName.startsWith("x-forwarded-") || lowerCaseName.equals("forwarded");
    }

    /** 하류가 받은 요청 하나의 헤더 이름(소문자). 요청이 정확히 하나여야 한다. */
    Set<String> onlyRequestHeaderNames() {
        if (requests.size() != 1) {
            throw new AssertionError("하류 요청이 하나가 아니다: " + requests.size());
        }
        return requests.peek().keySet();
    }

    /**
     * 클라이언트 라이브러리가 헤더 이름을 정규화하지 않도록 HTTP/1.1 요청을 소켓으로 직접 보낸다 — 대소문자가 다른 같은 이름 헤더를 그대로 실어 본다.
     *
     * @param headerLines {@code "Name: value"} 꼴의 줄
     * @return 응답 상태 줄
     */
    static String rawGet(int port, String path, List<String> headerLines) {
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: 127.0.0.1:").append(port).append("\r\n")
            .append("Connection: close\r\n");
        headerLines.forEach(line -> request.append(line).append("\r\n"));
        request.append("\r\n");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return response.substring(0, response.indexOf("\r\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
