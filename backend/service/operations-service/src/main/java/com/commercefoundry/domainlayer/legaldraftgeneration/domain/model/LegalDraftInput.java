package com.commercefoundry.domainlayer.legaldraftgeneration.domain.model;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record LegalDraftInput(LegalDocumentKey document, int templateVersion, Map<String, String> confirmedFacts) {
    public static final int MAX_FACT_LENGTH = 600;
    private static final Pattern FACT = Pattern.compile("[\\p{L}\\p{M}\\p{N}\\p{Zs}.,;:/@'’()·%&+_–—~!?-]+");

    public LegalDraftInput {
        if (document == null || templateVersion < 1 || confirmedFacts == null || confirmedFacts.size() > 6) {
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
        return "LegalDraftInput[document=" + document + ", templateVersion=" + templateVersion + "]";
    }
}
