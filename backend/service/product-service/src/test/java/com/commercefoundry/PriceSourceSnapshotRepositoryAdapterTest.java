package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.CALLER_TRANSACTION_REQUIRED;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.INVALID_INPUT;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.PRODUCT_MISSING;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.READ_FAILED;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.ROW_LIMIT_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceSourceSnapshotRepositoryAdapter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductPriceRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuPriceRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException;
import com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason;
import com.commercefoundry.domainlayer.product.application.mapper.CurrencyPriceMapper;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapper;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PriceSourceSnapshotRepositoryAdapterTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final SkuRepository skus = mock(SkuRepository.class);
    private final ProductPriceRepository productPrices = mock(ProductPriceRepository.class);
    private final SkuPriceRepository skuPrices = mock(SkuPriceRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final PriceSourceSnapshotRepositoryAdapter adapter = new PriceSourceSnapshotRepositoryAdapter(
        products, skus, productPrices, skuPrices, mock(ProductMapper.class), mock(SkuMapper.class), mock(CurrencyPriceMapper.class), manager);

    @BeforeEach
    void transaction() {
        when(manager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void emptyAndInvalidInputsAvoidTransactionAndSql() {
        assertThat(adapter.readByProductIds(List.of()).productIds()).isEmpty();
        for (List<Long> ids : Arrays.asList(null, Arrays.asList(1L, null), List.of(0L), List.of(-1L),
            LongStream.rangeClosed(1, 51).boxed().toList())) {
            assertFailure(catchThrowable(() -> adapter.readByProductIds(ids)), INVALID_INPUT);
        }
        verifyNoInteractions(manager, products, skus, productPrices, skuPrices);
    }

    @Test
    void duplicatesAreRemovedBeforeTheCapAndMissingStopsAfterFirstQuery() {
        var ids = LongStream.rangeClosed(1, 50).boxed().toList();
        var duplicated = new java.util.ArrayList<>(ids.reversed());
        duplicated.addAll(ids);
        assertFailure(catchThrowable(() -> adapter.readByProductIds(duplicated)), PRODUCT_MISSING);
        verify(products).findAllByIdInOrderByIdAsc(ids, PageRequest.of(0, 51));
        verifyNoInteractions(skus, productPrices, skuPrices);
        var config = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(config.capture());
        assertThat(config.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(config.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(config.getValue().getTimeout()).isEqualTo(5);
        assertThat(config.getValue().isReadOnly()).isTrue();
        verify(manager).rollback(status);
    }

    @Test
    void rowOverflowStopsBeforeLaterQueries() {
        when(products.findAllByIdInOrderByIdAsc(any(), any())).thenReturn(List.of(ProductEntity.builder().id(1L).build()));
        when(skus.findAllByProductIdInOrderByProductIdAscIdAsc(eq(List.of(1L)), eq(PageRequest.of(0, 2001))))
            .thenReturn(Collections.nCopies(2001, mock(SkuEntity.class)));
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), ROW_LIMIT_EXCEEDED);
        verifyNoInteractions(productPrices, skuPrices);
    }

    @Test
    void priceCapsUseActualParentCountsAndStopAtTheOverflowingLane() {
        int cap = com.commercefoundry.shared.commerce.CurrencyCode.values().length + 1;
        when(products.findAllByIdInOrderByIdAsc(any(), any())).thenReturn(List.of(ProductEntity.builder().id(1L).build()));
        when(skus.findAllByProductIdInOrderByProductIdAscIdAsc(any(), any())).thenReturn(List.of(SkuEntity.builder().id(10L).build()));
        var limit = PageRequest.of(0, cap);
        var parents = when(productPrices.findAllByProductIdInOrderByProductIdAscSalePriceCurrencyAscIdAsc(List.of(1L), limit));
        parents.thenReturn(Collections.nCopies(cap, mock(ProductPriceEntity.class)));
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), ROW_LIMIT_EXCEEDED);
        verifyNoInteractions(skuPrices);

        when(productPrices.findAllByProductIdInOrderByProductIdAscSalePriceCurrencyAscIdAsc(List.of(1L), limit)).thenReturn(List.of());
        when(skuPrices.findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAscIdAsc(List.of(10L), limit))
            .thenReturn(Collections.nCopies(cap, mock(SkuPriceEntity.class)));
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), ROW_LIMIT_EXCEEDED);
    }

    @Test
    void transactionBeginFailureDropsOriginalException() {
        when(manager.getTransaction(any())).thenThrow(new IllegalStateException("raw-begin"));
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), READ_FAILED);
        verifyNoInteractions(products);
    }

    @Test
    void queryAndRollbackFailuresDropOriginalExceptions() {
        when(products.findAllByIdInOrderByIdAsc(any(), any())).thenThrow(new IllegalStateException("raw-query"));
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), READ_FAILED);
        doThrow(new IllegalStateException("raw-rollback")).when(manager).rollback(status);
        assertFailure(catchThrowable(() -> adapter.readByProductIds(List.of(1L))), READ_FAILED);
    }

    @Test
    void commitFailureDropsOriginalExceptionAndDoesNotReturnResults() {
        var mapper = mock(ProductMapper.class);
        var product = ProductEntity.builder().id(1L).build();
        when(products.findAllByIdInOrderByIdAsc(any(), any())).thenReturn(List.of(product));
        when(mapper.toDomains(List.of(product))).thenReturn(List.of(mock(com.commercefoundry.domainlayer.product.domain.model.Product.class)));
        var committing = new PriceSourceSnapshotRepositoryAdapter(
            products, skus, productPrices, skuPrices, mapper, mock(SkuMapper.class), mock(CurrencyPriceMapper.class), manager);
        doThrow(new IllegalStateException("raw-commit")).when(manager).commit(status);
        assertFailure(catchThrowable(() -> committing.readByProductIds(List.of(1L))), READ_FAILED);
        verify(manager).commit(status);
    }

    @Test
    void currentTransactionReadRejectsMissingOrWeakCallerBeforeTransactionAndSql() {
        assertThat(adapter.readByProductIdsInCurrentTransaction(List.of()).productIds()).isEmpty();
        assertFailure(catchThrowable(() -> adapter.readByProductIdsInCurrentTransaction(List.of(0L))), INVALID_INPUT);
        assertFailure(catchThrowable(() -> adapter.readByProductIdsInCurrentTransaction(List.of(1L))), CALLER_TRANSACTION_REQUIRED);
        // 열린 TX 라도 read-only 가 아니거나 REPEATABLE_READ 미만이면 호출자의 첫 읽기와 같은 시점을 보장할 수 없다.
        assertCallerRejected(false, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertCallerRejected(true, TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertCallerRejected(true, null);
        verifyNoInteractions(manager, products, skus, productPrices, skuPrices);
    }

    @Test
    void currentTransactionReadJoinsTheCallerWithMandatoryPropagationAndSharesSanitizing() {
        var ids = LongStream.rangeClosed(1, 50).boxed().toList();
        var duplicated = new java.util.ArrayList<>(ids.reversed());
        duplicated.addAll(ids);
        inCaller(true, TransactionDefinition.ISOLATION_REPEATABLE_READ, () ->
            assertFailure(catchThrowable(() -> adapter.readByProductIdsInCurrentTransaction(duplicated)), PRODUCT_MISSING));
        verify(products).findAllByIdInOrderByIdAsc(ids, PageRequest.of(0, 51));
        verifyNoInteractions(skus, productPrices, skuPrices);
        var config = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(config.capture());
        assertThat(config.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_MANDATORY);
        verify(manager).rollback(status);

        when(products.findAllByIdInOrderByIdAsc(any(), any())).thenThrow(new IllegalStateException("raw-repository"));
        inCaller(true, TransactionDefinition.ISOLATION_SERIALIZABLE, () ->
            assertFailure(catchThrowable(() -> adapter.readByProductIdsInCurrentTransaction(List.of(1L))), READ_FAILED));
    }

    private void assertCallerRejected(boolean readOnly, Integer isolation) {
        inCaller(readOnly, isolation, () ->
            assertFailure(catchThrowable(() -> adapter.readByProductIdsInCurrentTransaction(List.of(1L))), CALLER_TRANSACTION_REQUIRED));
    }

    /** 실제 TX 없이 호출자 TX 의 동기화 상태만 흉내 낸다 — 어댑터가 보는 것은 이 상태와 TransactionTemplate 의 전파뿐이다. */
    private static void inCaller(boolean readOnly, Integer isolation, Runnable body) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        try {
            body.run();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    static void assertFailure(Throwable error, Reason reason) {
        assertThat(error).isExactlyInstanceOf(PriceSourceSnapshotReadException.class).hasMessage(reason.name()).hasNoCause();
        assertThat(((PriceSourceSnapshotReadException) error).reason()).isEqualTo(reason);
        assertThat(error.getSuppressed()).isEmpty();
    }
}
