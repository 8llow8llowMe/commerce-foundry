package com.commercefoundry.domainlayer.member.application.mapper;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MemberAgreementMapper {

    MemberAgreement toDomain(MemberAgreementEntity entity);

    /** 시각은 persist 때 JPA Auditing 이 채운다. */
    @Mapping(target = "createdAt", ignore = true)
    MemberAgreementEntity toEntity(MemberAgreement agreement);

    List<MemberAgreementEntity> toEntities(List<MemberAgreement> agreements);
}
