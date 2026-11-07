package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminPasswordResetAccountProcessor {
    private final MemberRepositoryPort memberRepositoryPort;

    @Transactional(readOnly = true)
    public Optional<Member> recipient(long actor, long target) {
        memberRepositoryPort.findById(actor).filter(Member::isActiveOperator).filter(Member::isOwner)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN));
        return memberRepositoryPort.findById(target).filter(Member::isActiveOperator);
    }

    /** 잠근 최신 행의 버전을 검사한다. 비밀번호 변경·정지 후의 옛 링크는 같은 오류로 거절한다. */
    @Transactional
    public void reset(AdminPasswordResetGrant grant, String passwordHash) {
        Member member = memberRepositoryPort.findByIdForUpdate(grant.memberId())
            .filter(Member::isActiveOperator).filter(candidate -> Objects.equals(candidate.version(), grant.memberVersion()))
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_INVALID));
        memberRepositoryPort.save(member.changePassword(passwordHash));
    }
}
