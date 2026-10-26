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

    private AdminRequestPatterns() {
    }
}
