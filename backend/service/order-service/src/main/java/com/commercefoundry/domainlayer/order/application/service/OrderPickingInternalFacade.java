package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPickingInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPickingInternalPresenter;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPickingInternalUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPickingReadProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderPickingInternalFacade implements OrderPickingInternalUseCase {
    private final OrderPickingReadProcessor processor;
    private final OrderPickingInternalPresenter presenter;

    @Override
    public List<OrderPickingInternalResponse> findAll(List<Long> orderIds) {
        return presenter.present(processor.findAll(orderIds));
    }
}
