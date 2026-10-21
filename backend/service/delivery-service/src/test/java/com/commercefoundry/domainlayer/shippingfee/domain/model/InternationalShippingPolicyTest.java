package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.CUSTOMS_NOTICE;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.asia;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.northAmerica;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InternationalShippingPolicyTest {

    private static InternationalShippingPolicy policy(Money baseFee, Money threshold, List<ShippingZone> zones) {
        return new InternationalShippingPolicy(1L, baseFee, threshold, null, zones, 0L, null);
    }

    private static ShippingZone zone(long id, String name, String countryCode, long cents) {
        return new ShippingZone(id, name, List.of(new ShippingZoneCountry(id + 1, countryCode)), List.of(new ShippingZoneRate(id + 2, 1_000, usd(cents))));
    }

    @Nested
    @DisplayName("견적")
    class Quote {

        @ParameterizedTest(name = "{0} {1}g → 무게 요금 {2}센트, 배송비 {3}센트")
        @CsvSource({
            "JP, 1, 1000, 1500",
            "JP, 500, 1000, 1500",
            "JP, 501, 1500, 2000",
            "JP, 1000, 1500, 2000",
            "JP, 1001, 2500, 3000",
            "SG, 2000, 2500, 3000",
            "CA, 1000, 2000, 2500",
            "US, 1001, 6000, 6500",
            "US, 5000, 6000, 6500"
        })
        @DisplayName("국가 · 무게 경계 — 상한과 같은 무게는 그 구간, 상한 + 1g 은 다음 구간. 배송비 = 기본료 + 무게 요금")
        void countryWeightBoundaries(String countryCode, int weightGrams, long weightFee, long total) {
            ShippingFeeQuote quote = internationalPolicy().quote(usd(10_000), countryCode, weightGrams);

            assertThat(quote.scope()).isEqualTo(ShippingScope.INTERNATIONAL);
            assertThat(quote.weightFee()).isEqualTo(usd(weightFee));
            assertThat(quote.totalFee()).isEqualTo(usd(total));
            assertThat(quote.freeShippingApplied()).isFalse();
            assertThat(quote.remoteAreaSurcharge()).isEqualTo(usd(0));
            assertThat(quote.remoteAreaName()).isNull();
        }

        @ParameterizedTest(name = "주문 {0}센트 → 배송비 {1}센트, 무료배송 {2}")
        @CsvSource({"19999, 2000, false", "20000, 0, true", "20001, 0, true", "0, 2000, false"})
        @DisplayName("무료배송은 주문 금액이 기준($200) 이상일 때 — 기본료와 무게 요금을 모두 받지 않는다")
        void freeShippingBoundary(long orderCents, long expectedTotal, boolean free) {
            ShippingFeeQuote quote = internationalPolicy().quote(usd(orderCents), "JP", 800);

            assertThat(quote.totalFee()).isEqualTo(usd(expectedTotal));
            assertThat(quote.freeShippingApplied()).isEqualTo(free);
            assertThat(quote.baseFee()).isEqualTo(usd(500));
            assertThat(quote.weightFee()).isEqualTo(usd(1_500));
        }

        @Test
        @DisplayName("무료배송 기준이 없으면 주문 금액과 무관하게 기본료 + 무게 요금이다")
        void noThreshold() {
            ShippingFeeQuote quote = policy(usd(0), null, List.of(asia())).quote(usd(10_000_000), "JP", 500);

            assertThat(quote.totalFee()).isEqualTo(usd(1_000));
            assertThat(quote.freeShippingApplied()).isFalse();
        }

        @Test
        @DisplayName("국가 그룹 이름 · 정책 버전 · 무료배송 기준을 스냅숏용으로 낸다")
        void carriesSnapshot() {
            ShippingFeeQuote quote = internationalPolicy().quote(usd(10_000), "US", 500);

            assertThat(quote.shippingZoneName()).isEqualTo("북미");
            assertThat(quote.shippingPolicyVersion()).isEqualTo(2L);
            assertThat(quote.freeShippingThreshold()).isEqualTo(usd(20_000));
        }

        @Test
        @DisplayName("그룹에 없는 국가 · 최대 무게 초과 · 다른 통화는 IllegalArgumentException — 호출자가 먼저 도메인 오류로 거절한다")
        void rejectsUnsupported() {
            assertThatThrownBy(() -> internationalPolicy().quote(usd(10_000), "FR", 500)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> internationalPolicy().quote(usd(10_000), "JP", 2_001)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> internationalPolicy().quote(krw(10_000), "JP", 500)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> internationalPolicy().quote(usd(10_000), "JP", 0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("저장되지 않은 정책(버전 없음)으로는 견적하지 않는다")
        void unsavedPolicyCannotQuote() {
            InternationalShippingPolicy unsaved = new InternationalShippingPolicy(1L, usd(500), null, null, List.of(asia()), null, null);

            assertThatThrownBy(() -> unsaved.quote(usd(10_000), "JP", 500)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test
        @DisplayName("국가 그룹은 주어진(표시) 순서를 지킨다")
        void keepsZoneOrder() {
            assertThat(policy(usd(500), null, List.of(northAmerica(), asia())).zones()).extracting(ShippingZone::name).containsExactly("북미", "아시아");
        }

        @Test
        @DisplayName("무료배송 기준 · 무게 요금의 통화가 기본료와 다르면 거부한다")
        void mixedCurrencies() {
            assertThatThrownBy(() -> policy(usd(500), krw(100_000), List.of(asia()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> policy(krw(5_000), null, List.of(asia()))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("한 국가가 두 그룹에 들면 거부한다")
        void countryInTwoZones() {
            assertThatThrownBy(() -> policy(usd(500), null, List.of(zone(10L, "가", "JP", 100), zone(20L, "나", "JP", 200))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JP");
        }

        @Test
        @DisplayName("그룹 이름이 겹치면 거부한다")
        void duplicatedZoneName() {
            assertThatThrownBy(() -> policy(usd(500), null, List.of(zone(10L, "가", "JP", 100), zone(20L, "가", "US", 200))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("가");
        }

        @Test
        @DisplayName("무료배송 기준 0 · 빈 안내 문구는 거부한다 — 없으면 null 이다")
        void zeroThresholdAndBlankNotice() {
            assertThatThrownBy(() -> policy(usd(500), usd(0), List.of(asia()))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InternationalShippingPolicy(1L, usd(500), null, " ", List.of(asia()), 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(new InternationalShippingPolicy(1L, usd(500), null, CUSTOMS_NOTICE, List.of(asia()), 0L, null).customsNotice()).isEqualTo(CUSTOMS_NOTICE);
        }

        /** 도서산간(우편번호 구간)은 국내 모델에만 있다 — 해외 모델에는 담을 자리가 없고, 어드민 요청의 remoteAreas 는 400(DELIVERY_136)이다. */
        @Test
        @DisplayName("해외 정책 모델에는 도서산간 구간이 없다")
        void hasNoRemoteAreas() {
            assertThat(Arrays.stream(InternationalShippingPolicy.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("remoteAreas");
        }
    }
}
