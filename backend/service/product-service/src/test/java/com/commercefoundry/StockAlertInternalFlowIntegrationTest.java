package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockAlertPolicyRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAlertPolicyEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 빈 구성으로 정책 CRUD·목록·오류 봉투를 잇고 재고 수량을 바꾸지 않는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StockAlertInternalFlowIntegrationTest {

    private static final long PRODUCT_ID = 4611686018427387904L;
    private static final long SKU_ID = 4611686018427387905L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvcTester mvc;
    @Autowired private ProductRepository products;
    @Autowired private SkuRepository skus;
    @Autowired private StockRepository stocks;
    @Autowired private StockAlertPolicyRepository policies;

    @BeforeEach
    void setUp() {
        products.saveAndFlush(ProductEntity.builder().id(PRODUCT_ID).categoryId(1L).name("점검 상품").saleStatus(SaleStatus.ON_SALE)
            .visible(true).salePriceAmount(1000).salePriceCurrency(CurrencyCode.KRW).build());
        skus.saveAndFlush(SkuEntity.builder().id(SKU_ID).productId(PRODUCT_ID).optionValueKey("")
            .additionalPriceCurrency(CurrencyCode.KRW).saleStatus(SaleStatus.ON_SALE).build());
        stocks.saveAndFlush(StockEntity.builder().skuId(SKU_ID).stockManaged(true).availableQuantity(2).build());
    }

    @AfterEach
    void tearDown() {
        policies.deleteById(SKU_ID);
        stocks.deleteById(SKU_ID);
        skus.deleteById(SKU_ID);
        products.deleteById(PRODUCT_ID);
    }

    @Test
    void internalCursorRoundTrip() {
        policies.saveAndFlush(StockAlertPolicyEntity.builder().skuId(SKU_ID).thresholdQuantity(2).build());
        long second = SKU_ID + 1;
        skus.saveAndFlush(SkuEntity.builder().id(second).productId(PRODUCT_ID).optionValueKey("10_20")
            .additionalPriceCurrency(CurrencyCode.KRW).saleStatus(SaleStatus.STOP_SELLING).build());
        policies.saveAndFlush(StockAlertPolicyEntity.builder().skuId(second).thresholdQuantity(0).build());
        try {
            Instant before = Instant.now();
            var first = internal("?size=1", operations());
            assertThat(first).hasStatusOk();
            var page = body(first).at("/dataBody");
            assertThat(Instant.parse(page.at("/observedAt").asString())).isBetween(before, Instant.now());
            assertThat(page.at("/contents").size()).isOne();
            assertThat(page.at("/contents/0/skuId").isString()).isTrue();
            assertThat(page.at("/contents/0/skuId").asString()).isEqualTo(String.valueOf(SKU_ID));
            assertThat(page.at("/contents/0/productId").asString()).isEqualTo(String.valueOf(PRODUCT_ID));
            assertThat(page.at("/contents/0/productName").asString()).isEqualTo("점검 상품");
            assertThat(page.at("/contents/0/availableQuantity").asLong()).isEqualTo(2);
            assertThat(page.at("/contents/0/thresholdQuantity").asLong()).isEqualTo(2);
            assertThat(page.at("/contents/0/productVisible").asBoolean()).isTrue();
            assertThat(page.at("/contents/0/productSaleStatus/code").asString()).isEqualTo("ON_SALE");
            assertThat(page.at("/contents/0/skuSaleStatus/code").asString()).isEqualTo("ON_SALE");
            assertThat(page.at("/contents/0/policyUpdatedAt").isNull()).isFalse();
            assertThat(page.at("/hasNext").asBoolean()).isTrue();
            assertThat(page.at("/nextAfterSkuId").asString()).isEqualTo(String.valueOf(SKU_ID));
            var end = internal("?size=1&afterSkuId=" + page.at("/nextAfterSkuId").asString(), operations());
            assertThat(end).hasStatusOk();
            assertThat(body(end).at("/dataBody/contents/0/skuId").asString()).isEqualTo(String.valueOf(second));
            assertThat(body(end).at("/dataBody/contents/0/optionValueKey").asString()).isEqualTo("10_20");
            assertThat(body(end).at("/dataBody/contents/0/availableQuantity").asLong()).isZero();
            assertThat(body(end).at("/dataBody/contents/0/skuSaleStatus/code").asString()).isEqualTo("STOP_SELLING");
            assertThat(body(end).at("/dataBody/hasNext").asBoolean()).isFalse();
            assertThat(body(end).at("/dataBody/nextAfterSkuId").isNull()).isTrue();
            var empty = internal("?afterSkuId=" + Long.MAX_VALUE, operations());
            assertThat(empty).hasStatusOk();
            assertThat(body(empty).at("/dataBody/contents").size()).isZero();
            assertThat(body(empty).at("/dataBody/nextAfterSkuId").isNull()).isTrue();
            assertThat(body(internal("", operations())).at("/dataBody/contents").size()).isEqualTo(2);
            assertThat(stocks.findById(SKU_ID).orElseThrow().getAvailableQuantity()).isEqualTo(2);
            assertThat(stocks.existsById(second)).isFalse();
        } finally {
            policies.deleteById(second);
            skus.deleteById(second);
        }
    }

    @Test
    void onlyOperationsSignedTokenIsAllowed() {
        failure(internal("", null), HttpStatus.UNAUTHORIZED, "SECURITY_007");
        failure(internal("", "Bearer invalid"), HttpStatus.UNAUTHORIZED, "SECURITY_007");
        for (MemberRole role : new MemberRole[]{MemberRole.ADMIN, MemberRole.CUSTOMER}) {
            failure(internal("", "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue()),
                HttpStatus.UNAUTHORIZED, "SECURITY_007");
        }
        failure(internal("", TestServiceTokens.bearer("order-service", "product-service")), HttpStatus.FORBIDDEN, "SECURITY_005");
    }

    @Test
    void validationAndBindingFollowExistingOrder() {
        for (var invalid : Map.of("afterSkuId=-1", "STOCK_ALERT_106", "size=0", "STOCK_ALERT_105", "size=101", "STOCK_ALERT_105",
            "afterSkuId=bad", "PRODUCT_198", "afterSkuId=9223372036854775808", "PRODUCT_198", "size=bad", "PRODUCT_198").entrySet()) {
            failure(internal("?" + invalid.getKey(), operations()), HttpStatus.BAD_REQUEST, invalid.getValue());
        }
        failure(internal("?afterSkuId=-1", TestServiceTokens.bearer("order-service", "product-service")), HttpStatus.BAD_REQUEST, "STOCK_ALERT_106");
        failure(internal("?afterSkuId=-1", null), HttpStatus.UNAUTHORIZED, "SECURITY_007");
    }

    private MvcTestResult internal(String query, String authorization) {
        var request = mvc.get().uri("/internal/v1/stocks/low" + query);
        if (authorization != null) { request = request.header(HttpHeaders.AUTHORIZATION, authorization); }
        return request.exchange();
    }

    private static String operations() { return TestServiceTokens.bearer("operations-service", "product-service"); }

    private static void failure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(body(result).at("/dataHeader/resultCode").asString()).isEqualTo(code);
        assertThat(body(result).at("/dataBody").isNull()).isTrue();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
