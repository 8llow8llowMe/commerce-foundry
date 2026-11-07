package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminPasswordResetAccountProcessor {
    private final MemberRepositoryPort memberRepositoryPort;
    private final AdminAccessCutoffPort adminAccessCutoffPort;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<Member> recipient(long actor, long target) {
        memberRepositoryPort.findById(actor).filter(Member::isActiveOperator).filter(Member::isOwner)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN));
        return memberRepositoryPort.findById(target).filter(Member::isActiveOperator);
    }

    /**
     * 잠근 최신 행의 버전을 검사하고 저장·flush 뒤 컷오프를 쓴다. 보안상 Redis 호출을 이 트랜잭션 안에 두는 명시적 예외다.
     * 컷오프 실패는 비밀번호를 롤백한다. 성공한 컷오프는 이후 DB 커밋 실패에도 보상 삭제하지 않는다(security-guide §11).
     */
    @Transactional(timeout = 5)
    public void reset(AdminPasswordResetGrant grant, String passwordHash) {
        Member member = memberRepositoryPort.findByIdForSecurityUpdate(grant.memberId())
            .filter(Member::isActiveOperator).filter(candidate -> Objects.equals(candidate.version(), grant.memberVersion()))
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_INVALID));
        memberRepositoryPort.save(member.changePassword(passwordHash));
        adminAccessCutoffPort.cutOff(member.id(), clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
    }
}
