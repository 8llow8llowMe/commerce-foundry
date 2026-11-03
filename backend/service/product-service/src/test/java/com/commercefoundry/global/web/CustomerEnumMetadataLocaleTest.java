package com.commercefoundry.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 전시 섹션 유형 · 사이즈표 단위의 고객 응답 표시 이름 (#20) — 코드는 그대로, 영어 지역 태그만 영어, 그 밖은 기존 한국어. */
class CustomerEnumMetadataLocaleTest {

    @ParameterizedTest
    @EnumSource(DisplaySectionType.class)
    @DisplayName("섹션 유형은 모든 값에 영문 이름 · 설명이 있고 코드는 같다. 인자 없는 metadata 는 한국어 그대로다")
    void sectionType(DisplaySectionType type) {
        var english = type.toMetadata(Locale.forLanguageTag("en-GB"));
        assertThat(english.code()).isEqualTo(type.name());
        assertThat(english.name()).isNotBlank().isNotEqualTo(type.getDisplayName()).matches("\\p{ASCII}+");
        assertThat(english.description()).isNotBlank().matches("\\p{ASCII}+");
        assertThat(type.toMetadata(Locale.KOREAN)).isEqualTo(type.toMetadata());
    }

    @ParameterizedTest
    @EnumSource(SizeChartUnit.class)
    @DisplayName("사이즈표 단위도 같다")
    void sizeChartUnit(SizeChartUnit unit) {
        var english = unit.toMetadata(Locale.US);
        assertThat(english.code()).isEqualTo(unit.name());
        assertThat(english.name()).isNotBlank().matches("\\p{ASCII}+");
        assertThat(unit.toMetadata(Locale.KOREAN)).isEqualTo(unit.toMetadata());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja-JP", "zh-CN", "fr"})
    @DisplayName("지원하지 않는 언어는 한국어다")
    void unsupportedFallsBackToKorean(String tag) {
        assertThat(DisplaySectionType.HERO.toMetadata(Locale.forLanguageTag(tag))).isEqualTo(DisplaySectionType.HERO.toMetadata());
        assertThat(SizeChartUnit.CM.toMetadata(Locale.forLanguageTag(tag))).isEqualTo(SizeChartUnit.CM.toMetadata());
    }
}
