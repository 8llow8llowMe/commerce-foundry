package com.commercefoundry.domainlayer.storeprofile.domain.model;

public record StoreProfile(
    String storeName,
    String companyName,
    String representativeName,
    String countryCode,
    String businessRegistrationNumber,
    String mailOrderRegistrationNumber,
    String address,
    String customerServiceEmail,
    String customerServicePhone,
    String customerServiceHours
) {
}
