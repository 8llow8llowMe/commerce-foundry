package com.commercefoundry.domainlayer.shippingaddress.application.service.processor;

import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.info.ShippingAddressListInfo;
import com.commercefoundry.domainlayer.shippingaddress.application.port.out.ShippingAddressRepositoryPort;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.global.properties.ShippingAddressProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회원 배송지 주소록. 주소는 언제나 (주소 아이디, JWT 의 회원 아이디) 로만 찾는다 — 없거나 남의 것이면 같은 404(AUTH_020)다.
 *
 * <p>불변식 "주소가 있으면 기본 배송지는 정확히 하나" 를 여기서 지킨다 — 첫 주소는 기본이 되고, 기본을 지우면 가장 최근 주소가 기본이 된다.
 * 개수 상한 검사와 기본 교체는 회원 하나에 걸친 읽고-쓰기라 동시 요청이 함께 지나갈 수 있다. 그래서 생성 · 기본 지정 · 삭제는
 * Facade 가 같은 트랜잭션 맨 앞에서 회원 행을 잠근 뒤에 부른다({@code MemberLockProcessor}). 이 클래스는 트랜잭션을 걸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ShippingAddressProcessor {

    private final ShippingAddressRepositoryPort shippingAddressRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ShippingAddressProperties shippingAddressProperties;

    /** 기본 배송지가 먼저, 그다음 최신순. 상한이 있어 페이지 없이 전체다. */
    public ShippingAddressListInfo getAddresses(long memberId) {
        return new ShippingAddressListInfo(shippingAddressRepositoryPort.findAllByMemberId(memberId), shippingAddressProperties.maxPerMember());
    }

    /** @throws ShippingAddressException {@link ShippingAddressErrorCode#NOT_FOUND_SHIPPING_ADDRESS} — 없거나 남의 주소 */
    public ShippingAddress getAddress(long memberId, long addressId) {
        return shippingAddressRepositoryPort.findByIdAndMemberId(addressId, memberId)
            .orElseThrow(() -> new ShippingAddressException(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS));
    }

    /**
     * 새 주소를 넣는다. 회원의 첫 주소는 {@code requestedDefault} 와 무관하게 기본이 된다. 회원 행을 잠근 트랜잭션에서 부른다.
     *
     * <p>기본으로 넣을 때는 기존 기본을 <b>먼저</b> 푼다 — PostgreSQL 은 유니크 검사를 문장 단위로 하므로 새 행을 기본으로 넣은 뒤에
     * 기존 기본을 풀면 그 INSERT 가 {@code uk_shipping_address_default_member_id} 위반이다.
     *
     * @throws ShippingAddressException {@link ShippingAddressErrorCode#SHIPPING_ADDRESS_LIMIT_EXCEEDED} — 상한에 닿았다
     */
    public ShippingAddress create(long memberId, ShippingAddressCommand command, boolean requestedDefault) {
        int maxPerMember = shippingAddressProperties.maxPerMember();
        long count = shippingAddressRepositoryPort.countByMemberId(memberId);
        if (count >= maxPerMember) {
            throw new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_LIMIT_EXCEEDED, maxPerMember);
        }

        boolean makeDefault = count == 0 || requestedDefault;
        if (makeDefault && count > 0) {
            shippingAddressRepositoryPort.clearDefault(memberId);
        }
        return shippingAddressRepositoryPort.save(ShippingAddress.create(snowflakeIdGenerator.generateId(), memberId, command.toContent(), makeDefault));
    }

    /**
     * 내용을 통째로 바꾼다. 기본 여부는 바꾸지 않는다. 읽은 버전으로 저장하므로 그 사이 다른 요청이 고쳤거나 지웠으면 409(AUTH_022)다.
     * 기본 여부 · 개수가 바뀌지 않아 회원 잠금이 필요 없다 — 기본 교체는 대상 행의 버전을 올려 이 저장이 옛 기본 여부를 되돌려 쓰지 않게 한다.
     *
     * @throws ShippingAddressException {@link ShippingAddressErrorCode#NOT_FOUND_SHIPPING_ADDRESS},
     *     {@link ShippingAddressErrorCode#SHIPPING_ADDRESS_UPDATE_CONFLICT}
     */
    public ShippingAddress update(long memberId, long addressId, ShippingAddressCommand command) {
        ShippingAddress address = getAddress(memberId, addressId);
        return shippingAddressRepositoryPort.save(address.replaceContent(command.toContent()));
    }

    /**
     * 그 주소를 기본 배송지로 지정한다. 이미 기본이면 아무것도 쓰지 않는다(멱등). 회원 행을 잠근 트랜잭션에서 부른다.
     * 기존 기본 해제 UPDATE 가 새 기본 지정 UPDATE 보다 먼저 나간다 — 문장 단위 유니크 검사라 순서가 바뀌면 위반이다.
     *
     * @throws ShippingAddressException {@link ShippingAddressErrorCode#NOT_FOUND_SHIPPING_ADDRESS}
     */
    public void setDefault(long memberId, long addressId) {
        ShippingAddress address = getAddress(memberId, addressId);
        if (address.defaultAddress()) {
            return;
        }
        shippingAddressRepositoryPort.clearDefault(memberId);
        shippingAddressRepositoryPort.markDefault(addressId, memberId);
    }

    /**
     * 주소를 지운다. 기본 배송지를 지웠고 남은 주소가 있으면 가장 최근(아이디 최대) 주소를 기본으로 올린다. 회원 행을 잠근 트랜잭션에서 부른다.
     *
     * @throws ShippingAddressException {@link ShippingAddressErrorCode#NOT_FOUND_SHIPPING_ADDRESS}
     */
    public void delete(long memberId, long addressId) {
        ShippingAddress address = getAddress(memberId, addressId);
        if (!shippingAddressRepositoryPort.deleteByIdAndMemberId(addressId, memberId)) {
            throw new ShippingAddressException(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS);
        }
        if (address.defaultAddress()) {
            shippingAddressRepositoryPort.findLatestByMemberId(memberId)
                .ifPresent(latest -> shippingAddressRepositoryPort.markDefault(latest.id(), memberId));
        }
    }
}
