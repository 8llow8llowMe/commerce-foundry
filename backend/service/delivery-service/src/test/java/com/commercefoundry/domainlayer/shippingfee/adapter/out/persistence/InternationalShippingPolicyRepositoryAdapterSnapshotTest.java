package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.INTERNATIONAL_POLICY_ID;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneCountryRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneRateRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneRepository;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.InternationalShippingPolicyMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 읽기 문장들이 서로 다른 스냅숏을 본 경우(동시 교체가 하위 행 아이디를 새로 발급) 도메인 생성자의 IllegalArgumentException(500) 대신
 * 낙관적 락 실패로 알리는지 본다. 실제 동시 실행은 재현하기 어려워 리포지토리를 목으로 두고 엇갈린 행을 돌려준다.
 */
@ExtendWith(MockitoExtension.class)
class InternationalShippingPolicyRepositoryAdapterSnapshotTest {

    private static final long OLD_ZONE = 8101L;
    private static final long NEW_ZONE = 9101L;

    @Mock
    private ShippingPolicyRepository shippingPolicyRepository;

    @Mock
    private ShippingZoneRepository shippingZoneRepository;

    @Mock
    private ShippingZoneCountryRepository shippingZoneCountryRepository;

    @Mock
    private ShippingZoneRateRepository shippingZoneRateRepository;

    private InternationalShippingPolicyRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InternationalShippingPolicyRepositoryAdapter(
            shippingPolicyRepository, shippingZoneRepository, shippingZoneCountryRepository, shippingZoneRateRepository, new InternationalShippingPolicyMapperImpl()
        );
        given(shippingPolicyRepository.findByScope(ShippingScope.INTERNATIONAL)).willReturn(Optional.of(ShippingPolicyEntity.builder()
            .id(INTERNATIONAL_POLICY_ID).scope(ShippingScope.INTERNATIONAL).baseFeeAmount(500L).baseFeeCurrency(USD).version(2L).build()));
    }

    private static ShippingZoneEntity zone(long id) {
        return ShippingZoneEntity.builder().id(id).shippingPolicyId(INTERNATIONAL_POLICY_ID).name("아시아").displayOrder(0).build();
    }

    private static ShippingZoneCountryEntity country(long zoneId) {
        return ShippingZoneCountryEntity.builder().id(zoneId + 1).shippingPolicyId(INTERNATIONAL_POLICY_ID).shippingZoneId(zoneId).countryCode("JP").build();
    }

    private static ShippingZoneRateEntity rate(long zoneId) {
        return ShippingZoneRateEntity.builder().id(zoneId + 2).shippingZoneId(zoneId).maxWeightGrams(500).feeAmount(1_000L).feeCurrency(USD).build();
    }

    @Test
    @DisplayName("같은 스냅숏이면 그대로 읽는다")
    void consistent() {
        given(shippingZoneRepository.findAllByShippingPolicyIdOrderByDisplayOrderAsc(INTERNATIONAL_POLICY_ID)).willReturn(List.of(zone(OLD_ZONE)));
        given(shippingZoneCountryRepository.findAllByShippingPolicyId(INTERNATIONAL_POLICY_ID)).willReturn(List.of(country(OLD_ZONE)));
        given(shippingZoneRateRepository.findAllByShippingZoneIdIn(List.of(OLD_ZONE))).willReturn(List.of(rate(OLD_ZONE)));

        assertThat(adapter.findPolicy()).hasValueSatisfying(policy -> assertThat(policy.zones()).hasSize(1));
    }

    @Test
    @DisplayName("새 그룹 + 옛 국가(교체 사이에 끼인 읽기)면 낙관적 락 실패다")
    void newZonesOldCountries() {
        given(shippingZoneRepository.findAllByShippingPolicyIdOrderByDisplayOrderAsc(INTERNATIONAL_POLICY_ID)).willReturn(List.of(zone(NEW_ZONE)));
        given(shippingZoneCountryRepository.findAllByShippingPolicyId(INTERNATIONAL_POLICY_ID)).willReturn(List.of(country(OLD_ZONE)));
        given(shippingZoneRateRepository.findAllByShippingZoneIdIn(List.of(NEW_ZONE))).willReturn(List.of(rate(NEW_ZONE)));

        assertThatThrownBy(() -> adapter.findPolicy()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("그룹은 있는데 구간이 아직 없으면(삭제 뒤 · 삽입 전) 낙관적 락 실패다")
    void zonesWithoutRates() {
        given(shippingZoneRepository.findAllByShippingPolicyIdOrderByDisplayOrderAsc(INTERNATIONAL_POLICY_ID)).willReturn(List.of(zone(NEW_ZONE)));
        given(shippingZoneCountryRepository.findAllByShippingPolicyId(INTERNATIONAL_POLICY_ID)).willReturn(List.of(country(NEW_ZONE)));
        given(shippingZoneRateRepository.findAllByShippingZoneIdIn(List.of(NEW_ZONE))).willReturn(List.of());

        assertThatThrownBy(() -> adapter.findPolicy()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("그룹은 비었는데 국가가 남아 있으면 낙관적 락 실패다")
    void noZonesButCountries() {
        given(shippingZoneRepository.findAllByShippingPolicyIdOrderByDisplayOrderAsc(INTERNATIONAL_POLICY_ID)).willReturn(List.of());
        given(shippingZoneCountryRepository.findAllByShippingPolicyId(INTERNATIONAL_POLICY_ID)).willReturn(List.of(country(OLD_ZONE)));

        assertThatThrownBy(() -> adapter.findPolicy()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
