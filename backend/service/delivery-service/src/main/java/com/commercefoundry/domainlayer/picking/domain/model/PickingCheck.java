package com.commercefoundry.domainlayer.picking.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 포장 검수 한 번의 기록 — 운영자가 센 상품 옵션별 수량과 그 결과. 검수 성공은 "주문대로 꺼냈다" 는 뜻일 뿐 출고 · 송장 등록이 아니다.
 * 실패 사유에 원격 오류 원문을 넣지 않는다.
 *
 * @param quantitiesKey 보낸 수량을 상품 옵션 오름차순 {@code 옵션:수량} 으로 이은 값 — 같은 주문 · 같은 수량의 재요청을 알아보는 키이자 보낸 수량의 기록이다
 */
public record PickingCheck(long orderId, String quantitiesKey, Failure failure) {

    /** 검수 결과. 저장값({@code picking_check.failure})이라 이름을 바꾸면 DDL 도 바꾼다. */
    public enum Failure {
        /** 성공 — 주문의 상품 옵션 · 수량과 정확히 같다. */
        NONE,
        /** 이 출고 묶음의 대상 주문이 아니다(제외된 주문 포함). */
        ORDER_NOT_IN_BATCH,
        /** 0 이하 수량이 있다. */
        INVALID_QUANTITY,
        /** 주문에 없는 상품 옵션이 있다. */
        WRONG_SKU,
        /** 덜 꺼냈다. */
        SHORTAGE,
        /** 더 꺼냈다. */
        EXCESS,
        /** 이미 검수에 성공한 주문을 다른 수량으로 다시 보냈다 — 앞선 성공을 덮지 않는다. */
        ALREADY_CHECKED,
        /** 상품에 등록되지 않은 바코드를 스캔했다. */
        UNKNOWN_BARCODE
    }

    public PickingCheck {
        if (orderId <= 0) {
            throw new IllegalArgumentException("주문 아이디는 양수여야 합니다.");
        }
        Objects.requireNonNull(quantitiesKey, "검수 수량 키가 필요합니다.");
        Objects.requireNonNull(failure, "검수 결과가 필요합니다.");
    }

    public boolean successful() {
        return failure == Failure.NONE;
    }

    public String submittedSkuKey() {
        int separator = quantitiesKey.indexOf('|');
        return separator < 0 ? quantitiesKey : quantitiesKey.substring(0, separator);
    }

    /** 상품 옵션 → 수량을 순서 없는 키로 바꾼다. 맵 · 옵션 · 수량이 null 이면 {@link IllegalArgumentException}. */
    public static String key(Map<Long, Long> quantities) {
        if (quantities == null || quantities.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("검수할 상품 옵션과 수량이 필요합니다.");
        }
        return new TreeMap<>(quantities).entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(Collectors.joining(","));
    }
}
