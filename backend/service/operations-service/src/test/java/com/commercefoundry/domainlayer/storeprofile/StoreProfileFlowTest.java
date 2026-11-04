package com.commercefoundry.domainlayer.storeprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository.StoreProfileRepository;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.audit.AdminAuditLog;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StoreProfileFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/profile";
    private static final String PUBLIC = "/api/v1/store/profile";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired
    private StoreProfileRepository repository;

    @BeforeEach
    void clearProfile() {
        repository.deleteAll();
    }

    @Test
    void actualJwtPermissionsAndUnconfiguredEnvelope() throws Exception {
        assertThat(perform(get(ADMIN)).getStatus()).isEqualTo(401);
        assertThat(perform(get(ADMIN).header("Authorization", bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(403);
        assertThat(perform(put(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body(null))).getStatus()).isEqualTo(401);
        assertThat(perform(put(ADMIN).header("Authorization", bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content(body(null))).getStatus()).isEqualTo(403);
        var anonymous = perform(get(PUBLIC));
        assertThat(anonymous.getStatus()).isEqualTo(404);
        assertThat(anonymous.getContentAsString()).contains("STORE_PROFILE_001");
        assertThat(perform(get(ADMIN).header("Authorization", bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(404);
    }

    @Test
    void savePublishesOnlyAllowListedFieldsAndStaleEditorsConflict() throws Exception {
        var created = save(body(null));
        assertThat(created.getStatus()).isEqualTo(200);
        assertThat(created.getHeader("Cache-Control")).contains("no-store");
        JsonNode admin = JSON.readTree(created.getContentAsString()).get("dataBody");
        assertThat(admin.get("version").longValue()).isZero();
        var publicResponse = perform(get(PUBLIC));
        assertThat(publicResponse.getHeader("Cache-Control")).contains("no-store");
        JsonNode published = JSON.readTree(publicResponse.getContentAsString()).get("dataBody");
        assertThat(published.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "storeName", "companyName", "representativeName", "countryCode", "businessRegistrationNumber",
            "mailOrderRegistrationNumber", "address", "customerServiceEmail", "customerServicePhone", "customerServiceHours");
        assertThat(published.get("storeName").stringValue()).isEqualTo("Example Store");
        assertThat(save(body(null)).getStatus()).isEqualTo(409);
        var updated = save(body(0L).replace("Example Store", "Another Store"));
        assertThat(updated.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(updated.getContentAsString()).get("dataBody").get("version").longValue()).isEqualTo(1);
        assertThat(save(body(0L)).getStatus()).isEqualTo(409);
        assertThat(perform(get(PUBLIC)).getContentAsString()).contains("Another Store");
    }

    @Test
    void rejectsLengthsMissingFieldsInvalidCountryEmailAndVersion() throws Exception {
        for (String invalid : new String[]{"{}", body(-1L), body(null).replace("Example Store", "x".repeat(101)),
            body(null).replace("US", "ZZ"), body(null).replace("help@example.invalid", "invalid-email"), body(null).replace("Example Company", "a\\u0000b"),
            body(null).replace("Example Company", "a\\nb"), "{"}) {
            var response = save(invalid);
            assertThat(response.getStatus()).as(invalid).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("STORE_PROFILE_1");
        }
        assertThat(repository.count()).isZero();
        assertThat(save(body(0L)).getStatus()).isEqualTo(409);
    }

    private MockHttpServletResponse save(String body) throws Exception {
        return perform(put(ADMIN).header("Authorization", bearer(MemberRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void auditContainsActualJwtActorFixedTargetAndOutcomeWithoutBody() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(save(body(null).replace("US", "ZZ")).getStatus()).isEqualTo(400);
            assertThat(save(body(null).replace("Example Store", "x".repeat(101))).getStatus()).isEqualTo(400);
            assertThat(appender.list).isEmpty();
            assertThat(save(body(null)).getStatus()).isEqualTo(200);
            assertThat(save(body(null)).getStatus()).isEqualTo(409);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=STORE_PROFILE_UPDATE actorMemberId=" + TestTokens.MEMBER_ID + " target=store-profile:1 result=SUCCESS",
                "action=STORE_PROFILE_UPDATE actorMemberId=" + TestTokens.MEMBER_ID + " target=store-profile:1 result=FAILURE");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static String body(Long version) {
        return """
            {"expectedVersion":%s,"storeName":"Example Store","companyName":"Example Company",
            "representativeName":"Example Representative","countryCode":"US","businessRegistrationNumber":"EXAMPLE-REG-A1",
            "mailOrderRegistrationNumber":null,"address":"Example public address","customerServiceEmail":"help@example.invalid",
            "customerServicePhone":null,"customerServiceHours":null}
            """.formatted(version);
    }

}
