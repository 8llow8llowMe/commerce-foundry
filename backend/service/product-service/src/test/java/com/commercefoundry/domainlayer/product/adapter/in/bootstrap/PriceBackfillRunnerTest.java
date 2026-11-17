package com.commercefoundry.domainlayer.product.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Tally;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.port.in.PriceBackfillUseCase;
import com.commercefoundry.global.properties.PriceBackfillProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class PriceBackfillRunnerTest {

    final PriceBackfillUseCase useCase = mock(PriceBackfillUseCase.class);

    @Test
    void offAndMissingModeDoNothing() {
        new PriceBackfillRunner(new PriceBackfillProperties(PriceBackfillProperties.Mode.OFF), useCase).run(new DefaultApplicationArguments());
        new PriceBackfillRunner(new PriceBackfillProperties(null), useCase).run(new DefaultApplicationArguments());

        verifyNoInteractions(useCase);
    }

    @Test
    void dryRunAndApplyPassTheModeThrough() {
        when(useCase.run(any())).thenReturn(new Report(Mode.DRY_RUN, Verdict.READY, new Tally(Map.of(), 0, 0), null));
        new PriceBackfillRunner(new PriceBackfillProperties(PriceBackfillProperties.Mode.DRY_RUN), useCase).run(new DefaultApplicationArguments());
        verify(useCase).run(Mode.DRY_RUN);

        when(useCase.run(Mode.APPLY)).thenReturn(new Report(Mode.APPLY, Verdict.BLOCKED, null, null));
        new PriceBackfillRunner(new PriceBackfillProperties(PriceBackfillProperties.Mode.APPLY), useCase).run(new DefaultApplicationArguments());
        verify(useCase).run(Mode.APPLY);
    }

    @Test
    void failureDoesNotStopStartup() {
        when(useCase.run(Mode.APPLY)).thenThrow(new PriceMigrationPreflightException(PriceMigrationPreflightException.Reason.SCAN_LIMIT_EXCEEDED));

        assertThatCode(() -> new PriceBackfillRunner(new PriceBackfillProperties(PriceBackfillProperties.Mode.APPLY), useCase)
            .run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }
}
