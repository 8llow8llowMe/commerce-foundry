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
}
