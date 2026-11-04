package com.commercefoundry.domainlayer.legaldocument.application.service;

import com.commercefoundry.domainlayer.legaldocument.adapter.in.web.dto.response.LegalDocumentWebResponses;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.web.presenter.LegalDocumentWebPresenter;
import com.commercefoundry.domainlayer.legaldocument.application.port.in.LegalDocumentWebUseCase;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LegalDocumentWebFacade implements LegalDocumentWebUseCase {
    private final LegalDocumentProcessor processor;
    private final LegalDocumentWebPresenter presenter;

    @Override
    public List<LegalDocumentWebResponses.Summary> list(String locale) {
        return processor.published(locale).stream().map(presenter::summary).toList();
    }

    @Override
    public LegalDocumentWebResponses.Document current(String type, String locale) {
        return presenter.document(processor.published(type, locale, null));
    }

    @Override
    public LegalDocumentWebResponses.Document revision(String type, String locale, int revisionNumber) {
        return presenter.document(processor.published(type, locale, revisionNumber));
    }
}
