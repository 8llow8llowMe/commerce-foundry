package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.PresignedUpload;
import com.commercefoundry.storage.client.StoredObject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전시 영상 업로드 (#796) — 실제 signed JWT · H2 로 발급 → 확정 뒤, 이미지 업로드로 올린 대표 사진과 함께 전시 VIDEO 를 저장하고 고객 조회에 같은 URL 이 나오는지 본다.
 * 컨텍스트 구성(속성 · 프로필 · {@code ObjectStorageClient} 목 필드 이름)은 {@code MediaImageUploadFlowIntegrationTest} 와 같게 두어 캐시를 같이 쓴다.
 */
@SpringBootTest(properties = "store.media.public-base-url=https://cdn.example.com/media/")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class MediaVideoUploadFlowIntegrationTest {
    private static final String VIDEO_PATH = "/api/v1/admin/displays/video-uploads";
    private static final String IMAGE_PATH = "/api/v1/admin/displays/image-uploads";
    private static final String VIDEO_ETAG = "\"video-version\"";
    private static final byte[] FTYP = {0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
        'i', 's', 'o', 'm', 'i', 's', 'o', '2', 'a', 'v', 'c', '1', 'm', 'p', '4', '1'};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvcTester mvc;
    @MockitoBean private ObjectStorageClient storage;

    @BeforeEach
    void storage() {
        when(storage.presignPut(anyString(), anyString(), anyLong())).thenAnswer(call -> new PresignedUpload(
            "https://storage.example.com/private?signature=secret", "PUT", Map.of("Content-Type", call.getArgument(1)), Instant.now().plusSeconds(300)));
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(8, "image/png", "\"opaque-version\"")));
        when(storage.head(endsWith(".mp4"))).thenReturn(Optional.of(new StoredObject(32, "video/mp4", VIDEO_ETAG)));
        when(storage.readRange(anyString(), eq(12), eq("\"opaque-version\""))).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        when(storage.readRange(anyString(), eq(128), eq(VIDEO_ETAG))).thenReturn(FTYP);
        when(storage.publicUrlOf(anyString())).thenAnswer(call -> "https://cdn.example.com/media/" + call.getArgument(0));
    }

    @Test
    void validationSeparatesVideoFromImageBeforeStorage() {
        assertThat(mvc.post().uri(VIDEO_PATH).contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(post(VIDEO_PATH, "{\"contentType\":\"video/mp4\",\"contentLength\":1}", MemberRole.CUSTOMER, 7)).hasStatus(HttpStatus.FORBIDDEN);
        assertCode(post(VIDEO_PATH, "{\"contentLength\":1}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_107");
        assertCode(post(VIDEO_PATH, "{\"contentType\":\"image/png\",\"contentLength\":1}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_108");
        assertCode(post(VIDEO_PATH, "{\"contentType\":\"video/webm\",\"contentLength\":1}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_108");
        assertCode(post(VIDEO_PATH, "{\"contentType\":\"video/mp4\"}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_103");
        assertCode(post(VIDEO_PATH, "{\"contentType\":\"video/mp4\",\"contentLength\":0}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_104");
        assertCode(post(VIDEO_PATH, "{\"contentType\":\"video/mp4\",\"contentLength\":104857601}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_109");
        assertCode(post(VIDEO_PATH + "/11111111-1111-1111-1111-111111111111.png/confirm", null, MemberRole.ADMIN, 7), "MEDIA_UPLOAD_110");
        assertCode(post(IMAGE_PATH + "/11111111-1111-1111-1111-111111111111.mp4/confirm", null, MemberRole.ADMIN, 7), "MEDIA_UPLOAD_106");
        assertCode(post(IMAGE_PATH, "{\"contentType\":\"video/mp4\",\"contentLength\":1}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_102");
        assertCode(post(IMAGE_PATH, "{\"contentType\":\"image/png\",\"contentLength\":10485761}", MemberRole.ADMIN, 7), "MEDIA_UPLOAD_105");
        verifyNoInteractions(storage);

        var max = post(VIDEO_PATH, "{\"contentType\":\" VIDEO/MP4 \",\"contentLength\":104857600}", MemberRole.ADMIN, 7);
        assertThat(max).hasStatus(HttpStatus.CREATED);
        String key = body(max).at("/dataBody/uploadKey").asString();
        assertThat(key).matches("[0-9a-f-]{36}\\.mp4");
        verify(storage).presignPut("uploads/display-media/7/" + key, "video/mp4", 104857600L);
    }

    @Test
    void confirmedVideoAndImagePosterPersistThroughRealDisplayVideo() {
        String videoKey = issue(VIDEO_PATH, "video/mp4", 32);
        var video = post(VIDEO_PATH + "/" + videoKey + "/confirm", null, MemberRole.ADMIN, 7);
        assertThat(video).hasStatusOk();
        assertThat(video.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String videoUrl = body(video).at("/dataBody/publicUrl").asString();
        assertThat(videoUrl).startsWith("https://cdn.example.com/media/products/media/7/" + videoKey.replace(".mp4", "") + "/").endsWith(".mp4");
        assertThat(body(video).at("/dataBody/contentType").asString()).isEqualTo("video/mp4");
        assertThat(body(video).at("/dataBody/contentLength").asLong()).isEqualTo(32);

        String posterKey = issue(IMAGE_PATH, "image/png", 8);
        var poster = post(IMAGE_PATH + "/" + posterKey + "/confirm", null, MemberRole.ADMIN, 7);
        assertThat(poster).hasStatusOk();
        String posterUrl = body(poster).at("/dataBody/publicUrl").asString();

        var section = post("/api/v1/admin/displays/sections", """
            {"sectionType":"HERO","visible":true,"items":[{"itemType":"MEDIA","media":{"mediaType":"VIDEO",
            "pcUrl":"%s","mobileUrl":"%s","pcPosterUrl":"%s","mobilePosterUrl":"%s"}}]}
            """.formatted(videoUrl, videoUrl, posterUrl, posterUrl), MemberRole.ADMIN, 7);
        assertThat(section).hasStatus(HttpStatus.CREATED);
        JsonNode media = body(mvc.get().uri("/api/v1/displays/main").exchange()).at("/dataBody/sections/0/items/0/media");
        assertThat(media.at("/mediaType").asString()).isEqualTo("VIDEO");
        assertThat(media.at("/pcUrl").asString()).isEqualTo(videoUrl);
        assertThat(media.at("/mobileUrl").asString()).isEqualTo(videoUrl);
        assertThat(media.at("/pcPosterUrl").asString()).isEqualTo(posterUrl);
        assertThat(media.at("/mobilePosterUrl").asString()).isEqualTo(posterUrl);

        verify(storage).readRange("uploads/display-media/7/" + videoKey, 128, VIDEO_ETAG);
        verify(storage).copy(eq("uploads/display-media/7/" + videoKey), startsWith("products/media/7/"),
            eq("video/mp4"), eq("public, max-age=31536000, immutable"), eq(VIDEO_ETAG));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void otherAdminNamespaceOversizedAndNonMp4AreRejectedOnConfirm() {
        String key = issue(VIDEO_PATH, "video/mp4", 32);
        when(storage.head("uploads/display-media/8/" + key)).thenReturn(Optional.empty());
        var other = post(VIDEO_PATH + "/" + key + "/confirm", null, MemberRole.ADMIN, 8);
        assertThat(other).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(body(other).at("/dataHeader/resultCode").asString()).isEqualTo("MEDIA_UPLOAD_008");

        when(storage.head("uploads/display-media/7/" + key)).thenReturn(Optional.of(new StoredObject(104857601, "video/mp4", VIDEO_ETAG)));
        assertCode(post(VIDEO_PATH + "/" + key + "/confirm", null, MemberRole.ADMIN, 7), "MEDIA_UPLOAD_010");

        when(storage.head("uploads/display-media/7/" + key)).thenReturn(Optional.of(new StoredObject(32, "video/mp4", VIDEO_ETAG)));
        when(storage.readRange("uploads/display-media/7/" + key, 128, VIDEO_ETAG)).thenReturn(new byte[] {'<', 's', 'v', 'g', '>'});
        assertCode(post(VIDEO_PATH + "/" + key + "/confirm", null, MemberRole.ADMIN, 7), "MEDIA_UPLOAD_009");
        verify(storage, never()).copy(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private String issue(String path, String contentType, long length) {
        var issued = post(path, "{\"contentType\":\"%s\",\"contentLength\":%d}".formatted(contentType, length), MemberRole.ADMIN, 7);
        assertThat(issued).hasStatus(HttpStatus.CREATED);
        assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return body(issued).at("/dataBody/uploadKey").asString();
    }

    private static void assertCode(MvcTestResult result, String code) {
        assertThat(result.getResponse().getStatus()).as(code).isEqualTo(400);
        assertThat(body(result).at("/dataHeader/resultCode").asString()).isEqualTo(code);
    }

    private MvcTestResult post(String path, String content, MemberRole role, long actor) {
        var request = mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION,
            "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(actor, role).tokenValue());
        if (content != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return request.exchange();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
