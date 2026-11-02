package com.commercefoundry.domainlayer.knowledge.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.SupportAdminAuditProcessor;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.response.KnowledgeAdminResponses;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.presenter.KnowledgeAdminPresenter;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeCreateCommand;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeAdminInfo;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeAdminQuery;
import com.commercefoundry.domainlayer.knowledge.application.port.in.KnowledgeAdminUseCase;
import com.commercefoundry.domainlayer.knowledge.application.service.processor.KnowledgeProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Processor가 여는 기존 원자적 트랜잭션 경계를 유지하며 커밋 실패도 감사한다. */
@Service
@RequiredArgsConstructor
public class KnowledgeAdminFacade implements KnowledgeAdminUseCase {

    private final KnowledgeProcessor processor;
    private final KnowledgeAdminPresenter presenter;
    private final SupportAdminAuditProcessor audit;
    private final Clock clock;

    @Override
    public PageResponse<KnowledgeAdminResponses.Summary> list(KnowledgeAdminQuery query) {
        return presenter.toPage(processor.listAdmin(query.type(), query.locale(), query.page(), query.size()));
    }

    @Override
    public KnowledgeAdminResponses.Detail get(long documentId) {
        return presenter.toResponse(processor.getAdmin(documentId));
    }

    @Override
    public KnowledgeAdminResponses.Detail create(KnowledgeCreateCommand command, long actor) {
        KnowledgeAdminInfo created;
        try {
            created = processor.createAdmin(command.type(), command.locale(), command.content());
        } catch (RuntimeException exception) {
            audit.created(actor, null, false);
            throw exception;
        }
        audit.created(actor, created.document().id(), true);
        return presenter.toResponse(created);
    }

    @Override
    public KnowledgeAdminResponses.Detail edit(long documentId, int number, Long version, KnowledgeContentCommand content, long actor) {
        return presenter.toResponse(audited(() -> processor.editAdmin(documentId, number, version, content), ok -> audit.edited(actor, documentId, ok)));
    }

    @Override
    public KnowledgeAdminResponses.Detail createDraft(long documentId, Long documentVersion, KnowledgeContentCommand content, long actor) {
        return presenter.toResponse(audited(() -> processor.createDraftAdmin(documentId, documentVersion, content), ok -> audit.draftCreated(actor, documentId, ok)));
    }

    @Override
    public KnowledgeAdminResponses.Detail approve(long documentId, int number, Long version, long actor) {
        return presenter.toResponse(audited(() -> processor.approveAdmin(documentId, number, version, actor, clock.instant()), ok -> audit.approved(actor, documentId, ok)));
    }

    @Override
    public KnowledgeAdminResponses.Detail publish(long documentId, int number, Long version, Long documentVersion, long actor) {
        return presenter.toResponse(audited(
            () -> processor.publishAdmin(documentId, number, version, documentVersion, clock.instant()), ok -> audit.published(actor, documentId, ok)));
    }

    @Override
    public void archive(long documentId, int number, Long version, Long documentVersion, long actor) {
        audited(() -> {
            processor.archiveAdmin(documentId, number, version, documentVersion);
            return null;
        }, ok -> audit.archived(actor, documentId, ok));
    }

    private <T> T audited(Supplier<T> work, Consumer<Boolean> record) {
        T result;
        try {
            result = work.get();
        } catch (RuntimeException exception) {
            record.accept(false);
            throw exception;
        }
        record.accept(true);
        return result;
    }
}
