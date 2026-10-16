package com.commercefoundry.domainlayer.product.application.info;

import java.util.List;

/**
 * 맞춤 제작 안내. 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 만든다.
 *
 * @param fields 노출 순서 → 아이디 오름차순
 */
public record CustomOrderInfo(String notice, Integer leadTimeDays, List<CustomOrderFieldInfo> fields) {

    public CustomOrderInfo {
        fields = List.copyOf(fields);
    }
}
