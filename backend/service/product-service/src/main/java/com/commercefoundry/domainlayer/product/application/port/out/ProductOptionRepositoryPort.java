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

    /** 기존 행의 이름·순서·수정 시각만 변경한다. */
    void updateLabels(List<OptionGroup> existingGroups, List<OptionValue> existingValues, List<OptionGroup> groups, List<OptionValue> values);

    /** 새 그룹·값 INSERT. 아이디는 호출한 쪽이 발급했다. */
    void insertAll(List<OptionGroup> groups, List<OptionValue> values);

    /**
     * 옵션 그룹 · 값 은퇴 (#9 PR-C2). 아직 사용 중인 행만 {@code retiredAt} 을 채운다 — 이미 은퇴한 행은 그대로다. 행은 지우지 않는다(주문 · 장바구니 옵션명 표시).
     * 호출자가 상품 버전 CAS 로 동시 편집을 먼저 막는다.
     */
    void retire(long productId, Collection<Long> optionGroupIds, Collection<Long> optionValueIds, java.time.Instant retiredAt);
}
