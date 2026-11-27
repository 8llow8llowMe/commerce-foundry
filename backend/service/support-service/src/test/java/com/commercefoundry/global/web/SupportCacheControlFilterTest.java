package com.commercefoundry.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SupportCacheControlFilterTest {
    private final SupportCacheControlFilter filter = new SupportCacheControlFilter();

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/support",
        "/api/v1/support/guest-sessions",
        "/api/v1/support/conversations",
        "/api/v1/support/conversations/123/messages",
        "/api/v1/%73upport/guest-sessions",
        "/api/v1/support;x=1/guest-sessions"
    })
    void supportPathsAreNoStore(String uri) throws Exception {
        assertThat(cacheControl(uri)).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/products/1", "/api/v1/supporting/1", "/api/v1/admin/support/knowledge", "/actuator/health"})
    void otherPathsAreUntouched(String uri) throws Exception {
        assertThat(cacheControl(uri)).isNull();
    }

    private String cacheControl(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader("Cache-Control");
    }
}
