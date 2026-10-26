package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.jeju;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.RemoteAreaAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class ShippingPolicyAdminPresenterTest {

    private final ShippingPolicyAdminPresenter presenter = new ShippingPolicyAdminPresenter();

    @Test
    @DisplayName("아이디는 문자열, 버전 · 수정 시각을 함께 내린다")
    void toResponse() {
        ShippingPolicyAdminResponse response = presenter.toResponse(domesticPolicy());

        assertThat(response.shippingPolicyId()).isEqualTo("7001");
        assertThat(response.baseFee()).isEqualTo(MoneyItem.of(3_000, "KRW"));
        assertThat(response.freeShippingThreshold()).isEqualTo(MoneyItem.of(100_000, "KRW"));
        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(response.remoteAreas()).containsExactly(
            RemoteAreaAdminItem.builder().remoteAreaId("7102").name("울릉").postalCodeFrom("40200").postalCodeTo("40240")
                .surcharge(MoneyItem.of(5_000, "KRW")).build(),
            RemoteAreaAdminItem.builder().remoteAreaId("7101").name("제주").postalCodeFrom("63000").postalCodeTo("63644")
                .surcharge(MoneyItem.of(3_000, "KRW")).build()
        );
    }

    @Test
    @DisplayName("저장되지 않은 정책(버전 없음)은 응답으로 만들지 않는다 — 저장 흐름의 오류다")
    void unsavedPolicyIsRejected() {
        ShippingPolicy unsaved = new ShippingPolicy(1L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null);

        assertThatThrownBy(() -> presenter.toResponse(unsaved)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("이력: 아이디 · 어드민 아이디는 문자열, 금액은 MoneyItem, 처음 만든 교체의 before 는 null, 구간 아이디는 싣지 않는다 — 페이지 정보는 그대로")
    void toHistoryPageResponse() {
        Snapshot after = new Snapshot(krw(3_000), null, List.of(jeju()), 0L);
        ShippingPolicyHistory history = new ShippingPolicyHistory(9301L, POLICY_ID, ShippingScope.DOMESTIC, 4611686018427387904L, null, after, UPDATED_AT);

        PageResponse<ShippingPolicyHistoryAdminItem> response = presenter.toHistoryPageResponse(
            new PageImpl<>(List.of(history), PageRequest.of(0, 1), 3), Map.of(4611686018427387904L, "홍*동"));

        assertThat(response.totalElements()).isEqualTo(3L);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.contents()).containsExactly(ShippingPolicyHistoryAdminItem.builder()
            .historyId("9301")
            .adminMemberId("4611686018427387904")
            .adminDisplayName("홍*동")
            .changedAt(UPDATED_AT)
            .before(null)
            .after(ShippingPolicyHistoryAdminItem.SnapshotItem.builder()
                .baseFee(MoneyItem.of(3_000, "KRW"))
                .freeShippingThreshold(null)
                .remoteAreas(List.of(ShippingPolicyHistoryAdminItem.RemoteAreaSnapshotItem.builder()
                    .name("제주").postalCodeFrom("63000").postalCodeTo("63644").surcharge(MoneyItem.of(3_000, "KRW")).build()))
                .version(0L)
                .build())
            .build());
    }

    @Test
    @DisplayName("이력: 교체 전 값도 같은 모양으로 내린다")
    void historyWithBefore() {
        Snapshot before = new Snapshot(krw(3_000), krw(100_000), List.of(), 3L);
        Snapshot after = new Snapshot(krw(3_500), null, List.of(), 4L);
        ShippingPolicyHistory history = new ShippingPolicyHistory(9302L, POLICY_ID, ShippingScope.DOMESTIC, 1L, before, after, UPDATED_AT);

        ShippingPolicyHistoryAdminItem item = presenter.toHistoryPageResponse(new PageImpl<>(List.of(history)), Map.of()).contents().getFirst();

        assertThat(item.adminDisplayName()).isNull();
        assertThat(item.before().version()).isEqualTo(3L);
        assertThat(item.before().freeShippingThreshold()).isEqualTo(MoneyItem.of(100_000, "KRW"));
        assertThat(item.after().baseFee()).isEqualTo(MoneyItem.of(3_500, "KRW"));
        assertThat(item.after().freeShippingThreshold()).isNull();
    }
}
