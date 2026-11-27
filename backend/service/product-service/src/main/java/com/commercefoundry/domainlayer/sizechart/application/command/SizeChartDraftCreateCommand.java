package com.commercefoundry.domainlayer.sizechart.application.command;

import java.util.List;
import java.util.UUID;

/**
 * @param images                       IMAGE 입력에서 운영자가 확인·승인한 이미지. 순서가 생성 입력 순서다. TEXT 면 비어 있다
 * @param personalInfoRemovedConfirmed 운영자가 이미지에 고객 개인정보가 없음을 확인했는지. IMAGE 에서만 의미가 있다
 */
public record SizeChartDraftCreateCommand(
    long productId, long templateId, long actorId, UUID idempotencyKey, String mode, String sourceText,
    List<Image> images, boolean personalInfoRemovedConfirmed, TextSelection textSelection
) {
    public SizeChartDraftCreateCommand {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public SizeChartDraftCreateCommand(long productId, long templateId, long actorId, UUID idempotencyKey, String mode, String sourceText,
        List<Image> images, boolean personalInfoRemovedConfirmed) {
        this(productId, templateId, actorId, idempotencyKey, mode, sourceText, images, personalInfoRemovedConfirmed, null);
    }

    /** 검토한 본문 후보와 sourceText를 함께 대조한다. 기존 입력 해시에는 포함하지 않는다. */
    public record TextSelection(String sourceId, String bodyHash) {
    }

    /** TEXT 입력. */
    public SizeChartDraftCreateCommand(long productId, long templateId, long actorId, UUID idempotencyKey, String mode, String sourceText) {
        this(productId, templateId, actorId, idempotencyKey, mode, sourceText, List.of(), false);
    }

    /** @param approvedContentHash 운영자가 inspection 으로 받은 SHA-256(소문자 16진수) */
    public record Image(String sourceId, String approvedContentHash) {
    }

    @Override
    public String toString() {
        return "SizeChartDraftCreateCommand[redacted]";
    }
}
