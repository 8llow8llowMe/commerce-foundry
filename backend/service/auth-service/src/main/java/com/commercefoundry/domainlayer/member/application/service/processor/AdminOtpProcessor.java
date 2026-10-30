package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpEnrollmentInfo;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpStatusInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof.RecoveryCodeMatch;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof.TotpCode;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpEnrollmentStorePort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpRecoveryCode;
import com.commercefoundry.domainlayer.member.domain.model.Base32;
import com.commercefoundry.domainlayer.member.domain.model.RecoveryCode;
import com.commercefoundry.domainlayer.member.domain.model.Totp;
import com.commercefoundry.global.properties.AdminOtpProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 OTP 등록 · 확인 · 해제 · 복구 코드 재발급 (#238 PR-A2, API 계약은 {@code api-design-guide.md} §2-4).
 *
 * <p>트랜잭션은 DB 쓰기만 감싼다. 무거운 일(BCrypt 해시 · 비교)과 Redis(진행 중 등록)는 트랜잭션 밖이다:
 * <ul>
 *   <li>{@link #resolveProof} — 두 번째 인자(TOTP 또는 복구 코드)를 밖에서 확인만 한다. 복구 코드는 미사용 코드(최대 8개)와 BCrypt 로 비교해 후보 아이디를 찾는다 —
 *       트랜잭션 안에서 8번 비교하면 그동안 자격 행 잠금을 쥐어 같은 관리자의 다른 요청이 {@code AUTH_044} 로 밀린다</li>
 *   <li>{@link #disable} · {@link #replaceRecoveryCodes} — 자격 행 잠금 → 코드 소비(조건부 UPDATE) → 삭제 · 교체를 <b>한 트랜잭션</b>에서 한다. 소비가 0행이면(같은 코드
 *       재사용 · 다른 요청이 먼저 씀 · 재발급으로 사라진 복구 코드) {@code AUTH_035} 이고 아무것도 바뀌지 않는다. 소비와 결과가 갈라지지 않는다</li>
 *   <li>{@link #confirm} — 자격 저장 + 복구 코드 넣기를 한 트랜잭션에서 한다. 동시 확인 두 건은 유니크 키로 한쪽이 {@code AUTH_037} 이다</li>
 *   <li>{@link #consumeForLogin} — 로그인 2단계(PR-B)도 {@link #resolveProof} 로 밖에서 확인하고, 자격 잠금 → 코드 소비만 한 트랜잭션에서 한다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminOtpProcessor {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminOtpRepositoryPort adminOtpRepositoryPort;
    private final AdminOtpEnrollmentStorePort adminOtpEnrollmentStorePort;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final AdminOtpProperties adminOtpProperties;

    /** 확정 자격이 있는가 — 관리자 로그인 1단계가 2단계를 요구할지 정한다 (#238 PR-B). 비밀을 복호화하지 않는다(암호화 키 문제와 무관하다). */
    @Transactional(readOnly = true)
    public boolean isEnrolled(long memberId) {
        return adminOtpRepositoryPort.findConfirmedAt(memberId).isPresent();
    }

    /** 자격과 남은 복구 코드 수를 한 읽기 트랜잭션에서 읽는다. 비밀을 복호화하지 않는다. */
    @Transactional(readOnly = true)
    public AdminOtpStatusInfo status(long memberId) {
        Optional<Instant> confirmedAt = adminOtpRepositoryPort.findConfirmedAt(memberId);
        if (confirmedAt.isEmpty()) {
            return new AdminOtpStatusInfo(false, null, 0);
        }
        return new AdminOtpStatusInfo(true, confirmedAt.get(), adminOtpRepositoryPort.countUnusedRecoveryCodes(memberId));
    }

    /**
     * 새 비밀을 만들어 진행 중 등록으로 둔다(같은 관리자의 이전 진행 중 등록은 덮는다). 이미 확정 자격이 있으면 {@code AUTH_037} — 바꾸려면 해제부터 한다.
     *
     * @param accountName 인증 앱에 보일 계정 이름(관리자 이메일). 응답 URI 에만 들어가고 저장 · 로그에 남기지 않는다
     */
    public AdminOtpEnrollmentInfo startEnrollment(long memberId, String accountName, Instant now) {
        if (adminOtpRepositoryPort.findConfirmedAt(memberId).isPresent()) {
            throw new MemberException(MemberErrorCode.OTP_ALREADY_ENROLLED);
        }
        byte[] secret = new byte[Totp.SECRET_BYTES];
        RANDOM.nextBytes(secret);
        adminOtpEnrollmentStorePort.save(memberId, secret, adminOtpProperties.enrollmentTtl());
        String encoded = Base32.encode(secret);
        return new AdminOtpEnrollmentInfo(encoded, Totp.provisioningUri(adminOtpProperties.issuer(), accountName, encoded),
            now.plus(adminOtpProperties.enrollmentTtl()));
    }

    /** 진행 중인 등록의 비밀. 없거나 만료됐으면 {@code AUTH_038}. */
    public byte[] findEnrollment(long memberId) {
        return adminOtpEnrollmentStorePort.find(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.OTP_ENROLLMENT_NOT_FOUND));
    }

    /** 등록 확인 코드를 진행 중 비밀로 검증해 맞은 step 을 돌려준다. 틀리면 {@code AUTH_035}. */
    public long verifyEnrollmentCode(byte[] secret, String code, Instant now) {
        OptionalLong step = Totp.verify(secret, strip(code), Totp.timeStep(now));
        if (step.isEmpty()) {
            throw new MemberException(MemberErrorCode.OTP_CODE_INVALID);
        }
        return step.getAsLong();
    }

    /** 복구 코드 {@value RecoveryCode#COUNT}개와 그 해시. BCrypt 가 무거워 트랜잭션 밖에서 부른다. */
    public IssuedRecoveryCodes issueRecoveryCodes() {
        List<String> codes = RecoveryCode.generate(RANDOM, RecoveryCode.COUNT);
        List<String> hashes = codes.stream()
            .map(code -> passwordEncoder.encode(RecoveryCode.normalize(code).orElseThrow()))
            .toList();
        return new IssuedRecoveryCodes(codes, hashes);
    }

    /**
     * 확인한 등록을 확정 자격으로 저장하고 복구 코드를 넣는다 — 한 트랜잭션. 확인에 쓴 step 을 마지막 step 으로 둔다(같은 코드로 바로 다시 쓰지 못하게).
     * 같은 관리자의 자격이 이미 있으면(동시 확인) {@code AUTH_037} 이고 둘 다 롤백된다.
     */
    @Transactional
    public void confirm(long memberId, byte[] secret, long confirmedTimeStep, Instant now, List<String> recoveryCodeHashes) {
        adminOtpRepositoryPort.save(AdminOtpCredential.confirm(snowflakeIdGenerator.generateId(), memberId, secret, confirmedTimeStep, now));
        adminOtpRepositoryPort.replaceRecoveryCodes(memberId, recoveryCodeHashes);
    }

    /**
     * 확인이 끝난 진행 중 등록을 지운다 — best-effort. 자격은 이미 커밋됐고, 남은 등록은 TTL 로 사라지며 다시 확인해도 {@code AUTH_037} 이라 해가 없다.
     * 저장소 장애가 아닌 오류(버그)는 삼키지 않는다.
     */
    public void discardEnrollment(long memberId) {
        try {
            adminOtpEnrollmentStorePort.delete(memberId);
        } catch (MemberException failure) {
            if (failure.getErrorCode() != MemberErrorCode.TOKEN_STORE_UNAVAILABLE) {
                throw failure;
            }
            log.warn("admin otp enrollment discard failed (best-effort, expires by ttl)");
        }
    }

    /**
     * 해제 · 재발급의 두 번째 인자(로그인 2단계의 코드도)를 트랜잭션 밖에서 확인한다 — 소비는 하지 않는다. 앞뒤 공백은 지운다.
     * <ul>
     *   <li>6자리 숫자면 TOTP — 자격의 비밀로 검증하고, 이미 쓴 step 이면 여기서 거절한다</li>
     *   <li>복구 코드 형식이면 정규화한 뒤 미사용 코드와 BCrypt 로 비교한다</li>
     * </ul>
     * 자격이 없으면 {@code AUTH_036}, 맞지 않으면 {@code AUTH_035} 다 — 형식이 틀렸는지 · 틀린 코드인지 · 이미 쓴 코드인지는 나누지 않는다.
     */
    public AdminOtpProof resolveProof(long memberId, String code, Instant now) {
        String stripped = strip(code);
        if (stripped.length() == Totp.DIGITS && stripped.chars().allMatch(c -> c >= '0' && c <= '9')) {
            AdminOtpCredential credential = adminOtpRepositoryPort.findByMemberId(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.OTP_NOT_ENROLLED));
            OptionalLong step = Totp.verify(credential.secret(), stripped, Totp.timeStep(now));
            if (step.isEmpty()) {
                throw new MemberException(MemberErrorCode.OTP_CODE_INVALID);
            }
            return new TotpCode(credential.accept(step.getAsLong()).orElseThrow(() -> new MemberException(MemberErrorCode.OTP_CODE_INVALID)));
        }
        if (adminOtpRepositoryPort.findConfirmedAt(memberId).isEmpty()) {
            throw new MemberException(MemberErrorCode.OTP_NOT_ENROLLED);
        }
        String normalized = RecoveryCode.normalize(stripped).orElseThrow(() -> new MemberException(MemberErrorCode.OTP_CODE_INVALID));
        return adminOtpRepositoryPort.findUnusedRecoveryCodes(memberId).stream()
            .filter(candidate -> hashMatches(normalized, candidate))
            .findFirst()
            .map(candidate -> (AdminOtpProof) new RecoveryCodeMatch(candidate.id()))
            .orElseThrow(() -> new MemberException(MemberErrorCode.OTP_CODE_INVALID));
    }

    /** 자격 잠금 → 코드 소비 → 자격 · 복구 코드 삭제. 한 트랜잭션이다. */
    @Transactional
    public void disable(long memberId, AdminOtpProof proof, Instant now) {
        lockAndConsume(memberId, proof, now);
        adminOtpRepositoryPort.deleteByMemberId(memberId);
    }

    /**
     * 관리자 로그인 2단계의 코드 소비 (#238 PR-B) — 자격 잠금 → 코드 소비(조건부 UPDATE)만 한 트랜잭션에서 한다. 해제 · 재발급과 같은 잠금으로 줄 서므로
     * 해제와 겹친 로그인이 해제된 자격의 코드로 통과하지 못한다(앞선 해제가 이기면 {@code AUTH_036}). TOTP 소비가 이전 키의 암호문이면 같은 문장에서 현재 키로 다시 암호화한다.
     */
    @Transactional
    public void consumeForLogin(long memberId, AdminOtpProof proof, Instant now) {
        lockAndConsume(memberId, proof, now);
    }

    /** 자격 잠금 → 코드 소비 → 복구 코드 교체(이전 코드는 모두 지운다). 한 트랜잭션이다. */
    @Transactional
    public void replaceRecoveryCodes(long memberId, AdminOtpProof proof, List<String> recoveryCodeHashes, Instant now) {
        lockAndConsume(memberId, proof, now);
        adminOtpRepositoryPort.replaceRecoveryCodes(memberId, recoveryCodeHashes);
    }

    /**
     * 잠금을 먼저 잡아 같은 관리자의 해제 · 재발급을 줄 세운다(대기 상한 → {@code AUTH_044}). 앞선 요청이 해제했으면 자격이 없어 {@code AUTH_036} 이다.
     * TOTP 는 자격 아이디까지 맞아야 기록된다 — 해제 후 다시 등록한 새 자격에 옛 확인 결과를 쓰지 못한다.
     */
    private void lockAndConsume(long memberId, AdminOtpProof proof, Instant now) {
        if (!adminOtpRepositoryPort.lockByMemberId(memberId)) {
            throw new MemberException(MemberErrorCode.OTP_NOT_ENROLLED);
        }
        boolean consumed = switch (proof) {
            case TotpCode totp -> totp.accepted().memberId() == memberId && adminOtpRepositoryPort.recordTimeStep(totp.accepted(), now);
            case RecoveryCodeMatch recovery -> adminOtpRepositoryPort.markRecoveryCodeUsed(recovery.recoveryCodeId(), memberId, now);
        };
        if (!consumed) {
            throw new MemberException(MemberErrorCode.OTP_CODE_INVALID);
        }
    }

    /** BCrypt 형식이 아닌 저장값은 맞지 않는 것으로 본다(예외로 500 을 내지 않는다). */
    private boolean hashMatches(String normalized, AdminOtpRecoveryCode candidate) {
        try {
            return passwordEncoder.matches(normalized, candidate.codeHash());
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static String strip(String code) {
        return code == null ? "" : code.strip();
    }
}
