package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request;

/**
 * 가게 운영자 요청이 쓰는 형식. 고객 가입({@code MemberRequestPatterns})과 같은 식이고, 진입을 나누려고 여기 둔다.
 * 애노테이션 값이라 컴파일 시점 상수다.
 */
public final class AdminRequestPatterns {

    /** 도메인에 점이 하나 이상 있어야 한다. {@code @Email} 기본은 {@code a@b} 도 받는다. */
    public static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /** 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII. 길이는 {@code @Size} 가 본다. */
    public static final String PASSWORD = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]+$";

    /** 인증 앱의 6자리 숫자 (#238). 앞뒤 공백은 받고 서버가 지운다. */
    public static final String OTP_TOTP = "^\\s*[0-9]{6}\\s*$";

    /**
     * 6자리 숫자 또는 복구 코드 (#238). 복구 코드는 헷갈리는 글자(I · O · 0 · 1)를 뺀 32자 10자리이고, 가운데 하이픈 · 소문자 · 앞뒤 공백은 받는다
     * ({@code RecoveryCode#normalize} 와 같은 범위).
     */
    public static final String OTP_CODE = "^\\s*(?:[0-9]{6}|[A-HJ-NP-Za-hj-np-z2-9]{5}-?[A-HJ-NP-Za-hj-np-z2-9]{5})\\s*$";

    /** 인증 코드 길이 상한 — 정규식 전에 긴 입력을 끊는다. */
    public static final int OTP_CODE_MAX_LENGTH = 32;

    private AdminRequestPatterns() {
    }
}
