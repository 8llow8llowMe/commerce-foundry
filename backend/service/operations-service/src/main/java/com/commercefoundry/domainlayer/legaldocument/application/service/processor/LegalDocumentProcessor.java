package com.commercefoundry.domainlayer.legaldocument.application.service.processor;

import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentErrorCode;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentException;
import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos;
import com.commercefoundry.domainlayer.legaldocument.application.port.out.LegalDocumentRepositoryPort;
import com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LegalDocumentProcessor {
    private final LegalDocumentRepositoryPort port;
    private final SnowflakeIdGenerator idGenerator;
    private final Clock clock;

    public List<LegalDocument> list() {
        return port.list();
    }

    public LegalDocumentInfos.Detail detail(String type, String locale) {
        return info(port.detail(key(type, locale)).orElseThrow(() -> new LegalDocumentException(LegalDocumentErrorCode.NOT_FOUND)));
    }

    public PageResponse<LegalDocumentRevision> revisions(String type, String locale, int page, int size) {
        if (page < 0 || size < 1 || size > 20) {
            throw new LegalDocumentException(LegalDocumentErrorCode.INVALID_REQUEST);
        }
        return PageResponse.of(port.revisions(key(type, locale), PageRequest.of(page, size, Sort.by("revisionNumber").descending())));
    }

    public List<LegalDocumentInfos.Published> published(String locale) {
        key("TERMS", locale);
        return port.published(locale).stream().map(this::info).toList();
    }

    public LegalDocumentInfos.Published published(String type, String locale, Integer number) {
        if (number != null && number < 1) {
            throw new LegalDocumentException(LegalDocumentErrorCode.INVALID_REQUEST);
        }
        return info(port.published(key(type, locale), number).orElseThrow(() -> new LegalDocumentException(LegalDocumentErrorCode.NOT_FOUND)));
    }

    public LegalDocumentInfos.Detail save(long actorMemberId, LegalDocumentCommands.SaveDraft command) {
        return info(port.saveDraft(key(command.type(), command.locale()), command.expectedVersion(), command.title(), command.body(),
            actorMemberId, idGenerator.generateId(), idGenerator.generateId(), clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }

    public LegalDocumentInfos.Detail publish(long actorMemberId, LegalDocumentCommands.Publish command) {
        return info(port.publish(key(command.type(), command.locale()), command.expectedVersion(), command.revisionNumber(),
            command.reviewConfirmed(), actorMemberId, clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }

    public LegalDocumentKey key(String type, String locale) {
        try {
            return new LegalDocumentKey(LegalDocumentKey.Type.valueOf(type), locale);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new LegalDocumentException(LegalDocumentErrorCode.INVALID_REQUEST);
        }
    }

    private LegalDocumentInfos.Detail info(LegalDocumentQueryResults.Detail result) {
        return new LegalDocumentInfos.Detail(result.document(), result.revision());
    }

    private LegalDocumentInfos.Published info(LegalDocumentQueryResults.Published result) {
        return new LegalDocumentInfos.Published(result.type(), result.locale(), result.revisionNumber(), result.title(), result.body(), result.publishedAt());
    }
}
