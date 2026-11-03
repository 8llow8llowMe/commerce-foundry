package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;

/**
 * 기존 상품에 옵션 값 · 새 조합 SKU 추가 (#9 PR-C1). 문구는 앞뒤 공백을 뺐다.
 *
 * @param newOptionValues 기존 그룹에 더할 값. 배열 순서대로 그룹 끝에 붙는다
 * @param skus 새 조합. 옵션 값은 그룹 노출 순서대로 이름으로 고른다 — 새 값은 요청 안의 이름으로 가리킨다
 */
public record SkuAddCommand(long productId, long version, long actorMemberId, List<NewOptionValue> newOptionValues, List<NewSku> skus) {

    public SkuAddCommand {
        newOptionValues = List.copyOf(newOptionValues);
        skus = List.copyOf(skus);
    }

    public record NewOptionValue(long optionGroupId, String name) {
    }

    /** @param initialStock 없으면 재고 행을 만들지 않는다(품절로 생긴다) — 등록(E1)과 같다 */
    public record NewSku(List<String> optionValues, Money additionalPrice, SaleStatus saleStatus, Integer weightGrams, HsCode hsCode,
                         InitialStock initialStock) {
        public NewSku {
            optionValues = List.copyOf(optionValues);
        }
    }

    public record InitialStock(boolean stockManaged, long quantity) {
    }
}
