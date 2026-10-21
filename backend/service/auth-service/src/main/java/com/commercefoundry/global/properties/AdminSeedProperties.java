package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 초기 관리자 시드 ({@code auth.admin-seed.*}, #6). 값은 환경변수 {@code ADMIN_SEED_EMAIL} · {@code ADMIN_SEED_PASSWORD} · {@code ADMIN_SEED_NAME} 이고
 * Vault 대상이다 ({@code secret-management.md}). 규칙(둘 다 비면 건너뜀 · 하나만이면 기동 실패 · 비밀번호 규칙)은 {@code AdminSeedProcessor#validate} 가 본다 —
 * 바인딩에서 막으면 규칙이 테스트하기 어려운 곳으로 흩어진다.
 *
 * @param email    관리자 이메일. 비면 시드하지 않는다
 * @param password 관리자 비밀번호 원문 — 12~64자, 영문 · 숫자 포함 공백 없는 ASCII. {@link #toString()} 에 싣지 않는다
 * @param name     관리자 이름. 비면 {@code 관리자}
 */
@ConfigurationProperties(prefix = "auth.admin-seed")
public record AdminSeedProperties(String email, String password, String name) {

    /** 이메일 · 비밀번호 · 이름 원문을 싣지 않는다 — 설정 덤프 · 로그로 새지 않게. */
    @Override
    public String toString() {
        return "AdminSeedProperties[email=" + (isBlank(email) ? "(empty)" : "****") + ", password=" + (isBlank(password) ? "(empty)" : "****") + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
