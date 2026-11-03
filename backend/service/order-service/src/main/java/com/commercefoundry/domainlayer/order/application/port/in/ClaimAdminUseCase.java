package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.persistence.dto.PageResponse;

public interface ClaimAdminUseCase {
    PageResponse<ClaimAdminItem> getClaims(ClaimAdminFilter filter, long adminId);
    ClaimAdminResponse getClaim(long claimId, long adminId);
}
