package com.commercefoundry.domainlayer.storebrand.application.service.processor;

import static com.commercefoundry.domainlayer.storebrand.application.model.BrandImageLimits.PNG_MIME;
import static com.commercefoundry.domainlayer.storebrand.application.model.BrandImageLimits.UPLOAD_LIMIT;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class BrandPngValidator {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    public byte[] normalize(Kind kind, String contentType, byte[] input) {
        if (!PNG_MIME.equalsIgnoreCase(contentType)) {
            throw new BrandAssetException(Code.MIME);
        }
        if (input.length > UPLOAD_LIMIT) {
            throw new BrandAssetException(Code.SIZE);
        }
        if (input.length < SIGNATURE.length || Arrays.mismatch(input, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length) != -1) {
            throw new BrandAssetException(Code.IMAGE);
        }

        var reader = ImageIO.getImageReadersByFormatName("PNG").next();
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(pixelsOnly(input)))) {
            reader.setInput(stream, true, true);
            validateDimensions(kind, reader.getWidth(0), reader.getHeight(0));
            BufferedImage decoded = reader.read(0);
            return encode(cleanPixels(decoded));
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new BrandAssetException(Code.IMAGE);
        } finally {
            reader.dispose();
        }
    }

    private static byte[] pixelsOnly(byte[] input) {
        var chunks = ByteBuffer.wrap(input);
        chunks.position(SIGNATURE.length);
        var filtered = new ByteArrayOutputStream(input.length);
        filtered.writeBytes(SIGNATURE);
        boolean first = true;
        while (chunks.remaining() >= 12) {
            int start = chunks.position();
            int length = chunks.getInt();
            int type = chunks.getInt();
            if (length < 0 || length > chunks.remaining() - Integer.BYTES
                || (first && (type != 0x49484452 || length != 13))) { // IHDR
                throw new BrandAssetException(Code.IMAGE);
            }
            first = false;
            chunks.position(chunks.position() + length);
            var checksum = new CRC32();
            checksum.update(input, start + Integer.BYTES, Integer.BYTES + length);
            if ((int) checksum.getValue() != chunks.getInt()) {
                throw new BrandAssetException(Code.IMAGE);
            }
            // 디코딩 전에 메타데이터와 애니메이션 확장을 제거한다. tRNS는 팔레트 투명도에 필요하다.
            switch (type) {
                case 0x49484452, 0x504C5445, 0x74524E53, 0x49444154 -> // IHDR, PLTE, tRNS, IDAT
                    filtered.write(input, start, length + 12);
                case 0x49454E44 -> { // IEND
                    if (length != 0) {
                        throw new BrandAssetException(Code.IMAGE);
                    }
                    filtered.write(input, start, 12);
                    return filtered.toByteArray();
                }
                default -> {
                    if ((type & 0x20000000) == 0) { // 알 수 없는 필수 청크
                        throw new BrandAssetException(Code.IMAGE);
                    }
                }
            }
        }
        throw new BrandAssetException(Code.IMAGE);
    }

    private static void validateDimensions(Kind kind, int width, int height) {
        boolean valid = kind == Kind.ICON
            ? width == height && width >= 32 && width <= 512
            : width >= 16 && width <= 1024 && height >= 16 && height <= 512;
        if (!valid) {
            throw new BrandAssetException(Code.IMAGE);
        }
    }

    private static BufferedImage cleanPixels(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean transparent = false;
        boolean visible = false;
        var normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                if ((row[x] >>> 24) == 0) {
                    transparent = true;
                    row[x] = 0;
                } else {
                    visible = true;
                }
            }
            normalized.setRGB(0, y, width, 1, row, 0, width);
        }
        if (!transparent || !visible) {
            throw new BrandAssetException(Code.IMAGE);
        }
        return normalized;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "PNG", output);
        }
        return bytes.toByteArray();
    }
}
