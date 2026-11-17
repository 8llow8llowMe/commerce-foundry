package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.port.out.OrderPickingReadPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderPickingQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderPickingReadAdapter implements OrderPickingReadPort {
    private final EntityManager entityManager;

    @Override
    public List<OrderPickingQueryResult> findAll(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        // 명시 projection 한 번으로 헤더·국가·항목을 같은 DB 문장 시점에 읽는다. 개인정보 컬럼은 SELECT하지 않는다.
        List<Tuple> rows = entityManager.createQuery("""
            select o.id as orderId, o.status as status, a.countryCode as countryCode,
                   i.productId as productId, i.skuId as skuId, i.quantity as quantity, i.customOrder as customOrder
            from OrderEntity o
            left join OrderShippingAddressEntity a on a.orderId = o.id
            left join OrderItemEntity i on i.orderId = o.id
            where o.id in :orderIds
            order by o.id, i.lineNo
            """, Tuple.class).setParameter("orderIds", orderIds).getResultList();
        Map<Long, List<Tuple>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.get("orderId", Long.class), ignored -> new ArrayList<>()).add(row));
        return grouped.entrySet().stream().map(entry -> {
            Tuple first = entry.getValue().getFirst();
            List<OrderPickingQueryResult.Line> lines = entry.getValue().stream().filter(row -> row.get("skuId") != null)
                .map(row -> new OrderPickingQueryResult.Line(row.get("productId", Long.class), row.get("skuId", Long.class),
                    ((Number) row.get("quantity")).longValue(), row.get("customOrder", Boolean.class))).toList();
            return new OrderPickingQueryResult(entry.getKey(), first.get("status", OrderStatus.class), first.get("countryCode", String.class), lines);
        }).toList();
    }
}
