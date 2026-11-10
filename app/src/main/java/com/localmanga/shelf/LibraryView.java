package com.localmanga.shelf;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
public final class LibraryView extends FrameLayout {
    public interface Actions {
        void importComic(); void openComic(Comic comic); void manageComic(Comic comic);
        void showInfo(); void createCollection(); void manageCollection(String name);
    }
    private final int orange;
    private final int textPrimary;
    private final int textSecondary;
    private final int screenPadding;
    private final int coverWidth;
    private final int coverHeight;
    private final int rowMinHeight;
    private final int fabSize;
    private final Actions actions;
    private final float density;
    private final LinearLayout content;
    private final LinearLayout stats;
    private final LinearLayout categories;
    private final LinearLayout collectionList;
    private final ListView list;
    private final ComicAdapter comicAdapter = new ComicAdapter();
    private final EditText search;
    private final Button clearSearch;
    private final Button resumeButton;
    private final Button statsToggle;
    private final SharedPreferences preferences;
    private final TextView filterSummary;
    private final CoverLoader coverLoader = new CoverLoader();
    private Button sortButton;
    private List<Comic> all = new ArrayList<>();
    private List<String> collectionNames = new ArrayList<>();
    private TextView comicsHeading;
    private String activeCollection = "";
    private long totalBytes;
    private int category;
    private int sortMode;
    private String query = "";
    private List<Comic> displayed = new ArrayList<>();

    public LibraryView(Context context, Actions actions) {
        super(context); this.actions = actions;
        preferences = context.getSharedPreferences("library_view_v1", Context.MODE_PRIVATE);
        sortMode = preferences.getInt("sortMode", 0);
        density = getResources().getDisplayMetrics().density;
        orange = getResources().getColor(R.color.manga_orange, context.getTheme());
        textPrimary = getResources().getColor(R.color.manga_text_primary, context.getTheme());
        textSecondary = getResources().getColor(R.color.manga_text_secondary, context.getTheme());
        screenPadding = getResources().getDimensionPixelSize(R.dimen.screen_horizontal_padding);
        coverWidth = getResources().getDimensionPixelSize(R.dimen.comic_cover_width);
        coverHeight = getResources().getDimensionPixelSize(R.dimen.comic_cover_height);
        rowMinHeight = getResources().getDimensionPixelSize(R.dimen.comic_row_min_height);
        fabSize = getResources().getDimensionPixelSize(R.dimen.floating_action_size);
        setBackgroundColor(getResources().getColor(R.color.manga_background, context.getTheme()));

        list = new ListView(context);
        list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(92));
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setItemsCanFocus(true);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = hero(); hero.setMinimumHeight(dp(120)); content.addView(hero, lp(-1, -2));
        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(round(Color.WHITE, 15));
        searchRow.setElevation(dp(2));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(textPrimary);
        search.setHintTextColor(0xFF7F6B60);
        search.setHint(R.string.library_search_hint);
        search.setContentDescription(context.getString(R.string.library_search_hint));
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(14), 0, 0, 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        clearSearch = button(context.getString(R.string.library_clear_search),
                context.getString(R.string.library_clear_search), 13, textSecondary);
        clearSearch.setEnabled(false);
        clearSearch.setOnClickListener(v -> search.setText(""));
        searchRow.addView(clearSearch, lp(dp(56), dp(48)));
        LinearLayout.LayoutParams sp = lp(-1, -2);
        sp.setMargins(0, dp(8), 0, dp(12)); hero.addView(searchRow, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                query = value.toString().trim();
                clearSearch.setEnabled(value.length() > 0);
                rebuildComics();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
            list.setSelection(1);
            return true;
        });

        resumeButton = button("", context.getString(R.string.library_resume), 15, textPrimary);
        resumeButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        resumeButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        resumeButton.setBackground(round(0xFFFFE6D0, 14));
        LinearLayout.LayoutParams resumeLayout = lp(-1, -2);
        resumeLayout.setMargins(dp(16), dp(8), dp(16), dp(8));
        content.addView(resumeButton, resumeLayout);

        statsToggle = button("", context.getString(R.string.library_stats_expand), 14, textSecondary);
        content.addView(statsToggle, lp(-1, dp(48)));
        stats = new LinearLayout(context); stats.setGravity(Gravity.CENTER); stats.setBackground(round(Color.WHITE, 20));
        stats.setMinimumHeight(dp(128));
        LinearLayout.LayoutParams statPos = lp(-1, -2);
        statPos.setMargins(dp(16), 0, dp(16), dp(12)); content.addView(stats, statPos);
        statsToggle.setOnClickListener(v -> {
            boolean expanded = stats.getVisibility() != VISIBLE;
            preferences.edit().putBoolean("statsExpanded", expanded).apply();
            updateStatsVisibility(expanded);
        });
        updateStatsVisibility(preferences.getBoolean("statsExpanded", false));

        addSectionTitle("智能分类", "", null);
        HorizontalScrollView categoryScroll = new HorizontalScrollView(context); categoryScroll.setHorizontalScrollBarEnabled(false);
        categories = new LinearLayout(context); categories.setPadding(dp(12), dp(8), dp(12), dp(10));
        categoryScroll.addView(categories); content.addView(categoryScroll, lp(-1, -2));

        addSectionTitle("我的合集", "新建 +", v -> actions.createCollection());
        HorizontalScrollView collectionScroll = new HorizontalScrollView(context); collectionScroll.setHorizontalScrollBarEnabled(false);
        collectionList = new LinearLayout(context); collectionList.setPadding(dp(12), dp(5), dp(12), dp(8));
        collectionScroll.addView(collectionList); content.addView(collectionScroll, lp(-1, -2));

        String[] sortNames = getResources().getStringArray(R.array.library_sort_choices);
        comicsHeading = addSectionTitle("我的漫画", getResources().getString(
                R.string.sort_label, sortNames[sortMode]), v -> showSortMenu());
        filterSummary = label("", 13, textSecondary, false);
        filterSummary.setMaxLines(4);
        filterSummary.setPadding(dp(20), 0, dp(20), dp(8));
        content.addView(filterSummary, lp(-1, -2));
        list.addHeaderView(content, null, false);
        list.setAdapter(comicAdapter);
        addView(list, match());
        buildFab(); refresh();
    }

    public void setLibrary(List<Comic> comics, long bytes, List<String> collections) {
        all = comics; totalBytes = bytes; collectionNames = collections;
        if (!activeCollection.isEmpty() && !collectionNames.contains(activeCollection)) activeCollection = "";
        refresh();
    }
    public void close() { coverLoader.close(); }

    private LinearLayout hero() {
        LinearLayout hero = new LinearLayout(getContext()); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(screenPadding, dp(12), screenPadding, 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFFCF8, 0xFFFFE7D2, 0xFFFF9A4A});
        hero.setBackground(bg);
        LinearLayout titleRow = new LinearLayout(getContext()); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("我的漫画 ✦", 27, 0xFF2C241F, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(43), 1));
        ImageView brand = new ImageView(getContext()); brand.setImageResource(R.drawable.app_cover);
        brand.setScaleType(ImageView.ScaleType.CENTER_CROP); brand.setBackground(round(0x22FFFFFF, 12));
        brand.setClipToOutline(true); brand.setContentDescription(getResources().getString(R.string.brand_cover_description));
        LinearLayout.LayoutParams brandPos = lp(dp(43), dp(43)); brandPos.setMargins(0, 0, dp(5), 0);
        titleRow.addView(brand, brandPos);
        Button info = button("⋮", "打开应用菜单", 26, 0xFF4A3325); info.setOnClickListener(v -> actions.showInfo());
        titleRow.addView(info, lp(dp(48), dp(48))); titleRow.setMinimumHeight(dp(48)); hero.addView(titleRow, lp(-1, -2));
        return hero;
    }

    private TextView addSectionTitle(String left, String right, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        TextView heading = label(left, 18, textPrimary, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView more;
        if (click != null) {
            Button action = button(right, right, 13, 0xFF8B8693);
            action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); action.setOnClickListener(click);
            sortButton = action; more = action;
        } else {
            more = label(right, 13, 0xFF8B8693, false); more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        }
        row.addView(more, lp(dp(136), -2)); row.setMinimumHeight(dp(42)); content.addView(row, lp(-1, -2)); return heading;
    }

    private void refresh() {
        if (stats == null) return;
        updateResumeButton();
        stats.removeAllViews();
        int read = 0; for (Comic c : all) read += c.pagesRead();
        addStat("▣", String.valueOf(all.size()), "漫画数量", orange);
        addStat("▤", String.valueOf(read), "已读页数", 0xFFFF9B45);
        addStat("◆", formatBytes(totalBytes), "占用空间", 0xFFE85D0B);
        addStat("✓", "本地", "存储方式", 0xFFF2A45E);
        rebuildCategories(); rebuildCollections(); rebuildComics();
    }

    private void updateStatsVisibility(boolean expanded) {
        stats.setVisibility(expanded ? VISIBLE : GONE);
        statsToggle.setText(expanded ? R.string.library_stats_collapse : R.string.library_stats_expand);
        statsToggle.setContentDescription(statsToggle.getText());
    }

    private void updateResumeButton() {
        Comic recent = null;
        for (Comic comic : all) {
            if (comic.lastRead > 0 && (recent == null || comic.lastRead > recent.lastRead)) recent = comic;
        }
        resumeButton.setVisibility(recent == null ? GONE : VISIBLE);
        if (recent == null) return;
        Comic target = recent;
        String title = getResources().getString(R.string.library_resume_comic,
                target.title, target.progress + 1, target.pageCount());
        resumeButton.setText(title);
        resumeButton.setContentDescription(title);
        resumeButton.setOnClickListener(v -> actions.openComic(target));
    }

    private void rebuildCollections() {
        collectionList.removeAllViews();
        collectionList.addView(collectionCard("", "全部漫画", all.size()), collectionParams());
        for (String name : collectionNames) {
            int count = 0; for (Comic comic : all) if (comic.collection.equals(name)) count++;
            collectionList.addView(collectionCard(name, name, count), collectionParams());
        }
    }

    private View collectionCard(String key, String name, int count) {
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(7), dp(5), dp(7), dp(5));
        card.setBackground(round(activeCollection.equals(key) ? 0xFFFFE6D0 : Color.WHITE, 14));
        TextView nameView = center(name, 12, 0xFF3A302A, true); nameView.setMaxLines(2); card.addView(nameView, lp(-1, -2));
        card.addView(center(count + (key.isEmpty() ? " 本" : " 本 · 长按"), 9, 0xFF9A7B67, false), lp(-1, -2));
        card.setOnClickListener(v -> { activeCollection = key; refresh(); });
        card.setFocusable(true); card.setMinimumHeight(dp(64));
        card.setContentDescription(getResources().getString(key.isEmpty()
                ? R.string.collection_description : R.string.collection_manage_description, name, count));
        if (!key.isEmpty()) card.setOnLongClickListener(v -> { actions.manageCollection(key); return true; });
        return card;
    }

    private LinearLayout.LayoutParams collectionParams() {
        LinearLayout.LayoutParams params = lp(dp(96), -2); params.setMargins(dp(4), 0, dp(4), 0); return params;
    }

    private void addStat(String icon, String value, String caption, int color) {
        LinearLayout item = new LinearLayout(getContext()); item.setGravity(Gravity.CENTER); item.setOrientation(LinearLayout.VERTICAL);
        TextView i = label(icon, 17, color, true); i.setGravity(Gravity.CENTER); i.setBackground(round((color & 0x00FFFFFF) | 0x18000000, 24));
        item.setPadding(dp(2), dp(10), dp(2), dp(10)); item.setMinimumHeight(dp(128));
        item.addView(i, lp(dp(42), dp(42))); item.addView(center(value, 17, 0xFF1D1A22, true), lp(-1, -2));
        item.addView(center(caption, 11, 0xFF7B7683, false), lp(-1, -2));
        stats.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void rebuildCategories() {
        categories.removeAllViews();
        String[] names = getResources().getStringArray(R.array.library_category_choices);
        for (int n = 0; n < names.length; n++) {
            final int index = n;
            int count = categoryCount(n);
            Button chip = button(getResources().getString(R.string.library_category_count, names[n], count),
                    getResources().getString(R.string.category_description, names[n], count), 14, textPrimary);
            chip.setBackground(round(category == n ? 0xFFFFE9D6 : Color.WHITE, 14));
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setSelected(category == n);
            chip.setOnClickListener(v -> { category = index; refresh(); });
            LinearLayout.LayoutParams pos = lp(-2, dp(48));
            pos.setMargins(dp(4), 0, dp(4), 0);
            categories.addView(chip, pos);
        }
    }

    private int categoryCount(int kind) {
        int count = 0;
        for (Comic c : all) if ((activeCollection.isEmpty() || c.collection.equals(activeCollection)) &&
                (kind == 0 || (kind == 1 && c.lastRead > 0) || (kind == 2 && c.isUnread()) ||
                        (kind == 3 && c.favorite) || (kind == 4 && c.isComplete()))) count++;
        return count;
    }

    private List<Comic> filtered() {
        List<Comic> out = new ArrayList<>(); String q = query.toLowerCase(Locale.ROOT);
        for (Comic c : all) {
            boolean match = q.isEmpty() || c.title.toLowerCase(Locale.ROOT).contains(q);
            boolean inCollection = activeCollection.isEmpty() || c.collection.equals(activeCollection);
            boolean type = category == 0 || (category == 1 && c.lastRead > 0) ||
                    (category == 2 && c.isUnread()) || (category == 3 && c.favorite) || (category == 4 && c.isComplete());
            if (match && type && inCollection) out.add(c);
        }
        if (sortMode == 0) out.sort((a, b) -> Long.compare(Math.max(b.lastRead, b.importedAt), Math.max(a.lastRead, a.importedAt)));
        else if (sortMode == 1) out.sort((a, b) -> java.text.Collator.getInstance(Locale.CHINA).compare(a.title, b.title));
        else out.sort((a, b) -> Float.compare(readRatio(b), readRatio(a)));
        return out;
    }

    private float readRatio(Comic comic) {
        return comic.pageCount() == 0 ? 0f : comic.pagesRead() / (float) comic.pageCount();
    }

    private void showSortMenu() {
        String[] choices = getResources().getStringArray(R.array.library_sort_choices);
        new AlertDialog.Builder(getContext()).setTitle("书架排序")
                .setSingleChoiceItems(choices, sortMode, (dialog, which) -> {
                    sortMode = which; preferences.edit().putInt("sortMode", which).apply();
                    sortButton.setText(getResources().getString(R.string.sort_label, choices[which]));
                    rebuildComics(); dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void rebuildComics() {
        displayed = filtered();
        String[] categoryNames = getResources().getStringArray(R.array.library_category_choices);
        filterSummary.setText(getResources().getString(R.string.library_filter_summary,
                query.isEmpty() ? getResources().getString(R.string.library_no_keyword) : query,
                categoryNames[category], activeCollection.isEmpty()
                        ? getResources().getString(R.string.library_all_collections) : activeCollection,
                displayed.size()));
        comicsHeading.setText(activeCollection.isEmpty() ? "我的漫画" : "合集 · " + activeCollection);
        comicAdapter.notifyDataSetChanged();
    }

    private View createComicRow() {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(7), dp(9)); row.setBackground(round(Color.WHITE, 16));
        ImageView cover = new ImageView(getContext()); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(0xFFFFEBDD, 10)); row.addView(cover, lp(coverWidth, coverHeight));

        LinearLayout words = new LinearLayout(getContext()); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(14), 0, dp(8), 0);
        TextView title = label("", 16, 0xFF211E29, true); words.addView(title, lp(-1, -2));
        TextView status = label("", 12, 0xFF7F7A86, false); words.addView(status, lp(-1, -2));
        LinearLayout progress = new LinearLayout(getContext()); progress.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(orange));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF1E1D5));
        progress.addView(bar, new LinearLayout.LayoutParams(0, dp(4), 1));
        TextView count = label("", 11, 0xFF817C88, false);
        progress.addView(count, lp(dp(64), dp(28))); words.addView(progress, lp(-1, dp(32)));
        row.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        Button menu = button("⋮", "管理漫画", 24, 0xFF8A8590);
        row.addView(menu, lp(dp(48), -1));
        row.setTag(new ComicRow(cover, title, status, bar, count, menu));
        return row;
    }

    private void bindComicRow(View view, Comic comic) {
        ComicRow row = (ComicRow) view.getTag();
        coverLoader.load(comic.cover(), row.cover, coverWidth, coverHeight);
        row.cover.setContentDescription(getResources().getString(R.string.comic_cover_description, comic.title));
        row.title.setText(comic.favorite ? getResources().getString(R.string.comic_title_favorite, comic.title) : comic.title);
        String status = comic.isUnread() ? "尚未阅读" : "上次读到第 " + (comic.progress + 1) + " 页";
        if (!comic.collection.isEmpty()) status = "合集 · " + comic.collection + "  ·  " + status;
        row.status.setText(status);
        int read = comic.pagesRead();
        row.progress.setProgress(comic.pageCount() == 0 ? 0 : Math.round(read * 100f / comic.pageCount()));
        row.count.setText(getResources().getString(R.string.comic_progress_count, read, comic.pageCount()));
        row.menu.setContentDescription(getResources().getString(R.string.comic_manage_description, comic.title));
        row.menu.setOnClickListener(v -> actions.manageComic(comic));
        view.setContentDescription(getResources().getString(R.string.comic_row_description, comic.title, status));
        view.setOnClickListener(v -> actions.openComic(comic));
    }

    private final class ComicAdapter extends BaseAdapter {
        @Override public int getCount() { return displayed.isEmpty() ? 1 : displayed.size(); }
        @Override public Object getItem(int position) { return displayed.isEmpty() ? null : displayed.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return displayed.isEmpty() ? 0 : 1; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (displayed.isEmpty()) {
                LinearLayout empty = new LinearLayout(getContext());
                empty.setOrientation(LinearLayout.VERTICAL);
                empty.setGravity(Gravity.CENTER);
                TextView message = center(getResources().getString(all.isEmpty()
                        ? R.string.library_empty : R.string.library_no_results), 15, textSecondary, false);
                message.setMaxLines(4);
                empty.addView(message, lp(-1, -2));
                if (!all.isEmpty()) {
                    Button reset = button(getResources().getString(R.string.library_reset_filters),
                            getResources().getString(R.string.library_reset_filters), 14, orange);
                    reset.setOnClickListener(v -> {
                        category = 0;
                        activeCollection = "";
                        search.setText("");
                        refresh();
                    });
                    empty.addView(reset, lp(-1, dp(48)));
                }
                empty.setLayoutParams(new ListView.LayoutParams(-1, dp(160)));
                return empty;
            }
            View row = recycled == null ? createComicRow() : recycled;
            bindComicRow(row, displayed.get(position));
            row.setPadding(dp(24), dp(9), dp(21), dp(9));
            row.setMinimumHeight(rowMinHeight);
            return row;
        }
    }

    private static final class ComicRow {
        final ImageView cover;
        final TextView title;
        final TextView status;
        final ProgressBar progress;
        final TextView count;
        final Button menu;

        ComicRow(ImageView cover, TextView title, TextView status, ProgressBar progress, TextView count, Button menu) {
            this.cover = cover; this.title = title; this.status = status;
            this.progress = progress; this.count = count; this.menu = menu;
        }
    }

    private void buildFab() {
        Button fab = button("+", "导入漫画", 32, Color.WHITE); fab.setBackground(round(orange, 32));
        fab.setElevation(dp(10)); fab.setOnClickListener(v -> actions.importComic());
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(fabSize, fabSize, Gravity.END | Gravity.BOTTOM);
        pos.setMargins(0, 0, dp(22), dp(22)); addView(fab, pos);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(android.graphics.Typeface.create("sans", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMaxLines(2); v.setEllipsize(android.text.TextUtils.TruncateAt.END); return v;
    }
    private TextView center(String text, int size, int color, boolean bold) {
        TextView v = label(text, size, color, bold); v.setGravity(Gravity.CENTER); return v;
    }
    private Button button(String text, String description, int size, int color) {
        Button button = new Button(getContext()); button.setText(text); button.setTextSize(size); button.setTextColor(color);
        button.setAllCaps(false); button.setGravity(Gravity.CENTER); button.setPadding(0, 0, 0, 0);
        button.setMinWidth(dp(48)); button.setMinHeight(dp(48)); button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description); return button;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }
    private int dp(float value) { return Math.round(value * density); }
    private String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return bytes / 1024 + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576f);
        return String.format(Locale.CHINA, "%.1f GB", bytes / 1073741824f);
    }
}
