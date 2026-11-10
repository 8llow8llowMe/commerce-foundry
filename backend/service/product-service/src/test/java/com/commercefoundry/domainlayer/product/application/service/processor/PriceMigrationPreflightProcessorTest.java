package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 포트 스텁으로 순회 종료·상한 중단·읽기 실패 전파·Port 계약 위반을 본다. */
class PriceMigrationPreflightProcessorTest {

    @Test
    void emptySourcesSkipPagesWhenUpperIsNullAndAreReady() {
        var stub = new PriceMigrationReadStub();
        var report = new PriceMigrationPreflightProcessor(stub).run();
        assertThat(stub.calls).isEmpty();
        assertThat(report.verdict()).isEqualTo(Verdict.READY);
        assertThat(report.scanned().values()).containsOnly(0L);
    }

    @Test
    void eachLaneAdvancesByLastIdUntilAnEmptyPage() {
        var stub = new PriceMigrationReadStub();
        for (long id : List.of(-5L, 0L, 7L, 9L, 30L)) stub.sku(id, null, 0L, "KRW", "ON_SALE");
        var report = new PriceMigrationPreflightProcessor(stub).run(2, 100);
        assertThat(stub.calls).allSatisfy(criteria -> assertThat(criteria.lane()).isEqualTo(Lane.SKU));
        assertThat(stub.calls).extracting(Criteria::after).containsExactly(null, 0L, 9L, 30L);
        assertThat(stub.calls).extracting(Criteria::upper).containsOnly(30L);
        assertThat(report.scanned().get(Lane.SKU)).isEqualTo(5L);
    }

    @Test
    void exceedingTotalScanLimitAcrossLanesStopsWithoutPartialResult() {
        var stub = new PriceMigrationReadStub().product(1, 1L, "KRW", null, null).product(2, 1L, "KRW", null, null)
            .sku(1, 1L, 0L, "KRW", "ON_SALE").sku(2, 2L, 0L, "KRW", "ON_SALE");
        var processor = new PriceMigrationPreflightProcessor(stub);
        assertThat(processor.run(500, 4).scanned().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4L);
        stub.skuPrice(1, 1L, 0L, "KRW");
        Throwable failure = catchThrowable(() -> processor.run(500, 4));
        assertThat(failure).isInstanceOf(PriceMigrationPreflightException.class).hasMessage("SCAN_LIMIT_EXCEEDED").hasNoCause();
    }

    @Test
    void readFailuresPropagateUnchangedWithoutRetry() {
        var failure = new PriceMigrationReadException(PriceMigrationReadException.Reason.READ_FAILED);
        var stub = new PriceMigrationReadStub() {
            @Override public List<Row> readPage(Criteria criteria) {
                calls.add(criteria);
                throw failure;
            }
        };
        stub.product(1, 1L, "KRW", null, null);
        assertThat(catchThrowable(() -> stub.run(500))).isSameAs(failure);
        assertThat(stub.calls).hasSize(1);
        var boundsFailure = new PriceMigrationReadStub() {
            @Override public Bounds captureBounds() { throw failure; }
        };
        assertThat(catchThrowable(() -> boundsFailure.run(500))).isSameAs(failure);
    }

    @Test
    void portContractViolationsStopInsteadOfLoopingOrDoubleCounting() {
        Row repeated = new SkuRow(5L, null, 0L, "KRW", "ON_SALE");
        for (List<Row> page : List.of(List.of(repeated, repeated), Arrays.asList((Row) null), List.<Row>of(new SkuRow(99L, null, 0L, "KRW", "ON_SALE")))) {
            var stub = new PriceMigrationReadStub() {
                @Override public List<Row> readPage(Criteria criteria) { return page; }
            };
            stub.sku(5, null, 0L, "KRW", "ON_SALE");
            assertThatThrownBy(() -> stub.run(500)).isInstanceOf(PriceMigrationPreflightException.class).hasMessage("PORT_CONTRACT_VIOLATION");
        }
        var nullBounds = new PriceMigrationReadStub() {
            @Override public Bounds captureBounds() { return null; }
        };
        assertThatThrownBy(() -> nullBounds.run(500)).hasMessage("PORT_CONTRACT_VIOLATION");
    }
}
