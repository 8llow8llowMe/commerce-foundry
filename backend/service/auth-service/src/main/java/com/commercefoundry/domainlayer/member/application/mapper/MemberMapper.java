package com.commercefoundry.domainlayer.member.application.mapper;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MemberMapper {

    /** {@link Member#restore} 로 검증 없이 되살린다 — 규칙 밖으로 저장된 행(이관 데이터)도 조회 · 로그인이 실패하지 않게. 검증은 가입 · 수정에서만 한다. */
    default Member toDomain(MemberEntity entity) {
        return Member.restore(
            entity.getId(), entity.getEmail(), entity.getPasswordHash(), entity.getName(), entity.getPhone(), entity.getRole(), entity.getVersion()
        );
    }

    /** 버전을 그대로 옮긴다 — null 이면 새 회원(persist), 값이 있으면 그 버전에 대한 수정(merge 후 버전 비교)이다. */
    MemberEntity toEntity(Member member);
}
