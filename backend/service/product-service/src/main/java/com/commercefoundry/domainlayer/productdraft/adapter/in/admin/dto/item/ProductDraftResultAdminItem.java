package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 정제된 AI 제안. 운영자가 입력한 사실은 초안 응답의 facts 에 있다. */
@Schema(description = "검토할 AI 상품 문구")
public record ProductDraftResultAdminItem(Suggestion suggestion, List<CodeNameDescriptionMetadata> unconfirmedFacts, List<Warning> warnings) {

    public record Suggestion(String name, String description, List<String> searchTags, String seoTitle, String seoDescription, List<ImageAlt> imageAlts) {
        @Override
        public String toString() {
            return "Suggestion[redacted]";
        }
    }

    public record ImageAlt(int index, String altText) {
        @Override
        public String toString() {
            return "ImageAlt[redacted]";
        }
    }

    public record Warning(String code, String name, String description, int index) {
    }
}
