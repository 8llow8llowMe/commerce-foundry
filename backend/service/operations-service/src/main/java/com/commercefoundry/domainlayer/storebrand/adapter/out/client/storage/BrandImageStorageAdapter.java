package com.commercefoundry.domainlayer.storebrand.adapter.out.client.storage;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.model.BrandImageLimits;
import com.commercefoundry.domainlayer.storebrand.application.port.out.BrandImagePort;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrandImageStorageAdapter implements BrandImagePort {
    private final ObjectStorageClient storage;

    @Override
    public void put(Kind kind, UUID id, byte[] png) {
        try {
            storage.put(key(kind, id), png, BrandImageLimits.PNG_MIME, "private, no-store");
        } catch (StorageException exception) {
            throw new BrandAssetException(Code.STORAGE);
        }
    }

    @Override
    public boolean exists(Kind kind, UUID id) {
        try {
            return storage.head(key(kind, id)).filter(BrandImageStorageAdapter::valid).isPresent();
        } catch (StorageException exception) {
            throw new BrandAssetException(Code.STORAGE);
        }
    }

    @Override
    public byte[] read(Kind kind, UUID id) {
        String key = key(kind, id);
        try {
            var object = storage.head(key).orElseThrow(() -> new BrandAssetException(Code.NOT_FOUND));
            if (!valid(object)) {
                throw new BrandAssetException(Code.STORAGE);
            }
            return storage.read(key, BrandImageLimits.STORED_LIMIT, object.eTag());
        } catch (StorageException exception) {
            throw new BrandAssetException(exception.reason() == StorageException.Reason.NOT_FOUND ? Code.NOT_FOUND : Code.STORAGE);
        }
    }

    private static boolean valid(StoredObject object) {
        return BrandImageLimits.PNG_MIME.equals(object.contentType())
            && object.contentLength() > 0 && object.contentLength() <= BrandImageLimits.STORED_LIMIT;
    }

    private static String key(Kind kind, UUID id) {
        return "store-brand/" + kind.name().toLowerCase(Locale.ROOT) + "/" + id + ".png";
    }
}
