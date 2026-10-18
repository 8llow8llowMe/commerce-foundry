package com.commercefoundry.common.web.servlet;

import static com.commercefoundry.common.support.ErrorPageEnvelopeSmokeTestSupport.assertEnvelope;
import static com.commercefoundry.common.web.servlet.FallbackErrorControllerContainerTest.send;

import com.commercefoundry.common.exception.CommonErrorCode;
import com.commercefoundry.common.web.servlet.FallbackErrorControllerContainerTest.ThrowingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 에러 경로를 {@code spring.web.error.path} 로 바꾼 서비스에서도 봉투가 나가는지 본다. Boot 4 의 {@code ErrorPageCustomizer} 는 이 키로 에러 페이지를 등록한다 —
 * 컨트롤러 매핑 키가 갈라지면(Boot 3 의 {@code server.error.path} 등) 에러 디스패치가 매핑 없는 경로가 돼 봉투가 아니게 된다.
 */
@SpringBootTest(classes = FallbackErrorControllerContainerTest.ContainerSmokeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.web.error.path=" + FallbackErrorControllerCustomPathContainerTest.CUSTOM_ERROR_PATH)
class FallbackErrorControllerCustomPathContainerTest {

    static final String CUSTOM_ERROR_PATH = "/custom-error";

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("필터가 sendError(400) 로 끝낸 요청은 바꾼 에러 경로에서도 400 · COMMON_004 봉투다")
    void filterSendErrorOnCustomErrorPath() throws Exception {
        assertEnvelope(send(port, "GET", ThrowingFilter.REJECT_PATH, null), ThrowingFilter.REJECT_PATH, 400, CommonErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("바꾼 에러 경로를 직접 부르면 404 · COMMON_001 이다")
    void directCustomErrorPath() throws Exception {
        assertEnvelope(send(port, "GET", CUSTOM_ERROR_PATH, null), CUSTOM_ERROR_PATH, 404, CommonErrorCode.PATH_NOT_FOUND);
    }
}
