package com.commercefoundry.domainlayer.job.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 백그라운드 작업. 상태를 바꾸는 JPQL 을 두지 않는다 — 도메인 전이 뒤 {@code saveAndFlush}(merge, {@code @Version} 검사)로만 바꾼다.
 *
 * <p>상태가 아닌 칸(lease · 진행률 · 취소 요청 플래그)은 조건부 UPDATE 한 문장이다 ({@code coding-conventions.md} §9-1 · §9-5). 반환값은 바뀐 행 수(0 또는 1)다.
 * 이 쓰기도 {@code version} 을 직접 올린다 — 그 사이 같은 행을 읽은 쪽(lease 만료로 회수하려는 다른 워커, 종료를 쓰는 워커)의 merge 가
 * 옛 lease · 플래그로 덮지 못하고 버전 충돌로 진다. bulk UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 인자로 받아 직접 세팅한다.
 */
public interface JobRepository extends JpaRepository<JobEntity, Long> {

    /** 같은 종류 · 같은 키의 활성 작업 — {@code uk_job_type_active_dedup_key} 로 찾는다. 끝난 작업은 키가 null 이라 걸리지 않는다. */
    Optional<JobEntity> findByTypeAndActiveDedupKey(String type, String activeDedupKey);

    /** 어드민 목록 — 조건 없음. 아이디 내림차순(등록 최신순). */
    Page<JobEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 어드민 목록 — 상태 조건. */
    Page<JobEntity> findAllByStatusOrderByIdDesc(JobStatus status, Pageable pageable);

    /** 어드민 목록 — 종류 조건. {@code idx_job_type_id} 순서 그대로 읽는다. */
    Page<JobEntity> findAllByTypeOrderByIdDesc(String type, Pageable pageable);

    /** 어드민 목록 — 상태 · 종류 조건. */
    Page<JobEntity> findAllByStatusAndTypeOrderByIdDesc(JobStatus status, String type, Pageable pageable);

    /**
     * 예약 시각이 지난 QUEUED 후보 — {@code idx_job_status_priority_scheduled_at_id} 의 순서(우선순위 내림차순 → 예약 시각 → 아이디) 그대로 읽어
     * 정렬을 인덱스에서 얻는다. 만료 RUNNING 과 OR 로 묶으면 두 인덱스를 합친 뒤 따로 정렬해야 해서 나눴다.
     */
    @Query("""
        select new com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult(j.id, j.type)
          from JobEntity j
         where j.status = :queued and j.scheduledAt <= :now and j.type in :types
         order by j.priority desc, j.scheduledAt asc, j.id asc
        """)
    List<ClaimableJobQueryResult> findClaimableQueued(Collection<String> types, JobStatus queued, Instant now, Limit limit);

    /** lease 가 만료된 RUNNING 후보(워커가 죽었다) — {@code idx_job_status_lease_expires_at}. 오래 만료된 것부터. */
    @Query("""
        select new com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult(j.id, j.type)
          from JobEntity j
         where j.status = :running and j.leaseExpiresAt < :now and j.type in :types
         order by j.leaseExpiresAt asc, j.id asc
        """)
    List<ClaimableJobQueryResult> findClaimableExpired(Collection<String> types, JobStatus running, Instant now, Limit limit);

    /** heartbeat — 아직 이 워커의 이 시도이고 취소 요청이 없을 때만 lease 를 연장하고 진행률을 쓴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update JobEntity j
           set j.leaseExpiresAt = :leaseExpiresAt, j.progressPercent = :progressPercent, j.progressMessage = :progressMessage,
               j.updatedAt = :now, j.version = j.version + 1
         where j.id = :id and j.status = :running and j.workerId = :workerId and j.attempt = :attempt and j.cancelRequested = false
        """)
    int heartbeat(
        Long id, JobStatus running, String workerId, int attempt, Integer progressPercent, String progressMessage, Instant leaseExpiresAt, Instant now
    );

    /**
     * 실행 중인 작업에 취소 요청 플래그를 세운다. 이미 세웠으면 0행이다(멱등). 시도 번호를 조건에 넣지 않는다 — 읽은 뒤 다른 워커가 회수했어도
     * "이 작업을 취소한다" 는 뜻이라 새 시도에 걸리는 것이 맞다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update JobEntity j
           set j.cancelRequested = true, j.cancelRequestedByMemberId = :memberId, j.updatedAt = :now, j.version = j.version + 1
         where j.id = :id and j.status = :running and j.cancelRequested = false
        """)
    int requestCancel(Long id, JobStatus running, Long memberId, Instant now);
}
