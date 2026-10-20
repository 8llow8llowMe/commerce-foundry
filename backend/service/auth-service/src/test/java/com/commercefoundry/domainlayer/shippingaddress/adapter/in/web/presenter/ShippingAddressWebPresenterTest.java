package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.application.info.ShippingAddressListInfo;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShippingAddressWebPresenterTest {

    private static final ShippingAddressContent DOMESTIC =
        new ShippingAddressContent("집", "홍길동", "01012345678", "KR", "06236", "서울특별시 강남구 테헤란로 123", "4층", null, null);
    private static final ShippingAddressContent INTERNATIONAL =
        new ShippingAddressContent(null, "John Doe", "+14155550100", "US", null, "1 Market St", null, "San Francisco", "CA");

    private final ShippingAddressWebPresenter presenter = new ShippingAddressWebPresenter();

    @Test
    @DisplayName("생성 응답의 아이디는 문자열이다 — 19자리 Snowflake 가 JS Number 에서 반올림되지 않게")
    void createIdIsString() {
        assertThat(presenter.toCreateResponse(ShippingAddress.create(4611686018427387904L, 1L, DOMESTIC, true)).addressId())
            .isEqualTo("4611686018427387904");
    }

    @Test
    @DisplayName("단건은 아이디만 문자열로 바꾸고 칸을 그대로 옮긴다 — 기본 여부는 isDefault")
    void single() {
        ShippingAddressResponse response = presenter.toResponse(ShippingAddress.restore(4611686018427387905L, 1L, INTERNATIONAL, false, 2L));

        assertThat(response).isEqualTo(ShippingAddressResponse.builder()
            .addressId("4611686018427387905")
            .label(null)
            .recipientName("John Doe")
            .recipientPhone("+14155550100")
            .countryCode("US")
            .postalCode(null)
            .addressLine1("1 Market St")
            .addressLine2(null)
            .city("San Francisco")
            .stateProvince("CA")
            .isDefault(false)
            .build());
    }

    @Test
    @DisplayName("목록은 순서를 그대로 두고 상한을 함께 낸다")
    void list() {
        ShippingAddressListResponse response = presenter.toListResponse(new ShippingAddressListInfo(List.of(
            ShippingAddress.restore(2L, 1L, DOMESTIC, true, 0L),
            ShippingAddress.restore(3L, 1L, INTERNATIONAL, false, 0L)
        ), 20));

        assertThat(response.maxCount()).isEqualTo(20);
        assertThat(response.addresses()).extracting(ShippingAddressItem::addressId, ShippingAddressItem::isDefault, ShippingAddressItem::addressLine2)
            .containsExactly(tuple("2", true, "4층"), tuple("3", false, null));
    }

    @Test
    @DisplayName("빈 목록은 빈 배열이다")
    void emptyList() {
        assertThat(presenter.toListResponse(new ShippingAddressListInfo(List.of(), 20)).addresses()).isEmpty();
    }
}
