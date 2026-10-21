package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

/**
 * 회원 요청 DTO 가 같이 쓰는 형식 정규식. 애노테이션 값이라 컴파일 시점 상수로 둔다.
 */
final class MemberRequestPatterns {

    /** {@code @Email} 은 {@code a@b} 처럼 점 없는 도메인도 받는다. 도메인에 점이 하나 이상 있어야 한다. */
    static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    /**
     * 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII 출력 문자(영문 · 숫자 · 특수문자)만. 길이(8~64)는 {@code @Size} 가 본다.
     * ASCII 로 묶는 이유 — BCrypt 는 72바이트까지만 쓰고 그 뒤를 버린다(Spring Security 는 넘으면 거절한다). 64자 ASCII 는 64바이트라 넘지 않는다.
     */
    static final String PASSWORD = "^(?=.*[A-Za-z])(?=.*[0-9])[\\x21-\\x7E]+$";

    /** 국내 휴대폰 — {@code 01012345678} · {@code 010-1234-5678}. 숫자만 저장한다({@code Member}). */
    static final String PHONE = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$";

    /** {@link #PHONE} 또는 빈 문자열(번호 지우기) — 내 정보 수정에서만. */
    static final String PHONE_OR_EMPTY = "^$|" + PHONE;

    /**
     * 공백 아닌 문자가 하나 이상 있다 — "공백" 은 도메인 {@code Member} 가 지우는 {@code String#strip()} 과 같은 {@link Character#isWhitespace} 다
     * ({@code \p{javaWhitespace}}). {@code \S}(ASCII 공백만 공백으로 본다)로 검사하면 전각 공백(U+3000)만 넣은 이름이 요청 검증을 지나
     * 도메인에서 {@link IllegalArgumentException}(500)이 됐다 (#155). 필수 칸의 {@code @NotBlank}(Hibernate Validator)도 같은 공백 기준이라 맞는다.
     */
    static final String NOT_BLANK = "(?s).*\\P{javaWhitespace}.*";

    private MemberRequestPatterns() {
    }
}
