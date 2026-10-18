package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.CUSTOMS_NOTICE;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.asia;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneRateAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InternationalShippingPolicyAdminPresenterTest {

    private final InternationalShippingPolicyAdminPresenter presenter = new InternationalShippingPolicyAdminPresenter();

    @Test
    @DisplayName("아이디는 문자열, 그룹은 표시 순서 · 국가는 코드 순 · 구간은 상한 순, 버전 · 수정 시각 · 안내 문구를 함께 내린다")
    void toResponse() {
        InternationalShippingPolicyAdminResponse response = presenter.toResponse(internationalPolicy());

        assertThat(response.shippingPolicyId()).isEqualTo("8001");
        assertThat(response.baseFee()).isEqualTo(MoneyItem.of(500, "USD"));
        assertThat(response.freeShippingThreshold()).isEqualTo(MoneyItem.of(20_000, "USD"));
        assertThat(response.customsNotice()).isEqualTo(CUSTOMS_NOTICE);
        assertThat(response.version()).isEqualTo(2L);
        assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(response.zones()).extracting(ShippingZoneAdminItem::name).containsExactly("아시아", "북미");
        assertThat(response.zones().getFirst()).isEqualTo(ShippingZoneAdminItem.builder()
            .shippingZoneId("8101")
            .name("아시아")
            .countryCodes(List.of("JP", "SG"))
            .rates(List.of(
                ShippingZoneRateAdminItem.builder().shippingZoneRateId("8121").maxWeightGrams(500).fee(MoneyItem.of(1_000, "USD")).build(),
                ShippingZoneRateAdminItem.builder().shippingZoneRateId("8122").maxWeightGrams(1_000).fee(MoneyItem.of(1_500, "USD")).build(),
                ShippingZoneRateAdminItem.builder().shippingZoneRateId("8123").maxWeightGrams(2_000).fee(MoneyItem.of(2_500, "USD")).build()
            ))
            .build());
    }

    @Test
    @DisplayName("저장되지 않은 정책(버전 없음)은 응답으로 만들지 않는다 — 저장 흐름의 오류다")
    void unsavedPolicyIsRejected() {
        InternationalShippingPolicy unsaved = new InternationalShippingPolicy(1L, usd(500), null, null, List.of(asia()), null, null);

        assertThatThrownBy(() -> presenter.toResponse(unsaved)).isInstanceOf(NullPointerException.class);
    }
}
