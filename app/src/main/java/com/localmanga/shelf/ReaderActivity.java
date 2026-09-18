package com.localmanga.shelf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ReaderActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<Integer, Bitmap> pageCache = new LruCache<Integer, Bitmap>(
            Math.max(8192, (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L))) {
        @Override protected int sizeOf(Integer key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private ComicRepository repository;
    private Comic comic;
    private ZoomablePageView pageView;
    private LinearLayout topBar;
    private LinearLayout controls;
    private TextView pageLabel;
    private TextView remainLabel;
    private SeekBar progress;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private CoverLoader thumbnailLoader;
    private int page;
    private boolean chromeVisible = true;
    private boolean rightToLeft;
    private boolean multiTouchGesture;
    private boolean gestureStartedZoomed;
    private volatile boolean destroyed;
    private float touchDownX;
    private float touchDownY;
    private int touchSlop;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLock lock = new AppLock(this);
        if (lock.isEnabled() && !AppLock.isSessionUnlocked()) {
            Intent gate = new Intent(this, MainActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(gate);
            finish();
            return;
        }
        int readerBackground = getColor(R.color.reader_background);
        getWindow().setStatusBarColor(readerBackground);
        getWindow().setNavigationBarColor(readerBackground);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        repository = new ComicRepository(this);
        rightToLeft = getSharedPreferences("reader_settings_v1", MODE_PRIVATE)
                .getBoolean("right_to_left", false);
        comic = repository.load(getIntent().getStringExtra("comic_id"));
        if (comic == null || comic.pages.isEmpty()) {
            finish();
            return;
        }
        page = ReaderNavigation.clampPage(comic.progress, comic.pages.size());
        buildUi();
        showPage(page);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.reader_background));
        pageView = new ZoomablePageView(this);
        pageView.setBackgroundColor(Color.BLACK);
        pageView.setContentDescription(getString(R.string.reader_page_description));
        root.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        configurePageGestures();
        pageView.setOnClickListener(view -> toggleChrome());

        topBar = buildTop();
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        controls = buildControls();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        WindowInsetsHelper.enableEdgeToEdge(getWindow(), root);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configurePageGestures() {
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                multiTouchGesture = true;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                pageView.zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                float position = event.getX() / Math.max(1f, pageView.getWidth());
                if (pageView.isZoomed() || position >= 0.30f && position <= 0.70f) {
                    pageView.toggleZoom(event.getX(), event.getY());
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                float position = event.getX() / Math.max(1f, pageView.getWidth());
                if (position >= 0.30f && position <= 0.70f) pageView.performClick();
                return true;
            }

            @Override public boolean onScroll(MotionEvent first, MotionEvent current,
                    float distanceX, float distanceY) {
                if (!pageView.isZoomed()) return false;
                pageView.panBy(-distanceX, -distanceY);
                return true;
            }
        });
        pageView.setOnTouchListener((view, event) -> onPageTouch(event));
    }

    private boolean onPageTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            multiTouchGesture = false;
            gestureStartedZoomed = pageView.isZoomed();
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchGesture = true;
        }

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && !multiTouchGesture && !gestureStartedZoomed && !pageView.isZoomed()) {
            float horizontalDistance = event.getX() - touchDownX;
            float verticalDistance = event.getY() - touchDownY;
            if (Math.abs(horizontalDistance) > dp(55)
                    && Math.abs(horizontalDistance) > Math.abs(verticalDistance) * 1.2f) {
                showPage(page + ReaderNavigation.deltaForSwipe(horizontalDistance, rightToLeft));
            } else if (Math.hypot(horizontalDistance, verticalDistance) <= touchSlop) {
                float position = event.getX() / Math.max(1f, pageView.getWidth());
                int delta = ReaderNavigation.deltaForTap(position, rightToLeft);
                if (delta != 0) showPage(page + delta);
            }
        }
        return true;
    }

    private LinearLayout buildTop() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), 0);
        bar.setBackgroundColor(0xDD090909);
        bar.setMinimumHeight(dp(72));
        Button back = actionButton("‹", getString(R.string.reader_back), 38);
        back.setOnClickListener(v -> finish());
        bar.addView(back, lp(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(text(comic.title, 17, Color.WHITE, true), lp(-1, -2));
        titles.addView(text(getString(R.string.reader_subtitle, comic.pageCount()),
                12, 0xFFCACACA, false), lp(-1, -2));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));

        Button menu = actionButton("⋮", getString(R.string.reader_settings), 26);
        menu.setOnClickListener(v -> showReaderInfo());
        bar.addView(menu, lp(dp(48), dp(48)));
        return bar;
    }

    private LinearLayout buildControls() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(8));
        panel.setBackground(round(0xEB121212, 22));
        panel.setMinimumHeight(dp(150));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        Button pageButton = actionButton("1/1", getString(R.string.reader_page_picker_description), 14);
        pageButton.setTextColor(0xFFFF9A45);
        pageButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pageButton.setOnClickListener(v -> showPagePicker());
        pageLabel = pageButton;
        seekRow.addView(pageLabel, lp(dp(62), dp(42)));

        progress = new SeekBar(this);
        progress.setMax(Math.max(0, comic.pageCount() - 1));
        progress.setProgress(page);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF47B20));
        progress.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF47B20));
        seekRow.addView(progress, new LinearLayout.LayoutParams(0, dp(40), 1));
        remainLabel = text(getString(R.string.reader_remaining_pages, 0),
                13, Color.WHITE, false);
        remainLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        seekRow.addView(remainLabel, lp(dp(78), dp(36)));
        seekRow.setMinimumHeight(dp(48));
        panel.addView(seekRow, lp(-1, -2));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) updateLabels(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                showPage(seekBar.getProgress());
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        addAction(actions, getString(R.string.reader_previous_button),
                getString(R.string.reader_previous_page), v -> showPage(page - 1));
        addAction(actions, getString(R.string.reader_brightness_button),
                getString(R.string.reader_brightness_description), v -> brightnessDialog());
        addAction(actions, getString(R.string.reader_fit_button),
                getString(R.string.reader_fit_description), v -> toggleScale());
        addAction(actions, getString(R.string.reader_rotate_button),
                getString(R.string.reader_rotate_description), v -> toggleOrientation());
        addAction(actions, getString(R.string.reader_next_button),
                getString(R.string.reader_next_page), v -> showPage(page + 1));
        actions.setMinimumHeight(dp(88));
        panel.addView(actions, lp(-1, -2));
        return panel;
    }

    private void addAction(LinearLayout row, String value, String description,
            View.OnClickListener click) {
        Button item = actionButton(value, description, 13);
        item.setOnClickListener(click);
        row.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void showPage(int requested) {
        int next = ReaderNavigation.clampPage(requested, comic.pageCount());
        page = next;
        updateLabels(next);
        repository.saveProgress(comic.id, next);
        pageView.setContentDescription(getString(
                R.string.reader_page_position_description, next + 1, comic.pageCount()));
        worker.getQueue().clear();

        Bitmap cached = pageCache.get(next);
        if (cached != null && !cached.isRecycled()) pageView.setImageBitmap(cached);
        else {
            pageView.setImageDrawable(null);
            queuePage(next);
        }
        queuePage(next + 1);
        queuePage(next - 1);
    }

    private void queuePage(int index) {
        if (destroyed || index < 0 || index >= comic.pageCount()) return;
        Bitmap cached = pageCache.get(index);
        if (cached != null && !cached.isRecycled()) return;
        int targetWidth = getResources().getDisplayMetrics().widthPixels;
        int targetHeight = getResources().getDisplayMetrics().heightPixels;
        File file = comic.pages.get(index);
        try {
            worker.execute(() -> {
                Bitmap bitmap = decodePage(file, targetWidth, targetHeight);
                if (bitmap == null || destroyed) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                pageCache.put(index, bitmap);
                runOnUiThread(() -> {
                    if (!destroyed && page == index) pageView.setImageBitmap(bitmap);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Activity teardown can race with a final page request.
        }
    }

    private static Bitmap decodePage(File file, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > targetWidth * 2
                || bounds.outHeight / sample > targetHeight * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void updateLabels(int value) {
        pageLabel.setText(getString(R.string.reader_page_progress, value + 1, comic.pageCount()));
        remainLabel.setText(getString(
                R.string.reader_remaining_pages, Math.max(0, comic.pageCount() - value - 1)));
        if (progress.getProgress() != value) progress.setProgress(value);
    }

    private void showPagePicker() {
        if (thumbnailLoader == null) thumbnailLoader = new CoverLoader();
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(96));
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(12), dp(8), dp(12), dp(12));
        grid.setClipToPadding(false);
        grid.setAdapter(new PageThumbnailAdapter());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reader_page_picker)
                .setView(grid)
                .setNegativeButton(R.string.reader_cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            showPage(position);
            dialog.dismiss();
        });
        dialog.setOnShowListener(ignored -> grid.post(() -> grid.setSelection(page)));
        dialog.show();
    }

    private final class PageThumbnailAdapter extends BaseAdapter {
        @Override public int getCount() {
            return comic.pageCount();
        }

        @Override public Object getItem(int position) {
            return comic.pages.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ThumbnailHolder holder;
            if (convertView == null) {
                LinearLayout root = new LinearLayout(ReaderActivity.this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);
                root.setPadding(dp(3), dp(3), dp(3), dp(5));
                ImageView image = new ImageView(ReaderActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(image, new LinearLayout.LayoutParams(dp(90), dp(118)));
                TextView label = text("", 12, Color.WHITE, false);
                label.setGravity(Gravity.CENTER);
                root.addView(label, new LinearLayout.LayoutParams(-1, dp(30)));
                holder = new ThumbnailHolder(image, label);
                root.setTag(holder);
                convertView = root;
            } else {
                holder = (ThumbnailHolder) convertView.getTag();
            }
            convertView.setBackgroundColor(position == page ? 0x66F47B20 : Color.TRANSPARENT);
            holder.label.setText(getString(R.string.reader_page_number, position + 1));
            holder.image.setContentDescription(getString(
                    R.string.reader_thumbnail_description, position + 1));
            thumbnailLoader.load(comic.pages.get(position), holder.image, dp(90), dp(118));
            return convertView;
        }
    }

    private static final class ThumbnailHolder {
        final ImageView image;
        final TextView label;

        ThumbnailHolder(ImageView image, TextView label) {
            this.image = image;
            this.label = label;
        }
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        topBar.animate().alpha(chromeVisible ? 1 : 0)
                .translationY(chromeVisible ? 0 : -topBar.getHeight()).setDuration(180).start();
        controls.animate().alpha(chromeVisible ? 1 : 0)
                .translationY(chromeVisible ? 0 : controls.getHeight()).setDuration(180).start();
    }

    private void toggleScale() {
        pageView.setCropMode(!pageView.isCropMode());
    }

    private void toggleOrientation() {
        int current = getResources().getConfiguration().orientation;
        setRequestedOrientation(current == android.content.res.Configuration.ORIENTATION_PORTRAIT
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void brightnessDialog() {
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(70);
        seek.setPadding(dp(24), 0, dp(24), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                WindowManager.LayoutParams attributes = getWindow().getAttributes();
                attributes.screenBrightness = Math.max(.05f, value / 100f);
                getWindow().setAttributes(attributes);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        new AlertDialog.Builder(this).setTitle(R.string.reader_brightness_title)
                .setView(seek).setPositiveButton(R.string.reader_done, null).show();
    }

    private void showReaderInfo() {
        String direction = getString(rightToLeft
                ? R.string.reader_direction_rtl : R.string.reader_direction_ltr);
        new AlertDialog.Builder(this).setTitle(R.string.reader_settings)
                .setItems(new String[]{getString(R.string.reader_help),
                        getString(R.string.reader_direction_setting, direction)}, (dialog, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this).setTitle(R.string.reader_operation_title)
                                .setMessage(R.string.reader_operation_message)
                                .setPositiveButton(R.string.reader_got_it, null).show();
                    } else {
                        rightToLeft = !rightToLeft;
                        getSharedPreferences("reader_settings_v1", MODE_PRIVATE).edit()
                                .putBoolean("right_to_left", rightToLeft).apply();
                        String changedDirection = getString(rightToLeft
                                ? R.string.reader_direction_rtl : R.string.reader_direction_ltr);
                        Toast.makeText(this, getString(
                                R.string.reader_direction_changed, changedDirection),
                                Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton(R.string.reader_cancel, null).show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                showPage(page - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                showPage(page + 1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create("sans", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{dp(radius), dp(radius), dp(radius), dp(radius), 0, 0, 0, 0});
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button actionButton(String value, String description, int size) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(size);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        int touchTarget = getResources().getDimensionPixelSize(R.dimen.minimum_touch_target);
        button.setMinWidth(touchTarget);
        button.setMinHeight(touchTarget);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        return button;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (repository != null && comic != null) repository.saveProgress(comic.id, page);
        if (pageView != null) pageView.setImageDrawable(null);
        worker.shutdownNow();
        pageCache.evictAll();
        if (thumbnailLoader != null) thumbnailLoader.close();
        super.onDestroy();
    }
}
