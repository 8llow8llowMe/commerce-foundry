package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
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
    public Member findActiveAdmin(long memberId) {
        return memberRepositoryPort.findById(memberId)
            .filter(Member::isActiveAdmin)
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
     * 활성 관리자를 끈다. 활성 관리자가 한 명뿐이면 거절한다. 없거나 고객이거나 이미 꺼진 계정은 같은 404 다.
     */
    @Transactional
    public void deactivate(long memberId, Instant deactivatedAt) {
        List<Member> activeAdmins = memberRepositoryPort.findActiveAdminsForUpdate();
        Member target = activeAdmins.stream()
            .filter(admin -> admin.id() == memberId)
            .findFirst()
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        if (activeAdmins.size() == 1) {
            throw new MemberException(MemberErrorCode.LAST_ACTIVE_ADMIN);
        }
        memberRepositoryPort.save(target.deactivate(deactivatedAt));
    }

    @Transactional
    public void changePassword(long memberId, String passwordHash) {
        Member admin = memberRepositoryPort.findByIdForUpdate(memberId)
            .filter(Member::isActiveAdmin)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        memberRepositoryPort.save(admin.changePassword(passwordHash));
    }
}
