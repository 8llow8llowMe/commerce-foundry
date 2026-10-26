package com.commercefoundry.domainlayer.sizechart.application.port.in;

import com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.response.ProductSizeChartResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartUnitCode;

/**
 * 고객 상품 사이즈표. 없거나 숨긴 상품은 상품 상세와 같이 {@code PRODUCT_001}(404)이다.
 * 보이는 상품에 표가 없으면 null 이다. {@code unit} 이 없으면 저장 단위, 있으면 그 단위로 바꾼 값을 낸다.
 */
public interface ProductSizeChartWebUseCase {

    ProductSizeChartResponse getSizeChart(long productId, SizeChartUnitCode unit);
}
