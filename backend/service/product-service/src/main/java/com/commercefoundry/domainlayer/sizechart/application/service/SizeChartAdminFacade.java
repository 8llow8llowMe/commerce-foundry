package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateSummaryAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.ProductSizeChartAdminResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartTemplateAdminResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.ProductSizeChartInfo;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartAdminUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartTemplateProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 사이즈표. 상품이 있는지는 상품 컨텍스트 {@link ProductLookupProcessor} 로 확인한다(숨김 포함, in 절 한 번).
 * 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 쓰기는 템플릿 행 잠금을 시간 상한({@value #LOCKING_TIMEOUT_SECONDS}초) 안에 얻는다.
 * 상한을 넘기면 409 · {@code PRODUCT_072} 다. 버전 칸이 없어 같은 템플릿을 두 어드민이 고치면 잠금을 먼저 잡은 쓰기가 이긴다.
 */
@Service
@RequiredArgsConstructor
public class SizeChartAdminFacade implements SizeChartAdminUseCase {

    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final SizeChartTemplateProcessor sizeChartTemplateProcessor;
    private final ProductSizeChartProcessor productSizeChartProcessor;
    private final ProductLookupProcessor productLookupProcessor;
    private final SizeChartAdminPresenter sizeChartAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SizeChartTemplateSummaryAdminItem> getTemplates(int page, int size) {
        return sizeChartAdminPresenter.toPage(sizeChartTemplateProcessor.getTemplates(page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public SizeChartTemplateAdminResponse getTemplate(long templateId) {
        return sizeChartAdminPresenter.toTemplate(sizeChartTemplateProcessor.getTemplate(templateId));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public SizeChartTemplateAdminResponse createTemplate(SizeChartTemplateUpsertCommand command) {
        return runLocked(() -> sizeChartAdminPresenter.toTemplate(sizeChartTemplateProcessor.create(command)));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public SizeChartTemplateAdminResponse updateTemplate(long templateId, SizeChartTemplateUpsertCommand command) {
        return runLocked(() -> sizeChartAdminPresenter.toTemplate(sizeChartTemplateProcessor.update(templateId, command)));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void deleteTemplate(long templateId) {
        runLocked(() -> {
            sizeChartTemplateProcessor.delete(templateId);
            return null;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSizeChartAdminResponse getProductSizeChart(long productId) {
        requireProduct(productId);
        ProductSizeChartInfo info = productSizeChartProcessor.find(productId, null);
        return info == null ? null : sizeChartAdminPresenter.toChart(info);
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public ProductSizeChartAdminResponse replaceProductSizeChart(long productId, ProductSizeChartUpsertCommand command) {
        return runLocked(() -> sizeChartAdminPresenter.toChart(
            productSizeChartProcessor.replace(productId, productsIncludingHidden(productId), command)
        ));
    }

    private void requireProduct(long productId) {
        productSizeChartProcessor.requireProduct(productId, productsIncludingHidden(productId));
    }

    private Map<Long, Product> productsIncludingHidden(long productId) {
        return productLookupProcessor.findAllIncludingHidden(List.of(productId));
    }

    private <T> T runLocked(Supplier<T> work) {
        try {
            return work.get();
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new SizeChartException(SizeChartErrorCode.SIZE_CHART_BUSY, exception);
        }
    }
}
