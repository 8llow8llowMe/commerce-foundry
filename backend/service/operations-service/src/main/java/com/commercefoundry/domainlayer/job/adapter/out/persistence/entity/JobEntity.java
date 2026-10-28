package com.commercefoundry.domainlayer.job.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 백그라운드 작업 한 건.
 *
 * <p>상태는 도메인 전이 → 이 엔티티로 매핑 → {@code saveAndFlush}(merge) 로 바꾼다 — JPQL 로 상태를 바꾸지 않는다. {@code @Version} 이 여러 워커의
 * 동시 가져가기 · 종료 · 취소를 하나만 통과시킨다. lease · 진행률 · 취소 요청 플래그는 상태가 아니라 조건부 UPDATE 로 바꾸고, 그때도 버전을 올린다
 * ({@code JobRepository}). 버전이 {@code Long}(wrapper)이라 새 행은 버전이 null 이고, Spring Data 는 그것으로 새 행을 판정해 {@code persist} 한다.
 *
 * <p>{@code active_dedup_key} 는 QUEUED · RUNNING 동안만 {@code dedup_key} 와 같고 끝나면 null 이다. 유니크 키 {@code (type, active_dedup_key)} 가
 * 같은 종류 · 같은 키의 활성 작업을 하나로 막고 — 종류가 다르면 같은 키를 써도 서로 막지 않는다 — 끝난 키는 다시 등록할 수 있다(PostgreSQL 유니크 키는 null 을 겹치게 둔다).
 *
 * <p>인덱스 — lease 만료 스캔({@code status, lease_expires_at}), 어드민 목록의 종류 필터 · 아이디 내림차순({@code type, id}),
 * 정기 슬롯 등록 확인 — 끝난 작업 포함({@code idx_job_type_dedup_key}).
 * 실행 대상은 Kafka 신호가 아이디로 가리키므로 가져갈 후보 스캔 인덱스를 두지 않는다 ({@code architecture-guide.md} §10).
 */
@Entity
@Table(
    name = "job",
    comment = "백그라운드 작업",
    uniqueConstraints = @UniqueConstraint(name = "uk_job_type_active_dedup_key", columnNames = {"type", "active_dedup_key"}),
    indexes = {
        @Index(name = "idx_job_status_lease_expires_at", columnList = "status, lease_expires_at"),
        @Index(name = "idx_job_type_dedup_key", columnList = "type, dedup_key"),
        @Index(name = "idx_job_type_id", columnList = "type, id")
    },
    check = {
        @CheckConstraint(name = "ck_job_attempt_range", constraint = "attempt >= 0 and attempt <= max_attempts"),
        @CheckConstraint(name = "ck_job_max_attempts_positive", constraint = "max_attempts > 0"),
        @CheckConstraint(name = "ck_job_progress_percent_range", constraint = "progress_percent is null or (progress_percent >= 0 and progress_percent <= 100)")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class JobEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "작업 아이디 (Snowflake)")
    private Long id;

    @Column(name = "type", nullable = false, length = 64, comment = "작업 종류 — 핸들러 이름 ([A-Z0-9_]{1,64})")
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "작업 상태 (QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED)")
    private JobStatus status;

    @Column(name = "priority", nullable = false, comment = "우선순위 — 등록 기록용. 실행 순서는 Kafka 파티션 도착 순서다")
    private int priority;

    /** 앱 상한 16KiB. {@code text} 로 두어 길이 변경에 DDL 이 따라오지 않게 한다. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "payload_json", nullable = false, comment = "핸들러 입력 JSON (16KiB 이하, 개인정보 원문 없음)")
    private String payloadJson;

    @Column(name = "dedup_key", length = 200, comment = "멱등 키. 없으면 null")
    private String dedupKey;

    @Column(name = "active_dedup_key", length = 200, comment = "활성 작업의 멱등 키 — QUEUED · RUNNING 동안만 dedup_key 와 같고 끝나면 null (키당 활성 작업 1건)")
    private String activeDedupKey;

    @Column(name = "requested_by_member_id", comment = "등록한 어드민 (외부 FK: auth-service member.id). 시스템 등록이면 null")
    private Long requestedByMemberId;

    @Column(name = "scheduled_at", nullable = false, comment = "이 시각부터 실행할 수 있다 — 재시도면 백오프가 더해진 시각")
    private Instant scheduledAt;

    @Column(name = "attempt", nullable = false, comment = "가져간 시도 수 (0 부터). RUNNING 이면 실행 중인 시도 번호")
    private int attempt;

    @Column(name = "max_attempts", nullable = false, comment = "시도 횟수 상한 (첫 실행 포함)")
    private int maxAttempts;

    @Column(name = "worker_id", length = 100, comment = "작업을 쥔 워커 ({앱}:{호스트}:{pid}:{난수}). 끝난 작업은 마지막 워커, QUEUED 는 null")
    private String workerId;

    @Column(name = "lease_expires_at", comment = "lease 만료 시각 (RUNNING 일 때만) — 지나면 만료 판정 트리거가 회수한다")
    private Instant leaseExpiresAt;

    @Column(name = "started_at", comment = "이번 시도를 가져간 시각")
    private Instant startedAt;

    @Column(name = "finished_at", comment = "종료 시각 (SUCCEEDED / FAILED / CANCELLED 일 때만)")
    private Instant finishedAt;

    @Column(name = "progress_percent", comment = "진행률 0 ~ 100. 보고 전이면 null")
    private Integer progressPercent;

    @Column(name = "progress_message", length = 200, comment = "진행 문구 (개인정보 원문 없음)")
    private String progressMessage;

    @Column(name = "cancel_requested", nullable = false, comment = "취소 요청을 받았다 — RUNNING 이면 다음 checkpoint 에서 멈춘다")
    private boolean cancelRequested;

    @Column(name = "cancel_requested_by_member_id", comment = "취소를 요청한 어드민 (외부 FK: auth-service member.id). 없으면 null")
    private Long cancelRequestedByMemberId;

    /** 앱 상한 {@code operations.jobs.result.max-json-bytes}(기본 64KiB). */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "result_json", comment = "작은 결과 JSON (기본 64KiB 이하, 개인정보 원문 없음)")
    private String resultJson;

    @Column(name = "error_code", length = 50, comment = "마지막 실패 코드 — 재시도 대기 중에도 남고 성공하면 지운다")
    private String errorCode;

    @Column(name = "error_message", length = 500, comment = "마지막 실패 설명 (개인정보 원문 없음)")
    private String errorMessage;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전 — 상태 전이와 lease · 진행률 · 취소 요청 갱신이 모두 올린다")
    private Long version;
}
