package com.commercefoundry.domainlayer.shippingaddress.application.info;

import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import java.util.List;

/**
 * 내 배송지 목록.
 *
 * @param addresses 기본 배송지가 먼저, 그다음 최신순
 * @param maxCount 회원당 상한 ({@code auth.shipping-address.max-per-member})
 */
public record ShippingAddressListInfo(List<ShippingAddress> addresses, int maxCount) {

    public ShippingAddressListInfo {
        addresses = List.copyOf(addresses);
    }

    /** 개인정보를 싣지 않는다 — 개수만. */
    @Override
    public String toString() {
        return "ShippingAddressListInfo[size=" + addresses.size() + ", maxCount=" + maxCount + "]";
    }
}
