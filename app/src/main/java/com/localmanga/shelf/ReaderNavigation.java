package com.localmanga.shelf;

final class ReaderNavigation {
    private ReaderNavigation() {}

    static int deltaForTap(float horizontalFraction, boolean rightToLeft) {
        if (horizontalFraction < 0.30f) return rightToLeft ? 1 : -1;
        if (horizontalFraction > 0.70f) return rightToLeft ? -1 : 1;
        return 0;
    }

    static int deltaForSwipe(float horizontalDistance, boolean rightToLeft) {
        if (horizontalDistance == 0f) return 0;
        int leftToRightDelta = horizontalDistance < 0f ? 1 : -1;
        return rightToLeft ? -leftToRightDelta : leftToRightDelta;
    }

    static int clampPage(int requested, int pageCount) {
        if (pageCount <= 0) return 0;
        return Math.max(0, Math.min(requested, pageCount - 1));
    }
}
