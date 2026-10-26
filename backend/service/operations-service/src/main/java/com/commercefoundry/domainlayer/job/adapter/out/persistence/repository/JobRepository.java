package com.commercefoundry.domainlayer.job.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.application.port.out.query.ExpiredJobLeaseQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;
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
 * 이 쓰기도 {@code version} 을 직접 올린다 — 그 사이 같은 행을 읽은 쪽(lease 만료를 판정하는 트리거, 종료를 쓰는 워커)의 merge 가
 * 옛 lease · 플래그로 덮지 못하고 버전 충돌로 진다. bulk UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 인자로 받아 직접 세팅한다.
 */
public interface JobRepository extends JpaRepository<JobEntity, Long> {

    /** 같은 종류 · 같은 키의 활성 작업 — {@code uk_job_type_active_dedup_key} 로 찾는다. 끝난 작업은 키가 null 이라 걸리지 않는다. */
    Optional<JobEntity> findByTypeAndActiveDedupKey(String type, String activeDedupKey);

    /** 같은 종류 · 같은 멱등 키의 작업 — 끝난 작업의 {@code dedup_key} 도 포함한다. {@code idx_job_type_dedup_key}. */
    boolean existsByTypeAndDedupKey(String type, String dedupKey);

    /** 어드민 목록 — 조건 없음. 아이디 내림차순(등록 최신순). */
    Page<JobEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 어드민 목록 — 상태 조건. */
    Page<JobEntity> findAllByStatusOrderByIdDesc(JobStatus status, Pageable pageable);

    /** 어드민 목록 — 종류 조건. {@code idx_job_type_id} 순서 그대로 읽는다. */
    Page<JobEntity> findAllByTypeOrderByIdDesc(String type, Pageable pageable);

    /** 어드민 목록 — 상태 · 종류 조건. */
    Page<JobEntity> findAllByStatusAndTypeOrderByIdDesc(JobStatus status, String type, Pageable pageable);

    /** lease 가 만료된 RUNNING(워커가 죽었다)의 첫 배치 — {@code idx_job_status_lease_expires_at}. 오래 만료된 것부터. */
    @Query("""
        select new com.commercefoundry.domainlayer.job.application.port.out.query.ExpiredJobLeaseQueryResult(j.id, j.leaseExpiresAt)
          from JobEntity j
         where j.status = :running and j.leaseExpiresAt < :now
         order by j.leaseExpiresAt asc, j.id asc
        """)
    List<ExpiredJobLeaseQueryResult> findExpiredLeases(JobStatus running, Instant now, Limit limit);

    /** 다음 배치 — 앞 배치의 마지막 {@code (lease_expires_at, id)} 다음부터 읽는다(keyset). 회수에 실패해 남은 건을 다시 읽지 않는다. */
    @Query("""
        select new com.commercefoundry.domainlayer.job.application.port.out.query.ExpiredJobLeaseQueryResult(j.id, j.leaseExpiresAt)
          from JobEntity j
         where j.status = :running and j.leaseExpiresAt < :now
           and (j.leaseExpiresAt > :afterLeaseExpiresAt or (j.leaseExpiresAt = :afterLeaseExpiresAt and j.id > :afterId))
         order by j.leaseExpiresAt asc, j.id asc
        """)
    List<ExpiredJobLeaseQueryResult> findExpiredLeasesAfter(
        JobStatus running, Instant now, Instant afterLeaseExpiresAt, Long afterId, Limit limit
    );

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
