package com.commercefoundry.domainlayer.storeprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.StoreProfileRepositoryAdapter;
import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository.StoreProfileRepository;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileErrorCode;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileException;
import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:store-profile-persistence;MODE=PostgreSQL;"
    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations")
@Import({OperationsServiceFeaturesConfig.class, StoreProfileRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoreProfilePersistenceTest {
    @Autowired
    StoreProfileRepositoryAdapter adapter;
    @Autowired
    StoreProfileRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void roundTripAndVersionGuards() {
        assertThat(adapter.find()).isEmpty();
        assertConflict(() -> adapter.replace(0L, profile("Example")));
        var first = adapter.replace(null, profile("Example"));
        assertThat(first.version()).isZero();
        assertThat(adapter.replace(first.version(), profile("Example")).version()).isEqualTo(first.version());
        assertThat(adapter.find().orElseThrow().profile()).isEqualTo(profile("Example"));
        assertConflict(() -> adapter.replace(null, profile("Other")));
        var updated = adapter.replace(first.version(), profile("Other"));
        assertThat(updated.version()).isEqualTo(1L);
        assertConflict(() -> adapter.replace(first.version(), profile("Stale")));
        assertThat(adapter.find().orElseThrow().profile().storeName()).isEqualTo("Other");
        assertThat(repository.count()).isEqualTo(1);
    }

    static StoreProfile profile(String name) {
        return new StoreProfile(name, "Example Company", "Example Representative", "US", "EXAMPLE-REG-A1", null,
            "Example public address", "help@example.invalid", null, null);
    }

    static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreProfileException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(StoreProfileErrorCode.VERSION_CONFLICT));
    }
}
