package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.remoteArea;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.ulleung;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryEntity;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyHistoryMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.global.config.DeliveryServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 배송비 정책 교체 이력 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 (#69) — 파생 페이지 · in 절 쿼리와 check 제약은 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5).
 *
 * <p>스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. check 제약 위반은 테스트마다 하나씩 둔다 —
 * PostgreSQL 은 위반 뒤 그 트랜잭션의 다음 문장을 모두 거절한다(#128). Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=delivery",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, DeliveryServiceFeaturesConfig.class, ShippingPolicyHistoryRepositoryAdapter.class, ShippingPolicyHistoryMapperImpl.class})
class ShippingPolicyHistoryRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    private static final long ADMIN_MEMBER_ID = 4611686018427387904L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    private static ShippingPolicyHistory history(long id, Snapshot before, Snapshot after) {
        return new ShippingPolicyHistory(id, POLICY_ID, ShippingScope.DOMESTIC, ADMIN_MEMBER_ID, before, after, null);
    }

    private static ShippingPolicyHistoryEntity.ShippingPolicyHistoryEntityBuilder validHistoryRow() {
        return ShippingPolicyHistoryEntity.builder()
            .id(9301L).shippingPolicyId(POLICY_ID).scope(ShippingScope.DOMESTIC).adminMemberId(ADMIN_MEMBER_ID)
            .afterVersion(0L).afterBaseFeeAmount(3_000L).afterBaseFeeCurrency(KRW);
    }

    private static ShippingPolicyHistoryAreaEntity.ShippingPolicyHistoryAreaEntityBuilder validAreaRow() {
        return ShippingPolicyHistoryAreaEntity.builder()
            .id(9401L).shippingPolicyHistoryId(9301L).snapshotSide(ShippingPolicyHistoryAreaEntity.Side.AFTER).name("제주")
            .postalCodeFrom("63000").postalCodeTo("63644").surchargeAmount(3_000L).surchargeCurrency(KRW);
    }

    @Test
    @DisplayName("이력이 없으면 빈 페이지이고 전체 건수는 0 이다")
    void emptyPage() {
        Page<ShippingPolicyHistory> page = shippingPolicyHistoryRepositoryPort.findDomesticPage(0, 20);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("처음 만든 교체(before 없음) · 교체(before 있음)를 저장하면 교체 시각이 채워지고 금액 · 버전 · 쪽별 구간이 그대로 다시 읽힌다")
    void insertsAndReadsBack() {
        Snapshot created = new Snapshot(krw(3_000), null, List.of(remoteArea(9311L, "제주", "63000", "63644", krw(3_000))), 0L);
        Snapshot replaced = new Snapshot(krw(3_500), krw(50_000), List.of(remoteArea(9321L, "울릉", "40200", "40240", krw(5_000))), 1L);
        Snapshot before = new Snapshot(krw(3_000), null, List.of(remoteArea(9322L, "제주", "63000", "63644", krw(3_000))), 0L);
        shippingPolicyHistoryRepositoryPort.insert(history(9301L, null, created));
        shippingPolicyHistoryRepositoryPort.insert(history(9302L, before, replaced));
        entityManager.clear();

        List<ShippingPolicyHistory> histories = shippingPolicyHistoryRepositoryPort.findDomesticPage(0, 20).getContent();

        assertThat(histories).extracting(ShippingPolicyHistory::id).containsExactly(9302L, 9301L);
        ShippingPolicyHistory latest = histories.getFirst();
        assertThat(latest.changedAt()).isNotNull();
        assertThat(latest.adminMemberId()).isEqualTo(ADMIN_MEMBER_ID);
        assertThat(latest.before()).isEqualTo(before);
        assertThat(latest.after()).isEqualTo(replaced);
        assertThat(histories.get(1).before()).isNull();
        assertThat(histories.get(1).after()).isEqualTo(created);
    }

    @Test
    @DisplayName("최신순(아이디 내림차순)으로 자르고 전체 건수 · 페이지 수는 자르기 전 기준이다 — 마지막 페이지를 넘기면 빈 목록이고 건수는 그대로다")
    void pagesNewestFirst() {
        for (long id = 9301L; id <= 9305L; id++) {
            shippingPolicyHistoryRepositoryPort.insert(history(id, null, new Snapshot(krw(3_000), null, List.of(), id - 9301L)));
        }
        entityManager.clear();

        Page<ShippingPolicyHistory> second = shippingPolicyHistoryRepositoryPort.findDomesticPage(1, 2);
        Page<ShippingPolicyHistory> beyond = shippingPolicyHistoryRepositoryPort.findDomesticPage(9, 2);

        assertThat(second.getContent()).extracting(ShippingPolicyHistory::id).containsExactly(9303L, 9302L);
        assertThat(second.getTotalElements()).isEqualTo(5L);
        assertThat(second.getTotalPages()).isEqualTo(3);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5L);
    }

    @Test
    @DisplayName("구간이 많은 이력도 쪽별로 나뉘어 시작 우편번호 순으로 읽힌다")
    void areasAreGroupedBySide() {
        Snapshot before = new Snapshot(krw(3_000), null, List.of(jeju(), ulleung()), 3L);
        Snapshot after = new Snapshot(krw(3_000), null, List.of(remoteArea(9331L, "제주", "63000", "63644", krw(3_000))), 4L);
        shippingPolicyHistoryRepositoryPort.insert(history(9301L, before, after));
        entityManager.clear();

        ShippingPolicyHistory read = shippingPolicyHistoryRepositoryPort.findDomesticPage(0, 20).getContent().getFirst();

        assertThat(read.before().remoteAreas()).containsExactly(ulleung(), jeju());
        assertThat(read.after().remoteAreas()).extracting(area -> area.id()).containsExactly(9331L);
    }

    @Test
    @DisplayName("제약: 교체 전 버전만 있고 금액이 없으면 저장되지 않는다 (ck_shipping_policy_history_before_snapshot)")
    void partialBeforeIsRejected() {
        ShippingPolicyHistoryEntity entity = validHistoryRow().beforeVersion(2L).build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_history_before_snapshot");
    }

    @Test
    @DisplayName("제약: 교체 후 무료배송 기준 금액만 있고 통화가 없으면 저장되지 않는다 (ck_shipping_policy_history_after_threshold_pair)")
    void halfAfterThresholdIsRejected() {
        ShippingPolicyHistoryEntity entity = validHistoryRow().afterFreeShippingThresholdAmount(50_000L).build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_history_after_threshold_pair");
    }

    @Test
    @DisplayName("제약: 교체 후 기본 배송비가 음수면 저장되지 않는다 (ck_shipping_policy_history_after_base_fee_non_negative)")
    void negativeAfterBaseFeeIsRejected() {
        ShippingPolicyHistoryEntity entity = validHistoryRow().afterBaseFeeAmount(-1L).build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_history_after_base_fee_non_negative");
    }

    @Test
    @DisplayName("제약: 숫자가 아닌 우편번호 구간 스냅숏은 저장되지 않는다 (ck_shipping_policy_history_area_postal_code_format)")
    void nonNumericAreaPostalCodeIsRejected() {
        ShippingPolicyHistoryAreaEntity entity = validAreaRow().postalCodeTo("6364A").build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_history_area_postal_code_format");
    }

    @Test
    @DisplayName("제약: 시작이 끝보다 큰 구간 스냅숏은 저장되지 않는다 (ck_shipping_policy_history_area_postal_code_range)")
    void reversedAreaRangeIsRejected() {
        ShippingPolicyHistoryAreaEntity entity = validAreaRow().postalCodeFrom("63644").postalCodeTo("63000").build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining("ck_shipping_policy_history_area_postal_code_range");
    }

    /** 교체 전 값이 있는 이력 행 — 버전 2 → 3, 기본 배송비 3,000원. */
    private static ShippingPolicyHistoryEntity.ShippingPolicyHistoryEntityBuilder validReplaceRow() {
        return validHistoryRow().beforeVersion(2L).beforeBaseFeeAmount(3_000L).beforeBaseFeeCurrency(KRW).afterVersion(3L);
    }

    private void assertRejectedBy(Object entity, String constraintName) {
        assertThatThrownBy(() -> entityManager.persistAndFlush(entity))
            .rootCause()
            .hasMessageContaining(constraintName);
    }

    @Test
    @DisplayName("제약: 교체 전 기본 배송비가 음수면 저장되지 않는다 (ck_shipping_policy_history_before_base_fee_non_negative)")
    void negativeBeforeBaseFeeIsRejected() {
        assertRejectedBy(validReplaceRow().beforeBaseFeeAmount(-1L).build(), "ck_shipping_policy_history_before_base_fee_non_negative");
    }

    @Test
    @DisplayName("제약: 교체 전 무료배송 기준 금액만 있고 통화가 없으면 저장되지 않는다 (ck_shipping_policy_history_before_threshold_pair)")
    void halfBeforeThresholdIsRejected() {
        assertRejectedBy(validReplaceRow().beforeFreeShippingThresholdAmount(50_000L).build(), "ck_shipping_policy_history_before_threshold_pair");
    }

    @Test
    @DisplayName("제약: 교체 전 무료배송 기준 0원은 저장되지 않는다 (ck_shipping_policy_history_before_threshold_positive)")
    void zeroBeforeThresholdIsRejected() {
        ShippingPolicyHistoryEntity entity = validReplaceRow().beforeFreeShippingThresholdAmount(0L).beforeFreeShippingThresholdCurrency(KRW).build();

        assertRejectedBy(entity, "ck_shipping_policy_history_before_threshold_positive");
    }

    @Test
    @DisplayName("제약: 교체 전 무료배송 기준 통화가 교체 전 기본 배송비 통화와 다르면 저장되지 않는다 (ck_shipping_policy_history_before_threshold_currency)")
    void beforeThresholdCurrencyMismatchIsRejected() {
        ShippingPolicyHistoryEntity entity = validReplaceRow().beforeFreeShippingThresholdAmount(50_000L).beforeFreeShippingThresholdCurrency(USD).build();

        assertRejectedBy(entity, "ck_shipping_policy_history_before_threshold_currency");
    }

    @Test
    @DisplayName("제약: 교체 후 무료배송 기준 0원은 저장되지 않는다 (ck_shipping_policy_history_after_threshold_positive)")
    void zeroAfterThresholdIsRejected() {
        ShippingPolicyHistoryEntity entity = validHistoryRow().afterFreeShippingThresholdAmount(0L).afterFreeShippingThresholdCurrency(KRW).build();

        assertRejectedBy(entity, "ck_shipping_policy_history_after_threshold_positive");
    }

    @Test
    @DisplayName("제약: 교체 후 무료배송 기준 통화가 교체 후 기본 배송비 통화와 다르면 저장되지 않는다 (ck_shipping_policy_history_after_threshold_currency)")
    void afterThresholdCurrencyMismatchIsRejected() {
        ShippingPolicyHistoryEntity entity = validHistoryRow().afterFreeShippingThresholdAmount(50_000L).afterFreeShippingThresholdCurrency(USD).build();

        assertRejectedBy(entity, "ck_shipping_policy_history_after_threshold_currency");
    }

    @Test
    @DisplayName("제약: 교체 후 버전이 교체 전보다 크지 않으면 저장되지 않는다 (ck_shipping_policy_history_version_order)")
    void nonIncreasingVersionIsRejected() {
        assertRejectedBy(validReplaceRow().afterVersion(2L).build(), "ck_shipping_policy_history_version_order");
    }

    @Test
    @DisplayName("제약: 음수 추가 배송비 구간 스냅숏은 저장되지 않는다 (ck_shipping_policy_history_area_surcharge_non_negative)")
    void negativeAreaSurchargeIsRejected() {
        assertRejectedBy(validAreaRow().surchargeAmount(-1L).build(), "ck_shipping_policy_history_area_surcharge_non_negative");
    }
}
