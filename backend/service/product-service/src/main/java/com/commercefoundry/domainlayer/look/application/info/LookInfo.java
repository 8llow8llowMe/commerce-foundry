package com.commercefoundry.domainlayer.look.application.info;

import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import java.util.List;

/**
 * 고객 룩 한 개.
 *
 * @param description 설명. 없으면 null
 * @param products 고객에게 보이는 구성 상품 카드 (룩 안의 순서). 숨긴 상품은 빠진다 — 모두 빠지면 빈 목록이고 룩은 남는다
 */
public record LookInfo(long lookId, String title, String description, LookImage image, List<LookProductInfo> products) {
}
