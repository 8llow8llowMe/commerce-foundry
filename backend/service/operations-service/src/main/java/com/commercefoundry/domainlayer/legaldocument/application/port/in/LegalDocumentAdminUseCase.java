package com.commercefoundry.domainlayer.legaldocument.application.port.in;

import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.response.LegalDocumentAdminResponses;
import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;

public interface LegalDocumentAdminUseCase {
    List<LegalDocumentAdminResponses.Summary> list();
    LegalDocumentAdminResponses.Detail detail(String type, String locale);
    LegalDocumentAdminResponses.Detail save(long actorMemberId, LegalDocumentCommands.SaveDraft command);
    LegalDocumentAdminResponses.Detail publish(long actorMemberId, LegalDocumentCommands.Publish command);
    PageResponse<LegalDocumentAdminResponses.Revision> revisions(String type, String locale, int page, int size);
    LegalDocumentAdminResponses.Template template(String type, String locale);
}
