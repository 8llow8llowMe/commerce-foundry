package com.commercefoundry.ai.masking;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 보내기 전에 개인정보를 가린다 ({@code bootstrap-conventions.md} AI).
 *
 * <ol>
 *   <li>입력을 NFKC 로 정규화한다 — 전각 숫자 · {@code ＠} · 대시 변형이 기본 패턴을 피하지 못하게.</li>
 *   <li>세 종류의 일치 구간을 원문에서 <b>모두</b> 찾는다 — 호출자가 아는 값(대소문자 · 공백 무시, {@code [PII]}), 설정 패턴({@code [이름 대문자]}),
 *       기본 패턴(이메일 · 주민번호 · 카드 · 전화 · 계좌 · 주소). 이름은 정규식으로 잡을 수 없으므로 알려진 값이 정본이다.</li>
 *   <li>겹치는 구간을 합쳐 한 번에 바꾼다 — 한 규칙이 먼저 바꿔 다른 규칙의 일치를 깨뜨리는 일(이메일 앞부분만 가려 도메인이 남는 등)이 없다.</li>
 * </ol>
 *
 * <p>기본 패턴은 시작 위치를 단어 경계로 묶고 반복 길이를 제한해, 공백 없는 긴 입력에서도 처리 시간이 길이에 비례한다(되추적 폭주 없음).
 * 과하게 가리는 쪽을 택한다 — 본문 일부가 가려지는 비용이 개인정보가 나가는 비용보다 작다.
 * 자유 텍스트 속 이름 · 형식 없는 주소는 잡지 못한다 — 호출자가 아는 값으로 넘기거나, 그런 텍스트는 보내지 않는다.
 */
public final class PiiMasker {

    public static final String KNOWN_VALUE = "[PII]";
    /** 설정 패턴이 쓰면 기본 패턴과 자리표시가 겹치는 이름. {@code AiProperties} 가 막는다. */
    public static final Set<String> RESERVED_NAMES = Set.of("pii", "email", "rrn", "card", "phone", "account", "address");

    /** 숫자 묶음 사이 구분자 — 하이픈 · 점 · 공백 · 밑줄 · 괄호 · en/em 대시(NFKC 가 대시 변형을 바꾸지 않는다). */
    private static final String SEPARATOR = "[-.\\s_)(\\x{2013}\\x{2014}]{0,3}";
    private static final List<Rule> DEFAULT_RULES = List.of(
        new Rule("[EMAIL]", Pattern.compile("(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]{1,64}\\s?@\\s?[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,5}\\.[A-Za-z]{2,24}")),
        new Rule("[RRN]", Pattern.compile("(?<!\\d)\\d{6}\\s?[-\\x{2013}\\x{2014}]?\\s?[1-8]\\d{6}(?!\\d)")),
        // 13~19 자리, 4자리 묶음 구분 허용
        new Rule("[CARD]", Pattern.compile("(?<!\\d)\\d{4}(?:[-\\s]?\\d{4}){2}[-\\s]?\\d{1,7}(?!\\d)")),
        // 국내 휴대폰 · 지역번호((02) 포함) · +82/82 국제 표기 · 대표번호, 그 밖의 + 국제 번호
        new Rule("[PHONE]", Pattern.compile(
            "(?<![\\d+])(?:\\+?82" + SEPARATOR + "0?\\d{1,2}|\\(?0\\d{1,2}\\)?)" + SEPARATOR + "\\d{3,4}" + SEPARATOR + "\\d{4}(?!\\d)"
                + "|(?<!\\d)1[5-9]\\d{2}" + SEPARATOR + "\\d{4}(?!\\d)"
                + "|\\+\\d{1,3}[-. ]?\\d{1,4}(?:[-. ]?\\d{2,4}){2,3}(?!\\d)")),
        // 계좌: 세 묶음이면 셋째가 3자리 이상(날짜 2026-10-24 는 셋째가 2자리라 빠진다), 네 묶음이면 길이와 무관하게 계좌로 본다
        new Rule("[ACCOUNT]", Pattern.compile("(?<![\\d-])\\d{2,6}-\\d{2,6}-(?:\\d{1,8}-\\d{1,6}|\\d{3,8})(?![\\d-])")),
        // 도로명(…로 · …길 + 건물번호, 번길 포함) · 지번(…동/리/가 + 번지) · 동호수(101동 1203호)
        new Rule("[ADDRESS]", Pattern.compile(
            "(?<![\\p{L}\\p{N}])[가-힣A-Za-z0-9]{1,30}(?:로|길)\\s?\\d{1,5}(?:-\\d{1,5})?(?:\\s?번길\\s?\\d{1,5}(?:-\\d{1,5})?)?"
                + "|(?<![가-힣])[가-힣]{1,20}(?:동|리|가)\\s?(?:산\\s?)?\\d{1,5}(?:-\\d{1,5})?\\s?번지"
                + "|(?<!\\d)\\d{1,5}\\s?동\\s?\\d{1,5}\\s?호")));

    private final List<Rule> configured;

    /** @param extraPatterns 이름 → 정규식. 이름은 소문자 · 숫자 · {@code -} 이고 {@link #RESERVED_NAMES} 가 아니다({@code AiProperties} 가 검사한다) */
    public PiiMasker(Map<String, String> extraPatterns) {
        this.configured = extraPatterns.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Rule("[" + entry.getKey().toUpperCase(Locale.ROOT).replace('-', '_') + "]", Pattern.compile(entry.getValue())))
            .toList();
    }

    /**
     * @param text        가릴 텍스트. {@code null} 이면 {@code null}
     * @param knownValues 가릴 값들 — 대소문자 · 공백 차이를 무시하고 맞춘다. 정규화 뒤 두 글자 미만은 무시한다(한 글자를 가리면 본문이 깨진다)
     */
    public String mask(String text, List<String> knownValues) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        List<Span> spans = new ArrayList<>();
        for (Rule rule : knownValueRules(knownValues)) {
            collect(rule, normalized, spans);
        }
        configured.forEach(rule -> collect(rule, normalized, spans));
        DEFAULT_RULES.forEach(rule -> collect(rule, normalized, spans));
        return replace(normalized, spans);
    }

    /** 값 하나를 공백으로 나눠 {@code \s*} 로 잇는다 — {@code 홍 길동} · {@code hong gildong} 도 맞는다. 긴 값을 먼저 둔다(같은 시작이면 긴 쪽이 이긴다). */
    private static List<Rule> knownValueRules(List<String> knownValues) {
        return knownValues.stream()
            .filter(Objects::nonNull)
            .map(value -> Normalizer.normalize(value, Normalizer.Form.NFKC).strip())
            .filter(value -> value.replaceAll("\\s", "").length() >= 2)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(value -> new Rule(KNOWN_VALUE, Pattern.compile(
                Arrays.stream(value.split("\\s+")).map(Pattern::quote).collect(Collectors.joining("\\s*")),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)))
            .toList();
    }

    private static void collect(Rule rule, String text, List<Span> spans) {
        Matcher matcher = rule.pattern().matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                spans.add(new Span(matcher.start(), matcher.end(), rule.placeholder()));
            }
        }
    }

    /** 겹치거나 맞닿은 구간을 합친다. 합친 구간의 자리표시는 먼저 시작한 쪽(같으면 먼저 등록된 규칙 — 알려진 값 · 설정 · 기본 순)이다. */
    private static String replace(String text, List<Span> spans) {
        if (spans.isEmpty()) {
            return text;
        }
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(Span::start));
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        Span current = sorted.getFirst();
        for (Span span : sorted.subList(1, sorted.size())) {
            if (span.start() <= current.end()) {
                current = new Span(current.start(), Math.max(current.end(), span.end()), current.placeholder());
            } else {
                out.append(text, cursor, current.start()).append(current.placeholder());
                cursor = current.end();
                current = span;
            }
        }
        out.append(text, cursor, current.start()).append(current.placeholder());
        out.append(text, current.end(), text.length());
        return out.toString();
    }

    private record Rule(String placeholder, Pattern pattern) {

    }

    private record Span(int start, int end, String placeholder) {

    }
}
