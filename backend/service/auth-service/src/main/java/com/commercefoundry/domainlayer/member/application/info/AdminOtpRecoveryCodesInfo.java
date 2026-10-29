package com.commercefoundry.domainlayer.member.application.info;

import java.util.List;
import java.util.Objects;

/**
 * 새로 발급한 복구 코드 원문 (#238 PR-A2) — 등록 확인 · 재발급 응답에 이번 한 번만 싣는다. 서버에는 해시만 남는다.
 *
 * @param recoveryCodes 표시 형식({@code xxxxx-xxxxx}) 원문. 로그에 남기지 않는다
 */
public record AdminOtpRecoveryCodesInfo(List<String> recoveryCodes) {

    public AdminOtpRecoveryCodesInfo {
        recoveryCodes = List.copyOf(Objects.requireNonNull(recoveryCodes, "recoveryCodes"));
    }

    /** 원문을 가린다. */
    @Override
    public String toString() {
        return "AdminOtpRecoveryCodesInfo[count=" + recoveryCodes.size() + "]";
    }
}
