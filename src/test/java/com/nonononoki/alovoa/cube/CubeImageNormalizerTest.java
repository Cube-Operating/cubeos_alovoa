package com.nonononoki.alovoa.cube;

import com.nonononoki.alovoa.cube.service.CubeImageNormalizer;
import com.nonononoki.alovoa.cube.web.CubeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CubeImageNormalizerTest {

    private CubeImageNormalizer normalizer() {
        CubeImageNormalizer n = new CubeImageNormalizer();
        ReflectionTestUtils.setField(n, "imageLength", 600);
        return n;
    }

    static byte[] png(int w, int h, int type) throws Exception {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int x = 0; x < w; x += 7) {
            for (int y = 0; y < h; y += 5) {
                img.setRGB(x, y, 0x80ff0000 | (x * y));
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    void normalisesPngWithAlphaToSquareJpeg() throws Exception {
        CubeImageNormalizer.Normalized n = normalizer().normalize(png(900, 500, BufferedImage.TYPE_INT_ARGB));
        assertEquals("image/jpeg", n.mime());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(n.bytes()));
        assertEquals(600, out.getWidth());
        assertEquals(600, out.getHeight());
    }

    @Test
    void acceptsJpegAndWebp() throws Exception {
        for (String name : new String[]{"img/long.jpeg", "img/wide.webp"}) {
            byte[] bytes = new ClassPathResource(name).getContentAsByteArray();
            CubeImageNormalizer.Normalized n = normalizer().normalize(bytes);
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(n.bytes()));
            assertEquals(600, out.getWidth(), name);
        }
    }

    @Test
    void rejectsNonImagesAndOversizedFiles() {
        CubeApiException e = assertThrows(CubeApiException.class,
                () -> normalizer().normalize("GIF89a not supported".getBytes()));
        assertEquals("Use a JPEG, PNG or WebP photo.", e.getMessage());
        byte[] big = new byte[(int) CubeImageNormalizer.MAX_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThrows(CubeApiException.class, () -> normalizer().normalize(big));
        // right signature, broken body
        assertThrows(CubeApiException.class, () -> normalizer().normalize(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}));
    }
}
