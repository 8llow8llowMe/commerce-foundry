package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.out.ai;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.prompt.PromptValues;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.model.LegalDraftRemoteFailure;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftAiPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class LegalDraftAiAdapter implements LegalDraftAiPort {
    private final AiClient client;
    private final PromptTemplates templates;
    private final ObjectMapper mapper;

    @Override
    public LegalDraftPlan generate(String type, String locale, int templateVersion, List<String> sectionIds, List<String> confirmedSectionIds,
                                   long jobId, int attempt) {
        var promptData = new PlanningInput(type, locale, templateVersion, sectionIds, confirmedSectionIds);
        try {
            var request = templates.get("legal-draft").render(PromptValues.create().put("planningJson", mapper.writeValueAsString(promptData)))
                .withCallerRef("job:" + jobId + ":" + attempt);
            return client.complete(request, LegalDraftPlan.class).value();
        } catch (AiException exception) {
            var code = switch (exception.reason()) {
                case BUDGET_EXCEEDED -> LegalDraftRemoteFailure.Code.LEGAL_DRAFT_BUDGET_EXCEEDED;
                case INVALID_RESPONSE -> LegalDraftRemoteFailure.Code.LEGAL_DRAFT_AI_INVALID;
                default -> LegalDraftRemoteFailure.Code.LEGAL_DRAFT_AI_FAILED;
            };
            throw new LegalDraftRemoteFailure(code);
        } catch (IllegalArgumentException exception) {
            throw new LegalDraftRemoteFailure(LegalDraftRemoteFailure.Code.LEGAL_DRAFT_AI_INVALID);
        }
    }

    private record PlanningInput(String type, String locale, int templateVersion, List<String> sectionIds, List<String> confirmedSectionIds) {}
}
