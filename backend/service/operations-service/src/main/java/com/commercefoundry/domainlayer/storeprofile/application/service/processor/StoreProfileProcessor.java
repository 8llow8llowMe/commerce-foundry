package com.commercefoundry.domainlayer.storeprofile.application.service.processor;

import com.commercefoundry.domainlayer.storeprofile.application.command.StoreProfileSaveCommand;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileErrorCode;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileException;
import com.commercefoundry.domainlayer.storeprofile.application.info.StoreProfileInfo;
import com.commercefoundry.domainlayer.storeprofile.application.port.out.StoreProfilePort;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreProfileProcessor {

    private static final Set<String> COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private final StoreProfilePort port;

    public StoreProfileInfo get() {
        var result = port.find().orElseThrow(() -> new StoreProfileException(StoreProfileErrorCode.NOT_CONFIGURED));
        return new StoreProfileInfo(result.profile(), result.version());
    }

    public void validate(StoreProfileSaveCommand command) {
        if (!COUNTRY_CODES.contains(command.profile().countryCode())) {
            throw new StoreProfileException(StoreProfileErrorCode.INVALID_REQUEST);
        }
    }

    public StoreProfileInfo save(StoreProfileSaveCommand command) {
        var result = port.replace(command.expectedVersion(), command.profile());
        return new StoreProfileInfo(result.profile(), result.version());
    }
}
