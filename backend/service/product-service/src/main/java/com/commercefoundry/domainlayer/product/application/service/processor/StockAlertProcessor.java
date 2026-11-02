package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.StockAlertErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.StockAlertException;
import com.commercefoundry.domainlayer.product.application.info.StockAlertInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAlertPolicyRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StockAlertProcessor {

    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductRepositoryPort productRepositoryPort;
    private final StockAlertPolicyRepositoryPort policyRepositoryPort;

    public StockAlertInfo.Policy getPolicy(long skuId) {
        Sku sku = skuRepositoryPort.findAllByIds(List.of(skuId)).stream().findFirst().orElseThrow(StockAlertProcessor::notFound);
        requireProduct(sku);
        return policyRepositoryPort.findBySkuId(skuId).map(StockAlertProcessor::toPolicy)
            .orElseGet(() -> new StockAlertInfo.Policy(skuId, false, null, null));
    }

    public Page<StockAlertInfo.LowStock> getLowStocks(int page, int size) {
        return policyRepositoryPort.findLowStocks(page, size).map(row -> new StockAlertInfo.LowStock(
            row.skuId(), row.productId(), row.productName(), row.optionValueKey(), row.availableQuantity(), row.thresholdQuantity(),
            row.productVisible(), row.productSaleStatus(), row.skuSaleStatus(), row.policyUpdatedAt()
        ));
    }

    /** SKU 잠금 → 상품 삭제 여부 확인 → 정책 저장 순서. 재고 행은 잠그거나 갱신하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockAlertInfo.Policy setPolicy(long skuId, long thresholdQuantity) {
        requireProduct(skuRepositoryPort.findByIdForUpdate(skuId).orElseThrow(StockAlertProcessor::notFound));
        return toPolicy(policyRepositoryPort.save(StockAlertPolicy.configure(skuId, thresholdQuantity)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearPolicy(long skuId) {
        requireProduct(skuRepositoryPort.findByIdForUpdate(skuId).orElseThrow(StockAlertProcessor::notFound));
        policyRepositoryPort.delete(skuId);
    }

    private void requireProduct(Sku sku) {
        boolean exists = productRepositoryPort.findAllByIdsIncludingHidden(List.of(sku.productId())).stream().anyMatch(product -> !product.isDeleted());
        if (!exists) {
            throw notFound();
        }
    }

    private static StockAlertInfo.Policy toPolicy(StockAlertPolicy policy) {
        return new StockAlertInfo.Policy(policy.skuId(), true, policy.thresholdQuantity(), policy.updatedAt());
    }

    private static StockAlertException notFound() {
        return new StockAlertException(StockAlertErrorCode.NOT_FOUND_SKU);
    }
}
