package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.application.info.ShippingAddressListInfo;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import org.springframework.stereotype.Component;

/**
 * 고객 배송지 응답. 식별자 {@code long → String} 변환만 한다. 단건은 Processor 가 돌려준 도메인을 가공 없이 옮겨 Info 를 두지 않는다
 * ({@code architecture-guide.md} §3 Presenter).
 *
 * <p>목록 항목({@link ShippingAddressItem})과 단건 응답({@link ShippingAddressResponse})은 칸이 같다 — 진입 DTO 는 나눠 두고 옮기는 코드는
 * {@link #map} 하나로 묶는다(두 record 의 캐노니컬 생성자를 받는다).
 */
@Component
public class ShippingAddressWebPresenter {

    public ShippingAddressListResponse toListResponse(ShippingAddressListInfo info) {
        return ShippingAddressListResponse.builder()
            .addresses(info.addresses().stream().map(address -> map(address, ShippingAddressItem::new)).toList())
            .maxCount(info.maxCount())
            .build();
    }

    public ShippingAddressCreateResponse toCreateResponse(ShippingAddress address) {
        return ShippingAddressCreateResponse.builder()
            .addressId(String.valueOf(address.id()))
            .build();
    }

    public ShippingAddressResponse toResponse(ShippingAddress address) {
        return map(address, ShippingAddressResponse::new);
    }

    private static <T> T map(ShippingAddress address, AddressFactory<T> factory) {
        ShippingAddressContent content = address.content();
        return factory.create(
            String.valueOf(address.id()), content.label(), content.recipientName(), content.recipientPhone(), content.countryCode(), content.postalCode(),
            content.addressLine1(), content.addressLine2(), content.city(), content.stateProvince(), address.defaultAddress()
        );
    }

    /** 응답 칸 순서 그대로 — {@link ShippingAddressItem} · {@link ShippingAddressResponse} 의 캐노니컬 생성자. */
    @FunctionalInterface
    private interface AddressFactory<T> {

        T create(
            String addressId, String label, String recipientName, String recipientPhone, String countryCode, String postalCode,
            String addressLine1, String addressLine2, String city, String stateProvince, boolean isDefault
        );
    }
}
