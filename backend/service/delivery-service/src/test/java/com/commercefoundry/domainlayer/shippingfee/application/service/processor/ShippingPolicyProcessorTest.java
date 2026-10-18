package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
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
class ShippingPolicyProcessorTest {

    private static final RemoteAreaCommand JEJU = new RemoteAreaCommand("제주", postalCode("63000"), postalCode("63644"), krw(3_000));
    private static final RemoteAreaCommand ULLEUNG = new RemoteAreaCommand("울릉", postalCode("40200"), postalCode("40240"), krw(5_000));

    @Mock
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private ShippingPolicyProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ShippingPolicyProcessor(shippingPolicyRepositoryPort, snowflakeIdGenerator);
    }

    private static ShippingPolicyReplaceCommand command(Long version, List<RemoteAreaCommand> remoteAreas) {
        return new ShippingPolicyReplaceCommand(krw(3_000), krw(50_000), remoteAreas, version);
    }

    private static void assertErrorCode(Throwable thrown, DeliveryErrorCode errorCode) {
        assertThat(thrown).isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    @Test
    @DisplayName("정책이 없고 version 이 null 이면 새 정책 · 구간 아이디를 발급해 만든다 — 저장은 한 번")
    void createsWhenAbsent() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9101L, 9102L, 9001L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> invocation.getArgument(0));

        processor.replaceDomesticPolicy(command(null, List.of(JEJU, ULLEUNG)));

        ArgumentCaptor<ShippingPolicy> saved = ArgumentCaptor.forClass(ShippingPolicy.class);
        verify(shippingPolicyRepositoryPort, times(1)).replace(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(9001L);
        assertThat(saved.getValue().version()).isNull();
        assertThat(saved.getValue().scope()).isEqualTo(ShippingScope.DOMESTIC);
        assertThat(saved.getValue().baseFee()).isEqualTo(krw(3_000));
        assertThat(saved.getValue().freeShippingThreshold()).isEqualTo(krw(50_000));
        assertThat(saved.getValue().remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(
            tuple(9102L, "울릉"),
            tuple(9101L, "제주")
        );
    }

    @Test
    @DisplayName("정책이 있으면 아이디를 유지하고 조회한 version 으로 교체한다 — 구간 아이디는 새로 발급한다")
    void replacesExistingWithSameVersion() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));
        given(snowflakeIdGenerator.generateId()).willReturn(9201L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> invocation.getArgument(0));

        ShippingPolicy result = processor.replaceDomesticPolicy(command(3L, List.of(JEJU)));

        assertThat(result.id()).isEqualTo(POLICY_ID);
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.remoteAreas()).extracting(RemoteArea::id).containsExactly(9201L);
        verify(shippingPolicyRepositoryPort, times(1)).replace(any());
    }

    @Test
    @DisplayName("무료배송 기준이 없고 구간이 없는 정책도 된다")
    void withoutThresholdAndAreas() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9001L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> invocation.getArgument(0));

        ShippingPolicy result = processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(0), null, List.of(), null));

        assertThat(result.freeShippingThreshold()).isNull();
        assertThat(result.remoteAreas()).isEmpty();
    }

    @Test
    @DisplayName("정책이 있는데 version 이 다르면 DELIVERY_006 이고 저장하지 않는다")
    void staleVersion() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(2L, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("정책이 있는데 version 을 비우면(처음 만들기로 착각) DELIVERY_006")
    void missingVersionForExisting() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(null, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("정책이 없는데 version 을 보내면(그 사이 사라짐) DELIVERY_006")
    void versionForAbsent() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.empty());

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(0L, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("확인 뒤 저장에서 동시 교체에 지면(낙관적 락 실패) DELIVERY_006 으로 바꾼다")
    void concurrentReplace() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));
        given(shippingPolicyRepositoryPort.replace(any())).willThrow(new ObjectOptimisticLockingFailureException(Object.class, POLICY_ID));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of())));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        assertThat(thrown).hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("구간이 겹치면 DELIVERY_005 이고 두 구간 이름을 시작 우편번호 순으로 알린다 — 정책을 읽지 않는다")
    void overlappingAreas() {
        RemoteAreaCommand inside = new RemoteAreaCommand("제주 일부", postalCode("63600"), postalCode("63700"), krw(1_000));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of(inside, JEJU))));

        assertErrorCode(thrown, DeliveryErrorCode.REMOTE_AREA_RANGE_OVERLAP);
        assertThat(thrown).hasMessage("도서산간 구간이 겹칩니다: 제주, 제주 일부");
        verify(shippingPolicyRepositoryPort, never()).findByScope(any());
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("구간 시작이 끝보다 크면 DELIVERY_004 이고 구간 이름을 알린다 — 아이디를 발급하지 않는다")
    void reversedRange() {
        RemoteAreaCommand reversed = new RemoteAreaCommand("거꾸로", postalCode("63644"), postalCode("63000"), krw(1_000));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of(reversed))));

        assertErrorCode(thrown, DeliveryErrorCode.REMOTE_AREA_RANGE_INVALID);
        assertThat(thrown).hasMessage("도서산간 구간의 시작 우편번호가 끝 우편번호보다 큽니다: 거꾸로");
        verifyNoInteractions(shippingPolicyRepositoryPort, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("무료배송 기준 · 추가 배송비 통화가 기본 배송비와 다르면 DELIVERY_003")
    void mixedCurrencies() {
        Money usd = Money.of(1_000, CurrencyCode.USD);
        RemoteAreaCommand usdArea = new RemoteAreaCommand("제주", postalCode("63000"), postalCode("63644"), usd);

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(3_000), usd, List.of(), 3L))),
            DeliveryErrorCode.CURRENCY_MISMATCH);
        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(3_000), null, List.of(usdArea), 3L))),
            DeliveryErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(shippingPolicyRepositoryPort);
    }

    @Test
    @DisplayName("조회는 국내 범위 정책이다")
    void findsDomesticPolicy() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));

        assertThat(processor.findDomesticPolicy()).hasValue(domesticPolicy());
    }
}
