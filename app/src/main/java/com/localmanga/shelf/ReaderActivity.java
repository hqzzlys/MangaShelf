package com.localmanga.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReaderActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicInteger decodeGeneration = new AtomicInteger();
    private ComicRepository repository;
    private Comic comic;
    private ReaderPageView pageView;
    private LinearLayout topBar, controls;
    private TextView pageLabel, remainLabel;
    private SeekBar progress;
    private Bitmap currentBitmap;
    private int page;
    private boolean chromeVisible = true;
    private volatile boolean destroyed;
    private float touchX;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLock lock = new AppLock(this);
        if (lock.isEnabled() && !AppLock.isSessionUnlocked()) {
            Intent gate = new Intent(this, MainActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gate); finish(); return;
        }
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        repository = new ComicRepository(this);
        comic = repository.load(getIntent().getStringExtra("comic_id"));
        if (comic == null || comic.pages.isEmpty()) { finish(); return; }
        page = Math.max(0, Math.min(comic.progress, comic.pages.size() - 1));
        buildUi(); showPage(page);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        pageView = new ReaderPageView(this); pageView.setScaleType(ImageView.ScaleType.FIT_CENTER); pageView.setBackgroundColor(Color.BLACK);
        pageView.setContentDescription(getString(R.string.reader_page_description));
        root.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        pageView.setOnTouchListener((view, event) -> {
            float position = event.getX() / Math.max(1f, pageView.getWidth());
            if (event.getAction() == MotionEvent.ACTION_UP && Math.abs(event.getX() - touchX) <= dp(55)
                    && position >= 0.30f && position <= 0.70f) view.performClick();
            return onPageTouch(event);
        });
        pageView.setOnClickListener(view -> toggleChrome());
        topBar = buildTop(); root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(82), Gravity.TOP));
        controls = buildControls(); FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM); root.addView(controls, cp);
        setContentView(root);
    }

    private LinearLayout buildTop() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), dp(10), dp(12), 0);
        bar.setBackgroundColor(0xDD090909);
        TextView back = text("‹", 42, Color.WHITE, false); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish()); bar.addView(back, lp(dp(48), -1));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(text(comic.title, 17, Color.WHITE, true), lp(-1, dp(30)));
        titles.addView(text("本地漫画 · 共 " + comic.pageCount() + " 页", 12, 0xFFCACACA, false), lp(-1, dp(24)));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));
        TextView menu = text("⋮", 29, Color.WHITE, true); menu.setGravity(Gravity.CENTER); menu.setOnClickListener(v -> showReaderInfo()); bar.addView(menu, lp(dp(44), -1)); return bar;
    }

    private LinearLayout buildControls() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(18), dp(12), dp(18), dp(8));
        panel.setBackground(round(0xEB121212, 22));
        LinearLayout seekRow = new LinearLayout(this); seekRow.setGravity(Gravity.CENTER_VERTICAL);
        pageLabel = text("1/1", 14, 0xFFFF9A45, true); seekRow.addView(pageLabel, lp(dp(54), dp(36)));
        progress = new SeekBar(this); progress.setMax(Math.max(0, comic.pageCount() - 1)); progress.setProgress(page);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF47B20));
        progress.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF47B20));
        seekRow.addView(progress, new LinearLayout.LayoutParams(0, dp(40), 1));
        remainLabel = text(getString(R.string.reader_remaining_pages, 0), 13, Color.WHITE, false); remainLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); seekRow.addView(remainLabel, lp(dp(78), dp(36)));
        panel.addView(seekRow, lp(-1, dp(48)));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean user) { if (user) updateLabels(value); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { showPage(s.getProgress()); }
        });
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        addAction(actions, "◀\n上一页", v -> showPage(page - 1));
        addAction(actions, "☀\n亮度", v -> brightnessDialog());
        addAction(actions, "▣\n适应", v -> toggleScale());
        addAction(actions, "◇\n旋转", v -> toggleOrientation());
        addAction(actions, "▶\n下一页", v -> showPage(page + 1));
        panel.addView(actions, lp(-1, dp(88))); return panel;
    }

    private void addAction(LinearLayout row, String value, View.OnClickListener click) {
        TextView item = text(value, 13, Color.WHITE, false); item.setGravity(Gravity.CENTER); item.setOnClickListener(click);
        row.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private boolean onPageTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { touchX = event.getX(); return true; }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float delta = event.getX() - touchX;
            if (Math.abs(delta) > dp(55)) {
                showPage(page + (delta < 0 ? 1 : -1));
            } else {
                float position = event.getX() / Math.max(1f, pageView.getWidth());
                if (position < 0.30f) showPage(page - 1);
                else if (position > 0.70f) showPage(page + 1);
            }
            return true;
        }
        return true;
    }

    private void showPage(int requested) {
        int next = Math.max(0, Math.min(requested, comic.pageCount() - 1)); page = next;
        updateLabels(next); repository.saveProgress(comic.id, next);
        File file = comic.pages.get(next); int targetW = getResources().getDisplayMetrics().widthPixels;
        int targetH = getResources().getDisplayMetrics().heightPixels;
        int generation = decodeGeneration.incrementAndGet();
        worker.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1; while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565;
            final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            runOnUiThread(() -> {
                if (destroyed || generation != decodeGeneration.get() || page != next) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                recycleCurrent(); currentBitmap = bitmap; pageView.setImageBitmap(bitmap);
            });
        });
    }

    private void updateLabels(int value) {
        pageLabel.setText(getString(R.string.reader_page_progress, value + 1, comic.pageCount()));
        remainLabel.setText(getString(R.string.reader_remaining_pages, Math.max(0, comic.pageCount() - value - 1)));
        if (progress.getProgress() != value) progress.setProgress(value);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        topBar.animate().alpha(chromeVisible ? 1 : 0).translationY(chromeVisible ? 0 : -topBar.getHeight()).setDuration(180).start();
        controls.animate().alpha(chromeVisible ? 1 : 0).translationY(chromeVisible ? 0 : controls.getHeight()).setDuration(180).start();
    }

    private void toggleScale() {
        pageView.setScaleType(pageView.getScaleType() == ImageView.ScaleType.FIT_CENTER ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
    }

    private void toggleOrientation() {
        int current = getResources().getConfiguration().orientation;
        setRequestedOrientation(current == android.content.res.Configuration.ORIENTATION_PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void brightnessDialog() {
        SeekBar seek = new SeekBar(this); seek.setMax(100); seek.setProgress(70); seek.setPadding(dp(24), 0, dp(24), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) { WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = Math.max(.05f, v / 100f); getWindow().setAttributes(lp); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });
        new AlertDialog.Builder(this).setTitle("阅读亮度").setView(seek).setPositiveButton("完成", null).show();
    }

    private void showReaderInfo() {
        new AlertDialog.Builder(this).setTitle("阅读操作").setMessage("点击屏幕左侧翻到上一页，点击右侧翻到下一页；点击中间区域隐藏或显示控制栏。也可左右滑动翻页。“适应”可切换完整显示与铺满屏幕，阅读进度会自动保存。")
                .setPositiveButton("知道了", null).show();
    }

    private void recycleCurrent() { if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle(); currentBitmap = null; }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create("sans", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        view.setMaxLines(2); view.setEllipsize(android.text.TextUtils.TruncateAt.END); return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadii(new float[]{dp(radius),dp(radius),dp(radius),dp(radius),0,0,0,0}); return drawable;
    }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ReaderPageView extends ImageView {
        ReaderPageView(Context context) { super(context); }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        decodeGeneration.incrementAndGet();
        if (repository != null && comic != null) repository.saveProgress(comic.id, page);
        worker.shutdownNow(); recycleCurrent(); super.onDestroy();
    }
}
