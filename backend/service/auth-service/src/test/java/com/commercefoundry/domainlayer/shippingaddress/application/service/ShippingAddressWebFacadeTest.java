package com.commercefoundry.domainlayer.shippingaddress.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLockProcessor;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.presenter.ShippingAddressWebPresenter;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.info.ShippingAddressListInfo;
import com.commercefoundry.domainlayer.shippingaddress.application.service.processor.ShippingAddressProcessor;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

@ExtendWith(MockitoExtension.class)
class ShippingAddressWebFacadeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ADDRESS_ID = 4611686018427387904L;
    private static final ShippingAddressCommand COMMAND =
        new ShippingAddressCommand(null, "홍길동", "01012345678", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null);

    @Mock
    private ShippingAddressProcessor shippingAddressProcessor;

    @Mock
    private MemberLockProcessor memberLockProcessor;

    private ShippingAddressWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShippingAddressWebFacade(shippingAddressProcessor, memberLockProcessor, new ShippingAddressWebPresenter());
    }

    @Test
    @DisplayName("생성은 회원 행을 먼저 잠그고 만든다 — 응답 아이디는 문자열이다")
    void createLocksMemberFirst() {
        given(shippingAddressProcessor.create(MEMBER_ID, COMMAND, true))
            .willReturn(ShippingAddress.create(ADDRESS_ID, MEMBER_ID, COMMAND.toContent(), true));

        assertThat(facade.create(MEMBER_ID, COMMAND, true).addressId()).isEqualTo("4611686018427387904");

        InOrder order = inOrder(memberLockProcessor, shippingAddressProcessor);
        order.verify(memberLockProcessor).lock(MEMBER_ID);
        order.verify(shippingAddressProcessor).create(MEMBER_ID, COMMAND, true);
    }

    @Test
    @DisplayName("기본 지정 · 삭제도 회원 행을 먼저 잠근다")
    void setDefaultAndDeleteLockMemberFirst() {
        facade.setDefault(MEMBER_ID, ADDRESS_ID);
        facade.delete(MEMBER_ID, ADDRESS_ID);

        InOrder order = inOrder(memberLockProcessor, shippingAddressProcessor);
        order.verify(memberLockProcessor).lock(MEMBER_ID);
        order.verify(shippingAddressProcessor).setDefault(MEMBER_ID, ADDRESS_ID);
        order.verify(memberLockProcessor).lock(MEMBER_ID);
        order.verify(shippingAddressProcessor).delete(MEMBER_ID, ADDRESS_ID);
    }

    @Test
    @DisplayName("잠금 대기가 시간 상한을 넘기면(쿼리 타임아웃) 409 · AUTH_023 이고 배송지를 건드리지 않는다")
    void lockWaitTimeout() {
        willThrow(new QueryTimeoutException("canceled")).given(memberLockProcessor).lock(MEMBER_ID);

        assertThatThrownBy(() -> facade.setDefault(MEMBER_ID, ADDRESS_ID))
            .isInstanceOf(ShippingAddressException.class)
            .hasCauseInstanceOf(QueryTimeoutException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_BUSY);
        verifyNoInteractions(shippingAddressProcessor);
    }

    @Test
    @DisplayName("잠근 뒤 작업 중 시간 상한이 지나도(TransactionTimedOutException) AUTH_023 이다")
    void transactionTimedOutAfterLock() {
        willThrow(new TransactionTimedOutException("deadline")).given(shippingAddressProcessor).delete(MEMBER_ID, ADDRESS_ID);

        assertThatThrownBy(() -> facade.delete(MEMBER_ID, ADDRESS_ID))
            .isInstanceOf(ShippingAddressException.class)
            .extracting(exception -> ((ShippingAddressException) exception).getErrorCode())
            .isEqualTo(ShippingAddressErrorCode.SHIPPING_ADDRESS_BUSY);
    }

    @Test
    @DisplayName("회원이 없으면 잠금에서 AUTH_003 이 나고 배송지를 건드리지 않는다")
    void unknownMember() {
        willThrow(new MemberException(MemberErrorCode.NOT_FOUND_MEMBER)).given(memberLockProcessor).lock(MEMBER_ID);

        assertThatThrownBy(() -> facade.create(MEMBER_ID, COMMAND, false)).isInstanceOf(MemberException.class);
        verifyNoInteractions(shippingAddressProcessor);
    }

    @Test
    @DisplayName("조회 · 전체 교체는 잠그지 않는다")
    void readAndUpdateDoNotLock() {
        ShippingAddress address = ShippingAddress.restore(ADDRESS_ID, MEMBER_ID, COMMAND.toContent(), true, 0L);
        given(shippingAddressProcessor.getAddresses(MEMBER_ID)).willReturn(new ShippingAddressListInfo(List.of(address), 20));
        given(shippingAddressProcessor.getAddress(MEMBER_ID, ADDRESS_ID)).willReturn(address);
        given(shippingAddressProcessor.update(MEMBER_ID, ADDRESS_ID, COMMAND)).willReturn(address);

        ShippingAddressListResponse list = facade.getMyAddresses(MEMBER_ID);
        facade.getMyAddress(MEMBER_ID, ADDRESS_ID);
        facade.update(MEMBER_ID, ADDRESS_ID, COMMAND);

        assertThat(list.addresses()).singleElement().satisfies(item -> assertThat(item.addressId()).isEqualTo("4611686018427387904"));
        assertThat(list.maxCount()).isEqualTo(20);
        verifyNoInteractions(memberLockProcessor);
    }
}
