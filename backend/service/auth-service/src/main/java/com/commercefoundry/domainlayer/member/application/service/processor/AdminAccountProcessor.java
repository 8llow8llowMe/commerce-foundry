package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가게 운영자 계정 추가 · 비활성화 · 비밀번호 변경 (#193). 해시는 트랜잭션 밖에서 하고, 저장만 트랜잭션이다.
 *
 * <p>어드민은 상품을 파는 사업자의 직원 계정이다. 고객 계정을 관리자로 올리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AdminAccountProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean passwordMatches(Member admin, String rawPassword) {
        try {
            return rawPassword != null && passwordEncoder.matches(rawPassword, admin.passwordHash());
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Member findActiveOperator(long memberId) {
        return memberRepositoryPort.findById(memberId)
            .filter(Member::isActiveOperator)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
    }

    @Transactional
    public Member create(String email, String passwordHash, String name) {
        if (memberRepositoryPort.existsByEmail(email)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        return memberRepositoryPort.save(Member.admin(snowflakeIdGenerator.generateId(), email, passwordHash, name));
    }

    /**
     * 활성 운영자 계정을 끈다 — 운영자 역할 전체가 대상이다. 없거나 고객이거나 이미 꺼진 계정은 같은 404 다.
     * 대표 관리자(ADMIN · OWNER) 계정은 활성 대표 관리자만 끄고({@link Member#canBeDeactivatedBy}, 403), 활성 대표 관리자 중 마지막 한 명이면 거절한다
     * ({@link Member#isLastActiveOwner}, 409). 활성 운영자 전부를 한 번에 잠가 읽으므로 대표 관리자 두 명을 동시에 끄는 요청도 줄을 선다.
     */
    @Transactional
    public void deactivate(long actorMemberId, long memberId, Instant deactivatedAt) {
        List<Member> activeOperators = memberRepositoryPort.findActiveOperatorsForUpdate();
        Member target = findIn(activeOperators, memberId).orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        if (!target.canBeDeactivatedBy(findIn(activeOperators, actorMemberId))) {
            throw new MemberException(MemberErrorCode.ADMIN_DEACTIVATION_FORBIDDEN);
        }
        if (Member.isLastActiveOwner(target, activeOperators)) {
            throw new MemberException(MemberErrorCode.LAST_ACTIVE_ADMIN);
        }
        memberRepositoryPort.save(target.deactivate(deactivatedAt));
    }

    private static Optional<Member> findIn(List<Member> operators, long memberId) {
        return operators.stream().filter(operator -> operator.id() == memberId).findFirst();
    }

    @Transactional
    public void changePassword(long memberId, String passwordHash) {
        Member admin = memberRepositoryPort.findByIdForUpdate(memberId)
            .filter(Member::isActiveOperator)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        memberRepositoryPort.save(admin.changePassword(passwordHash));
    }
}
