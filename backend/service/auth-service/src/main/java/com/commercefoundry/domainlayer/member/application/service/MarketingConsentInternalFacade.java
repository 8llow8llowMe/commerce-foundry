package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.MarketingConsentInternalPresenter;
import com.commercefoundry.domainlayer.member.application.port.in.MarketingConsentInternalUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.MarketingConsentQueryProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketingConsentInternalFacade implements MarketingConsentInternalUseCase {

    private final MarketingConsentQueryProcessor marketingConsentQueryProcessor;
    private final MarketingConsentInternalPresenter marketingConsentInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public MarketingConsentInternalResponse query(List<Long> memberIds) {
        return marketingConsentInternalPresenter.toResponse(marketingConsentQueryProcessor.query(memberIds));
    }
}
