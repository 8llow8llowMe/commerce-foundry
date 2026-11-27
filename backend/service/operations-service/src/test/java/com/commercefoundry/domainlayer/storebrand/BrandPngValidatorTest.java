package com.commercefoundry.domainlayer.storebrand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.service.processor.BrandPngValidator;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BrandPngValidatorTest {
    private final BrandPngValidator validator = new BrandPngValidator();

    @Test
    void retainsVisiblePixelsAndDropsTrailingBytes() throws Exception {
        var icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        icon.setRGB(0, 0, 0x00123456);
        icon.setRGB(10, 10, 0x80556677);
        byte[] png = encode(icon);
        byte[] extended = Arrays.copyOf(png, png.length + 64);
        assertThat(validator.normalize(Kind.ICON, "image/png", extended)).isEqualTo(validator.normalize(Kind.ICON, "image/png", png));
        var decoded = ImageIO.read(new ByteArrayInputStream(validator.normalize(Kind.ICON, "image/png", extended)));
        assertThat(decoded.getRGB(0, 0)).isZero();
        assertThat(decoded.getRGB(10, 10)).isEqualTo(0x80556677);
    }

    @Test
    void rejectsBadDimensionsMissingTransparencyAndTruncatedOrCorruptedData() throws Exception {
        var rectangle = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        rectangle.setRGB(0, 0, 0xFF335577);
        byte[] logo = encode(rectangle);
        assertThat(validator.normalize(Kind.DARK_LOGO, "image/png", logo)).isNotEmpty();
        reject(Kind.ICON, logo);
        reject(Kind.DARK_LOGO, Arrays.copyOf(logo, logo.length - 12));

        var opaque = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        reject(Kind.ICON, encode(opaque));
        var empty = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        reject(Kind.ICON, encode(empty));
        empty.setRGB(0, 0, 0xFF112233);
        byte[] corrupted = encode(empty);
        corrupted[29] ^= 1;
        reject(Kind.ICON, corrupted);
    }

    private void reject(Kind kind, byte[] bytes) {
        assertThatThrownBy(() -> validator.normalize(kind, "image/png", bytes))
            .isInstanceOfSatisfying(BrandAssetException.class, error -> assertThat(error.getCode()).isEqualTo(Code.IMAGE));
    }

    private static byte[] encode(BufferedImage image) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }
}
