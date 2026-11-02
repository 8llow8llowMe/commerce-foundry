package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreementEntity, Long> {

    /** 그 회원 · 유형의 가장 최근 행. {@code idx_member_agreement_member_id_agreement_type} 로 거른 뒤 정렬한다 — 회원 · 유형당 행은 몇 줄뿐이다. */
    Optional<MemberAgreementEntity> findFirstByMemberIdAndAgreementTypeOrderByCreatedAtDescIdDesc(Long memberId, AgreementType agreementType);

    /** 최신 이력이 철회라면 과거의 true 행도 반환하지 않는다. 회원별 조회 없이 한 문장의 스냅숏으로 판정한다 (#230). */
    @Query("""
        select a.memberId from MemberAgreementEntity a, MemberEntity m
        where m.id = a.memberId and m.role = :role and m.deactivatedAt is null
          and a.memberId in :memberIds and a.agreementType = :agreementType and a.agreed = true
          and not exists (
            select newer.id from MemberAgreementEntity newer
            where newer.memberId = a.memberId and newer.agreementType = a.agreementType
              and (newer.createdAt > a.createdAt or (newer.createdAt = a.createdAt and newer.id > a.id))
          )
        """)
    List<Long> findLatestAgreedMemberIds(List<Long> memberIds, AgreementType agreementType, MemberRole role);
}
