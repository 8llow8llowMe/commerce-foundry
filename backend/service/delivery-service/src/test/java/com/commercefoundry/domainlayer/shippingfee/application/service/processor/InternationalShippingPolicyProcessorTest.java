package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.INTERNATIONAL_POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.RateCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.ZoneCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class InternationalShippingPolicyProcessorTest {

    private static final ZoneCommand ASIA = new ZoneCommand("아시아", List.of("SG", "JP"), List.of(new RateCommand(1_000, usd(1_500)), new RateCommand(500, usd(1_000))));
    private static final ZoneCommand NORTH_AMERICA = new ZoneCommand("북미", List.of("US"), List.of(new RateCommand(1_000, usd(2_000))));

    @Mock
    private InternationalShippingPolicyRepositoryPort internationalShippingPolicyRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private InternationalShippingPolicyProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InternationalShippingPolicyProcessor(
            internationalShippingPolicyRepositoryPort, snowflakeIdGenerator, new StoreProperties(new StoreProperties.Features(true))
        );
    }

    private static InternationalShippingPolicyReplaceCommand command(Long version, List<ZoneCommand> zones) {
        return new InternationalShippingPolicyReplaceCommand(usd(500), usd(20_000), "관세는 받는 분 부담", zones, version);
    }

    private static void assertErrorCode(Throwable thrown, DeliveryErrorCode errorCode) {
        assertThat(thrown).isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private void givenSavedPolicy() {
        given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.of(internationalPolicy()));
    }

    @Test
    @DisplayName("정책이 없고 version 이 null 이면 정책 · 그룹 · 국가 · 구간 아이디를 발급해 만든다 — 그룹은 요청 순서, 저장은 한 번")
    void createsWhenAbsent() {
        given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9001L, 9101L, 9102L, 9103L, 9104L, 9105L, 9201L, 9202L, 9203L);
        given(internationalShippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> invocation.getArgument(0));

        processor.replacePolicy(command(null, List.of(ASIA, NORTH_AMERICA)));

        ArgumentCaptor<InternationalShippingPolicy> saved = ArgumentCaptor.forClass(InternationalShippingPolicy.class);
        verify(internationalShippingPolicyRepositoryPort, times(1)).replace(saved.capture());
        InternationalShippingPolicy policy = saved.getValue();
        assertThat(policy.id()).isEqualTo(9001L);
        assertThat(policy.version()).isNull();
        assertThat(policy.baseFee()).isEqualTo(usd(500));
        assertThat(policy.freeShippingThreshold()).isEqualTo(usd(20_000));
        assertThat(policy.customsNotice()).isEqualTo("관세는 받는 분 부담");
        assertThat(policy.zones()).extracting(ShippingZone::name).containsExactly("아시아", "북미");
        ShippingZone asia = policy.zones().getFirst();
        assertThat(asia.id()).isEqualTo(9105L);
        assertThat(asia.countries()).extracting(ShippingZoneCountry::id, ShippingZoneCountry::countryCode)
            .containsExactly(tuple(9102L, "JP"), tuple(9101L, "SG"));
        assertThat(asia.rates()).extracting(ShippingZoneRate::maxWeightGrams).containsExactly(500, 1_000);
    }

    @Test
    @DisplayName("정책이 있으면 아이디를 유지하고 조회한 version 으로 교체한다")
    void replacesExistingWithSameVersion() {
        givenSavedPolicy();
        given(snowflakeIdGenerator.generateId()).willReturn(1L, 2L, 3L);
        given(internationalShippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> invocation.getArgument(0));

        InternationalShippingPolicy result = processor.replacePolicy(command(2L, List.of(NORTH_AMERICA)));

        assertThat(result.id()).isEqualTo(INTERNATIONAL_POLICY_ID);
        assertThat(result.version()).isEqualTo(2L);
        assertThat(result.zones()).extracting(ShippingZone::name).containsExactly("북미");
    }

    @Test
    @DisplayName("정책이 있는데 version 이 다르거나 비었으면 DELIVERY_006 이고 저장하지 않는다")
    void staleVersion() {
        givenSavedPolicy();

        assertErrorCode(catchThrowable(() -> processor.replacePolicy(command(1L, List.of(ASIA)))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        assertErrorCode(catchThrowable(() -> processor.replacePolicy(command(null, List.of(ASIA)))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(internationalShippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("확인 뒤 저장에서 동시 교체에 지면 DELIVERY_006 으로 바꾼다")
    void concurrentReplace() {
        givenSavedPolicy();
        given(snowflakeIdGenerator.generateId()).willReturn(1L, 2L, 3L);
        given(internationalShippingPolicyRepositoryPort.replace(any())).willThrow(new ObjectOptimisticLockingFailureException(Object.class, INTERNATIONAL_POLICY_ID));

        Throwable thrown = catchThrowable(() -> processor.replacePolicy(command(2L, List.of(NORTH_AMERICA))));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        assertThat(thrown).hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /** 쓰기 트랜잭션(READ COMMITTED)의 교체 전 조회가 동시 교체와 겹쳐 하위 행이 엇갈리면 어댑터가 낙관적 락 실패로 알린다. */
    @Test
    @DisplayName("교체 전 조회가 엇갈린 스냅숏을 만나도(낙관적 락 실패) 500 이 아니라 DELIVERY_006 이고 저장하지 않는다")
    void inconsistentReadBeforeReplace() {
        given(internationalShippingPolicyRepositoryPort.findPolicy()).willThrow(new ObjectOptimisticLockingFailureException(Object.class, INTERNATIONAL_POLICY_ID));

        Throwable thrown = catchThrowable(() -> processor.replacePolicy(command(2L, List.of(NORTH_AMERICA))));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(internationalShippingPolicyRepositoryPort, never()).replace(any());
    }

    /** #70 — 저장된 USD 정책을 KRW 로 바꾸면 그때부터 USD 주문 견적이 모두 막힌다. */
    @Test
    @DisplayName("저장된 정책과 통화가 다르면 DELIVERY_012 이고 저장하지 않는다")
    void currencyChangeIsRejected() {
        givenSavedPolicy();
        InternationalShippingPolicyReplaceCommand krwCommand = new InternationalShippingPolicyReplaceCommand(
            krw(5_000), null, null, List.of(new ZoneCommand("아시아", List.of("JP"), List.of(new RateCommand(500, krw(10_000))))), 2L
        );

        Throwable thrown = catchThrowable(() -> processor.replacePolicy(krwCommand));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_CURRENCY_CHANGE_NOT_ALLOWED);
        assertThat(thrown).hasMessage("배송비 정책의 통화는 바꿀 수 없습니다. 현재 통화: USD");
        verify(internationalShippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("무료배송 기준 · 무게 요금 통화가 기본료와 다르면 DELIVERY_003 — 정책을 읽지 않는다")
    void mixedCurrencies() {
        ZoneCommand krwRate = new ZoneCommand("아시아", List.of("JP"), List.of(new RateCommand(500, krw(10_000))));

        assertErrorCode(catchThrowable(() -> processor.replacePolicy(command(null, List.of(krwRate)))), DeliveryErrorCode.CURRENCY_MISMATCH);
        assertErrorCode(catchThrowable(() -> processor.replacePolicy(
            new InternationalShippingPolicyReplaceCommand(usd(500), krw(100_000), null, List.of(ASIA), null))), DeliveryErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(internationalShippingPolicyRepositoryPort, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("KR 을 넣으면 DELIVERY_009 이고 그룹 이름을 알린다")
    void domesticCountry() {
        ZoneCommand withKorea = new ZoneCommand("동아시아", List.of("JP", "KR"), List.of(new RateCommand(500, usd(1_000))));

        Throwable thrown = catchThrowable(() -> processor.replacePolicy(command(null, List.of(withKorea))));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_ZONE_DOMESTIC_COUNTRY);
        assertThat(thrown).hasMessage("국내(KR)는 해외 국가 그룹에 넣을 수 없습니다: 동아시아");
        verifyNoInteractions(internationalShippingPolicyRepositoryPort);
    }

    @Test
    @DisplayName("그룹 이름이 겹치면 DELIVERY_010")
    void duplicatedZoneName() {
        ZoneCommand anotherAsia = new ZoneCommand("아시아", List.of("US"), List.of(new RateCommand(500, usd(1_000))));

        assertErrorCode(catchThrowable(() -> processor.replacePolicy(command(null, List.of(ASIA, anotherAsia)))), DeliveryErrorCode.SHIPPING_ZONE_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("한 국가가 두 그룹에 들거나 한 그룹에 두 번 들면 DELIVERY_007 이고 국가를 알린다")
    void duplicatedCountry() {
        ZoneCommand withJapan = new ZoneCommand("기타", List.of("US", "JP"), List.of(new RateCommand(500, usd(1_000))));
        ZoneCommand twice = new ZoneCommand("기타", List.of("US", "US"), List.of(new RateCommand(500, usd(1_000))));

        Throwable across = catchThrowable(() -> processor.replacePolicy(command(null, List.of(ASIA, withJapan))));
        assertErrorCode(across, DeliveryErrorCode.SHIPPING_ZONE_COUNTRY_DUPLICATED);
        assertThat(across).hasMessage("국가가 중복되었습니다(그룹 안 · 그룹 간): JP");
        assertErrorCode(catchThrowable(() -> processor.replacePolicy(command(null, List.of(twice)))), DeliveryErrorCode.SHIPPING_ZONE_COUNTRY_DUPLICATED);
    }

    @Test
    @DisplayName("한 그룹의 구간 상한이 겹치면 DELIVERY_008 이고 그룹 이름 · 상한을 알린다 — 다른 그룹끼리는 같아도 된다")
    void duplicatedMaxWeight() {
        ZoneCommand duplicated = new ZoneCommand("유럽", List.of("FR"), List.of(new RateCommand(500, usd(1_000)), new RateCommand(500, usd(1_200))));

        Throwable thrown = catchThrowable(() -> processor.replacePolicy(command(null, List.of(duplicated))));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_ZONE_RATE_DUPLICATED);
        assertThat(thrown).hasMessage("국가 그룹의 무게 구간 상한이 겹칩니다: 유럽 500g");
    }

    @Test
    @DisplayName("관리 조회는 기능 플래그와 무관하고, 고객 조회는 플래그가 꺼지면 정책을 읽지 않고 빈 값이다")
    void findsByFlag() {
        InternationalShippingPolicyProcessor off = new InternationalShippingPolicyProcessor(
            internationalShippingPolicyRepositoryPort, snowflakeIdGenerator, new StoreProperties(null)
        );

        assertThat(off.findOfferedPolicy()).isEmpty();
        verifyNoInteractions(internationalShippingPolicyRepositoryPort);

        givenSavedPolicy();
        assertThat(off.findPolicy()).hasValue(internationalPolicy());
        assertThat(processor.findOfferedPolicy()).hasValue(internationalPolicy());
    }
}
