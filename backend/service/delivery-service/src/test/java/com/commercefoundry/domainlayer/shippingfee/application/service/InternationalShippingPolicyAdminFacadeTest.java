package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.InternationalShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.InternationalShippingPolicyProcessor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class InternationalShippingPolicyAdminFacadeTest {

    private static final InternationalShippingPolicyReplaceCommand COMMAND = new InternationalShippingPolicyReplaceCommand(usd(500), null, null, List.of(), 2L);

    @Mock
    private InternationalShippingPolicyProcessor internationalShippingPolicyProcessor;

    @Mock
    private InternationalShippingPolicyAdminPresenter internationalShippingPolicyAdminPresenter;

    private InternationalShippingPolicyAdminFacade facade;

    @BeforeEach
    void setUp() {
        facade = new InternationalShippingPolicyAdminFacade(internationalShippingPolicyProcessor, internationalShippingPolicyAdminPresenter);
    }

    @Test
    @DisplayName("조회: 정책이 없으면 null 이고 Presenter 를 부르지 않는다")
    void getReturnsNullWhenAbsent() {
        given(internationalShippingPolicyProcessor.findPolicy()).willReturn(Optional.empty());

        assertThat(facade.getInternationalPolicy()).isNull();
        verifyNoInteractions(internationalShippingPolicyAdminPresenter);
    }

    @Test
    @DisplayName("조회: 기능 플래그와 무관한 관리 조회(findPolicy)로 Presenter 응답을 낸다")
    void getPresentsPolicy() {
        InternationalShippingPolicyAdminResponse response = InternationalShippingPolicyAdminResponse.builder().shippingPolicyId("8001").version(2).build();
        given(internationalShippingPolicyProcessor.findPolicy()).willReturn(Optional.of(internationalPolicy()));
        given(internationalShippingPolicyAdminPresenter.toResponse(internationalPolicy())).willReturn(response);

        assertThat(facade.getInternationalPolicy()).isSameAs(response);
    }

    @Test
    @DisplayName("교체: Processor 가 저장한 정책을 Presenter 로 바꾸고, 도메인 오류는 Presenter 없이 그대로 올라간다")
    void replace() {
        InternationalShippingPolicyAdminResponse response = InternationalShippingPolicyAdminResponse.builder().shippingPolicyId("8001").version(3).build();
        given(internationalShippingPolicyProcessor.replacePolicy(COMMAND)).willReturn(internationalPolicy());
        given(internationalShippingPolicyAdminPresenter.toResponse(internationalPolicy())).willReturn(response);

        assertThat(facade.replaceInternationalPolicy(COMMAND)).isSameAs(response);
    }

    @Test
    @DisplayName("교체: 도메인 오류는 그대로 올라가고 Presenter 를 부르지 않는다")
    void replacePropagatesDomainError() {
        given(internationalShippingPolicyProcessor.replacePolicy(COMMAND)).willThrow(new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT));

        assertThatThrownBy(() -> facade.replaceInternationalPolicy(COMMAND)).isInstanceOf(DeliveryException.class);
        verifyNoInteractions(internationalShippingPolicyAdminPresenter);
    }

    /** 정책 행 갱신과 하위 행 삭제 · 삽입이 한 트랜잭션이어야 버전 충돌 때 아무것도 바뀌지 않는다. */
    @Test
    @DisplayName("교체는 쓰기 트랜잭션, 조회는 읽기 전용 · REPEATABLE READ 트랜잭션이다")
    void transactionBoundaries() throws NoSuchMethodException {
        Method replace = InternationalShippingPolicyAdminFacade.class.getMethod("replaceInternationalPolicy", InternationalShippingPolicyReplaceCommand.class);
        Method get = InternationalShippingPolicyAdminFacade.class.getMethod("getInternationalPolicy");

        assertThat(replace.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
        assertThat(get.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
        assertThat(get.getAnnotation(Transactional.class).isolation()).as("하위 행 아이디가 교체마다 바뀌어 네 테이블을 같은 스냅숏에서 읽는다").isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(replace.getAnnotation(Transactional.class).isolation()).as("쓰기는 기본 격리 수준 — 엇갈린 조회는 어댑터가 006 으로 바꾼다").isEqualTo(Isolation.DEFAULT);
    }
}
