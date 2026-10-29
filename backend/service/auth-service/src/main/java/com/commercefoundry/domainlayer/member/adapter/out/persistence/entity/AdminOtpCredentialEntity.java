package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
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
import lombok.ToString;

/**
 * 확정된 관리자 OTP 자격 (#238) — 회원당 한 행. 미확정 등록은 Redis 에만 둔다.
 *
 * <p>비밀은 AES-256-GCM 암호문(base64, IV ‖ 암호문 ‖ 태그)으로만 저장한다 — {@code AdminOtpSecretCipher}, AAD 는 {@code admin-otp:v1:credential:{memberId}:{id}}.
 * {@code key_id} 는 암호화한 키의 식별자이고 어댑터만 다룬다(도메인에 없다).
 *
 * <p>{@code last_used_time_step} 은 같은 코드 재사용을 막는 기준이고 조건부 UPDATE({@code id = ? and last_used_time_step < ?})로만 올린다 —
 * 상태가 아닌 칸이라 "도메인 전이 → merge" 대신 조건부 UPDATE 한 문장으로 바꾼다({@code coding-conventions.md} §9-1 예외). 그 UPDATE 도 버전을 올린다.
 *
 * <p>암호문은 {@code toString} 에 싣지 않는다 — 필드의 {@code @ToString.Exclude} 는 엔티티에 {@code @ToString} 을 붙일 때를 위한 것이고,
 * Lombok 빌더의 {@code toString} 은 그 표시를 보지 않아(실측) 빌더 {@code toString} 을 직접 둔다.
 */
@Entity
@Table(
    name = "admin_otp_credential",
    comment = "관리자 OTP 자격 (확정된 등록만, 회원당 하나)",
    uniqueConstraints = @UniqueConstraint(name = AdminOtpCredentialEntity.UK_MEMBER_ID, columnNames = "member_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminOtpCredentialEntity extends BaseEntity {

    /** 동시 등록 확인이 둘 다 지나가면 이 키가 막는다 — 어댑터가 위반을 {@code AUTH_037} 로 바꾼다. */
    public static final String UK_MEMBER_ID = "uk_admin_otp_credential_member_id";

    @Id
    @Column(name = "id", comment = "OTP 자격 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "관리자 회원 아이디 (FK: member.id)")
    private Long memberId;

    @ToString.Exclude
    @Column(name = "secret_ciphertext", nullable = false, length = 128, comment = "TOTP 비밀 AES-256-GCM 암호문 — base64(IV ‖ 암호문 ‖ 태그), AAD 는 용도 · 회원 아이디 · 자격 아이디")
    private String secretCiphertext;

    @Column(name = "key_id", nullable = false, length = 16, comment = "암호화 키 식별자 — base64url(SHA-256(키) 앞 8바이트). 회전 판단용")
    private String keyId;

    @Column(name = "last_used_time_step", nullable = false, comment = "마지막으로 받은 TOTP step (Unix 초 / 30). 이 값 이하의 코드는 다시 받지 않는다")
    private Long lastUsedTimeStep;

    @Column(name = "confirmed_at", nullable = false, comment = "등록 확인 시각")
    private Instant confirmedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;

    /** Lombok 이 나머지를 채운다. {@code toString} 만 직접 둬 암호문을 가린다. */
    public static class AdminOtpCredentialEntityBuilder {

        @Override
        public String toString() {
            return "AdminOtpCredentialEntityBuilder(id=" + id + ", memberId=" + memberId + ", secretCiphertext=****, keyId=" + keyId
                + ", lastUsedTimeStep=" + lastUsedTimeStep + ", confirmedAt=" + confirmedAt + ", version=" + version + ")";
        }
    }
}
