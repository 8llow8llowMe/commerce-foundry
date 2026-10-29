package com.commercefoundry.domainlayer.member.application.model;

import com.commercefoundry.domainlayer.member.application.info.AdminOtpRecoveryCodesInfo;
import java.util.List;
import java.util.Objects;

/**
 * 새로 만든 복구 코드 묶음 (#238 PR-A2) — 표시 형식 원문(응답에 이번 한 번만)과 저장할 BCrypt 해시. 순서는 서로 맞지만 저장 쪽은 순서를 쓰지 않는다.
 *
 * @param codes  표시 형식({@code xxxxx-xxxxx}) 원문. 로그 · {@code toString} 에 싣지 않는다
 * @param hashes 정규화한 코드의 BCrypt 해시
 */
public record IssuedRecoveryCodes(List<String> codes, List<String> hashes) {

    public IssuedRecoveryCodes {
        codes = List.copyOf(Objects.requireNonNull(codes, "codes"));
        hashes = List.copyOf(Objects.requireNonNull(hashes, "hashes"));
        if (codes.size() != hashes.size()) {
            throw new IllegalArgumentException("codes and hashes must have the same size");
        }
    }

    /** 응답에 실을 원문만 담는다 — 해시는 저장 쪽에만 쓴다. */
    public AdminOtpRecoveryCodesInfo toInfo() {
        return new AdminOtpRecoveryCodesInfo(codes);
    }

    /** 원문 · 해시를 가린다. */
    @Override
    public String toString() {
        return "IssuedRecoveryCodes[count=" + codes.size() + "]";
    }
}
