package com.commercefoundry.domainlayer.knowledge.application.service;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSearchInfo;
import com.commercefoundry.domainlayer.knowledge.application.port.in.KnowledgeSearchUseCase;
import com.commercefoundry.domainlayer.knowledge.application.service.processor.KnowledgeProcessor;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeSearchFacade implements KnowledgeSearchUseCase {
    private final KnowledgeProcessor processor;
    private final Clock clock;

    @Override
    public List<KnowledgeSearchInfo> search(KnowledgeSearchCommand command) {
        return processor.search(command, clock.instant());
    }
}
