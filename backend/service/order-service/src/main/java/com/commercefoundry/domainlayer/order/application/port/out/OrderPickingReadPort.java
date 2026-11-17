package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderPickingQueryResult;
import java.util.List;

public interface OrderPickingReadPort {
    /** 없는 주문은 결과에서 빠진다. 상태와 국가의 적격성은 delivery가 판정한다. */
    List<OrderPickingQueryResult> findAll(List<Long> orderIds);
}
