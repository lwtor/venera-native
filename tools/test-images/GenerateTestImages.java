/*
 * S0-06 fixture generator for the large-image validation slice.
 *
 * This is a standalone JDK developer tool, NOT part of the Android build. It produces
 * deterministic test images with no third-party content, so the repository never has to carry
 * copyrighted comic pages. Run it with the JDK 17 that the Gradle build already requires:
 *
 *   java -Xmx2g tools/test-images/GenerateTestImages.java <outputDir>
 *
 * The output is consumed by :feature:reader as raw assets and is deliberately git-ignored.
 */
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public final class GenerateTestImages {

    private static final int GRID_STEP = 120;
    private static final int CHECKER_STEP = 8;
    private static final int RULER_BAND_STEP = 600;
    private static final int RULER_BAND_HEIGHT = 20;
    private static final float JPEG_QUALITY = 0.92f;

    private record Fixture(String name, int width, int height, String format) {
        String fileName() {
            return name + "." + format;
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("page_normal_1080x1440", 1080, 1440, "png"),
            new Fixture("page_normal_1080x1440", 1080, 1440, "jpg"),
            new Fixture("page_wide_1920x1080", 1920, 1080, "png"),
            new Fixture("page_long_1080x6000", 1080, 6000, "png"),
            new Fixture("page_ultralong_1080x16000", 1080, 16000, "png"),
            new Fixture("page_hires_3000x4000", 3000, 4000, "png")
    );

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "tools/test-images/out");
        Files.createDirectories(outputDir);

        System.out.println("| fixture | 像素 | 格式 | 文件字节 |");
        System.out.println("| --- | --- | --- | --- |");
        for (Fixture fixture : FIXTURES) {
            Path file = outputDir.resolve(fixture.fileName());
            write(fixture, file);
            System.out.printf(
                    "| %s | %d x %d | %s | %d |%n",
                    fixture.fileName(),
                    fixture.width(),
                    fixture.height(),
                    fixture.format(),
                    Files.size(file)
            );
        }
        System.out.println();
        System.out.println("output: " + outputDir.toAbsolutePath());
    }

    private static void write(Fixture fixture, Path file) throws IOException {
        BufferedImage image = new BufferedImage(fixture.width(), fixture.height(), BufferedImage.TYPE_INT_RGB);
        int[] row = new int[fixture.width()];
        for (int y = 0; y < fixture.height(); y++) {
            fillRow(row, fixture.width(), y, fixture.height());
            image.setRGB(0, y, fixture.width(), 1, row, 0, 0);
        }
        if ("jpg".equals(fixture.format())) {
            writeJpeg(image, file);
        } else {
            ImageIO.write(image, "png", file.toFile());
        }
    }

    /**
     * Deterministic pattern: vertical colour gradient, grid lines, a checkerboard and a tick ruler
     * every 600 px. The ruler and checkerboard exist so that blur from heavy down-sampling is
     * visible on screen instead of only measurable in numbers.
     */
    private static void fillRow(int[] row, int width, int y, int height) {
        float t = height > 1 ? (float) y / (height - 1) : 0f;
        int baseR = 40 + (int) (180 * t);
        int baseG = 60 + (int) (120 * (1f - Math.abs(0.5f - t) * 2f));
        int baseB = 200 - (int) (150 * t);

        boolean gridLine = y % GRID_STEP < 2;
        boolean rulerBand = y % RULER_BAND_STEP < RULER_BAND_HEIGHT;
        boolean rulerTick = rulerBand && (y % 4 < 2);

        for (int x = 0; x < width; x++) {
            int r = baseR;
            int g = baseG;
            int b = baseB;

            if (gridLine || x % GRID_STEP < 2) {
                r = 250;
                g = 250;
                b = 250;
            } else if (rulerTick) {
                boolean dark = (x / 4) % 2 == 0;
                r = g = b = dark ? 0 : 255;
            } else if (((y / CHECKER_STEP) + (x / CHECKER_STEP)) % 2 == 0) {
                r = clamp(r + 24);
                g = clamp(g + 24);
                b = clamp(b + 24);
            }
            row[x] = (r << 16) | (g << 8) | b;
        }
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }

    private static void writeJpeg(BufferedImage image, Path file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private GenerateTestImages() {
    }
}
