package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.catalog.application.service.processor.CatalogSkuProcessor;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderQuoteWebPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.port.in.OrderQuoteWebUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderQuoteProcessor;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 고객 주문서 금액 미리보기. <b>트랜잭션을 걸지 않는다</b> — DB 에 닿지 않고 원격 호출(product-service SKU 조회, delivery-service 배송비)만 있다.
 *
 * <p>SKU 조회는 catalog 의 {@link CatalogSkuProcessor} 를 한 번 부르고, 계산은 {@link OrderQuoteProcessor} 하나가 한다 — 주문 생성도 같은 둘을 같은 순서로 부른다.
 * 조회 실패({@code CatalogException})는 {@code OrderExceptionHandler} 가 {@code ORDER_002}(503)로 번역한다.
 */
@Service
@RequiredArgsConstructor
public class OrderQuoteWebFacade implements OrderQuoteWebUseCase {

    private final CatalogSkuProcessor catalogSkuProcessor;
    private final OrderQuoteProcessor orderQuoteProcessor;
    private final OrderQuoteWebPresenter orderQuoteWebPresenter;

    @Override
    public OrderQuoteResponse quote(OrderQuoteCommand command) {
        Set<Long> skuIds = new LinkedHashSet<>();
        command.items().stream().map(OrderItemCommand::skuId).forEach(skuIds::add);
        Map<Long, CatalogSku> skus = catalogSkuProcessor.findSkus(skuIds);
        return orderQuoteWebPresenter.toResponse(orderQuoteProcessor.quote(command, skus));
    }
}
