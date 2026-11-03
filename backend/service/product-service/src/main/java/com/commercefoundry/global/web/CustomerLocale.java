package com.commercefoundry.global.web;

import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 고객 조회 응답의 표시 언어 (#20). {@code Accept-Language} 가 없거나 비면 서버 기본 언어가 아니라 한국어로 고정하고, 요청이 끝나면 이전 값으로 되돌린다 —
 * 요청 사이에 언어가 새지 않게 한다. 영어 지역 태그(en-US · en-GB)는 영어, 그 밖은 한국어다({@code ProductText#select}).
 *
 * <p>응답이 언어로 갈리므로 공유 캐시가 섞지 않게 컨트롤러가 {@code Vary: Accept-Language} 를 싣는다.
 */
public final class CustomerLocale {

    public static final String HEADER = "Accept-Language";

    private CustomerLocale() {
    }

    public static <T> T within(String acceptLanguage, Supplier<T> body) {
        LocaleContext previous = LocaleContextHolder.getLocaleContext();
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            LocaleContextHolder.setLocale(Locale.KOREAN);
        }
        try {
            return body.get();
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }
}
