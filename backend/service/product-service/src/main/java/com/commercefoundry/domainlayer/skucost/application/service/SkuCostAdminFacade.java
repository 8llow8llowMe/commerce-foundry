package com.commercefoundry.domainlayer.skucost.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.SkuLookupProcessor;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item.SkuCostAdminItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.response.SkuCurrentCostsAdminResponse;
import com.commercefoundry.domainlayer.skucost.adapter.in.admin.presenter.SkuCostAdminPresenter;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.model.SkuCostTypeCode;
import com.commercefoundry.domainlayer.skucost.application.port.in.SkuCostAdminUseCase;
import com.commercefoundry.domainlayer.skucost.application.service.processor.SkuCostAppendProcessor;
import com.commercefoundry.domainlayer.skucost.application.service.processor.SkuCostQueryProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 SKU 원가. SKU 가 있는지는 상품 컨텍스트 {@link SkuLookupProcessor} 로 본다 — 숨긴 상품의 SKU 도 있는 SKU 다. 없으면 404 {@code PRODUCT_002}.
 * 외부 I/O 가 없어 Facade 에 트랜잭션을 건다.
 *
 * <p>추가는 SKU 행을 잠근 뒤 최신 행을 읽고 판정한다 — 같은 SKU 의 동시 추가가 줄을 서서 단조 규칙을 함께 비켜 가지 못한다. 잠금은 시간 상한
 * ({@value #LOCKING_TIMEOUT_SECONDS}초, 행 잠금 대기 2초) 안에 얻고, 넘기면 409 {@code SKU_COST_005} 이다.
 *
 * <p><b>감사 (#163 PR-C′)</b> — 추가는 {@link ProductAdminAuditProcessor} 로 {@code sku:<skuId>} 대상 한 줄 남긴다(선례: {@code CategoryAdminFacade}). 트랜잭션 안에서 불러도
 * 줄은 커밋 결과로 남는다(롤백되면 성공으로 부른 것도 {@code FAILURE}). 업무 예외는 기록한 뒤 그대로 다시 던진다. 금액 · 통화 · 유형 · 적용 시각 · 사유는 싣지 않는다 —
 * 정본은 원가 이력 표다.
 */
@Service
@RequiredArgsConstructor
public class SkuCostAdminFacade implements SkuCostAdminUseCase {

    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final SkuLookupProcessor skuLookupProcessor;
    private final SkuCostQueryProcessor skuCostQueryProcessor;
    private final SkuCostAppendProcessor skuCostAppendProcessor;
    private final SkuCostAdminPresenter skuCostAdminPresenter;
    private final ProductAdminAuditProcessor productAdminAuditProcessor;

    @Override
    @Transactional(readOnly = true)
    public SkuCurrentCostsAdminResponse getCurrentCosts(long skuId) {
        skuLookupProcessor.requireExists(skuId);
        return skuCostAdminPresenter.toCurrent(skuId, skuCostQueryProcessor.getCurrent(skuId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SkuCostAdminItem> getHistories(long skuId, SkuCostTypeCode costType, int page, int size) {
        skuLookupProcessor.requireExists(skuId);
        return skuCostAdminPresenter.toPage(skuCostQueryProcessor.getHistories(skuId, costType == null ? null : costType.toDomain(), page, size));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public SkuCostAdminItem createCost(SkuCostCreateCommand command) {
        SkuCostAdminItem item;
        try {
            item = appendLocked(command);
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.skuCostCreated(command.adminMemberId(), command.skuId(), false);
            throw exception;
        }
        productAdminAuditProcessor.skuCostCreated(command.adminMemberId(), command.skuId(), true);
        return item;
    }

    /** 잠금 대기 · 작업 중 시간 상한 초과와 잠금 획득 실패는 409 {@code SKU_COST_005} 로 바꾼다 — 감사 try 안에서 불러 바꾼 예외도 {@code FAILURE} 로 남는다. */
    private SkuCostAdminItem appendLocked(SkuCostCreateCommand command) {
        try {
            skuLookupProcessor.requireForUpdate(command.skuId());
            return skuCostAdminPresenter.toItem(skuCostAppendProcessor.append(command));
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new SkuCostException(SkuCostErrorCode.SKU_COST_BUSY, exception);
        }
    }
}
