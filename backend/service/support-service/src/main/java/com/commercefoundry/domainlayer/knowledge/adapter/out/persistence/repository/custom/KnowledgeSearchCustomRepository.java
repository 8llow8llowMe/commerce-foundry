package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeSearchCriteria;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSearchQueryResult;
import java.time.Instant;
import java.util.List;

public interface KnowledgeSearchCustomRepository {
    List<KnowledgeSearchQueryResult> search(KnowledgeSearchCriteria criteria, Instant cutoff);
}
