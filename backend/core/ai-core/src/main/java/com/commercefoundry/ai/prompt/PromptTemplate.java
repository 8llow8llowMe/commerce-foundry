package com.commercefoundry.ai.prompt;

import com.commercefoundry.ai.client.AiRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기능 하나 · 버전 하나의 프롬프트 템플릿. 자리 표기는 {@code {{이름}}}(일반)과 {@code {{pii:이름}}}(개인정보)이다 — 이름은 영문자로 시작하는 영문 · 숫자 · {@code _}.
 *
 * <p>렌더링은 한 번만 훑는다 — 값 안의 {@code {{...}}} 는 다시 바뀌지 않는다. 자리가 빠졌거나, 넘긴 값이 쓰이지 않았거나, 개인정보 자리에 일반 값을
 * (또는 반대로) 넣으면 {@link IllegalArgumentException} 이다(호스트 결함). 예외 메시지에는 자리 이름만 있고 값은 없다.
 *
 * @param feature 기능 이름
 * @param version 버전(1 이상)
 * @param system  시스템 프롬프트 템플릿. 없으면 {@code null}
 * @param user    사용자 메시지 템플릿
 */
public record PromptTemplate(String feature, int version, String system, String user) {

    static final Pattern SLOT = Pattern.compile("\\{\\{(pii:)?([A-Za-z][A-Za-z0-9_]{0,63})}}");

    public PromptTemplate {
        checkSlots(feature, version, system);
        checkSlots(feature, version, user);
    }

    /** 값을 채워 요청을 만든다. 개인정보 자리에 들어간 값은 요청의 {@code sensitiveValues} 로 넘어가 보내기 전에 가려진다. */
    public AiRequest render(PromptValues values) {
        Set<String> used = new HashSet<>();
        String renderedSystem = system == null ? null : fill(system, values, used);
        String renderedUser = fill(user, values, used);
        Set<String> unused = new LinkedHashSet<>();
        values.plain().keySet().stream().filter(name -> !used.contains(name)).forEach(unused::add);
        values.sensitive().keySet().stream().filter(name -> !used.contains("pii:" + name)).forEach(name -> unused.add("pii:" + name));
        if (!unused.isEmpty()) {
            throw new IllegalArgumentException(describe() + " 에 없는 자리에 값을 넘겼습니다: " + unused);
        }
        return new AiRequest(feature, renderedSystem, renderedUser, new ArrayList<>(values.sensitive().values()), version);
    }

    /** 템플릿이 쓰는 자리 이름 — 개인정보 자리는 {@code pii:} 가 붙는다. */
    public Set<String> slots() {
        Set<String> slots = new LinkedHashSet<>();
        for (String text : new String[] {system, user}) {
            if (text != null) {
                Matcher matcher = SLOT.matcher(text);
                while (matcher.find()) {
                    slots.add((matcher.group(1) == null ? "" : "pii:") + matcher.group(2));
                }
            }
        }
        return slots;
    }

    private String fill(String template, PromptValues values, Set<String> used) {
        Matcher matcher = SLOT.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            boolean pii = matcher.group(1) != null;
            String name = matcher.group(2);
            Map<String, String> source = pii ? values.sensitive() : values.plain();
            Map<String, String> other = pii ? values.plain() : values.sensitive();
            String value = source.get(name);
            if (value == null) {
                throw new IllegalArgumentException(other.containsKey(name)
                    ? describe() + " 의 자리 '" + slotName(pii, name) + "' 는 " + (pii ? "putSensitive" : "put") + " 로 넣어야 합니다"
                    : describe() + " 의 자리 '" + slotName(pii, name) + "' 에 값이 없습니다");
            }
            used.add(slotName(pii, name));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String describe() {
        return "프롬프트 템플릿 " + feature + " v" + version;
    }

    private static String slotName(boolean pii, String name) {
        return (pii ? "pii:" : "") + name;
    }

    /** {@code {{} 로 시작했지만 자리 규칙에 맞지 않는 표기(오타)를 막는다. */
    private static void checkSlots(String feature, int version, String text) {
        if (text == null) {
            return;
        }
        List<String> broken = new ArrayList<>();
        int from = 0;
        int open;
        while ((open = text.indexOf("{{", from)) >= 0) {
            Matcher matcher = SLOT.matcher(text);
            if (!matcher.find(open) || matcher.start() != open) {
                broken.add("위치 " + open);
            }
            from = open + 2;
        }
        if (!broken.isEmpty()) {
            throw new IllegalArgumentException("프롬프트 템플릿 " + feature + " v" + version + " 에 규칙에 맞지 않는 자리 표기가 있습니다 — {{이름}} · {{pii:이름}} 만 씁니다: "
                + broken);
        }
    }

    /** 문구를 로그로 내지 않는다 — 길이와 자리 이름만 싣는다. */
    @Override
    public String toString() {
        return "PromptTemplate[feature=" + feature + ", version=" + version + ", systemLength=" + (system == null ? 0 : system.length())
            + ", userLength=" + user.length() + ", slots=" + slots() + "]";
    }
}
