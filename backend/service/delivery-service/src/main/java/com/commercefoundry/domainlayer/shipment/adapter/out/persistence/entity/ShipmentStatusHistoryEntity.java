package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentChangedBy;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 배송 상태 이력 — append-only 다. {@code created_at} 이 바뀐 시각이다. 송장별 시간순({@code where shipment_id = ? order by id})으로 읽으므로 {@code (shipment_id, id)} 인덱스를 둔다.
 *
 * <p>행을 고치지 않는다. 지우는 경우는 하나 — 주문에 반영되지 못한 대기 송장을 지울 때 그 이력도 같은 트랜잭션에서 지운다(출고된 적 없는 송장이다).
 * {@code @Immutable} 을 달지 않는 이유가 그 삭제(JPQL bulk DELETE)다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 하게 한다 — 아이디(Snowflake)를 직접 넣는 엔티티라 기본 {@code save} 는 merge 로 가서 SELECT 를 한 번 더 보낸다.
 */
@Entity
@Table(
    name = "shipment_status_history",
    comment = "배송 상태 이력 (append-only)",
    indexes = @Index(name = "idx_shipment_status_history_shipment_id_id", columnList = "shipment_id, id"),
    check = @CheckConstraint(name = "ck_shipment_status_history_admin_actor", constraint = "changed_by <> 'ADMIN' or changed_by_id is not null")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShipmentStatusHistoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "배송 상태 이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "shipment_id", nullable = false, comment = "송장 아이디 (FK: shipment.id)")
    private Long shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, comment = "바뀌기 전 배송 상태. 등록 줄이면 null")
    private ShipmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, comment = "바뀐 배송 상태")
    private ShipmentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by", nullable = false, length = 20, comment = "변경 주체 (ADMIN · SYSTEM)")
    private ShipmentChangedBy changedBy;

    @Column(name = "changed_by_id", comment = "바꾼 관리자 회원 아이디 (외부 FK: auth-service member.id) — access 토큰의 memberId. 자동 처리면 null")
    private Long changedById;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
