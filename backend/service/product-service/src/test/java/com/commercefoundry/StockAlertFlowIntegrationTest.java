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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 빈 구성으로 정책 CRUD·목록·오류 봉투를 잇고 재고 수량을 바꾸지 않는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StockAlertFlowIntegrationTest {

    private static final long PRODUCT_ID = 229001L;
    private static final long SKU_ID = 229002L;
    private static final String POLICY = "/api/v1/admin/stocks/" + SKU_ID + "/low-stock-policy";
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
    void policyRoundTripAndLowStockListLeaveStockUnchanged() {
        var initial = exchange("GET", POLICY, null, MemberRole.ADMIN);
        assertThat(initial).hasStatusOk();
        assertThat(body(initial).at("/dataBody/enabled").asBoolean()).isFalse();
        assertThat(body(initial).at("/dataBody/thresholdQuantity").isNull()).isTrue();
        var configured = exchange("PUT", POLICY, "{\"thresholdQuantity\":2}", MemberRole.ADMIN);
        assertThat(configured).hasStatusOk();
        assertThat(body(configured).at("/dataBody/skuId").asString()).isEqualTo(String.valueOf(SKU_ID));
        assertThat(body(configured).at("/dataBody/updatedAt").isNull()).isFalse();
        var low = exchange("GET", "/api/v1/admin/stocks/low?page=0&size=1", null, MemberRole.ADMIN);
        assertThat(low).hasStatusOk();
        assertThat(body(low).at("/dataBody/totalElements").asLong()).isOne();
        assertThat(body(low).at("/dataBody/contents/0/availableQuantity").asLong()).isEqualTo(2);

        assertThat(exchange("PUT", POLICY, "{\"thresholdQuantity\":1}", MemberRole.ADMIN)).hasStatusOk();
        assertThat(body(exchange("GET", "/api/v1/admin/stocks/low", null, MemberRole.ADMIN)).at("/dataBody/totalElements").asLong()).isZero();
        assertThat(exchange("DELETE", POLICY, null, MemberRole.ADMIN)).hasStatusOk();
        assertThat(exchange("DELETE", POLICY, null, MemberRole.ADMIN)).hasStatusOk();
        assertThat(body(exchange("GET", POLICY, null, MemberRole.ADMIN)).at("/dataBody/enabled").asBoolean()).isFalse();
        var stock = stocks.findById(SKU_ID).orElseThrow();
        assertThat(stock.getAvailableQuantity()).isEqualTo(2);
        assertThat(stock.isStockManaged()).isTrue();
    }

    @Test
    void invalidRequestsUseOwnFieldCodesAndMissingSkuIs404() {
        for (var invalid : Map.of("{}", "STOCK_ALERT_101", "{\"thresholdQuantity\":-1}", "STOCK_ALERT_102").entrySet()) {
            var result = exchange("PUT", POLICY, invalid.getKey(), MemberRole.ADMIN);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(body(result).at("/dataHeader/resultCode").asString()).isEqualTo(invalid.getValue());
        }
        for (var invalid : Map.of("page=-1", "STOCK_ALERT_104", "size=0", "STOCK_ALERT_105", "size=101", "STOCK_ALERT_105").entrySet()) {
            var result = exchange("GET", "/api/v1/admin/stocks/low?" + invalid.getKey(), null, MemberRole.ADMIN);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(body(result).at("/dataHeader/resultCode").asString()).isEqualTo(invalid.getValue());
        }
        var missing = exchange("PUT", "/api/v1/admin/stocks/999999/low-stock-policy", "{\"thresholdQuantity\":0}", MemberRole.ADMIN);
        assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(body(missing).at("/dataHeader/resultCode").asString()).isEqualTo("STOCK_ALERT_001");
        var invalidSku = exchange("GET", "/api/v1/admin/stocks/0/low-stock-policy", null, MemberRole.ADMIN);
        assertThat(invalidSku).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(invalidSku).at("/dataHeader/resultCode").asString()).isEqualTo("STOCK_ALERT_103");
        assertThat(policies.existsById(SKU_ID)).isFalse();
    }

    @Test
    void anonymousAndCustomerCannotReadOrWritePolicies() {
        for (String method : new String[]{"GET", "PUT", "DELETE"}) {
            assertThat(exchange(method, POLICY, method.equals("PUT") ? "{\"thresholdQuantity\":0}" : null, null)).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(exchange(method, POLICY, method.equals("PUT") ? "{\"thresholdQuantity\":0}" : null, MemberRole.CUSTOMER)).hasStatus(HttpStatus.FORBIDDEN);
        }
        assertThat(exchange("GET", "/api/v1/admin/stocks/low", null, null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("GET", "/api/v1/admin/stocks/low", null, MemberRole.CUSTOMER)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(policies.existsById(SKU_ID)).isFalse();
    }

    private MvcTestResult exchange(String method, String uri, String content, MemberRole role) {
        var request = mvc.method(HttpMethod.valueOf(method)).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (content != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return request.exchange();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
