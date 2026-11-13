package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimItemEntity;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.springframework.stereotype.Component;

/** 감사 시각은 쓰기 매핑에 싣지 않는다. 최초 응답도 DB에서 다시 읽은 생성 시각을 쓴다. */
@Component
public class ClaimMapper {
    public ClaimEntity toEntity(Claim claim) {
        Claim.ReturnApproval approval = claim.approval();
        return ClaimEntity.builder().id(claim.id()).orderId(claim.orderId()).requestKey(claim.requestKey()).type(claim.type()).status(claim.status())
            .expectedRefundAmount(claim.expectedRefund() == null ? null : claim.expectedRefund().amount())
            .expectedRefundCurrency(claim.expectedRefund() == null ? null : claim.expectedRefund().currency())
            .paymentId(claim.paymentId()).attemptCount(claim.attemptCount()).nextAttemptAt(claim.nextAttemptAt()).failureCode(claim.failureCode())
            .decisionReason(claim.decision() == null ? null : claim.decision().reason())
            .decidedByMemberId(claim.decision() == null ? null : claim.decision().decidedByMemberId())
            .decidedAt(claim.decision() == null ? null : claim.decision().decidedAt())
            .decisionFailureCode(claim.decision() == null ? null : claim.decision().previousFailureCode())
            .approvedAt(approval == null ? null : approval.approvedAt())
            .approvedByMemberId(approval == null ? null : approval.approvedByMemberId())
            .restock(approval == null ? null : approval.restock())
            .deductionAmount(approval == null ? null : approval.deduction().amount()).version(claim.version()).build();
    }

    public List<ClaimItemEntity> toItemEntities(Claim claim) {
        return claim.items().stream().map(item -> ClaimItemEntity.builder().id(item.id()).claimId(claim.id()).orderItemId(item.orderItemId())
            .quantity(item.quantity()).reason(item.reason()).build()).toList();
    }

    public Claim toDomain(ClaimEntity entity, List<ClaimItemEntity> items) {
        return new Claim(entity.getId(), entity.getOrderId(), entity.getRequestKey(), entity.getType(), entity.getStatus(),
            MoneyColumns.nullable(entity.getExpectedRefundAmount(), entity.getExpectedRefundCurrency()), entity.getPaymentId(), entity.getAttemptCount(),
            entity.getNextAttemptAt(), entity.getFailureCode(), entity.getDecisionReason() == null ? null : new Claim.Decision(entity.getDecisionReason(),
                entity.getDecidedByMemberId(), entity.getDecidedAt(), entity.getDecisionFailureCode()), approval(entity),
            items.stream().map(item -> new ClaimItem(item.getId(), item.getOrderItemId(), item.getQuantity(), item.getReason())).toList(),
            entity.getCreatedAt(), entity.getVersion());
    }

    /** 승인 칸 넷은 함께 있거나 함께 없다({@code ck_claim_return_approval}). 차감액 통화는 환불 통화다 — 승인 이후 반품은 환불액이 늘 있다. */
    private static Claim.ReturnApproval approval(ClaimEntity entity) {
        if (entity.getApprovedAt() == null) {
            return null;
        }
        return new Claim.ReturnApproval(entity.getRestock(), Money.of(entity.getDeductionAmount(), entity.getExpectedRefundCurrency()),
            entity.getApprovedByMemberId(), entity.getApprovedAt());
    }
}
