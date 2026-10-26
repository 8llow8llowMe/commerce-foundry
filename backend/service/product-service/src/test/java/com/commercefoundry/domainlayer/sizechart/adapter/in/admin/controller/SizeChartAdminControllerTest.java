package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 사이즈표 템플릿 · 상품 사이즈표의 HTTP 계약 — ADMIN 만, 필드 검증 봉투, 표가 없을 때 200 · dataBody null.
 */
@WebMvcTest({SizeChartTemplateAdminController.class, ProductSizeChartAdminController.class})
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class SizeChartAdminControllerTest {

    private static final String TEMPLATES = "/api/v1/admin/size-chart-templates";
    private static final String CHART = "/api/v1/admin/products/700/size-chart";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SizeChartAdminUseCase sizeChartAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/size-chart-templates",
            "GET, /api/v1/admin/size-chart-templates/1",
            "POST, /api/v1/admin/size-chart-templates",
            "PUT, /api/v1/admin/size-chart-templates/1",
            "DELETE, /api/v1/admin/size-chart-templates/1",
            "GET, /api/v1/admin/products/1/size-chart",
            "PUT, /api/v1/admin/products/1/size-chart"
        })
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, "{}", MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(sizeChartAdminUseCase);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/size-chart-templates",
            "POST, /api/v1/admin/size-chart-templates",
            "GET, /api/v1/admin/products/1/size-chart",
            "PUT, /api/v1/admin/products/1/size-chart"
        })
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, "{}", null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(sizeChartAdminUseCase);
        }
    }

    @Nested
    @DisplayName("본문 · 파라미터 검증")
    class Validation {

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {"name": "   ", "baseUnit": "CM", "columns": [{"name": "가슴", "displayOrder": 0}]} | PRODUCT_170
            {"name": "상의", "baseUnit": "CM", "columns": []}                                     | PRODUCT_174
            {"name": "상의", "columns": [{"name": "가슴", "displayOrder": 0}]}                    | PRODUCT_173
            """)
        @DisplayName("템플릿 생성 필드 검증은 400 이고 유스케이스에 닿지 않는다")
        void templateBody(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, TEMPLATES, body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(sizeChartAdminUseCase);
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {"templateId": 1, "rows": []}                                                                                          | PRODUCT_188
            {"templateId": 1, "rows": [{"sizeLabel": "44", "displayOrder": 0, "cells": [{"columnId": 1, "value": 0}]}]}           | PRODUCT_197
            {"templateId": 1, "rows": [{"sizeLabel": "44", "displayOrder": 0, "cells": [{"columnId": 1, "value": 1.001}]}]}       | PRODUCT_197
            {"templateId": 1, "rows": [{"sizeLabel": "44", "displayOrder": 0, "cells": [{"columnId": 1, "value": 10000}]}]}       | PRODUCT_197
            """)
        @DisplayName("상품 사이즈표 저장 필드 검증은 400 이고 유스케이스에 닿지 않는다")
        void chartBody(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.PUT, CHART, body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(sizeChartAdminUseCase);
        }

        @Test
        @DisplayName("page 가 음수면 400 · PRODUCT_101 이고 fieldErrors 가 page 를 짚는다")
        void pageMin() {
            MvcTestResult result = mvc.get().uri(TEMPLATES).param("page", "-1")
                .header(HttpHeaders.AUTHORIZATION, adminToken())
                .exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_101");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("page");
            verifyNoInteractions(sizeChartAdminUseCase);
        }
    }

    @Test
    @DisplayName("상품은 있고 사이즈표가 없으면 200 이고 dataBody 는 null 이다")
    void chartAbsent() {
        given(sizeChartAdminUseCase.getProductSizeChart(700L)).willReturn(null);

        MvcTestResult result = exchange(HttpMethod.GET, CHART, null, MemberRole.ADMIN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private MvcTestResult exchange(HttpMethod method, String uri, String body, MemberRole role) {
        var request = mvc.method(method).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (body != null && (method == HttpMethod.POST || method == HttpMethod.PUT)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static String adminToken() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
    }
}
