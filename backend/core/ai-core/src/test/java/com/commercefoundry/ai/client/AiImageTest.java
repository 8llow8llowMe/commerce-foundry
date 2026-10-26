package com.commercefoundry.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiImageTest {

    @Test
    @DisplayName("허용 형식만 받고, 바이트는 복사한다. 빈 값 · 상한 초과 · 다른 형식은 만들지 못한다")
    void acceptsSupportedTypesAndCopiesBytes() {
        byte[] source = new byte[] {1, 2, 3};
        AiImage image = new AiImage(AiImage.WEBP, source);
        source[0] = 9;

        assertThat(image.mediaType()).isEqualTo(AiImage.WEBP);
        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.toString()).doesNotContain("1, 2").contains("bytes=3");

        assertThatThrownBy(() -> new AiImage("image/bmp", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiImage(AiImage.JPEG, new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiImage(AiImage.PNG, new byte[AiImage.MAX_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
    }
}
