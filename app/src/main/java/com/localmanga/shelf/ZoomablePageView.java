package com.localmanga.shelf;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

@SuppressLint("ViewConstructor")
final class ZoomablePageView extends ImageView {
    private static final float MAX_ZOOM = 5f;
    private final Matrix pageMatrix = new Matrix();
    private float baseScale = 1f;
    private float zoom = 1f;
    private float translationX;
    private float translationY;
    private boolean cropMode;

    ZoomablePageView(Context context) {
        super(context);
        super.setScaleType(ScaleType.MATRIX);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        post(this::resetZoom);
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetZoom);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::resetZoom);
    }

    boolean isCropMode() {
        return cropMode;
    }

    void setCropMode(boolean cropMode) {
        this.cropMode = cropMode;
        resetZoom();
    }

    boolean isZoomed() {
        return zoom > 1.01f;
    }

    void toggleZoom(float focusX, float focusY) {
        if (isZoomed()) resetZoom();
        else zoomBy(2.5f, focusX, focusY);
    }

    void zoomBy(float factor, float focusX, float focusY) {
        if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
        float nextZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float appliedFactor = nextZoom / zoom;
        translationX = focusX - (focusX - translationX) * appliedFactor;
        translationY = focusY - (focusY - translationY) * appliedFactor;
        zoom = nextZoom;
        clampTranslation();
        applyMatrix();
    }

    void panBy(float distanceX, float distanceY) {
        if (!isZoomed() || getDrawable() == null) return;
        translationX += distanceX;
        translationY += distanceY;
        clampTranslation();
        applyMatrix();
    }

    void resetZoom() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            zoom = 1f;
            pageMatrix.reset();
            setImageMatrix(pageMatrix);
            return;
        }
        float widthScale = getWidth() / (float) drawable.getIntrinsicWidth();
        float heightScale = getHeight() / (float) drawable.getIntrinsicHeight();
        baseScale = cropMode ? Math.max(widthScale, heightScale) : Math.min(widthScale, heightScale);
        zoom = 1f;
        float scaledWidth = drawable.getIntrinsicWidth() * baseScale;
        float scaledHeight = drawable.getIntrinsicHeight() * baseScale;
        translationX = (getWidth() - scaledWidth) / 2f;
        translationY = (getHeight() - scaledHeight) / 2f;
        applyMatrix();
    }

    private void clampTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        float scaledWidth = drawable.getIntrinsicWidth() * baseScale * zoom;
        float scaledHeight = drawable.getIntrinsicHeight() * baseScale * zoom;
        if (scaledWidth <= getWidth()) translationX = (getWidth() - scaledWidth) / 2f;
        else translationX = Math.max(getWidth() - scaledWidth, Math.min(0f, translationX));
        if (scaledHeight <= getHeight()) translationY = (getHeight() - scaledHeight) / 2f;
        else translationY = Math.max(getHeight() - scaledHeight, Math.min(0f, translationY));
    }

    private void applyMatrix() {
        pageMatrix.reset();
        pageMatrix.setScale(baseScale * zoom, baseScale * zoom);
        pageMatrix.postTranslate(translationX, translationY);
        setImageMatrix(pageMatrix);
    }
}
