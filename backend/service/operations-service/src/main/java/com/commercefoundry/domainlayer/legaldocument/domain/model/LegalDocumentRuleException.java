package com.commercefoundry.domainlayer.legaldocument.domain.model;

public class LegalDocumentRuleException extends RuntimeException {
    public enum Reason { CONFLICT, REVIEW_REQUIRED, UNRESOLVED_PLACEHOLDER }
    private final Reason reason;

    public LegalDocumentRuleException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
