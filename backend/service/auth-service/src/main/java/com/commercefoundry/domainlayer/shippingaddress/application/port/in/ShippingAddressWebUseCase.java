package com.commercefoundry.domainlayer.shippingaddress.application.port.in;

import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;

/**
 * 내 배송지 주소록. 회원 아이디는 언제나 JWT 의 것이다. 없거나 남의 주소는 {@code AUTH_020}(404)으로 같게 답한다.
 */
public interface ShippingAddressWebUseCase {

    ShippingAddressListResponse getMyAddresses(long memberId);

    ShippingAddressResponse getMyAddress(long memberId, long addressId);

    /** 상한에 닿았으면 {@code AUTH_021}(409), 회원이 없으면 {@code AUTH_003}(404) 다. */
    ShippingAddressCreateResponse create(long memberId, ShippingAddressCommand command, boolean requestedDefault);

    /** 전체 교체. 기본 여부는 바꾸지 않는다. 다른 요청이 먼저 고쳤으면 {@code AUTH_022}(409) 다. */
    ShippingAddressResponse update(long memberId, long addressId, ShippingAddressCommand command);

    /** 기본 배송지를 지우면 가장 최근 주소가 기본이 된다. */
    void delete(long memberId, long addressId);

    /** 멱등 — 이미 기본이어도 성공이다. */
    void setDefault(long memberId, long addressId);
}
