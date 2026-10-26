package com.commercefoundry.domainlayer.sizechart.domain.model;

import java.math.BigDecimal;

/**
 * 사이즈표 입력 상한. 요청 검증·도메인 판정·DB check 가 같은 값을 쓴다.
 */
public final class SizeChartLimits {

    public static final int MAX_TEMPLATE_NAME_CODE_POINTS = 50;
    public static final int MAX_COLUMN_NAME_CODE_POINTS = 30;
    public static final int MAX_DESCRIPTION_CODE_POINTS = 200;
    public static final int MAX_SIZE_LABEL_CODE_POINTS = 20;
    public static final int MAX_NOTE_CODE_POINTS = 500;
    public static final int MIN_COLUMNS = 1;
    public static final int MAX_COLUMNS = 20;
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 30;
    public static final BigDecimal MAX_MEASUREMENT = new BigDecimal("9999.99");
    public static final int MEASUREMENT_SCALE = 2;

    private SizeChartLimits() {
    }
}
