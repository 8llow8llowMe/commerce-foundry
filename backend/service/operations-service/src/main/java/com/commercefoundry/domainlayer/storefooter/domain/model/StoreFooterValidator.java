package com.commercefoundry.domainlayer.storefooter.domain.model;

import java.util.HashSet;
import java.util.Set;

public final class StoreFooterValidator {
    private StoreFooterValidator() {
    }

    public static void validate(StoreFooterContent content, StoreFooterUrlPolicy urls) {
        if (content == null || content.copyrightText() == null) {
            fail(StoreFooterRuleException.Reason.COPYRIGHT);
        }
        text(content.copyrightText().ko(), 200, true, StoreFooterRuleException.Reason.COPYRIGHT);
        text(content.copyrightText().en(), 200, true, StoreFooterRuleException.Reason.COPYRIGHT);
        if (content.links() == null || content.links().size() > 20) {
            fail(StoreFooterRuleException.Reason.LINKS);
        }
        Set<Integer> orders = new HashSet<>();
        for (var link : content.links()) {
            if (link == null) {
                fail(StoreFooterRuleException.Reason.LINKS);
            }
            if (link.type() == null || !Set.of("INSTAGRAM", "KAKAO_CHANNEL", "CUSTOM").contains(link.type())) {
                fail(StoreFooterRuleException.Reason.TYPE);
            }
            if (link.labels() == null) {
                fail(StoreFooterRuleException.Reason.LABELS);
            }
            text(link.labels().ko(), 80, false, StoreFooterRuleException.Reason.LABELS);
            text(link.labels().en(), 80, false, StoreFooterRuleException.Reason.LABELS);
            urls.validate(link.type(), link.url());
            if (link.displayOrder() == null || link.displayOrder() < 0 || link.displayOrder() > 999 || !orders.add(link.displayOrder())) {
                fail(StoreFooterRuleException.Reason.ORDER);
            }
            if (link.visible() == null) {
                fail(StoreFooterRuleException.Reason.VISIBLE);
            }
        }
    }

    private static void text(String value, int max, boolean nullable, StoreFooterRuleException.Reason reason) {
        if (value == null && nullable) {
            return;
        }
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            fail(reason);
        }
    }

    private static void fail(StoreFooterRuleException.Reason reason) {
        throw new StoreFooterRuleException(reason);
    }
}
