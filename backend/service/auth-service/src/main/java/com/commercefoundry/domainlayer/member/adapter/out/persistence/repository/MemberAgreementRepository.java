package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreementEntity, Long> {

    /** 그 회원 · 유형의 가장 최근 행. {@code idx_member_agreement_member_id_agreement_type} 로 거른 뒤 정렬한다 — 회원 · 유형당 행은 몇 줄뿐이다. */
    Optional<MemberAgreementEntity> findFirstByMemberIdAndAgreementTypeOrderByCreatedAtDescIdDesc(Long memberId, AgreementType agreementType);
}
