package com.commercefoundry.domainlayer.catalog.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductCustomOrderFieldInternalItem;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalItem;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalResponse;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogErrorCode;
import com.commercefoundry.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogCustomOrderField;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogCustomOrderInputType;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product-service internal 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}(대상 서비스 논리명)다. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.product-service}.</li>
 *   <li>서킷 오픈({@link CallNotPermittedException}) · 전송 실패 · 타임아웃 · 5xx — 503 {@code CatalogException}({@code CATALOG_001}). 호출한 컨텍스트가 자기 코드로 번역한다(장바구니 {@code CART_008}).</li>
 *   <li>4xx({@code FeignClientException})는 서킷 실패로 세지 않는다({@code ignore-exceptions}) — 호출한 쪽(우리)의 요청 · 설정 문제다.
 *       사용자에게는 같은 503 으로 답하고 error 로그를 남긴다. 401 · {@code SECURITY_007} 은 서비스 토큰 비밀 · 서비스 ID 설정 문제라 재시도로 풀리지 않는다
 *       ({@code security-guide.md} §9-5).</li>
 * </ul>
 *
 * <p>컴포넌트 이름에 대상 서비스 접두사(Product)를 붙인다 — 같은 단순 이름의 {@code @Component} 가 두 패키지에 있으면 기동이 실패한다.
 */
@Slf4j
@Component
public class ProductInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "product-service";
    static final String SOLD_OUT = "SOLD_OUT";

    private final CircuitBreaker circuitBreaker;

    public ProductInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /** 원격 호출을 서킷으로 감싸고 실패를 503 {@code CatalogException} 으로 바꾼다. */
    public <T> T call(Supplier<T> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("product-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            log.error("product-service rejected internal request. status={}", exception.status());
            throw new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            log.warn("product-service call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * SKU 벌크 조회 응답을 조회 결과로 바꾼다. 봉투가 실패이거나 본문이 비었으면 503 이다 — 성공 응답에는 언제나 목록이 있다.
     *
     * <p>{@code weightGrams} 는 여기서 검증하지 않고 옮긴다 — 1 미만은 계약 위반이며 해외 총 무게 계산(주문)에서 거절한다.
     * 장바구니 조회가 무게 하나로 막히지 않게 경계에서는 검증하지 않는다.
     *
     * <p>상품명 · 옵션명은 늘 있다(옵션이 없으면 옵션명은 빈 문자열) — null 이면 계약 위반이다. 주문 · 장바구니가 이름을 메시지 · 스냅숏에 쓰므로 추측하지 않는다.
     *
     * @throws CatalogException {@code PRODUCT_SERVICE_UNAVAILABLE} — 봉투 · 본문이 계약과 다르거나 금액 · 아이디 · 이름을 읽을 수 없다
     */
    public List<SkuQueryResult> toSkuQueryResults(Response<ProductSkuInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success()
            || response.dataBody() == null || response.dataBody().skus() == null) {
            log.error("product-service sku response is not a success envelope. resultCode={}",
                response == null || response.dataHeader() == null ? null : RemoteResultCodes.loggable(response.dataHeader().resultCode()));
            throw new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        try {
            return response.dataBody().skus().stream().map(ProductInternalResponseSupport::toSkuQueryResult).toList();
        } catch (RuntimeException exception) {
            // 아이디 · 금액 · 통화 · 이름이 계약과 다르다(숫자 아님 · 음수 · 모르는 통화 · 빠진 칸). 가격 · 이름을 추측하지 않는다
            log.error("product-service sku response violates contract. type={}", exception.getClass().getSimpleName());
            throw new CatalogException(CatalogErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        }
    }

    private static SkuQueryResult toSkuQueryResult(ProductSkuInternalItem item) {
        return new SkuQueryResult(
            Long.parseLong(item.skuId()), Long.parseLong(item.productId()),
            Objects.requireNonNull(item.productName(), "productName"), Objects.requireNonNull(item.optionName(), "optionName"), toMoney(item.salePrice()),
            item.purchasable(), item.saleStatus() != null && SOLD_OUT.equals(item.saleStatus().code()),
            item.maxQuantityPerOrder(), item.customOrder(), item.weightGrams(), item.hsCode(), toCustomOrderFields(item.customOrderFields()));
    }

    /** null 이면 아직 칸이 없는 응답이다 — 빈 목록으로 읽는다. 칸이 있는데 키가 비었거나 유형을 모르면 계약 위반이다. */
    private static List<CatalogCustomOrderField> toCustomOrderFields(List<ProductCustomOrderFieldInternalItem> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream().map(ProductInternalResponseSupport::toCustomOrderField).toList();
    }

    private static CatalogCustomOrderField toCustomOrderField(ProductCustomOrderFieldInternalItem field) {
        if (field == null || field.fieldKey() == null || field.fieldKey().isBlank() || field.inputType() == null) {
            throw new IllegalArgumentException("customOrderField");
        }
        return new CatalogCustomOrderField(
            field.fieldKey(), CatalogCustomOrderInputType.valueOf(field.inputType()), field.required(), field.min(), field.max(), field.step());
    }

    private static Money toMoney(MoneyItem money) {
        if (money == null) {
            throw new IllegalArgumentException("salePrice 가 없습니다");
        }
        return Money.of(money.amount(), CurrencyCode.valueOf(money.currency()));
    }
}
