package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.persistence.dto.PageResponse;

public interface ShippingPolicyAdminUseCase {

    /** 아직 설정되지 않았으면 null 이다. */
    ShippingPolicyAdminResponse getDomesticPolicy();

    /** 전체 교체. 정책이 없고 버전이 null 이면 만든다. 성공하면 같은 트랜잭션에서 교체 이력을 한 건 남긴다. */
    ShippingPolicyAdminResponse replaceDomesticPolicy(ShippingPolicyReplaceCommand command);

    /** 교체 이력 — 최신순 한 페이지. 이력이 없으면 빈 페이지다. */
    PageResponse<ShippingPolicyHistoryAdminItem> getDomesticPolicyHistories(int page, int size);
}
