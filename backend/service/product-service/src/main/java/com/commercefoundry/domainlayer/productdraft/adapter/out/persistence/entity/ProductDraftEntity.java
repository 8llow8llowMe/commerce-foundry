package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * AI 상품 등록 초안 (#359). 운영자 입력(사실 · 이미지)은 {@code input_json} 한 칸에 둔다 — 초안 단위로만 읽고 칸별로 찾지 않는다
 * (선례: {@code operations.job.payload_json}). 고객 개인정보를 담지 않는다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 한다 — Snowflake 아이디를 직접 넣는 엔티티라 기본 {@code save} 는 merge 로 간다
 * ({@code coding-conventions.md} §9-1). 상태 변경은 읽어 온 관리 엔티티를 {@link #decide} 로 고치고, {@code @Version} 이 동시 변경을 막는다.
 * 생성 결과 · 적용 기록 칸은 그 기능의 PR 에서 더한다.
 */
@Entity
@Table(
    name = "product_draft",
    comment = "AI 상품 등록 초안",
    check = {
        @CheckConstraint(
            name = "ck_product_draft_rejected",
            constraint = "(status = 'REJECTED') = (rejected_by_member_id is not null and rejected_at is not null)"
        ),
        @CheckConstraint(name = "ck_product_draft_input_json_length", constraint = "char_length(input_json) <= 32768")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductDraftEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "초안 아이디 (Snowflake)")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16,
        comment = "초안 상태 (REQUESTED / GENERATING / READY / FAILED / APPLIED / REJECTED / EXPIRED). 만료는 읽을 때 expires_at 으로 판정한다")
    private ProductDraftStatus status;

    @Column(name = "requested_by_member_id", nullable = false, comment = "초안을 만든 어드민 회원 아이디 (auth member.id, 서비스 밖 참조)")
    private long requestedByMemberId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "input_json", nullable = false, comment = "운영자 입력(사실 · 이미지) JSON — 고객 개인정보 없음, 32768자 이하")
    private String inputJson;

    @Column(name = "rejected_by_member_id", comment = "거절한 어드민 회원 아이디. 거절 전에는 null")
    private Long rejectedByMemberId;

    @Column(name = "rejected_at", comment = "거절 시각. 거절 전에는 null")
    private Instant rejectedAt;

    @Column(name = "expires_at", nullable = false, comment = "보관 만료 시각 — 지나면 적용 · 거절할 수 없다")
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;

    /** 상태 · 거절 칸을 바꾼다. 판정은 호출한 쪽이 끝냈다. */
    public void decide(ProductDraftStatus status, Long rejectedByMemberId, Instant rejectedAt) {
        this.status = status;
        this.rejectedByMemberId = rejectedByMemberId;
        this.rejectedAt = rejectedAt;
    }

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
