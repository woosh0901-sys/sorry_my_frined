package game;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** 클래스패스 이미지 로딩을 담당합니다. */
final class ImageAssets {
    private ImageAssets() { }

    static BufferedImage load(String name) {
        String path = "/images/" + name + ".png";
        try (InputStream stream = ImageAssets.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing resource: " + path);
            BufferedImage image = ImageIO.read(stream);
            if (image == null) throw new IllegalStateException("Invalid PNG: " + path);
            return image;
        } catch (IOException ex) { throw new IllegalStateException("Cannot read " + path, ex); }
    }

}
