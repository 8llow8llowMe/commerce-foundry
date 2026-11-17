package com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출고 묶음(피킹 리스트) 머리 행. 담은 주문 · 제외 · 수량 · 검수 기록은 자식 테이블이 묶음 아이디로 갖는다.
 *
 * <p>검수 기록을 더할 때마다 {@code check_count} 를 올려 이 행을 버전 비교로 갱신한다({@code @Version}) — 같은 묶음의 검수 두 건이 같은 버전에서 갈라지면
 * 하나만 저장되고, 진 쪽은 자기 검수 기록까지 롤백된다. 새 행은 버전이 null 이라 persist(INSERT), 읽어 온 행은 merge(버전 비교 UPDATE)다.
 */
@Entity
@Table(
    name = "picking_batch",
    comment = "출고 묶음(피킹 리스트) — 검수 완료는 출고 완료가 아니다",
    check = @CheckConstraint(name = "ck_picking_batch_check_count", constraint = "check_count >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PickingBatchEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "출고 묶음 아이디 (Snowflake)")
    private Long id;

    @Column(name = "check_count", nullable = false, comment = "검수 기록 수 — 기록을 더할 때마다 올려 버전 비교 갱신을 일으킨다")
    private int checkCount;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
