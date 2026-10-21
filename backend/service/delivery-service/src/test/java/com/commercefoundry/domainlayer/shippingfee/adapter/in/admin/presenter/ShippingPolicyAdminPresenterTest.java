package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.RemoteAreaAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
