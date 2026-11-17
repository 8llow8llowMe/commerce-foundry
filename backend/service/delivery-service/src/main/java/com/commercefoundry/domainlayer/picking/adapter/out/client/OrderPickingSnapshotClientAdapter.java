package com.commercefoundry.domainlayer.picking.adapter.out.client;

import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.OrderPickingClient;
import com.commercefoundry.domainlayer.picking.adapter.out.client.support.OrderPickingInternalResponseSupport;
import com.commercefoundry.domainlayer.picking.application.port.out.OrderPickingSnapshotQueryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 요청 주문 전체를 한 번의 벌크 호출로 읽는다 — 주문마다 부르지 않는다. */
@Component
@RequiredArgsConstructor
public class OrderPickingSnapshotClientAdapter implements OrderPickingSnapshotQueryPort {

    private final OrderPickingClient orderPickingClient;
    private final OrderPickingInternalResponseSupport orderPickingInternalResponseSupport;

    @Override
    public List<PickingSnapshot> findAll(List<Long> orderIds) {
        return orderPickingInternalResponseSupport.read(orderIds, () -> orderPickingClient.findAll(orderIds));
    }
}
