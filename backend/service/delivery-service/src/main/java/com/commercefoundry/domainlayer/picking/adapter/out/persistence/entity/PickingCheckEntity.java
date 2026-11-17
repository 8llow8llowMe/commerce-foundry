package com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
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
 * 포장 검수 기록 (append-only). {@code attempt_no} 는 묶음 안 순번(1부터)이고 묶음 행의 {@code check_count} 와 같은 값이다 — 버전 비교를 지나 같은 순번이 두 번
 * 들어오면 {@code uk_picking_check_batch_id_attempt_no} 가 막는다(이중 방어). 유일 키가 {@code batch_id} 로 시작해 묶음별 시간순 조회 인덱스를 겸한다.
 * 검수 성공은 출고가 아니다. append-only 라 {@link Persistable} 로 persist 한다.
 */
@Entity
@Table(
    name = "picking_check",
    comment = "포장 검수 기록 (append-only) — 검수 성공은 출고가 아니다",
    uniqueConstraints = @UniqueConstraint(name = "uk_picking_check_batch_id_attempt_no", columnNames = {"batch_id", "attempt_no"}),
    check = @CheckConstraint(name = "ck_picking_check_attempt_no", constraint = "attempt_no >= 1")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PickingCheckEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "검수 기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "batch_id", nullable = false, comment = "출고 묶음 아이디 (FK: picking_batch.id)")
    private Long batchId;

    @Column(name = "attempt_no", nullable = false, comment = "묶음 안 검수 순번 (1부터) — 묶음 행의 check_count 와 같다")
    private int attemptNo;

    @Column(name = "order_id", nullable = false, comment = "검수한 주문 아이디 (외부 FK: order-service orders.id) — 묶음 밖 주문이면 실패 기록이다")
    private Long orderId;

    @Column(name = "quantities_key", nullable = false, columnDefinition = "text",
        comment = "보낸 수량 — 상품 옵션 오름차순 옵션:수량 을 쉼표로 이은 값. 같은 주문 같은 수량 재요청을 알아보는 키")
    private String quantitiesKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure", nullable = false, length = 32, comment = "검수 결과 (PickingCheck.Failure). NONE 이 성공")
    private PickingCheck.Failure failure;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
