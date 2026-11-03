package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapperImpl;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GuestSessionStoreAdapterTest {
    @Test
    void unrelatedOrUnclassifiedIntegrityErrorsRetainTheirOriginalMeaning() {
        var repository = mock(GuestSessionRepository.class);
        var adapter = new GuestSessionStoreAdapter(repository, new GuestSessionMapperImpl());
        var failure = new DataIntegrityViolationException("unrelated constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var session = new GuestSession(1, "A".repeat(43), Instant.parse("2026-11-10T00:00:00Z"), null, null);
        assertThatThrownBy(() -> adapter.saveAndFlush(session)).isSameAs(failure);
    }
}
