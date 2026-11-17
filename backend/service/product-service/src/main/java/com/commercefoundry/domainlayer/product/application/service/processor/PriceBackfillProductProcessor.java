package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.ProductResult;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 한 건의 기준통화 가격행 backfill. 상품마다 새 TX 를 열어 그 시점의 legacy 값과 가격행을 다시 읽는다.
 *
 * <p>계획은 B1b writer 와 같은 {@link LegacyPriceWriteProcessor#prepare} 에 같은 스냅숏을 before · after 로 넘겨 만든다 —
 * 기존 행은 바꾸지 않고 빠진 기준통화 행만 legacy 값 · 실제 currency 그대로 INSERT 계획에 담긴다. drift · 통화 불일치 · 휴면 행은
 * 그 자리에서 예외로 거절되고(보정 없음) TX 가 롤백된다.
 *
 * <p>APPLY 는 INSERT 전에 {@link ProductRepositoryPort#fenceVersion} 로 상품 버전을 CAS 로 올린다. 이 TX 가 읽은 뒤 커밋한
 * writer 가 있으면 fence 가 PRODUCT_010 으로 실패하고, 같은 버전을 읽어 둔 writer 는 이후 자기 CAS 에서 실패한다.
 */
@Component
@RequiredArgsConstructor
public class PriceBackfillProductProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final LegacyPriceWriteProcessor legacyPriceWriteProcessor;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductResult fill(long productId, Mode mode) {
        List<Product> found = productRepositoryPort.findAllByIdsIncludingHidden(List.of(productId));
        if (found.isEmpty()) {
            return ProductResult.of(Outcome.NOT_FOUND);
        }
        Product product = found.get(0);
        LegacyPriceSnapshot snapshot = LegacyPriceSnapshot.of(product, skuRepositoryPort.findAllByProductId(productId));
        LegacyPriceWritePlan plan = legacyPriceWriteProcessor.prepare(snapshot, snapshot);
        if (!plan.productUpdates().isEmpty() || !plan.skuUpdates().isEmpty()) {
            // 같은 스냅숏이면 수정 계획이 나올 수 없다. 나오면 계약 위반이라 쓰지 않는다.
            throw new IllegalStateException("backfill 계획에 수정 행이 있습니다");
        }
        int productPrices = plan.productInserts().size();
        int skuPrices = plan.skuInserts().size();
        if (productPrices == 0 && skuPrices == 0) {
            return ProductResult.of(Outcome.COMPLETE);
        }
        if (mode == Mode.DRY_RUN) {
            return new ProductResult(Outcome.WOULD_FILL, productPrices, skuPrices);
        }
        if (product.version() == null) {
            throw new IllegalStateException("저장된 상품의 버전이 없습니다");
        }
        productRepositoryPort.fenceVersion(productId, product.version());
        legacyPriceWriteProcessor.write(plan);
        return new ProductResult(Outcome.FILLED, productPrices, skuPrices);
    }
}
