package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpRecoveryCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processor 단위 테스트용 관리자 OTP 저장소 — 어댑터의 조건부 UPDATE 의미(마지막 step 보다 늦을 때만 · 안 쓴 코드일 때만)를 메모리로 흉내 낸다.
 * 잠금 · 트랜잭션 롤백은 흉내 내지 않는다 — 그건 {@code AdminOtpConcurrencyTest}(PostgreSQL)가 본다.
 */
class InMemoryAdminOtpRepository implements AdminOtpRepositoryPort {

    private record StoredCode(long id, long memberId, String codeHash, Instant usedAt) {
    }

    final Map<Long, AdminOtpCredential> credentials = new HashMap<>();
    final List<StoredCode> codes = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(9000);

    @Override
    public Optional<AdminOtpCredential> findByMemberId(long memberId) {
        return Optional.ofNullable(credentials.get(memberId));
    }

    @Override
    public Optional<Instant> findConfirmedAt(long memberId) {
        return findByMemberId(memberId).map(AdminOtpCredential::confirmedAt);
    }

    @Override
    public long countUnusedRecoveryCodes(long memberId) {
        return codes.stream().filter(code -> code.memberId() == memberId && code.usedAt() == null).count();
    }

    @Override
    public boolean lockByMemberId(long memberId) {
        return credentials.containsKey(memberId);
    }

    @Override
    public AdminOtpCredential save(AdminOtpCredential credential) {
        if (credentials.containsKey(credential.memberId())) {
            throw new MemberException(MemberErrorCode.OTP_ALREADY_ENROLLED);
        }
        credentials.put(credential.memberId(), credential);
        return credential;
    }

    @Override
    public boolean recordTimeStep(AdminOtpCredential accepted, Instant now) {
        AdminOtpCredential stored = credentials.get(accepted.memberId());
        if (stored == null || stored.id() != accepted.id() || stored.lastUsedTimeStep() >= accepted.lastUsedTimeStep()) {
            return false;
        }
        credentials.put(accepted.memberId(), accepted);
        return true;
    }

    @Override
    public void deleteByMemberId(long memberId) {
        codes.removeIf(code -> code.memberId() == memberId);
        credentials.remove(memberId);
    }

    @Override
    public List<AdminOtpRecoveryCode> findUnusedRecoveryCodes(long memberId) {
        return codes.stream()
            .filter(code -> code.memberId() == memberId && code.usedAt() == null)
            .map(code -> new AdminOtpRecoveryCode(code.id(), code.memberId(), code.codeHash()))
            .toList();
    }

    @Override
    public boolean markRecoveryCodeUsed(long recoveryCodeId, long memberId, Instant usedAt) {
        for (int i = 0; i < codes.size(); i++) {
            StoredCode code = codes.get(i);
            if (code.id() == recoveryCodeId && code.memberId() == memberId && code.usedAt() == null) {
                codes.set(i, new StoredCode(code.id(), code.memberId(), code.codeHash(), usedAt));
                return true;
            }
        }
        return false;
    }

    @Override
    public void replaceRecoveryCodes(long memberId, List<String> codeHashes) {
        if (!credentials.containsKey(memberId)) {
            throw new MemberException(MemberErrorCode.OTP_NOT_ENROLLED);
        }
        codes.removeIf(code -> code.memberId() == memberId);
        codeHashes.forEach(hash -> codes.add(new StoredCode(ids.incrementAndGet(), memberId, hash, null)));
    }
}
