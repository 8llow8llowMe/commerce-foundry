package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "social_identity", comment = "소셜 로그인 식별자 — 이메일 자동 연결 금지",
    uniqueConstraints = {
        @UniqueConstraint(name = SocialIdentityEntity.UK_PROVIDER_SUBJECT, columnNames = {"provider", "subject"}),
        @UniqueConstraint(name = SocialIdentityEntity.UK_MEMBER_PROVIDER, columnNames = {"member_id", "provider"})},
    indexes = @Index(name = "idx_social_identity_member_id", columnList = "member_id"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SocialIdentityEntity extends BaseEntity {
    public static final String UK_MEMBER_PROVIDER = "uk_social_identity_member_id_provider";
    public static final String UK_PROVIDER_SUBJECT = "uk_social_identity_provider_subject";

    @Id
    @Column(name = "id", comment = "소셜 식별자 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (FK: member.id)")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20, comment = "소셜 제공자 (KAKAO / NAVER)")
    private SocialProvider provider;

    @Column(name = "subject", nullable = false, length = 255, comment = "제공자가 발급한 사용자 식별자")
    private String subject;
}