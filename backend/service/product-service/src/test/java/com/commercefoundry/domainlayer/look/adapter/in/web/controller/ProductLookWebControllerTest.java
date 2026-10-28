package com.commercefoundry.domainlayer.look.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 상품별 룩의 HTTP 계약 — 없거나 숨긴 상품은 상품 상세와 같은 404 · PRODUCT_001(상품 advice 가 봉투로), 룩이 없으면 200 빈 배열 · 공유 캐시 헤더.
 */
@WebMvcTest(ProductLookWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductLookWebControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LookWebUseCase lookWebUseCase;

    @Test
    @DisplayName("숨기거나 없는 상품은 404 · PRODUCT_001 봉투다")
    void hiddenProduct() {
        given(lookWebUseCase.getLooksOfProduct(9L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));

        MvcTestResult result = mvc.get().uri("/api/v1/products/9/looks").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_001");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("룩이 없으면(또는 룩 기능이 꺼지면) 익명 200 · looks 빈 배열 · 공유 캐시 헤더다")
    void emptyList() {
        given(lookWebUseCase.getLooksOfProduct(7L)).willReturn(ProductLookListResponse.builder().looks(List.of()).build());

        MvcTestResult result = mvc.get().uri("/api/v1/products/7/looks").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "max-age=60, public");
        assertThat(result).bodyJson().extractingPath("$.dataBody.looks").asArray().isEmpty();
    }
}
