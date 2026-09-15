package com.localmanga.shelf;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LibraryView extends FrameLayout {
    public interface Actions {
        void importComic(); void openComic(Comic comic); void manageComic(Comic comic);
        void search(); void showInfo(); void createCollection(); void manageCollection(String name);
    }
    private static final int ORANGE = 0xFFF47B20;
    private final Actions actions;
    private final float density;
    private final LinearLayout content;
    private final LinearLayout stats;
    private final LinearLayout categories;
    private final LinearLayout collectionList;
    private final LinearLayout comicList;
    private final TextView search;
    private TextView sortButton;
    private List<Comic> all = new ArrayList<>();
    private List<String> collectionNames = new ArrayList<>();
    private TextView comicsHeading;
    private String activeCollection = "";
    private long totalBytes;
    private int category;
    private int sortMode;
    private String query = "";

    public LibraryView(Context context, Actions actions) {
        super(context); this.actions = actions;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFFFFFAF5);

        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false); scroll.setPadding(0, 0, 0, dp(92));
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); addView(scroll, match());

        LinearLayout hero = hero(); content.addView(hero, lp(-1, dp(198)));
        search = label("⌕   搜索漫画名称", 14, 0xFF8A6650, false);
        search.setGravity(Gravity.CENTER_VERTICAL); search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(Color.WHITE, 15)); search.setElevation(dp(2)); search.setOnClickListener(v -> actions.search());
        LinearLayout.LayoutParams sp = lp(-1, dp(50)); sp.setMargins(dp(20), dp(12), dp(20), 0); hero.addView(search, sp);

        stats = new LinearLayout(context); stats.setGravity(Gravity.CENTER); stats.setBackground(round(Color.WHITE, 20));
        LinearLayout.LayoutParams statPos = lp(-1, dp(128)); statPos.setMargins(dp(16), dp(-21), dp(16), dp(18)); content.addView(stats, statPos);

        addSectionTitle("智能分类", "", null);
        HorizontalScrollView categoryScroll = new HorizontalScrollView(context); categoryScroll.setHorizontalScrollBarEnabled(false);
        categories = new LinearLayout(context); categories.setPadding(dp(12), dp(8), dp(12), dp(10));
        categoryScroll.addView(categories); content.addView(categoryScroll, lp(-1, dp(112)));

        addSectionTitle("我的合集", "新建 +", v -> actions.createCollection());
        HorizontalScrollView collectionScroll = new HorizontalScrollView(context); collectionScroll.setHorizontalScrollBarEnabled(false);
        collectionList = new LinearLayout(context); collectionList.setPadding(dp(12), dp(5), dp(12), dp(8));
        collectionScroll.addView(collectionList); content.addView(collectionScroll, lp(-1, dp(106)));

        comicsHeading = addSectionTitle("我的漫画", "最近更新⌄", v -> showSortMenu());
        comicList = new LinearLayout(context); comicList.setOrientation(LinearLayout.VERTICAL);
        comicList.setPadding(dp(14), dp(4), dp(14), dp(20)); content.addView(comicList, lp(-1, -2));
        buildFab(); refresh();
    }

    public void setLibrary(List<Comic> comics, long bytes, List<String> collections) {
        all = comics; totalBytes = bytes; collectionNames = collections;
        if (!activeCollection.isEmpty() && !collectionNames.contains(activeCollection)) activeCollection = "";
        refresh();
    }
    public void setQuery(String value) { query = value == null ? "" : value.trim(); refresh(); }

    private LinearLayout hero() {
        LinearLayout hero = new LinearLayout(getContext()); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(25), dp(22), 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFFCF8, 0xFFFFE7D2, 0xFFFF9A4A});
        hero.setBackground(bg);
        LinearLayout titleRow = new LinearLayout(getContext()); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("我的漫画 ✦", 27, 0xFF2C241F, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(43), 1));
        ImageView brand = new ImageView(getContext()); brand.setImageResource(R.drawable.app_cover);
        brand.setScaleType(ImageView.ScaleType.CENTER_CROP); brand.setBackground(round(0x22FFFFFF, 12));
        brand.setClipToOutline(true); brand.setContentDescription("漫匣封面");
        LinearLayout.LayoutParams brandPos = lp(dp(43), dp(43)); brandPos.setMargins(0, 0, dp(5), 0);
        titleRow.addView(brand, brandPos);
        TextView info = label("⋮", 29, 0xFF4A3325, true); info.setGravity(Gravity.CENTER); info.setOnClickListener(v -> actions.showInfo());
        titleRow.addView(info, lp(dp(44), dp(44))); hero.addView(titleRow, lp(-1, dp(48)));
        hero.addView(label("珍藏每一段精彩的故事", 15, 0xFF8A5B3D, false), lp(-1, dp(28)));
        return hero;
    }

    private TextView addSectionTitle(String left, String right, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);
        TextView heading = label(left, 18, 0xFF24212B, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView more = label(right, 13, 0xFF8B8693, false); more.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (click != null) { more.setOnClickListener(click); sortButton = more; }
        row.addView(more, lp(dp(126), dp(34))); content.addView(row, lp(-1, dp(42))); return heading;
    }

    private void refresh() {
        if (stats == null) return;
        search.setText(query.isEmpty() ? "⌕   搜索漫画名称" : "⌕   搜索：“" + query + "”");
        stats.removeAllViews();
        int read = 0; for (Comic c : all) read += Math.min(c.pageCount(), c.progress + (c.lastRead > 0 ? 1 : 0));
        addStat("▣", String.valueOf(all.size()), "漫画数量", 0xFFF47B20);
        addStat("▤", String.valueOf(read), "已读页数", 0xFFFF9B45);
        addStat("◆", formatBytes(totalBytes), "占用空间", 0xFFE85D0B);
        addStat("✓", "本地", "存储方式", 0xFFF2A45E);
        rebuildCategories(); rebuildCollections(); rebuildComics();
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
        TextView icon = center("▰", 23, ORANGE, true); card.addView(icon, lp(-1, dp(34)));
        TextView nameView = center(name, 12, 0xFF3A302A, true); nameView.setMaxLines(1); card.addView(nameView, lp(-1, dp(22)));
        card.addView(center(count + (key.isEmpty() ? " 本" : " 本 · 长按"), 9, 0xFF9A7B67, false), lp(-1, dp(18)));
        card.setOnClickListener(v -> { activeCollection = key; refresh(); });
        if (!key.isEmpty()) card.setOnLongClickListener(v -> { actions.manageCollection(key); return true; });
        return card;
    }

    private LinearLayout.LayoutParams collectionParams() {
        LinearLayout.LayoutParams params = lp(dp(96), dp(88)); params.setMargins(dp(4), 0, dp(4), 0); return params;
    }

    private void addStat(String icon, String value, String caption, int color) {
        LinearLayout item = new LinearLayout(getContext()); item.setGravity(Gravity.CENTER); item.setOrientation(LinearLayout.VERTICAL);
        TextView i = label(icon, 17, color, true); i.setGravity(Gravity.CENTER); i.setBackground(round((color & 0x00FFFFFF) | 0x18000000, 24));
        item.addView(i, lp(dp(42), dp(42))); item.addView(center(value, 17, 0xFF1D1A22, true), lp(-1, dp(30)));
        item.addView(center(caption, 11, 0xFF7B7683, false), lp(-1, dp(22)));
        stats.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void rebuildCategories() {
        categories.removeAllViews();
        String[] names = {"全部", "最近阅读", "未读", "收藏", "读完"};
        String[] icons = {"▰", "◷", "●", "★", "✓"};
        int[] colors = {0xFFF47B20, 0xFFFF9B45, 0xFFE85D0B, 0xFFF2A45E, 0xFFD94A12};
        for (int n = 0; n < names.length; n++) {
            final int index = n;
            LinearLayout box = new LinearLayout(getContext()); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
            box.setBackground(round(category == n ? 0xFFFFE9D6 : Color.WHITE, 14));
            TextView icon = center(icons[n], 16, colors[n], true); icon.setBackground(round((colors[n] & 0x00FFFFFF) | 0x16000000, 22));
            box.addView(icon, lp(dp(38), dp(38))); box.addView(center(names[n], 12, 0xFF39353F, true), lp(-1, dp(25)));
            box.addView(center(String.valueOf(categoryCount(n)), 11, 0xFF8C8792, false), lp(-1, dp(18)));
            box.setOnClickListener(v -> { category = index; refresh(); });
            LinearLayout.LayoutParams pos = lp(dp(92), dp(92)); pos.setMargins(dp(4), 0, dp(4), 0); categories.addView(box, pos);
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
        return comic.pageCount() == 0 ? 0f : (comic.progress + (comic.lastRead > 0 ? 1 : 0)) / (float) comic.pageCount();
    }

    private void showSortMenu() {
        String[] choices = {"最近更新", "漫画名称", "阅读进度"};
        new AlertDialog.Builder(getContext()).setTitle("书架排序")
                .setSingleChoiceItems(choices, sortMode, (dialog, which) -> {
                    sortMode = which; sortButton.setText(choices[which] + "⌄");
                    rebuildComics(); dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void rebuildComics() {
        comicList.removeAllViews(); List<Comic> items = filtered();
        comicsHeading.setText(activeCollection.isEmpty() ? "我的漫画" : "合集 · " + activeCollection);
        if (items.isEmpty()) {
            String message = all.isEmpty() ? "书架还是空的\n点击右下角 + 导入漫画" :
                    (!activeCollection.isEmpty() ? "这个合集还是空的\n从漫画右侧 ⋮ 加入合集" : "没有匹配的漫画");
            TextView empty = center(message, 15, 0xFF756F80, false);
            empty.setGravity(Gravity.CENTER); comicList.addView(empty, lp(-1, dp(150))); return;
        }
        for (Comic comic : items) comicList.addView(comicRow(comic), rowParams());
    }

    private View comicRow(Comic comic) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(7), dp(9)); row.setBackground(round(Color.WHITE, 16));
        ImageView cover = new ImageView(getContext()); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (comic.cover() != null) cover.setImageURI(android.net.Uri.fromFile(comic.cover()));
        cover.setBackground(round(0xFFFFEBDD, 10)); row.addView(cover, lp(dp(68), dp(96)));

        LinearLayout words = new LinearLayout(getContext()); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(14), 0, dp(8), 0);
        words.addView(label(comic.title + (comic.favorite ? "  ★" : ""), 16, 0xFF211E29, true), lp(-1, dp(31)));
        String status = comic.isUnread() ? "尚未阅读" : "上次读到第 " + (comic.progress + 1) + " 页";
        if (!comic.collection.isEmpty()) status = "合集 · " + comic.collection + "  ·  " + status;
        words.addView(label(status, 12, 0xFF7F7A86, false), lp(-1, dp(25)));
        LinearLayout progress = new LinearLayout(getContext()); progress.setGravity(Gravity.CENTER_VERTICAL);
        TextView bar = new TextView(getContext()); bar.setBackground(progressDrawable(comic));
        progress.addView(bar, new LinearLayout.LayoutParams(0, dp(4), 1));
        TextView count = label("  " + (comic.progress + 1) + "/" + comic.pageCount(), 11, 0xFF817C88, false);
        progress.addView(count, lp(dp(64), dp(28))); words.addView(progress, lp(-1, dp(32)));
        row.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        TextView menu = center("⋮", 25, 0xFF8A8590, true); menu.setOnClickListener(v -> actions.manageComic(comic));
        row.addView(menu, lp(dp(42), -1)); row.setOnClickListener(v -> actions.openComic(comic));
        return row;
    }

    private android.graphics.drawable.Drawable progressDrawable(Comic comic) {
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                round(0xFFF1E1D5, 3), round(ORANGE, 3)});
        int percent = comic.pageCount() == 0 ? 0 : Math.min(100, Math.round((comic.progress + 1) * 100f / comic.pageCount()));
        layers.setLayerInset(1, 0, 0, dp((100 - percent) * 1.8f), 0); return layers;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams pos = lp(-1, dp(116)); pos.setMargins(0, 0, 0, dp(10)); return pos;
    }

    private void buildFab() {
        TextView fab = center("+", 34, Color.WHITE, false); fab.setGravity(Gravity.CENTER); fab.setBackground(round(ORANGE, 32));
        fab.setElevation(dp(10)); fab.setOnClickListener(v -> actions.importComic());
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.RIGHT | Gravity.BOTTOM);
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
