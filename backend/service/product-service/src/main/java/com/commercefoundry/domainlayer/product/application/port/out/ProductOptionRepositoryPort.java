package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import java.util.Collection;
import java.util.List;

/**
 * 옵션 그룹 · 값. 아이디 목록 조회는 빈 컬렉션이면 쿼리 없이 빈 목록이다.
 */
public interface ProductOptionRepositoryPort {

    /** 노출 순서 → 아이디 오름차순. */
    List<OptionGroup> findGroupsByProductId(long productId);

    /** 노출 순서 → 아이디 오름차순. 그룹으로 나누면 그룹 안의 순서가 된다. */
    List<OptionValue> findValuesByProductId(long productId);

    List<OptionGroup> findGroupsByIds(Collection<Long> optionGroupIds);

    List<OptionValue> findValuesByIds(Collection<Long> optionValueIds);
}
