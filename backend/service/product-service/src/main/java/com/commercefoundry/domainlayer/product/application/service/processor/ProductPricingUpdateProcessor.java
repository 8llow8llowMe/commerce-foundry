package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상품 버전으로 가격 전체 교체를 보호한다. SKU · 재고 · 선점은 변경하지 않는다. */
@Component
@RequiredArgsConstructor
public class ProductPricingUpdateProcessor {
    private final ProductRepositoryPort productRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductPricingUpdateCommand command) {
        var current = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(product -> !product.isDeleted()).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (current.version() == null || current.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        if (current.salePrice().currency() != command.salePrice().currency()) {
            throw new ProductException(ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED, "상품의 기본 통화는 바꿀 수 없습니다");
        }
        var skus = skuRepositoryPort.findAllByProductId(command.productId());
        ProductPricing.violation(command.salePrice(), command.listPrice(), skus.stream()
            .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), sku.saleStatus())).toList())
            .ifPresent(reason -> { throw new ProductException(ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED, reason); });
        // 동일 값도 먼저 버전과 가격 불변식을 검사한다. 감사 필드의 merge 여부에 기대지 않고 저장을 생략한다.
        if (!current.salePrice().equals(command.salePrice()) || !java.util.Objects.equals(current.listPrice(), command.listPrice())) {
            productRepositoryPort.update(current.withPricing(command.salePrice(), command.listPrice()));
        }
        return productAdminDetailProcessor.getProduct(command.productId());
    }
}
