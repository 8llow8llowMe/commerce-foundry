package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.global.properties.OperatorRoleProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가게 운영자 계정 추가 · 역할 변경 · 비활성화 · 재활성화 · 비밀번호 변경 (#193 · #236). 해시는 트랜잭션 밖에서 하고, 저장만 트랜잭션이다.
 *
 * <p>어드민은 상품을 파는 사업자의 직원 계정이다. 고객 계정을 관리자로 올리지 않는다.
 *
 * <p>역할 · 상태 변경은 활성 운영자 전부를 한 번에 잠가 읽는다({@link MemberRepositoryPort#findActiveOperatorsForUpdate}) — 행위자 판정과 "마지막 대표 관리자"
 * 판정이 같은 잠금 안에 있어야 대표 관리자 두 명을 동시에 끄거나 내리는 요청이 줄을 선다.
 */
@Component
@RequiredArgsConstructor
public class AdminAccountProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OperatorRoleProperties operatorRoleProperties;

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

    /**
     * 운영자 계정을 만든다. 역할이 없으면 기존 ADMIN 이다. 역할을 주면 발급 플래그가 켜져 있어야 한다(아니면 409 {@code AUTH_049}).
     *
     * <p>행위자는 토큰이 아니라 DB 로 다시 판정한다 — 행위자 행을 잠가 읽고, 역할이 없으면(ADMIN 은 대표 관리자 급) 활성 대표 관리자여야 하며
     * 역할이 있으면 그 역할을 줄 수 있어야 한다({@link Member#canAssignRole}). 아니면 403 {@code AUTH_046}. 강등 · 정지된 옛 대표 관리자의 토큰이
     * 컷오프 실패 등으로 아직 살아 있어도 운영자를 만들지 못한다. 잠금은 강등 · 정지 트랜잭션(활성 운영자 전체 잠금)과 줄을 서 커밋된 역할을 본다.
     *
     * @param role OWNER · MANAGER · STAFF 중 하나. null 이면 ADMIN
     */
    @Transactional
    public Member create(long actorMemberId, String email, String passwordHash, String name, MemberRole role) {
        if (role != null) {
            requireRoleIssuanceEnabled();
        }
        Optional<Member> actor = memberRepositoryPort.findByIdForUpdate(actorMemberId);
        if (!Member.canAssignRole(actor, role == null ? MemberRole.OWNER : role)) {
            throw new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN);
        }
        if (memberRepositoryPort.existsByEmail(email)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        long id = snowflakeIdGenerator.generateId();
        Member created = role == null ? Member.admin(id, email, passwordHash, name) : Member.operator(id, email, passwordHash, name, role);
        return memberRepositoryPort.save(created);
    }

    /**
     * 활성 운영자 계정을 끈다 — 운영자 역할 전체가 대상이다. 없거나 고객이거나 이미 꺼진 계정은 같은 404 다. 본인도 끌 수 있다(본문 없는 이전 API, #193).
     * 대표 관리자(ADMIN · OWNER) 계정은 활성 대표 관리자만 끄고({@link Member#canBeManagedBy}, 403), 활성 대표 관리자 중 마지막 한 명이면 거절한다
     * ({@link Member#isLastActiveOwner}, 409).
     */
    @Transactional
    public void deactivate(long actorMemberId, long memberId, Instant deactivatedAt) {
        List<Member> activeOperators = memberRepositoryPort.findActiveOperatorsForUpdate();
        Member target = findIn(activeOperators, memberId).orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        if (!target.canBeManagedBy(findIn(activeOperators, actorMemberId))) {
            throw new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN);
        }
        if (Member.isLastActiveOwner(target, activeOperators)) {
            throw new MemberException(MemberErrorCode.LAST_ACTIVE_ADMIN);
        }
        memberRepositoryPort.save(target.deactivate(deactivatedAt));
    }

    /**
     * 활성 운영자의 역할을 바꾼다 (#236). 같은 역할이면 저장하지 않는다(호출자는 컷오프를 그대로 한다 — 앞선 요청의 컷오프가 실패했을 때 같은 요청으로 복구).
     * 판정 순서: 발급 플래그(409 {@code AUTH_049}) → 본인(400 {@code AUTH_047}) → 대상 없음 · 고객 · 비활성(404 {@code AUTH_033}) →
     * 대상 관리 권한 · 줄 수 있는 역할(403 {@code AUTH_046}) → 마지막 활성 대표 관리자 강등(409 {@code AUTH_048}).
     *
     * <p>관리 권한(403)을 먼저 본다 — 대표 관리자가 아닌 행위자에게 대상이 "마지막 대표 관리자"인지 새지 않게. 그 결과 지금 규칙에서는 048 에 정상 경로로
     * 닿지 않는다: 대표 관리자 대상은 활성 대표 관리자만 바꾸고 본인은 047 이라, 행위자가 통과하면 활성 대표 관리자가 둘 이상이다. 그래도 관리 권한 규칙이
     * 넓어질 때를 위한 방어로 남긴다(판정 자체는 {@link Member#isLastActiveOwner} 단위 테스트가 본다).
     *
     * @param newRole OWNER · MANAGER · STAFF 중 하나 (요청 검증 AUTH_159 를 지나온 값)
     */
    @Transactional
    public void changeRole(long actorMemberId, long memberId, MemberRole newRole) {
        requireRoleIssuanceEnabled();
        requireOtherMember(actorMemberId, memberId);
        List<Member> activeOperators = memberRepositoryPort.findActiveOperatorsForUpdate();
        Member target = findIn(activeOperators, memberId).orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        Optional<Member> actor = findIn(activeOperators, actorMemberId);
        if (!target.canBeManagedBy(actor) || !Member.canAssignRole(actor, newRole)) {
            throw new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN);
        }
        if (!newRole.isOwner() && Member.isLastActiveOwner(target, activeOperators)) {
            throw new MemberException(MemberErrorCode.LAST_ACTIVE_OWNER_ROLE);
        }
        if (target.role() == newRole) {
            return;
        }
        memberRepositoryPort.save(target.changeRole(newRole));
    }

    /**
     * 운영자 계정을 켜거나 끈다 (#236). 같은 상태면 저장하지 않는다(200). 판정 순서: 본인(400 {@code AUTH_047}) → 없음 · 고객(404 {@code AUTH_033}) →
     * 대표 관리자 계정을 대표 관리자가 아닌 행위자가 바꿈(403 {@code AUTH_046}) → 마지막 활성 대표 관리자 끄기(409 {@code AUTH_031}) — 역할 변경과 같은 순서이고,
     * 같은 이유로 031 은 지금 규칙에서 정상 경로로 닿지 않는 방어다({@link #changeRole} 참고).
     * 비활성 대상은 활성 운영자 잠금 밖이라 그 행도 잠가 읽는다(활성 운영자 → 대상 순서).
     *
     * @param active true 면 다시 켜고(비밀번호 · OTP 자격은 끄기 전 그대로), false 면 끈다
     */
    @Transactional
    public void changeStatus(long actorMemberId, long memberId, boolean active, Instant at) {
        requireOtherMember(actorMemberId, memberId);
        List<Member> activeOperators = memberRepositoryPort.findActiveOperatorsForUpdate();
        Member target = findIn(activeOperators, memberId)
            .or(() -> memberRepositoryPort.findByIdForUpdate(memberId).filter(Member::isOperator))
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        if (!target.canBeManagedBy(findIn(activeOperators, actorMemberId))) {
            throw new MemberException(MemberErrorCode.ADMIN_MANAGEMENT_FORBIDDEN);
        }
        if (!active && target.isActiveOperator() && Member.isLastActiveOwner(target, activeOperators)) {
            throw new MemberException(MemberErrorCode.LAST_ACTIVE_ADMIN);
        }
        if (target.isActiveOperator() == active) {
            return;
        }
        memberRepositoryPort.save(active ? target.reactivate() : target.deactivate(at));
    }

    @Transactional
    public void changePassword(long memberId, String passwordHash) {
        Member admin = memberRepositoryPort.findByIdForUpdate(memberId)
            .filter(Member::isActiveOperator)
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND));
        memberRepositoryPort.save(admin.changePassword(passwordHash));
    }

    private void requireRoleIssuanceEnabled() {
        if (!operatorRoleProperties.issuanceEnabled()) {
            throw new MemberException(MemberErrorCode.OPERATOR_ROLE_ISSUANCE_DISABLED);
        }
    }

    private static void requireOtherMember(long actorMemberId, long memberId) {
        if (actorMemberId == memberId) {
            throw new MemberException(MemberErrorCode.ADMIN_SELF_MANAGEMENT);
        }
    }

    private static Optional<Member> findIn(List<Member> operators, long memberId) {
        return operators.stream().filter(operator -> operator.id() == memberId).findFirst();
    }
}
