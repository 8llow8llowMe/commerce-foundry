package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.remoteArea;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RemoteAreaTest {

    private static RemoteArea area(String from, String to) {
        return remoteArea(1L, "구간", from, to, krw(3_000));
    }

    @ParameterizedTest(name = "[63000~63644] 와 [{0}~{1}] → {2}")
    @CsvSource({
        "63645, 63700, false",
        "62000, 62999, false",
        "63644, 63700, true",
        "62000, 63000, true",
        "63000, 63644, true",
        "63100, 63200, true",
        "62000, 64000, true"
    })
    @DisplayName("겹침 — 맞닿으면 false, 끝 하나라도 같거나 포함하면 true")
    void overlaps(String from, String to, boolean expected) {
        RemoteArea jeju = area("63000", "63644");
        RemoteArea other = area(from, to);

        assertThat(jeju.overlaps(other)).isEqualTo(expected);
        assertThat(other.overlaps(jeju)).as("대칭").isEqualTo(expected);
    }

    @Test
    @DisplayName("시작과 끝이 같은 한 칸 구간도 된다 — 같은 한 칸끼리는 겹친다")
    void singlePostalCode() {
        assertThat(area("63100", "63100").contains(postalCode("63100"))).isTrue();
        assertThat(area("63100", "63100").overlaps(area("63100", "63100"))).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"63000, true", "63644, true", "63300, true", "62999, false", "63645, false"})
    @DisplayName("포함 — 양 끝을 포함한다")
    void contains(String postalCode, boolean expected) {
        assertThat(area("63000", "63644").contains(postalCode(postalCode))).isEqualTo(expected);
    }

    @Test
    @DisplayName("시작이 끝보다 크면 거부한다")
    void reversedRangeIsRejected() {
        assertThatThrownBy(() -> area("63644", "63000")).isInstanceOf(IllegalArgumentException.class);
    }
}
