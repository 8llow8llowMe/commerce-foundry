package com.commercefoundry.domainlayer.shippingaddress.application.port.out;

import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import java.util.List;
import java.util.Optional;

/**
 * 회원 배송지. 조회는 언제나 회원 아이디를 같이 받는다 — 주소 아이디만으로 찾는 메서드를 두지 않는다 (소유 확인, {@code api-design-guide.md} §5).
 *
 * <p>기본 배송지 바꾸기는 {@link #clearDefault} → {@link #markDefault} 순서로 같은 트랜잭션에서 부른다.
 * 쓰기 메서드는 트랜잭션 안에서 부르고, 회원 단위 직렬화(회원 행 잠금)는 Facade 가 먼저 건다.
 */
public interface ShippingAddressRepositoryPort {

    /** 기본 배송지가 먼저, 그다음 최신순(아이디 내림차순)이다. */
    List<ShippingAddress> findAllByMemberId(long memberId);

    Optional<ShippingAddress> findByIdAndMemberId(long addressId, long memberId);

    /** 가장 최근(아이디 최대) 주소. 기본 배송지를 지운 뒤 승격할 주소를 고른다. */
    Optional<ShippingAddress> findLatestByMemberId(long memberId);

    long countByMemberId(long memberId);

    /**
     * 새 주소(버전 null)면 넣고, 아니면 그 버전에 대한 수정으로 저장한다. 바로 flush 해 제약 위반 · 버전 충돌을 여기서 드러낸다.
     *
     * @throws ShippingAddressException {@code SHIPPING_ADDRESS_UPDATE_CONFLICT} — 다른 요청이 먼저 고쳤거나 지웠다, 또는 기본 배송지 유니크 키 위반
     */
    ShippingAddress save(ShippingAddress address);

    /**
     * 회원의 기본 배송지를 푼다 (없으면 아무것도 하지 않는다). 조건부 UPDATE 한 문장이고 바로 나간다 — 새 기본을 지정하기 <b>전에</b> 부른다.
     * 바뀐 행의 버전을 올린다 — 그 행을 옛 버전으로 읽은 전체 교체가 기본 여부를 되돌려 쓰지 않게.
     */
    void clearDefault(long memberId);

    /**
     * 그 회원의 그 주소를 기본으로 지정한다. 조건부 UPDATE 한 문장이고 버전을 올린다.
     *
     * @throws ShippingAddressException {@code NOT_FOUND_SHIPPING_ADDRESS} — 바뀐 행이 없다(지워졌다).
     *     {@code SHIPPING_ADDRESS_UPDATE_CONFLICT} — 기본 배송지 유니크 키 위반(기존 기본을 풀지 않았다)
     */
    void markDefault(long addressId, long memberId);

    /** @return 지웠으면 true, 그 회원의 주소가 아니거나 없으면 false */
    boolean deleteByIdAndMemberId(long addressId, long memberId);
}
