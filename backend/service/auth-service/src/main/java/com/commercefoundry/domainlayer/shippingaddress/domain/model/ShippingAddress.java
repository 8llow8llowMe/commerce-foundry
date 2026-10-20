package com.commercefoundry.domainlayer.shippingaddress.domain.model;

import java.util.Objects;

/**
 * 회원 배송지 주소록의 한 줄. 회원과는 아이디({@link #memberId})로만 이어진다 — member 컨텍스트 domain 을 필드로 갖지 않는다.
 *
 * <p>불변식 "주소가 있으면 기본 배송지는 정확히 하나" 는 이 객체 하나로 지킬 수 없다(회원의 주소 전체에 걸친다).
 * Facade 가 회원 행을 잠근 트랜잭션 안에서 Processor 가 지키고, DB 의 {@code uk_shipping_address_default_member_id} 가 "최대 하나" 를 강제한다.
 *
 * <p>상태는 메서드가 새 값을 돌려주는 방식으로만 바뀐다. 기본 여부는 이 객체로 바꾸지 않는다 — 다른 주소의 기본을 함께 풀어야 해서
 * 저장소의 조건부 UPDATE(기존 기본 해제 → 새 기본 지정)로만 바꾼다.
 *
 * @param defaultAddress 기본 배송지인가 ({@code default_member_id} 가 null 이 아닌가)
 * @param version 낙관적 락 버전. 아직 저장하지 않은 주소는 null
 */
public record ShippingAddress(long id, long memberId, ShippingAddressContent content, boolean defaultAddress, Long version) {

    public ShippingAddress {
        Objects.requireNonNull(content, "content");
    }

    /** 새 주소. {@code defaultAddress} 는 호출하는 쪽(Processor)이 회원의 다른 주소를 보고 정한다. */
    public static ShippingAddress create(long id, long memberId, ShippingAddressContent content, boolean defaultAddress) {
        return new ShippingAddress(id, memberId, content, defaultAddress, null);
    }

    /** 저장된 행을 되살린다 — 영속성 매퍼만 쓴다. */
    public static ShippingAddress restore(long id, long memberId, ShippingAddressContent content, boolean defaultAddress, Long version) {
        return new ShippingAddress(id, memberId, content, defaultAddress, version);
    }

    /** 내용을 통째로 바꾼 주소. 기본 여부 · 버전은 그대로다 — 저장할 때 읽은 버전으로 비교한다. */
    public ShippingAddress replaceContent(ShippingAddressContent newContent) {
        return new ShippingAddress(id, memberId, newContent, defaultAddress, version);
    }

    /** 개인정보를 싣지 않는다 — {@link ShippingAddressContent#toString()} 도 국가 코드만 낸다. */
    @Override
    public String toString() {
        return "ShippingAddress[id=" + id + ", memberId=" + memberId + ", content=" + content + ", defaultAddress=" + defaultAddress
            + ", version=" + version + "]";
    }
}
