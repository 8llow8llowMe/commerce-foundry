package com.commercefoundry.domainlayer.look.application.info;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;

/**
 * 룩 구성 상품 — 어드민이 알아볼 정도만 담는다. 상품 컨텍스트 domain 은 Presenter 까지 넘기지 않고 여기로 옮긴다 ({@code architecture-guide.md} §3 H8).
 *
 * @param name 상품명. 상품 행이 없으면(데이터 오류) null
 * @param visible 고객 노출 여부. 상품 행이 없으면 false
 * @param saleStatus 저장한 판매 상태(판매중 · 판매 종료). 상품 행이 없으면 null
 */
public record LookProductAdminInfo(long productId, String name, boolean visible, CodeNameDescribable saleStatus) {
}
