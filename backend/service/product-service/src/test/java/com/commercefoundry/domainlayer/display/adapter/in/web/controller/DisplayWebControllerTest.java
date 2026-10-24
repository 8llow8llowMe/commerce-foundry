package com.commercefoundry.domainlayer.display.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayElementItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayLinkItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayMediaItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayProductItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplaySectionItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayWebUseCase;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 메인 전시의 HTTP 계약 — 익명 · 회원 모두 200, 응답 모양(아이디 String · 섹션 유형 metadata · 아이템 유형 문자열 · link 묶음), Cache-Control.
 */
@WebMvcTest(DisplayWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class DisplayWebControllerTest {

    private static final String PATH = "/api/v1/displays/main";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DisplayWebUseCase displayWebUseCase;

    @Test
    @DisplayName("익명이면 200 — 섹션 유형은 metadata, 아이템 유형 · 미디어 종류는 문자열, 아이디는 String 이고 Cache-Control 은 max-age=60, public 이다")
    void anonymousGetsMain() {
        given(displayWebUseCase.getMain()).willReturn(response());

        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "max-age=60, public");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].sectionId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].sectionType.code").isEqualTo("HERO");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].sectionType.name").isEqualTo("히어로");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].link.url").isEqualTo("/products");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].itemId").isEqualTo("11");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].itemType").isEqualTo("MEDIA");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].media.mediaType").isEqualTo("IMAGE");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].product").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].link").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].items[0].itemType").isEqualTo("PRODUCT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].items[0].media").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].items[0].product.productId").isEqualTo("700");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].items[0].product.salePrice.amount").isEqualTo(39000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.sections[1].items[0].product.saleStatus.code").isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("회원 토큰이 있어도 같은 응답이다")
    void memberGetsSameMain() {
        given(displayWebUseCase.getMain()).willReturn(response());

        MvcTestResult result = mvc.get().uri(PATH)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue())
            .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "max-age=60, public");
    }

    private static DisplayMainResponse response() {
        DisplaySectionItem hero = DisplaySectionItem.builder()
            .sectionId("4611686018427387904")
            .sectionType(DisplaySectionType.HERO.toMetadata())
            .title("가을")
            .link(DisplayLinkItem.builder().url("/products").label("보기").build())
            .items(List.of(DisplayElementItem.builder()
                .itemId("11").itemType("MEDIA")
                .media(DisplayMediaItem.builder().mediaType("IMAGE").pcUrl("https://cdn.example.com/a.jpg").build())
                .build()))
            .build();
        DisplaySectionItem pick = DisplaySectionItem.builder()
            .sectionId("2")
            .sectionType(DisplaySectionType.PRODUCT_PICK.toMetadata())
            .items(List.of(DisplayElementItem.builder()
                .itemId("21").itemType("PRODUCT")
                .product(DisplayProductItem.builder()
                    .productId("700").name("저고리").salePrice(MoneyItem.of(39_000L, "KRW")).saleStatus(SaleAvailability.ON_SALE.toMetadata()).build())
                .build()))
            .build();
        return DisplayMainResponse.builder().sections(List.of(hero, pick)).build();
    }
}
