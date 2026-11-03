package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.Collection;
import java.util.List;

public interface ProductCustomOrderFieldRepositoryPort {

    /** 노출 순서 → 아이디 오름차순. */
    List<CustomOrderField> findAllByProductId(long productId);

    /**
     * 여러 상품의 입력 항목. 상품 아이디 → 노출 순서 → 아이디 오름차순.
     * 빈 컬렉션이면 쿼리하지 않는다.
     */
    List<CustomOrderField> findAllByProductIds(Collection<Long> productIds);

    /**
     * 한 상품의 항목 정의를 {@code next} 로 맞춘다 (#9 PR-B) — {@code existing} 에만 있는 아이디는 지우고, 둘 다 있는 아이디는 고치고, 새 아이디는 넣는다.
     * 같은 키는 같은 아이디라 고유 제약(상품 · 키)과 부딪히지 않는다. 호출자가 상품 버전으로 동시 편집을 먼저 막는다 —
     * 이 표에 쓰는 모든 경로는 상품 버전을 올려야 한다(안 올리는 쓰기가 생기면 오래된 정의로 덮어쓴다).
     * 일괄 쿼리가 영속성 컨텍스트를 비우므로 트랜잭션의 마지막 쓰기로 부른다.
     *
     * @throws IllegalStateException 고칠 행이 없다(읽은 뒤 사라졌다)
     * @throws com.commercefoundry.domainlayer.product.application.exception.ProductException 고유 제약 위반 — 동시 변경으로 본다(409 {@code PRODUCT_010})
     */
    void replace(long productId, List<CustomOrderField> existing, List<CustomOrderField> next);
}
