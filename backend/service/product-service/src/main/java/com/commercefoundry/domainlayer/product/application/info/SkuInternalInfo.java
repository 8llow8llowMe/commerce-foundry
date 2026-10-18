package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import lombok.Builder;

/**
 * 서비스 간 SKU 조회 한 건 (주문 생성 · 장바구니 가격 확인).
 *
 * @param optionValues 옵션 그룹의 (노출 순서, 아이디) 순. 옵션이 없으면 빈 목록
 * @param optionName 옵션 값 이름을 {@code " / "} 로 이은 표시명. 옵션이 없으면 빈 문자열
 * @param saleStatus 상품 · SKU 판매 상태와 실제 재고에서 낸다. 재고 행이 없는 SKU 는 품절이다
 * @param purchasable 상품이 노출 중이고 판매 상태가 판매중일 때만 true (품절이면 false) — 숨긴 상품의 SKU 도 행은 내린다
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param customOrder 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true
 * @param weightGrams SKU 배송 무게 (그램). null 이면 미입력 — 해외 주문에서 거절할지는 호출한 쪽(order-service)이 정한다
 * @param hsCode 통관 신고용 HS 코드. null 이면 미입력
 */
@Builder
public record SkuInternalInfo(
    long skuId, long productId, String productName, List<SkuOptionValueInfo> optionValues, String optionName,
    Money salePrice, Money additionalPrice, SaleAvailability saleStatus, boolean purchasable,
    Integer maxQuantityPerOrder, boolean customOrder, Integer weightGrams, HsCode hsCode
) {

    public SkuInternalInfo {
        optionValues = List.copyOf(optionValues);
    }
}
