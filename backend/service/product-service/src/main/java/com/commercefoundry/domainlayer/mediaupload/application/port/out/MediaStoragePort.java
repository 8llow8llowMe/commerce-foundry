package com.commercefoundry.domainlayer.mediaupload.application.port.out;

import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;

/** 업로드 서명 발급과 검증 · 공개 복사. 종류(이미지 · 영상)별 형식 · 크기 · 시그니처 규칙은 키의 형식({@link MediaUploadKey#kind()})이 정한다. */
public interface MediaStoragePort {
    MediaUploadQueryResult.Ticket issue(long actorId, MediaUploadKey key, long contentLength);
    MediaUploadQueryResult.Confirmed confirm(long actorId, MediaUploadKey key);
}
