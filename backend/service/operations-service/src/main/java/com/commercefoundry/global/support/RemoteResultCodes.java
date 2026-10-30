package com.commercefoundry.global.support;

import java.util.regex.Pattern;

/** 원격 응답 코드가 로그에 안전하게 남길 수 있는 형식인지 판정한다. */
public final class RemoteResultCodes {
    private static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_LENGTH = 40;

    private RemoteResultCodes() {
    }

    public static String loggable(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_LENGTH && SHAPE.matcher(resultCode).matches()
            ? resultCode : "<unrecognized>";
    }
}
