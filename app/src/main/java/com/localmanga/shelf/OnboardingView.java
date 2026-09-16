package com.localmanga.shelf;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;

@SuppressLint("ViewConstructor")
public final class OnboardingView extends FrameLayout {
    public interface Listener { void onFinish(boolean importNow); }

    private final int orange;
    private final Listener listener;
    private final ImageView artwork;
    private final TextView title;
    private final TextView description;
    private final TextView dots;
    private final Button secondary;
    private final Button primary;
    private final float density;
    private int page;

    private final int[] images = {
            R.drawable.onboarding_import,
            R.drawable.onboarding_collection,
            R.drawable.onboarding_merge,
            R.drawable.onboarding_read,
            R.drawable.onboarding_secure
    };
    private final String[] titles = {
            "把漫画带进漫匣",
            "同类漫画，收入一个合集",
            "续集可以接在前作之后",
            "每次翻页都很自然",
            "只属于你的本地书架"
    };
    private final String[] descriptions = {
            "一次选择一个或多个 ZIP、CBZ 漫画包，漫匣会自动解压、排序并加入书架。",
            "新建合集文件夹，把同系列或同类型漫画放在一起；打开合集即可专注浏览。",
            "导入续集后，从它的菜单选择前作，即可把续集页面按顺序追加到前作末尾。",
            "点击左侧看上一页，点击右侧看下一页；中间区域用于显示或隐藏阅读菜单。",
            "漫画始终保存在本机，不会上传。你还可以设置应用密码，保护私人收藏。"
    };

    public OnboardingView(Context context, Listener listener) {
        super(context); this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        orange = getResources().getColor(R.color.manga_orange, context.getTheme());
        setBackgroundColor(getResources().getColor(R.color.manga_surface, context.getTheme()));

        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(22)); addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brandRow = new LinearLayout(context); brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brand = new ImageView(context); brand.setImageResource(R.drawable.app_cover);
        brand.setScaleType(ImageView.ScaleType.CENTER_CROP); brand.setBackground(round(Color.WHITE, 12));
        brand.setContentDescription(getResources().getString(R.string.app_name));
        brand.setClipToOutline(true); brandRow.addView(brand, lp(dp(42), dp(42)));
        TextView brandName = text("漫匣", 20, 0xFF24201D, true); brandName.setPadding(dp(11), 0, 0, 0);
        brandRow.addView(brandName, lp(-2, -2)); brandRow.setMinimumHeight(dp(50)); root.addView(brandRow, lp(-1, -2));

        artwork = new ImageView(context); artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(-1, 0, 1); art.setMargins(0, dp(8), 0, dp(6)); root.addView(artwork, art);
        title = text("", 28, 0xFF25211E, true); title.setGravity(Gravity.CENTER); title.setMinHeight(dp(48)); root.addView(title, lp(-1, -2));
        description = text("", 15, 0xFF746C66, false); description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.2f); description.setMinHeight(dp(72)); root.addView(description, lp(-1, -2));
        dots = text("", 14, orange, true); dots.setGravity(Gravity.CENTER); dots.setMinHeight(dp(42)); root.addView(dots, lp(-1, -2));

        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        secondary = button("跳过", 15, 0xFF7A716A); secondary.setContentDescription("跳过使用介绍");
        secondary.setOnClickListener(v -> listener.onFinish(false));
        primary = button("下一步", 16, Color.WHITE); primary.setContentDescription("下一页介绍");
        primary.setBackground(round(orange, 16)); primary.setElevation(dp(5));
        primary.setOnClickListener(v -> next());
        actions.addView(secondary, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams main = new LinearLayout.LayoutParams(0, dp(54), 1.55f); main.setMargins(dp(10), 0, 0, 0);
        actions.addView(primary, main); root.addView(actions, lp(-1, dp(58)));
        render();
    }

    private void next() {
        if (page < images.length - 1) {
            page++; render();
        } else listener.onFinish(true);
    }

    private void render() {
        artwork.setImageResource(images[page]);
        artwork.setContentDescription(titles[page]);
        title.setText(titles[page]); description.setText(descriptions[page]);
        StringBuilder indicator = new StringBuilder();
        for (int i = 0; i < images.length; i++) {
            if (i > 0) indicator.append("  ");
            indicator.append(i == page ? "●" : "○");
        }
        dots.setText(indicator.toString());
        secondary.setText(page == images.length - 1 ? "进入书架" : "跳过");
        primary.setText(page == images.length - 1 ? "导入第一本漫画" : "下一步");
        secondary.setContentDescription(page == images.length - 1 ? "进入书架" : "跳过使用介绍");
        primary.setContentDescription(page == images.length - 1 ? "导入第一本漫画" : "下一页介绍");
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create("sans", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        return view;
    }

    private Button button(String value, int size, int color) {
        Button button = new Button(getContext()); button.setText(value); button.setTextSize(size); button.setTextColor(color);
        button.setAllCaps(false); button.setGravity(Gravity.CENTER); button.setMinHeight(dp(48));
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(float value) { return Math.round(value * density); }
}
