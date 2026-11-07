package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No external I/O: corrected chart, reviewed candidate and receipt commit or roll back together. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class SizeChartDraftApplyProcessor {
    private final SizeChartDraftRepositoryPort drafts;
    private final SizeChartTemplateRepositoryPort templates;
    private final ProductSizeChartProcessor charts;
    private final SizeChartDraftValidationProcessor validation;
    private final Clock clock;

    public SizeChartDraftApplyInfo apply(SizeChartDraftApplyCommand command, Map<Long, Product> products) {
        var corrected = validation.corrected(command.correctedChart());
        String hash = validation.applyHash(corrected, command.acknowledgedWarningIds());
        SizeChartDraft draft = get(command.draftId());
        if (draft.status() == SizeChartDraftStatus.APPLIED) {
            return replay(draft, hash);
        }
        require(draft.status() == SizeChartDraftStatus.READY, SizeChartDraftTransportErrorCode.INVALID_STATUS);
        require(draft.version() == command.expectedDraftVersion(), SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        require(!draft.isExpired(clock.instant()), SizeChartDraftTransportErrorCode.EXPIRED);
        require(command.actorId() > 0, SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        var input = draft.input();
        require(corrected.templateId() == input.templateId(), SizeChartDraftTransportErrorCode.TEMPLATE_CHANGED);
        charts.requireProduct(input.productId(), products);
        // Global write order: selected template, then chart. Draft uses optimistic CAS in this same TX.
        var template = templates.lockById(input.templateId())
            .orElseThrow(() -> new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.TEMPLATE_CHANGED));
        var columns = templates.findColumns(template.id());
        require(template.revision() == input.templateRevision()
            && columns.stream().map(SizeChartTemplateColumn::id).collect(Collectors.toSet()).equals(validation.capturedColumnIds(input.templateJson())),
            SizeChartDraftTransportErrorCode.TEMPLATE_CHANGED);
        validation.requireAcknowledgments(draft.candidate(), corrected, command.acknowledgedWarningIds());
        var chart = charts.replaceCaptured(input.productId(), corrected, template, columns, input.baseChartId(), input.baseChartRevision());
        var now = clock.instant();
        // Expiry is rechecked by the domain after lock waits; failure also rolls back the replacement.
        require(!draft.isExpired(now), SizeChartDraftTransportErrorCode.EXPIRED);
        var info = new SizeChartDraftApplyInfo(draft.id(), input.productId(), chart.id(), chart.revision(), command.actorId(), now);
        var receipt = new SizeChartDraft.ApplyReceipt(hash, validation.receipt(info), command.actorId(), now);
        drafts.update(draft.apply(validation.reviewed(corrected, input.templateJson()), receipt, now), now);
        return info;
    }

    /** Called only after the losing transaction exited, so concurrent identical applies can replay the winner. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<SizeChartDraftApplyInfo> replayApplied(SizeChartDraftApplyCommand command) {
        var draft = get(command.draftId());
        if (draft.status() != SizeChartDraftStatus.APPLIED) {
            return Optional.empty();
        }
        return Optional.of(replay(draft, validation.applyHash(validation.corrected(command.correctedChart()), command.acknowledgedWarningIds())));
    }

    private SizeChartDraftApplyInfo replay(SizeChartDraft draft, String hash) {
        require(draft.applyReceipt().applyHash().equals(hash), SizeChartDraftTransportErrorCode.APPLY_CONFLICT);
        return validation.receipt(draft.applyReceipt());
    }

    private SizeChartDraft get(long id) {
        SizeChartDraft draft = drafts.findById(id)
            .orElseThrow(() -> new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.NOT_FOUND));
        require(!draft.isExpired(clock.instant()), SizeChartDraftTransportErrorCode.EXPIRED);
        return draft;
    }

    private void require(boolean valid, SizeChartDraftTransportErrorCode code) {
        if (!valid) {
            throw new SizeChartDraftTransportException(code);
        }
    }
}
