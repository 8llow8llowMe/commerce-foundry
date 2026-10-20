package com.commercefoundry.domainlayer.shippingaddress.application.service;

import com.commercefoundry.domainlayer.member.application.service.processor.MemberLockProcessor;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter.ShippingAddressWebPresenter;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.port.in.ShippingAddressWebUseCase;
import com.commercefoundry.domainlayer.shippingaddress.application.service.processor.ShippingAddressProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 배송지 주소록. 외부 I/O 가 없어 트랜잭션을 여기에 건다.
 *
 * <p>생성 · 기본 지정 · 삭제는 <b>회원 단위로 줄을 세운다</b> — 같은 트랜잭션 맨 앞에서 회원 행을 잠근다({@link MemberLockProcessor}, member 컨텍스트의
 * Processor 라 Facade 만 부른다 — {@code architecture-guide.md} §3). 개수 상한 검사(센 뒤 넣기)와 기본 교체(풀고 지정하기)는 회원의 주소 전체에 걸친
 * 읽고-쓰기라, 잠금이 없으면 동시 요청이 함께 상한을 넘기거나 기본을 둘 만들려 한다(뒤의 것은 유니크 키가 막지만 사용자에게는 409 가 된다).
 * 전체 교체(update)는 개수 · 기본 여부를 바꾸지 않아 잠그지 않고 주소 행의 낙관적 락만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ShippingAddressWebFacade implements ShippingAddressWebUseCase {

    private final ShippingAddressProcessor shippingAddressProcessor;
    private final MemberLockProcessor memberLockProcessor;
    private final ShippingAddressWebPresenter shippingAddressWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public ShippingAddressListResponse getMyAddresses(long memberId) {
        return shippingAddressWebPresenter.toListResponse(shippingAddressProcessor.getAddresses(memberId));
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingAddressResponse getMyAddress(long memberId, long addressId) {
        return shippingAddressWebPresenter.toResponse(shippingAddressProcessor.getAddress(memberId, addressId));
    }

    @Override
    @Transactional
    public ShippingAddressCreateResponse create(long memberId, ShippingAddressCommand command, boolean requestedDefault) {
        memberLockProcessor.lock(memberId);
        return shippingAddressWebPresenter.toCreateResponse(shippingAddressProcessor.create(memberId, command, requestedDefault));
    }

    @Override
    @Transactional
    public ShippingAddressResponse update(long memberId, long addressId, ShippingAddressCommand command) {
        return shippingAddressWebPresenter.toResponse(shippingAddressProcessor.update(memberId, addressId, command));
    }

    @Override
    @Transactional
    public void delete(long memberId, long addressId) {
        memberLockProcessor.lock(memberId);
        shippingAddressProcessor.delete(memberId, addressId);
    }

    @Override
    @Transactional
    public void setDefault(long memberId, long addressId) {
        memberLockProcessor.lock(memberId);
        shippingAddressProcessor.setDefault(memberId, addressId);
    }
}
