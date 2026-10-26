package com.commercefoundry.domainlayer.sizechart.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartCellItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item.ProductSizeChartRowItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response.ProductSizeChartResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartUnitCode;
import com.commercefoundry.domainlayer.sizechart.application.port.in.ProductSizeChartWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 고객 상품 사이즈표 — 익명 조회, 잘못된 unit 은 PRODUCT_198, 표가 없으면 200 · dataBody null, 치수는 JSON number. */
@WebMvcTest(ProductSizeChartWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductSizeChartWebControllerTest {

    private static final String PATH = "/api/v1/products/700/size-chart";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductSizeChartWebUseCase productSizeChartWebUseCase;

    @ParameterizedTest(name = "unit={0}")
    @ValueSource(strings = {"MM", "FOO"})
    @DisplayName("unit 이 CM · INCH 가 아니면 400 · PRODUCT_198 이고 유스케이스를 부르지 않는다")
    void invalidUnit(String unit) {
        MvcTestResult result = mvc.get().uri(PATH).param("unit", unit).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_198");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo("PRODUCT_198");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("unit");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(productSizeChartWebUseCase);
    }

    @Test
    @DisplayName("보이는 상품에 표가 없으면 200 이고 dataBody 는 null 이다")
    void absent() {
        given(productSizeChartWebUseCase.getSizeChart(700L, null)).willReturn(null);

        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("치수는 JSON number 이고 단위는 code · name · description 이다")
    void valueIsJsonNumber() {
        given(productSizeChartWebUseCase.getSizeChart(700L, SizeChartUnitCode.INCH)).willReturn(ProductSizeChartResponse.builder()
            .productId("700")
            .templateId("10")
            .templateName("상의")
            .baseUnit(CodeNameDescriptionMetadata.of("CM", "센티미터", "치수를 센티미터로 둡니다."))
            .displayUnit(CodeNameDescriptionMetadata.of("INCH", "인치", "치수를 인치로 둡니다."))
            .columns(List.of())
            .rows(List.of(ProductSizeChartRowItem.builder()
                .sizeLabel("44")
                .displayOrder(0)
                .cells(List.of(ProductSizeChartCellItem.builder().columnId("101").value(new BigDecimal("34.06")).build()))
                .build()))
            .build());

        MvcTestResult result = mvc.get().uri(PATH).param("unit", "INCH").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.baseUnit.code").isEqualTo("CM");
        assertThat(result).bodyJson().extractingPath("$.dataBody.displayUnit.code").isEqualTo("INCH");
        assertThat(result).bodyJson().extractingPath("$.dataBody.rows[0].cells[0].value").asNumber()
            .satisfies(value -> assertThat(new BigDecimal(value.toString())).isEqualByComparingTo("34.06"));
    }
}
