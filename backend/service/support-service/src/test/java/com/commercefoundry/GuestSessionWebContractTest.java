package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response.GuestSessionWebResponses;
import com.commercefoundry.domainlayer.guestsession.adapter.in.web.presenter.GuestSessionWebPresenter;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GuestSessionWebContractTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    void issuedResponseCarriesOnlyTokenAndExpiryWithSwaggerSchemas() throws Exception {
        assertThat(Arrays.stream(GuestSessionWebResponses.Issued.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("token", "expiresAt");
        for (RecordComponent component : GuestSessionWebResponses.Issued.class.getRecordComponents()) {
            assertThat(component.getAccessor().getAnnotation(Schema.class)).as(component.getName()).isNotNull();
        }
        assertThat(GuestSessionWebResponses.Issued.class.getDeclaredMethod("expiresAt").getReturnType()).isEqualTo(Instant.class);
        assertThat(GuestSessionWebResponses.Issued.class.getDeclaredMethod("token").getReturnType()).isEqualTo(String.class);
    }

    @Test
    void presenterDropsSessionIdAndToStringNeverShowsToken() {
        Instant expiresAt = Instant.parse("2026-12-04T09:30:00.123456Z");
        var issued = new GuestSessionWebPresenter().issued(new GuestSessionIssuedInfo(4611686018427387904L, TOKEN, expiresAt));

        assertThat(issued.token()).isEqualTo(TOKEN);
        assertThat(issued.expiresAt()).isEqualTo(expiresAt);
        assertThat(issued.toString()).doesNotContain(TOKEN).doesNotContain("4611686018427387904").contains("REDACTED");
    }
}
