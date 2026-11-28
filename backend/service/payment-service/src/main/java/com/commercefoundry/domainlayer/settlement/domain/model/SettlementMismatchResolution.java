package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 불일치를 닫은 방법. 수동 처리(MANUAL)는 어드민 API(#219 PR3)가 값과 check 제약(drop / add)을 함께 더한다.
 */
@Getter
@RequiredArgsConstructor
public enum SettlementMismatchResolution implements CodeNameDescribable {

    AUTO_CLEARED("자동 해소", "다시 대사했을 때 더는 어긋나지 않아 자동으로 닫았습니다.");

    private final String displayName;
    private final String description;
}
