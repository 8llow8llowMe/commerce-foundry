package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class StorePropertiesTest {

    @Test
    @DisplayName("store.* 가 하나도 없으면 기능 플래그는 모두 꺼진 기본값이다 — NPE 없이 닫힌 쪽")
    void absentMeansDisabled() {
        StoreProperties properties = bind(Map.of());

        assertThat(properties.features()).isEqualTo(StoreProperties.Features.DISABLED);
        assertThat(properties.features().customOrder()).isFalse();
        assertThat(properties.features().preorder()).isFalse();
        assertThat(properties.features().productVideo()).isFalse();
    }

    @Test
    @DisplayName("features 를 null 로 만들어도 꺼진 기본값이다")
    void nullFeaturesMeansDisabled() {
        StoreProperties.Features features = new StoreProperties(null).features();

        assertThat(features.customOrder()).isFalse();
        assertThat(features.preorder()).isFalse();
        assertThat(features.productVideo()).isFalse();
    }

    @Test
    @DisplayName("store.features.custom-order=true 면 켜진다")
    void enabled() {
        assertThat(bind(Map.of("store.features.custom-order", "true")).features().customOrder()).isTrue();
    }

    @Test
    @DisplayName("store.features.preorder · product-video 는 각자 켜진다 — 다른 플래그는 꺼진 채다")
    void eachFlagIsIndependent() {
        StoreProperties.Features preorderOnly = bind(Map.of("store.features.preorder", "true")).features();
        StoreProperties.Features videoOnly = bind(Map.of("store.features.product-video", "true")).features();

        assertThat(preorderOnly.preorder()).isTrue();
        assertThat(preorderOnly.productVideo()).isFalse();
        assertThat(preorderOnly.customOrder()).isFalse();
        assertThat(videoOnly.productVideo()).isTrue();
        assertThat(videoOnly.preorder()).isFalse();
        assertThat(videoOnly.customOrder()).isFalse();
    }

    /** {@code .env.example} 이 "비우면 꺼짐" 이라고 안내한다 — 빈 환경변수가 플레이스홀더 기본값 대신 빈 문자열로 풀려도 기동하고 꺼진다. */
    @ParameterizedTest
    @ValueSource(strings = {"custom-order", "preorder", "product-video"})
    @DisplayName("값이 빈 문자열이면 기동을 막지 않고 꺼진다")
    void blankMeansDisabled(String flag) {
        assertThat(bind(Map.of("store.features." + flag, "")).features()).isEqualTo(StoreProperties.Features.DISABLED);
    }

    @Test
    @DisplayName("Features 를 null 로 만들어도 꺼짐으로 정규화한다")
    void nullFlagMeansDisabled() {
        assertThat(new StoreProperties.Features(null, null, null)).isEqualTo(StoreProperties.Features.DISABLED);
    }

    private static StoreProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("store", StoreProperties.class);
    }
}
