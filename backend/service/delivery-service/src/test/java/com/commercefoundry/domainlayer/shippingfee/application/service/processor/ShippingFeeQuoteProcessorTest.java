package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShippingFeeQuoteProcessorTest {

    private static final StoreProperties INTERNATIONAL_ON = new StoreProperties(new StoreProperties.Features(true));
    private static final StoreProperties INTERNATIONAL_OFF = new StoreProperties(null);

    @Mock
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    @Mock
    private InternationalShippingPolicyRepositoryPort internationalShippingPolicyRepositoryPort;

    private ShippingFeeQuoteProcessor processor(StoreProperties storeProperties) {
        return new ShippingFeeQuoteProcessor(shippingPolicyRepositoryPort, internationalShippingPolicyRepositoryPort, storeProperties);
    }

    private static void assertErrorCode(ThrowingCallable call, DeliveryErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    @Nested
    @DisplayName("국내 (KR)")
    class Domestic {

        @Test
        @DisplayName("국내 정책으로 계산한다 — 제주 · 무료배송 기준 미만이면 기본 + 추가 배송비, 스냅숏용 기준 · 정책 버전을 함께 낸다. 해외 정책은 읽지 않는다")
        void quotesWithDomesticPolicy() {
            given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

            ShippingFeeQuote quote = processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.domestic(krw(99_999), postalCode("63100")));

            assertThat(quote.scope()).isEqualTo(ShippingScope.DOMESTIC);
            assertThat(quote.totalFee()).isEqualTo(krw(6_000));
            assertThat(quote.freeShippingApplied()).isFalse();
            assertThat(quote.remoteAreaName()).isEqualTo("제주");
            assertThat(quote.shippingZoneName()).isNull();
            assertThat(quote.weightFee()).isNull();
            assertThat(quote.freeShippingThreshold()).isEqualTo(krw(100_000));
            assertThat(quote.shippingPolicyVersion()).isEqualTo(3L);
            verifyNoInteractions(internationalShippingPolicyRepositoryPort);
        }

        @Test
        @DisplayName("해외 배송이 꺼져 있어도 국내 견적은 그대로다")
        void domesticIgnoresFlag() {
            given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

            assertThat(processor(INTERNATIONAL_OFF).quote(ShippingFeeQuoteCommand.domestic(krw(10_000), postalCode("06236"))).totalFee()).isEqualTo(krw(3_000));
        }

        @Test
        @DisplayName("정책이 없으면 0원으로 가정하지 않고 DELIVERY_001")
        void policyNotConfigured() {
            given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());

            assertThatThrownBy(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.domestic(krw(10_000), postalCode("06236"))))
                .isInstanceOfSatisfying(DeliveryException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED);
                    assertThat(exception.getMessage()).isEqualTo("배송비 정책이 설정되지 않았습니다.");
                });
        }

        /** #70 — 영문 스토어(USD) 주문의 국내 배송. 환산하지 않는다. */
        @Test
        @DisplayName("USD 주문 + KR 배송지는 국내 정책(KRW)과 통화가 달라 환산하지 않고 DELIVERY_003")
        void usdOrderToKoreaIsRejected() {
            given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

            assertErrorCode(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.domestic(Money.of(10_000, CurrencyCode.USD), postalCode("06236"))),
                DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }

    @Nested
    @DisplayName("해외 (KR 외)")
    class International {

        private ShippingFeeQuote quote(long orderCents, String countryCode, int weightGrams) {
            given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.of(internationalPolicy()));
            return processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(usd(orderCents), countryCode, weightGrams));
        }

        @ParameterizedTest(name = "{0} {1}g → 무게 요금 {2}센트, 배송비 {3}센트")
        @CsvSource({
            "JP, 1, 1000, 1500",
            "JP, 500, 1000, 1500",
            "JP, 501, 1500, 2000",
            "SG, 1000, 1500, 2000",
            "JP, 2000, 2500, 3000",
            "US, 1001, 6000, 6500"
        })
        @DisplayName("무게 요금은 상한이 무게 이상인 가장 작은 구간 (상한 포함, 상한 + 1g 은 다음 구간) — 배송비는 기본료($5) + 무게 요금")
        void weightBoundaries(String countryCode, int weightGrams, long expectedWeightFee, long expectedTotal) {
            ShippingFeeQuote quote = quote(10_000, countryCode, weightGrams);

            assertThat(quote.scope()).isEqualTo(ShippingScope.INTERNATIONAL);
            assertThat(quote.weightFee()).isEqualTo(usd(expectedWeightFee));
            assertThat(quote.baseFee()).isEqualTo(usd(500));
            assertThat(quote.totalFee()).isEqualTo(usd(expectedTotal));
            assertThat(quote.remoteAreaSurcharge()).isEqualTo(usd(0));
            assertThat(quote.remoteAreaName()).isNull();
            assertThat(quote.shippingPolicyVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("국가 그룹 이름 · 무료배송 기준 스냅숏을 낸다")
        void carriesZoneAndSnapshot() {
            ShippingFeeQuote quote = quote(10_000, "US", 300);

            assertThat(quote.shippingZoneName()).isEqualTo("북미");
            assertThat(quote.freeShippingThreshold()).isEqualTo(usd(20_000));
        }

        @ParameterizedTest(name = "주문 {0}센트 → 무료배송 {1}")
        @CsvSource({"19999, false", "20000, true", "20001, true"})
        @DisplayName("무료배송은 주문 금액이 기준($200) 이상일 때 — 기본료 · 무게 요금 모두 0")
        void freeShippingBoundary(long orderCents, boolean free) {
            ShippingFeeQuote quote = quote(orderCents, "JP", 800);

            assertThat(quote.freeShippingApplied()).isEqualTo(free);
            assertThat(quote.totalFee()).isEqualTo(free ? usd(0) : usd(2_000));
            assertThat(quote.weightFee()).as("무게 요금은 원래 요금으로 남긴다").isEqualTo(usd(1_500));
        }

        @Test
        @DisplayName("가장 큰 구간보다 무거우면 DELIVERY_011 이고 무게와 최대 무게를 알린다")
        void overMaxWeight() {
            given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.of(internationalPolicy()));

            assertThatThrownBy(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(usd(10_000), "JP", 2_001)))
                .isInstanceOfSatisfying(DeliveryException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.WEIGHT_NOT_SUPPORTED);
                    assertThat(exception.getMessage()).isEqualTo("배송할 수 있는 무게를 넘었습니다: 2001g (최대 2000g)");
                });
        }

        @Test
        @DisplayName("어느 국가 그룹에도 없는 국가는 DELIVERY_002")
        void countryNotInAnyZone() {
            given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.of(internationalPolicy()));

            assertErrorCode(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(usd(10_000), "FR", 500)),
                DeliveryErrorCode.DESTINATION_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("해외 배송이 꺼져 있으면 정책을 읽지 않고 DELIVERY_002")
        void flagOff() {
            assertErrorCode(() -> processor(INTERNATIONAL_OFF).quote(ShippingFeeQuoteCommand.international(usd(10_000), "JP", 500)),
                DeliveryErrorCode.DESTINATION_NOT_SUPPORTED);
            verifyNoInteractions(internationalShippingPolicyRepositoryPort, shippingPolicyRepositoryPort);
        }

        @Test
        @DisplayName("해외 정책이 없으면 DELIVERY_001")
        void policyNotConfigured() {
            given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.empty());

            assertErrorCode(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(usd(10_000), "JP", 500)),
                DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("KRW 주문을 USD 해외 정책으로 견적하면 환산하지 않고 DELIVERY_003")
        void currencyMismatch() {
            given(internationalShippingPolicyRepositoryPort.findPolicy()).willReturn(Optional.of(internationalPolicy()));

            assertErrorCode(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(krw(100_000), "JP", 500)),
                DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }

    @Test
    @DisplayName("국가 코드 형식이 아니면(소문자) 정책을 읽지 않고 DELIVERY_002")
    void malformedCountry() {
        assertErrorCode(() -> processor(INTERNATIONAL_ON).quote(ShippingFeeQuoteCommand.international(usd(10_000), "us", 500)),
            DeliveryErrorCode.DESTINATION_NOT_SUPPORTED);
        verifyNoInteractions(shippingPolicyRepositoryPort, internationalShippingPolicyRepositoryPort);
    }
}
