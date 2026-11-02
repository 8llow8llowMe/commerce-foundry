package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Locale;

/** 영문 문구가 빠진 필드마다 기본(한국어) 문구로 돌아간다. */
public final class ProductText {

    private ProductText() {
    }

    public static String select(Locale locale, String korean, String english) {
        return locale != null && "en".equals(locale.getLanguage()) && english != null && !english.isBlank()
            ? english : korean;
    }
}
