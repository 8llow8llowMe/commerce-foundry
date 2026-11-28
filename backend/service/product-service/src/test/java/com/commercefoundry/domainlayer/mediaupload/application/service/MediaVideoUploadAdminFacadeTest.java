package com.commercefoundry.domainlayer.mediaupload.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter.MediaVideoUploadAdminPresenter;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaStoragePort;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.application.service.processor.MediaUploadProcessor;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaVideoUploadAdminFacadeTest {
    private final MediaStoragePort storage = mock(MediaStoragePort.class);
    private final ProductAdminAuditProcessor audit = mock(ProductAdminAuditProcessor.class);
    private final MediaVideoUploadAdminFacade facade = new MediaVideoUploadAdminFacade(
        new MediaUploadProcessor(storage), new MediaVideoUploadAdminPresenter(), audit);

    @Test
    void issuesNormalizedMp4UpTo100MiBAndAuditsOnlyUuid() {
        when(storage.issue(eq(7L), any(), eq(MediaKind.VIDEO_MAX_BYTES))).thenAnswer(call -> {
            MediaUploadKey key = call.getArgument(1);
            assertThat(key.format()).isEqualTo(MediaFormat.MP4);
            return new MediaUploadQueryResult.Ticket(key.value(), "secret-url", "PUT", Map.of("signed", "secret"), Instant.now());
        });
        var result = facade.issue(7, " VIDEO/MP4 ", MediaKind.VIDEO_MAX_BYTES);
        assertThat(result.uploadKey()).endsWith(".mp4");
        verify(audit).mediaVideoUploadIssued(7, MediaUploadKey.parse(MediaKind.VIDEO, result.uploadKey()).id(), true);
        verify(audit, never()).mediaImageUploadIssued(anyLong(), any(), anyBoolean());
        assertThat(result.toString()).doesNotContain(result.uploadKey(), "secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/webm", "video/quicktime", "image/png", "video/mp4; codecs=avc1", ""})
    void rejectsNonMp4BeforeStorage(String contentType) {
        assertThatThrownBy(() -> facade.issue(7, contentType, 1)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.VIDEO_FORMAT));
        verify(audit).mediaVideoUploadIssued(7, null, false);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsSizeAndInvalidKeysBeforeStorage() {
        assertThatThrownBy(() -> facade.issue(7, "video/mp4", 0)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.VIDEO_SIZE));
        assertThatThrownBy(() -> facade.issue(7, "video/mp4", MediaKind.VIDEO_MAX_BYTES + 1)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.VIDEO_SIZE));
        assertThatThrownBy(() -> facade.confirm(7, "../a.mp4")).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.INVALID_KEY));
        assertThatThrownBy(() -> facade.confirm(7, UUID.randomUUID() + ".png")).as("영상 API 는 이미지 키를 받지 않는다")
            .isInstanceOfSatisfying(MediaUploadException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.INVALID_KEY));
        verify(audit, times(2)).mediaVideoUploadIssued(7, null, false);
        verify(audit, times(2)).mediaVideoUploadConfirmed(7, null, false);
        verifyNoInteractions(storage);
    }

    @Test
    void confirmsAndAuditsSuccessAndFailure() {
        String key = UUID.randomUUID() + ".mp4";
        when(storage.confirm(eq(7L), any())).thenReturn(new MediaUploadQueryResult.Confirmed("secret-url", "video/mp4", 1024));
        var confirmed = facade.confirm(7, key);
        assertThat(confirmed.contentType()).isEqualTo("video/mp4");
        assertThat(confirmed.toString()).doesNotContain("secret");
        verify(audit).mediaVideoUploadConfirmed(7, MediaUploadKey.parse(MediaKind.VIDEO, key).id(), true);
        when(storage.confirm(eq(7L), any())).thenThrow(new MediaUploadException(MediaUploadErrorCode.VIDEO_FORMAT));
        assertThatThrownBy(() -> facade.confirm(7, key)).isInstanceOf(MediaUploadException.class);
        verify(audit).mediaVideoUploadConfirmed(7, MediaUploadKey.parse(MediaKind.VIDEO, key).id(), false);
    }
}
