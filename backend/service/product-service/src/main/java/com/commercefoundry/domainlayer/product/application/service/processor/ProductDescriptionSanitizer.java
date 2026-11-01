package com.commercefoundry.domainlayer.product.application.service.processor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 상품 상세 HTML 을 저장 전에 허용 목록으로 정제한다 (#9 E1 · D7). 허용 목록은 FE {@code packages/sanitize/src/sanitize-html.ts} 와 같다 —
 * script · iframe · style · on* 속성 · javascript: URL · 상대 · 프로토콜 상대 URL 은 남지 않는다. FE 정제는 이중 방어로 남는다.
 * 카페24 이관(#19)도 같은 정제를 쓴다.
 *
 * <p>정제로 무엇이 빠졌는지는 {@link Result#changed()} 로 알린다 — 막지 않고 정제본을 저장한다(D7). 판정은 원문이 허용 목록 안인가({@link Jsoup#isValid})로 한다 —
 * 출력 직렬화의 공백 · 따옴표 차이를 "바뀜" 으로 세지 않는다. {@code isValid} 는 HTML 구조 오류(닫히지 않은 표 · 잘못 겹친 태그 · 주석)도 "바뀜" 으로 본다 —
 * 정제가 구조를 정리했다는 뜻이라 그대로 알린다.
 *
 * <p>FE 와 다른 점 하나: FE 는 상대 주소({@code /x} · {@code #a})를 남기지만 서버는 지운다(절대 https · http · mailto 만). 서버 쪽이 더 엄격하다.
 */
@Component
public class ProductDescriptionSanitizer {

    private static final Safelist SAFELIST = new Safelist()
        .addTags("p", "br", "hr", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "small", "ul", "ol", "li", "blockquote",
            "figure", "figcaption", "img", "a", "table", "thead", "tbody", "tr", "th", "td", "span", "div")
        // target · rel · loading · decoding 은 원문 값을 받되 아래 enforced 값으로 덮는다 — FE 가 만든 HTML 을 "정제됨" 으로 세지 않게 허용 속성에 둔다
        .addAttributes("a", "href", "title", "target", "rel")
        .addAttributes("img", "src", "alt", "width", "height", "loading", "decoding")
        .addAttributes("th", "colspan", "rowspan", "scope")
        .addAttributes("td", "colspan", "rowspan")
        .addProtocols("a", "href", "https", "http", "mailto")
        .addProtocols("img", "src", "https", "http")
        // 바깥 링크는 새 창 + opener 차단, 이미지는 지연 로딩 (FE transformTags 와 같다)
        .addEnforcedAttribute("a", "target", "_blank")
        .addEnforcedAttribute("a", "rel", "noopener noreferrer")
        .addEnforcedAttribute("img", "loading", "lazy")
        .addEnforcedAttribute("img", "decoding", "async");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings().prettyPrint(false);

    /** @param html 어드민이 보낸 원문. null 이면 null 이다 */
    public Result sanitize(String html) {
        if (html == null) {
            return new Result(null, false);
        }
        String cleaned = Jsoup.clean(html, "", SAFELIST, OUTPUT);
        return new Result(cleaned, !Jsoup.isValid(html, SAFELIST));
    }

    /**
     * @param html 정제본 — 저장 값
     * @param changed 허용 목록 밖의 태그 · 속성 · URL 이 있어 빠졌다
     */
    public record Result(String html, boolean changed) {
    }
}
