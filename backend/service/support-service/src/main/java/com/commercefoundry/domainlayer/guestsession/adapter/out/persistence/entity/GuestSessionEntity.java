package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "support_guest_session", comment = "비회원 상담 자격 증명",
    uniqueConstraints = @UniqueConstraint(name = "uk_support_guest_session_token_hash", columnNames = "token_hash"),
    check = @CheckConstraint(name = "ck_support_guest_session_state", constraint = "id > 0 and length(token_hash) = 43"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GuestSessionEntity extends BaseEntity {
    @Id
    @Column(comment = "비회원 세션 아이디 (Snowflake)")
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 43, comment = "SHA-256 자격 증명 지문, 토큰 원문 미저장")
    private String tokenHash;

    @Column(nullable = false, comment = "자격 증명 절대 만료 시각 (UTC)")
    private Instant expiresAt;

    @Column(comment = "자격 증명 폐기 시각 (UTC)")
    private Instant revokedAt;

    @Version
    @Column(nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
