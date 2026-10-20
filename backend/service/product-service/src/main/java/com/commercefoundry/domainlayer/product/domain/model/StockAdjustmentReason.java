package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민 재고 조정 사유. 조정 이력({@link StockAdjustmentHistory})에 남는다.
 *
 * <p>요청은 코드 문자열(이름)로 받고, 이력 조회 응답은 화면 표에 표시하므로 {@code {code, name, description}} 메타데이터로 내린다
 * ({@code coding-conventions.md} §11).
 */
@Getter
@RequiredArgsConstructor
public enum StockAdjustmentReason implements CodeNameDescribable {

    RESTOCK("입고", "새로 들어온 수량을 반영했습니다."),
    RECOUNT("실사", "실물 수량에 맞췄습니다."),
    DAMAGE("파손 · 불량", "파손 · 불량으로 판매할 수 없는 수량을 뺐습니다."),
    MANAGEMENT_CHANGE("관리 여부 변경", "재고 관리 여부(무제한 ↔ 수량 관리)를 바꿨습니다."),
    OTHER("기타", "메모에 사유를 남겼습니다.");

    private final String displayName;
    private final String description;
}
