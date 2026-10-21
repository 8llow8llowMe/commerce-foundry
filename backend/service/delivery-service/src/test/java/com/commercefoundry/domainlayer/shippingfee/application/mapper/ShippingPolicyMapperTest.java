package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingRemoteAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ShippingPolicyMapperTest {

    private final ShippingPolicyMapper mapper = new ShippingPolicyMapperImpl();

    private static ShippingPolicyEntity.ShippingPolicyEntityBuilder policyEntity() {
        return ShippingPolicyEntity.builder().id(7001L).scope(ShippingScope.DOMESTIC).baseFeeAmount(3_000L).baseFeeCurrency(KRW).version(3L);
    }

    private static ShippingRemoteAreaEntity areaEntity(Long id, String name, String from, String to, long surcharge) {
        return ShippingRemoteAreaEntity.builder()
            .id(id).shippingPolicyId(7001L).name(name).postalCodeFrom(from).postalCodeTo(to).surchargeAmount(surcharge).surchargeCurrency(KRW)
            .build();
    }

    @Test
    @DisplayName("엔티티 → 도메인: 금액 컬럼 쌍을 Money 로 묶고 버전 · 수정 시각 · 구간을 옮긴다")
    void toDomain() {
        ShippingPolicyEntity entity = policyEntity().freeShippingThresholdAmount(100_000L).freeShippingThresholdCurrency(KRW).build();
        ReflectionTestUtils.setField(entity, "updatedAt", UPDATED_AT);

        ShippingPolicy policy = mapper.toDomain(entity, List.of(
            areaEntity(7102L, "울릉", "40200", "40240", 5_000L),
            areaEntity(7101L, "제주", "63000", "63644", 3_000L)
        ));

        assertThat(policy).isEqualTo(domesticPolicy());
    }

    @Test
    @DisplayName("엔티티 → 도메인: 무료배송 기준 컬럼이 둘 다 null 이면 null, 한쪽만 있으면 데이터 오류다")
    void nullableThreshold() {
        assertThat(mapper.toDomain(policyEntity().build(), List.of()).freeShippingThreshold()).isNull();
        assertThatThrownBy(() -> mapper.toDomain(policyEntity().freeShippingThresholdAmount(100_000L).build(), List.of()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("도메인 → 엔티티: 새 정책은 버전 null 로 만든다 — Spring Data 가 persist 한다")
    void toEntity() {
        ShippingPolicy policy = new ShippingPolicy(7001L, ShippingScope.DOMESTIC, krw(3_000), krw(100_000), List.of(), null, null);

        ShippingPolicyEntity entity = mapper.toEntity(policy);

        assertThat(entity.getId()).isEqualTo(7001L);
        assertThat(entity.getScope()).isEqualTo(ShippingScope.DOMESTIC);
        assertThat(entity.getBaseFeeAmount()).isEqualTo(3_000L);
        assertThat(entity.getBaseFeeCurrency()).isEqualTo(KRW);
        assertThat(entity.getFreeShippingThresholdAmount()).isEqualTo(100_000L);
        assertThat(entity.getFreeShippingThresholdCurrency()).isEqualTo(KRW);
        assertThat(entity.getVersion()).isNull();
    }

    @Test
    @DisplayName("도메인 → 엔티티: 무료배송 기준이 없으면 두 컬럼 모두 null 이다")
    void toEntityWithoutThreshold() {
        ShippingPolicyEntity entity = mapper.toEntity(new ShippingPolicy(7001L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null));

        assertThat(entity.getFreeShippingThresholdAmount()).isNull();
        assertThat(entity.getFreeShippingThresholdCurrency()).isNull();
    }

    @Test
    @DisplayName("구간 도메인 → 엔티티: 정책 아이디를 붙이고 새 엔티티로 표시된다")
    void remoteAreaToEntities() {
        List<ShippingRemoteAreaEntity> entities = mapper.toEntities(List.of(jeju()), 7001L);

        assertThat(entities).singleElement().satisfies(entity -> {
            assertThat(entity.getId()).isEqualTo(7101L);
            assertThat(entity.getShippingPolicyId()).isEqualTo(7001L);
            assertThat(entity.getName()).isEqualTo("제주");
            assertThat(entity.getPostalCodeFrom()).isEqualTo("63000");
            assertThat(entity.getPostalCodeTo()).isEqualTo("63644");
            assertThat(entity.getSurchargeAmount()).isEqualTo(3_000L);
            assertThat(entity.getSurchargeCurrency()).isEqualTo(KRW);
            assertThat(entity.isNew()).isTrue();
        });
    }

    @Test
    @DisplayName("구간 엔티티 → 도메인")
    void remoteAreaToDomain() {
        RemoteArea area = mapper.toDomain(areaEntity(7101L, "제주", "63000", "63644", 3_000L));

        assertThat(area).isEqualTo(jeju());
    }
}
