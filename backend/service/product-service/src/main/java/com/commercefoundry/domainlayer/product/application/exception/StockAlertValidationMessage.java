package com.commercefoundry.domainlayer.product.application.exception;

public final class StockAlertValidationMessage {

    public static final String THRESHOLD_REQUIRED = "STOCK_ALERT_101:thresholdQuantity는 필수입니다.";
    public static final String THRESHOLD_NON_NEGATIVE = "STOCK_ALERT_102:thresholdQuantity는 0 이상이어야 합니다.";
    public static final String SKU_ID_POSITIVE = "STOCK_ALERT_103:skuId는 양수여야 합니다.";
    public static final String PAGE_NON_NEGATIVE = "STOCK_ALERT_104:page는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "STOCK_ALERT_105:size는 1~100이어야 합니다.";

    private StockAlertValidationMessage() {
    }
}
