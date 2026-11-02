package com.commercefoundry.domainlayer.job.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobResultFileEntity;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobResultFileRepository extends JpaRepository<JobResultFileEntity, Long> {
    Optional<JobResultFileEntity> findByJobIdAndAttemptAndState(long jobId, int attempt, JobResultFile.State state);

    @Query("select f from JobResultFileEntity f where f.jobId = :jobId and f.attempt = :attempt and f.eTag is not null order by f.id desc")
    Optional<JobResultFileEntity> findConfirmed(long jobId, int attempt, Limit limit);

    @Query("""
        select f from JobResultFileEntity f where f.nextDeleteAt <= :now
        and (f.nextDeleteAt > :afterTime or (f.nextDeleteAt = :afterTime and f.id > :afterId))
        order by f.nextDeleteAt, f.id
        """)
    List<JobResultFileEntity> findDue(Instant now, Instant afterTime, long afterId, Limit limit);
}
