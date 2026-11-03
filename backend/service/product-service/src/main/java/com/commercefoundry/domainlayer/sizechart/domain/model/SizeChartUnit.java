package com.commercefoundry.domainlayer.sizechart.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 사이즈표 치수의 저장 · 표시 단위. 저장은 템플릿의 기본 단위 그대로이고, 다른 단위로 바꾸는 것은 응답에서만 한다 ({@link SizeChartUnits}).
 */
@Getter
@RequiredArgsConstructor
public enum SizeChartUnit implements CodeNameDescribable {

    CM("센티미터", "치수를 센티미터로 둡니다."),
    INCH("인치", "치수를 인치로 둡니다.");

    private final String displayName;
    private final String description;

    /** 고객 사이즈표 응답의 표시 이름 (#20). 영어 지역 태그면 영어, 그 밖은 한국어다. */
    public CodeNameDescriptionMetadata toMetadata(Locale locale) {
        String[] en = switch (this) {
            case CM -> new String[] {"Centimeters", "Measurements are shown in centimeters."};
            case INCH -> new String[] {"Inches", "Measurements are shown in inches."};
        };
        // 상품 문구(ProductText)와 같은 규칙 — 영어 지역 태그만 영어. 다른 컨텍스트 도메인을 부르지 않게 여기서 판정한다(H8)
        boolean english = locale != null && "en".equals(locale.getLanguage());
        return english ? CodeNameDescriptionMetadata.of(name(), en[0], en[1]) : toMetadata();
    }
}
