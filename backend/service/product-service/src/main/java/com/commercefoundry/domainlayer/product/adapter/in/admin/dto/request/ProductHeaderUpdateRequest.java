package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Replace the editable header fields using the version returned by the admin detail endpoint. */
public record ProductHeaderUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank(message = ProductValidationMessage.PRODUCT_NAME_REQUIRED)
    @Size(max = 100, message = ProductValidationMessage.PRODUCT_NAME_SIZE_MAX)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_NAME_INVALID_CHARACTER)
    String name,
    @Size(max = 200, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    String summary,
    @Size(max = 200_000, message = ProductValidationMessage.PRODUCT_DESCRIPTION_HTML_SIZE_MAX)
    String descriptionHtml,
    @NotNull(message = ProductValidationMessage.PRODUCT_SALE_STATUS_REQUIRED) ProductSaleStatusFilter saleStatus,
    @NotNull(message = ProductValidationMessage.PRODUCT_VISIBLE_REQUIRED) Boolean visible,
    @Size(max = 100, message = ProductValidationMessage.PRODUCT_NAME_SIZE_MAX)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_NAME_INVALID_CHARACTER)
    String nameEn,
    @Size(max = 200, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    String summaryEn,
    @Size(max = 200_000, message = ProductValidationMessage.PRODUCT_DESCRIPTION_HTML_SIZE_MAX)
    String descriptionHtmlEn
) {
    public ProductHeaderUpdateRequest(Long version, String name, String summary, String descriptionHtml,
                                      ProductSaleStatusFilter saleStatus, Boolean visible) {
        this(version, name, summary, descriptionHtml, saleStatus, visible, null, null, null);
    }

    public ProductHeaderUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductHeaderUpdateCommand(productId, version, actorMemberId, name.strip(),
            summary == null || summary.isBlank() ? null : summary.strip(), descriptionHtml,
            saleStatus.toSaleStatus(), visible,
            nameEn == null ? null : nameEn.strip(),
            summaryEn == null ? null : summaryEn.strip(), descriptionHtmlEn);
    }
}
