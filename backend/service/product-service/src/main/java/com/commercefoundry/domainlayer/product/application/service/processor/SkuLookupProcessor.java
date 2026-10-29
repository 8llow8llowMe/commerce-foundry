package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 서비스의 다른 컨텍스트(SKU 원가 어드민)가 SKU 를 아이디로 확인할 때 쓰는 용도별 조회. <b>숨긴 상품 · 판매 중지 SKU 도 있는 SKU 다 — 어드민 · 쓰기 판정 전용이다.</b>
 * 없으면 상품 컨텍스트의 404 {@code PRODUCT_002} 를 던지고, 상품 컨텍스트의 {@code ProductExceptionHandler} 가 봉투로 바꾼다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 */
@Component
@RequiredArgsConstructor
public class SkuLookupProcessor {

    private final SkuRepositoryPort skuRepositoryPort;

    /** 있는지만 본다. */
    public void requireExists(long skuId) {
        if (!skuRepositoryPort.existsById(skuId)) {
            throw new ProductException(ProductErrorCode.NOT_FOUND_SKU);
        }
    }

    /**
     * SKU 행을 잠그고 읽는다 — 같은 SKU 기준으로 판정하는 쓰기가 줄을 선다. 잠금은 호출한 트랜잭션이 끝날 때 풀리므로 트랜잭션 밖이면 실패한다.
     * 잠금 대기 상한 초과 예외는 그대로 올라간다 — 호출한 Facade 가 자기 컨텍스트의 409 로 바꾼다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Sku requireForUpdate(long skuId) {
        return skuRepositoryPort.findByIdForUpdate(skuId).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_SKU));
    }
}
