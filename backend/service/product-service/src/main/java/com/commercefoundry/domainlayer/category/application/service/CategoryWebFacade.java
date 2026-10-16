package com.commercefoundry.domainlayer.category.application.service;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.response.CategoryTreeResponse;
import com.commercefoundry.domainlayer.category.adapter.in.web.presenter.CategoryWebPresenter;
import com.commercefoundry.domainlayer.category.application.port.in.CategoryWebUseCase;
import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryWebFacade implements CategoryWebUseCase {

    private final CategoryProcessor categoryProcessor;
    private final CategoryWebPresenter categoryWebPresenter;

    @Override
    public CategoryTreeResponse getCategoryTree() {
        return categoryWebPresenter.toResponse(categoryProcessor.getVisibleTree());
    }
}
