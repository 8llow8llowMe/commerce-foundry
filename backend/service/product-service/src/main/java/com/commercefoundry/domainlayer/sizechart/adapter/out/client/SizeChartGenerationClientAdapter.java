package com.commercefoundry.domainlayer.sizechart.adapter.out.client;

import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.SizeChartGenerationClient;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegisterRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.support.SizeChartGenerationInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationClientAdapter implements SizeChartGenerationPort {
    private final SizeChartGenerationClient client;
    private final SizeChartGenerationInternalResponseSupport support;

    @Override
    public SizeChartGenerationRegistrationQueryResult register(SizeChartDraft draft) {
        var input = draft.input();
        var request = new SizeChartGenerationRegisterRequest(draft.id(), input.inputHash(), input.requestedByMemberId(), draft.expiresAt());
        return support.registration(draft, support.call(() -> client.register(request), true));
    }

    @Override
    public SizeChartGenerationResultQueryResult result(SizeChartDraft draft) {
        if (draft.jobId() == null) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        }
        return support.result(draft, support.call(() -> client.result(draft.jobId()), false));
    }
}
