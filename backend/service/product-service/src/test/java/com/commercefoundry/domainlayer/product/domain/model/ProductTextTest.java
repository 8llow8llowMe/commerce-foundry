package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class ProductTextTest {

    @Test
    void englishUsesTranslationPerFieldAndFallsBackWhenMissing() {
        assertThat(ProductText.select(Locale.ENGLISH, "저고리", "Jacket")).isEqualTo("Jacket");
        assertThat(ProductText.select(Locale.ENGLISH, "요약", null)).isEqualTo("요약");
        assertThat(ProductText.select(Locale.ENGLISH, "<p>설명</p>", " ")).isEqualTo("<p>설명</p>");
        assertThat(ProductText.select(Locale.KOREAN, "저고리", "Jacket")).isEqualTo("저고리");
    }
}
