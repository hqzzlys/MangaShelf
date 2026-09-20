package com.localmanga.shelf;

final class WhiteBorderDetector {
    interface PixelReader { int pixelAt(int x, int y); }

    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private WhiteBorderDetector() {}

    static Bounds detect(int width, int height, PixelReader pixels) {
        if (width < 8 || height < 8) return full(width, height);
        if (!looksLikeWhitePaper(width, height, pixels)) return full(width, height);

        int step = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / 500_000d)));
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if (isInk(pixels.pixelAt(x, y))) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) return full(width, height);

        int paddingX = Math.max(2, width / 100);
        int paddingY = Math.max(2, height / 100);
        int left = Math.max(0, minX - paddingX);
        int top = Math.max(0, minY - paddingY);
        int right = Math.min(width, maxX + step + paddingX);
        int bottom = Math.min(height, maxY + step + paddingY);
        Bounds detected = new Bounds(left, top, right, bottom);
        boolean meaningful = detected.width() <= width * 0.96f || detected.height() <= height * 0.96f;
        return meaningful && detected.width() > 0 && detected.height() > 0 ? detected : full(width, height);
    }

    private static boolean looksLikeWhitePaper(int width, int height, PixelReader pixels) {
        int insetX = Math.max(1, width / 100);
        int insetY = Math.max(1, height / 100);
        int[] samples = {
                pixels.pixelAt(insetX, insetY),
                pixels.pixelAt(width - insetX - 1, insetY),
                pixels.pixelAt(insetX, height - insetY - 1),
                pixels.pixelAt(width - insetX - 1, height - insetY - 1)
        };
        int whiteCorners = 0;
        for (int color : samples) if (luminance(color) >= 235) whiteCorners++;
        return whiteCorners >= 3;
    }

    private static boolean isInk(int color) {
        int alpha = color >>> 24;
        return alpha >= 32 && luminance(color) < 232;
    }

    private static int luminance(int color) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private static Bounds full(int width, int height) {
        return new Bounds(0, 0, width, height);
    }
}
