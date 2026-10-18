package com.commercefoundry.common.web.servlet;

import static com.commercefoundry.common.support.ErrorPageEnvelopeSmokeTestSupport.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.exception.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 실제 서블릿 컨테이너(Tomcat, RANDOM_PORT)에서 MVC 밖 오류가 {@code /error} 에러 디스패치를 거쳐 봉투로 나가는지 본다.
 * MockMvc 는 에러 디스패치를 하지 않아 이 경로를 볼 수 없다 — 그래서 MockMvc 스모크에서 필터 오류는 빈 본문이다.
 *
 * <p>보안이 없는 최소 앱이다. 방화벽 거부 · 보안 체인 통과는 서비스의 {@code *ErrorPageEnvelopeSmokeTest} 가 서비스 구성 그대로 본다.
 * 봉투 단언은 그 스모크와 같은 것({@code ErrorPageEnvelopeSmokeTestSupport#assertEnvelope})을 쓴다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = FallbackErrorControllerContainerTest.ContainerSmokeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FallbackErrorControllerContainerTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Boot 의 BasicErrorController 는 빠지고 ErrorController 는 FallbackErrorController 하나다")
    void replacesBasicErrorController() {
        assertThat(applicationContext.getBeansOfType(ErrorController.class).values())
            .singleElement().isInstanceOf(FallbackErrorController.class);
    }

    @Test
    @DisplayName("필터가 던진 예외는 500 · COMMON_000 봉투이고 원인을 응답에 싣지 않는다 — 로그에는 원인과 가린 경로가 남는다")
    void filterException(CapturedOutput output) throws Exception {
        String path = ThrowingFilter.FAILURE_PATH + "/4611686018427387904";
        HttpResponse<byte[]> response = send(port, "GET", path, null);

        assertEnvelope(response, path, 500, CommonErrorCode.INTERNAL_ERROR);
        assertThat(new String(response.body())).doesNotContain(ThrowingFilter.FAILURE_DETAIL).doesNotContain("IllegalStateException");
        assertThat(output).contains("unhandled exception answered by error page envelope").contains(ThrowingFilter.FAILURE_DETAIL)
            .contains("path=/container-smoke/failure/x/…").doesNotContain("4611686018427387904");
    }

    @Test
    @DisplayName("필터가 sendError(400) 로 끝낸 요청(방화벽 거부와 같은 모양)은 Accept: text/html 이어도 400 · COMMON_004 JSON 봉투다")
    void filterSendErrorWithHtmlAccept() throws Exception {
        String path = ThrowingFilter.REJECT_PATH;

        assertEnvelope(send(port, "GET", path, MediaType.TEXT_HTML_VALUE), path, 400, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("원래 요청이 POST 여도 에러 디스패치는 봉투로 끝난다 — /error 는 모든 메서드를 받는다")
    void filterSendErrorOnPost() throws Exception {
        assertEnvelope(send(port, "POST", ThrowingFilter.REJECT_PATH, null), ThrowingFilter.REJECT_PATH, 400, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("직접 GET /error 는 404 · COMMON_001 이다")
    void directErrorPath() throws Exception {
        assertEnvelope(send(port, "GET", "/error", null), "/error", 404, CommonErrorCode.PATH_NOT_FOUND);
    }

    @Test
    @DisplayName("매핑 없는 경로는 컨테이너에서도 404 · COMMON_001 이다 — MVC 안 오류는 폴백 리졸버가 끝내 /error 로 가지 않는다")
    void unmappedPath() throws Exception {
        String path = "/container-smoke-missing";

        assertEnvelope(send(port, "GET", path, null), path, 404, CommonErrorCode.PATH_NOT_FOUND);
    }

    /** 컨테이너 테스트들이 같이 쓴다({@link FallbackErrorControllerCustomPathContainerTest}). {@code accept} 는 {@code null} 이면 보내지 않는다. */
    static HttpResponse<byte[]> send(int port, String method, String path, String accept) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            request.header(HttpHeaders.ACCEPT, accept);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ContainerSmokeApplication {

        @Bean
        ThrowingFilter throwingFilter() {
            return new ThrowingFilter();
        }
    }

    /** DispatcherServlet 앞에서 오류를 내는 필터 — 서비스의 보안 필터 · 방화벽 자리에 선다. ERROR 디스패치는 거르지 않는다(기본값). */
    static class ThrowingFilter extends OncePerRequestFilter {

        static final String FAILURE_PATH = "/container-smoke/failure/x";
        static final String REJECT_PATH = "/container-smoke/reject";
        static final String FAILURE_DETAIL = "jdbc:postgresql://db.internal:5432/orders password=container-smoke";

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            if (path.startsWith(FAILURE_PATH)) {
                throw new IllegalStateException(FAILURE_DETAIL);
            }
            if (path.startsWith(REJECT_PATH)) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
