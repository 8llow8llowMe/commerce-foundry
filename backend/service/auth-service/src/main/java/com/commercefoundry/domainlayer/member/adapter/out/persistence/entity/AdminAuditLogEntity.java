package com.commercefoundry.domainlayer.member.adapter.out.persistence.entity;

import jakarta.persistence.CheckConstraint;
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
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 관리자 감사 로그 (#192) — 추가만 한다(append-only). {@code admin.audit} 로거 한 줄과 같은 네 칸에 시각을 더한다 ({@code security-guide.md} §11-4).
 * 고치거나 지우지 않으므로 {@code BaseEntity} 를 상속하지 않고 {@code created_at} 하나만 둔다 — {@code MemberAgreementEntity} 와 같은 방식이다.
 * {@link Immutable} 이라 Hibernate 도 이 행을 UPDATE 하지 않는다. 보관 기간이 지난 행은 {@code AdminAuditLogRetentionScheduler} 가 아이디 범위로 지운다.
 *
 * <p>행위({@code action})는 문자열이다 — enum 칼럼이면 행위를 더할 때마다 check 제약을 바꾸는 운영 DDL 이 생긴다. 결과는 둘로 고정이라 check 로 막는다.
 *
 * <p>인덱스 — 최신순 전체는 PK 역순, 행위 · 관리자 · 대상 조건은 {@code (조건, id)} 로 아이디 내림차순을 인덱스에서 얻는다.
 * 대상 인덱스는 한 관리자 계정을 노린 로그인 실패({@code target=member:<id>})를 고를 때 쓴다.
 */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "admin_audit_log",
    comment = "관리자 감사 로그 (추가만 한다 — admin.audit 로거 한 줄과 같은 네 칸)",
    indexes = {
        @Index(name = "idx_admin_audit_log_action_id", columnList = "action, id"),
        @Index(name = "idx_admin_audit_log_actor_member_id_id", columnList = "actor_member_id, id"),
        @Index(name = "idx_admin_audit_log_target_id", columnList = "target, id")
    },
    check = @CheckConstraint(name = "ck_admin_audit_log_result", constraint = "result in ('SUCCESS', 'FAILURE')")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminAuditLogEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "감사 로그 아이디 (Snowflake — 시각순)")
    private Long id;

    @Column(name = "action", nullable = false, length = 40, comment = "행위 (ADMIN_LOGIN / MEMBER_SEARCH …) — 로그 검색 키와 같은 이름")
    private String action;

    @Column(name = "actor_member_id", comment = "행위한 관리자 회원 아이디 (FK: member.id). 로그인 실패처럼 모르면 null")
    private Long actorMemberId;

    @Column(name = "target", length = 200, comment = "대상 (member:<id> · members?role=..&page=..&size=..). 없으면 null. 개인정보 없음")
    private String target;

    @Column(name = "result", nullable = false, length = 10, comment = "결과 (SUCCESS / FAILURE) — 실패 이유는 나누지 않는다")
    private String result;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, comment = "행위 시각 (동기 기록이라 기록 시각과 같다)")
    private Instant createdAt;

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
