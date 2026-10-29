package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpEnrollmentInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof.RecoveryCodeMatch;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof.TotpCode;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpEnrollmentStorePort;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.Base32;
import com.commercefoundry.domainlayer.member.domain.model.RecoveryCode;
import com.commercefoundry.domainlayer.member.domain.model.Totp;
import com.commercefoundry.global.properties.AdminOtpProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 관리자 OTP Processor (#238 PR-A2) — 등록 시작 · 확인 · 두 번째 인자 확인 · 소비. 저장소는 조건부 UPDATE 의미를 흉내 낸 메모리 구현이다.
 * 잠금 · 롤백 · 동시성은 {@code AdminOtpConcurrencyTest}(PostgreSQL)가 본다. 값은 전부 테스트 전용이다.
 */
class AdminOtpProcessorTest {

    private static final long MEMBER_ID = 4242L;
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:10Z");
    private static final long NOW_STEP = Totp.timeStep(NOW);
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final AdminOtpProperties PROPERTIES = new AdminOtpProperties(
        AdminOtpSecretCipherTest.KEY, null, "Test Shop", Duration.ofMinutes(15), Duration.ofMinutes(5), true);

    private final InMemoryAdminOtpRepository repository = new InMemoryAdminOtpRepository();
    private final AdminOtpEnrollmentStorePort enrollmentStore = mock(AdminOtpEnrollmentStorePort.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AdminOtpProcessor processor = new AdminOtpProcessor(repository, enrollmentStore, encoder, new SnowflakeIdGenerator(1, 1), PROPERTIES);

    private void enroll(long lastUsedStep) {
        repository.save(AdminOtpCredential.confirm(7001L, MEMBER_ID, SECRET, lastUsedStep, NOW));
    }

    private List<String> enrollWithCodes() {
        enroll(NOW_STEP - 10);
        IssuedRecoveryCodes issued = processor.issueRecoveryCodes();
        repository.replaceRecoveryCodes(MEMBER_ID, issued.hashes());
        return issued.codes();
    }

    private static void assertCode(Throwable thrown, MemberErrorCode code) {
        assertThat(thrown).isInstanceOf(MemberException.class);
        assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("상태 — 미등록이면 false · null · 0, 등록돼 있으면 확인 시각과 안 쓴 복구 코드 수")
    void status() {
        assertThat(processor.status(MEMBER_ID)).satisfies(info -> {
            assertThat(info.enrolled()).isFalse();
            assertThat(info.confirmedAt()).isNull();
            assertThat(info.remainingRecoveryCodes()).isZero();
        });

        enrollWithCodes();
        repository.markRecoveryCodeUsed(repository.findUnusedRecoveryCodes(MEMBER_ID).getFirst().id(), MEMBER_ID, NOW);

        assertThat(processor.status(MEMBER_ID)).satisfies(info -> {
            assertThat(info.enrolled()).isTrue();
            assertThat(info.confirmedAt()).isEqualTo(NOW);
            assertThat(info.remainingRecoveryCodes()).isEqualTo(RecoveryCode.COUNT - 1L);
        });
    }

    @Test
    @DisplayName("등록 시작 — 20바이트 비밀을 TTL 15분으로 저장하고, 같은 비밀의 Base32 · 등록 URI(발급자 · 이메일 라벨) · 만료 시각을 돌려준다")
    void startEnrollment() {
        AdminOtpEnrollmentInfo info = processor.startEnrollment(MEMBER_ID, "owner@example.com", NOW);

        ArgumentCaptor<byte[]> saved = ArgumentCaptor.forClass(byte[].class);
        verify(enrollmentStore).save(eq(MEMBER_ID), saved.capture(), eq(Duration.ofMinutes(15)));
        assertThat(saved.getValue()).hasSize(Totp.SECRET_BYTES);
        assertThat(info.secret()).isEqualTo(Base32.encode(saved.getValue())).hasSize(32);
        assertThat(info.otpauthUri()).startsWith("otpauth://totp/Test%20Shop:owner%40example.com?secret=" + info.secret() + "&issuer=Test%20Shop");
        assertThat(info.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(info.toString()).doesNotContain(info.secret());
    }

    @Test
    @DisplayName("이미 확정 자격이 있으면 등록 시작은 409 AUTH_037 이고 비밀을 만들지 않는다")
    void startEnrollmentRejectsEnrolled() {
        enroll(1L);

        assertCode(catchThrown(() -> processor.startEnrollment(MEMBER_ID, "owner@example.com", NOW)), MemberErrorCode.OTP_ALREADY_ENROLLED);
        verify(enrollmentStore, never()).save(anyLong(), any(), any());
    }

    @Test
    @DisplayName("진행 중 등록이 없으면 AUTH_038, 있으면 그 비밀이다")
    void findEnrollment() {
        given(enrollmentStore.find(MEMBER_ID)).willReturn(Optional.empty());
        assertCode(catchThrown(() -> processor.findEnrollment(MEMBER_ID)), MemberErrorCode.OTP_ENROLLMENT_NOT_FOUND);

        given(enrollmentStore.find(MEMBER_ID)).willReturn(Optional.of(SECRET));
        assertThat(processor.findEnrollment(MEMBER_ID)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("등록 확인 코드 — 앞뒤 공백을 지우고 ±1 step 안이면 그 step, 아니면 AUTH_035")
    void verifyEnrollmentCode() {
        assertThat(processor.verifyEnrollmentCode(SECRET, " " + Totp.code(SECRET, NOW_STEP - 1) + "\t", NOW)).isEqualTo(NOW_STEP - 1);
        assertCode(catchThrown(() -> processor.verifyEnrollmentCode(SECRET, Totp.code(SECRET, NOW_STEP - 2), NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertCode(catchThrown(() -> processor.verifyEnrollmentCode(SECRET, null, NOW)), MemberErrorCode.OTP_CODE_INVALID);
    }

    @Test
    @DisplayName("복구 코드 8개는 서로 다르고 표시 형식이며, 해시는 정규화한 값(대문자 · 하이픈 없음)의 BCrypt 다. toString 은 원문을 싣지 않는다")
    void issueRecoveryCodes() {
        IssuedRecoveryCodes issued = processor.issueRecoveryCodes();

        assertThat(issued.codes()).hasSize(RecoveryCode.COUNT).doesNotHaveDuplicates().allMatch(code -> code.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));
        for (int i = 0; i < issued.codes().size(); i++) {
            assertThat(encoder.matches(issued.codes().get(i).replace("-", ""), issued.hashes().get(i))).isTrue();
        }
        assertThat(issued.toString()).doesNotContain(issued.codes().getFirst());
    }

    @Test
    @DisplayName("확인 — 확인한 step 을 마지막 step 으로 저장하고 복구 코드를 넣는다. 같은 관리자로 두 번이면 AUTH_037")
    void confirm() {
        IssuedRecoveryCodes issued = processor.issueRecoveryCodes();

        processor.confirm(MEMBER_ID, SECRET, NOW_STEP, NOW, issued.hashes());

        AdminOtpCredential saved = repository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(saved.lastUsedTimeStep()).isEqualTo(NOW_STEP);
        assertThat(saved.secret()).isEqualTo(SECRET);
        assertThat(repository.countUnusedRecoveryCodes(MEMBER_ID)).isEqualTo(RecoveryCode.COUNT);
        assertCode(catchThrown(() -> processor.confirm(MEMBER_ID, SECRET, NOW_STEP, NOW, issued.hashes())), MemberErrorCode.OTP_ALREADY_ENROLLED);
    }

    @Test
    @DisplayName("진행 중 등록 지우기는 저장소 장애(AUTH_013)를 삼키고, 다른 오류는 던진다")
    void discardEnrollmentIsBestEffort() {
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(enrollmentStore).delete(MEMBER_ID);
        assertThatCode(() -> processor.discardEnrollment(MEMBER_ID)).doesNotThrowAnyException();

        willThrow(new MemberException(MemberErrorCode.OTP_UNAVAILABLE)).given(enrollmentStore).delete(MEMBER_ID);
        assertCode(catchThrown(() -> processor.discardEnrollment(MEMBER_ID)), MemberErrorCode.OTP_UNAVAILABLE);
    }

    @Test
    @DisplayName("두 번째 인자 — 미등록이면 TOTP · 복구 코드 모두 AUTH_036")
    void resolveProofNotEnrolled() {
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, Totp.code(SECRET, NOW_STEP), NOW)), MemberErrorCode.OTP_NOT_ENROLLED);
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, "ABCDE-FGH23", NOW)), MemberErrorCode.OTP_NOT_ENROLLED);
    }

    @Test
    @DisplayName("두 번째 인자 TOTP — 맞으면 그 step 을 기록할 자격, 틀리거나 이미 쓴 step 이면 AUTH_035")
    void resolveTotp() {
        enroll(NOW_STEP - 1);

        AdminOtpProof proof = processor.resolveProof(MEMBER_ID, Totp.code(SECRET, NOW_STEP), NOW);

        assertThat(proof).isInstanceOfSatisfying(TotpCode.class, totp -> assertThat(totp.accepted().lastUsedTimeStep()).isEqualTo(NOW_STEP));
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, Totp.code(SECRET, NOW_STEP - 1), NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, "000000".equals(Totp.code(SECRET, NOW_STEP)) ? "111111" : "000000", NOW)),
            MemberErrorCode.OTP_CODE_INVALID);
    }

    @Test
    @DisplayName("두 번째 인자 복구 코드 — 소문자 · 하이픈 없이 쳐도 그 코드 아이디, 형식이 틀리거나 없는 코드면 AUTH_035")
    void resolveRecoveryCode() {
        List<String> codes = enrollWithCodes();
        long expectedId = repository.findUnusedRecoveryCodes(MEMBER_ID).get(2).id();

        AdminOtpProof proof = processor.resolveProof(MEMBER_ID, " " + codes.get(2).toLowerCase().replace("-", "") + " ", NOW);

        assertThat(proof).isEqualTo(new RecoveryCodeMatch(expectedId));
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, "ABCDE-FGH2", NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, "ZZZZZ-ZZZZZ", NOW)), MemberErrorCode.OTP_CODE_INVALID);
    }

    @Test
    @DisplayName("완료 기준 — 재발급에 쓴 TOTP 코드를 같은 step 에 다시 쓰면 해제는 AUTH_035 이고 자격이 남는다")
    void totpCannotBeReused() {
        enrollWithCodes();
        String code = Totp.code(SECRET, NOW_STEP);

        processor.replaceRecoveryCodes(MEMBER_ID, processor.resolveProof(MEMBER_ID, code, NOW), processor.issueRecoveryCodes().hashes(), NOW);

        assertCode(catchThrown(() -> processor.disable(MEMBER_ID, processor.resolveProof(MEMBER_ID, code, NOW), NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertThat(repository.findByMemberId(MEMBER_ID)).isPresent();
    }

    @Test
    @DisplayName("완료 기준 — 같은 TOTP 를 두 요청이 동시에 확인해도(소비 전) 소비는 한 번만 된다. 늦은 쪽은 AUTH_035 이고 결과를 쓰지 않는다")
    void totpConsumedOnce() {
        List<String> before = enrollWithCodes();
        String code = Totp.code(SECRET, NOW_STEP);
        AdminOtpProof first = processor.resolveProof(MEMBER_ID, code, NOW);
        AdminOtpProof second = processor.resolveProof(MEMBER_ID, code, NOW);

        processor.replaceRecoveryCodes(MEMBER_ID, first, processor.issueRecoveryCodes().hashes(), NOW);

        assertCode(catchThrown(() -> processor.disable(MEMBER_ID, second, NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertThat(repository.findByMemberId(MEMBER_ID)).isPresent();
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, before.getFirst(), NOW)), MemberErrorCode.OTP_CODE_INVALID);
    }

    @Test
    @DisplayName("완료 기준 — 쓴 복구 코드는 다시 쓸 수 없다. 소비 전에 두 번 확인했어도 늦은 쪽은 AUTH_035 이고 복구 코드를 교체하지 않는다")
    void recoveryCodeIsSingleUse() {
        List<String> codes = enrollWithCodes();
        AdminOtpProof first = processor.resolveProof(MEMBER_ID, codes.get(1), NOW);
        AdminOtpProof second = processor.resolveProof(MEMBER_ID, codes.get(1), NOW);

        processor.replaceRecoveryCodes(MEMBER_ID, first, processor.issueRecoveryCodes().hashes(), NOW);
        List<Long> afterFirst = repository.findUnusedRecoveryCodes(MEMBER_ID).stream().map(code -> code.id()).toList();

        assertCode(catchThrown(() -> processor.replaceRecoveryCodes(MEMBER_ID, second, processor.issueRecoveryCodes().hashes(), NOW)),
            MemberErrorCode.OTP_CODE_INVALID);
        assertThat(repository.findUnusedRecoveryCodes(MEMBER_ID).stream().map(code -> code.id()).toList()).isEqualTo(afterFirst);
        assertCode(catchThrown(() -> processor.resolveProof(MEMBER_ID, codes.get(1), NOW)), MemberErrorCode.OTP_CODE_INVALID);
    }

    @Test
    @DisplayName("해제 — 소비한 뒤 자격과 복구 코드를 모두 지운다. 이미 해제됐으면 AUTH_036")
    void disable() {
        List<String> codes = enrollWithCodes();
        AdminOtpProof proof = processor.resolveProof(MEMBER_ID, codes.getFirst(), NOW);

        processor.disable(MEMBER_ID, proof, NOW);

        assertThat(repository.findByMemberId(MEMBER_ID)).isEmpty();
        assertThat(repository.codes).isEmpty();
        assertCode(catchThrown(() -> processor.disable(MEMBER_ID, proof, NOW)), MemberErrorCode.OTP_NOT_ENROLLED);
    }

    @Test
    @DisplayName("해제 후 다시 등록한 새 자격에는 옛 자격으로 확인한 TOTP 를 쓰지 못한다 — 자격 아이디가 달라 AUTH_035")
    void totpProofBoundToCredential() {
        enroll(NOW_STEP - 5);
        AdminOtpProof stale = processor.resolveProof(MEMBER_ID, Totp.code(SECRET, NOW_STEP), NOW);
        repository.deleteByMemberId(MEMBER_ID);
        repository.save(AdminOtpCredential.confirm(7002L, MEMBER_ID, SECRET, NOW_STEP - 5, NOW));

        assertCode(catchThrown(() -> processor.disable(MEMBER_ID, stale, NOW)), MemberErrorCode.OTP_CODE_INVALID);
        assertThat(repository.findByMemberId(MEMBER_ID)).isPresent();
    }

    private static Throwable catchThrown(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.catchThrowable(callable);
    }
}
