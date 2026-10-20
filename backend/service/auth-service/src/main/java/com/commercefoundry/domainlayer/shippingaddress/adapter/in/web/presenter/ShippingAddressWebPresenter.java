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
 */
@Component
public class ShippingAddressWebPresenter {

    public ShippingAddressListResponse toListResponse(ShippingAddressListInfo info) {
        return ShippingAddressListResponse.builder()
            .addresses(info.addresses().stream().map(ShippingAddressWebPresenter::toItem).toList())
            .maxCount(info.maxCount())
            .build();
    }

    public ShippingAddressCreateResponse toCreateResponse(ShippingAddress address) {
        return ShippingAddressCreateResponse.builder()
            .addressId(String.valueOf(address.id()))
            .build();
    }

    public ShippingAddressResponse toResponse(ShippingAddress address) {
        ShippingAddressContent content = address.content();
        return ShippingAddressResponse.builder()
            .addressId(String.valueOf(address.id()))
            .label(content.label())
            .recipientName(content.recipientName())
            .recipientPhone(content.recipientPhone())
            .countryCode(content.countryCode())
            .postalCode(content.postalCode())
            .addressLine1(content.addressLine1())
            .addressLine2(content.addressLine2())
            .city(content.city())
            .stateProvince(content.stateProvince())
            .isDefault(address.defaultAddress())
            .build();
    }

    private static ShippingAddressItem toItem(ShippingAddress address) {
        ShippingAddressContent content = address.content();
        return ShippingAddressItem.builder()
            .addressId(String.valueOf(address.id()))
            .label(content.label())
            .recipientName(content.recipientName())
            .recipientPhone(content.recipientPhone())
            .countryCode(content.countryCode())
            .postalCode(content.postalCode())
            .addressLine1(content.addressLine1())
            .addressLine2(content.addressLine2())
            .city(content.city())
            .stateProvince(content.stateProvince())
            .isDefault(address.defaultAddress())
            .build();
    }
}
