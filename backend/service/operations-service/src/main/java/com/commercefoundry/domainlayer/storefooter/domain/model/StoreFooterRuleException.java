package com.commercefoundry.domainlayer.storefooter.domain.model;

public class StoreFooterRuleException extends RuntimeException {
    public enum Reason { CONFLICT, VERSION, COPYRIGHT, LINKS, TYPE, LABELS, URL, ORDER, VISIBLE, REVISION }
    private final Reason reason;

    public StoreFooterRuleException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
