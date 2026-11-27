package com.commercefoundry.domainlayer.legaldraftgeneration.domain.model;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 문서 유형 · 언어는 legaldocument 의 키를 필드로 갖지 않고 원시값(TERMS/PRIVACY · ko/en)으로 가리킨다. */
public record LegalDraftInput(String type, String locale, int templateVersion, Map<String, String> confirmedFacts) {
    public static final int MAX_FACT_LENGTH = 600;
    private static final Set<String> TYPES = Set.of("TERMS", "PRIVACY");
    private static final Set<String> LOCALES = Set.of("ko", "en");
    private static final Pattern FACT = Pattern.compile("[\\p{L}\\p{M}\\p{N}\\p{Zs}.,;:/@'’()·%&+_–—~!?-]+");

    public LegalDraftInput {
        if (!TYPES.contains(type) || !LOCALES.contains(locale) || templateVersion < 1 || confirmedFacts == null || confirmedFacts.size() > 6) {
            throw new IllegalArgumentException("Invalid legal draft input");
        }
        var normalized = new TreeMap<String, String>();
        confirmedFacts.forEach((key, value) -> {
            if (key == null || value == null || value.length() > MAX_FACT_LENGTH
                || value.isBlank() || !FACT.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid confirmed fact");
            }
            normalized.put(key, value.strip());
        });
        confirmedFacts = Collections.unmodifiableMap(normalized);
    }

    @Override
    public String toString() {
        return "LegalDraftInput[type=" + type + ", locale=" + locale + ", templateVersion=" + templateVersion + "]";
    }
}
