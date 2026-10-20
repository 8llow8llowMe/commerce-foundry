package com.commercefoundry.domainlayer.product.application.info;

import java.util.List;
import java.util.Objects;

/**
 * 어드민 카테고리 한 노드 (숨김 포함). 카테고리 컨텍스트의 domain model 을 상품 컨텍스트 Presenter 가 직접 쓰지 않게 {@code CategoryAdminProcessor} 가 옮겨 담는다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙 — 다른 컨텍스트 domain model 은 Facade · Processor 에서만 쓴다).
 *
 * @param parentId 최상위면 null
 * @param children 하위 노드. 노출 순서 → 아이디 오름차순이다. 생성 · 수정 결과처럼 자기 자신만 낼 때는 빈 목록이다
 */
public record CategoryAdminInfo(long categoryId, Long parentId, String name, int displayOrder, boolean visible, List<CategoryAdminInfo> children) {

    public CategoryAdminInfo {
        Objects.requireNonNull(name, "name");
        children = List.copyOf(children);
    }
}
