package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 같은 선점 후보로 확정 요청을 보내는 추가 결제의 영속 증거. */
@Entity
@Table(name = "order_stock_confirmation_participant", comment = "동일 재고 확정 후보의 추가 참여 결제")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStockConfirmationParticipantEntity {

    @EmbeddedId
    private Key key;

    public OrderStockConfirmationParticipantEntity(Key key) {
        this.key = key;
    }

    @Embeddable
    public record Key(
        @Column(name = "work_id", comment = "확정 후보 아이디 (FK: order_stock_restoration.id)") Long workId,
        @Column(name = "payment_id", comment = "참여 결제 아이디 (외부 FK: payment-service payment.id)") Long paymentId
    ) implements Serializable {
    }
}
