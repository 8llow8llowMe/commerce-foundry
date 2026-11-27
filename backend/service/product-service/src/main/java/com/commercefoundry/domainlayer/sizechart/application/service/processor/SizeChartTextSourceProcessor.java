package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTextSourceInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** HTML을 AI에 보내지 않는다. 명확한 표 전체가 허용 어휘·구조를 만족할 때만 검토용 텍스트를 만든다. */
@Component
public class SizeChartTextSourceProcessor {
    private static final int MAX_HTML = 256 * 1024;
    private static final int MAX_CANDIDATES = 20;
    private static final Set<String> TAGS = Set.of("table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "span", "b", "strong", "em", "i", "br");
    private static final Pattern SIZE = Pattern.compile("(?i)(?:[2-9]?[X]{0,4}[SML]|XXS|FREE|ONE(?: SIZE)?|OS|F|[2-9][0-9]|1[0-9]{2})");
    private static final Pattern VALUE = Pattern.compile("(?:[0-9]{1,3}(?:[.][0-9]{1,2})?|(?i:N/A|NULL)|-)");
    private static final PiiMasker MASKER = new PiiMasker(Map.of());
    /** 인라인 스타일의 숨김 · 화면 밖 배치. 공백을 지운 소문자 스타일에 포함되면 숨김으로 본다. */
    private static final List<String> HIDDEN_STYLES = List.of("display:none", "visibility:hidden", "visibility:collapse", "opacity:0",
        "font-size:0", "text-indent:-", "clip:rect(", "left:-", "top:-");
    /** 흔한 숨김 · 스크린리더 전용 클래스. 국내 쇼핑몰 본문에 흔한 {@code blind} 를 포함한다. */
    private static final Set<String> HIDDEN_CLASSES = Set.of("hidden", "hide", "blind", "invisible", "sr-only", "visually-hidden",
        "screen-reader-text", "screen_out", "d-none", "displaynone");
    /** 값이 0 이면 내용이 보이지 않는 크기 속성. {@code border-width:0} 같은 다른 속성과 섞이지 않게 선언 단위로 본다. */
    private static final Set<String> ZERO_SIZE_PROPERTIES = Set.of("height", "max-height", "width", "max-width");
    private static final Pattern ZERO_LENGTH = Pattern.compile("0+(?:[.]0+)?(?:px|em|rem|%|pt|vh|vw)?(?:!important)?");
    private static final Pattern ZERO_SCALE = Pattern.compile("scale[xy]?[(]0+(?:[.]0+)?[,)]");
    /** 본문 앞쪽 table 만 검사한다. 긴 본문의 검사 비용을 묶는 상한이다. */
    private static final int MAX_TABLES = 40;

    public List<SizeChartTextSourceInfo> sources(Product product) {
        if (product == null) {
            return List.of();
        }
        List<SizeChartTextSourceInfo> result = new ArrayList<>();
        extract(product.descriptionHtml(), "KO", result);
        extract(product.descriptionHtmlEn(), "EN", result);
        return List.copyOf(result);
    }

    /** 신규 생성에만 호출한다. 재생은 기존 불변 입력을 사용하므로 본문이 바뀌어도 기존 영수증을 복구한다. */
    public void verify(Product product, SizeChartDraftCreateCommand command) {
        var selection = command.textSelection();
        if (selection == null) {
            return;
        }
        if (!"TEXT".equals(command.mode()) || product == null || product.id() != command.productId()
            || sources(product).stream().noneMatch(s -> s.sourceId().equals(selection.sourceId())
                && s.bodyHash().equals(selection.bodyHash()) && s.sourceText().equals(command.sourceText()))) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED);
        }
    }

    private void extract(String html, String language, List<SizeChartTextSourceInfo> result) {
        if (html == null || html.isBlank() || html.length() > MAX_HTML) {
            return;
        }
        Parser parser = Parser.htmlParser().setTrackErrors(1);
        var document = parser.parseInput(html, "");
        // 본문 안 스타일시트는 어떤 요소든 숨길 수 있는데 서버는 계산하지 않는다 — 그 언어 본문 전체를 후보에서 뺀다.
        if (!parser.getErrors().isEmpty() || !document.select("style").isEmpty()
            || document.select("link").stream().anyMatch(link -> link.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet"))) {
            return;
        }
        String bodyHash = hash(html);
        int index = 0;
        int accepted = 0;
        for (Element table : document.select("table")) {
            if (++index > MAX_TABLES || accepted >= MAX_CANDIDATES) {
                break;
            }
            String text = snapshot(table);
            if (text != null) {
                result.add(new SizeChartTextSourceInfo("TEXT-" + language + "-" + index, bodyHash, text));
                accepted++;
            }
        }
    }

    private String snapshot(Element table) {
        if (table.getAllElements().size() > 2000 || table.parents().stream().anyMatch(e -> e.normalName().equals("table") || hidden(e))) {
            return null;
        }
        if (table.getAllElements().stream().anyMatch(e -> !TAGS.contains(e.normalName()) || hidden(e)
            || e.hasAttr("rowspan") || e.hasAttr("colspan") || e.hasAttr("contenteditable"))) {
            return null;
        }
        if (table.select("table").size() > 1 || !safeNodes(table)) {
            return null;
        }
        // 셀 밖의 텍스트도 버리지 않고 거절한다. 구조가 모호한 표는 후보로 만들지 않는다.
        for (Element element : table.getAllElements()) {
            if (!Set.of("td", "th", "caption").contains(element.normalName())
                && element.parents().stream().noneMatch(p -> Set.of("td", "th", "caption").contains(p.normalName()))
                && element.textNodes().stream().anyMatch(n -> !n.isBlank())) {
                return null;
            }
        }
        var rows = table.select("tr");
        if (rows.size() < 2 || rows.size() > 31) {
            return null;
        }
        var headers = rows.getFirst().children();
        if (headers.size() < 2 || headers.size() > 21 || !headers.stream().allMatch(e -> Set.of("td", "th").contains(e.normalName()))
            || !Set.of("size", "사이즈").contains(cell(headers.getFirst()).toLowerCase(Locale.ROOT))) {
            return null;
        }
        List<String> names = headers.stream().skip(1).map(this::cell).toList();
        if (names.stream().anyMatch(n -> !SizeChartDraftValidationProcessor.isMeasurementLabel(n))
            || names.stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct().count() != names.size()) {
            return null;
        }
        String caption = table.select("caption").text().strip();
        if (!caption.isEmpty() && !Set.of("cm", "inch", "in").contains(caption.toLowerCase(Locale.ROOT))) {
            return null;
        }
        List<String> parts = new ArrayList<>(names);
        if (!caption.isEmpty()) {
            parts.add(caption);
        }
        Set<String> labels = new HashSet<>();
        boolean measured = false;
        for (Element row : rows.subList(1, rows.size())) {
            var cells = row.children();
            if (cells.size() != headers.size() || !cells.stream().allMatch(e -> Set.of("td", "th").contains(e.normalName()))) {
                return null;
            }
            String label = cell(cells.getFirst());
            if (!SIZE.matcher(label).matches() || !labels.add(label.toUpperCase(Locale.ROOT))) {
                return null;
            }
            parts.add(label);
            for (Element value : cells.subList(1, cells.size())) {
                String number = cell(value);
                if (!VALUE.matcher(number).matches()) {
                    return null;
                }
                measured |= number.matches("[0-9].*");
                parts.add(number);
            }
        }
        String text = String.join(" ", parts);
        // 개인정보가 가려졌다는 이유로 불완전한 치수를 허용하지 않는다. 블록 전체를 거절한다.
        if (!measured || text.length() > 12000 || !text.equals(MASKER.mask(text, List.of()))) {
            return null;
        }
        return text;
    }

    private boolean safeNodes(Node node) {
        for (Element element : ((Element) node).getAllElements()) {
            if (element.childNodes().stream().anyMatch(child -> !(child instanceof Element) && !(child instanceof TextNode))) {
                return false;
            }
        }
        return true;
    }

    /** 화면에 보이지 않을 수 있으면 true. 스타일시트는 볼 수 없어 인라인 스타일과 흔한 숨김 클래스만 보고, 애매하면 숨김으로 본다(블록 거절). */
    private boolean hidden(Element element) {
        String style = element.attr("style").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return element.hasAttr("hidden") || element.attr("aria-hidden").equalsIgnoreCase("true")
            || element.normalName().equals("details") && !element.hasAttr("open")
            || HIDDEN_STYLES.stream().anyMatch(style::contains) || zeroSized(style) || ZERO_SCALE.matcher(style).find()
            || element.classNames().stream().map(c -> c.toLowerCase(Locale.ROOT)).anyMatch(HIDDEN_CLASSES::contains)
            || Set.of("script", "style", "template", "noscript").contains(element.normalName());
    }

    private static boolean zeroSized(String style) {
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0 && ZERO_SIZE_PROPERTIES.contains(declaration.substring(0, colon))
                && ZERO_LENGTH.matcher(declaration.substring(colon + 1)).matches()) {
                return true;
            }
        }
        return false;
    }

    private String cell(Element element) {
        return element.text().replace('\u00a0', ' ').strip().replaceAll("\\s+", " ");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
