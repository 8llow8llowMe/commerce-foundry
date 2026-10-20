package com.commercefoundry.domainlayer.catalog.adapter.out.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.DataHeader;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalItem;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalResponse;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductInternalResponseSupportTest {

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/skus?ids=1", Map.of(), null,
        StandardCharsets.UTF_8, null);

    private CircuitBreakerRegistry registry;
    private ProductInternalResponseSupport support;

    /** application.yml 의 product-service 서킷과 같은 판정(4xx 는 실패로 세지 않는다). 창은 테스트가 빨리 열리게 작게 둔다. */
    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(4)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .ignoreExceptions(FeignException.FeignClientException.class)
            .build());
        support = new ProductInternalResponseSupport(registry);
    }

    private static FeignException status(int status) {
        feign.Response response = feign.Response.builder().status(status).reason("x").request(REQUEST).headers(Map.of()).build();
        return FeignException.errorStatus("ProductSkuClient#getSkus(List)", response);
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CatalogException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE));
    }

    @Nested
    @DisplayName("호출 실패 변환")
    class Call {

        @Test
        @DisplayName("성공하면 결과를 그대로 돌려준다")
        void success() {
            assertThat(support.call(() -> "ok")).isEqualTo("ok");
        }

        @ParameterizedTest(name = "status={0}")
        @ValueSource(ints = {500, 502, 503, 504})
        @DisplayName("5xx 는 CATALOG_001(503) 이고 FeignException 을 밖으로 내보내지 않는다")
        void serverError(int status) {
            assertUnavailable(() -> support.call(() -> {
                throw status(status);
            }));
        }

        @Test
        @DisplayName("전송 실패(연결 거부 · 타임아웃)도 503 이다")
        void transportFailure() {
            assertUnavailable(() -> support.call(() -> {
                throw new RetryableException(-1, "connect timed out", Request.HttpMethod.GET, (Long) null, REQUEST);
            }));
        }

        @Test
        @DisplayName("4xx 도 사용자에게는 503 이지만 서킷 실패로 세지 않는다 — 우리 요청 · 설정 문제다")
        void clientErrorIsNotCounted() {
            for (int index = 0; index < 10; index++) {
                assertUnavailable(() -> support.call(() -> {
                    throw status(401);
                }));
            }

            CircuitBreaker circuitBreaker = registry.circuitBreaker(ProductInternalResponseSupport.CIRCUIT_BREAKER);
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("5xx 가 쌓이면 서킷이 열리고, 열린 뒤에는 원격을 부르지 않고 503 이다")
        void circuitOpens() {
            for (int index = 0; index < 4; index++) {
                assertUnavailable(() -> support.call(() -> {
                    throw status(503);
                }));
            }
            assertThat(registry.circuitBreaker(ProductInternalResponseSupport.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);

            int[] remoteCalls = {0};
            assertThatThrownBy(() -> support.call(() -> ++remoteCalls[0]))
                .isInstanceOfSatisfying(CatalogException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
                    assertThat(exception.getCause()).isInstanceOf(CallNotPermittedException.class);
                });
            assertThat(remoteCalls[0]).isZero();
        }
    }

    @Nested
    @DisplayName("응답 변환")
    class Convert {

        private ProductSkuInternalItem item(String skuId, String saleStatus, boolean purchasable, MoneyItem salePrice) {
            return new ProductSkuInternalItem(skuId, "7301", "저고리", "아이보리 / L", salePrice,
                CodeNameDescriptionMetadata.of(saleStatus, "n", "d"), purchasable, 10, true, 850, "6211429000");
        }

        private Response<ProductSkuInternalResponse> success(ProductSkuInternalItem... items) {
            return Response.success(new ProductSkuInternalResponse(List.of(items)));
        }

        @Test
        @DisplayName("문자열 아이디 · 금액 · 품절 여부 · 무게 · HS 코드를 조회 결과로 바꾼다")
        void converts() {
            List<SkuQueryResult> results = support.toSkuQueryResults(success(
                item("9004", "ON_SALE", true, MoneyItem.of(39_000, "KRW")),
                item("9005", "SOLD_OUT", false, MoneyItem.of(1_000, "KRW")),
                item("9006", "STOP_SELLING", false, MoneyItem.of(1_000, "KRW"))));

            assertThat(results).extracting(SkuQueryResult::skuId).containsExactly(9004L, 9005L, 9006L);
            assertThat(results.getFirst()).isEqualTo(
                new SkuQueryResult(9004L, 7301L, "저고리", "아이보리 / L", Money.of(39_000, CurrencyCode.KRW), true, false, 10, true, 850, "6211429000"));
            assertThat(results).extracting(SkuQueryResult::soldOut).containsExactly(false, true, false);
        }

        @Test
        @DisplayName("무게 · HS 코드가 null(미입력)이어도 거절하지 않고 null 로 옮긴다 — 해외 주문 거절은 order 컨텍스트가 한다")
        void missingWeightAndHsCodeStayNull() {
            ProductSkuInternalItem unset = new ProductSkuInternalItem("9004", "7301", "저고리", "", MoneyItem.of(39_000, "KRW"),
                CodeNameDescriptionMetadata.of("ON_SALE", "n", "d"), true, null, false, null, null);

            assertThat(support.toSkuQueryResults(success(unset))).singleElement()
                .satisfies(result -> {
                    assertThat(result.weightGrams()).isNull();
                    assertThat(result.hsCode()).isNull();
                });
        }

        @Test
        @DisplayName("실패 봉투 · 빈 본문은 503 이다")
        void notSuccessEnvelope() {
            assertUnavailable(() -> support.toSkuQueryResults(Response.fail("PRODUCT_104", "ids 는 최대 100개입니다.")));
            assertUnavailable(() -> support.toSkuQueryResults(new Response<>(DataHeader.ok(), null)));
            assertUnavailable(() -> support.toSkuQueryResults(new Response<>(DataHeader.ok(), new ProductSkuInternalResponse(null))));
            assertUnavailable(() -> support.toSkuQueryResults(null));
        }

        @Test
        @DisplayName("계약과 다른 값(모르는 통화 · 음수 금액 · 숫자 아닌 아이디 · 금액 없음)은 가격을 추측하지 않고 503 이다")
        void contractViolation() {
            assertUnavailable(() -> support.toSkuQueryResults(success(item("9004", "ON_SALE", true, MoneyItem.of(1_000, "JPY")))));
            assertUnavailable(() -> support.toSkuQueryResults(success(item("9004", "ON_SALE", true, MoneyItem.of(-1, "KRW")))));
            assertUnavailable(() -> support.toSkuQueryResults(success(item("abc", "ON_SALE", true, MoneyItem.of(1_000, "KRW")))));
            assertUnavailable(() -> support.toSkuQueryResults(success(item("9004", "ON_SALE", true, null))));
        }
    }
}
