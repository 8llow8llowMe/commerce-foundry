package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockReservationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.application.info.StockReservationInfo;
import org.springframework.stereotype.Component;

@Component
public class StockInternalPresenter {

    public StockReservationResponse toResponse(StockReservationInfo reservation) {
        return StockReservationResponse.builder()
            .reservationId(String.valueOf(reservation.reservationId()))
            .orderId(String.valueOf(reservation.orderId()))
            .status(reservation.status().name())
            .expiresAt(reservation.expiresAt())
            .items(reservation.items().stream().map(this::toItem).toList())
            .build();
    }

    private StockReservationItemInternalItem toItem(StockReservationInfo.Item item) {
        return StockReservationItemInternalItem.builder()
            .skuId(String.valueOf(item.skuId()))
            .quantity(item.quantity())
            .deducted(item.deducted())
            .build();
    }
}
