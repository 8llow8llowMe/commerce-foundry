package com.commercefoundry.domainlayer.legaldocument.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.legaldocument.adapter.in.web.dto.response.LegalDocumentWebResponses;
import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos;
import org.springframework.stereotype.Component;

@Component
public class LegalDocumentWebPresenter {
    public LegalDocumentWebResponses.Summary summary(LegalDocumentInfos.Published info) {
        return new LegalDocumentWebResponses.Summary(info.type(), info.locale(), info.revisionNumber(), info.title(), info.publishedAt());
    }

    public LegalDocumentWebResponses.Document document(LegalDocumentInfos.Published info) {
        return new LegalDocumentWebResponses.Document(info.type(), info.locale(), info.revisionNumber(), info.title(), info.body(), info.publishedAt());
    }
}
