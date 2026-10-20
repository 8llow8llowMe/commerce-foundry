package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockReservationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.StockRestorationItemInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.application.info.StockReservationInfo;
import com.commercefoundry.domainlayer.product.application.info.StockRestorationInfo;
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

    public StockRestorationResponse toResponse(StockRestorationInfo restoration) {
        return StockRestorationResponse.builder()
            .restorationId(String.valueOf(restoration.restorationId()))
            .claimId(String.valueOf(restoration.claimId()))
            .reservationId(String.valueOf(restoration.reservationId()))
            .reason(restoration.reason().name())
            .items(restoration.items().stream().map(this::toItem).toList())
            .build();
    }

    private StockReservationItemInternalItem toItem(StockReservationInfo.Item item) {
        return StockReservationItemInternalItem.builder()
            .skuId(String.valueOf(item.skuId()))
            .quantity(item.quantity())
            .deducted(item.deducted())
            .build();
    }

    private StockRestorationItemInternalItem toItem(StockRestorationInfo.Item item) {
        return StockRestorationItemInternalItem.builder()
            .skuId(String.valueOf(item.skuId()))
            .quantity(item.quantity())
            .restored(item.restored())
            .build();
    }
}
