package com.commercefoundry.domainlayer.onboarding.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto.ProductRegistrationFactInternalResponse;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ProductRegistrationFactQueryResult;
import com.commercefoundry.global.support.RemoteResultCodes;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductRegistrationFactInternalResponseSupport {
    static final String CIRCUIT_BREAKER = "product-service";
    private final CircuitBreaker circuitBreaker;

    public ProductRegistrationFactInternalResponseSupport(CircuitBreakerRegistry registry) {
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER);
    }

    public <T> T call(Supplier<T> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("product-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new OnboardingException(OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            log.error("product-service rejected product registration fact request. status={}", exception.status());
            throw new OnboardingException(OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            log.warn("product-service product registration fact call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new OnboardingException(OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE, exception);
        }
    }

    public ProductRegistrationFactQueryResult toQueryResult(Response<ProductRegistrationFactInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success()
            || response.dataBody() == null || response.dataBody().storeReady() == null) {
            log.error("product-service product registration fact response violates contract. resultCode={}",
                response == null || response.dataHeader() == null ? null : RemoteResultCodes.loggable(response.dataHeader().resultCode()));
            throw new OnboardingException(OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        return new ProductRegistrationFactQueryResult(response.dataBody().storeReady(), response.dataBody().memberFirstRegisteredAt());
    }
}
