package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPickingInternalResponse;
import java.util.List;

public interface OrderPickingInternalUseCase {
    List<OrderPickingInternalResponse> findAll(List<Long> orderIds);
}
