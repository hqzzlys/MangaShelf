package com.localmanga.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements LibraryView.Actions, LibraryTaskController.Listener {
    private static final int PICK_ARCHIVE = 40;
    private static final int CREATE_BACKUP = 41;
    private static final int RESTORE_BACKUP = 42;
    private LibraryTaskController tasks;
    private ComicRepository repository;
    private LibraryView library;
    private AppLock appLock;
    private FrameLayout contentHost;
    private FrameLayout taskOverlay;
    private TextView taskTitle;
    private TextView taskMessage;
    private Button cancelTask;
    private boolean firstResume = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(getColor(R.color.manga_orange));
        getWindow().setNavigationBarColor(getColor(R.color.manga_surface));
        Object retained = getLastNonConfigurationInstance();
        tasks = retained instanceof LibraryTaskController
                ? (LibraryTaskController) retained : new LibraryTaskController(this);
        repository = tasks.repository();
        appLock = new AppLock(this);
        library = new LibraryView(this, this);
        buildRoot();
        tasks.refresh();
        if (appLock.isEnabled() && !AppLock.isSessionUnlocked()) {
            library.setVisibility(View.INVISIBLE);
            showUnlockDialog();
        } else {
            AppLock.unlockSession(); showStartDestination();
        }
    }

    private void buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.manga_background));
        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(-1, -1));

        taskOverlay = new FrameLayout(this);
        taskOverlay.setBackgroundColor(0x66000000);
        taskOverlay.setClickable(true);
        taskOverlay.setFocusable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xFFFFFCF8);
        panelBackground.setCornerRadius(dp(18));
        panel.setBackground(panelBackground);
        taskTitle = new TextView(this);
        taskTitle.setTextSize(18);
        taskTitle.setTextColor(0xFF24212B);
        taskTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(taskTitle, new LinearLayout.LayoutParams(-1, -2));
        taskMessage = new TextView(this);
        taskMessage.setTextSize(14);
        taskMessage.setTextColor(0xFF6F625A);
        LinearLayout.LayoutParams messageLayout = new LinearLayout.LayoutParams(-1, -2);
        messageLayout.setMargins(0, dp(8), 0, dp(10));
        panel.addView(taskMessage, messageLayout);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        panel.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        cancelTask = new Button(this);
        cancelTask.setText("取消操作");
        cancelTask.setOnClickListener(view -> tasks.cancel());
        LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(-2, dp(48));
        cancelLayout.gravity = Gravity.END;
        cancelLayout.setMargins(0, dp(10), 0, 0);
        panel.addView(cancelTask, cancelLayout);
        FrameLayout.LayoutParams panelLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        panelLayout.setMargins(dp(24), 0, dp(24), 0);
        taskOverlay.addView(panel, panelLayout);
        taskOverlay.setVisibility(View.GONE);
        root.addView(taskOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showPage(library);
        WindowInsetsHelper.enableEdgeToEdge(getWindow(), root);
    }

    private void showPage(View page) {
        if (page.getParent() == contentHost) return;
        if (page.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) page.getParent()).removeView(page);
        }
        contentHost.removeAllViews();
        contentHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showStartDestination() {
        boolean complete = getSharedPreferences("onboarding_v3", MODE_PRIVATE).getBoolean("complete", false);
        if (complete) { library.setVisibility(View.VISIBLE); showPage(library); }
        else showOnboarding();
    }

    private void showOnboarding() {
        showPage(new OnboardingView(this, importNow -> {
            getSharedPreferences("onboarding_v3", MODE_PRIVATE).edit().putBoolean("complete", true).apply();
            library.setVisibility(View.VISIBLE); showPage(library);
            if (importNow) library.post(this::importComic);
        }));
    }

    @Override protected void onStart() { super.onStart(); tasks.attach(this); }
    @Override protected void onStop() { tasks.detach(this); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        if (firstResume) firstResume = false;
        else tasks.refresh();
    }
    @Override public Object onRetainNonConfigurationInstance() { return tasks; }
    @Override protected void onDestroy() {
        if (library != null) library.close();
        if (!isChangingConfigurations() && tasks != null) tasks.close();
        super.onDestroy();
    }

    private void reload() { tasks.refresh(); }

    @Override public void importComic() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/zip");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(pick, PICK_ARCHIVE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null && data.getClipData() == null) return;
        if (code == CREATE_BACKUP && data.getData() != null) {
            tasks.exportBackup(data.getData());
            return;
        }
        if (code == RESTORE_BACKUP && data.getData() != null) {
            tasks.restoreBackup(data.getData());
            return;
        }
        if (code != PICK_ARCHIVE) return;
        List<Uri> selected = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) selected.add(clips.getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        if (!selected.isEmpty()) tasks.importArchives(selected);
    }

    @Override public void openComic(Comic comic) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("comic_id", comic.id);
        startActivity(intent);
    }

    @Override public void manageComic(Comic comic) {
        String favorite = comic.favorite ? "取消收藏" : "加入收藏";
        new AlertDialog.Builder(this).setTitle(comic.title)
                .setItems(new String[]{favorite, comic.collection.isEmpty() ? "加入合集" : "更改合集 · " + comic.collection,
                        "合并到另一漫画", "重命名", "移入回收站"}, (d, which) -> {
                    if (which == 0) { repository.setFavorite(comic, !comic.favorite); reload(); }
                    else if (which == 1) chooseCollection(comic);
                    else if (which == 2) chooseMergeTarget(comic);
                    else if (which == 3) renameComic(comic);
                    else confirmDelete(comic);
                }).show();
    }

    private void chooseCollection(Comic comic) {
        List<String> collections = repository.loadCollections();
        String[] choices = new String[collections.size() + 2];
        choices[0] = "＋ 新建合集"; choices[1] = "不放入合集";
        for (int i = 0; i < collections.size(); i++) choices[i + 2] = "▰  " + collections.get(i);
        new AlertDialog.Builder(this).setTitle("将《" + comic.title + "》放入")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) promptCollectionName("新建合集", "", name -> {
                        if (repository.addCollection(name)) { repository.setCollection(comic, name.trim()); reload(); return true; }
                        return false;
                    });
                    else if (which == 1) { repository.setCollection(comic, ""); reload(); }
                    else { repository.setCollection(comic, collections.get(which - 2)); reload(); }
                }).setNegativeButton("取消", null).show();
    }

    private void chooseMergeTarget(Comic sequel) {
        List<Comic> targets = repository.loadAll();
        targets.removeIf(item -> item.id.equals(sequel.id));
        if (targets.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("没有可合并的前作")
                    .setMessage("请先导入前作，再从续集的菜单发起合并。")
                    .setPositiveButton("知道了", null).show(); return;
        }
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) labels[i] = targets.get(i).title + "  ·  " + targets.get(i).pageCount() + " 页";
        new AlertDialog.Builder(this).setTitle("选择要追加到的前作")
                .setItems(labels, (dialog, which) -> confirmMerge(targets.get(which), sequel))
                .setNegativeButton("取消", null).show();
    }

    private void confirmMerge(Comic target, Comic sequel) {
        new AlertDialog.Builder(this).setTitle("确认漫画顺序")
                .setMessage("前作：《" + target.title + "》 · " + target.pageCount() + " 页\n" +
                        "续集：《" + sequel.title + "》 · " + sequel.pageCount() + " 页\n\n" +
                        "续集页面会追加到前作末尾。合并完成后会保留续集原条目供核对，确认无误后可自行删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始合并", (dialog, which) -> mergeInBackground(target, sequel)).show();
    }

    private void mergeInBackground(Comic target, Comic sequel) {
        tasks.merge(target, sequel);
    }

    private void renameComic(Comic comic) {
        EditText input = textField(null);
        input.setText(comic.title); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("重命名").setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    repository.rename(comic, input.getText().toString()); reload();
                }).show();
    }

    private void confirmDelete(Comic comic) {
        new AlertDialog.Builder(this).setTitle(getString(R.string.comic_move_to_trash_title, comic.title))
                .setMessage(R.string.comic_move_to_trash_message)
                .setNegativeButton("取消", null)
                .setPositiveButton(R.string.comic_move_to_trash, (d, w) -> tasks.delete(comic)).show();
    }

    @Override public void createCollection() {
        promptCollectionName("新建合集", "", name -> {
            if (!repository.addCollection(name)) return false;
            reload(); return true;
        });
    }

    @Override public void manageCollection(String name) {
        new AlertDialog.Builder(this).setTitle(name)
                .setItems(new String[]{"重命名合集", "删除合集"}, (dialog, which) -> {
                    if (which == 0) promptCollectionName("重命名合集", name, changed -> {
                        if (!repository.renameCollection(name, changed)) return false;
                        reload(); return true;
                    });
                    else new AlertDialog.Builder(this).setTitle("删除合集“" + name + "”？")
                            .setMessage("合集中的漫画会回到全部漫画，不会删除任何图片或阅读进度。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (d, w) -> { repository.deleteCollection(name); reload(); }).show();
                }).show();
    }

    private interface NameAction { boolean apply(String name); }

    private void promptCollectionName(String title, String current, NameAction action) {
        EditText input = textField("合集名称"); input.setText(current); input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入合集名称"); input.requestFocus(); return; }
            if (!action.apply(name)) { input.setError("该合集名称已存在"); input.requestFocus(); return; }
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showUnlockDialog() {
        EditText input = passwordField("请输入应用密码");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("漫匣已锁定")
                .setMessage("验证密码后才能进入书架。")
                .setView(input).setPositiveButton("解锁", null)
                .setNegativeButton("退出", (d, w) -> finishAffinity()).create();
        dialog.setCancelable(false);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (appLock.verify(input.getText().toString())) {
                AppLock.unlockSession(); input.setError(null);
                dialog.dismiss(); showStartDestination();
            } else {
                input.setText(""); input.setError("密码不正确"); input.requestFocus();
            }
        }));
        dialog.show();
    }

    private EditText passwordField(String hint) {
        EditText input = textField(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private EditText textField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint); input.setTextSize(16); input.setSingleLine(true);
        input.setIncludeFontPadding(true); input.setMinHeight(dp(54));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFBF7); background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xFFE4B28D); input.setBackground(background);
        input.setPadding(dp(15), dp(11), dp(15), dp(11)); return input;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void search() {
        EditText input = textField("漫画名称");
        new AlertDialog.Builder(this).setTitle("搜索书架").setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除", (d, w) -> library.setQuery(""))
                .setPositiveButton("搜索", (d, w) -> library.setQuery(input.getText().toString())).show();
    }

    @Override public void showInfo() {
        new AlertDialog.Builder(this).setTitle("漫匣")
                .setItems(new String[]{"密码设置", "书架备份与恢复", getString(R.string.trash_menu), "功能介绍", "关于本软件"}, (dialog, which) -> {
                    if (which == 0) showPasswordSettings();
                    else if (which == 1) showBackupOptions();
                    else if (which == 2) showTrash();
                    else if (which == 3) showOnboarding(); else showAbout();
                }).show();
    }

    private void showTrash() {
        List<Comic> trashed = repository.loadTrash();
        if (trashed.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.trash_title)
                    .setMessage(R.string.trash_empty_message)
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] labels = new String[trashed.size()];
        for (int index = 0; index < trashed.size(); index++) {
            Comic comic = trashed.get(index);
            labels[index] = getString(R.string.trash_comic_label, comic.title, comic.pageCount());
        }
        new AlertDialog.Builder(this).setTitle(R.string.trash_title)
                .setItems(labels, (dialog, which) -> manageTrashedComic(trashed.get(which)))
                .setNeutralButton(R.string.trash_empty, (dialog, which) -> confirmEmptyTrash())
                .setNegativeButton("取消", null).show();
    }

    private void manageTrashedComic(Comic comic) {
        new AlertDialog.Builder(this).setTitle(comic.title)
                .setItems(new String[]{getString(R.string.trash_restore), getString(R.string.trash_delete_forever)},
                        (dialog, which) -> {
                            if (which == 0) tasks.restoreTrash(comic);
                            else confirmPermanentDelete(comic);
                        })
                .setNegativeButton("取消", null).show();
    }

    private void confirmPermanentDelete(Comic comic) {
        new AlertDialog.Builder(this).setTitle(getString(R.string.trash_delete_title, comic.title))
                .setMessage(R.string.trash_delete_message)
                .setNegativeButton("取消", null)
                .setPositiveButton(R.string.trash_delete_forever,
                        (dialog, which) -> tasks.permanentlyDeleteTrash(comic)).show();
    }

    private void confirmEmptyTrash() {
        new AlertDialog.Builder(this).setTitle(R.string.trash_empty_title)
                .setMessage(R.string.trash_empty_confirm)
                .setNegativeButton("取消", null)
                .setPositiveButton(R.string.trash_empty, (dialog, which) -> tasks.emptyTrash()).show();
    }

    private void showBackupOptions() {
        new AlertDialog.Builder(this).setTitle("书架备份与恢复")
                .setItems(new String[]{"导出完整书架备份", "从备份恢复书架"}, (dialog, which) -> {
                    if (which == 0) confirmCreateBackup();
                    else confirmRestoreBackup();
                })
                .setNegativeButton("取消", null).show();
    }

    private void confirmCreateBackup() {
        new AlertDialog.Builder(this).setTitle("导出完整书架备份？")
                .setMessage("备份包含漫画图片、标题、合集、收藏、阅读进度和书签，不包含应用密码。备份文件未加密，请保存到安全位置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择保存位置", (dialog, which) -> createBackupDocument()).show();
    }

    private void createBackupDocument() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "MangaShelf-backup-" + timestamp + ".zip");
        startActivityForResult(intent, CREATE_BACKUP);
    }

    private void confirmRestoreBackup() {
        new AlertDialog.Builder(this).setTitle("从备份恢复书架？")
                .setMessage("备份中的漫画会新增到当前书架，不会覆盖已有漫画。重复恢复同一备份会产生副本；应用密码不会恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择备份文件", (dialog, which) -> pickBackupDocument()).show();
    }

    private void pickBackupDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        startActivityForResult(intent, RESTORE_BACKUP);
    }

    @Override public void onTaskState(LibraryTaskController.State state) {
        taskTitle.setText(state.title);
        taskMessage.setText(state.message);
        cancelTask.setVisibility(state.cancellable ? View.VISIBLE : View.GONE);
        taskOverlay.setVisibility(state.running ? View.VISIBLE : View.GONE);
        taskOverlay.announceForAccessibility(state.running ? state.title + "。" + state.message : "操作完成");
    }

    @Override public void onLibrarySnapshot(LibraryTaskController.Snapshot snapshot) {
        library.setLibrary(snapshot.comics, snapshot.bytes, snapshot.collections);
    }

    @Override public void onTaskEvent(LibraryTaskController.Event event) {
        if (event.toast) {
            Toast.makeText(this, event.message, Toast.LENGTH_LONG).show();
        } else {
            new AlertDialog.Builder(this).setTitle(event.title).setMessage(event.message)
                    .setPositiveButton("知道了", null).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("漫匣 · 本地漫画")
                .setMessage("支持一次选择多个 ZIP / CBZ 漫画包。导入后图片会安全解压到应用私有目录，全程离线，不申请存储权限，也不会上传任何内容。\n\n点击漫画开始阅读；点击右侧 ⋮ 可收藏、重命名或删除。右上角菜单可导出或恢复完整书架备份。")
                .setPositiveButton("知道了", null).show();
    }

    private void showPasswordSettings() {
        if (!appLock.isEnabled()) {
            showNewPasswordDialog("设置应用密码"); return;
        }
        new AlertDialog.Builder(this).setTitle("密码保护已开启")
                .setItems(new String[]{"修改密码", "关闭密码保护"}, (dialog, which) ->
                        verifyCurrentPassword(() -> {
                            if (which == 0) showNewPasswordDialog("修改应用密码");
                            else {
                                appLock.disable();
                                Toast.makeText(this, "密码保护已关闭", Toast.LENGTH_SHORT).show();
                            }
                        })).setNegativeButton("取消", null).show();
    }

    private void verifyCurrentPassword(Runnable success) {
        EditText input = passwordField("输入当前密码");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("验证当前密码")
                .setView(input).setPositiveButton("验证", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (appLock.verify(input.getText().toString())) {
                dialog.dismiss(); success.run();
            } else {
                input.setText(""); input.setError("密码不正确"); input.requestFocus();
            }
        }));
        dialog.show();
    }

    private void showNewPasswordDialog(String title) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(0, dp(6), 0, 0);
        EditText password = passwordField("输入新密码（至少 4 位）");
        EditText confirm = passwordField("再次输入新密码");
        LinearLayout.LayoutParams firstFieldLayout = new LinearLayout.LayoutParams(-1, -2);
        firstFieldLayout.setMargins(0, 0, 0, dp(12));
        fields.addView(password, firstFieldLayout);
        fields.addView(confirm, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
                .setMessage("密码仅保存在本机。忘记密码后只能清除应用数据或重新安装。")
                .setView(fields).setPositiveButton("保存", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String first = password.getText().toString();
            String second = confirm.getText().toString();
            if (first.length() < 4) { password.setError("密码至少需要 4 位"); password.requestFocus(); return; }
            if (!first.equals(second)) { confirm.setError("两次输入的密码不一致"); confirm.requestFocus(); return; }
            appLock.setPassword(first); AppLock.unlockSession(); dialog.dismiss();
            Toast.makeText(this, "密码保护已开启，下次打开应用时生效", Toast.LENGTH_LONG).show();
        }));
        dialog.show();
    }
}
