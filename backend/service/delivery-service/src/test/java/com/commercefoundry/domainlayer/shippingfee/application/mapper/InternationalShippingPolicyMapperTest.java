package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.CUSTOMS_NOTICE;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.INTERNATIONAL_POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InternationalShippingPolicyMapperTest {

    private final InternationalShippingPolicyMapper mapper = new InternationalShippingPolicyMapperImpl();

    private static ShippingPolicyEntity.ShippingPolicyEntityBuilder policyEntity() {
        return ShippingPolicyEntity.builder()
            .id(INTERNATIONAL_POLICY_ID).scope(ShippingScope.INTERNATIONAL).baseFeeAmount(500L).baseFeeCurrency(USD)
            .freeShippingThresholdAmount(20_000L).freeShippingThresholdCurrency(USD).customsNotice(CUSTOMS_NOTICE).version(2L);
    }

    private static ShippingZoneEntity zone(long id, String name, int displayOrder) {
        return ShippingZoneEntity.builder().id(id).shippingPolicyId(INTERNATIONAL_POLICY_ID).name(name).displayOrder(displayOrder).build();
    }

    private static ShippingZoneCountryEntity country(long id, long zoneId, String code) {
        return ShippingZoneCountryEntity.builder().id(id).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(zoneId).countryCode(code).build();
    }

    private static ShippingZoneRateEntity rate(long id, long zoneId, int maxWeight, long cents) {
        return ShippingZoneRateEntity.builder().id(id).shippingZoneId(zoneId).maxWeightGrams(maxWeight).feeAmount(cents).feeCurrency(USD).build();
    }

    @Test
    @DisplayName("엔티티 → 도메인: 그룹은 표시 순서, 국가 · 구간은 그룹 아이디로 묶는다 — 행 순서와 무관하다")
    void toDomain() {
        ShippingPolicyEntity entity = policyEntity().build();
        ReflectionTestUtils.setField(entity, "updatedAt", UPDATED_AT);

        assertThat(mapper.toDomain(
            entity,
            List.of(zone(8201L, "북미", 1), zone(8101L, "아시아", 0)),
            List.of(country(8212L, 8201L, "US"), country(8112L, 8101L, "SG"), country(8211L, 8201L, "CA"), country(8111L, 8101L, "JP")),
            List.of(rate(8222L, 8201L, 5_000, 6_000), rate(8123L, 8101L, 2_000, 2_500), rate(8121L, 8101L, 500, 1_000),
                rate(8221L, 8201L, 1_000, 2_000), rate(8122L, 8101L, 1_000, 1_500))
        )).isEqualTo(internationalPolicy());
    }

    @Test
    @DisplayName("엔티티 → 도메인: 국내 범위 행은 데이터 · 코드 오류다")
    void rejectsDomesticRow() {
        ShippingPolicyEntity domestic = policyEntity().scope(ShippingScope.DOMESTIC).customsNotice(null).build();

        assertThatThrownBy(() -> mapper.toDomain(domestic, List.of(), List.of(), List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("도메인 → 엔티티: 새 정책은 범위 INTERNATIONAL · 버전 null 로 만든다 — 안내 문구를 옮긴다")
    void toEntity() {
        ShippingPolicyEntity entity = mapper.toEntity(internationalPolicy());

        assertThat(entity.getId()).isEqualTo(INTERNATIONAL_POLICY_ID);
        assertThat(entity.getScope()).isEqualTo(ShippingScope.INTERNATIONAL);
        assertThat(entity.getBaseFeeAmount()).isEqualTo(500L);
        assertThat(entity.getBaseFeeCurrency()).isEqualTo(USD);
        assertThat(entity.getFreeShippingThresholdAmount()).isEqualTo(20_000L);
        assertThat(entity.getCustomsNotice()).isEqualTo(CUSTOMS_NOTICE);
        assertThat(entity.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("하위 행: 그룹은 목록 순서를 표시 순서로, 국가는 정책 · 그룹 아이디를, 구간은 그룹 아이디를 붙이고 모두 새 엔티티다")
    void childEntities() {
        assertThat(mapper.toZoneEntities(internationalPolicy()))
            .extracting(ShippingZoneEntity::getId, ShippingZoneEntity::getShippingPolicyId, ShippingZoneEntity::getName,
                ShippingZoneEntity::getDisplayOrder, ShippingZoneEntity::isNew)
            .containsExactly(tuple(8101L, INTERNATIONAL_POLICY_ID, "아시아", 0, true), tuple(8201L, INTERNATIONAL_POLICY_ID, "북미", 1, true));
        assertThat(mapper.toCountryEntities(internationalPolicy()))
            .extracting(ShippingZoneCountryEntity::getShippingPolicyId, ShippingZoneCountryEntity::getShippingZoneId, ShippingZoneCountryEntity::getCountryCode)
            .containsExactly(
                tuple(INTERNATIONAL_POLICY_ID, 8101L, "JP"), tuple(INTERNATIONAL_POLICY_ID, 8101L, "SG"),
                tuple(INTERNATIONAL_POLICY_ID, 8201L, "CA"), tuple(INTERNATIONAL_POLICY_ID, 8201L, "US")
            );
        assertThat(mapper.toRateEntities(internationalPolicy()))
            .extracting(ShippingZoneRateEntity::getId, ShippingZoneRateEntity::getShippingZoneId, ShippingZoneRateEntity::getMaxWeightGrams,
                ShippingZoneRateEntity::getFeeAmount, ShippingZoneRateEntity::getFeeCurrency)
            .startsWith(tuple(8121L, 8101L, 500, 1_000L, USD))
            .hasSize(5);
    }

    @Test
    @DisplayName("구간 금액 컬럼의 통화가 없으면 데이터 오류다")
    void brokenRateMoney() {
        ShippingZoneRateEntity broken = ShippingZoneRateEntity.builder().id(1L).shippingZoneId(2L).maxWeightGrams(500).feeAmount(1_000L).build();

        assertThatThrownBy(() -> mapper.toDomain(broken)).isInstanceOf(IllegalStateException.class);
    }
}
