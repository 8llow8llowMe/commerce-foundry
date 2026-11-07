package com.commercefoundry.domainlayer.sizechart.application.port.in;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftAcceptedResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftApplyResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartDraftSummaryResponse;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftSummaryInfo;
import java.util.Set;
import org.springframework.data.domain.Page;

public interface SizeChartDraftUseCase {
    SizeChartDraftAcceptedResponse createAdmin(SizeChartDraftCreateCommand command);

    SizeChartDraftResponse getAdmin(long draftId);

    PageResponse<SizeChartDraftSummaryResponse> listAdmin(Long productId, Long requestedByMemberId, int page, int size);

    SizeChartDraftResponse refreshAdmin(long draftId, long actorId);

    SizeChartDraftApplyResponse applyAdmin(long draftId, long expectedDraftVersion, ProductSizeChartUpsertCommand correctedChart,
        Set<String> acknowledgedWarningIds, long actorId);

    SizeChartDraftInfo create(SizeChartDraftCreateCommand command);

    SizeChartDraftInfo get(long draftId);

    Page<SizeChartDraftSummaryInfo> list(Long productId, Long requestedByMemberId, int page, int size);

    SizeChartDraftInfo refresh(long draftId, long actorId);

    SizeChartDraftApplyInfo apply(
        long draftId, long expectedDraftVersion,
        ProductSizeChartUpsertCommand correctedChart,
        Set<String> acknowledgedWarningIds, long actorId);

}
