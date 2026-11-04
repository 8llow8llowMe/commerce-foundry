package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentApprovalSummaryH2Test extends PaymentApprovalSummaryQueryContract {
}
