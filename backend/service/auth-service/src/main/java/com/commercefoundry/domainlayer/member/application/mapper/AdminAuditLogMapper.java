package com.commercefoundry.domainlayer.member.application.mapper;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 감사 로그는 읽기만 domain 으로 옮긴다 — 쓰기는 어댑터가 감사 칸에서 바로 엔티티를 만든다(포트가 행위별 메서드라 domain 입력이 없다). */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AdminAuditLogMapper {

    AdminAuditLog toDomain(AdminAuditLogEntity entity);
}
