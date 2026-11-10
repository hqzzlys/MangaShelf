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
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReaderActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    private final Set<Long> decodingPages = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final LruCache<Integer, Bitmap> pageCache = new LruCache<Integer, Bitmap>(
            Math.max(8192, (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L))) {
        @Override protected int sizeOf(Integer key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final AtomicInteger decodeGeneration = new AtomicInteger();

    private ComicRepository repository;
    private ReaderSettings settings;
    private Comic comic;
    private ZoomablePageView pageView;
    private ListView continuousView;
    private ContinuousPageAdapter continuousAdapter;
    private LinearLayout topBar;
    private LinearLayout controls;
    private TextView pageLabel;
    private TextView remainLabel;
    private Button bookmarkButton;
    private Button autoTurnButton;
    private final Handler autoTurnHandler = new Handler(Looper.getMainLooper());
    private int page;
    private boolean autoTurning;
    private boolean autoTurnScheduled;
    private final Set<Integer> failedPages = new HashSet<>();
    private final SparseArray<int[]> pageSizes = new SparseArray<>();
    private PageStatusView pagedStatus;
    private Bitmap pagedBitmap;
    private int restorePage = -1;
    private float restoreFraction;
    private SeekBar progress;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private CoverLoader thumbnailLoader;
    private Set<Integer> bookmarks;
    private boolean chromeVisible = true;
    private boolean continuousMode;
    private boolean autoCrop;
    private boolean rightToLeft;
    private boolean multiTouchGesture;
    private boolean gestureStartedZoomed;
    private boolean selectingContinuousPage;
    private volatile boolean destroyed;
    private float touchDownX;
    private float touchDownY;
    private int touchSlop;

    private final Runnable autoTurnTask = () -> {
        autoTurnScheduled = false;
        if (!autoTurning) return;
        if (!hasWindowFocus()) { stopAutoTurn(); return; }
        if (!visiblePagesReady()) return;
        if (continuousMode) {
            if (!continuousView.canScrollVertically(1)) { stopAutoTurn(); return; }
            int viewport = continuousView.getHeight() - continuousView.getPaddingTop()
                    - continuousView.getPaddingBottom();
            continuousView.scrollListBy(ReaderNavigation.scrollStep(viewport));
            continuousView.post(this::scheduleAutoTurn);
        } else if (page < comic.pageCount() - 1) showPage(page + 1);
        else stopAutoTurn();
    };

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
        comic = repository.load(getIntent().getStringExtra("comic_id"));
        if (comic == null || comic.pages.isEmpty()) {
            finish();
            return;
        }
        boolean legacyRightToLeft = getSharedPreferences("reader_settings_v1", MODE_PRIVATE)
                .getBoolean("right_to_left", false);
        settings = new ReaderSettings(this, comic.id, legacyRightToLeft);
        continuousMode = settings.isContinuous();
        autoCrop = settings.isAutoCrop();
        rightToLeft = settings.isRightToLeft();
        bookmarks = repository.loadBookmarks(comic.id);
        page = ReaderNavigation.clampPage(comic.progress, comic.pages.size());
        applyBrightness(settings.brightnessPercent());
        setRequestedOrientation(settings.isLandscape()
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        buildUi();
        pageView.setCropMode(settings.isFillScreen());
        applyReaderMode(true);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.reader_background));
        pageView = new ZoomablePageView(this);
        pageView.setBackgroundColor(Color.BLACK);
        pageView.setContentDescription(getString(R.string.reader_page_description));
        root.addView(pageView, new FrameLayout.LayoutParams(-1, -1));
        pagedStatus = new PageStatusView();
        root.addView(pagedStatus, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        configurePageGestures();
        pageView.setOnClickListener(view -> toggleChrome());

        continuousView = new ListView(this);
        continuousView.setBackgroundColor(Color.BLACK);
        continuousView.setCacheColorHint(Color.BLACK);
        continuousView.setDivider(null);
        continuousView.setDividerHeight(0);
        continuousView.setFastScrollEnabled(true);
        continuousView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                stopAutoTurn();
                restorePage = -1;
                selectingContinuousPage = false;
            }
            return false;
        });
        continuousAdapter = new ContinuousPageAdapter();
        continuousView.setAdapter(continuousAdapter);
        continuousView.setOnItemClickListener((parent, view, position, id) -> toggleChrome());
        continuousView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) saveContinuousPosition();
                else {
                    stopAutoTurn();
                    restorePage = -1;
                    selectingContinuousPage = false;
                }
            }
            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
                if (!continuousMode || selectingContinuousPage || totalItemCount == 0) return;
                updateCurrentPageFromScroll(firstVisibleItem);
                scheduleAutoTurn();
                int last = Math.min(totalItemCount - 1, firstVisibleItem + visibleItemCount + 1);
                for (int index = Math.max(0, firstVisibleItem - 1); index <= last; index++) queuePage(index);
            }
        });
        root.addView(continuousView, new FrameLayout.LayoutParams(-1, -1));

        topBar = buildTop();
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        controls = buildControls();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        WindowInsetsHelper.enableEdgeToEdge(getWindow(), root);
        root.post(this::updateContinuousPadding);
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
            @Override public boolean onDown(MotionEvent event) { return true; }
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
            stopAutoTurn();
            touchDownX = event.getX();
            touchDownY = event.getY();
            multiTouchGesture = false;
            gestureStartedZoomed = pageView.isZoomed();
        } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) multiTouchGesture = true;
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
        panel.setPadding(dp(12), dp(10), dp(12), dp(7));
        panel.setBackground(round(0xEB121212, 22));
        panel.setMinimumHeight(dp(148));
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
        remainLabel = text(getString(R.string.reader_remaining_pages, 0), 13, Color.WHITE, false);
        remainLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        seekRow.addView(remainLabel, lp(dp(78), dp(36)));
        seekRow.setMinimumHeight(dp(48));
        panel.addView(seekRow, lp(-1, -2));
        autoTurnButton = actionButton(getString(R.string.reader_auto_turn_continue),
                getString(R.string.reader_auto_turn_continue), 14);
        autoTurnButton.setTextColor(0xFFFF9A45);
        autoTurnButton.setOnClickListener(v -> {
            if (autoTurning) stopAutoTurn();
            else startAutoTurn();
        });
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.addView(autoTurnButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button interval = actionButton(getString(R.string.reader_auto_turn_interval),
                getString(R.string.reader_auto_turn_seconds), 14);
        interval.setOnClickListener(v -> showAutoTurnDialog());
        autoRow.addView(interval, lp(dp(96), dp(48)));
        panel.addView(autoRow, lp(-1, -2));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) updateLabels(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { stopAutoTurn(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { showPage(seekBar.getProgress()); }
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        addAction(actions, getString(R.string.reader_previous_button),
                getString(R.string.reader_previous_page), v -> showPage(page - 1));
        addAction(actions, getString(R.string.reader_brightness_button),
                getString(R.string.reader_brightness_description), v -> brightnessDialog());
        bookmarkButton = addAction(actions, getString(R.string.reader_bookmark_button),
                getString(R.string.reader_bookmark_description), v -> toggleBookmark());
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

    private Button addAction(LinearLayout row, String value, String description, View.OnClickListener click) {
        Button item = actionButton(value, description, 12);
        item.setOnClickListener(click);
        row.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        return item;
    }

    private void applyReaderMode(boolean initial) {
        if (!initial) {
            settings.setContinuous(continuousMode);
            resetDecodedPages();
        }
        pageView.setVisibility(continuousMode ? View.GONE : View.VISIBLE);
        continuousView.setVisibility(continuousMode ? View.VISIBLE : View.GONE);
        pagedStatus.setVisibility(View.GONE);
        updateContinuousPadding();
        if (continuousMode) {
            continuousAdapter.notifyDataSetChanged();
            float fraction = initial && settings.continuousPage() == page
                    ? settings.continuousOffset() : 0f;
            positionContinuousPage(page, fraction);
            updateLabels(page);
            updateBookmarkButton();
            queuePage(page);
            queuePage(page + 1);
        } else showPage(page);
    }

    private void updateContinuousPadding() {
        if (continuousView == null || topBar == null || controls == null) return;
        continuousView.setPadding(0, chromeVisible ? topBar.getHeight() : 0,
                0, chromeVisible ? controls.getHeight() : 0);
    }

    private void positionContinuousPage(int index, float fraction) {
        restorePage = index;
        restoreFraction = fraction;
        selectingContinuousPage = true;
        continuousView.setSelectionFromTop(index, 0);
        continuousView.post(this::restoreContinuousPosition);
    }

    private void restoreContinuousPosition() {
        if (destroyed || restorePage < 0 || !continuousMode) return;
        int index = restorePage;
        if (bitmapForPage(index) == null && !failedPages.contains(index)) return;
        continuousView.setSelectionFromTop(index,
                -ReaderNavigation.offsetPixels(restoreFraction, continuousPageHeight(index)));
        continuousView.post(() -> {
            if (destroyed || restorePage != index) return;
            restorePage = -1;
            selectingContinuousPage = false;
            scheduleAutoTurn();
        });
    }

    private void saveContinuousPosition() {
        if (!continuousMode || settings == null || continuousView == null || restorePage >= 0) return;
        View first = continuousView.getChildAt(0);
        if (first == null) return;
        int index = continuousView.getFirstVisiblePosition();
        settings.setContinuousPosition(index, ReaderNavigation.offsetFraction(
                continuousView.getPaddingTop() - first.getTop(), first.getHeight()));
        repository.saveProgress(comic.id, index);
    }

    private void showPage(int requested) {
        cancelAutoTurnTick();
        int next = ReaderNavigation.clampPage(requested, comic.pageCount());
        Bitmap cached = bitmapForPage(next);
        page = next;
        updateLabels(next);
        updateBookmarkButton();
        repository.saveProgress(comic.id, next);
        if (continuousMode) {
            positionContinuousPage(next, 0f);
            queuePage(next);
            queuePage(next + 1);
            return;
        }
        pageView.setContentDescription(getString(
                R.string.reader_page_position_description, next + 1, comic.pageCount()));
        worker.getQueue().clear();
        decodingPages.clear();
        pagedBitmap = cached;
        if (cached != null) pageView.setImageBitmap(cached);
        else {
            pageView.setImageDrawable(null);
            queuePage(next);
        }
        pagedStatus.bind(next);
        pageView.post(this::scheduleAutoTurn);
        queuePage(next + 1);
        queuePage(next - 1);
    }

    private void updateCurrentPageFromScroll(int value) {
        int next = ReaderNavigation.clampPage(value, comic.pageCount());
        if (page == next) return;
        page = next;
        updateLabels(next);
        updateBookmarkButton();
        repository.saveProgress(comic.id, next);
    }

    private Bitmap bitmapForPage(int index) {
        Bitmap bitmap = pageCache.get(index);
        if (bitmap != null) return bitmap;
        if (!continuousMode) return page == index ? pagedBitmap : null;
        if (continuousView != null) {
            for (int child = 0; child < continuousView.getChildCount(); child++) {
                PageCell cell = (PageCell) continuousView.getChildAt(child);
                if (cell.index == index && cell.generation == decodeGeneration.get()) return cell.bitmap;
            }
        }
        return null;
    }

    private void queuePage(int index) {
        if (destroyed || index < 0 || index >= comic.pageCount()) return;
        if (failedPages.contains(index) || bitmapForPage(index) != null) return;
        int targetWidth = getResources().getDisplayMetrics().widthPixels;
        int targetHeight = getResources().getDisplayMetrics().heightPixels;
        File file = comic.pages.get(index);
        int generation = decodeGeneration.get();
        boolean crop = autoCrop;
        boolean continuous = continuousMode;
        long requestKey = ((long) generation << 32) | (index & 0xffffffffL);
        if (!decodingPages.add(requestKey)) return;
        try {
            worker.execute(() -> {
                try {
                    Bitmap bitmap = decodePage(file, targetWidth, targetHeight, crop, continuous);
                    if (destroyed || generation != decodeGeneration.get()) {
                        if (bitmap != null) bitmap.recycle();
                        return;
                    }
                    if (bitmap != null) pageCache.put(index, bitmap);
                    runOnUiThread(() -> {
                        if (destroyed || generation != decodeGeneration.get()) return;
                        if (bitmap == null) failedPages.add(index);
                        else pageSizes.put(index, new int[]{bitmap.getWidth(), bitmap.getHeight()});
                        if (continuousMode) {
                            for (int child = 0; child < continuousView.getChildCount(); child++) {
                                PageCell cell = (PageCell) continuousView.getChildAt(child);
                                if (cell.index == index) cell.bind(index, bitmap);
                            }
                            continuousView.post(this::restoreContinuousPosition);
                        } else if (page == index) {
                            pagedBitmap = bitmap;
                            if (bitmap != null) pageView.setImageBitmap(bitmap);
                            pagedStatus.bind(index);
                        }
                        if (bitmap == null && page == index) stopAutoTurn();
                        // Start the interval after layout has displayed the decoded page.
                        pageView.post(this::scheduleAutoTurn);
                    });
                } finally {
                    decodingPages.remove(requestKey);
                }
            });
        } catch (RejectedExecutionException ignored) {
            decodingPages.remove(requestKey);
            // Activity teardown can race with a final page request.
        }
    }

    private static Bitmap decodePage(File file, int targetWidth, int targetHeight,
            boolean crop, boolean continuous) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > targetWidth * 2
                || !continuous && bounds.outHeight / sample > targetHeight * 2
                || continuous && (long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > 20_000_000L) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (!crop || bitmap == null) return bitmap;
        WhiteBorderDetector.Bounds detected = WhiteBorderDetector.detect(
                bitmap.getWidth(), bitmap.getHeight(), bitmap::getPixel);
        if (detected.left == 0 && detected.top == 0
                && detected.right == bitmap.getWidth() && detected.bottom == bitmap.getHeight()) return bitmap;
        Bitmap cropped = Bitmap.createBitmap(bitmap, detected.left, detected.top,
                detected.width(), detected.height());
        if (cropped != bitmap) bitmap.recycle();
        return cropped;
    }

    private int continuousPageHeight(int index) {
        int width = Math.max(1, continuousView.getWidth());
        int[] size = pageSizes.get(index);
        return size == null ? Math.max(dp(160), width * 4 / 3)
                : Math.max(1, Math.round(width * (size[1] / (float) size[0])));
    }

    private final class PageStatusView extends LinearLayout {
        private final TextView message;
        private final Button retry;
        private final Button skip;
        PageStatusView() {
            super(ReaderActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            setBackgroundColor(0xDD121212);
            message = text("", 15, Color.WHITE, false);
            message.setGravity(Gravity.CENTER);
            message.setMaxLines(4);
            addView(message, lp(-1, -2));
            LinearLayout actions = new LinearLayout(ReaderActivity.this);
            retry = actionButton(getString(R.string.reader_retry_page),
                    getString(R.string.reader_retry_page), 14);
            skip = actionButton(getString(R.string.reader_skip_page),
                    getString(R.string.reader_skip_page), 14);
            actions.addView(retry, new LinearLayout.LayoutParams(0, dp(48), 1));
            actions.addView(skip, new LinearLayout.LayoutParams(0, dp(48), 1));
            addView(actions, lp(-1, -2));
        }
        void bind(int index) {
            if (bitmapForPage(index) != null) { setVisibility(GONE); return; }
            boolean failed = failedPages.contains(index);
            setVisibility(VISIBLE);
            message.setText(getString(failed ? R.string.reader_page_failed
                    : R.string.reader_page_loading, index + 1));
            retry.setVisibility(failed ? VISIBLE : GONE);
            skip.setVisibility(failed && index + 1 < comic.pageCount() ? VISIBLE : GONE);
            retry.setOnClickListener(v -> {
                stopAutoTurn();
                failedPages.remove(index);
                bind(index);
                queuePage(index);
            });
            skip.setOnClickListener(v -> { stopAutoTurn(); showPage(index + 1); });
        }
    }

    private final class PageCell extends FrameLayout {
        final ImageView image;
        final PageStatusView status;
        int index = -1;
        int generation = -1;
        Bitmap bitmap;
        PageCell() {
            super(ReaderActivity.this);
            image = new ImageView(ReaderActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(Color.BLACK);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            status = new PageStatusView();
            addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }
        void bind(int position, Bitmap value) {
            index = position;
            generation = decodeGeneration.get();
            bitmap = value;
            image.setImageBitmap(value);
            image.setContentDescription(getString(R.string.reader_page_position_description,
                    position + 1, comic.pageCount()));
            setLayoutParams(new AbsListView.LayoutParams(-1, continuousPageHeight(position)));
            status.bind(position);
        }
    }

    private final class ContinuousPageAdapter extends BaseAdapter {
        @Override public int getCount() { return comic.pageCount(); }
        @Override public Object getItem(int position) { return comic.pages.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Bitmap bitmap = bitmapForPage(position);
            PageCell cell = convertView instanceof PageCell ? (PageCell) convertView : new PageCell();
            cell.bind(position, bitmap);
            if (bitmap == null) queuePage(position);
            return cell;
        }
    }

    private void updateLabels(int value) {
        pageLabel.setText(getString(R.string.reader_page_progress, value + 1, comic.pageCount()));
        remainLabel.setText(getString(
                R.string.reader_remaining_pages, Math.max(0, comic.pageCount() - value - 1)));
        if (progress.getProgress() != value) progress.setProgress(value);
    }

    private void updateBookmarkButton() {
        if (bookmarkButton == null) return;
        bookmarkButton.setText(bookmarks.contains(page)
                ? R.string.reader_bookmarked_button : R.string.reader_bookmark_button);
    }

    private void toggleBookmark() {
        boolean added = repository.toggleBookmark(comic.id, page);
        if (added) bookmarks.add(page); else bookmarks.remove(page);
        updateBookmarkButton();
        Toast.makeText(this, getString(added ? R.string.reader_bookmark_added
                : R.string.reader_bookmark_removed, page + 1), Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        stopAutoTurn();
        List<Integer> pages = new ArrayList<>(bookmarks);
        Collections.sort(pages);
        if (pages.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.reader_bookmarks_title)
                    .setMessage(R.string.reader_bookmarks_empty)
                    .setPositiveButton(R.string.reader_got_it, null).show();
            return;
        }
        String[] labels = new String[pages.size()];
        for (int index = 0; index < pages.size(); index++) {
            labels[index] = getString(R.string.reader_page_number, pages.get(index) + 1);
        }
        new AlertDialog.Builder(this).setTitle(R.string.reader_bookmarks_title)
                .setItems(labels, (dialog, which) -> showPage(pages.get(which)))
                .setNegativeButton(R.string.reader_cancel, null).show();
    }

    private void showPagePicker() {
        stopAutoTurn();
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
                .setTitle(R.string.reader_page_picker).setView(grid)
                .setNegativeButton(R.string.reader_cancel, null).create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            showPage(position);
            dialog.dismiss();
        });
        dialog.setOnShowListener(ignored -> grid.post(() -> grid.setSelection(page)));
        dialog.show();
    }

    private final class PageThumbnailAdapter extends BaseAdapter {
        @Override public int getCount() { return comic.pageCount(); }
        @Override public Object getItem(int position) { return comic.pages.get(position); }
        @Override public long getItemId(int position) { return position; }
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
            } else holder = (ThumbnailHolder) convertView.getTag();
            convertView.setBackgroundColor(position == page ? 0x66F47B20 : Color.TRANSPARENT);
            holder.label.setText(getString(R.string.reader_page_number, position + 1));
            holder.image.setContentDescription(getString(R.string.reader_thumbnail_description, position + 1));
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
        if (!chromeVisible) stopAutoTurn();
        chromeVisible = !chromeVisible;
        updateContinuousPadding();
        topBar.animate().alpha(chromeVisible ? 1 : 0)
                .translationY(chromeVisible ? 0 : -topBar.getHeight()).setDuration(180).start();
        controls.animate().alpha(chromeVisible ? 1 : 0)
                .translationY(chromeVisible ? 0 : controls.getHeight()).setDuration(180).start();
    }

    private void toggleScale() {
        if (continuousMode) {
            Toast.makeText(this, R.string.reader_continuous_fit_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean fill = !pageView.isCropMode();
        pageView.setCropMode(fill);
        settings.setFillScreen(fill);
    }

    private void toggleOrientation() {
        stopAutoTurn();
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        settings.setLandscape(landscape);
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void brightnessDialog() {
        stopAutoTurn();
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(settings.brightnessPercent());
        seek.setPadding(dp(24), 0, dp(24), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                int brightness = Math.max(5, value);
                applyBrightness(brightness);
                if (fromUser) settings.setBrightnessPercent(brightness);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { stopAutoTurn(); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        new AlertDialog.Builder(this).setTitle(R.string.reader_brightness_title)
                .setView(seek).setPositiveButton(R.string.reader_done, null).show();
    }

    private void applyBrightness(int percent) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = Math.max(.05f, Math.min(1f, percent / 100f));
        getWindow().setAttributes(attributes);
    }

    private void showReaderInfo() {
        stopAutoTurn();
        String direction = getString(rightToLeft
                ? R.string.reader_direction_rtl : R.string.reader_direction_ltr);
        String mode = getString(continuousMode
                ? R.string.reader_mode_continuous : R.string.reader_mode_paged);
        String crop = getString(autoCrop ? R.string.reader_setting_on : R.string.reader_setting_off);
        new AlertDialog.Builder(this).setTitle(R.string.reader_settings)
                .setItems(new String[]{
                        getString(R.string.reader_mode_setting, mode),
                        getString(R.string.reader_crop_setting, crop),
                        getString(R.string.reader_direction_setting, direction),
                        getString(R.string.reader_bookmarks_setting, bookmarks.size()),
                        getString(R.string.reader_auto_turn_seconds),
                        getString(R.string.reader_help)
                }, (dialog, which) -> {
                    if (which == 0) {
                        continuousMode = !continuousMode;
                        applyReaderMode(false);
                    } else if (which == 1) {
                        autoCrop = !autoCrop;
                        settings.setAutoCrop(autoCrop);
                        reloadDecodedPages();
                    } else if (which == 2) {
                        rightToLeft = !rightToLeft;
                        settings.setRightToLeft(rightToLeft);
                        String changedDirection = getString(rightToLeft
                                ? R.string.reader_direction_rtl : R.string.reader_direction_ltr);
                        Toast.makeText(this, getString(R.string.reader_direction_changed, changedDirection),
                                Toast.LENGTH_SHORT).show();
                    } else if (which == 3) showBookmarks();
                    else if (which == 4) showAutoTurnDialog();
                    else {
                        new AlertDialog.Builder(this).setTitle(R.string.reader_operation_title)
                                .setMessage(continuousMode ? R.string.reader_operation_message_continuous
                                        : R.string.reader_operation_message)
                                .setPositiveButton(R.string.reader_got_it, null).show();
                    }
                }).setNegativeButton(R.string.reader_cancel, null).show();
    }

    private void showAutoTurnDialog() {
        stopAutoTurn();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        TextView label = text(getString(R.string.reader_auto_turn_seconds), 16,
                getColor(R.color.manga_text_primary), false);
        content.addView(label, lp(-1, -2));
        EditText seconds = new EditText(this);
        seconds.setId(View.generateViewId());
        label.setLabelFor(seconds.getId());
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setSingleLine(true);
        seconds.setMinHeight(dp(48));
        seconds.setText(String.valueOf(settings.autoTurnSeconds()));
        seconds.selectAll();
        content.addView(seconds, lp(-1, -2));
        TextView hint = text(getString(R.string.reader_auto_turn_hint), 14,
                getColor(R.color.manga_text_primary), false);
        hint.setMaxLines(Integer.MAX_VALUE);
        content.addView(hint, lp(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reader_auto_turn).setView(content)
                .setNegativeButton(R.string.reader_cancel, null)
                .setPositiveButton(R.string.reader_auto_turn_start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int interval = ReaderNavigation.parseAutoTurnSeconds(seconds.getText().toString());
                    if (interval == 0) {
                        seconds.setError(getString(R.string.reader_auto_turn_invalid));
                        return;
                    }
                    settings.setAutoTurnSeconds(interval);
                    dialog.dismiss();
                    startAutoTurn();
                }));
        dialog.show();
    }

    private void startAutoTurn() {
        if (!continuousMode && page >= comic.pageCount() - 1
                || continuousMode && restorePage < 0 && !continuousView.canScrollVertically(1)) {
            Toast.makeText(this, R.string.reader_auto_turn_last_page, Toast.LENGTH_SHORT).show();
            return;
        }
        autoTurning = true;
        autoTurnButton.setText(getString(R.string.reader_auto_turn_pause, settings.autoTurnSeconds()));
        autoTurnButton.setContentDescription(autoTurnButton.getText());
        if (chromeVisible) toggleChrome();
        continuousView.post(this::scheduleAutoTurn);
    }

    private boolean visiblePagesReady() {
        if (!continuousMode) {
            if (failedPages.contains(page)) stopAutoTurn();
            return pagedBitmap != null;
        }
        if (selectingContinuousPage || continuousView.getChildCount() == 0) return false;
        boolean ready = true;
        for (int child = 0; child < continuousView.getChildCount(); child++) {
            PageCell cell = (PageCell) continuousView.getChildAt(child);
            if (cell.getBottom() <= continuousView.getPaddingTop()
                    || cell.getTop() >= continuousView.getHeight() - continuousView.getPaddingBottom()) continue;
            if (failedPages.contains(cell.index)) { stopAutoTurn(); return false; }
            if (cell.bitmap == null) ready = false;
        }
        return ready;
    }

    private void scheduleAutoTurn() {
        if (!autoTurning || autoTurnScheduled || !visiblePagesReady()) return;
        autoTurnScheduled = true;
        autoTurnHandler.postDelayed(autoTurnTask, settings.autoTurnSeconds() * 1000L);
    }

    private void cancelAutoTurnTick() {
        autoTurnHandler.removeCallbacks(autoTurnTask);
        autoTurnScheduled = false;
    }

    private void stopAutoTurn() {
        autoTurning = false;
        cancelAutoTurnTick();
        if (autoTurnButton != null) {
            autoTurnButton.setText(R.string.reader_auto_turn_continue);
            autoTurnButton.setContentDescription(getString(R.string.reader_auto_turn_continue));
        }
    }

    @Override protected void onPause() {
        stopAutoTurn();
        saveContinuousPosition();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopAutoTurn();
    }

    private void reloadDecodedPages() {
        resetDecodedPages();
        continuousAdapter.notifyDataSetChanged();
        showPage(page);
    }

    private void resetDecodedPages() {
        decodeGeneration.incrementAndGet();
        worker.getQueue().clear();
        decodingPages.clear();
        pageView.setImageDrawable(null);
        pageCache.evictAll();
        pagedBitmap = null;
        pageSizes.clear();
        failedPages.clear();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                stopAutoTurn();
                showPage(page - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                stopAutoTurn();
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
        stopAutoTurn();
        saveContinuousPosition();
        destroyed = true;
        if (repository != null && comic != null) repository.saveProgress(comic.id, page);
        if (pageView != null) pageView.setImageDrawable(null);
        if (continuousView != null) continuousView.setAdapter(null);
        worker.shutdownNow();
        pageCache.evictAll();
        if (thumbnailLoader != null) thumbnailLoader.close();
        super.onDestroy();
    }
}
