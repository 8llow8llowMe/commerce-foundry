package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.security.principal.MemberRole;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객과 관리자 계정이 한 테이블이다 (#4 결정 4). 역할 enum 은 security-core {@link MemberRole} 을 그대로 쓴다 — 저장한 값이 곧 access 토큰의
 * {@code role} claim 이라 이름이 두 곳에서 갈라지지 않게. 역할을 더하면 {@code role} check 제약도 바꾼다 ({@code coding-conventions.md} §9-8).
 *
 * <p>이메일은 정규화(앞뒤 공백 제거 + 소문자)한 값만 들어온다 — 유니크 키가 대소문자만 다른 중복을 막으려면 저장 전에 맞춰야 한다.
 * 애플리케이션을 거치지 않은 쓰기(이관 #19 · 수동 SQL)도 막도록 {@code ck_member_email_normalized} 가 소문자를 강제한다.
 */
@Entity
@Table(
    name = "member",
    comment = "회원 (고객 · 관리자)",
    uniqueConstraints = @UniqueConstraint(name = MemberEntity.UK_EMAIL, columnNames = "email"),
    check = @CheckConstraint(name = "ck_member_email_normalized", constraint = "email = lower(email)")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberEntity extends BaseEntity {

    /** 동시 가입이 사전 조회를 함께 지나면 이 키가 막는다 — 어댑터가 위반을 이메일 중복(AUTH_002)으로 바꾼다. */
    public static final String UK_EMAIL = "uk_member_email";

    @Id
    @Column(name = "id", comment = "회원 아이디 (Snowflake)")
    private Long id;

    @Column(name = "email", nullable = false, length = 254, comment = "로그인 이메일 — 앞뒤 공백 제거 + 소문자로 정규화한 값")
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100, comment = "비밀번호 BCrypt 해시. 원문은 저장하지 않는다")
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 50, comment = "이름")
    private String name;

    @Column(name = "phone", length = 20, comment = "휴대폰 번호 — 숫자만 (01012345678). 없으면 null")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, comment = "역할 (CUSTOMER / ADMIN) — access 토큰 role claim 값")
    private MemberRole role;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
