package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.presenter.ShippingFeeWebPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.InternationalShippingPolicyProcessor;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
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
class ShippingFeeWebFacadeTest {

    @Mock
    private ShippingPolicyProcessor shippingPolicyProcessor;

    @Mock
    private InternationalShippingPolicyProcessor internationalShippingPolicyProcessor;

    @Mock
    private ShippingFeeWebPresenter shippingFeeWebPresenter;

    private ShippingFeeWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShippingFeeWebFacade(shippingPolicyProcessor, internationalShippingPolicyProcessor, shippingFeeWebPresenter);
    }

    @Test
    @DisplayName("해외 정책 안내는 고객용 조회(findOfferedPolicy — 플래그가 꺼지면 빈 값)를 쓴다")
    void internationalUsesOfferedPolicy() {
        InternationalShippingPolicyResponse response = InternationalShippingPolicyResponse.builder().build();
        given(internationalShippingPolicyProcessor.findOfferedPolicy()).willReturn(Optional.of(internationalPolicy()));
        given(shippingFeeWebPresenter.toResponse(internationalPolicy())).willReturn(response);

        assertThat(facade.getInternationalPolicy()).isSameAs(response);
    }

    @Test
    @DisplayName("해외 배송이 꺼졌거나 정책이 없으면 null 이고 Presenter 를 부르지 않는다")
    void internationalNullWhenNotOffered() {
        given(internationalShippingPolicyProcessor.findOfferedPolicy()).willReturn(Optional.empty());

        assertThat(facade.getInternationalPolicy()).isNull();
        verifyNoInteractions(shippingFeeWebPresenter, shippingPolicyProcessor);
    }

    /** 해외는 교체마다 하위 행 아이디가 바뀌어 네 테이블을 같은 스냅숏에서 읽어야 한다. 국내 안내는 기본 격리 수준이다. */
    @Test
    @DisplayName("해외 정책 안내는 읽기 전용 REPEATABLE READ, 국내 안내는 읽기 전용 기본 격리 수준이다")
    void transactionIsolation() throws NoSuchMethodException {
        Transactional international = ShippingFeeWebFacade.class.getMethod("getInternationalPolicy").getAnnotation(Transactional.class);
        Transactional domestic = ShippingFeeWebFacade.class.getMethod("getDomesticPolicy").getAnnotation(Transactional.class);

        assertThat(international.readOnly()).isTrue();
        assertThat(international.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(domestic.readOnly()).isTrue();
        assertThat(domestic.isolation()).isEqualTo(Isolation.DEFAULT);
    }
}
