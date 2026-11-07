package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationInternalUseCase;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.guestsession.application.port.in.GuestSessionInternalUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationWebTest {
    @Autowired MockMvc mvc;
    @Autowired ConversationInternalUseCase conversations;
    @Autowired GuestSessionInternalUseCase guests;

    @Test
    void customerBearerWinsAndInvalidBearerNeverFallsBack() throws Exception {
        var guest = guests.issue();
        long own = conversations.createForMember(TestTokens.MEMBER_ID, ConversationLocale.KO).id();
        long guestConversation = conversations.createForGuest(guest.token(), ConversationLocale.KO).id();
        mvc.perform(get(path(own)).header("Authorization", bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER))
                .header("X-Support-Guest-Token", "invalid"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.dataBody.id").value(String.valueOf(own)));
        mvc.perform(get(path(guestConversation)).header("Authorization", bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER))
                .header("X-Support-Guest-Token", guest.token()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_010"));
        for (String token : new String[]{"Bearer broken", "Bearer " + TestTokens.issuer(Instant.now().minusSeconds(7200))
            .issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue()}) {
            mvc.perform(get(path(guestConversation)).header("Authorization", token).header("X-Support-Guest-Token", guest.token()))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (MemberRole role : MemberRole.OPERATOR_ROLES) {
            mvc.perform(get(path(guestConversation)).header("Authorization", bearer(TestTokens.MEMBER_ID, role))
                    .header("X-Support-Guest-Token", guest.token())).andExpect(status().isForbidden());
        }
    }

    @Test
    void guestAuthorizationAndOwnershipAreRequiredForEveryOperation() throws Exception {
        var guest = guests.issue();
        long id = conversations.createForGuest(guest.token(), ConversationLocale.EN).id();
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.dataHeader.resultCode").value("GUEST_SESSION_001"));
        mvc.perform(get(path(id)).header("X-Support-Guest-Token", "invalid")).andExpect(status().isUnauthorized());
        String otherGuest = guests.issue().token();
        mvc.perform(get(path(id)).header("X-Support-Guest-Token", otherGuest)).andExpect(status().isNotFound());
        mvc.perform(get(path(id) + "/messages?cursor=-1&size=0").header("X-Support-Guest-Token", otherGuest))
            .andExpect(status().isNotFound());
        mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", otherGuest)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hello\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(get(path(id)).header("X-Support-Guest-Token", guest.token())).andExpect(status().isOk());
        guests.revoke(guest.id());
        mvc.perform(get(path(id)).header("X-Support-Guest-Token", guest.token())).andExpect(status().isUnauthorized());
    }

    @Test
    void appendReplayAndCursorUseTheExistingPersistenceContract() throws Exception {
        long id = conversations.createForMember(TestTokens.MEMBER_ID, ConversationLocale.KO).id();
        String auth = bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER);
        String key = UUID.randomUUID().toString();
        String response = mvc.perform(post(path(id) + "/messages").header("Authorization", auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hello 010-1234-5678\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.seq").value(1))
            .andExpect(jsonPath("$.dataBody.content").value("hello [PHONE]"))
            .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post(path(id) + "/messages").header("Authorization", auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hello 010-1234-5678\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(response);
        mvc.perform(post(path(id) + "/messages").header("Authorization", auth).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"second\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.seq").value(2));
        mvc.perform(get(path(id) + "/messages").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.messages.length()").value(2))
            .andExpect(jsonPath("$.dataBody.nextCursor").value(2)).andExpect(jsonPath("$.dataBody.hasNext").value(false));
        mvc.perform(get(path(id) + "/messages?size=1").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.nextCursor").value(1)).andExpect(jsonPath("$.dataBody.hasNext").value(true));
        mvc.perform(get(path(id) + "/messages?cursor=1&size=1").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.messages[0].seq").value(2));
        mvc.perform(get(path(id) + "/messages?cursor=3").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get(path(id) + "/messages?size=101").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(post(path(id) + "/messages").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hello\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_112"));
        String other = bearer(TestTokens.MEMBER_ID + 1, MemberRole.CUSTOMER);
        mvc.perform(get(path(id)).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(post(path(id) + "/messages").header("Authorization", other)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"other member\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_010"));
        mvc.perform(get(path(1)).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test
    void guestAppendReplayCursorAndContentErrorsUseTheSameWebContract() throws Exception {
        var guest = guests.issue();
        long id = conversations.createForGuest(guest.token(), ConversationLocale.KO).id();
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", guest.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"guest message\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", guest.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"guest message\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mvc.perform(get(path(id) + "/messages?cursor=1").header("X-Support-Guest-Token", guest.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.messages.length()").value(0))
            .andExpect(jsonPath("$.dataBody.nextCursor").value(1));
        mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", guest.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"different\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_014"));
        for (String content : new String[]{"", "x".repeat(2001), "\u0000"}) {
            mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", guest.token())
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"" + content.replace("\u0000", "\\u0000") + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_113"));
        }
        mvc.perform(post(path(id) + "/messages").header("X-Support-Guest-Token", guest.token())
                .header("Idempotency-Key", key.toUpperCase(java.util.Locale.ROOT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"guest message\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_112"));
    }

    private static String path(long id) { return "/api/v1/support/conversations/" + id; }
    private static String bearer(long id, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(id, role).tokenValue();
    }
}
