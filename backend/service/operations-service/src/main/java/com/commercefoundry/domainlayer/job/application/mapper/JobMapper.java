package com.commercefoundry.domainlayer.job.application.mapper;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface JobMapper {

    /**
     * 활성 멱등 키는 상태에서 유도하므로 읽지 않는다 ({@link Job#activeDedupKey()}). 도메인은 canonical 생성자로 만든다 — 인자 하나를 받아 {@code Job} 을
     * 돌려주는 전이 메서드({@code cancel} · {@code release})를 MapStruct 가 fluent setter 로 읽으므로 대상에서 뺀다.
     */
    @Mapping(target = "cancel", ignore = true)
    @Mapping(target = "release", ignore = true)
    Job toDomain(JobEntity entity);

    /**
     * 새 작업은 버전이 null 이라 INSERT, 읽어 온 작업은 버전이 있어 merge(버전 검사)다. 생성 시각은 매핑하지 않는다 —
     * {@code created_at} 은 {@code updatable = false} 라 merge 가 덮어쓰지 않는다.
     */
    @Mapping(target = "activeDedupKey", expression = "java(job.activeDedupKey())")
    JobEntity toEntity(Job job);
}
