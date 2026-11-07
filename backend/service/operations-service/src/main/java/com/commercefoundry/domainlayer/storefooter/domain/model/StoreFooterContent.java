package com.commercefoundry.domainlayer.storefooter.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record StoreFooterContent(Text copyrightText, List<Link> links) {
    public StoreFooterContent {
        links = links == null ? null : Collections.unmodifiableList(new ArrayList<>(links));
    }

    public record Text(String ko, String en) {
        public String localized(String locale) {
            return "ko".equals(locale) ? ko : en;
        }
    }

    public enum Type { INSTAGRAM, KAKAO_CHANNEL, CUSTOM }

    public record Link(String type, Text labels, String url, Integer displayOrder, Boolean visible) {
    }
}
