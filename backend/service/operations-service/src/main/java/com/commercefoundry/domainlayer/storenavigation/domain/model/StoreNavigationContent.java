package com.commercefoundry.domainlayer.storenavigation.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 한 리비전의 상단 메뉴 전체. 요청·저장은 평평한 목록이고 {@code parentKey}로 트리를 만든다.
 * key 는 리비전 안에서만 의미가 있는 편집용 식별자이며 공개 응답에는 내리지 않는다.
 */
public record StoreNavigationContent(List<Item> items) {
    public StoreNavigationContent {
        items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public record Text(String ko, String en) {
        public String localized(String locale) {
            return "ko".equals(locale) ? ko : en;
        }
    }

    /** INTERNAL: 스토어 내부 상대 경로, EXTERNAL: 허용 호스트 https URL, CATEGORY: 카테고리 참조(url 없음), NONE: 링크 없는 묶음 제목. */
    public enum Type { INTERNAL, EXTERNAL, CATEGORY, NONE }

    public record Item(String key, String parentKey, String type, Text labels, String url, Long categoryId,
                       Integer displayOrder, Boolean visible) {
    }

    public record PublicNode(String label, String type, String url, Long categoryId, List<PublicNode> children) {
        public PublicNode {
            children = List.copyOf(children);
        }
    }

    /**
     * 검증을 통과한 내용에서 공개 트리를 만든다. 숨김 항목은 하위 전체를 숨기고, 형제는 displayOrder 오름차순이다.
     * 언어 fallback 은 없다.
     */
    public List<PublicNode> publicTree(String locale) {
        return nodes(children(), null, locale);
    }

    /**
     * 검증을 통과한 내용을 트리 전위 순서(부모 다음 자식, 형제는 displayOrder 오름차순)의 평평한 목록으로 바꾼다.
     * 숨김 항목도 포함한다. 저장 직후 응답과 다시 읽은 결과가 같은 순서가 되게 맞추는 용도다.
     */
    public StoreNavigationContent ordered() {
        List<Item> result = new ArrayList<>(items.size());
        appendOrdered(children(), null, result);
        return new StoreNavigationContent(result);
    }

    private Map<String, List<Item>> children() {
        Map<String, List<Item>> children = new LinkedHashMap<>();
        for (Item item : items) {
            children.computeIfAbsent(item.parentKey(), ignored -> new ArrayList<>()).add(item);
        }
        return children;
    }

    private static void appendOrdered(Map<String, List<Item>> children, String parentKey, List<Item> result) {
        children.getOrDefault(parentKey, List.of()).stream().sorted(Comparator.comparing(Item::displayOrder)).forEach(item -> {
            result.add(item);
            appendOrdered(children, Objects.requireNonNull(item.key()), result);
        });
    }

    private static List<PublicNode> nodes(Map<String, List<Item>> children, String parentKey, String locale) {
        return children.getOrDefault(parentKey, List.of()).stream()
            .filter(item -> Boolean.TRUE.equals(item.visible()))
            .sorted(Comparator.comparing(Item::displayOrder))
            .map(item -> new PublicNode(item.labels().localized(locale), item.type(), item.url(), item.categoryId(),
                nodes(children, Objects.requireNonNull(item.key()), locale)))
            .toList();
    }
}
