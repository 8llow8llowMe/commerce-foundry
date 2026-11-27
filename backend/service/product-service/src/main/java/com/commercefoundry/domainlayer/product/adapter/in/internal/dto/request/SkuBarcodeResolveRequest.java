package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.product.adapter.in.support.SkuBarcodeDeserializer;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.commercefoundry.domainlayer.product.domain.model.SkuBarcode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SkuBarcodeResolveRequest(
    @NotEmpty(message = SkuAdminValidationMessage.BARCODE_LIST)
    @Size(max = 100, message = SkuAdminValidationMessage.BARCODE_LIST)
    @JsonDeserialize(contentUsing = SkuBarcodeDeserializer.class)
    List<@NotNull(message = SkuAdminValidationMessage.BARCODE) @Pattern(regexp = SkuBarcode.FORMAT, message = SkuAdminValidationMessage.BARCODE) String> barcodes
) {
}
