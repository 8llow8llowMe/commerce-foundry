package com.commercefoundry.domainlayer.job.application.port.out;

import com.commercefoundry.domainlayer.job.application.port.out.query.ClaimableJobQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/**
 * 백그라운드 작업 저장소. 상태는 도메인 전이({@link Job#claim} 등) 뒤 {@link #update}(merge, {@code @Version} 검사)로만 바꾼다.
 * 상태가 아닌 칸(lease · 진행률 · 취소 요청 플래그)만 조건부 UPDATE 한 문장으로 바꾼다 ({@code coding-conventions.md} §9-1) — 이 쓰기도 버전을 올려,
 * 그 사이 읽은 쪽의 merge 가 옛 값으로 덮지 못하고 충돌로 진다.
 */
public interface JobRepositoryPort {

    Optional<Job> findById(long jobId);

    /**
     * 어드민 작업 목록 — 아이디(Snowflake) 내림차순, 곧 등록 최신순 한 페이지. 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     *
     * @param status 없으면 전체
     * @param type 없으면 전체
     * @param page 0 부터 센다
     */
    Page<Job> findPage(JobStatus status, String type, int page, int size);

    /** 같은 종류 · 같은 멱등 키의 활성(QUEUED · RUNNING) 작업 — {@code uk_job_type_active_dedup_key} 로 찾는다. */
    Optional<Job> findActiveByDedupKey(String type, String dedupKey);

    /**
     * 지금 가져갈 수 있는 작업 — 예약 시각이 지난 QUEUED 와 lease 가 만료된 RUNNING. 우선순위 내림차순 → 예약 시각 → 아이디 오름차순으로 {@code limit} 건까지.
     *
     * @param types 이 인스턴스가 실행할 수 있는 종류. 비었으면 조회하지 않고 빈 목록이다
     */
    List<ClaimableJobQueryResult> findClaimable(Collection<String> types, Instant now, int limit);

    /**
     * 새 작업(버전 null)을 INSERT 한다.
     *
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_DEDUP_CONFLICT} — 같은 멱등 키의 활성 작업이 그 사이 생겼다
     */
    Job insert(Job job);

    /**
     * 버전이 있는 작업을 merge 하고 바로 flush 한다.
     *
     * @return 저장한 작업 (올라간 버전)
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code JOB_CONFLICT} — 읽은 뒤 다른 트랜잭션이 바꿨다
     */
    Job update(Job job);

    /**
     * heartbeat — 아직 이 워커의 이 시도이고 취소 요청이 없을 때만 lease 를 연장하고 진행률을 쓴다. 버전을 올린다.
     *
     * @return 바뀐 행 수. 0 이면 lease 를 잃었거나(다른 워커가 회수 · 끝남) 취소 요청을 받았다 — 다시 읽어 가린다
     */
    int heartbeat(long jobId, String workerId, int attempt, Integer progressPercent, String progressMessage, Instant leaseExpiresAt, Instant now);

    /**
     * RUNNING 작업에 취소 요청 플래그를 세운다. 이미 세웠거나 RUNNING 이 아니면 0행이다. 버전을 올린다.
     *
     * @param memberId 요청한 어드민. 시스템 요청이면 null
     */
    int requestCancel(long jobId, Long memberId, Instant now);
}
