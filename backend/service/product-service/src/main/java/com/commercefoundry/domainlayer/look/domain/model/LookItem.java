package com.commercefoundry.domainlayer.look.domain.model;

/**
 * 룩을 이루는 상품 하나. 상품은 아이디로만 가리킨다 — 상품 컨텍스트 domain 을 필드로 갖지 않는다 ({@code architecture-guide.md} §3).
 *
 * @param displayOrder 룩 안의 순서 (0 부터, 오름차순)
 */
public record LookItem(long id, long lookId, long productId, int displayOrder) {

    public LookItem {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder 는 0 이상이어야 합니다.");
        }
    }
}
