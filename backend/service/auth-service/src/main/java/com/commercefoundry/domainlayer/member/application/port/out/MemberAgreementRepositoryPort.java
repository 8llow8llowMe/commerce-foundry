package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import java.util.List;
import java.util.Optional;

/**
 * 동의 이력. 추가만 한다 — 고치거나 지우는 메서드를 두지 않는다.
 */
public interface MemberAgreementRepositoryPort {

    /** 그 회원 · 유형의 가장 최근 행. 없으면 빈 값이다. */
    Optional<MemberAgreement> findLatest(long memberId, AgreementType agreementType);

    /** 빈 목록이면 쿼리 없이 끝난다. */
    void appendAll(List<MemberAgreement> agreements);
}
