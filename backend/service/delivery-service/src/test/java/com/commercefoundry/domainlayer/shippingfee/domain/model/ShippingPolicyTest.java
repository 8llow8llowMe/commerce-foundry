package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.remoteArea;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.ulleung;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShippingPolicyTest {

    private static final String MAINLAND = "06236";

    private static final long VERSION = 5L;

    /** 저장된 정책 (버전 5). */
    private static ShippingPolicy policy(Money freeShippingThreshold, List<RemoteArea> remoteAreas) {
        return new ShippingPolicy(1L, ShippingScope.DOMESTIC, krw(3_000), freeShippingThreshold, remoteAreas, VERSION, null);
    }

    @Nested
    @DisplayName("견적")
    class Quote {

        @ParameterizedTest(name = "주문 {0}원 → 배송비 {1}원, 무료배송 {2}")
        @CsvSource({
            "99999, 3000, false",
            "100000, 0, true",
            "100001, 0, true",
            "0, 3000, false"
        })
        @DisplayName("무료배송은 주문 금액이 기준 이상일 때다 — 기준 100,000원")
        void freeShippingAtOrAboveThreshold(long orderAmount, long expectedFee, boolean expectedFree) {
            ShippingFeeQuote quote = policy(krw(100_000), List.of()).quote(krw(orderAmount), postalCode(MAINLAND));

            assertThat(quote.totalFee()).isEqualTo(krw(expectedFee));
            assertThat(quote.freeShippingApplied()).isEqualTo(expectedFree);
            assertThat(quote.baseFee()).isEqualTo(krw(3_000));
            assertThat(quote.remoteAreaSurcharge()).isEqualTo(krw(0));
            assertThat(quote.remoteAreaName()).isNull();
        }

        @Test
        @DisplayName("무료배송 기준이 없으면 주문 금액과 무관하게 기본 배송비다")
        void noThreshold() {
            ShippingFeeQuote quote = policy(null, List.of()).quote(krw(10_000_000), postalCode(MAINLAND));

            assertThat(quote.totalFee()).isEqualTo(krw(3_000));
            assertThat(quote.freeShippingApplied()).isFalse();
        }

        @Test
        @DisplayName("도서산간이면 기본 배송비에 추가 배송비를 더하고 구간 이름을 낸다")
        void remoteAreaSurcharge() {
            ShippingFeeQuote quote = policy(krw(100_000), List.of(jeju(), ulleung())).quote(krw(50_000), postalCode("63100"));

            assertThat(quote.totalFee()).isEqualTo(krw(6_000));
            assertThat(quote.remoteAreaSurcharge()).isEqualTo(krw(3_000));
            assertThat(quote.remoteAreaName()).isEqualTo("제주");
        }

        @Test
        @DisplayName("무료배송이어도 도서산간 추가 배송비는 받는다 — 배송비는 추가 배송비뿐이다")
        void freeShippingStillChargesSurcharge() {
            ShippingFeeQuote quote = policy(krw(100_000), List.of(jeju(), ulleung())).quote(krw(150_000), postalCode("40210"));

            assertThat(quote.freeShippingApplied()).isTrue();
            assertThat(quote.totalFee()).isEqualTo(krw(5_000));
            assertThat(quote.remoteAreaName()).isEqualTo("울릉");
        }

        @Test
        @DisplayName("정확히 기준 금액 + 구간 끝 우편번호 — 무료배송이고 배송비는 추가 배송비뿐이다")
        void exactThresholdAtRangeEnd() {
            ShippingFeeQuote quote = policy(krw(100_000), List.of(jeju(), ulleung())).quote(krw(100_000), postalCode("63644"));

            assertThat(quote.freeShippingApplied()).isTrue();
            assertThat(quote.remoteAreaName()).isEqualTo("제주");
            assertThat(quote.remoteAreaSurcharge()).isEqualTo(krw(3_000));
            assertThat(quote.totalFee()).isEqualTo(krw(3_000));
        }

        @Test
        @DisplayName("주문 시점 스냅숏용으로 정책의 무료배송 기준 · 버전을 함께 낸다")
        void carriesPolicySnapshot() {
            assertThat(policy(krw(100_000), List.of()).quote(krw(10_000), postalCode(MAINLAND)))
                .extracting(ShippingFeeQuote::freeShippingThreshold, ShippingFeeQuote::shippingPolicyVersion)
                .containsExactly(krw(100_000), VERSION);
            assertThat(policy(null, List.of()).quote(krw(10_000), postalCode(MAINLAND)).freeShippingThreshold()).isNull();
        }

        @Test
        @DisplayName("저장되지 않은 정책(버전 없음)으로는 견적하지 않는다 — 주문에 남길 버전이 없다")
        void unsavedPolicyCannotQuote() {
            ShippingPolicy unsaved = new ShippingPolicy(1L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null);

            assertThatThrownBy(() -> unsaved.quote(krw(10_000), postalCode(MAINLAND))).isInstanceOf(IllegalStateException.class);
        }

        @ParameterizedTest(name = "{0} → 도서산간 {1}")
        @CsvSource({"63000, true", "63644, true", "62999, false", "63645, false"})
        @DisplayName("구간은 양 끝(from · to)을 포함한다")
        void rangeBoundariesAreInclusive(String postalCode, boolean remote) {
            ShippingFeeQuote quote = policy(null, List.of(jeju())).quote(krw(10_000), postalCode(postalCode));

            assertThat(quote.remoteAreaName() != null).isEqualTo(remote);
            assertThat(quote.totalFee()).isEqualTo(krw(remote ? 6_000 : 3_000));
        }

        @Test
        @DisplayName("주문 금액 통화가 정책과 다르면 IllegalArgumentException — 호출자가 먼저 거절해야 한다")
        void orderAmountCurrencyMismatch() {
            assertThatThrownBy(() -> policy(krw(100_000), List.of()).quote(Money.of(10_000, CurrencyCode.USD), postalCode(MAINLAND)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test
        @DisplayName("구간은 시작 우편번호 오름차순으로 정렬해 들고 있는다")
        void sortsRemoteAreas() {
            assertThat(policy(null, List.of(jeju(), ulleung())).remoteAreas()).extracting(RemoteArea::name).containsExactly("울릉", "제주");
        }

        @Test
        @DisplayName("겹친 구간은 거부한다")
        void overlappingAreasAreRejected() {
            RemoteArea overlapping = remoteArea(2L, "제주 일부", "63600", "63700", krw(1_000));

            assertThatThrownBy(() -> policy(null, List.of(jeju(), overlapping)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제주")
                .hasMessageContaining("제주 일부");
        }

        @Test
        @DisplayName("맞닿은 구간은 겹치지 않는다")
        void adjacentAreasAreAllowed() {
            RemoteArea next = remoteArea(2L, "다음", "63645", "63700", krw(1_000));

            assertThat(policy(null, List.of(jeju(), next)).remoteAreas()).hasSize(2);
        }

        @Test
        @DisplayName("무료배송 기준 · 추가 배송비의 통화가 기본 배송비와 다르면 거부한다")
        void mixedCurrenciesAreRejected() {
            Money usd = Money.of(1_000, CurrencyCode.USD);

            assertThatThrownBy(() -> policy(usd, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> policy(null, List.of(remoteArea(2L, "제주", "63000", "63644", usd)))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("무료배송 기준 0원은 거부한다 — 없으면 null 이다")
        void zeroThresholdIsRejected() {
            assertThatThrownBy(() -> policy(krw(0), List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("findFirstOverlap")
    class FindFirstOverlap {

        @Test
        @DisplayName("입력 순서와 무관하게 시작 우편번호 순으로 앞 · 뒤 구간을 낸다")
        void returnsSortedPair() {
            RemoteArea later = remoteArea(2L, "뒤", "63500", "63700", krw(1_000));

            assertThat(ShippingPolicy.findFirstOverlap(List.of(later, jeju()))).hasValueSatisfying(overlap -> {
                assertThat(overlap.first().name()).isEqualTo("제주");
                assertThat(overlap.second().name()).isEqualTo("뒤");
            });
        }

        @Test
        @DisplayName("이웃하지 않은 구간이 겹쳐도 찾는다 — 큰 구간 안에 작은 구간 둘")
        void findsNestedOverlap() {
            RemoteArea wide = remoteArea(1L, "넓은", "10000", "19999", krw(1_000));
            RemoteArea first = remoteArea(2L, "안1", "11000", "11999", krw(1_000));
            RemoteArea second = remoteArea(3L, "안2", "15000", "15999", krw(1_000));

            assertThat(ShippingPolicy.findFirstOverlap(List.of(second, first, wide))).isPresent();
        }

        @Test
        @DisplayName("겹치지 않으면 빈 값이다")
        void emptyWhenDisjoint() {
            assertThat(ShippingPolicy.findFirstOverlap(List.of(jeju(), ulleung()))).isEmpty();
            assertThat(ShippingPolicy.findFirstOverlap(List.of())).isEmpty();
        }
    }
}
