package com.commercefoundry.domainlayer.look.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookImageItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookProductItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookSummaryResponse;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 룩 조회의 HTTP 계약 — 익명 허용, 목록 slice 모양 · 기본 page/size, page · size 검증(서비스 공용 PRODUCT_101/102), 상세 404 봉투, 공유 캐시 헤더.
 */
@WebMvcTest(LookWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class LookWebControllerTest {

    private static final String CACHE = "max-age=60, public";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LookWebUseCase lookWebUseCase;

    @Test
    @DisplayName("목록은 익명 200 — slice(contents · hasNext), 아이디 String, 상품 카드 · 공유 캐시 헤더. 기본 page=0 · size=20")
    void list() {
        given(lookWebUseCase.getLooks(0, 20)).willReturn(new SliceResponse<>(List.of(summary()), true));

        MvcTestResult result = mvc.get().uri("/api/v1/looks").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, CACHE);
        assertThat(result).bodyJson().extractingPath("$.dataBody.hasNext").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].lookId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].image.url").isEqualTo("https://cdn.example.com/looks/a.jpg");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].products[0].productId").isEqualTo("700");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].products[0].saleStatus.code").isEqualTo("SOLD_OUT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0]").asMap().doesNotContainKeys("description", "displayOrder", "visible");
        verify(lookWebUseCase).getLooks(0, 20);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"page=-1, PRODUCT_101", "size=0, PRODUCT_102", "size=101, PRODUCT_102", "page=abc, PRODUCT_198"})
    @DisplayName("page · size 검증은 서비스 공용 코드다")
    void pagingValidation(String query, String code) {
        MvcTestResult result = mvc.get().uri("/api/v1/looks?" + query).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(lookWebUseCase);
    }

    @Test
    @DisplayName("상세는 익명 200 · 설명 포함 · 공유 캐시 헤더다")
    void detail() {
        given(lookWebUseCase.getLook(5L)).willReturn(LookDetailResponse.builder()
            .lookId("5").title("가을 룩").description("첫 줄\n둘째 줄").image(image()).products(List.of()).build());

        MvcTestResult result = mvc.get().uri("/api/v1/looks/5").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, CACHE);
        assertThat(result).bodyJson().extractingPath("$.dataBody.description").isEqualTo("첫 줄\n둘째 줄");
        assertThat(result).bodyJson().extractingPath("$.dataBody.products").asArray().isEmpty();
    }

    @Test
    @DisplayName("없거나 숨긴 룩(플래그 꺼짐 포함)은 404 · LOOK_001 봉투다")
    void notFound() {
        given(lookWebUseCase.getLook(9L)).willThrow(new LookException(LookErrorCode.NOT_FOUND_LOOK));

        MvcTestResult result = mvc.get().uri("/api/v1/looks/9").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("LOOK_001");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("존재하지 않는 룩입니다.");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("경로 아이디가 0 이하면 400 · LOOK_118 이다")
    void nonPositiveLookId() {
        MvcTestResult result = mvc.get().uri("/api/v1/looks/0").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("LOOK_118");
        verifyNoInteractions(lookWebUseCase);
    }

    private static LookSummaryResponse summary() {
        return LookSummaryResponse.builder()
            .lookId("4611686018427387904")
            .title("가을 룩")
            .image(image())
            .products(List.of(LookProductItem.builder()
                .productId("700").name("저고리").salePrice(MoneyItem.of(39_000L, "KRW")).saleStatus(SaleAvailability.SOLD_OUT.toMetadata())
                .build()))
            .build();
    }

    private static LookImageItem image() {
        return LookImageItem.builder().url("https://cdn.example.com/looks/a.jpg").width(1200).height(1600).build();
    }
}
