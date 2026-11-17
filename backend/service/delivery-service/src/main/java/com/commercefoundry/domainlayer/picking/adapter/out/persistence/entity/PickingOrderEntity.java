package com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion;
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
import org.springframework.data.domain.Persistable;

/**
 * 출고 묶음 요청에 들어온 주문 한 건 — 담은 주문(대상)과 사유와 함께 뺀 주문을 같은 테이블에 둔다. {@code exclusion_reason} 이 null 이면 대상이다.
 * 요청 주문마다 정확히 한 행이라({@code uk_picking_order_batch_id_order_id}) 대상 · 제외가 한 묶음 안에서 겹치지 않는다. 유일 키가 {@code batch_id} 로 시작해
 * 묶음별 조회 인덱스를 겸한다.
 *
 * <p>대상 행만 생성 때 주문 상태 · 배송 국가 스냅숏을 갖는다. 개인정보(받는 사람 · 주소 · 연락처) 컬럼은 두지 않는다. append-only 라 {@link Persistable} 로 persist 한다.
 */
@Entity
@Table(
    name = "picking_order",
    comment = "출고 묶음 요청 주문 — 대상과 제외(사유) 주문",
    uniqueConstraints = @UniqueConstraint(name = "uk_picking_order_batch_id_order_id", columnNames = {"batch_id", "order_id"}),
    check = @CheckConstraint(
        name = "ck_picking_order_target_snapshot",
        constraint = "(exclusion_reason is null and order_status is not null and country_code is not null)"
            + " or (exclusion_reason is not null and order_status is null and country_code is null)"
    )
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PickingOrderEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "출고 묶음 주문 아이디 (Snowflake)")
    private Long id;

    @Column(name = "batch_id", nullable = false, comment = "출고 묶음 아이디 (FK: picking_batch.id)")
    private Long batchId;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service orders.id)")
    private Long orderId;

    @Column(name = "order_status", length = 32, comment = "묶음 생성 때 주문 상태 코드 — 대상 주문에만 있다")
    private String orderStatus;

    @Column(name = "country_code", length = 2, comment = "묶음 생성 때 배송 국가 코드 — 대상 주문에만 있다")
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "exclusion_reason", length = 32, comment = "제외 사유 (PickingExclusion.Reason). null 이면 묶음에 담은 대상 주문")
    private PickingExclusion.Reason exclusionReason;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
