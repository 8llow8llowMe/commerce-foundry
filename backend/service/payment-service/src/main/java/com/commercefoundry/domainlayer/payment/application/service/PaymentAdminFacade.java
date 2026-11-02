package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.adapter.in.admin.presenter.PaymentAdminPresenter;
import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAdminUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAdminQueryProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentAdminFacade implements PaymentAdminUseCase {
    private final PaymentAdminQueryProcessor queryProcessor;
    private final PaymentAdminPresenter presenter;

    /** 외부 I/O 없이 목록과 count 사이의 갱신을 섞지 않는 읽기 스냅숏이다. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PaymentAdminItem> search(PaymentAdminSearchCommand command) {
        return presenter.toPageResponse(queryProcessor.search(command));
    }
}
