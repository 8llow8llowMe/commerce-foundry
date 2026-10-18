package com.commercefoundry.domainlayer.cart.application.command;

import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import java.util.Objects;

/**
 * 장바구니에 담기 — 단건 담기와 병합의 한 항목이 같이 쓴다.
 *
 * @param quantity 1 이상 99 이하 (요청 검증이 거른다)
 * @param customOptions 맞춤 제작 입력. 없으면 빈 묶음
 */
public record CartItemAddCommand(long skuId, int quantity, CustomOptions customOptions) {

    public CartItemAddCommand {
        Objects.requireNonNull(customOptions, "customOptions");
    }
}
