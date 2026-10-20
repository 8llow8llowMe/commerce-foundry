package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockAdjustmentHistoryInfo;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuReservedQuantityQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockAdjustmentHistoryQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class StockAdminQueryProcessorTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-10-18T09:00:00Z");

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private StockRepositoryPort stockRepositoryPort;

    @Mock
    private StockReservationRepositoryPort stockReservationRepositoryPort;

    @Mock
    private StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;

    /** 조회 Processor 는 쓰지 않는다 — 조회 결과를 조정 Processor 에 넣어 보는 테스트만 쓴다. */
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @InjectMocks
    private StockAdminQueryProcessor processor;

    @Nested
    @DisplayName("재고 목록")
    class GetStocks {

        @Test
        @DisplayName("요청 순서(중복은 첫 등장 한 번)대로 내고 없는 SKU 는 뺀다 — 이후 쿼리는 있는 SKU 만 묻는다")
        void orderDuplicatesAndMissing() {
            given(skuRepositoryPort.findAllByIds(List.of(3L, 1L, 9L, 2L))).willReturn(List.of(sku(1L), sku(2L), sku(3L)));
            given(stockRepositoryPort.findAllWithUpdatedAtBySkuIds(Set.of(1L, 2L, 3L)))
                .willReturn(List.of(row(1L, true, 10L), row(2L, false, 0L), row(3L, true, 4L)));
            given(stockReservationRepositoryPort.sumReservedQuantitiesBySkuIds(Set.of(1L, 2L, 3L))).willReturn(List.of());

            List<StockAdminInfo> infos = processor.getStocks(List.of(3L, 1L, 9L, 3L, 2L));

            assertThat(infos).extracting(StockAdminInfo::skuId).containsExactly(3L, 1L, 2L);
        }

        @Test
        @DisplayName("재고 행이 없는 SKU 는 품절(관리 true · 수량 0 · 수정 시각 null)이다")
        void noRowIsSoldOut() {
            given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(sku(1L)));
            given(stockRepositoryPort.findAllWithUpdatedAtBySkuIds(Set.of(1L))).willReturn(List.of());
            given(stockReservationRepositoryPort.sumReservedQuantitiesBySkuIds(Set.of(1L))).willReturn(List.of());

            assertThat(processor.getStocks(List.of(1L))).containsExactly(new StockAdminInfo(1L, true, 0L, 0L, null));
        }

        @Test
        @DisplayName("선점 수량은 SKU 별 합을 싣고, 합이 없는 SKU 는 0 이다")
        void reservedQuantity() {
            given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(sku(1L), sku(2L)));
            given(stockRepositoryPort.findAllWithUpdatedAtBySkuIds(Set.of(1L, 2L))).willReturn(List.of(row(1L, true, 10L), row(2L, true, 5L)));
            given(stockReservationRepositoryPort.sumReservedQuantitiesBySkuIds(Set.of(1L, 2L)))
                .willReturn(List.of(new SkuReservedQuantityQueryResult(2L, 3L)));

            assertThat(processor.getStocks(List.of(1L, 2L))).containsExactly(
                new StockAdminInfo(1L, true, 10L, 0L, UPDATED_AT), new StockAdminInfo(2L, true, 5L, 3L, UPDATED_AT)
            );
        }

        @Test
        @DisplayName("있는 SKU 가 하나도 없으면 빈 목록이고 재고 · 선점을 읽지 않는다")
        void noneExists() {
            given(skuRepositoryPort.findAllByIds(List.of(9L))).willReturn(List.of());

            assertThat(processor.getStocks(List.of(9L))).isEmpty();
            verifyNoInteractions(stockRepositoryPort, stockReservationRepositoryPort);
        }

        /**
         * 조회한 availableQuantity 를 그대로 조정의 expectedQuantity 로 보내면 통과해야 한다 — 행 없는 SKU 의 조정은 기대 수량 0 을 요구한다 (#10).
         * 두 Processor 가 같은 "행 없음 = 품절" 기본값을 쓰는지 고정한다.
         */
        @Test
        @DisplayName("재고 행이 없는 SKU 의 조회 수량(0)을 조정의 expectedQuantity 로 보내면 첫 등록이 통과한다")
        void noRowQuantityIsAcceptedAsExpectedQuantity() {
            given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(sku(1L)));
            given(stockRepositoryPort.findAllWithUpdatedAtBySkuIds(Set.of(1L))).willReturn(List.of());
            long shown = processor.getStocks(List.of(1L)).getFirst().availableQuantity();

            StockAdjustmentProcessor adjustment = new StockAdjustmentProcessor(
                skuRepositoryPort, stockRepositoryPort, stockAdjustmentHistoryRepositoryPort, stockReservationRepositoryPort, snowflakeIdGenerator
            );
            given(skuRepositoryPort.existsById(1L)).willReturn(true);
            given(stockRepositoryPort.findBySkuId(1L)).willReturn(Optional.empty()).willReturn(Optional.of(row(1L, true, 12L)));

            StockAdminInfo adjusted = adjustment.adjust(StockAdjustmentCommand.builder()
                .skuId(1L).quantity(12L).expectedQuantity(shown).reason(StockAdjustmentReason.RESTOCK).adminMemberId(7L).build());

            verify(stockRepositoryPort).create(new Stock(1L, true, 12L));
            assertThat(adjusted.availableQuantity()).isEqualTo(12L);
        }
    }

    @Nested
    @DisplayName("조정 이력")
    class GetAdjustments {

        @Test
        @DisplayName("없는 SKU 면 404 · NOT_FOUND_SKU 이고 이력을 읽지 않는다")
        void skuNotFound() {
            given(skuRepositoryPort.existsById(1L)).willReturn(false);

            assertThatThrownBy(() -> processor.getAdjustments(1L, 0, 20))
                .isInstanceOf(ProductException.class)
                .extracting(exception -> ((ProductException) exception).getErrorCode())
                .isEqualTo(ProductErrorCode.NOT_FOUND_SKU);
            verifyNoInteractions(stockAdjustmentHistoryRepositoryPort);
        }

        @Test
        @DisplayName("이력 페이지를 Info 로 바꾸고 페이지 정보(전체 건수)는 그대로 둔다")
        void mapsPage() {
            StockAdjustmentHistoryQueryResult history = new StockAdjustmentHistoryQueryResult(
                9001L, 1L, 5L, 12L, 7L, true, true, StockAdjustmentReason.RESTOCK, "10월 입고분", 4611686018427387904L, UPDATED_AT
            );
            given(skuRepositoryPort.existsById(1L)).willReturn(true);
            given(stockAdjustmentHistoryRepositoryPort.findPageBySkuId(1L, 1, 1)).willReturn(new PageImpl<>(List.of(history), PageRequest.of(1, 1), 3L));

            Page<StockAdjustmentHistoryInfo> page = processor.getAdjustments(1L, 1, 1);

            assertThat(page.getTotalElements()).isEqualTo(3L);
            assertThat(page.getNumber()).isEqualTo(1);
            assertThat(page.getContent()).containsExactly(new StockAdjustmentHistoryInfo(
                9001L, 1L, 5L, 12L, 7L, true, true, StockAdjustmentReason.RESTOCK, "10월 입고분", 4611686018427387904L, UPDATED_AT
            ));
        }
    }

    private static StockQueryResult row(long skuId, boolean stockManaged, long availableQuantity) {
        return new StockQueryResult(skuId, stockManaged, availableQuantity, UPDATED_AT);
    }

    private static Sku sku(long id) {
        return ProductFixtures.sku(id, 100L, "", 0L, SaleStatus.ON_SALE);
    }
}
