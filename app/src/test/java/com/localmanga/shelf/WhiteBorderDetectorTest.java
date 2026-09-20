package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WhiteBorderDetectorTest {
    @Test public void cropsWhiteMarginsAroundDarkContent() {
        int width = 100;
        int height = 140;
        int[] pixels = filled(width * height, 0xFFFFFFFF);
        for (int y = 20; y < 120; y++) {
            for (int x = 15; x < 85; x++) pixels[y * width + x] = 0xFF202020;
        }

        WhiteBorderDetector.Bounds bounds = WhiteBorderDetector.detect(
                width, height, (x, y) -> pixels[y * width + x]);

        assertEquals(13, bounds.left);
        assertEquals(18, bounds.top);
        assertEquals(87, bounds.right);
        assertEquals(122, bounds.bottom);
    }

    @Test public void keepsDarkPagesUncropped() {
        int width = 80;
        int height = 120;
        int[] pixels = filled(width * height, 0xFF101010);

        WhiteBorderDetector.Bounds bounds = WhiteBorderDetector.detect(
                width, height, (x, y) -> pixels[y * width + x]);

        assertEquals(0, bounds.left);
        assertEquals(0, bounds.top);
        assertEquals(width, bounds.right);
        assertEquals(height, bounds.bottom);
    }

    private static int[] filled(int count, int color) {
        int[] pixels = new int[count];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }
}
