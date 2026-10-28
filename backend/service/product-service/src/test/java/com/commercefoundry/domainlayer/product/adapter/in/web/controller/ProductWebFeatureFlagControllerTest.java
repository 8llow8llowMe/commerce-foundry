package com.commercefoundry.domainlayer.product.adapter.in.web.controller;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.product.adapter.in.web.presenter.ProductWebPresenter;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.service.ProductWebFacade;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 스토어 기능 플래그(프리오더 · 상품 영상)가 고객 상품 응답 JSON 에 닿는지 본다. 유스케이스를 모킹하지 않고 Facade · Processor · Presenter 를
 * 실제로 띄우고 아웃 포트만 모킹한다 — 플래그 분기(Processor) → 변환(Presenter) → 직렬화(Jackson)를 한 번에 확인한다.
 * 플래그는 {@link StoreProperties} 를 모킹해 테스트마다 바꾼다. 요청은 모두 토큰 없이(익명) 보낸다 — 공개 API 다.
 */
@WebMvcTest(ProductWebController.class)
@Import({
    ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class,
    ProductWebFacade.class, ProductListProcessor.class, ProductDetailProcessor.class, ProductWebPresenter.class
})
class ProductWebFeatureFlagControllerTest {

    private static final long PRODUCT_ID = 7301L;
    private static final LocalDate SHIP_DATE = LocalDate.of(2026, 11, 20);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StoreProperties storeProperties;

    @MockitoBean
    private CategoryProcessor categoryProcessor;

    @MockitoBean
    private ProductRepositoryPort productRepositoryPort;

    @MockitoBean
    private ProductOptionRepositoryPort productOptionRepositoryPort;

    @MockitoBean
    private SkuRepositoryPort skuRepositoryPort;

    @MockitoBean
    private StockRepositoryPort stockRepositoryPort;

    @MockitoBean
    private ProductImageRepositoryPort productImageRepositoryPort;

    @MockitoBean
    private ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;

    @Nested
    @DisplayName("프리오더")
    class Preorder {

        @Test
        @DisplayName("플래그가 켜지면 목록 preorder 는 프리오더 상품만 true 이고, 상세 preorder.expectedShipDate 는 yyyy-MM-dd 문자열이다")
        void flagOn() {
            givenFeatures(false, true, false);
            givenListRows(row(1L, true), row(2L, false));
            givenDetail(ProductFixtures.preorderProduct(PRODUCT_ID, SHIP_DATE), List.of());

            MvcTestResult list = mvc.get().uri("/api/v1/products").exchange();
            MvcTestResult detail = mvc.get().uri("/api/v1/products/" + PRODUCT_ID).exchange();

            assertThat(list).hasStatusOk();
            assertThat(list).bodyJson().extractingPath("$.dataBody.contents[0].preorder").asBoolean().isTrue();
            assertThat(list).bodyJson().extractingPath("$.dataBody.contents[1].preorder").asBoolean().isFalse();
            assertThat(detail).hasStatusOk();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.preorder.expectedShipDate").asString().isEqualTo("2026-11-20");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.customOrder").isNull();
        }

        @Test
        @DisplayName("플래그가 켜지고 발송 예정일이 없으면 preorder 는 객체이고 expectedShipDate 만 null 이다 — 발송일 추후 안내")
        void flagOnWithoutShipDate() {
            givenFeatures(false, true, false);
            givenDetail(ProductFixtures.preorderProduct(PRODUCT_ID, null), List.of());

            MvcTestResult detail = mvc.get().uri("/api/v1/products/" + PRODUCT_ID).exchange();

            assertThat(detail).hasStatusOk();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.preorder").isNotNull();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.preorder.expectedShipDate").isNull();
        }

        @Test
        @DisplayName("플래그가 꺼지면 프리오더 상품이어도 목록 preorder 는 false, 상세 preorder 는 null 이다")
        void flagOff() {
            givenFeatures(true, false, true);
            givenListRows(row(1L, true));
            givenDetail(ProductFixtures.preorderProduct(PRODUCT_ID, SHIP_DATE), List.of());

            MvcTestResult list = mvc.get().uri("/api/v1/products").exchange();
            MvcTestResult detail = mvc.get().uri("/api/v1/products/" + PRODUCT_ID).exchange();

            assertThat(list).hasStatusOk();
            assertThat(list).bodyJson().extractingPath("$.dataBody.contents[0].preorder").asBoolean().isFalse();
            assertThat(detail).hasStatusOk();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.preorder").isNull();
        }
    }

    @Nested
    @DisplayName("상품 영상")
    class ProductVideo {

        /** 포트 계약대로 유형을 가리지 않고 노출 순서 → 아이디 오름차순이다. 이미지 11 과 영상 21 은 순서가 같다. */
        private void givenMixedMedia() {
            givenDetail(ProductFixtures.product(PRODUCT_ID), List.of(
                ProductFixtures.image(10, PRODUCT_ID, ImageType.THUMBNAIL, 0),
                ProductFixtures.image(11, PRODUCT_ID, ImageType.GALLERY, 1),
                ProductFixtures.video(21, PRODUCT_ID, 1),
                ProductFixtures.image(12, PRODUCT_ID, ImageType.GALLERY, 2)
            ));
        }

        @Test
        @DisplayName("플래그가 켜지면 영상이 mediaType VIDEO · posterUrl 과 함께 노출 순서대로 이미지와 섞여 나오고, 이미지는 IMAGE · posterUrl null 이다")
        void flagOn() {
            givenFeatures(false, false, true);
            givenMixedMedia();

            MvcTestResult detail = mvc.get().uri("/api/v1/products/" + PRODUCT_ID).exchange();

            assertThat(detail).hasStatusOk();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[*].imageId").asArray().containsExactly("11", "21", "12");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[*].mediaType").asArray().containsExactly("IMAGE", "VIDEO", "IMAGE");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[1].url").asString().isEqualTo("https://cdn.example.com/21.mp4");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[1].posterUrl").asString().isEqualTo("https://cdn.example.com/21-poster.jpg");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[1].displayOrder").asNumber().isEqualTo(1);
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[0].posterUrl").isNull();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.thumbnail.imageId").asString().isEqualTo("10");
        }

        @Test
        @DisplayName("플래그가 꺼지면 영상이 빠지고 이미지만 노출 순서대로 나온다")
        void flagOff() {
            givenFeatures(true, true, false);
            givenMixedMedia();

            MvcTestResult detail = mvc.get().uri("/api/v1/products/" + PRODUCT_ID).exchange();

            assertThat(detail).hasStatusOk();
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[*].imageId").asArray().containsExactly("11", "12");
            assertThat(detail).bodyJson().extractingPath("$.dataBody.images[*].mediaType").asArray().containsOnly("IMAGE");
        }
    }

    private void givenFeatures(boolean customOrder, boolean preorder, boolean productVideo) {
        given(storeProperties.features()).willReturn(new StoreProperties.Features(customOrder, preorder, productVideo, false));
    }

    private void givenListRows(ProductListQueryResult... rows) {
        given(productRepositoryPort.searchVisible(any())).willReturn(new SliceImpl<>(List.of(rows), PageRequest.of(0, 20), false));
    }

    /** 옵션 · SKU · 재고 · 카테고리 경로는 모킹 기본값(빈 목록)이다 — 판매 상태는 판매 종료로 나오지만 이 테스트의 관심 밖이다. */
    private void givenDetail(Product product, List<ProductImage> images) {
        given(productRepositoryPort.findVisibleById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(images);
    }

    private static ProductListQueryResult row(long productId, boolean preorder) {
        return new ProductListQueryResult(productId, "상품" + productId, ProductFixtures.BASE_PRICE, KRW, null, null, SaleStatus.ON_SALE, false, preorder);
    }
}
