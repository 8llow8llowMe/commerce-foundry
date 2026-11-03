package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.ClaimAdminPresenter;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.ClaimAdminProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 어드민 클레임 조회 (#17). */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ClaimAdminFacade implements ClaimAdminUseCase {
    private final ClaimAdminProcessor processor;
    private final ClaimAdminPresenter presenter;
    private final ClaimAdminAuditProcessor audit;

    @Override
    public PageResponse<ClaimAdminItem> getClaims(ClaimAdminFilter filter, long adminId) {
        PageResponse<ClaimAdminItem> response = presenter.toPageResponse(processor.search(filter));
        audit.searched(adminId, filter);
        return response;
    }

    @Override
    public ClaimAdminResponse getClaim(long claimId, long adminId) {
        ClaimAdminResponse response = presenter.toResponse(processor.getDetail(claimId));
        audit.viewed(adminId, claimId);
        return response;
    }
}
