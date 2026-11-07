package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto.SizeChartGenerationInputInternalResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SizeChartGenerationInputInternalResponseSupport {
    private final CircuitBreaker circuitBreaker;

    public SizeChartGenerationInputInternalResponseSupport(CircuitBreakerRegistry registry) {
        circuitBreaker = registry.circuitBreaker("product-service");
    }

    public <T> T call(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (FeignException.FeignClientException exception) {
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_REJECTED, false);
        } catch (FeignException | CallNotPermittedException exception) {
            // No remote body, exception cause or diagnostic message can reach worker logs.
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_UNAVAILABLE, true);
        }
    }

    public SizeChartGenerationInputQueryResult toQueryResult(Response<SizeChartGenerationInputInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_INVALID, false);
        }
        var body = response.dataBody();
        long draftId;
        try {
            if (body.draftId() == null || !body.draftId().matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            draftId = Long.parseLong(body.draftId());
        } catch (NumberFormatException exception) {
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_INVALID, false);
        }
        return new SizeChartGenerationInputQueryResult(draftId, body.inputHash(), body.sourceJson(), body.templateJson());
    }
}
