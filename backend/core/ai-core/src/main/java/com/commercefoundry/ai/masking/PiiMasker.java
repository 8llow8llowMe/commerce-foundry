package com.commercefoundry.ai.masking;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 보내기 전에 개인정보를 가린다 ({@code security-guide.md} AI). 순서가 곧 규칙이다.
 *
 * <ol>
 *   <li><b>호출자가 아는 값</b> — 받는 사람 이름 · 주소 · 연락처 · 주문번호처럼 호출자가 데이터로 가진 값을 정확히 일치로 {@code [PII]} 로 바꾼다.
 *       이름은 정규식으로 잡을 수 없으므로 이 경로가 정본이다. 긴 값부터 바꾼다(짧은 값이 긴 값의 일부를 먼저 깨지 않게). 두 글자 미만은 무시한다.</li>
 *   <li><b>설정 패턴</b> — 주문번호 형식처럼 브랜드마다 다른 것({@code ai.masking.patterns.<이름>}) → {@code [이름 대문자]}.</li>
 *   <li><b>기본 패턴</b> — 이메일 · 주민등록번호 · 전화번호 · 도로명 주소 · 지번 · 동호수. 자유 텍스트(문의 본문 등)에 섞인 값을 잡는다.
 *       과하게 가리는 쪽을 택한다 — 본문 일부가 가려지는 비용이 개인정보가 나가는 비용보다 작다.</li>
 * </ol>
 *
 * <p>자유 텍스트 속 이름 · 형식 없는 주소는 잡지 못한다 — 호출자가 아는 값으로 넘기거나, 그런 텍스트는 보내지 않는다.
 */
public final class PiiMasker {

    public static final String KNOWN_VALUE = "[PII]";

    /** 기본 패턴. 순서대로 적용한다 — 주민번호가 전화번호 패턴에 먼저 먹히지 않게 앞에 둔다. */
    private static final Map<String, Pattern> DEFAULT_PATTERNS = defaults();

    private final Map<String, Pattern> patterns;
    /** 적용 순서 — 설정 패턴(이름순) 다음 기본 패턴. */
    private final List<String> order;

    /** @param extraPatterns 이름 → 정규식. 이름은 소문자 · 숫자 · {@code -} 다({@code AiProperties} 가 검사한다) */
    public PiiMasker(Map<String, String> extraPatterns) {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        extraPatterns.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> compiled.put(placeholder(entry.getKey()), Pattern.compile(entry.getValue())));
        compiled.putAll(DEFAULT_PATTERNS);
        this.patterns = Map.copyOf(compiled);
        this.order = List.copyOf(compiled.keySet());
    }

    /**
     * @param text        가릴 텍스트. {@code null} 이면 {@code null}
     * @param knownValues 정확히 일치로 가릴 값들
     */
    public String mask(String text, List<String> knownValues) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String value : knownValues.stream().filter(Objects::nonNull).map(String::strip).filter(v -> v.length() >= 2).distinct()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            masked = masked.replace(value, KNOWN_VALUE);
        }
        for (String placeholder : order) {
            masked = patterns.get(placeholder).matcher(masked).replaceAll(Matcher.quoteReplacement(placeholder));
        }
        return masked;
    }

    private static String placeholder(String name) {
        return "[" + name.toUpperCase(Locale.ROOT).replace('-', '_') + "]";
    }

    private static Map<String, Pattern> defaults() {
        Map<String, Pattern> defaults = new LinkedHashMap<>();
        defaults.put("[EMAIL]", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        defaults.put("[RRN]", Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)"));
        // 국내 휴대폰 · 지역번호 · 대표번호(1588 등) · +82 국제 표기, 그 밖의 + 국제 번호
        defaults.put("[PHONE]", Pattern.compile(
            "(?<![\\d+])(?:\\+82[-. ]?\\(?0?\\)?\\d{1,2}|0\\d{1,2})[-. )]?\\d{3,4}[-. ]?\\d{4}(?!\\d)"
                + "|(?<!\\d)1[5-9]\\d{2}[-. ]?\\d{4}(?!\\d)"
                + "|\\+\\d{1,3}[-. ]?\\d{1,4}(?:[-. ]?\\d{2,4}){2,3}(?!\\d)"));
        // 도로명(…로 · …길 + 건물번호, …번길 포함) · 지번(…동/리 + 번지) · 동호수(101동 1203호)
        defaults.put("[ADDRESS]", Pattern.compile(
            "[가-힣A-Za-z0-9]+(?:로|길)\\s?\\d+(?:-\\d+)?(?:\\s?번길\\s?\\d+(?:-\\d+)?)?"
                + "|[가-힣]+(?:동|리|가)\\s?(?:산\\s?)?\\d+(?:-\\d+)?\\s?번지"
                + "|\\d+\\s?동\\s?\\d+\\s?호"));
        return defaults;
    }
}
