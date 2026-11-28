package com.commercefoundry.domainlayer.mediaupload.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaUploadKeyTest {
    @ParameterizedTest
    @ValueSource(strings = {"../a.jpg", "uploads/a.png", "A1111111-1111-1111-1111-111111111111.jpg",
        "1-1-1-1-1.jpg", "11111111-1111-1111-1111-111111111111.svg", "https://cdn.example/a.jpg",
        "11111111-1111-1111-1111-111111111111.mp4"})
    void imageRejectsNonCanonicalAndVideoBasenames(String value) {
        assertThatThrownBy(() -> MediaUploadKey.parse(MediaKind.IMAGE, value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../a.mp4", "uploads/a.mp4", "A1111111-1111-1111-1111-111111111111.mp4", "11111111-1111-1111-1111-111111111111.MP4",
        "11111111-1111-1111-1111-111111111111.webm", "11111111-1111-1111-1111-111111111111.mov", "11111111-1111-1111-1111-111111111111.png",
        "11111111-1111-1111-1111-111111111111.mp4/", "https://cdn.example/a.mp4"})
    void videoRejectsNonCanonicalAndImageBasenames(String value) {
        assertThatThrownBy(() -> MediaUploadKey.parse(MediaKind.VIDEO, value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalKeyAndMime() {
        var key = MediaUploadKey.parse(MediaKind.IMAGE, "11111111-1111-1111-1111-111111111111.webp");
        assertThat(key.sourceKey(7)).isEqualTo("uploads/display-media/7/" + key.value());
        assertThat(key.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(key.toString()).doesNotContain(key.value());
        assertThat(MediaFormat.ofContentType(MediaKind.IMAGE, " IMAGE/WEBP ")).contains(MediaFormat.WEBP);
        assertThat(MediaFormat.ofContentType(MediaKind.IMAGE, "image/svg+xml")).isEmpty();
        assertThat(MediaFormat.ofContentType(MediaKind.IMAGE, "image/jpeg; charset=utf8")).isEmpty();
    }

    @Test
    void videoKeyUsesSameOwnerNamespaceWithMp4Extension() {
        var key = MediaUploadKey.parse(MediaKind.VIDEO, "11111111-1111-1111-1111-111111111111.mp4");
        assertThat(key.format()).isEqualTo(MediaFormat.MP4);
        assertThat(key.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(key.sourceKey(7)).isEqualTo("uploads/display-media/7/11111111-1111-1111-1111-111111111111.mp4");
        assertThat(key.toString()).doesNotContain(key.value());
        assertThatThrownBy(() -> key.sourceKey(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentTypeIsLookedUpOnlyInsideKind() {
        assertThat(MediaFormat.ofContentType(MediaKind.VIDEO, " VIDEO/MP4 ")).contains(MediaFormat.MP4);
        assertThat(MediaFormat.ofContentType(MediaKind.IMAGE, "video/mp4")).isEmpty();
        assertThat(MediaFormat.ofContentType(MediaKind.VIDEO, "image/png")).isEmpty();
        assertThat(MediaFormat.ofContentType(MediaKind.VIDEO, "video/webm")).isEmpty();
        assertThat(MediaFormat.ofContentType(MediaKind.VIDEO, "video/mp4; codecs=avc1")).isEmpty();
        assertThat(MediaFormat.ofContentType(MediaKind.VIDEO, null)).isEmpty();
    }

    @Test
    void sizeLimitsPerKind() {
        assertThat(MediaKind.IMAGE.allowsSize(10L * 1024 * 1024)).isTrue();
        assertThat(MediaKind.IMAGE.allowsSize(10L * 1024 * 1024 + 1)).isFalse();
        assertThat(MediaKind.VIDEO.allowsSize(1)).isTrue();
        assertThat(MediaKind.VIDEO.allowsSize(100L * 1024 * 1024)).isTrue();
        assertThat(MediaKind.VIDEO.allowsSize(100L * 1024 * 1024 + 1)).isFalse();
        assertThat(MediaKind.VIDEO.allowsSize(0)).isFalse();
    }
}
