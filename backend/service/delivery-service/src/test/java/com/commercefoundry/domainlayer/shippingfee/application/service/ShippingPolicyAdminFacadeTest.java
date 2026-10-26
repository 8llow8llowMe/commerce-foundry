package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.ShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.MemberDisplayNameQueryProcessor;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private MemberDisplayNameQueryProcessor memberDisplayNameQueryProcessor;

    @Mock
    private ShippingPolicyAdminPresenter shippingPolicyAdminPresenter;

    private ShippingPolicyAdminFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShippingPolicyAdminFacade(shippingPolicyProcessor, memberDisplayNameQueryProcessor, shippingPolicyAdminPresenter);
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
    @DisplayName("이력: 항목이 여럿이어도 표시 이름 조회는 한 번이고 Presenter 에 그 맵을 넘긴다")
    void historiesLooksUpNamesOnce() {
        Snapshot after = new Snapshot(krw(3_000), null, List.of(), 0L);
        Instant changedAt = Instant.parse("2026-10-18T03:00:00Z");
        ShippingPolicyHistory first = new ShippingPolicyHistory(1L, 7001L, ShippingScope.DOMESTIC, 11L, null, after, changedAt);
        ShippingPolicyHistory second = new ShippingPolicyHistory(2L, 7001L, ShippingScope.DOMESTIC, 22L, null, after, changedAt);
        Page<ShippingPolicyHistory> histories = new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2);
        Map<Long, String> names = Map.of(11L, "홍*동");
        PageResponse<ShippingPolicyHistoryAdminItem> response = new PageResponse<>(List.of(), 0, 20, 2L, 1);
        given(shippingPolicyProcessor.findDomesticHistories(0, 20)).willReturn(histories);
        given(memberDisplayNameQueryProcessor.findMaskedNames(histories)).willReturn(names);
        given(shippingPolicyAdminPresenter.toHistoryPageResponse(histories, names)).willReturn(response);

        assertThat(facade.getDomesticPolicyHistories(0, 20)).isSameAs(response);
        verify(memberDisplayNameQueryProcessor, times(1)).findMaskedNames(histories);
    }

    /** 정책 행 갱신 · 구간 삭제 · 삽입 · 이력 append 가 한 트랜잭션이어야 버전 충돌 · 이력 저장 실패 때 아무것도 바뀌지 않는다. */
    @Test
    @DisplayName("교체 · 정책 조회는 Facade 트랜잭션이고, 이력 조회는 Feign 이 섞여 Facade 에 트랜잭션이 없다")
    void transactionBoundaries() throws NoSuchMethodException {
        Method replace = ShippingPolicyAdminFacade.class.getMethod("replaceDomesticPolicy", ShippingPolicyReplaceCommand.class);
        Method get = ShippingPolicyAdminFacade.class.getMethod("getDomesticPolicy");
        Method histories = ShippingPolicyAdminFacade.class.getMethod("getDomesticPolicyHistories", int.class, int.class);
        Method findHistories = ShippingPolicyProcessor.class.getMethod("findDomesticHistories", int.class, int.class);

        assertThat(replace.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(false);
        assertThat(get.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
        assertThat(histories.getAnnotation(Transactional.class)).isNull();
        assertThat(findHistories.getAnnotation(Transactional.class)).isNotNull().extracting(Transactional::readOnly).isEqualTo(true);
    }
}
