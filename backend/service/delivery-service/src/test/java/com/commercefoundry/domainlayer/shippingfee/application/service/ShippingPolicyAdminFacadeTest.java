package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.ShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.persistence.dto.PageResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ShippingPolicyAdminFacadeTest {

    private static final ShippingPolicyReplaceCommand COMMAND = new ShippingPolicyReplaceCommand(krw(3_000), null, List.of(), 3L, 4611686018427387904L);

    @Mock
    private ShippingPolicyProcessor shippingPolicyProcessor;

    @Mock
    private ShippingPolicyAdminPresenter shippingPolicyAdminPresenter;

    private ShippingPolicyAdminFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShippingPolicyAdminFacade(shippingPolicyProcessor, shippingPolicyAdminPresenter);
    }

    @Test
    @DisplayName("조회: 정책이 없으면 null 이고 Presenter 를 부르지 않는다")
    void getReturnsNullWhenAbsent() {
        given(shippingPolicyProcessor.findDomesticPolicy()).willReturn(Optional.empty());

        assertThat(facade.getDomesticPolicy()).isNull();
        verifyNoInteractions(shippingPolicyAdminPresenter);
    }

    @Test
    @DisplayName("조회: 정책이 있으면 Presenter 응답을 그대로 낸다")
    void getPresentsPolicy() {
        ShippingPolicyAdminResponse response = ShippingPolicyAdminResponse.builder().shippingPolicyId("7001").version(3).build();
        given(shippingPolicyProcessor.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(shippingPolicyAdminPresenter.toResponse(domesticPolicy())).willReturn(response);

        assertThat(facade.getDomesticPolicy()).isSameAs(response);
    }

    @Test
    @DisplayName("교체: Processor 가 저장한 정책을 Presenter 로 바꾼다")
    void replacePresentsSavedPolicy() {
        ShippingPolicyAdminResponse response = ShippingPolicyAdminResponse.builder().shippingPolicyId("7001").version(4).build();
        given(shippingPolicyProcessor.replaceDomesticPolicy(COMMAND)).willReturn(domesticPolicy());
        given(shippingPolicyAdminPresenter.toResponse(domesticPolicy())).willReturn(response);

        assertThat(facade.replaceDomesticPolicy(COMMAND)).isSameAs(response);
    }

    @Test
    @DisplayName("교체: 도메인 오류는 그대로 올라가고 Presenter 를 부르지 않는다")
    void replacePropagatesDomainError() {
        given(shippingPolicyProcessor.replaceDomesticPolicy(COMMAND)).willThrow(new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT));

        assertThatThrownBy(() -> facade.replaceDomesticPolicy(COMMAND)).isInstanceOf(DeliveryException.class);
        verifyNoInteractions(shippingPolicyAdminPresenter);
    }

    @Test
    @DisplayName("이력: Processor 의 페이지를 Presenter 로 바꾼다")
    void historiesPresentsPage() {
        Page<ShippingPolicyHistory> histories = new PageImpl<>(List.of(), PageRequest.of(1, 20), 0);
        PageResponse<ShippingPolicyHistoryAdminItem> response = new PageResponse<>(List.of(), 1, 20, 0L, 0);
        given(shippingPolicyProcessor.findDomesticHistories(1, 20)).willReturn(histories);
        given(shippingPolicyAdminPresenter.toHistoryPageResponse(histories)).willReturn(response);

        assertThat(facade.getDomesticPolicyHistories(1, 20)).isSameAs(response);
    }

    /** 정책 행 갱신 · 구간 삭제 · 삽입 · 이력 append 가 한 트랜잭션이어야 버전 충돌 · 이력 저장 실패 때 아무것도 바뀌지 않는다. */
    @Test
    @DisplayName("교체는 쓰기 트랜잭션, 조회 · 이력 조회는 읽기 전용 트랜잭션이다")
    void transactionBoundaries() throws NoSuchMethodException {
        Method replace = ShippingPolicyAdminFacade.class.getMethod("replaceDomesticPolicy", ShippingPolicyReplaceCommand.class);
        Method get = ShippingPolicyAdminFacade.class.getMethod("getDomesticPolicy");
        Method histories = ShippingPolicyAdminFacade.class.getMethod("getDomesticPolicyHistories", int.class, int.class);

        assertThat(replace.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
        assertThat(get.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
        assertThat(histories.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
    }
}
