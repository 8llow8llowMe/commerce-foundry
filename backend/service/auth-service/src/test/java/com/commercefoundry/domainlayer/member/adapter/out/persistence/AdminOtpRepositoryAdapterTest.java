package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpCredentialEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpRecoveryCodeEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpCredentialRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpRecoveryCodeRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;

/**
 * 어댑터의 잠금 순서 · 잠금 실패 변환 · 재암호화 판정을 리포지토리 없이 본다 (#238). 실제 SQL(조건부 UPDATE · 잠금)은 {@link AdminOtpPersistenceTest}(PostgreSQL)가 본다.
 */
class AdminOtpRepositoryAdapterTest {

    private static final long MEMBER_ID = 1001L;
    private static final long CREDENTIAL_ID = 7001L;
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    private final AdminOtpCredentialRepository credentialRepository = mock(AdminOtpCredentialRepository.class);
    private final AdminOtpRecoveryCodeRepository recoveryCodeRepository = mock(AdminOtpRecoveryCodeRepository.class);
    private final AdminOtpSecretCipher cipher = AdminOtpSecretCipherTest.cipher(AdminOtpSecretCipherTest.KEY, AdminOtpSecretCipherTest.OTHER_KEY);
    private final AdminOtpSecretCipher oldCipher = AdminOtpSecretCipherTest.cipher(AdminOtpSecretCipherTest.OTHER_KEY, null);
    private final AdminOtpRepositoryAdapter adapter =
        new AdminOtpRepositoryAdapter(credentialRepository, recoveryCodeRepository, cipher, new SnowflakeIdGenerator(1, 1));

    private static AdminOtpCredential accepted(long step) {
        return new AdminOtpCredential(CREDENTIAL_ID, MEMBER_ID, SECRET, step, NOW);
    }

    @Test
    @DisplayName("복구 코드 교체 — 자격 행을 먼저 잠그고(부모) 코드를 지운 뒤 넣는다(자식)")
    void replaceLocksCredentialFirst() {
        when(credentialRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(mock(AdminOtpCredentialEntity.class)));

        adapter.replaceRecoveryCodes(MEMBER_ID, List.of("hash-a", "hash-b"));

        InOrder order = inOrder(credentialRepository, recoveryCodeRepository);
        order.verify(credentialRepository).findByMemberIdForUpdate(MEMBER_ID);
        order.verify(recoveryCodeRepository).deleteByMemberId(MEMBER_ID);
        order.verify(recoveryCodeRepository).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("복구 코드 교체 — 자격이 없으면 AUTH_036 이고 코드를 지우지도 넣지도 않는다(자격 없는 코드가 남지 않는다)")
    void replaceWithoutCredentialRejected() {
        when(credentialRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.replaceRecoveryCodes(MEMBER_ID, List.of("hash-a")))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.OTP_NOT_ENROLLED);
        verifyNoInteractions(recoveryCodeRepository);
    }

    @Test
    @DisplayName("해제 — 자격 행 잠금 → 코드 삭제 → 자격 삭제 순서다")
    void deleteLocksThenDeletesChildThenParent() {
        when(credentialRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(mock(AdminOtpCredentialEntity.class)));

        adapter.deleteByMemberId(MEMBER_ID);

        InOrder order = inOrder(credentialRepository, recoveryCodeRepository);
        order.verify(credentialRepository).findByMemberIdForUpdate(MEMBER_ID);
        order.verify(recoveryCodeRepository).deleteByMemberId(MEMBER_ID);
        order.verify(credentialRepository).deleteByMemberId(MEMBER_ID);
    }

    @Test
    @DisplayName("해제 — 자격이 없으면 아무것도 지우지 않는다")
    void deleteWithoutCredentialIsNoop() {
        when(credentialRepository.findByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());

        adapter.deleteByMemberId(MEMBER_ID);

        verifyNoInteractions(recoveryCodeRepository);
        verify(credentialRepository, never()).deleteByMemberId(anyLong());
    }

    @Test
    @DisplayName("잠금 대기 상한 초과(PostgreSQL 55P03 → CannotAcquireLockException)는 재시도 가능한 409 AUTH_044 — 원인을 싣고 아무것도 쓰지 않는다")
    void lockTimeoutIsBusy() {
        CannotAcquireLockException timeout = new CannotAcquireLockException("lock timeout");
        when(credentialRepository.findByMemberIdForUpdate(MEMBER_ID)).thenThrow(timeout);

        assertThatThrownBy(() -> adapter.replaceRecoveryCodes(MEMBER_ID, List.of("hash-a")))
            .isInstanceOf(MemberException.class).hasCause(timeout).extracting("errorCode").isEqualTo(MemberErrorCode.OTP_BUSY);
        assertThatThrownBy(() -> adapter.deleteByMemberId(MEMBER_ID))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.OTP_BUSY);
        assertThat(MemberErrorCode.OTP_BUSY.getHttpStatus().value()).isEqualTo(409);
        verifyNoInteractions(recoveryCodeRepository);
        verify(credentialRepository, never()).deleteByMemberId(anyLong());
    }

    @Test
    @DisplayName("저장 — 호출자가 정한 아이디로 넣고, 암호문은 그 자격 아이디를 AAD 로 묶는다")
    void saveUsesCallerId() {
        AdminOtpCredential credential = AdminOtpCredential.confirm(CREDENTIAL_ID, MEMBER_ID, SECRET, 100L, NOW);
        ArgumentCaptor<AdminOtpCredentialEntity> saved = ArgumentCaptor.forClass(AdminOtpCredentialEntity.class);

        assertThat(adapter.save(credential)).isEqualTo(credential);

        verify(credentialRepository).saveAndFlush(saved.capture());
        AdminOtpCredentialEntity entity = saved.getValue();
        assertThat(entity.getId()).isEqualTo(CREDENTIAL_ID);
        assertThat(entity.getKeyId()).isEqualTo(cipher.currentKeyId());
        assertThat(cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, entity.getSecretCiphertext(), entity.getKeyId())).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("빌더 toString 은 암호문 · 복구 코드 해시를 싣지 않는다(@ToString.Exclude)")
    void builderToStringExcludesSecrets() {
        assertThat(AdminOtpCredentialEntity.builder().id(1L).secretCiphertext("CIPHERTEXT-VALUE").toString()).contains("id=1").doesNotContain("CIPHERTEXT-VALUE");
        assertThat(AdminOtpRecoveryCodeEntity.builder().id(2L).codeHash("HASH-VALUE").toString()).contains("id=2").doesNotContain("HASH-VALUE");
    }

    @Test
    @DisplayName("사용 기록 — 저장된 키가 현재 키면 step 만 올린다(대상은 accepted 의 자격 아이디 · step)")
    void recordWithCurrentKey() {
        when(credentialRepository.findKeyIdByIdAndMemberId(CREDENTIAL_ID, MEMBER_ID)).thenReturn(Optional.of(cipher.currentKeyId()));
        when(credentialRepository.advanceTimeStep(CREDENTIAL_ID, MEMBER_ID, 101L, NOW)).thenReturn(1);

        assertThat(adapter.recordTimeStep(accepted(101L), NOW)).isTrue();

        verify(credentialRepository, never()).advanceTimeStepAndReseal(anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("사용 기록 — 저장된 키가 옛 키면 옛 키 조건을 건 재암호화 UPDATE 로 간다. 새 암호문은 현재 키 · 같은 AAD 다")
    void recordWithOldKeyReseals() {
        String oldKeyId = oldCipher.currentKeyId();
        when(credentialRepository.findKeyIdByIdAndMemberId(CREDENTIAL_ID, MEMBER_ID)).thenReturn(Optional.of(oldKeyId));
        ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
        when(credentialRepository.advanceTimeStepAndReseal(eq(CREDENTIAL_ID), eq(MEMBER_ID), eq(oldKeyId), eq(101L), ciphertext.capture(),
            eq(cipher.currentKeyId()), eq(NOW))).thenReturn(1);

        assertThat(adapter.recordTimeStep(accepted(101L), NOW)).isTrue();

        assertThat(cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, ciphertext.getValue(), cipher.currentKeyId())).isEqualTo(SECRET);
        verify(credentialRepository, never()).advanceTimeStep(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("사용 기록 — 재암호화가 0행(다른 인스턴스가 먼저 암호문을 바꿈 · step 이 이미 앞섬)이면 암호문은 두고 step 만 기록을 시도한다")
    void recordFallsBackWhenResealLoses() {
        String oldKeyId = oldCipher.currentKeyId();
        when(credentialRepository.findKeyIdByIdAndMemberId(CREDENTIAL_ID, MEMBER_ID)).thenReturn(Optional.of(oldKeyId));
        when(credentialRepository.advanceTimeStepAndReseal(anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(0);
        when(credentialRepository.advanceTimeStep(CREDENTIAL_ID, MEMBER_ID, 101L, NOW)).thenReturn(0);

        assertThat(adapter.recordTimeStep(accepted(101L), NOW)).isFalse();

        verify(credentialRepository).advanceTimeStep(CREDENTIAL_ID, MEMBER_ID, 101L, NOW);
    }

    @Test
    @DisplayName("사용 기록 — 그 자격이 없으면(해제 · 재등록) false 이고 UPDATE 를 보내지 않는다")
    void recordWithoutCredential() {
        when(credentialRepository.findKeyIdByIdAndMemberId(CREDENTIAL_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(adapter.recordTimeStep(accepted(101L), NOW)).isFalse();

        verify(credentialRepository, never()).advanceTimeStep(anyLong(), anyLong(), anyLong(), any());
    }
}
