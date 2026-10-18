package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShippingFeeQuoteProcessorTest {

    @Mock
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    private ShippingFeeQuoteProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ShippingFeeQuoteProcessor(shippingPolicyRepositoryPort);
    }

    @Test
    @DisplayName("국내 정책으로 계산한다 — 제주 · 무료배송 기준 미만이면 기본 + 추가 배송비, 스냅숏용 기준 · 정책 버전을 함께 낸다")
    void quotesWithDomesticPolicy() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));

        ShippingFeeQuote quote = processor.quote(new ShippingFeeQuoteCommand(krw(99_999), "KR", postalCode("63100")));

        assertThat(quote.totalFee()).isEqualTo(krw(6_000));
        assertThat(quote.freeShippingApplied()).isFalse();
        assertThat(quote.remoteAreaName()).isEqualTo("제주");
        assertThat(quote.freeShippingThreshold()).isEqualTo(krw(100_000));
        assertThat(quote.shippingPolicyVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("배송할 수 없는 국가면 정책을 읽지 않고 DELIVERY_002")
    void unsupportedCountry() {
        assertThatThrownBy(() -> processor.quote(new ShippingFeeQuoteCommand(krw(10_000), "US", postalCode("10001"))))
            .isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED));
        verifyNoInteractions(shippingPolicyRepositoryPort);
    }

    @Test
    @DisplayName("정책이 없으면 0원으로 가정하지 않고 DELIVERY_001")
    void policyNotConfigured() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.empty());

        assertThatThrownBy(() -> processor.quote(new ShippingFeeQuoteCommand(krw(10_000), "KR", postalCode("06236"))))
            .isInstanceOfSatisfying(DeliveryException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED);
                assertThat(exception.getMessage()).isEqualTo("배송비 정책이 설정되지 않았습니다.");
            });
    }

    @Test
    @DisplayName("주문 금액 통화가 정책과 다르면 Money 의 IllegalArgumentException 이 아니라 DELIVERY_003")
    void currencyMismatch() {
        given(shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC)).willReturn(Optional.of(domesticPolicy()));

        assertThatThrownBy(() -> processor.quote(new ShippingFeeQuoteCommand(Money.of(10_000, CurrencyCode.USD), "KR", postalCode("06236"))))
            .isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.CURRENCY_MISMATCH));
    }
}
