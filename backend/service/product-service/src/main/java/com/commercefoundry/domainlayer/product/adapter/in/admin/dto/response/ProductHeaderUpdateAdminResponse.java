package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

/** Saved admin snapshot and whether HTML sanitization removed content. */
public record ProductHeaderUpdateAdminResponse(ProductDetailAdminResponse product, boolean descriptionHtmlSanitized) {
}
