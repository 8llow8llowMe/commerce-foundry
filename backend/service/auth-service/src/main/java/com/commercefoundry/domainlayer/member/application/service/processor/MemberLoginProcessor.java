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
 * 고객 · 관리자(#6) 로그인의 회원 확인. 조회(readOnly 트랜잭션)와 비밀번호 비교(BCrypt — 트랜잭션 밖)를 나눠 둔다.
 *
 * <p>실패는 이유와 무관하게 {@link MemberErrorCode#LOGIN_FAILED} 하나다 — 이메일 없음 · 비밀번호 틀림 · 관리자 계정을 구분하지 않는다
 * ({@code security-guide.md} §10). 응답 시간으로도 갈리지 않게 회원이 없어도 더미 해시로 한 번 비교한다.
 */
@Component
public class MemberLoginProcessor {

    /** 관리자 비밀번호 상한 — 시드 규칙({@code AdminSeedProcessor}, 12~64자 ASCII)과 같다. 넘는 입력은 비교하지 않고 실패다. */
    public static final int ADMIN_PASSWORD_MAX_LENGTH = 64;

    /** 상한을 넘는 입력 대신 비교하는 자리값 — 실패 경로도 BCrypt 비교를 한 번 해 시간이 갈리지 않게. */
    private static final String OVERSIZED_PLACEHOLDER = "oversized-password-placeholder";

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;

    /** 회원이 없을 때 비교할 해시. 같은 인코더(같은 강도)로 만들어 비교 시간이 실제 회원과 같다. 원문은 버린다. */
    private final String dummyPasswordHash;

    public MemberLoginProcessor(MemberRepositoryPort memberRepositoryPort, PasswordEncoder passwordEncoder) {
        this.memberRepositoryPort = memberRepositoryPort;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 로그인 이메일을 한 번 정규화한다(앞뒤 공백 제거 · 소문자, {@link Member#normalizeEmail}). Facade 는 이 값 하나를 시도 제한 · 조회 · 창 지우기에
     * 똑같이 넘긴다 (#66 A2 전제 1). 정규화할 수 없는 값(null · 빈 값 · 공백만 · 소문자화로 254자 초과)은 빈 값이다 — 부르는 쪽은 "없는 회원" 과 같게
     * {@link #authenticate}(더미 비교)로 끝낸다. 500 으로 새지 않게 예외를 여기서 흡수한다.
     */
    public Optional<String> normalizeEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Member.normalizeEmail(email));
        } catch (IllegalArgumentException outOfRule) {
            return Optional.empty();
        }
    }

    /** @param normalizedEmail {@link #normalizeEmail} 의 값. 다시 정규화하지 않는다 */
    @Transactional(readOnly = true)
    public Optional<Member> findByEmail(String normalizedEmail) {
        return memberRepositoryPort.findByEmail(normalizedEmail);
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
     * 관리자 로그인(#6) — 비밀번호를 비교하고 관리자 계정이면 돌려준다. {@link #authenticate} 의 거울이다: 회원이 없어도 · 고객이어도 비교는 언제나 한 번 하고,
     * 실패 이유를 나누지 않는다 — 관리자 로그인 경로로 관리자 이메일이 있는지 · 고객 이메일인지 드러나지 않게.
     *
     * <p>비밀번호가 {@value #ADMIN_PASSWORD_MAX_LENGTH}자를 넘으면 맞을 수 없다(시드 규칙 상한). 필드 검증 400 으로 따로 답하지 않고 같은 {@code AUTH_030} 으로 끝내되,
     * 비교는 자리값으로 똑같이 한 번 한다 — 응답 · 시간이 "틀린 비밀번호" 와 같다.
     *
     * @throws MemberException {@link MemberErrorCode#ADMIN_LOGIN_FAILED} — 회원 없음 · 비밀번호 틀림 · 64자 초과 · 고객 계정 · 비활성 관리자
     */
    public Member authenticateAdmin(Optional<Member> candidate, String rawPassword) {
        boolean acceptable = rawPassword != null && rawPassword.length() <= ADMIN_PASSWORD_MAX_LENGTH;
        String hash = candidate.map(Member::passwordHash).orElse(dummyPasswordHash);
        boolean matches = matches(acceptable ? rawPassword : OVERSIZED_PLACEHOLDER, hash) && acceptable;
        return candidate
            .filter(member -> matches && member.isActiveAdmin())
            .orElseThrow(() -> new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));
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
