package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.MemberProfileInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 내 정보 조회 · 수정. 회원은 JWT 의 memberId 로만 찾는다 — 요청 본문의 아이디를 받지 않는다 ({@code api-design-guide.md} §5).
 * 마케팅 동의는 회원 행이 아니라 동의 이력의 가장 최근 행이 정본이다.
 */
@Component
@RequiredArgsConstructor
public class MemberProfileProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final MemberAgreementRepositoryPort memberAgreementRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final AgreementProperties agreementProperties;

    /** @throws MemberException {@link MemberErrorCode#NOT_FOUND_MEMBER} — 토큰은 유효하지만 회원이 없다 */
    public MemberProfileInfo getProfile(long memberId) {
        Member member = getMember(memberId);
        return toInfo(member, marketingAgreed(memberId));
    }

    /**
     * 바뀐 칸만 반영한다. 아무것도 바뀌지 않으면 쓰지 않고, 마케팅 동의가 현재 값과 같으면 이력을 남기지 않는다.
     *
     * <p>무엇이 바뀌든 회원 버전은 한 번 오른다 — 이름 · 전화가 바뀌면 저장(버전 비교)이, 마케팅만 바뀌면 {@code incrementVersion} 이 올린다.
     * 같은 버전을 읽은 두 요청이 마케팅 이력을 둘 다 남기는 lost update 를 회원 버전 하나로 막는다. 진 쪽은 AUTH_004 이고 이력도 롤백된다.
     *
     * @throws MemberException {@link MemberErrorCode#NOT_FOUND_MEMBER}, {@link MemberErrorCode#MEMBER_UPDATE_CONFLICT} — 다른 요청이 먼저 고쳤다
     */
    public MemberProfileInfo updateProfile(long memberId, MemberProfileUpdateCommand command) {
        Member member = getMember(memberId);
        Member changed = member.changeProfile(command.name(), command.phone());
        boolean profileChanged = !changed.equals(member);

        boolean marketingAgreed = marketingAgreed(memberId);
        boolean marketingChanged = command.marketingAgreed() != null && command.marketingAgreed() != marketingAgreed;

        if (profileChanged) {
            changed = memberRepositoryPort.save(changed);
        } else if (marketingChanged) {
            memberRepositoryPort.incrementVersion(member);
        }

        if (marketingChanged) {
            marketingAgreed = command.marketingAgreed();
            MemberAgreement agreement = new MemberAgreement(
                snowflakeIdGenerator.generateId(), memberId, AgreementType.MARKETING, marketingAgreed, agreementProperties.versions().marketing()
            );
            memberAgreementRepositoryPort.appendAll(List.of(agreement));
        }
        return toInfo(changed, marketingAgreed);
    }

    private Member getMember(long memberId) {
        return memberRepositoryPort.findById(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.NOT_FOUND_MEMBER));
    }

    /** 이력이 없으면(관리자 계정 등) 동의하지 않은 것이다. */
    private boolean marketingAgreed(long memberId) {
        return memberAgreementRepositoryPort.findLatest(memberId, AgreementType.MARKETING).map(MemberAgreement::agreed).orElse(false);
    }

    private static MemberProfileInfo toInfo(Member member, boolean marketingAgreed) {
        return new MemberProfileInfo(member.id(), member.email(), member.name(), member.phone(), marketingAgreed);
    }
}
