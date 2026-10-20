package com.commercefoundry.domainlayer.product.application.command;

import java.util.Objects;
import lombok.Builder;

/**
 * 어드민 카테고리 생성 · 수정(전체 교체). 수정에서 {@code parentId} 를 바꾸면 하위까지 함께 옮긴다.
 *
 * @param parentId 상위 카테고리. 최상위면 null
 * @param name 앞뒤 공백을 뺀 이름
 * @param displayOrder 같은 상위 안의 노출 순서 (오름차순, 0 이상)
 * @param visible 스토어프론트 메뉴 노출 여부. false 면 하위까지 메뉴에서 빠진다 (상품 노출은 막지 않는다)
 */
@Builder
public record CategoryUpsertCommand(Long parentId, String name, int displayOrder, boolean visible) {

    public CategoryUpsertCommand {
        Objects.requireNonNull(name, "name");
    }
}
