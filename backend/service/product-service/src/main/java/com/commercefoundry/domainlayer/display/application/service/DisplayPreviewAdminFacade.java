package com.commercefoundry.domainlayer.display.application.service;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplayPreviewAdminResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.presenter.DisplayPreviewAdminPresenter;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayPreviewAdminUseCase;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전시 저장소와 무관한 초안 미리보기. 상품은 숨김 포함 존재 확인 후 고객 카드 규칙으로 일괄 조회한다. */
@Service
@RequiredArgsConstructor
public class DisplayPreviewAdminFacade implements DisplayPreviewAdminUseCase {

    private final DisplaySectionProcessor displaySectionProcessor;
    private final ProductLookupProcessor productLookupProcessor;
    private final ProductListProcessor productListProcessor;
    private final DisplayPreviewAdminPresenter displayPreviewAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public DisplayPreviewAdminResponse preview(List<DisplaySectionUpsertCommand> sections) {
        List<DisplaySectionContent> drafts = displaySectionProcessor.previewDraftsOf(sections);
        Set<Long> allProductIds = displaySectionProcessor.productIdsOf(drafts);
        displaySectionProcessor.validateProductsExist(allProductIds, productLookupProcessor.findAllIncludingHidden(allProductIds));
        List<DisplaySectionContent> live = displaySectionProcessor.liveContentsOf(drafts);
        return displayPreviewAdminPresenter.toMainResponse(displaySectionProcessor.toMainInfo(
            live, productListProcessor.summarizeVisible(displaySectionProcessor.productIdsOf(live))
        ));
    }
}
