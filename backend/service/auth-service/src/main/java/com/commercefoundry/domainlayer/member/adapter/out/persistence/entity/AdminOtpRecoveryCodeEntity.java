package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 관리자 OTP 복구 코드 (#238) — 한 번 발급에 8행. 원문은 저장하지 않고 정규화한 값의 BCrypt 해시만 둔다.
 * 사용은 {@code used_at} 을 조건부 UPDATE({@code used_at is null})로 채우는 것 하나다 — 같은 코드를 두 요청이 동시에 써도 한쪽만 성공한다.
 * 재발급 · 해제는 회원 단위로 지우고 다시 넣는다. 고치는 칸이 {@code used_at} 하나라 {@code BaseEntity} 를 상속하지 않는다.
 *
 * <p>아이디를 직접 넣는 엔티티라 {@link Persistable} 로 "감사 시각이 없으면 새 행" 이라고 알려 SELECT 없이 persist 한다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "admin_otp_recovery_code",
    comment = "관리자 OTP 복구 코드 (1회용, BCrypt 해시)",
    indexes = @Index(name = "idx_admin_otp_recovery_code_member_id", columnList = "member_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminOtpRecoveryCodeEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "복구 코드 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "관리자 회원 아이디 (FK: member.id)")
    private Long memberId;

    /** {@code toString} 에 싣지 않는다 — 오프라인 대입의 입력이 된다. Lombok 빌더 {@code toString} 은 이 표시를 보지 않아 아래에서 직접 가린다. */
    @ToString.Exclude
    @Column(name = "code_hash", nullable = false, length = 100, comment = "정규화한 복구 코드(대문자 10자)의 BCrypt 해시. 원문은 저장하지 않는다")
    private String codeHash;

    @Column(name = "used_at", comment = "사용 시각. 쓰지 않았으면 null")
    private Instant usedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, comment = "발급 시각")
    private Instant createdAt;

    /** Lombok 이 나머지를 채운다. {@code toString} 만 직접 둬 해시를 가린다. */
    public static class AdminOtpRecoveryCodeEntityBuilder {

        @Override
        public String toString() {
            return "AdminOtpRecoveryCodeEntityBuilder(id=" + id + ", memberId=" + memberId + ", codeHash=****, usedAt=" + usedAt + ", createdAt=" + createdAt + ")";
        }
    }

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
