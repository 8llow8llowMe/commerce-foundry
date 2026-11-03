package com.commercefoundry.common.dto.metadata;

/**
 * 응답에 {@code {code, name, description}} 으로 내리는 enum 이 구현한다 ({@code coding-conventions.md} §11).
 */
public interface CodeNameDescribable {

    String getDisplayName();

    default String getDescription() {
        return getDisplayName();
    }

    default CodeNameDescriptionMetadata toMetadata() {
        if (!(this instanceof Enum<?> enumValue)) {
            throw new IllegalStateException("CodeNameDescribable must be implemented by enum");
        }

        return CodeNameDescriptionMetadata.of(enumValue.name(), getDisplayName(), getDescription());
    }

    /**
     * 고객 응답의 표시 언어를 고른 metadata. 기본은 언어와 무관하게 {@link #toMetadata()}(한국어) 그대로다 —
     * 번역을 가진 enum 만 재정의한다(#20). 어드민 · internal 응답은 인자 없는 {@link #toMetadata()} 를 쓴다.
     */
    default CodeNameDescriptionMetadata toMetadata(java.util.Locale locale) {
        return toMetadata();
    }
}
