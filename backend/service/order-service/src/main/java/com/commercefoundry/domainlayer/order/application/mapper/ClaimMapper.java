package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimItemEntity;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import java.util.List;
import org.springframework.stereotype.Component;

/** 감사 시각은 쓰기 매핑에 싣지 않는다. 최초 응답도 DB에서 다시 읽은 생성 시각을 쓴다. */
@Component
public class ClaimMapper {
    public ClaimEntity toEntity(Claim claim) {
        return ClaimEntity.builder().id(claim.id()).orderId(claim.orderId()).requestKey(claim.requestKey()).type(claim.type()).status(claim.status())
            .expectedRefundAmount(claim.expectedRefund() == null ? null : claim.expectedRefund().amount())
            .expectedRefundCurrency(claim.expectedRefund() == null ? null : claim.expectedRefund().currency())
            .paymentId(claim.paymentId()).attemptCount(claim.attemptCount()).nextAttemptAt(claim.nextAttemptAt()).failureCode(claim.failureCode())
            .version(claim.version()).build();
    }

    public List<ClaimItemEntity> toItemEntities(Claim claim) {
        return claim.items().stream().map(item -> ClaimItemEntity.builder().id(item.id()).claimId(claim.id()).orderItemId(item.orderItemId())
            .quantity(item.quantity()).reason(item.reason()).build()).toList();
    }

    public Claim toDomain(ClaimEntity entity, List<ClaimItemEntity> items) {
        return new Claim(entity.getId(), entity.getOrderId(), entity.getRequestKey(), entity.getType(), entity.getStatus(),
            MoneyColumns.nullable(entity.getExpectedRefundAmount(), entity.getExpectedRefundCurrency()), entity.getPaymentId(), entity.getAttemptCount(),
            entity.getNextAttemptAt(), entity.getFailureCode(),
            items.stream().map(item -> new ClaimItem(item.getId(), item.getOrderItemId(), item.getQuantity(), item.getReason())).toList(),
            entity.getCreatedAt(), entity.getVersion());
    }
}
