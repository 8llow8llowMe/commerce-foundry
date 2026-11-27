package com.commercefoundry.domainlayer.storenavigation.domain.model;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent.Item;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRuleException.Reason;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 상단 메뉴 트리 규칙. 숨김 항목도 같은 규칙으로 검증한다(나중에 노출로 바꿔도 안전해야 한다).
 * 상한: 전체 {@value #MAX_ITEMS}개, 깊이 {@value #MAX_DEPTH}단계, 한 부모 아래 {@value #MAX_CHILDREN}개.
 */
public final class StoreNavigationValidator {
    public static final int MAX_ITEMS = 60;
    public static final int MAX_DEPTH = 3;
    public static final int MAX_CHILDREN = 20;
    private static final String KEY_PATTERN = "[a-z0-9][a-z0-9-]{0,39}";

    private StoreNavigationValidator() {
    }

    public static void validate(StoreNavigationContent content, StoreNavigationUrlPolicy urls) {
        if (content == null || content.items() == null || content.items().size() > MAX_ITEMS) {
            fail(Reason.ITEMS);
        }
        Map<String, Item> byKey = new HashMap<>();
        for (Item item : content.items()) {
            if (item == null) {
                fail(Reason.ITEMS);
            }
            if (item.key() == null || !item.key().matches(KEY_PATTERN) || byKey.putIfAbsent(item.key(), item) != null) {
                fail(Reason.KEY);
            }
        }
        for (Item item : content.items()) {
            if (item.parentKey() != null && (!byKey.containsKey(item.parentKey()) || item.parentKey().equals(item.key()))) {
                fail(Reason.PARENT);
            }
        }
        Map<String, Set<Integer>> siblingOrders = new HashMap<>();
        for (Item item : content.items()) {
            if (depth(item, byKey) > MAX_DEPTH) {
                fail(Reason.DEPTH);
            }
            item(item, urls);
            Set<Integer> orders = siblingOrders.computeIfAbsent(item.parentKey(), ignored -> new HashSet<>());
            if (!orders.add(item.displayOrder())) {
                fail(Reason.ORDER);
            }
            if (orders.size() > MAX_CHILDREN) {
                fail(Reason.ITEMS);
            }
        }
    }

    private static int depth(Item item, Map<String, Item> byKey) {
        int depth = 1;
        Set<String> seen = new HashSet<>();
        Item current = item;
        while (current.parentKey() != null) {
            if (!seen.add(current.key())) {
                fail(Reason.CYCLE);
            }
            current = byKey.get(current.parentKey());
            depth++;
            if (depth > MAX_ITEMS + 1) {
                fail(Reason.CYCLE);
            }
        }
        return depth;
    }

    private static void item(Item item, StoreNavigationUrlPolicy urls) {
        if (item.type() == null) {
            fail(Reason.TYPE);
        }
        StoreNavigationContent.Type type;
        try {
            type = StoreNavigationContent.Type.valueOf(item.type());
        } catch (IllegalArgumentException exception) {
            throw new StoreNavigationRuleException(Reason.TYPE);
        }
        if (item.labels() == null) {
            fail(Reason.LABELS);
        }
        text(item.labels().ko());
        text(item.labels().en());
        switch (type) {
            case INTERNAL -> {
                urls.validateInternal(item.url());
                noCategory(item);
            }
            case EXTERNAL -> {
                urls.validateExternal(item.url());
                noCategory(item);
            }
            case CATEGORY -> {
                if (item.url() != null) {
                    fail(Reason.URL);
                }
                if (item.categoryId() == null || item.categoryId() <= 0) {
                    fail(Reason.CATEGORY);
                }
            }
            case NONE -> {
                if (item.url() != null) {
                    fail(Reason.URL);
                }
                noCategory(item);
            }
        }
        if (item.displayOrder() == null || item.displayOrder() < 0 || item.displayOrder() > 999) {
            fail(Reason.ORDER);
        }
        if (item.visible() == null) {
            fail(Reason.VISIBLE);
        }
    }

    private static void noCategory(Item item) {
        if (item.categoryId() != null) {
            fail(Reason.CATEGORY);
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 40 || value.codePoints().anyMatch(Character::isISOControl)) {
            fail(Reason.LABELS);
        }
    }

    private static void fail(Reason reason) {
        throw new StoreNavigationRuleException(reason);
    }
}
