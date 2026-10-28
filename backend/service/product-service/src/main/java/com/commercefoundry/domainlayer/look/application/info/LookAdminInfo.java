package com.commercefoundry.domainlayer.look.application.info;

import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import java.util.List;

/**
 * 어드민 룩 한 개 — 숨긴 룩과 숨김 · 판매 종료 상품도 담는다. 노출 순서 번호는 담지 않는다 — 목록의 배열 순서로 전한다 ({@code api-design-guide.md} §2-8).
 *
 * @param products 구성 상품 (룩 안의 순서)
 */
public record LookAdminInfo(long lookId, String title, String description, LookImage image, boolean visible, List<LookProductAdminInfo> products) {
}
