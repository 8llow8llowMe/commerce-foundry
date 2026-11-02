package com.commercefoundry.domainlayer.job.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
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

@Entity
@Table(name = "job_result_file", comment = "작업 결과 파일",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_job_result_file_object_key", columnNames = "object_key")
    },
    indexes = {
        @Index(name = "idx_job_result_file_next_delete_at_id", columnList = "next_delete_at, id"),
        @Index(name = "idx_job_result_file_job_id_attempt", columnList = "job_id, attempt")
    },
    check = @CheckConstraint(name = "ck_job_result_file_size_bytes_positive", constraint = "size_bytes > 0"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class JobResultFileEntity extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, comment = "결과 파일 원장 아이디 (Snowflake)")
    private Long id;

    @Column(name = "job_id", nullable = false, comment = "작업 아이디 (FK: job.id)")
    private Long jobId;

    @Column(name = "attempt", nullable = false, comment = "파일을 만든 작업 시도 번호 — release 뒤 같은 번호의 원장이 여러 개일 수 있음")
    private int attempt;

    @Column(name = "worker_id", nullable = false, length = 100, comment = "파일을 예약한 워커 — 성공 확정 시 소유 확인")
    private String workerId;

    @Column(name = "object_key", nullable = false, length = 200, comment = "서버가 만든 비공개 객체 키 — 원장마다 새 UUID, 재사용 금지")
    private String objectKey;

    @Column(name = "file_name", nullable = false, length = 120, comment = "다운로드 표시 파일명 — 경로와 개인정보 원문 없음")
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120, comment = "MIME")
    private String contentType;

    @Column(name = "size_bytes", nullable = false, comment = "파일 바이트 수 — 업로드 뒤 HEAD와 대조")
    private long sizeBytes;

    @Column(name = "etag", length = 200, comment = "성공 확정 때 검증한 ETag — 미확정 원장은 null, 정리 후에도 보존")
    private String eTag;

    @Column(name = "sensitive", nullable = false, comment = "짧은 보관 정책 선택 — 개인정보 원문 저장을 허용하지 않음")
    private boolean sensitive;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20, comment = "원장 상태 (STAGED / AVAILABLE / RETIRED)")
    private JobResultFile.State state;

    @Column(name = "expires_at", nullable = false, comment = "예약 시점 기준 보관 만료 — 이후 새 다운로드 링크 발급 금지")
    private Instant expiresAt;

    @Column(name = "staging_deadline", nullable = false, comment = "미확정 업로드 회수 시각 — 이후 성공 확정 금지")
    private Instant stagingDeadline;

    @Column(name = "next_delete_at", nullable = false, comment = "다음 삭제 후보 시각 — 기한 만료, 실패 재시도 또는 RETIRED 재삭제")
    private Instant nextDeleteAt;

    @Column(name = "delete_attempts", nullable = false, comment = "삭제 시도 기록 수 — 실패 백오프 계산, 최대 1000000")
    private int deleteAttempts;

    @Column(name = "last_deleted_at", comment = "마지막 삭제 API 성공 시각 — 늦은 PUT 복구를 위해 원장은 계속 보존")
    private Instant lastDeletedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전 — 첨부 확정과 정리의 경합 제어")
    private Long version;

}
