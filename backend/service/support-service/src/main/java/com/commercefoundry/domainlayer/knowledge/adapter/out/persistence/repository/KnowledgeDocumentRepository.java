package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.KnowledgeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {
}
