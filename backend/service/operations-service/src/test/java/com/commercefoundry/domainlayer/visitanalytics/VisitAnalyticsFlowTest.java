package com.commercefoundry.domainlayer.visitanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercefoundry.domainlayer.visitanalytics.application.port.out.VisitAnalyticsStore;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "visit-analytics.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class VisitAnalyticsFlowTest {

    private static final String EVENTS = "/api/v1/analytics/events";
    private static final String PRODUCTS = "/api/v1/admin/analytics/products";
    private static final String SUBJECT = "Abcdef123456789_-ABCDE";

    @Autowired private MockMvc mvc;
    @Autowired private VisitAnalyticsStore store;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void countsAnonymousAndMemberViewsOnceAndOffersDailyAndPeriodReports() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate yesterday = today.minusDays(1);
        long product = 912345678901234567L;
        UUID anonymous = UUID.randomUUID();
        String first = event(anonymous, product, true);
        mvc.perform(eventRequest(first).contentType(MediaType.TEXT_PLAIN)).andExpect(status().isNoContent());
        mvc.perform(eventRequest(first)).andExpect(status().isNoContent());
        mvc.perform(eventRequest(event(UUID.randomUUID(), product, true)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)))
            .andExpect(status().isNoContent());
        mvc.perform(eventRequest(event(UUID.randomUUID(), product, false))).andExpect(status().isNoContent());
        mvc.perform(eventRequest(event(UUID.randomUUID(), product, true)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.OWNER)))
            .andExpect(status().isNoContent());
        mvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).header("X-Client-Subject", SUBJECT)
            .header(HttpHeaders.USER_AGENT, "ExampleCrawler/2").content(event(UUID.randomUUID(), product, true)))
            .andExpect(status().isNoContent());
        store.recordProductView(UUID.randomUUID(), product, yesterday);
        store.recordProductView(UUID.randomUUID(), 47L, yesterday);

        mvc.perform(report("/top", yesterday, today, MemberRole.ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].productId").value(Long.toString(product)))
            .andExpect(jsonPath("$.data[0].views").value(3))
            .andExpect(jsonPath("$.data[1].views").value(1));
        mvc.perform(report("/" + product + "/daily", yesterday, today, MemberRole.OWNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].day").value(yesterday.toString()))
            .andExpect(jsonPath("$.data[0].views").value(1))
            .andExpect(jsonPath("$.data[1].views").value(2));
        assertThat(jdbc.queryForObject("select count(*) from operations.visit_event_receipt where event_id = ?", Long.class, anonymous))
            .isEqualTo(1L);
    }

    @Test
    void rejectsUntrustedOversizedAndInvalidInputsAndRestrictsReports() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        mvc.perform(post(EVENTS).content("{}")).andExpect(status().isServiceUnavailable());
        mvc.perform(eventRequest(" ".repeat(1025))).andExpect(status().is(413));
        mvc.perform(eventRequest("{}").contentType(MediaType.APPLICATION_XML)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(eventRequest("{}")).andExpect(status().isBadRequest());
        mvc.perform(eventRequest(event(UUID.randomUUID(), 54L, true) + " {}")).andExpect(status().isBadRequest());
        mvc.perform(get(PRODUCTS + "/top")).andExpect(status().isUnauthorized());
        for (MemberRole role : new MemberRole[]{MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF}) {
            mvc.perform(report("/top", today, today, role)).andExpect(status().isForbidden());
        }
        mvc.perform(report("/top", today.minusDays(31), today, MemberRole.OWNER)).andExpect(status().isBadRequest());
    }

    @Test
    void retentionPrunesReceiptsAndCountersAtSeparateCutoffs() {
        LocalDate old = LocalDate.of(2020, 1, 1);
        UUID expired = UUID.randomUUID();
        long product = 99210L;
        store.recordProductView(expired, product, old);
        store.recordProductView(UUID.randomUUID(), product, old.plusDays(1));
        store.purgeBefore(old.plusDays(2), old.plusDays(1));

        assertThat(jdbc.queryForObject("select count(*) from operations.visit_event_receipt where event_id = ?", Long.class, expired))
            .isZero();
        assertThat(store.dailyProduct(product, old, old.plusDays(1))).singleElement()
            .satisfies(row -> assertThat(row.day()).isEqualTo(old.plusDays(1)));
        jdbc.update("delete from operations.visit_product_daily where product_id = ?", product);
    }

    private static MockHttpServletRequestBuilder eventRequest(String body) {
        return post(EVENTS).contentType(MediaType.APPLICATION_JSON).header("X-Client-Subject", SUBJECT)
            .header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content(body);
    }

    private static MockHttpServletRequestBuilder report(String path, LocalDate from, LocalDate to, MemberRole role) {
        return get(PRODUCTS + path).param("from", from.toString()).param("to", to.toString())
            .header(HttpHeaders.AUTHORIZATION, bearer(role));
    }

    private static String event(UUID eventId, long productId, boolean consent) {
        return """
            {"type":"PRODUCT_VIEW","eventId":"%s","productId":"%s","consent":%s}
            """.formatted(eventId, productId, consent);
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
