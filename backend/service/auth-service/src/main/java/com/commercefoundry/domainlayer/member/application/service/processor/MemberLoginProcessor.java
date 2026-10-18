package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 로그인의 회원 확인. 조회(readOnly 트랜잭션)와 비밀번호 비교(BCrypt — 트랜잭션 밖)를 나눠 둔다.
 *
 * <p>실패는 이유와 무관하게 {@link MemberErrorCode#LOGIN_FAILED} 하나다 — 이메일 없음 · 비밀번호 틀림 · 관리자 계정을 구분하지 않는다
 * ({@code security-guide.md} §10). 응답 시간으로도 갈리지 않게 회원이 없어도 더미 해시로 한 번 비교한다.
 */
@Component
public class MemberLoginProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;

    /** 회원이 없을 때 비교할 해시. 같은 인코더(같은 강도)로 만들어 비교 시간이 실제 회원과 같다. 원문은 버린다. */
    private final String dummyPasswordHash;

    public MemberLoginProcessor(MemberRepositoryPort memberRepositoryPort, PasswordEncoder passwordEncoder) {
        this.memberRepositoryPort = memberRepositoryPort;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** 정규화한 이메일로 찾는다. 정규화할 수 없는 값(빈 값 · 254자 초과)은 없는 회원과 같다 — 실패 이유를 나누지 않는다. */
    @Transactional(readOnly = true)
    public Optional<Member> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String normalized;
        try {
            normalized = Member.normalizeEmail(email);
        } catch (IllegalArgumentException outOfRule) {
            return Optional.empty();
        }
        return memberRepositoryPort.findByEmail(normalized);
    }

    /**
     * 비밀번호를 비교하고 고객 계정이면 돌려준다. 회원이 없어도 · 관리자여도 비교는 언제나 한 번 한다.
     *
     * @throws MemberException {@link MemberErrorCode#LOGIN_FAILED} — 회원 없음 · 비밀번호 틀림 · 관리자 계정
     */
    public Member authenticate(Optional<Member> candidate, String rawPassword) {
        boolean matches = matches(rawPassword, candidate.map(Member::passwordHash).orElse(dummyPasswordHash));
        return candidate
            .filter(member -> matches && member.isCustomer())
            .orElseThrow(() -> new MemberException(MemberErrorCode.LOGIN_FAILED));
    }

    /**
     * 재발급 — refresh 토큰 주인이 지금도 고객 로그인을 쓸 수 있는 회원인가. 없거나 관리자면 빈 값이다.
     * "누가 고객 토큰을 쓸 수 있나" 판정을 로그인({@link #authenticate})과 같은 곳({@link Member#isCustomer})에 모은다.
     */
    @Transactional(readOnly = true)
    public Optional<Member> findCustomer(long memberId) {
        return memberRepositoryPort.findById(memberId).filter(Member::isCustomer);
    }

    /**
     * BCrypt 가 받지 않는 입력(72바이트 초과 등)은 틀린 비밀번호다. 가입 규칙(64자 ASCII)을 지난 비밀번호는 여기에 걸리지 않는다.
     * 저장된 해시가 BCrypt 모양이 아니면(이관 데이터) 인코더가 false 를 돌려준다.
     */
    private boolean matches(String rawPassword, String passwordHash) {
        try {
            return rawPassword != null && passwordEncoder.matches(rawPassword, passwordHash);
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }
}
