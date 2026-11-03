package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 결제 반영의 재선점 증거 (#308 PR-C1) — 결제당 한 행. 만료 뒤 결제가 같은 주문 아이디로 재선점해 새 선점 아이디를 받았을 때 <b>확정 호출 전에</b> 남긴다.
 * 확정 뒤 DB 기록 전에 끊기고 주문이 취소되면 재전달이 이 선점 아이디로 해제 프로브를 불러 확정 선점을 찾는다({@code order-flow-guide.md} §3-6).
 * 첫 확정 대상(주문의 {@code stock_reservation_id})은 이미 주문 행에 있어 여기 남기지 않는다.
 *
 * <p>읽기는 PK({@code payment_id}) 한 행뿐이라 다른 인덱스를 두지 않는다. 지우지 않는다 — 결제당 한 행이고 결제 반영 뒤에는 읽지 않는다.
 */
@Entity
@Table(name = "order_stock_confirmation", comment = "결제 반영의 재선점 증거 (재선점 새 선점 아이디를 확정 전에 기록, 결제당 1행)")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderStockConfirmationEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "payment_id", comment = "결제 아이디 (외부 FK: payment-service payment.id)")
    private Long paymentId;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "stock_reservation_id", nullable = false, comment = "확정하려는 재선점 아이디 (외부 FK: product-service stock_reservation.id)")
    private Long stockReservationId;

    @Column(name = "event_id", nullable = false, comment = "기록한 payment-approved 이벤트 아이디 (봉투 eventId)")
    private UUID eventId;

    /**
     * 앞선 재선점이 확정 전에 다시 만료돼 더 새 선점을 받았다 — 앞선 선점은 확정되지 않았다.
     * {@code @Version} 을 두지 않는다 — reserve 는 같은 주문의 활성 선점(RESERVED · CONFIRMED)을 같은 아이디로 돌려주므로 값이 달라지는 경우는
     * 앞 선점이 만료돼 더는 확정될 수 없을 때뿐이다.
     */
    public void changeReservation(long newStockReservationId, UUID newEventId) {
        this.stockReservationId = newStockReservationId;
        this.eventId = newEventId;
    }

    @Override
    public Long getId() {
        return paymentId;
    }

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다(아이디를 직접 넣어 merge 의 SELECT 를 피한다). */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
