package com.commercefoundry.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.display.adapter.in.web.controller.DisplayWebController;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayWebUseCase;
import com.commercefoundry.domainlayer.look.adapter.in.web.controller.LookWebController;
import com.commercefoundry.domainlayer.look.adapter.in.web.controller.ProductLookWebController;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.controller.ProductSizeChartWebController;
import com.commercefoundry.domainlayer.sizechart.application.port.in.ProductSizeChartWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 메인 전시 · 룩 · 사이즈표 고객 조회가 {@code Accept-Language} 로 표시 언어를 고르고 {@code Vary} 를 싣는지 (#20).
 * 헤더가 없으면 서버 기본 언어가 아니라 한국어이고, 요청이 끝나면 언어가 다음 요청으로 새지 않는다.
 */
@WebMvcTest({DisplayWebController.class, LookWebController.class, ProductLookWebController.class, ProductSizeChartWebController.class})
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class CustomerLocaleWebTest {

    @Autowired MockMvcTester mvc;
    @MockitoBean DisplayWebUseCase displays;
    @MockitoBean LookWebUseCase looks;
    @MockitoBean ProductSizeChartWebUseCase sizeCharts;

    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("en-US", "en"), Arguments.of("en-GB", "en"), Arguments.of("ko-KR", "ko"), Arguments.of(null, "ko"));
    }

    @ParameterizedTest(name = "Accept-Language={0} → {1}")
    @MethodSource("cases")
    @DisplayName("다섯 조회 모두 요청 언어로 유스케이스를 부르고 Vary: Accept-Language 를 싣는다")
    void choosesLocaleAndVaries(String acceptLanguage, String expectedLanguage) {
        AtomicReference<Locale> seen = new AtomicReference<>();
        given(displays.getMain()).willAnswer(call -> {
            seen.set(LocaleContextHolder.getLocale());
            return DisplayMainResponse.builder().sections(List.of()).build();
        });
        given(looks.getLooks(anyInt(), anyInt())).willAnswer(call -> {
            seen.set(LocaleContextHolder.getLocale());
            return com.commercefoundry.persistence.dto.SliceResponse.of(new SliceImpl<>(List.of()));
        });
        given(looks.getLook(anyLong())).willAnswer(call -> {
            seen.set(LocaleContextHolder.getLocale());
            return LookDetailResponse.builder().lookId("1").products(List.of()).build();
        });
        given(looks.getLooksOfProduct(anyLong())).willAnswer(call -> {
            seen.set(LocaleContextHolder.getLocale());
            return ProductLookListResponse.builder().looks(List.of()).build();
        });
        given(sizeCharts.getSizeChart(anyLong(), isNull())).willAnswer(call -> {
            seen.set(LocaleContextHolder.getLocale());
            return null;
        });

        for (String path : List.of("/api/v1/displays/main", "/api/v1/looks", "/api/v1/looks/1", "/api/v1/products/7/looks", "/api/v1/products/7/size-chart")) {
            seen.set(null);
            var request = mvc.get().uri(path);
            if (acceptLanguage != null) {
                request = request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
            }
            var result = request.exchange();

            assertThat(result).as(path).hasStatusOk();
            assertThat(result.getResponse().getHeaders(HttpHeaders.VARY)).as(path).anySatisfy(vary -> assertThat(vary).contains("Accept-Language"));
            assertThat(seen.get()).as(path).isNotNull();
            assertThat(seen.get().getLanguage()).as(path).isEqualTo(expectedLanguage);
        }
    }
}
