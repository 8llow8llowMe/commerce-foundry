package com.commercefoundry.domainlayer.storebrand.adapter.in.admin.support;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.model.BrandImageLimits;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

public final class BrandUploadBody {
    private BrandUploadBody() {}

    public static byte[] read(HttpServletRequest request) {
        if (!BrandImageLimits.PNG_MIME.equalsIgnoreCase(request.getContentType())) {
            throw new BrandAssetException(Code.MIME);
        }
        if (request.getContentLengthLong() > BrandImageLimits.UPLOAD_LIMIT) {
            throw new BrandAssetException(Code.SIZE);
        }
        try (var input = request.getInputStream()) {
            return input.readNBytes(BrandImageLimits.UPLOAD_LIMIT + 1);
        } catch (IOException exception) {
            throw new BrandAssetException(Code.INVALID_REQUEST);
        }
    }
}
