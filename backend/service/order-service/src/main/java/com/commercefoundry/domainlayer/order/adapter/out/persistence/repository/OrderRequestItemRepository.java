package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderRequestItemEntity;
import com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRequestItemRepository extends JpaRepository<OrderRequestItemEntity, Long> {

    /**
     * 회원의 진행 중 기록(처리 중 · lease 가 {@code now} 뒤 · 마지막 활동이 {@code since} 이후, {@code excludedRequestId} 제외)에 담긴 상품별 수량 합 (#436).
     * 기록은 {@code idx_order_request_member_id_updated_at} 범위, 항목은 {@code uk_order_request_item_order_request_id_product_id} 의 선두 {@code order_request_id} 로 찾는다 —
     * 회원 한 명의 창 안 기록은 #231 상한 안팎이라 읽는 행이 적다.
     */
    @Query("""
        select new com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult(i.productId, sum(i.quantity))
          from OrderRequestEntity r, OrderRequestItemEntity i
         where i.orderRequestId = r.id and r.memberId = :memberId and r.id <> :excludedRequestId and r.status = :processing
           and r.leaseExpiresAt > :now and r.updatedAt >= :since and i.productId in :productIds
         group by i.productId
        """)
    List<PendingProductQuantityQueryResult> sumInFlightQuantityByProduct(
        long memberId, Collection<Long> productIds, long excludedRequestId, OrderRequest.Status processing, Instant now, Instant since
    );
}
