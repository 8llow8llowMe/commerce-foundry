package com.commercefoundry.domainlayer.onboarding.application.service.processor;

import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.ProductRegistrationFactQueryPort;
import com.commercefoundry.domainlayer.onboarding.application.port.out.ShippingPolicyFactQueryPort;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ProductRegistrationFactQueryResult;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;
import com.commercefoundry.domainlayer.onboarding.domain.model.OnboardingTask;
import com.commercefoundry.domainlayer.onboarding.domain.model.OnboardingTaskKey;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운영자의 실제 업무 완료 판정 (#357). 담당 서비스의 사실로만 판정한다 — 안내 투어 기록은 보지 않는다.
 * 원격 호출이라 트랜잭션을 걸지 않는다.
 *
 * <p>담당 서비스를 부를 수 없으면 그 업무만 {@code UNAVAILABLE} 로 흡수한다 — 온보딩 조회 전체(투어 진행)를 503 으로 막지 않는다.
 * 실패 원인(status · 서킷)은 어댑터 support 가 이미 로그로 남긴다.
 */
@Component
public class OnboardingTaskProcessor {

    private final ShippingPolicyFactQueryPort shippingPolicyFactQueryPort;
    private final ProductRegistrationFactQueryPort productRegistrationFactQueryPort;
    private final boolean productRegistrationEnabled;

    public OnboardingTaskProcessor(ShippingPolicyFactQueryPort shippingPolicyFactQueryPort,
                                   ProductRegistrationFactQueryPort productRegistrationFactQueryPort,
                                   @Value("${operations.onboarding.product-registration-enabled:false}") boolean productRegistrationEnabled) {
        this.shippingPolicyFactQueryPort = shippingPolicyFactQueryPort;
        this.productRegistrationFactQueryPort = productRegistrationFactQueryPort;
        this.productRegistrationEnabled = productRegistrationEnabled;
    }

    /** 업무 전부 — {@link OnboardingTaskKey} 선언 순서. */
    public List<OnboardingTask> getMine(long memberId) {
        OnboardingTask shipping = shippingPolicySaved(memberId);
        return productRegistrationEnabled ? List.of(shipping, productRegistered(memberId)) : List.of(shipping);
    }

    private OnboardingTask productRegistered(long memberId) {
        try {
            ProductRegistrationFactQueryResult fact = productRegistrationFactQueryPort.findProductRegistrationFact(memberId);
            return OnboardingTask.fromFact(OnboardingTaskKey.PRODUCT_REGISTERED, fact.storeReady(), fact.memberFirstRegisteredAt());
        } catch (OnboardingException exception) {
            if (exception.getErrorCode() != OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE) {
                throw exception;
            }
            return OnboardingTask.unavailable(OnboardingTaskKey.PRODUCT_REGISTERED);
        }
    }

    private OnboardingTask shippingPolicySaved(long memberId) {
        try {
            ShippingPolicyFactQueryResult fact = shippingPolicyFactQueryPort.findShippingPolicyFact(memberId);
            return OnboardingTask.fromFact(OnboardingTaskKey.SHIPPING_POLICY_SAVED, fact.storeReady(), fact.memberFirstSavedAt());
        } catch (OnboardingException exception) {
            if (exception.getErrorCode() != OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE) {
                throw exception;
            }
            return OnboardingTask.unavailable(OnboardingTaskKey.SHIPPING_POLICY_SAVED);
        }
    }
}
