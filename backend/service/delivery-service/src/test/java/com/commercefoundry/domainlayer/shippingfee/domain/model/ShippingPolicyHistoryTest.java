package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.ulleung;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingPolicyHistoryTest {

    private static final long ADMIN_MEMBER_ID = 4611686018427387904L;
    private static final Snapshot BEFORE = new Snapshot(krw(3_000), krw(100_000), List.of(), 3L);
    private static final Snapshot AFTER = new Snapshot(krw(3_500), null, List.of(), 4L);

    private static ShippingPolicyHistory history(ShippingScope scope, long adminMemberId, Snapshot before, Snapshot after) {
        return new ShippingPolicyHistory(9301L, POLICY_ID, scope, adminMemberId, before, after, null);
    }

    @Test
    @DisplayName("교체 전이 없는(처음 만든) 이력 · 교체 전 · 후가 있는 이력을 만든다")
    void createsValidHistories() {
        assertThat(history(ShippingScope.DOMESTIC, ADMIN_MEMBER_ID, null, new Snapshot(krw(3_000), null, List.of(), 0L)).before()).isNull();
        assertThat(history(ShippingScope.DOMESTIC, ADMIN_MEMBER_ID, BEFORE, AFTER).after()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("스냅숏의 구간은 시작 우편번호 오름차순으로 정렬한다")
    void sortsRemoteAreas() {
        assertThat(new Snapshot(krw(3_000), null, List.of(jeju(), ulleung()), 0L).remoteAreas()).containsExactly(ulleung(), jeju());
    }

    @Test
    @DisplayName("해외 범위는 아직 담지 않는다")
    void rejectsInternationalScope() {
        assertThatThrownBy(() -> history(ShippingScope.INTERNATIONAL, ADMIN_MEMBER_ID, null, AFTER)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {3L, 2L})
    @DisplayName("교체 후 버전이 교체 전보다 크지 않으면 거절한다")
    void rejectsNonIncreasingVersion(long afterVersion) {
        Snapshot after = new Snapshot(krw(3_500), null, List.of(), afterVersion);

        assertThatThrownBy(() -> history(ShippingScope.DOMESTIC, ADMIN_MEMBER_ID, BEFORE, after)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("교체 전 · 후 정책 통화가 다르면 거절한다 — 정책 통화는 고정이다")
    void rejectsCurrencyChange() {
        Snapshot after = new Snapshot(usd(300), null, List.of(), 4L);

        assertThatThrownBy(() -> history(ShippingScope.DOMESTIC, ADMIN_MEMBER_ID, BEFORE, after))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("통화");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("교체한 어드민 회원 아이디가 양수가 아니면 거절한다")
    void rejectsNonPositiveAdminMemberId(long adminMemberId) {
        assertThatThrownBy(() -> history(ShippingScope.DOMESTIC, adminMemberId, BEFORE, AFTER)).isInstanceOf(IllegalArgumentException.class);
    }
}
