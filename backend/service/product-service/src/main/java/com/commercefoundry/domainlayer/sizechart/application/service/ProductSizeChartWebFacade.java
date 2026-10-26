package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response.ProductSizeChartResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.web.presenter.SizeChartWebPresenter;
import com.commercefoundry.domainlayer.sizechart.application.info.ProductSizeChartInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartUnitCode;
import com.commercefoundry.domainlayer.sizechart.application.port.in.ProductSizeChartWebUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.ProductSizeChartProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 상품 사이즈표. 없거나 숨긴 상품은 상품 상세와 같은 {@link ProductDetailProcessor#getVisibleProduct} 라 {@code PRODUCT_001}(404)이다.
 * 보이는 상품에 표가 없으면 null 이다. 단위를 빼면 저장 값 그대로, 있으면 표시 단위로만 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class ProductSizeChartWebFacade implements ProductSizeChartWebUseCase {

    private final ProductDetailProcessor productDetailProcessor;
    private final ProductSizeChartProcessor productSizeChartProcessor;
    private final SizeChartWebPresenter sizeChartWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public ProductSizeChartResponse getSizeChart(long productId, SizeChartUnitCode unit) {
        Product product = productDetailProcessor.getVisibleProduct(productId);
        SizeChartUnit displayUnit = unit == null ? null : unit.toDomain();
        ProductSizeChartInfo info = productSizeChartProcessor.find(product.id(), displayUnit);
        return info == null ? null : sizeChartWebPresenter.toResponse(info);
    }
}
