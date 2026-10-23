package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.POLICY_ID;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.UPDATED_AT;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.postalCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class ShippingPolicyProcessorTest {

    private static final RemoteAreaCommand JEJU = new RemoteAreaCommand("제주", postalCode("63000"), postalCode("63644"), krw(3_000));
    private static final RemoteAreaCommand ULLEUNG = new RemoteAreaCommand("울릉", postalCode("40200"), postalCode("40240"), krw(5_000));
    private static final long ADMIN_MEMBER_ID = 4611686018427387904L;

    @Mock
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    @Mock
    private ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private ShippingPolicyProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ShippingPolicyProcessor(shippingPolicyRepositoryPort, shippingPolicyHistoryRepositoryPort, snowflakeIdGenerator);
    }

    private static ShippingPolicyReplaceCommand command(Long version, List<RemoteAreaCommand> remoteAreas) {
        return new ShippingPolicyReplaceCommand(krw(3_000), krw(50_000), remoteAreas, version, ADMIN_MEMBER_ID);
    }

    /** 어댑터가 돌려주는 저장 결과 — 새 정책은 버전 0, 교체는 버전 + 1 이고 수정 시각이 채워진다. */
    private static ShippingPolicy saved(ShippingPolicy policy) {
        long version = policy.version() == null ? 0L : policy.version() + 1;
        return new ShippingPolicy(
            policy.id(), policy.scope(), policy.baseFee(), policy.freeShippingThreshold(), policy.remoteAreas(), version, UPDATED_AT
        );
    }

    private ShippingPolicyHistory appendedHistory() {
        ArgumentCaptor<ShippingPolicyHistory> history = ArgumentCaptor.forClass(ShippingPolicyHistory.class);
        verify(shippingPolicyHistoryRepositoryPort, times(1)).insert(history.capture());
        return history.getValue();
    }

    private static void assertErrorCode(Throwable thrown, DeliveryErrorCode errorCode) {
        assertThat(thrown).isInstanceOfSatisfying(DeliveryException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    @Test
    @DisplayName("정책이 없고 version 이 null 이면 새 정책 · 구간 아이디를 발급해 만든다 — 저장은 한 번")
    void createsWhenAbsent() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9101L, 9102L, 9001L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        processor.replaceDomesticPolicy(command(null, List.of(JEJU, ULLEUNG)));

        ArgumentCaptor<ShippingPolicy> saved = ArgumentCaptor.forClass(ShippingPolicy.class);
        verify(shippingPolicyRepositoryPort, times(1)).replace(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(9001L);
        assertThat(saved.getValue().version()).isNull();
        assertThat(saved.getValue().scope()).isEqualTo(ShippingScope.DOMESTIC);
        assertThat(saved.getValue().baseFee()).isEqualTo(krw(3_000));
        assertThat(saved.getValue().freeShippingThreshold()).isEqualTo(krw(50_000));
        assertThat(saved.getValue().remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(
            tuple(9102L, "울릉"),
            tuple(9101L, "제주")
        );
    }

    @Test
    @DisplayName("정책이 있으면 아이디를 유지하고 조회한 version 으로 교체한다 — 구간 아이디는 새로 발급한다")
    void replacesExistingWithSameVersion() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(snowflakeIdGenerator.generateId()).willReturn(9201L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        ShippingPolicy result = processor.replaceDomesticPolicy(command(3L, List.of(JEJU)));

        assertThat(result.id()).isEqualTo(POLICY_ID);
        assertThat(result.version()).as("저장이 올린 버전").isEqualTo(4L);
        verify(shippingPolicyRepositoryPort).replace(argThat(policy -> Long.valueOf(3L).equals(policy.version())));
        assertThat(result.remoteAreas()).extracting(RemoteArea::id).containsExactly(9201L);
        verify(shippingPolicyRepositoryPort, times(1)).replace(any());
    }

    @Test
    @DisplayName("무료배송 기준이 없고 구간이 없는 정책도 된다")
    void withoutThresholdAndAreas() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9001L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        ShippingPolicy result = processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(0), null, List.of(), null, ADMIN_MEMBER_ID));

        assertThat(result.freeShippingThreshold()).isNull();
        assertThat(result.remoteAreas()).isEmpty();
    }

    @Test
    @DisplayName("정책이 있는데 version 이 다르면 DELIVERY_006 이고 저장하지 않는다 — 이력도 남기지 않는다")
    void staleVersion() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(2L, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
        verifyNoInteractions(shippingPolicyHistoryRepositoryPort);
    }

    @Test
    @DisplayName("정책이 있는데 version 을 비우면(처음 만들기로 착각) DELIVERY_006")
    void missingVersionForExisting() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(null, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("정책이 없는데 version 을 보내면(그 사이 사라짐) DELIVERY_006")
    void versionForAbsent() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(command(0L, List.of()))), DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("확인 뒤 저장에서 동시 교체에 지면(낙관적 락 실패) DELIVERY_006 으로 바꾼다 — 진 쪽은 이력을 남기지 않는다")
    void concurrentReplace() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(shippingPolicyRepositoryPort.replace(any())).willThrow(new ObjectOptimisticLockingFailureException(Object.class, POLICY_ID));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of())));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        assertThat(thrown).hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        verifyNoInteractions(shippingPolicyHistoryRepositoryPort);
    }

    @Test
    @DisplayName("이력: 처음 만든 교체는 교체 전이 null 이고 교체 후는 저장된 버전 0 · 값이다 — 구간은 이력 행 아이디를 새로 받는다")
    void createAppendsHistoryWithoutBefore() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9101L, 9102L, 9001L, 9301L, 9302L, 9303L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        processor.replaceDomesticPolicy(command(null, List.of(JEJU, ULLEUNG)));

        ShippingPolicyHistory history = appendedHistory();
        assertThat(history.id()).isEqualTo(9301L);
        assertThat(history.shippingPolicyId()).isEqualTo(9001L);
        assertThat(history.scope()).isEqualTo(ShippingScope.DOMESTIC);
        assertThat(history.adminMemberId()).isEqualTo(ADMIN_MEMBER_ID);
        assertThat(history.changedAt()).as("저장 때 감사 리스너가 채운다").isNull();
        assertThat(history.before()).isNull();
        assertThat(history.after().version()).isZero();
        assertThat(history.after().baseFee()).isEqualTo(krw(3_000));
        assertThat(history.after().freeShippingThreshold()).isEqualTo(krw(50_000));
        assertThat(history.after().remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(tuple(9302L, "울릉"), tuple(9303L, "제주"));
    }

    @Test
    @DisplayName("이력: 교체는 조회한 정책을 교체 전, 저장된 정책을 교체 후로 남긴다 — 버전은 3 → 4")
    void replaceAppendsBeforeAndAfter() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(snowflakeIdGenerator.generateId()).willReturn(9201L, 9301L, 9302L, 9303L, 9304L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        processor.replaceDomesticPolicy(command(3L, List.of(JEJU)));

        ShippingPolicyHistory history = appendedHistory();
        assertThat(history.id()).isEqualTo(9301L);
        assertThat(history.shippingPolicyId()).isEqualTo(POLICY_ID);
        assertThat(history.adminMemberId()).isEqualTo(ADMIN_MEMBER_ID);
        assertThat(history.before().version()).isEqualTo(3L);
        assertThat(history.before().baseFee()).isEqualTo(krw(3_000));
        assertThat(history.before().freeShippingThreshold()).isEqualTo(krw(100_000));
        assertThat(history.before().remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(tuple(9302L, "울릉"), tuple(9303L, "제주"));
        assertThat(history.after().version()).isEqualTo(4L);
        assertThat(history.after().freeShippingThreshold()).isEqualTo(krw(50_000));
        assertThat(history.after().remoteAreas()).extracting(RemoteArea::id, RemoteArea::name).containsExactly(tuple(9304L, "제주"));
    }

    @Test
    @DisplayName("이력: 값이 그대로인 교체(같은 본문 재전송)도 남긴다 — 교체 전 · 후 값이 같고 버전만 오른다")
    void sameValuesStillAppend() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(snowflakeIdGenerator.generateId()).willReturn(9201L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(3_000), krw(100_000), List.of(JEJU, ULLEUNG), 3L, ADMIN_MEMBER_ID));

        ShippingPolicyHistory history = appendedHistory();
        assertThat(history.after().baseFee()).isEqualTo(history.before().baseFee());
        assertThat(history.after().freeShippingThreshold()).isEqualTo(history.before().freeShippingThreshold());
        assertThat(history.after().remoteAreas()).extracting(RemoteArea::name, RemoteArea::postalCodeFrom, RemoteArea::surcharge)
            .containsExactlyElementsOf(history.before().remoteAreas().stream().map(area -> tuple(area.name(), area.postalCodeFrom(), area.surcharge())).toList());
        assertThat(history.before().version()).isEqualTo(3L);
        assertThat(history.after().version()).isEqualTo(4L);
    }

    @Test
    @DisplayName("이력 저장이 실패하면 예외를 그대로 올린다 — 도메인 오류로 바꾸지 않아 Facade 트랜잭션이 정책 교체까지 롤백한다")
    void historyFailurePropagates() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("history insert failed");
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        given(snowflakeIdGenerator.generateId()).willReturn(9201L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));
        willThrow(failure).given(shippingPolicyHistoryRepositoryPort).insert(any());

        assertThat(catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of(JEJU))))).isSameAs(failure);
        verify(shippingPolicyRepositoryPort, times(1)).replace(any());
    }

    @Test
    @DisplayName("이력 조회는 국내 이력 포트의 페이지를 그대로 낸다")
    void findsDomesticHistories() {
        Page<ShippingPolicyHistory> page = new PageImpl<>(List.of(), PageRequest.of(2, 30), 0);
        given(shippingPolicyHistoryRepositoryPort.findDomesticPage(2, 30)).willReturn(page);

        assertThat(processor.findDomesticHistories(2, 30)).isSameAs(page);
    }

    @Test
    @DisplayName("구간이 겹치면 DELIVERY_005 이고 두 구간 이름을 시작 우편번호 순으로 알린다 — 정책을 읽지 않는다")
    void overlappingAreas() {
        RemoteAreaCommand inside = new RemoteAreaCommand("제주 일부", postalCode("63600"), postalCode("63700"), krw(1_000));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of(inside, JEJU))));

        assertErrorCode(thrown, DeliveryErrorCode.REMOTE_AREA_RANGE_OVERLAP);
        assertThat(thrown).hasMessage("도서산간 구간이 겹칩니다: 제주, 제주 일부");
        verify(shippingPolicyRepositoryPort, never()).findDomesticPolicy();
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("구간 시작이 끝보다 크면 DELIVERY_004 이고 구간 이름을 알린다 — 아이디를 발급하지 않는다")
    void reversedRange() {
        RemoteAreaCommand reversed = new RemoteAreaCommand("거꾸로", postalCode("63644"), postalCode("63000"), krw(1_000));

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(command(3L, List.of(reversed))));

        assertErrorCode(thrown, DeliveryErrorCode.REMOTE_AREA_RANGE_INVALID);
        assertThat(thrown).hasMessage("도서산간 구간의 시작 우편번호가 끝 우편번호보다 큽니다: 거꾸로");
        verifyNoInteractions(shippingPolicyRepositoryPort, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("무료배송 기준 · 추가 배송비 통화가 기본 배송비와 다르면 DELIVERY_003")
    void mixedCurrencies() {
        Money usd = Money.of(1_000, CurrencyCode.USD);
        RemoteAreaCommand usdArea = new RemoteAreaCommand("제주", postalCode("63000"), postalCode("63644"), usd);

        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(3_000), usd, List.of(), 3L, ADMIN_MEMBER_ID))),
            DeliveryErrorCode.CURRENCY_MISMATCH);
        assertErrorCode(catchThrowable(() -> processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(krw(3_000), null, List.of(usdArea), 3L, ADMIN_MEMBER_ID))),
            DeliveryErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(shippingPolicyRepositoryPort);
    }

    @Test
    @DisplayName("조회는 국내 범위 정책이다")
    void findsDomesticPolicy() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));

        assertThat(processor.findDomesticPolicy()).hasValue(domesticPolicy());
    }

    /** #70 — 어드민 실수 한 번에 KRW 주문 견적이 모두 003 으로 막히지 않게. */
    @Test
    @DisplayName("저장된 KRW 정책을 USD 로 바꾸면 DELIVERY_012 이고 저장하지 않는다 — 통화가 섞이지 않은 요청이어도")
    void currencyChangeIsRejected() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.of(domesticPolicy()));
        Money usd = Money.of(300, CurrencyCode.USD);

        Throwable thrown = catchThrowable(() -> processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(usd, null, List.of(), 3L, ADMIN_MEMBER_ID)));

        assertErrorCode(thrown, DeliveryErrorCode.SHIPPING_POLICY_CURRENCY_CHANGE_NOT_ALLOWED);
        assertThat(thrown).hasMessage("배송비 정책의 통화는 바꿀 수 없습니다. 현재 통화: KRW");
        verify(shippingPolicyRepositoryPort, never()).replace(any());
    }

    @Test
    @DisplayName("처음 만들 때는 어떤 통화든 받는다 — KRW 로 고정하지 않는다")
    void anyCurrencyOnCreate() {
        given(shippingPolicyRepositoryPort.findDomesticPolicy()).willReturn(Optional.empty());
        given(snowflakeIdGenerator.generateId()).willReturn(9001L);
        given(shippingPolicyRepositoryPort.replace(any())).willAnswer(invocation -> saved(invocation.getArgument(0)));

        ShippingPolicy result = processor.replaceDomesticPolicy(new ShippingPolicyReplaceCommand(Money.of(300, CurrencyCode.USD), null, List.of(), null, ADMIN_MEMBER_ID));

        assertThat(result.currency()).isEqualTo(CurrencyCode.USD);
    }
}
