package com.commercefoundry.domainlayer.shippingaddress.application.service;

import com.commercefoundry.domainlayer.member.application.service.processor.MemberLockProcessor;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter.ShippingAddressWebPresenter;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.port.in.ShippingAddressWebUseCase;
import com.commercefoundry.domainlayer.shippingaddress.application.service.processor.ShippingAddressProcessor;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 배송지 주소록. 외부 I/O 가 없어 트랜잭션을 여기에 건다.
 *
 * <p>생성 · 기본 지정 · 삭제는 <b>회원 단위로 줄을 세운다</b> — 같은 트랜잭션 맨 앞에서 회원 행을 잠근다({@link MemberLockProcessor}, member 컨텍스트의
 * Processor 라 Facade 만 부른다 — {@code architecture-guide.md} §3). 개수 상한 검사(센 뒤 넣기)와 기본 교체(풀고 지정하기)는 회원의 주소 전체에 걸친
 * 읽고-쓰기라, 잠금이 없으면 동시 요청이 함께 상한을 넘기거나 기본을 둘 만들려 한다(뒤의 것은 유니크 키가 막지만 사용자에게는 409 가 된다).
 * 전체 교체(update)는 개수 · 기본 여부를 바꾸지 않아 잠그지 않고 주소 행의 낙관적 락만 쓴다.
 *
 * <p><b>잠금 대기 상한</b> — 잠그는 메서드는 트랜잭션 시간 상한({@value #LOCKING_TIMEOUT_SECONDS}초)을 건다. JPA 트랜잭션 관리자가 남은 시간을
 * 쿼리 타임아웃으로 넣어 잠금을 기다리는 {@code select ... for update} 도 그 안에 끊긴다(드라이버가 문장을 취소 — Spring {@link QueryTimeoutException}).
 * 상한이 이미 지난 뒤의 쿼리는 {@link TransactionTimedOutException} 이다. 둘 다 같은 회원의 다른 쓰기와 겹친 것이라 409 · AUTH_023 으로 바꾼다.
 *
 * <p>회원이 없으면 잠금에서 {@code MemberException}(404 · AUTH_003)이 그대로 올라간다 — 같은 서비스 · 같은 {@code AUTH_} 코드 공간이라 배송지 예외로
 * 번역하지 않는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존).
 */
@Service
@RequiredArgsConstructor
public class ShippingAddressWebFacade implements ShippingAddressWebUseCase {

    /** 회원 잠금을 거는 트랜잭션의 시간 상한(초). 잠금은 짧은 쓰기 몇 문장만 쥐므로 정상 경로는 이 값에 한참 못 미친다. */
    static final int LOCKING_TIMEOUT_SECONDS = 5;

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
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public ShippingAddressCreateResponse create(long memberId, ShippingAddressCommand command, boolean requestedDefault) {
        ShippingAddress created = inMemberQueue(memberId, () -> shippingAddressProcessor.create(memberId, command, requestedDefault));
        return shippingAddressWebPresenter.toCreateResponse(created);
    }

    @Override
    @Transactional
    public ShippingAddressResponse update(long memberId, long addressId, ShippingAddressCommand command) {
        return shippingAddressWebPresenter.toResponse(shippingAddressProcessor.update(memberId, addressId, command));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void delete(long memberId, long addressId) {
        inMemberQueue(memberId, () -> {
            shippingAddressProcessor.delete(memberId, addressId);
            return null;
        });
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void setDefault(long memberId, long addressId) {
        inMemberQueue(memberId, () -> {
            shippingAddressProcessor.setDefault(memberId, addressId);
            return null;
        });
    }

    /**
     * 회원 행을 잠근 뒤 작업을 한다. 잠금 대기 · 작업 중 시간 상한을 넘기면 409 · AUTH_023 이다 — 트랜잭션은 예외로 롤백된다.
     * {@link PessimisticLockingFailureException}(잠금 획득 실패 · 교착 상태 희생)도 같은 뜻이라 같이 바꾼다.
     */
    private <T> T inMemberQueue(long memberId, Supplier<T> work) {
        try {
            memberLockProcessor.lock(memberId);
            return work.get();
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_BUSY, exception);
        }
    }
}
