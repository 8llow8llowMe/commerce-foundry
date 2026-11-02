package com.commercefoundry.domainlayer.knowledge.application.port.in;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSearchInfo;
import java.util.List;

/** Same-service search entry point; no HTTP adapter is exposed. */
public interface KnowledgeSearchUseCase {
    List<KnowledgeSearchInfo> search(KnowledgeSearchCommand command);
}
