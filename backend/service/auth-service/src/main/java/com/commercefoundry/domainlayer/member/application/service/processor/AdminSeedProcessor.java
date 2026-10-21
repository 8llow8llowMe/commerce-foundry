package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초기 관리자 시드 (#6, {@code security-guide.md} §11-3). 설정 규칙 판정({@link #validate}), 해시(BCrypt — 트랜잭션 밖), 저장(트랜잭션 안)을 나눠 둔다 —
 * 회원가입({@link MemberSignUpProcessor})과 같은 이유다. 결과 로그는 진입점({@code AdminSeedRunner})이 한 줄로 남기고 여기는 debug(memberId)만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeedProcessor {

    /** 관리자 비밀번호 — 12~64자, 영문 · 숫자를 1자 이상씩 포함한 공백 없는 ASCII 출력 문자. 가입(8자)보다 길게 — 관리자 계정은 탈취 비용이 크다. */
    static final Pattern PASSWORD_RULE = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]{12,64}$");

    /** 가입 요청과 같은 이메일 형식 — 도메인에 점이 하나 이상. 정규화(앞뒤 공백 제거 · 소문자)한 값에 건다. */
    private static final Pattern EMAIL_RULE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static final String DEFAULT_NAME = "관리자";

    private final MemberRepositoryPort memberRepositoryPort;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 설정 규칙을 보고 정규화한 커맨드(소문자 이메일 · 앞뒤 공백 없는 이름 · 비면 {@value #DEFAULT_NAME})를 돌려준다. 이메일 · 비밀번호가 모두 비면 빈 값이다.
     * 공백 판정은 도메인 {@code strip()} 과 같은 {@link Character#isWhitespace} 다.
     *
     * @throws IllegalStateException 하나만 있다 · 비밀번호 · 이메일 · 이름 규칙 위반. 메시지에 값을 싣지 않는다 — 기동을 멈춘다
     */
    public Optional<AdminSeedCommand> validate(AdminSeedCommand command) {
        boolean hasEmail = hasText(command.email());
        boolean hasPassword = hasText(command.rawPassword());
        if (!hasEmail && !hasPassword) {
            return Optional.empty();
        }
        if (hasEmail != hasPassword) {
            throw new IllegalStateException("auth.admin-seed.email 과 auth.admin-seed.password 는 함께 넣거나 함께 비워야 합니다");
        }
        if (!PASSWORD_RULE.matcher(command.rawPassword()).matches()) {
            throw new IllegalStateException("auth.admin-seed.password 는 12~64자, 영문 · 숫자를 1자 이상씩 포함한 공백 없는 영문 · 숫자 · 특수문자여야 합니다");
        }
        String email = normalizeEmail(command.email());
        String name = hasText(command.name()) ? command.name().strip() : DEFAULT_NAME;
        if (name.length() > Member.NAME_MAX_LENGTH) {
            throw new IllegalStateException("auth.admin-seed.name 은 50자 이하여야 합니다");
        }
        return Optional.of(new AdminSeedCommand(email, command.rawPassword(), name));
    }

    /** BCrypt 해시. 원문과 해시를 로그에 남기지 않는다. */
    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 같은 이메일이 없으면 관리자를 만든다(동의 이력 없음). 있으면 아무것도 바꾸지 않는다 — 관리자면 비밀번호를 덮어쓰지 않고, 고객이면 승격하지 않는다.
     *
     * @param email 정규화한 이메일
     * @throws MemberException {@link MemberErrorCode#DUPLICATE_EMAIL} — 조회 뒤 다른 인스턴스가 먼저 넣었다(유니크 키). 이 트랜잭션은 롤백 전용이 되므로
     *                         부르는 쪽(트랜잭션 밖)이 받아 건너뜀으로 바꾼다
     */
    @Transactional
    public AdminSeedOutcome createIfAbsent(String email, String passwordHash, String name) {
        Optional<Member> existing = memberRepositoryPort.findByEmail(email);
        if (existing.isPresent()) {
            Member member = existing.get();
            log.debug("admin seed found existing member: memberId={}, role={}", member.id(), member.role());
            return member.isAdmin() ? AdminSeedOutcome.ALREADY_ADMIN : AdminSeedOutcome.CUSTOMER_EXISTS;
        }
        Member admin = memberRepositoryPort.save(Member.admin(snowflakeIdGenerator.generateId(), email, passwordHash, name));
        log.debug("admin seed created: memberId={}", admin.id());
        return AdminSeedOutcome.CREATED;
    }

    private static String normalizeEmail(String rawEmail) {
        String email;
        try {
            email = Member.normalizeEmail(rawEmail);
        } catch (IllegalArgumentException outOfRule) {
            throw new IllegalStateException("auth.admin-seed.email 이 254자를 넘습니다");
        }
        if (!EMAIL_RULE.matcher(email).matches()) {
            throw new IllegalStateException("auth.admin-seed.email 형식이 올바르지 않습니다");
        }
        return email;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
