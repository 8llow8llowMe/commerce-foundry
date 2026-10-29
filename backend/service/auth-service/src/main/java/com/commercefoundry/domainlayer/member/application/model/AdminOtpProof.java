package com.commercefoundry.domainlayer.member.application.model;

import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import java.util.Objects;

/**
 * 해제 · 복구 코드 재발급의 두 번째 인자를 트랜잭션 밖에서 확인한 결과 (#238 PR-A2). 아직 소비하지 않았다 — 소비(조건부 UPDATE)는 자격 행을 잠근
 * 트랜잭션 안에서 결과(삭제 · 교체)와 함께 한다. 코드 원문은 담지 않는다.
 */
public sealed interface AdminOtpProof {

    /**
     * 맞는 TOTP 코드.
     *
     * @param accepted 일치한 step 을 기록한 자격({@link AdminOtpCredential#accept}) — 소비는 이 step 의 조건부 UPDATE 다
     */
    record TotpCode(AdminOtpCredential accepted) implements AdminOtpProof {

        public TotpCode {
            Objects.requireNonNull(accepted, "accepted");
        }
    }

    /**
     * 해시가 맞은 미사용 복구 코드.
     *
     * @param recoveryCodeId 그 코드의 아이디 — 소비는 이 행의 "아직 안 썼을 때만" 조건부 UPDATE 다
     */
    record RecoveryCodeMatch(long recoveryCodeId) implements AdminOtpProof {
    }
}
