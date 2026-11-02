package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.QShipmentEntity.shipmentEntity;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * 조건은 완전 일치다. 주문은 {@code uk_shipment_order_id}, 상태는 {@code idx_shipment_status_id}, 반영 여부 false 는 {@code idx_shipment_order_synced_id} 와
 * {@code idx_shipment_completion_pending_id} 의 BitmapOr 로 읽는다(둘 다 드문 값). 반영 여부 true · 조건 없음은 대부분의 행이라 PK 를 거꾸로 훑어 한 페이지에서 멈춘다.
 * 건수 쿼리도 같은 조건이다.
 */
@RequiredArgsConstructor
public class ShipmentCustomRepositoryImpl implements ShipmentCustomRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<ShipmentEntity> search(Long orderId, ShipmentStatus status, Boolean synced, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        BooleanExpression[] filters = {orderIs(orderId), statusIs(status), syncedIs(synced)};
        List<ShipmentEntity> content = queryFactory
            .selectFrom(shipmentEntity)
            .where(filters)
            .orderBy(shipmentEntity.id.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = queryFactory.select(shipmentEntity.count()).from(shipmentEntity).where(filters).fetchOne();
            return total == null ? 0L : total;
        });
    }

    private static BooleanExpression orderIs(Long orderId) {
        return orderId == null ? null : shipmentEntity.orderId.eq(orderId);
    }

    private static BooleanExpression statusIs(ShipmentStatus status) {
        return status == null ? null : shipmentEntity.status.eq(status);
    }

    /** 반영됨 = 출고 반영 완료이고 배송 완료 반영 대기가 아님. 반영 안 됨은 그 부정이다. */
    private static BooleanExpression syncedIs(Boolean synced) {
        if (synced == null) {
            return null;
        }
        return synced
            ? shipmentEntity.orderSynced.isTrue().and(shipmentEntity.completionPending.isFalse())
            : shipmentEntity.orderSynced.isFalse().or(shipmentEntity.completionPending.isTrue());
    }
}
