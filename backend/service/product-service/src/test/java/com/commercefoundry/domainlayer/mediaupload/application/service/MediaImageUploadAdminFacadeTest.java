package com.commercefoundry.domainlayer.mediaupload.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter.MediaImageUploadAdminPresenter;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaStoragePort;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.application.service.processor.MediaUploadProcessor;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaImageUploadAdminFacadeTest {
    private final MediaStoragePort storage = mock(MediaStoragePort.class);
    private final ProductAdminAuditProcessor audit = mock(ProductAdminAuditProcessor.class);
    private final MediaImageUploadAdminFacade facade = new MediaImageUploadAdminFacade(
        new MediaUploadProcessor(storage), new MediaImageUploadAdminPresenter(), audit);

    @Test
    void canonicalIssueIsPresentedAndAudited() {
        when(storage.issue(eq(7L), any(), eq(1L))).thenAnswer(call -> {
            MediaUploadKey key = call.getArgument(1);
            assertThat(key.format().getContentType()).isEqualTo("image/jpeg");
            return new MediaUploadQueryResult.Ticket(key.value(), "secret-url", "PUT", Map.of("signed", "secret"), Instant.now());
        });
        var result = facade.issue(7, " IMAGE/JPEG ", 1);
        verify(audit).mediaImageUploadIssued(7, MediaUploadKey.parse(MediaKind.IMAGE, result.uploadKey()).id(), true);
        assertThat(result.toString()).doesNotContain(result.uploadKey(), "secret");
    }

    @Test
    void rejectedIssueAndInvalidConfirmHaveNoTargetAndNoStorageIo() {
        assertThatThrownBy(() -> facade.issue(7, "image/gif", 1)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.FORMAT));
        assertThatThrownBy(() -> facade.issue(7, "image/png", 10485761)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.SIZE));
        assertThatThrownBy(() -> facade.issue(7, "video/mp4", 1)).as("이미지 API 는 영상 형식을 받지 않는다")
            .isInstanceOfSatisfying(MediaUploadException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.FORMAT));
        assertThatThrownBy(() -> facade.confirm(7, "../a.png")).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.INVALID_KEY));
        assertThatThrownBy(() -> facade.confirm(7, UUID.randomUUID() + ".mp4")).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.INVALID_KEY));
        verify(audit, times(3)).mediaImageUploadIssued(7, null, false);
        verify(audit, times(2)).mediaImageUploadConfirmed(7, null, false);
        verifyNoInteractions(storage);
    }

    @Test
    void confirmsAndAuditsSuccessAndFailureWithOnlyUuid() {
        String key = UUID.randomUUID() + ".png";
        when(storage.confirm(eq(7L), any())).thenReturn(new MediaUploadQueryResult.Confirmed("secret-url", "image/png", 8));
        assertThat(facade.confirm(7, key).contentLength()).isEqualTo(8);
        verify(audit).mediaImageUploadConfirmed(7, MediaUploadKey.parse(MediaKind.IMAGE, key).id(), true);
        when(storage.confirm(eq(7L), any())).thenThrow(new MediaUploadException(MediaUploadErrorCode.CHANGED));
        assertThatThrownBy(() -> facade.confirm(7, key)).isInstanceOf(MediaUploadException.class);
        verify(audit).mediaImageUploadConfirmed(7, MediaUploadKey.parse(MediaKind.IMAGE, key).id(), false);
    }
}
