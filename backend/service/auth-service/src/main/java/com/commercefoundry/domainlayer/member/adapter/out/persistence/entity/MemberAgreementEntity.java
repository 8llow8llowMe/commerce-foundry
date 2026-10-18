package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 동의 이력 — 추가만 한다(append-only). 고치거나 지우지 않으므로 {@code BaseEntity} 를 상속하지 않고 {@code created_at} 하나만 둔다.
 * {@link Immutable} 이라 Hibernate 도 이 행을 UPDATE 하지 않는다. 현재 상태는 그 회원 · 유형의 가장 최근 행이다
 * ({@code created_at} 내림차순 → 아이디 내림차순).
 *
 * <p>아이디를 직접 넣는 엔티티라 Spring Data 의 {@code save} 가 새 행을 merge(SELECT 후 INSERT)로 다룬다. {@link Persistable} 로
 * "아직 감사 시각이 없으면 새 행" 이라고 알려 SELECT 없이 persist 한다.
 */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "member_agreement",
    comment = "회원 동의 이력 (추가만 한다 — 현재 상태는 회원 · 유형별 최근 행)",
    indexes = @Index(name = "idx_member_agreement_member_id_agreement_type", columnList = "member_id, agreement_type")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberAgreementEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "동의 이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (FK: member.id)")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_type", nullable = false, length = 30, comment = "동의 유형 (TERMS_OF_SERVICE / PRIVACY_POLICY / MARKETING)")
    private AgreementType agreementType;

    @Column(name = "agreed", nullable = false, comment = "동의 여부 — false 는 거절 · 철회")
    private boolean agreed;

    @Column(name = "terms_version", nullable = false, length = 20, comment = "동의 당시 시행 중이던 약관 버전 (auth.agreement.versions.*)")
    private String termsVersion;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, comment = "동의 · 철회 시각")
    private Instant createdAt;

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
