package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberAgreementRepository;
import com.commercefoundry.domainlayer.member.application.mapper.MemberAgreementMapper;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberAgreementRepositoryAdapter implements MemberAgreementRepositoryPort {

    private final MemberAgreementRepository memberAgreementRepository;
    private final MemberAgreementMapper memberAgreementMapper;

    @Override
    public Optional<MemberAgreement> findLatest(long memberId, AgreementType agreementType) {
        return memberAgreementRepository.findFirstByMemberIdAndAgreementTypeOrderByCreatedAtDescIdDesc(memberId, agreementType)
            .map(memberAgreementMapper::toDomain);
    }

    @Override
    public void appendAll(List<MemberAgreement> agreements) {
        if (agreements.isEmpty()) {
            return;
        }
        memberAgreementRepository.saveAll(memberAgreementMapper.toEntities(agreements));
    }
}
