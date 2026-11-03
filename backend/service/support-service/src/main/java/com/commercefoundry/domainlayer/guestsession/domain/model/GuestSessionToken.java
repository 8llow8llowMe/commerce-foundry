package com.commercefoundry.domainlayer.guestsession.domain.model;

import java.util.Base64;

/** The credential wire format, also shared by SHA-256 fingerprints. */
public final class GuestSessionToken {
    private GuestSessionToken() {
    }

    public static boolean isCanonical(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        byte[] decoded = Base64.getUrlDecoder().decode(token);
        return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token);
    }
}
