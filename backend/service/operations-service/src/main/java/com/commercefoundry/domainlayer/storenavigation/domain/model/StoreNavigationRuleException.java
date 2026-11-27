package com.commercefoundry.domainlayer.storenavigation.domain.model;

public class StoreNavigationRuleException extends RuntimeException {
    public enum Reason { CONFLICT, ITEMS, KEY, PARENT, CYCLE, DEPTH, TYPE, LABELS, URL, CATEGORY, ORDER, VISIBLE, DESCRIPTION, PLACEMENT }
    private final Reason reason;

    public StoreNavigationRuleException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
