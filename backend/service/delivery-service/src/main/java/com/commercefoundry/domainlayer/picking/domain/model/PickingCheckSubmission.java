package com.commercefoundry.domainlayer.picking.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** SKU 수량과 해석하지 못한 스캔을 하나의 검수 시도로 식별한다. */
public record PickingCheckSubmission(Map<Long, Long> quantities, Map<String, Long> unknownBarcodes) {

    public String key() {
        String skuKey = PickingCheck.key(quantities);
        if (unknownBarcodes.isEmpty()) {
            return skuKey;
        }
        // 바코드에 구분자가 들어올 수 없다. 지문만 저장하므로 잘못 스캔한 원문도 이력에 남기지 않는다.
        String scans = new TreeMap<>(unknownBarcodes).entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue()).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scans.getBytes(StandardCharsets.UTF_8));
            return skuKey + "|" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", exception);
        }
    }
}
