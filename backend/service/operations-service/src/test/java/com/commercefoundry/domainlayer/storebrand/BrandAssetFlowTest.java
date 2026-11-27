package com.commercefoundry.domainlayer.storebrand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:store-brand-598;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrandAssetFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/brand-assets";
    private static final String PUBLIC = "/api/v1/store/brand-assets";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @MockitoBean ObjectStorageClient storage;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("delete from operations.store_brand_revision");
        var objects = new HashMap<String, byte[]>();
        doAnswer(call -> {
            String key = call.getArgument(0);
            assertThat(key).startsWith("store-brand/");
            objects.put(key, call.getArgument(1));
            return null;
        }).when(storage).put(anyString(), any(byte[].class), anyString(), anyString());
        when(storage.head(anyString())).thenAnswer(call -> Optional.ofNullable(objects.get(call.getArgument(0)))
            .map(bytes -> new StoredObject(bytes.length, "image/png", "test-etag")));
        when(storage.read(anyString(), anyInt(), anyString())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(2)).isEqualTo("test-etag");
            return objects.get(call.getArgument(0));
        });
    }

    @Test
    void uploadDraftPublishRemoveAndRestoreAreIsolatedAndVersioned() throws Exception {
        JsonNode empty = data(call(get(PUBLIC), null), 200);
        assertThat(empty.get("version").asString()).isEqualTo("0");
        assertThat(empty.get("logo").isNull()).isTrue();

        JsonNode upload = data(call(post(ADMIN + "/uploads/LOGO").contentType(MediaType.IMAGE_PNG).content(png()), MemberRole.ADMIN), 200);
        String id = upload.get("assetId").asString();
        String preview = upload.get("previewUrl").asString();
        assertThat(call(get(preview), null).getStatus()).isEqualTo(401);
        assertThat(call(get(preview), MemberRole.OWNER).getContentType()).isEqualTo("image/png");

        String firstImage = PUBLIC + "/images/2/LOGO/" + id;
        error(call(get(firstImage), null), 404, "STORE_BRAND_001");
        JsonNode draft = data(save(0, "{\"logo\":{\"assetId\":\"%s\",\"altText\":\" Example \"}}".formatted(id)), 200);
        assertThat(draft.get("draft").get("logo").get("altText").asString()).isEqualTo("Example");
        assertThat(data(call(get(PUBLIC), null), 200).get("logo").isNull()).isTrue();

        JsonNode published = data(publish(1, ""), 200);
        assertThat(published.get("version").asString()).isEqualTo("2");
        assertThat(published.get("publication").get("actorMemberId").asString()).isEqualTo(Long.toString(TestTokens.MEMBER_ID));
        var current = call(get(PUBLIC), MemberRole.CUSTOMER);
        assertThat(current.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(data(current, 200).get("logo").get("url").asString()).isEqualTo(firstImage);
        var image = call(get(firstImage), null);
        assertThat(image.getStatus()).isEqualTo(200);
        assertThat(image.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.getHeader("Cache-Control")).contains("max-age=300", "public");

        error(save(1, "{}"), 409, "STORE_BRAND_020");
        data(save(2, "{}"), 200);
        assertThat(data(call(get(PUBLIC), null), 200).get("logo").get("url").asString()).isEqualTo(firstImage);
        data(publish(3, ""), 200);
        assertThat(data(call(get(PUBLIC), null), 200).get("logo").isNull()).isTrue();
        error(call(get(firstImage).accept(MediaType.IMAGE_PNG), null), 404, "STORE_BRAND_001");
        error(publish(4, ",\"restoreVersion\":\"3\""), 404, "STORE_BRAND_001");

        JsonNode restored = data(publish(4, ",\"restoreVersion\":\"2\""), 200);
        assertThat(restored.get("draft").get("logo").isNull()).isTrue();
        assertThat(restored.get("publication").get("restoredFromVersion").asString()).isEqualTo("2");
        assertThat(data(call(get(PUBLIC), null), 200).get("logo").get("url").asString()).contains("/images/5/");
        JsonNode history = data(call(get(ADMIN + "/publications?size=2"), MemberRole.ADMIN), 200);
        assertThat(history.get("totalElements").asLong()).isEqualTo(3);
        assertThat(history.get("contents")).extracting(node -> node.get("version").asString()).containsExactly("5", "4");
    }

    @Test
    void settingsPermissionsAndInvalidUploadsOrReferencesNeverChangeVersion() throws Exception {
        assertThat(call(get(ADMIN), null).getStatus()).isEqualTo(401);
        for (MemberRole role : new MemberRole[]{MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF}) {
            assertThat(call(get(ADMIN), role).getStatus()).isEqualTo(403);
            assertThat(call(post(ADMIN + "/uploads/ICON").contentType(MediaType.IMAGE_PNG).content(png()), role).getStatus()).isEqualTo(403);
            assertThat(call(put(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"assets\":{}}"), role).getStatus()).isEqualTo(403);
            assertThat(call(post(ADMIN + "/publication").contentType(MediaType.APPLICATION_JSON).content("{}"), role).getStatus()).isEqualTo(403);
        }
        error(call(post(ADMIN + "/uploads/ICON").contentType("image/svg+xml").content("<svg/>"), MemberRole.OWNER), 415, "STORE_BRAND_105");
        error(call(post(ADMIN + "/uploads/ICON").contentType(MediaType.IMAGE_PNG).content("<svg/>"), MemberRole.OWNER), 422, "STORE_BRAND_106");
        error(call(post(ADMIN + "/uploads/ICON").contentType(MediaType.IMAGE_PNG).content(new byte[1024 * 1024 + 1]),
            MemberRole.ADMIN), 413, "STORE_BRAND_104");
        error(call(get(ADMIN + "/publications?page=2147483647&size=20"), MemberRole.ADMIN), 400, "STORE_BRAND_100");
        error(save(0, "{\"icon\":{\"assetId\":\"%s\",\"altText\":\"\"}}".formatted(UUID.randomUUID())), 400, "STORE_BRAND_103");
        assertThat(data(call(get(ADMIN), MemberRole.ADMIN), 200).get("version").asString()).isEqualTo("0");
    }

    @Test
    void rejectsWrongAssetKindAndPreservesPublicationWhenStorageIsUnavailable() throws Exception {
        var uploaded = data(call(post(ADMIN + "/uploads/ICON").contentType(MediaType.IMAGE_PNG).content(png()), MemberRole.ADMIN), 200);
        String id = uploaded.get("assetId").asString();
        error(save(0, "{\"logo\":{\"assetId\":\"%s\",\"altText\":\"Example\"}}".formatted(id)), 400, "STORE_BRAND_103");

        data(save(0, "{\"icon\":{\"assetId\":\"%s\",\"altText\":\"\"}}".formatted(id)), 200);
        when(storage.head("store-brand/icon/" + id + ".png")).thenThrow(new StorageException(StorageException.Reason.DISABLED, StorageException.Operation.HEAD));
        error(publish(1, ""), 503, "STORE_BRAND_030");
        assertThat(data(call(get(PUBLIC), null), 200).get("icon").isNull()).isTrue();
        assertThat(data(call(get(ADMIN), MemberRole.ADMIN), 200).get("version").asString()).isEqualTo("1");
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(10, 10, 0xFF000000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private MockHttpServletResponse save(long version, String assets) throws Exception {
        return call(put(ADMIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":\"%d\",\"assets\":%s}".formatted(version, assets)), MemberRole.ADMIN);
    }

    private MockHttpServletResponse publish(long version, String extra) throws Exception {
        return call(post(ADMIN + "/publication").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":\"%d\"%s}".formatted(version, extra)), MemberRole.ADMIN);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder builder, MemberRole role) throws Exception {
        if (role != null) {
            builder.header("Authorization", bearer(role));
        }
        return perform(builder);
    }

    private static JsonNode data(MockHttpServletResponse response, int expectedStatus) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return JSON.readTree(response.getContentAsString()).get("dataBody");
    }

    private static void error(MockHttpServletResponse response, int expectedStatus, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        assertThat(JSON.readTree(response.getContentAsString()).get("dataHeader").get("resultCode").asString()).isEqualTo(code);
    }
}
