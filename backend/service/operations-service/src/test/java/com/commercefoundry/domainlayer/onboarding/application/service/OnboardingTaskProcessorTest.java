package com.commercefoundry.domainlayer.onboarding.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ProductRegistrationFactQueryResult;
import com.commercefoundry.domainlayer.onboarding.application.service.processor.OnboardingTaskProcessor;
import com.commercefoundry.domainlayer.onboarding.domain.model.MemberTaskStatus;
import com.commercefoundry.domainlayer.onboarding.domain.model.OnboardingTask;
import com.commercefoundry.domainlayer.onboarding.domain.model.OnboardingTaskKey;
import com.commercefoundry.domainlayer.onboarding.domain.model.StoreTaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 업무 완료 판정 — 담당 서비스 사실(스토어 준비 · 본인 최초 저장 시각)을 스토어 상태 · 본인 상태로 합치는 규칙과, 원천 장애를 UNAVAILABLE 로 흡수하는지 본다.
 */
class OnboardingTaskProcessorTest {

    private static final long MEMBER = 7L;
    private static final Instant SAVED_AT = Instant.parse("2026-10-28T01:00:00Z");

    private static OnboardingTask only(OnboardingTaskProcessor processor) {
        List<OnboardingTask> tasks = processor.getMine(MEMBER);
        assertThat(tasks).extracting(OnboardingTask::key).containsExactly(OnboardingTaskKey.SHIPPING_POLICY_SAVED, OnboardingTaskKey.PRODUCT_REGISTERED);
        return tasks.getFirst();
    }

    private static OnboardingTaskProcessor answering(boolean storeReady, Instant memberFirstSavedAt) {
        return new OnboardingTaskProcessor(memberId -> new ShippingPolicyFactQueryResult(storeReady, memberFirstSavedAt),
            memberId -> new ProductRegistrationFactQueryResult(true, null), true);
    }

    @Test
    @DisplayName("스토어 준비 + 본인 기록 있음 → READY · COMPLETED, 완료 시각은 본인 최초 저장 시각")
    void completed() {
        assertThat(only(answering(true, SAVED_AT)))
            .isEqualTo(new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.READY, MemberTaskStatus.COMPLETED, SAVED_AT));
    }

    @Test
    @DisplayName("스토어 준비 + 본인 기록 없음 → READY · NO_EVIDENCE — 다른 직원이 한 설정을 본인 완료로 추정하지 않는다")
    void noEvidence() {
        assertThat(only(answering(true, null)))
            .isEqualTo(new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.READY, MemberTaskStatus.NO_EVIDENCE, null));
    }

    @Test
    @DisplayName("스토어 미준비 + 본인 기록 없음 → NOT_READY · NOT_COMPLETED")
    void notReady() {
        assertThat(only(answering(false, null)))
            .isEqualTo(new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.NOT_READY, MemberTaskStatus.NOT_COMPLETED, null));
    }

    @Test
    @DisplayName("스토어 미준비여도 본인 기록이 있으면 COMPLETED — 한 번 해낸 기록은 되돌리지 않는다")
    void completedEvenIfStoreNotReady() {
        assertThat(only(answering(false, SAVED_AT)))
            .isEqualTo(new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.NOT_READY, MemberTaskStatus.COMPLETED, SAVED_AT));
    }

    @Test
    @DisplayName("원천 호출 불가(ONBOARDING_030) → 예외 없이 UNAVAILABLE · UNAVAILABLE. 운영자는 토큰의 memberId 로 그대로 넘긴다")
    void unavailable() {
        List<Long> requested = new ArrayList<>();
        OnboardingTaskProcessor processor = new OnboardingTaskProcessor(memberId -> {
            requested.add(memberId);
            throw new OnboardingException(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
        }, memberId -> new ProductRegistrationFactQueryResult(true, null), true);

        assertThat(only(processor))
            .isEqualTo(new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.UNAVAILABLE, MemberTaskStatus.UNAVAILABLE, null));
        assertThat(requested).containsExactly(MEMBER);
    }

    @Test
    @DisplayName("원천 불가가 아닌 예외는 흡수하지 않는다 — 버그를 UNAVAILABLE 로 숨기지 않는다")
    void otherFailurePropagates() {
        OnboardingTaskProcessor processor = new OnboardingTaskProcessor(memberId -> {
            throw new IllegalStateException("bug");
        }, memberId -> new ProductRegistrationFactQueryResult(true, null), true);

        assertThatThrownBy(() -> processor.getMine(MEMBER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("완료 시각은 COMPLETED 일 때만 있다 — 어긋난 조합은 만들 수 없다")
    void completedAtOnlyWhenCompleted() {
        assertThatThrownBy(() -> new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.READY, MemberTaskStatus.COMPLETED, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnboardingTask(OnboardingTaskKey.SHIPPING_POLICY_SAVED, StoreTaskStatus.READY, MemberTaskStatus.NO_EVIDENCE, SAVED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다른 운영자의 상품만 있으면 스토어 READY, 본인은 NO_EVIDENCE")
    void productOfOtherMember() {
        OnboardingTask product = answering(true, null).getMine(MEMBER).get(1);
        assertThat(product).isEqualTo(new OnboardingTask(OnboardingTaskKey.PRODUCT_REGISTERED,
            StoreTaskStatus.READY, MemberTaskStatus.NO_EVIDENCE, null));
    }

    @Test
    @DisplayName("상품 서비스 장애는 상품 업무만 UNAVAILABLE, 배송 업무 사실은 유지")
    void productUnavailableOnly() {
        OnboardingTaskProcessor processor = new OnboardingTaskProcessor(
            memberId -> new ShippingPolicyFactQueryResult(true, SAVED_AT),
            memberId -> { throw new OnboardingException(OnboardingErrorCode.PRODUCT_SERVICE_UNAVAILABLE); }, true);
        List<OnboardingTask> tasks = processor.getMine(MEMBER);
        assertThat(tasks.getFirst().myStatus()).isEqualTo(MemberTaskStatus.COMPLETED);
        assertThat(tasks.get(1)).isEqualTo(OnboardingTask.unavailable(OnboardingTaskKey.PRODUCT_REGISTERED));
    }

    @Test
    @DisplayName("상품 등록 화면이 배포되기 전에는 상품 업무를 응답하지 않고 product-service도 호출하지 않는다")
    void productTaskIsHiddenUntilRegistrationIsAvailable() {
        OnboardingTaskProcessor processor = new OnboardingTaskProcessor(
            memberId -> new ShippingPolicyFactQueryResult(true, SAVED_AT),
            memberId -> { throw new AssertionError("상품 사실을 조회하면 안 됩니다."); }, false);

        assertThat(processor.getMine(MEMBER)).extracting(OnboardingTask::key)
            .containsExactly(OnboardingTaskKey.SHIPPING_POLICY_SAVED);
    }
}
