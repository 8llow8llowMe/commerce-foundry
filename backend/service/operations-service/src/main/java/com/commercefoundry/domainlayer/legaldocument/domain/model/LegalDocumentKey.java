package com.commercefoundry.domainlayer.legaldocument.domain.model;

public record LegalDocumentKey(Type type, String locale) {
    public enum Type { TERMS, PRIVACY }

    public LegalDocumentKey {
        if (type == null || !("ko".equals(locale) || "en".equals(locale))) {
            throw new IllegalArgumentException("Unsupported document key");
        }
    }
}
