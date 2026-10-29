package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.util.SortedMap;

/** Reserve only. Never retry or compensate automatically; future C3 must reuse the request-record orderId. */
public interface StockReservationPort {

    /** SKU IDs in ascending order, with positive aggregated quantities. */
    StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId);
}
