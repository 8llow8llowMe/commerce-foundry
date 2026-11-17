package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.ProductResult;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Tally;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PriceBackfillProcessorTest {

    final PriceMigrationReadStub reads = new PriceMigrationReadStub();
    final PriceBackfillProductProcessor products = mock(PriceBackfillProductProcessor.class);
    final PriceBackfillProcessor processor = new PriceBackfillProcessor(reads, products);

    @Test
    void walksEveryProductAcrossPageBoundariesAndSumsResults() {
        for (long id = 1; id <= 5; id++) {
            reads.product(id, 1000L, "KRW", null, null);
        }
        when(products.fill(anyLong(), eq(Mode.APPLY))).thenReturn(ProductResult.of(Outcome.COMPLETE));
        when(products.fill(2, Mode.APPLY)).thenReturn(new ProductResult(Outcome.FILLED, 1, 3));
        when(products.fill(5, Mode.APPLY)).thenReturn(new ProductResult(Outcome.FILLED, 0, 2));

        Tally tally = processor.run(Mode.APPLY, 2);

        assertThat(tally.count(Outcome.FILLED)).isEqualTo(2);
        assertThat(tally.count(Outcome.COMPLETE)).isEqualTo(3);
        assertThat(tally.productPrices()).isEqualTo(1);
        assertThat(tally.skuPrices()).isEqualTo(5);
        assertThat(reads.calls).allSatisfy(criteria -> assertThat(criteria.lane()).isEqualTo(Lane.PRODUCT));
        assertThat(reads.calls).extracting(criteria -> criteria.after()).containsExactly(null, 2L, 4L, 5L);
    }

    @Test
    void emptyProductTableReadsNoPage() {
        Tally tally = processor.run(Mode.DRY_RUN, 10);

        assertThat(tally.outcomes().values()).containsOnly(0L);
        assertThat(reads.calls).isEmpty();
    }

    @Test
    void concurrentChangeAndBrokenDataAreCountedAndTheWalkContinues() {
        for (long id = 1; id <= 4; id++) {
            reads.product(id, 1000L, "KRW", null, null);
        }
        when(products.fill(1, Mode.APPLY)).thenThrow(new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        when(products.fill(2, Mode.APPLY)).thenThrow(new IllegalStateException("drift"));
        when(products.fill(3, Mode.APPLY)).thenThrow(new IllegalArgumentException("money"));
        when(products.fill(4, Mode.APPLY)).thenReturn(ProductResult.of(Outcome.NOT_FOUND));

        Tally tally = processor.run(Mode.APPLY, 10);

        assertThat(tally.count(Outcome.CONFLICT)).isEqualTo(1);
        assertThat(tally.count(Outcome.INVALID)).isEqualTo(2);
        assertThat(tally.count(Outcome.NOT_FOUND)).isEqualTo(1);
    }

    @Test
    void nonPositiveIdIsInvalidWithoutOpeningATransaction() {
        reads.product(0, 1000L, "KRW", null, null);

        assertThat(processor.run(Mode.APPLY, 10).count(Outcome.INVALID)).isEqualTo(1);
        verify(products, never()).fill(anyLong(), eq(Mode.APPLY));
    }

    @Test
    void otherProductErrorsAndInfrastructureFailuresStopTheRun() {
        reads.product(1, 1000L, "KRW", null, null);
        reads.product(2, 1000L, "KRW", null, null);
        when(products.fill(1, Mode.APPLY)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> processor.run(Mode.APPLY, 10)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(products, never()).fill(2, Mode.APPLY);

        doThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT)).when(products).fill(1, Mode.APPLY);
        assertThatThrownBy(() -> processor.run(Mode.APPLY, 10)).isInstanceOf(ProductException.class);
    }

    @Test
    void portContractViolationsStopWithoutPartialResult() {
        var nullBounds = new PriceMigrationReadStub() {
            @Override
            public com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds captureBounds() {
                return null;
            }
        };
        assertThatThrownBy(() -> new PriceBackfillProcessor(nullBounds, products).run(Mode.APPLY, 10))
            .isInstanceOfSatisfying(PriceMigrationPreflightException.class,
                exception -> assertThat(exception.reason()).isEqualTo(PriceMigrationPreflightException.Reason.PORT_CONTRACT_VIOLATION));

        var wrongLane = new PriceMigrationReadStub() {
            @Override
            public List<com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row> readPage(
                com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria criteria) {
                return List.of(new SkuRow(1L, 1L, 0L, "KRW", "ON_SALE"));
            }
        };
        wrongLane.product(1, 1000L, "KRW", null, null);
        assertThatThrownBy(() -> new PriceBackfillProcessor(wrongLane, products).run(Mode.APPLY, 10))
            .isInstanceOf(PriceMigrationPreflightException.class);

        var repeating = new PriceMigrationReadStub() {
            @Override
            public List<com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row> readPage(
                com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria criteria) {
                return List.of(new ProductRow(1L, 1000L, "KRW", null, null, "ON_SALE", true, null, 0L));
            }
        };
        repeating.product(1, 1000L, "KRW", null, null);
        when(products.fill(1, Mode.APPLY)).thenReturn(ProductResult.of(Outcome.COMPLETE));
        assertThatThrownBy(() -> new PriceBackfillProcessor(repeating, products).run(Mode.APPLY, 10))
            .isInstanceOf(PriceMigrationPreflightException.class);
    }
}
