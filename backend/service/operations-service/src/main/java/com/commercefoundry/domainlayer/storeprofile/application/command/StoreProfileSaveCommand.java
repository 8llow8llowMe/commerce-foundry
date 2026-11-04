package com.commercefoundry.domainlayer.storeprofile.application.command;

import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;

public record StoreProfileSaveCommand(Long expectedVersion, StoreProfile profile) {
}
