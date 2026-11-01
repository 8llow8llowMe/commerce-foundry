package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상세 HTML 정제 — FE {@code sanitize-html.test.ts} 와 같은 입력을 넣어 같은 것이 남고 빠지는지 본다. 이미지 지연 로딩 · 링크 새 창 속성은 FE 처럼 서버도 붙인다.
 * 빠진 것이 있을 때만 {@code changed} 다. 속성 순서는 jsoup 직렬화가 정해 값만 본다.
 */
class ProductDescriptionSanitizerTest {

    private final ProductDescriptionSanitizer sanitizer = new ProductDescriptionSanitizer();

    @Test
    @DisplayName("허용한 서식 · 이미지 · 표는 남기고 바뀌지 않았다고 알린다")
    void keepsAllowed() {
        ProductDescriptionSanitizer.Result result = sanitizer.sanitize(
            "<h3>소재</h3><p><strong>실크</strong> 100%</p><img src=\"https://img.test/a.jpg\" alt=\"디테일\"><table><tr><td>S</td></tr></table>"
        );

        assertThat(result.html()).contains("<h3>소재</h3>", "<strong>실크</strong>", "<img src=\"https://img.test/a.jpg\" alt=\"디테일\"", "<td>S</td>");
        assertThat(result.changed()).isFalse();
    }

    @Test
    @DisplayName("script · iframe · 이벤트 속성 · style 을 지우고 바뀌었다고 알린다")
    void removesDangerous() {
        ProductDescriptionSanitizer.Result result = sanitizer.sanitize(
            "<p onclick=\"alert(1)\" style=\"color:red\">안녕</p><script>alert(1)</script><iframe src=\"https://evil.test\"></iframe>"
                + "<img src=\"https://img.test/a.jpg\" onerror=\"alert(1)\">"
        );

        assertThat(result.html()).startsWith("<p>안녕</p><img src=\"https://img.test/a.jpg\"")
            .doesNotContain("onclick", "style", "script", "iframe", "onerror", "alert");
        assertThat(result.changed()).isTrue();
    }

    @Test
    @DisplayName("javascript: · data: URL 과 프로토콜 상대 · 상대 URL 을 지운다")
    void removesUnsafeUrls() {
        assertThat(sanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>").html()).doesNotContain("javascript");
        assertThat(sanitizer.sanitize("<img src=\"data:image/svg+xml;base64,AAAA\">").html()).doesNotContain("data:");
        assertThat(sanitizer.sanitize("<a href=\"//evil.test\">x</a>").html()).doesNotContain("evil.test");
        assertThat(sanitizer.sanitize("<img src=\"/uploads/a.jpg\">").html()).doesNotContain("uploads");
    }

    @Test
    @DisplayName("링크는 새 창 + opener 차단으로 고정한다 (원문 target 은 무시)")
    void forcesLinkTarget() {
        // 속성 순서는 jsoup 직렬화가 정한다 — 값만 본다
        assertThat(sanitizer.sanitize("<a href=\"https://brand.test\" target=\"_self\">브랜드</a>").html())
            .startsWith("<a href=\"https://brand.test\"").contains("target=\"_blank\"", "rel=\"noopener noreferrer\"")
            .doesNotContain("_self").endsWith(">브랜드</a>");
    }

    @Test
    @DisplayName("FE 가 붙이는 target · rel · loading · decoding 이 있는 원문은 바뀌지 않았다고 본다 — 값은 정해진 값으로 덮는다")
    void feAttributesAreNotChanges() {
        ProductDescriptionSanitizer.Result result = sanitizer.sanitize(
            "<a href=\"https://brand.test\" target=\"_blank\" rel=\"noopener noreferrer\">브랜드</a>"
                + "<img src=\"https://img.test/a.jpg\" loading=\"eager\" decoding=\"sync\">"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.html()).contains("loading=\"lazy\"", "decoding=\"async\"").doesNotContain("eager", "\"sync\"");
    }

    @Test
    @DisplayName("빈 값은 그대로 null 이다")
    void nullStaysNull() {
        assertThat(sanitizer.sanitize(null)).isEqualTo(new ProductDescriptionSanitizer.Result(null, false));
    }
}
