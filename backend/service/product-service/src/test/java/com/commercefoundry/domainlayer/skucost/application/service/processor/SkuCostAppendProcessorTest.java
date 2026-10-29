package com.commercefoundry.domainlayer.skucost.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 원가 추가 판정 — 최신 행을 읽어 미래 한도 · 통화 · 소급 · 단조를 보고, 어기면 넣지 않고 도메인 코드로 거절한다. 시작을 빼면 서버 시각이다.
 */
@ExtendWith(MockitoExtension.class)
class SkuCostAppendProcessorTest {

    private static final long SKU_ID = 100L;
    private static final long ADMIN_ID = 7L;
    /** 나노초가 있는 시계 — 저장 정밀도(마이크로초)로 잘리는지 본다. */
    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00.123456789Z");

    @Mock
    private SkuCostRepositoryPort skuCostRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private SkuCostAppendProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkuCostAppendProcessor(skuCostRepositoryPort, snowflakeIdGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("시작을 빼면 지금(마이크로초로 자름)으로 넣고, 저장한 행을 돌려준다")
    void defaultsToNow() {
        when(snowflakeIdGenerator.generateId()).thenReturn(501L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.empty());
        when(skuCostRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SkuCost saved = processor.append(command(SkuCostType.GOODS, Money.of(12_000, CurrencyCode.KRW), null));

        assertThat(saved.id()).isEqualTo(501L);
        assertThat(saved.effectiveFrom()).isEqualTo(Instant.parse("2026-10-28T09:00:00.123456Z"));
        assertThat(saved.reason()).isEqualTo("단가 인상");
        assertThat(saved.adminMemberId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("첫 행은 과거 시작(소급)을 받는다 — 지정한 시작도 마이크로초로 자른다")
    void firstRowAcceptsBackdated() {
        when(snowflakeIdGenerator.generateId()).thenReturn(502L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.empty());
        when(skuCostRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        processor.append(command(SkuCostType.GOODS, Money.of(0, CurrencyCode.KRW), Instant.parse("2026-10-01T00:00:00.000000999Z")));

        ArgumentCaptor<SkuCost> inserted = ArgumentCaptor.forClass(SkuCost.class);
        verify(skuCostRepositoryPort).insert(inserted.capture());
        assertThat(inserted.getValue().effectiveFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(inserted.getValue().amount()).isEqualTo(Money.of(0, CurrencyCode.KRW));
    }

    @Test
    @DisplayName("행이 있으면 최신 행보다 뒤여도 지금보다 앞인 시작은 409 · SKU_COST_006 이고 넣지 않는다 — 메시지에 금액이 없다")
    void rejectsRetroactiveAfterFirstRow() {
        when(snowflakeIdGenerator.generateId()).thenReturn(506L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.of(latest("2026-09-01T00:00:00Z", CurrencyCode.KRW)));

        assertThatThrownBy(() -> processor.append(command(SkuCostType.GOODS, Money.of(12_345, CurrencyCode.KRW), Instant.parse("2026-10-01T00:00:00Z"))))
            .isInstanceOf(SkuCostException.class)
            .satisfies(exception -> {
                assertThat(((SkuCostException) exception).getErrorCode()).isEqualTo(SkuCostErrorCode.RETROACTIVE_NOT_ALLOWED);
                assertThat(exception.getMessage()).doesNotContain("12345").doesNotContain("10000");
            });
        verify(skuCostRepositoryPort, never()).insert(any());
    }

    @Test
    @DisplayName("행이 있어도 시작이 지금과 같으면 받는다 — 시작 · 지금을 모두 마이크로초로 잘라 비교한다")
    void acceptsExactlyNowAfterFirstRow() {
        when(snowflakeIdGenerator.generateId()).thenReturn(507L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.of(latest("2026-09-01T00:00:00Z", CurrencyCode.KRW)));
        when(skuCostRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 나노초 단위로는 지금보다 앞이지만 마이크로초까지 같다 — 같은 시각으로 본다
        SkuCost saved = processor.append(command(SkuCostType.GOODS, Money.of(12_000, CurrencyCode.KRW), Instant.parse("2026-10-28T09:00:00.123456000Z")));

        assertThat(saved.effectiveFrom()).isEqualTo(Instant.parse("2026-10-28T09:00:00.123456Z"));
    }

    @Test
    @DisplayName("행이 있어도 시작을 빼면 지금이라 소급에 걸리지 않는다")
    void omittedStartPassesAfterFirstRow() {
        when(snowflakeIdGenerator.generateId()).thenReturn(508L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.of(latest("2026-09-01T00:00:00Z", CurrencyCode.KRW)));
        when(skuCostRepositoryPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SkuCost saved = processor.append(command(SkuCostType.GOODS, Money.of(12_000, CurrencyCode.KRW), null));

        assertThat(saved.effectiveFrom()).isEqualTo(Instant.parse("2026-10-28T09:00:00.123456Z"));
    }

    @Test
    @DisplayName("지금 이후라도 최신 행(미래 오차 안)보다 뒤가 아니면 409 · SKU_COST_002 이고 넣지 않는다 — 메시지에 최신 시작이 있고 금액은 없다")
    void rejectsNotAfterLatest() {
        when(snowflakeIdGenerator.generateId()).thenReturn(503L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.of(latest("2026-10-28T09:02:00Z", CurrencyCode.KRW)));

        assertThatThrownBy(() -> processor.append(command(SkuCostType.GOODS, Money.of(12_345, CurrencyCode.KRW), Instant.parse("2026-10-28T09:01:00Z"))))
            .isInstanceOf(SkuCostException.class)
            .satisfies(exception -> {
                assertThat(((SkuCostException) exception).getErrorCode()).isEqualTo(SkuCostErrorCode.EFFECTIVE_FROM_NOT_AFTER_LATEST);
                assertThat(exception.getMessage()).contains("2026-10-28T09:02:00Z").doesNotContain("12345").doesNotContain("10000");
            });
        verify(skuCostRepositoryPort, never()).insert(any());
    }

    @Test
    @DisplayName("기존 행과 통화가 다르면 409 · SKU_COST_003 이고 메시지에 현재 통화가 있다")
    void rejectsCurrencyMismatch() {
        when(snowflakeIdGenerator.generateId()).thenReturn(504L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.PACKAGING)).thenReturn(Optional.of(latest("2026-09-01T00:00:00Z", CurrencyCode.KRW)));

        assertThatThrownBy(() -> processor.append(command(SkuCostType.PACKAGING, Money.of(90, CurrencyCode.USD), null)))
            .isInstanceOf(SkuCostException.class)
            .satisfies(exception -> {
                assertThat(((SkuCostException) exception).getErrorCode()).isEqualTo(SkuCostErrorCode.CURRENCY_MISMATCH);
                assertThat(exception.getMessage()).contains("KRW");
            });
        verify(skuCostRepositoryPort, never()).insert(any());
    }

    @Test
    @DisplayName("지금 + 5분보다 뒤면 400 · SKU_COST_001 다 — 첫 행이어도 거절한다")
    void rejectsFuture() {
        when(snowflakeIdGenerator.generateId()).thenReturn(505L);
        when(skuCostRepositoryPort.findLatest(SKU_ID, SkuCostType.GOODS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.append(command(SkuCostType.GOODS, Money.of(1, CurrencyCode.KRW), NOW.plusSeconds(301))))
            .isInstanceOf(SkuCostException.class)
            .extracting(exception -> ((SkuCostException) exception).getErrorCode())
            .isEqualTo(SkuCostErrorCode.EFFECTIVE_FROM_IN_FUTURE);
        verify(skuCostRepositoryPort, never()).insert(any());
    }

    private static SkuCostCreateCommand command(SkuCostType type, Money amount, Instant effectiveFrom) {
        return SkuCostCreateCommand.builder()
            .skuId(SKU_ID)
            .costType(type)
            .amount(amount)
            .effectiveFrom(effectiveFrom)
            .reason("단가 인상")
            .adminMemberId(ADMIN_ID)
            .build();
    }

    private static SkuCost latest(String effectiveFrom, CurrencyCode currency) {
        return new SkuCost(1L, SKU_ID, SkuCostType.GOODS, Money.of(10_000, currency), Instant.parse(effectiveFrom), "최초", ADMIN_ID, Instant.parse("2026-09-01T00:00:00Z"));
    }
}
