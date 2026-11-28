package com.commercefoundry.domainlayer.product.adapter.out.persistence;

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
import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceSnapshotRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원천 snapshot이다. 새 TX 경로는 그 TX의 첫 SELECT 시점, 참여 경로는 호출자 TX의 snapshot 시점이다.
 * 최신성·가격 집합 유효성·다음 쓰기의 정합성을 인증하지 않는다.
 */
@Repository
public class PriceSourceSnapshotRepositoryAdapter implements PriceSourceSnapshotRepositoryPort {
    // 상품 · SKU 행 상한은 포트 계약 상수(MAX_PRODUCTS · MAX_SKU_ROWS)다 — 호출자의 묶음 크기가 같은 값을 보고 정해진다
    private static final int CURRENCIES = CurrencyCode.values().length;

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductPriceRepository productPriceRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final CurrencyPriceMapper currencyPriceMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate participatingTemplate;

    public PriceSourceSnapshotRepositoryAdapter(
        ProductRepository productRepository, SkuRepository skuRepository,
        ProductPriceRepository productPriceRepository, SkuPriceRepository skuPriceRepository,
        ProductMapper productMapper, SkuMapper skuMapper, CurrencyPriceMapper currencyPriceMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.productPriceRepository = productPriceRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.currencyPriceMapper = currencyPriceMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactionTemplate.setReadOnly(true);
        transactionTemplate.setTimeout(5);
        // 참여 경로는 호출자 TX의 격리·read-only·timeout을 그대로 따른다. 참여 TX에서는 정의의 격리·timeout이 적용되지 않으므로 전파만 정한다.
        this.participatingTemplate = new TransactionTemplate(transactionManager);
        participatingTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public PriceSourceSnapshotQueryResult readByProductIds(List<Long> productIds) {
        return read(productIds, transactionTemplate, false);
    }

    /**
     * 호출자의 read-only REPEATABLE_READ(이상) TX에 참여한다. 같은 EntityManager·커넥션이라 호출자가 이미 읽은 상품 Entity는 1차 캐시의 같은 인스턴스이고,
     * read-only TX의 MANUAL flush라 호출자 Entity를 flush하지 않는다. 실패하면 참여 TX를 rollback-only로 표시한다 — 호출자도 실패로 끝나야 한다.
     */
    @Override
    public PriceSourceSnapshotQueryResult readByProductIdsInCurrentTransaction(List<Long> productIds) {
        return read(productIds, participatingTemplate, true);
    }

    private PriceSourceSnapshotQueryResult read(List<Long> productIds, TransactionTemplate template, boolean requireCallerSnapshot) {
        List<Long> normalized = normalize(productIds);
        if (normalized.isEmpty()) {
            return new PriceSourceSnapshotQueryResult(normalized, List.of(), List.of(), List.of(), List.of());
        }
        if (requireCallerSnapshot && !callerHasReadOnlySnapshot()) {
            throw new PriceSourceSnapshotReadException(Reason.CALLER_TRANSACTION_REQUIRED);
        }
        // callback에서 만든 정제 예외만 신뢰한다. 저장소가 같은 공개 예외를 던져도 의도한 사유로 보지 않는다.
        PriceSourceSnapshotReadException[] callbackFailure = new PriceSourceSnapshotReadException[1];
        try {
            return template.execute(status -> {
                try {
                    return readSnapshot(normalized);
                } catch (SnapshotAbort exception) {
                    callbackFailure[0] = new PriceSourceSnapshotReadException(exception.reason);
                    throw callbackFailure[0];
                } catch (RuntimeException exception) {
                    // TransactionTemplate의 rollback DEBUG 로그에도 원문 예외를 넘기지 않는다.
                    callbackFailure[0] = new PriceSourceSnapshotReadException(Reason.READ_FAILED);
                    throw callbackFailure[0];
                }
            });
        } catch (RuntimeException exception) {
            // 시작·commit·rollback 실패에는 원인과 suppressed를 포함하지 않는다.
            Reason reason = exception == callbackFailure[0] ? callbackFailure[0].reason() : Reason.READ_FAILED;
            throw new PriceSourceSnapshotReadException(reason);
        }
    }

    private PriceSourceSnapshotQueryResult readSnapshot(List<Long> productIds) {
        var products = productRepository.findAllByIdInOrderByIdAsc(productIds, PageRequest.of(0, MAX_PRODUCTS + 1));
        if (products.size() != productIds.size()
            || !products.stream().map(entity -> entity.getId()).toList().equals(productIds)) {
            throw new SnapshotAbort(Reason.PRODUCT_MISSING);
        }
        var skus = skuRepository.findAllByProductIdInOrderByProductIdAscIdAsc(productIds, PageRequest.of(0, MAX_SKU_ROWS + 1));
        requireWithinLimit(skus.size(), MAX_SKU_ROWS);
        int productPriceLimit = products.size() * CURRENCIES;
        var productPrices = productPriceRepository.findAllByProductIdInOrderByProductIdAscSalePriceCurrencyAscIdAsc(
            productIds, PageRequest.of(0, productPriceLimit + 1));
        requireWithinLimit(productPrices.size(), productPriceLimit);
        var skuIds = skus.stream().map(entity -> entity.getId()).toList();
        int skuPriceLimit = skus.size() * CURRENCIES;
        var skuPrices = skuIds.isEmpty() ? List.<SkuPriceEntity>of()
            : skuPriceRepository.findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAscIdAsc(skuIds, PageRequest.of(0, skuPriceLimit + 1));
        requireWithinLimit(skuPrices.size(), skuPriceLimit);
        return new PriceSourceSnapshotQueryResult(productIds, productMapper.toDomains(products), skuMapper.toDomains(skus),
            productPrices.stream().map(currencyPriceMapper::toDomain).toList(), skuPrices.stream().map(currencyPriceMapper::toDomain).toList());
    }

    /** 참여 읽기가 호출자의 첫 읽기와 같은 시점이려면 호출자 TX가 실제로 열려 있고 read-only·REPEATABLE_READ 이상이어야 한다. */
    private static boolean callerHasReadOnlySnapshot() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        return TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            && isolation != null
            && (isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    private static List<Long> normalize(List<Long> productIds) {
        if (productIds == null) {
            throw new PriceSourceSnapshotReadException(Reason.INVALID_INPUT);
        }
        List<Long> copy = new ArrayList<>(productIds);
        TreeSet<Long> unique = new TreeSet<>();
        for (Long id : copy) {
            if (id == null || id <= 0) {
                throw new PriceSourceSnapshotReadException(Reason.INVALID_INPUT);
            }
            unique.add(id);
        }
        if (unique.size() > MAX_PRODUCTS) {
            throw new PriceSourceSnapshotReadException(Reason.INVALID_INPUT);
        }
        return List.copyOf(unique);
    }

    private static void requireWithinLimit(int size, int limit) {
        if (size > limit) {
            throw new SnapshotAbort(Reason.ROW_LIMIT_EXCEEDED);
        }
    }

    /** 어댑터의 직접 판정만 callback 정제 경계까지 운반하는 비공개 표식이다. */
    private static final class SnapshotAbort extends RuntimeException {
        private final Reason reason;

        private SnapshotAbort(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
