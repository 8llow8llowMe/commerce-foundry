package com.commercefoundry.domainlayer.knowledge.application.mapper;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.KnowledgeDocumentEntity;
import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.entity.KnowledgeRevisionEntity;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface KnowledgeMapper {

    default KnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        return KnowledgeDocumentEntity.builder().id(document.id()).type(document.type()).locale(document.locale())
            .latestRevision(document.latestRevision()).publishedRevision(document.publishedRevision()).version(document.version()).build();
    }

    default KnowledgeDocument toDomain(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocument(entity.getId(), entity.getType(), entity.getLocale(), entity.getLatestRevision(), entity.getPublishedRevision(), entity.getVersion());
    }

    default KnowledgeRevisionEntity toEntity(KnowledgeRevision revision) {
        KnowledgeRevision.Content content = revision.content();
        return KnowledgeRevisionEntity.builder().id(revision.id()).documentId(revision.documentId()).revisionNumber(revision.revisionNumber())
            .title(content.title()).body(content.body()).category(content.category()).validFrom(content.validFrom()).validTo(content.validTo())
            .status(revision.status()).approvedBy(revision.approvedBy()).approvedAt(revision.approvedAt()).publishedAt(revision.publishedAt())
            .version(revision.version()).build();
    }

    default KnowledgeRevision toDomain(KnowledgeRevisionEntity entity) {
        return new KnowledgeRevision(entity.getId(), entity.getDocumentId(), entity.getRevisionNumber(),
            new KnowledgeRevision.Content(entity.getTitle(), entity.getBody(), entity.getCategory(), entity.getValidFrom(), entity.getValidTo()),
            entity.getStatus(), entity.getApprovedBy(), entity.getApprovedAt(), entity.getPublishedAt(), entity.getVersion());
    }
}
