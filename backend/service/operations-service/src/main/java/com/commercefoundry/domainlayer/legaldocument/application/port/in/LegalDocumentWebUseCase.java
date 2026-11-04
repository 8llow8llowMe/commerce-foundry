package com.commercefoundry.domainlayer.legaldocument.application.port.in;

import com.commercefoundry.domainlayer.legaldocument.adapter.in.web.dto.response.LegalDocumentWebResponses;
import java.util.List;

public interface LegalDocumentWebUseCase {
    List<LegalDocumentWebResponses.Summary> list(String locale);
    LegalDocumentWebResponses.Document current(String type, String locale);
    LegalDocumentWebResponses.Document revision(String type, String locale, int revisionNumber);
}
