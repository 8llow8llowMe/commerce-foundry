package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

@Entity
@Immutable
@Table(name = "claim_item", comment = "클레임 항목 스냅숏",
    uniqueConstraints = @UniqueConstraint(name = "uk_claim_item_claim_id_order_item_id", columnNames = {"claim_id", "order_item_id"}),
    check = {
        @CheckConstraint(name = "ck_claim_item_identifiers_positive", constraint = "id > 0 and claim_id > 0 and order_item_id > 0"),
        @CheckConstraint(name = "ck_claim_item_quantity_positive", constraint = "quantity > 0")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ClaimItemEntity extends BaseEntity implements Persistable<Long> {
    @Id
    @Column(name = "id", comment = "클레임 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "claim_id", nullable = false, comment = "클레임 아이디 (FK: claim.id)")
    private Long claimId;

    @Column(name = "order_item_id", nullable = false, comment = "주문 항목 아이디 (FK: order_item.id)")
    private Long orderItemId;

    @Column(name = "quantity", nullable = false, comment = "클레임 수량")
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20,
        comment = "접수 사유 (반품 CHANGE_OF_MIND / DEFECTIVE / WRONG_ITEM, 취소 CHANGE_OF_MIND / ORDER_MISTAKE / PAYMENT_ISSUE / OTHER)")
    private ClaimItem.Reason reason;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
