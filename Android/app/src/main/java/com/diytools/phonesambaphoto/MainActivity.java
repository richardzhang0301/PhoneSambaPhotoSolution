package com.diytools.phonesambaphoto;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMAGES = 1001;
    private static final int REQUEST_DELETE_SELECTED = 1002;

    private enum Tab {
        LOCAL,
        REMOTE
    }

    private interface DateRangeAction {
        void onDateRangeSelected(long startMillis, long endMillis);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    private final List<PhotoItem> allPhotos = new ArrayList<>();
    private final List<PhotoItem> photos = new ArrayList<>();
    private final List<RemotePhotoItem> remotePhotos = new ArrayList<>();
    private final List<PhotoItem> pendingDeleteItems = new ArrayList<>();

    private ThumbLoader thumbLoader;
    private RemoteThumbLoader remoteThumbLoader;
    private PhotoGridAdapter adapter;
    private RemotePhotoGridAdapter remoteAdapter;
    private GridView localGrid;
    private GridView remoteGrid;
    private LinearLayout selectionActionRow;
    private LinearLayout selectionRowsColumn;
    private LinearLayout buttonRow;
    private LinearLayout noSyncRow;
    private LinearLayout localActionRow;
    private TextView status;
    private TextView destination;
    private ProgressBar progress;
    private Button localTabButton;
    private Button remoteTabButton;
    private Button syncAllButton;
    private Button deleteSyncedButton;
    private Button uploadSelectedButton;
    private Button noSyncButton;
    private Button reEnableSyncButton;
    private Button deleteSelectedButton;
    private Button cancelSelectionButton;
    private Tab selectedTab = Tab.LOCAL;
    private boolean uploading;
    private boolean deleting;
    private boolean retryDeleteAfterPermission;
    private boolean localLoaded;
    private boolean remoteLoaded;
    private boolean selectionMode;
    private String localLoadedIdentity = "";
    private String remoteLoadedIdentity = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        thumbLoader = new ThumbLoader(this);
        remoteThumbLoader = new RemoteThumbLoader(this);
        setContentView(createContentView());

        adapter = new PhotoGridAdapter(this, photos, thumbLoader);
        localGrid.setAdapter(adapter);
        localGrid.setOnItemClickListener((parent, view, position, id) -> {
            PhotoItem item = photos.get(position);
            if (selectionMode) {
                toggleLocalSelection(item);
            } else {
                RemoteMediaViewerActivity.openLocal(this, photos, position);
            }
        });
        localGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            enterSelectionMode(photos.get(position));
            return true;
        });

        SambaSettings settings = SambaSettings.load(this);
        remoteAdapter = new RemotePhotoGridAdapter(this, remotePhotos, remoteThumbLoader, settings);
        remoteGrid.setAdapter(remoteAdapter);
        remoteGrid.setOnItemClickListener((parent, view, position, id) -> {
            RemotePhotoItem item = remotePhotos.get(position);
            if (selectionMode) {
                toggleRemoteSelection(item);
            } else {
                setStatus(item.name + "  " + sizeLabel(item.size));
                RemoteMediaViewerActivity.openRemote(this, remotePhotos, position);
            }
        });
        remoteGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            enterRemoteSelectionMode(remotePhotos.get(position));
            return true;
        });

        selectTab(Tab.LOCAL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!RemoteMediaViewerActivity.consumeMediaChanged()) {
            return;
        }
        remoteLoaded = false;
        if (remoteThumbLoader != null) {
            remoteThumbLoader.clear();
        }
        if (selectedTab == Tab.REMOTE) {
            loadRemotePhotos();
        } else {
            localLoaded = false;
            loadPhotos();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanExecutor.shutdownNow();
        remoteExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        if (thumbLoader != null) {
            thumbLoader.shutdown();
        }
        if (remoteThumbLoader != null) {
            remoteThumbLoader.shutdown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_IMAGES && hasPhotoPermission()) {
            loadPhotos();
        } else if (selectedTab == Tab.LOCAL) {
            setStatus(t("Media permission is needed to show the gallery", "需要媒体权限才能显示图库"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DELETE_SELECTED) {
            return;
        }
        if (resultCode != RESULT_OK) {
            finishDeleteSelected(false, 0, t("Delete cancelled", "已取消删除"));
            return;
        }
        if (retryDeleteAfterPermission) {
            retryDeleteAfterPermission = false;
            deleteSelectedDirect(new ArrayList<>(pendingDeleteItems));
            return;
        }
        finishDeleteSelected(true, pendingDeleteItems.size(), deletedText(pendingDeleteItems.size()));
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.surface));
        applyMainSafeArea(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(10), dp(8), dp(8));
        toolbar.setBackgroundColor(getColorCompat(R.color.panel));

        TextView title = new TextView(this);
        title.setText(t("Photos", "照片"));
        title.setTextColor(getColorCompat(R.color.ink));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        ImageButton refreshButton = iconButton(R.drawable.ic_refresh, t("Refresh", "刷新"));
        refreshButton.setOnClickListener(v -> refreshCurrentTab());
        toolbar.addView(refreshButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageButton settingsButton = iconButton(R.drawable.ic_settings, t("Samba folder", "Samba 文件夹"));
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        toolbar.addView(settingsButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(12), 0, dp(12), dp(8));
        tabs.setBackgroundColor(getColorCompat(R.color.panel));

        localTabButton = tabButton(t("Local", "本地"));
        localTabButton.setOnClickListener(v -> selectTab(Tab.LOCAL));
        LinearLayout.LayoutParams localTabParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        localTabParams.setMargins(0, 0, dp(8), 0);
        tabs.addView(localTabButton, localTabParams);

        remoteTabButton = tabButton("Samba");
        remoteTabButton.setOnClickListener(v -> selectTab(Tab.REMOTE));
        tabs.addView(remoteTabButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(12), dp(8), dp(12), dp(10));
        actions.setBackgroundColor(getColorCompat(R.color.panel));

        LinearLayout destinationRow = new LinearLayout(this);
        destinationRow.setGravity(Gravity.CENTER_VERTICAL);

        destination = new TextView(this);
        destination.setTextColor(getColorCompat(R.color.muted));
        destination.setTextSize(13);
        destination.setSingleLine(true);
        destination.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        destinationRow.addView(destination, new LinearLayout.LayoutParams(0, dp(32), 1));
        actions.addView(destinationRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        localActionRow = new LinearLayout(this);
        localActionRow.setGravity(Gravity.CENTER_VERTICAL);

        syncAllButton = primaryButton(t("Sync all", "全部同步"), R.drawable.ic_sync);
        syncAllButton.setOnClickListener(v -> syncAll());
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        syncParams.setMargins(0, 0, dp(8), 0);
        localActionRow.addView(syncAllButton, syncParams);

        deleteSyncedButton = destructiveButton(t("Delete all synced", "删除已同步"));
        deleteSyncedButton.setOnClickListener(v -> confirmDeleteSynced());
        localActionRow.addView(deleteSyncedButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(localActionRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        selectionActionRow = new LinearLayout(this);
        selectionActionRow.setGravity(Gravity.CENTER_VERTICAL);

        selectionRowsColumn = new LinearLayout(this);
        selectionRowsColumn.setOrientation(LinearLayout.VERTICAL);

        buttonRow = new LinearLayout(this);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        uploadSelectedButton = secondaryButton(t("Upload selected", "上传所选"), R.drawable.ic_upload);
        uploadSelectedButton.setOnClickListener(v -> uploadSelected());
        LinearLayout.LayoutParams uploadParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        uploadParams.setMargins(0, 0, dp(8), 0);
        buttonRow.addView(uploadSelectedButton, uploadParams);

        deleteSelectedButton = destructiveButton(t("Delete", "删除"));
        deleteSelectedButton.setOnClickListener(v -> confirmDeleteSelected());
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        buttonRow.addView(deleteSelectedButton, deleteParams);

        cancelSelectionButton = secondaryButton(t("Done", "完成"), R.drawable.ic_close);
        cancelSelectionButton.setOnClickListener(v -> exitSelectionMode());
        selectionRowsColumn.addView(buttonRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        noSyncRow = new LinearLayout(this);
        noSyncRow.setGravity(Gravity.CENTER_VERTICAL);

        noSyncButton = grayButton(t("No Sync", "不同步"));
        noSyncButton.setOnClickListener(v -> markSelectedNoSync());
        LinearLayout.LayoutParams noSyncParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        noSyncParams.setMargins(0, 0, dp(8), 0);
        noSyncRow.addView(noSyncButton, noSyncParams);

        reEnableSyncButton = successButton(t("Re-enable Sync", "重新启用同步"));
        reEnableSyncButton.setOnClickListener(v -> reEnableSelectedSync());
        noSyncRow.addView(reEnableSyncButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        selectionRowsColumn.addView(noSyncRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout.LayoutParams selectionRowsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        selectionRowsParams.setMargins(0, 0, dp(8), 0);
        selectionActionRow.addView(selectionRowsColumn, selectionRowsParams);
        selectionActionRow.addView(cancelSelectionButton, new LinearLayout.LayoutParams(0, dp(92), 1));
        actions.addView(selectionActionRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(getColorCompat(R.color.muted));
        status.setTextSize(13);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        actions.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        actions.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);
        localGrid = photoGrid();
        remoteGrid = photoGrid();
        remoteGrid.setVisibility(View.GONE);
        content.addView(localGrid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(remoteGrid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        updateDestinationLabel();
        updateButtons();
        setStatus(t("Ready", "就绪"));
        return root;
    }

    private void applyMainSafeArea(View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int safeLeft;
            int safeTop;
            int safeRight;
            int safeBottom;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safeInsets = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                safeLeft = safeInsets.left;
                safeTop = safeInsets.top;
                safeRight = safeInsets.right;
                safeBottom = safeInsets.bottom;
            } else {
                safeLeft = insets.getSystemWindowInsetLeft();
                safeTop = insets.getSystemWindowInsetTop();
                safeRight = insets.getSystemWindowInsetRight();
                safeBottom = insets.getSystemWindowInsetBottom();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        safeLeft = Math.max(safeLeft, cutout.getSafeInsetLeft());
                        safeTop = Math.max(safeTop, cutout.getSafeInsetTop());
                        safeRight = Math.max(safeRight, cutout.getSafeInsetRight());
                        safeBottom = Math.max(safeBottom, cutout.getSafeInsetBottom());
                    }
                }
            }

            view.setPadding(baseLeft + safeLeft, baseTop + safeTop, baseRight + safeRight, baseBottom + safeBottom);
            return insets;
        });
        root.post(root::requestApplyInsets);
    }

    private GridView photoGrid() {
        GridView grid = new GridView(this);
        grid.setBackgroundColor(getColorCompat(R.color.surface));
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(4), dp(4), dp(12));
        grid.setHorizontalSpacing(dp(3));
        grid.setVerticalSpacing(dp(3));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(118));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setGravity(Gravity.CENTER);
        return grid;
    }

    private void selectTab(Tab tab) {
        boolean changedTabs = tab != selectedTab;
        if (changedTabs && selectionMode) {
            selectionMode = false;
            clearLocalSelection();
            clearRemoteSelection();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (remoteAdapter != null) {
                remoteAdapter.notifyDataSetChanged();
            }
        }
        selectedTab = tab;
        if (localGrid != null) {
            localGrid.setVisibility(tab == Tab.LOCAL ? View.VISIBLE : View.GONE);
        }
        if (remoteGrid != null) {
            remoteGrid.setVisibility(tab == Tab.REMOTE ? View.VISIBLE : View.GONE);
        }
        updateDestinationLabel();
        updateButtons();

        SambaSettings settings = SambaSettings.load(this);
        if (tab == Tab.LOCAL) {
            if (!localLoaded || !settings.identityKey().equals(localLoadedIdentity)) {
                if (hasPhotoPermission()) {
                    loadPhotos();
                } else {
                    requestPhotoPermission();
                }
            } else {
                setStatus(localMediaStatus(photos.size()));
            }
        } else {
            if (!settings.isConfigured()) {
                remotePhotos.clear();
                if (remoteAdapter != null) {
                    remoteAdapter.notifyDataSetChanged();
                }
                setStatus(t("Set Samba folder to view remote media files", "请设置 Samba 文件夹以查看远程媒体"));
                showSettingsDialog();
                return;
            }
            if (!remoteLoaded || !settings.identityKey().equals(remoteLoadedIdentity)) {
                loadRemotePhotos();
            } else {
                setStatus(remoteMediaStatus(remotePhotos.size()));
            }
        }
    }

    private void refreshCurrentTab() {
        if (selectionMode) {
            exitSelectionMode();
        }
        if (selectedTab == Tab.REMOTE) {
            remoteLoaded = false;
            remoteThumbLoader.clear();
            loadRemotePhotos();
        } else {
            exitSelectionMode();
            localLoaded = false;
            loadPhotos();
        }
    }

    private void loadPhotos() {
        if (!hasPhotoPermission()) {
            requestPhotoPermission();
            return;
        }
        if (selectedTab == Tab.LOCAL) {
            setStatus(t("Scanning media", "正在扫描媒体"));
        }
        scanExecutor.execute(() -> {
            SambaSettings settings = SambaSettings.load(getApplicationContext());
            List<PhotoItem> loaded = PhotoRepository.loadPhotos(getApplicationContext(), settings);
            main.post(() -> {
                selectionMode = false;
                allPhotos.clear();
                allPhotos.addAll(loaded);
                localLoaded = true;
                localLoadedIdentity = settings.identityKey();
                applyCachedSambaExists(settings);
                applyLocalFilters();
                refreshLocalSambaExists(settings);
                if (selectedTab == Tab.LOCAL) {
                    setStatus(localMediaStatus(photos.size()));
                }
                updateDestinationLabel();
                updateButtons();
            });
        });
    }

    private void applyLocalFilters() {
        photos.clear();
        photos.addAll(allPhotos);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void loadRemotePhotos() {
        SambaSettings settings = SambaSettings.load(this);
        updateDestinationLabel();
        if (remoteAdapter != null) {
            remoteAdapter.setSettings(settings);
        }
        if (!settings.isConfigured()) {
            selectionMode = false;
            clearRemoteSelection();
            remotePhotos.clear();
            remoteLoaded = false;
            clearSambaExists();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (remoteAdapter != null) {
                remoteAdapter.notifyDataSetChanged();
            }
            setStatus(t("Set Samba folder to view remote media files", "请设置 Samba 文件夹以查看远程媒体"));
            return;
        }

        selectionMode = false;
        clearRemoteSelection();
        if (remoteAdapter != null) {
            remoteAdapter.notifyDataSetChanged();
        }
        updateButtons();
        setStatus(t("Scanning Samba folder", "正在扫描 Samba 文件夹"));
        remoteExecutor.execute(() -> {
            try {
                List<RemotePhotoItem> loaded = RemotePhotoRepository.loadPhotos(settings);
                main.post(() -> {
                    remotePhotos.clear();
                    remotePhotos.addAll(loaded);
                    remoteLoaded = true;
                    remoteLoadedIdentity = settings.identityKey();
                    remoteAdapter.setSettings(settings);
                    applySambaExists(loaded);
                    remoteAdapter.notifyDataSetChanged();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (selectedTab == Tab.REMOTE) {
                        setStatus(remoteMediaStatus(loaded.size()));
                    }
                    updateButtons();
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    remotePhotos.clear();
                    remoteLoaded = false;
                    clearSambaExists();
                    remoteAdapter.notifyDataSetChanged();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    if (selectedTab == Tab.REMOTE) {
                        setStatus(t("Cannot read Samba folder", "无法读取 Samba 文件夹"));
                    }
                    updateButtons();
                });
            }
        });
    }

    private void refreshLocalSambaExists(SambaSettings settings) {
        if (!settings.isConfigured()) {
            clearSambaExists();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        String identity = settings.identityKey();
        if (remoteLoaded && identity.equals(remoteLoadedIdentity)) {
            applySambaExists(remotePhotos);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        remoteExecutor.execute(() -> {
            try {
                List<RemotePhotoItem> loaded = RemotePhotoRepository.loadPhotos(settings);
                main.post(() -> {
                    if (!identity.equals(SambaSettings.load(this).identityKey())) {
                        return;
                    }
                    remotePhotos.clear();
                    remotePhotos.addAll(loaded);
                    remoteLoaded = true;
                    remoteLoadedIdentity = identity;
                    if (remoteAdapter != null) {
                        remoteAdapter.setSettings(settings);
                        remoteAdapter.notifyDataSetChanged();
                    }
                    applySambaExists(loaded);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    updateButtons();
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if (!identity.equals(SambaSettings.load(this).identityKey())) {
                        return;
                    }
                    clearSambaExists();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private void applyCachedSambaExists(SambaSettings settings) {
        if (settings.isConfigured() && remoteLoaded && settings.identityKey().equals(remoteLoadedIdentity)) {
            applySambaExists(remotePhotos);
        } else {
            clearSambaExists();
        }
    }

    private void applySambaExists(List<RemotePhotoItem> remoteItems) {
        Set<String> remoteKeys = new HashSet<>();
        for (RemotePhotoItem remote : remoteItems) {
            remoteKeys.add(sambaMatchKey(remote.name, remote.size, remote.video));
        }
        for (PhotoItem photo : allPhotos) {
            photo.sambaExists = photo.uploaded || remoteKeys.contains(sambaMatchKey(photo.name, photo.size, photo.video));
        }
    }

    private void clearSambaExists() {
        for (PhotoItem photo : allPhotos) {
            photo.sambaExists = photo.uploaded;
        }
    }

    private static String sambaMatchKey(String name, long size, boolean video) {
        String normalizedName = TextUtils.isEmpty(name) ? "" : name.toLowerCase(Locale.US);
        return (video ? "video" : "image") + "|" + normalizedName + "|" + size;
    }

    private void syncAll() {
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showSettingsDialog();
            return;
        }
        showSyncDateRangeDialog(settings);
    }

    private void showSyncDateRangeDialog(SambaSettings settings) {
        showDateRangeDialog(t("Sync date range", "同步日期范围"),
                (startMillis, endMillis) -> syncDateRange(settings, startMillis, endMillis));
    }

    private void showDeleteSyncedDateRangeDialog() {
        showDateRangeDialog(t("Delete synced date range", "删除已同步日期范围"),
                this::confirmDeleteSyncedRange);
    }

    private void showDateRangeDialog(String title, DateRangeAction action) {
        final int todayId = View.generateViewId();
        final int weekId = View.generateViewId();
        final int monthId = View.generateViewId();
        final int yearId = View.generateViewId();
        final int customId = View.generateViewId();
        final int allId = View.generateViewId();
        final Calendar[] startDate = {startOfToday()};
        final Calendar[] endDate = {endOfToday()};
        applySyncPreset(weekId, todayId, weekId, monthId, yearId, startDate, endDate);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, dp(8), pad, 0);

        RadioGroup presets = new RadioGroup(this);
        presets.setOrientation(RadioGroup.VERTICAL);
        presets.addView(rangeRadioButton(todayId, t("Today", "今天")));
        presets.addView(rangeRadioButton(weekId, t("Past Week", "过去一周")));
        presets.addView(rangeRadioButton(monthId, t("Past Month", "过去一个月")));
        presets.addView(rangeRadioButton(yearId, t("Past Year", "过去一年")));
        presets.addView(rangeRadioButton(customId, t("Custom Range", "自定义范围")));
        presets.addView(rangeRadioButton(allId, t("All", "全部")));
        form.addView(presets, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        Button startButton = dateButton("");
        Button endButton = dateButton("");
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        startParams.setMargins(0, dp(10), dp(8), 0);
        dateRow.addView(startButton, startParams);
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        endParams.setMargins(0, dp(10), 0, 0);
        dateRow.addView(endButton, endParams);
        form.addView(dateRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        TextView rangeMessage = new TextView(this);
        rangeMessage.setTextColor(Color.rgb(176, 43, 43));
        rangeMessage.setTextSize(13);
        rangeMessage.setSingleLine(false);
        form.addView(rangeMessage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        Runnable refreshDates = () -> {
            boolean all = presets.getCheckedRadioButtonId() == allId;
            startButton.setText(all ? t("Start  All", "开始  全部") : t("Start  ", "开始  ") + formatDate(startDate[0]));
            endButton.setText(all ? t("End  All", "结束  全部") : t("End  ", "结束  ") + formatDate(endDate[0]));
            boolean custom = presets.getCheckedRadioButtonId() == customId;
            startButton.setEnabled(custom);
            endButton.setEnabled(custom);
            rangeMessage.setText("");
        };
        startButton.setOnClickListener(v -> showDatePicker(startDate[0], false, refreshDates));
        endButton.setOnClickListener(v -> showDatePicker(endDate[0], true, refreshDates));
        presets.check(weekId);
        refreshDates.run();
        presets.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId != customId) {
                applySyncPreset(checkedId, todayId, weekId, monthId, yearId, startDate, endDate);
            }
            refreshDates.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(form)
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("OK", "确定"), null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                boolean all = presets.getCheckedRadioButtonId() == allId;
                long startMillis = all ? Long.MIN_VALUE : startDate[0].getTimeInMillis();
                long endMillis = all ? Long.MAX_VALUE : endDate[0].getTimeInMillis();
                if (startMillis > endMillis) {
                    rangeMessage.setText(t("Start date must be before end date", "开始日期必须早于结束日期"));
                    return;
                }
                dialog.dismiss();
                action.onDateRangeSelected(startMillis, endMillis);
            });
        });
        dialog.show();
    }

    private void applySyncPreset(int checkedId, int todayId, int weekId, int monthId, int yearId, Calendar[] startDate, Calendar[] endDate) {
        Calendar start = startOfToday();
        Calendar end = endOfToday();
        if (checkedId == weekId) {
            start.add(Calendar.DAY_OF_YEAR, -6);
        } else if (checkedId == monthId) {
            start.add(Calendar.MONTH, -1);
        } else if (checkedId == yearId) {
            start.add(Calendar.YEAR, -1);
        } else if (checkedId != todayId) {
            return;
        }
        startDate[0] = start;
        endDate[0] = end;
    }

    private void syncDateRange(SambaSettings settings, long startMillis, long endMillis) {
        List<PhotoItem> pending = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (!photo.noSync && !photo.uploaded && !photo.sambaExists && isInDateRange(photo, startMillis, endMillis)) {
                pending.add(photo);
            }
        }
        if (pending.isEmpty()) {
            setStatus(t("No media to sync in date range", "该日期范围内没有可同步的媒体"));
            return;
        }
        startUpload(settings, pending);
    }

    private boolean isInDateRange(PhotoItem photo, long startMillis, long endMillis) {
        long mediaTime = photo.sortTimeMillis();
        return mediaTime >= startMillis && mediaTime <= endMillis;
    }

    private Calendar startOfToday() {
        Calendar calendar = Calendar.getInstance();
        setStartOfDay(calendar);
        return calendar;
    }

    private Calendar endOfToday() {
        Calendar calendar = Calendar.getInstance();
        setEndOfDay(calendar);
        return calendar;
    }

    private void setStartOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private void setEndOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
    }

    private void showDatePicker(Calendar target, boolean endOfDay, Runnable onChanged) {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    target.set(Calendar.YEAR, year);
                    target.set(Calendar.MONTH, month);
                    target.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    if (endOfDay) {
                        setEndOfDay(target);
                    } else {
                        setStartOfDay(target);
                    }
                    onChanged.run();
                },
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private RadioButton rangeRadioButton(int id, String text) {
        RadioButton radioButton = new RadioButton(this);
        radioButton.setId(id);
        radioButton.setText(text);
        radioButton.setTextSize(14);
        radioButton.setTextColor(getColorCompat(R.color.ink));
        radioButton.setMinHeight(dp(36));
        return radioButton;
    }

    private Button dateButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(getColorCompat(R.color.ink));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.edit_text_bg);
        return button;
    }

    private String formatDate(Calendar calendar) {
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
        );
    }

    private void uploadSelected() {
        if (selectedTab != Tab.LOCAL || !selectionMode || deleting) {
            return;
        }
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showSettingsDialog();
            return;
        }
        List<PhotoItem> selected = selectedLocalItems();
        if (selected.isEmpty()) {
            setStatus(t("Select media to upload", "请选择要上传的媒体"));
            return;
        }
        startUpload(settings, selected);
    }

    private void markSelectedNoSync() {
        if (selectedTab != Tab.LOCAL || !selectionMode || uploading || deleting) {
            return;
        }
        List<PhotoItem> selected = selectedLocalItems();
        if (selected.isEmpty()) {
            setStatus(t("Select media to mark no sync", "请选择要标记为不同步的媒体"));
            return;
        }

        int marked = PhotoRepository.markNoSync(getApplicationContext(), selected);
        selectionMode = false;
        clearLocalSelection();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateButtons();
        setStatus(markedNoSyncText(marked));
    }

    private void reEnableSelectedSync() {
        if (selectedTab != Tab.LOCAL || !selectionMode || uploading || deleting) {
            return;
        }
        List<PhotoItem> selected = selectedLocalItems();
        if (selected.isEmpty()) {
            setStatus(t("Select media to re-enable sync", "请选择要重新启用同步的媒体"));
            return;
        }

        int cleared = PhotoRepository.clearNoSync(getApplicationContext(), selected);
        selectionMode = false;
        clearLocalSelection();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateButtons();
        setStatus(reEnabledSyncText(cleared));
    }

    private void confirmDeleteSelected() {
        if (!selectionMode || uploading || deleting) {
            return;
        }
        if (selectedTab == Tab.REMOTE) {
            confirmDeleteRemoteSelected();
            return;
        }
        List<PhotoItem> selected = selectedLocalItems();
        if (selected.isEmpty()) {
            setStatus(t("Select media to delete", "请选择要删除的媒体"));
            return;
        }

        String countLabel = selectedItemLabel(selected.size());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Delete selected?", "删除所选项目？"))
                .setMessage(isChinese()
                        ? "要从手机删除" + countLabel + "吗？此操作无法撤销。"
                        : "Delete " + countLabel + " from this phone? This cannot be undone.")
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("Delete", "删除"), (d, which) -> deleteSelected(selected))
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(190, 34, 34)));
        dialog.show();
    }

    private List<PhotoItem> selectedLocalItems() {
        List<PhotoItem> selected = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (photo.selected) {
                selected.add(photo);
            }
        }
        return selected;
    }

    private void confirmDeleteSynced() {
        if (uploading || deleting) {
            return;
        }
        if (syncedLocalItems().isEmpty()) {
            setStatus(t("No synced media to delete", "没有可删除的已同步媒体"));
            return;
        }
        showDeleteSyncedDateRangeDialog();
    }

    private void confirmDeleteSyncedRange(long startMillis, long endMillis) {
        if (uploading || deleting) {
            return;
        }
        List<PhotoItem> synced = syncedLocalItems(startMillis, endMillis);
        if (synced.isEmpty()) {
            setStatus(t("No synced media to delete in date range", "该日期范围内没有可删除的已同步媒体"));
            return;
        }
        String countLabel = syncedItemLabel(synced.size());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Delete synced media?", "删除已同步媒体？"))
                .setMessage(isChinese()
                        ? "要从手机删除" + countLabel + "吗？它们仍会保留在 Samba 中。此操作无法撤销。"
                        : "Delete " + countLabel + " from this phone? They will remain in Samba. This cannot be undone.")
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("Delete", "删除"), (d, which) -> deleteSelected(synced))
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(190, 34, 34)));
        dialog.show();
    }

    private List<PhotoItem> syncedLocalItems() {
        List<PhotoItem> synced = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (photo.sambaExists) {
                synced.add(photo);
            }
        }
        return synced;
    }

    private List<PhotoItem> syncedLocalItems(long startMillis, long endMillis) {
        List<PhotoItem> synced = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (photo.sambaExists && isInDateRange(photo, startMillis, endMillis)) {
                synced.add(photo);
            }
        }
        return synced;
    }

    private void confirmDeleteRemoteSelected() {
        List<RemotePhotoItem> selected = selectedRemoteItems();
        if (selected.isEmpty()) {
            setStatus(t("Select Samba media to delete", "请选择要从 Samba 删除的媒体"));
            return;
        }

        String countLabel = selectedItemLabel(selected.size());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Delete from Samba?", "从 Samba 删除？"))
                .setMessage(isChinese()
                        ? "要从 Samba 删除" + countLabel + "吗？此操作无法撤销。"
                        : "Delete " + countLabel + " from Samba? This cannot be undone.")
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("Delete", "删除"), (d, which) -> deleteRemoteSelected(selected))
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(190, 34, 34)));
        dialog.show();
    }

    private List<RemotePhotoItem> selectedRemoteItems() {
        List<RemotePhotoItem> selected = new ArrayList<>();
        for (RemotePhotoItem photo : remotePhotos) {
            if (photo.selected) {
                selected.add(photo);
            }
        }
        return selected;
    }

    private void deleteSelected(List<PhotoItem> selected) {
        if (selected.isEmpty()) {
            setStatus(t("Select media to delete", "请选择要删除的媒体"));
            return;
        }
        deleting = true;
        retryDeleteAfterPermission = false;
        pendingDeleteItems.clear();
        pendingDeleteItems.addAll(selected);
        progress.setIndeterminate(false);
        progress.setMax(Math.max(1, selected.size()));
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        setStatus(deletingText(selected.size()));
        updateButtons();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestDeleteSelectedPermission(selected);
        } else {
            deleteSelectedDirect(new ArrayList<>(selected));
        }
    }

    private void requestDeleteSelectedPermission(List<PhotoItem> selected) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (PhotoItem item : selected) {
            uris.add(item.uri);
        }
        try {
            PendingIntent deleteRequest = MediaStore.createDeleteRequest(getContentResolver(), uris);
            startIntentSenderForResult(deleteRequest.getIntentSender(), REQUEST_DELETE_SELECTED, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException exc) {
            finishDeleteSelected(false, 0, t("Delete not allowed", "不允许删除"));
        }
    }

    private void deleteSelectedDirect(List<PhotoItem> selected) {
        uploadExecutor.execute(() -> {
            int deleted = 0;
            for (int index = 0; index < selected.size(); index++) {
                PhotoItem item = selected.get(index);
                try {
                    if (getContentResolver().delete(item.uri, null, null) > 0) {
                        deleted++;
                    }
                    int done = index + 1;
                    main.post(() -> {
                        progress.setProgress(done);
                        setStatus(deletingProgressText(done, selected.size()));
                    });
                } catch (SecurityException exc) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exc instanceof RecoverableSecurityException) {
                        List<PhotoItem> remaining = new ArrayList<>();
                        for (int pendingIndex = index; pendingIndex < selected.size(); pendingIndex++) {
                            remaining.add(selected.get(pendingIndex));
                        }
                        int deletedSoFar = deleted;
                        main.post(() -> requestRecoverableDeletePermission((RecoverableSecurityException) exc, remaining, deletedSoFar));
                        return;
                    }
                }
            }
            int deletedTotal = deleted;
            main.post(() -> finishDeleteSelected(deletedTotal > 0, deletedTotal, deletedTotal == 0 ? t("Delete failed", "删除失败") : deletedText(deletedTotal)));
        });
    }

    private void deleteRemoteSelected(List<RemotePhotoItem> selected) {
        if (selected.isEmpty()) {
            setStatus(t("Select Samba media to delete", "请选择要从 Samba 删除的媒体"));
            return;
        }
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showSettingsDialog();
            return;
        }

        deleting = true;
        progress.setIndeterminate(false);
        progress.setMax(Math.max(1, selected.size()));
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        setStatus(deletingFromSambaText(selected.size()));
        updateButtons();

        remoteExecutor.execute(() -> {
            int deleted = 0;
            int failed = 0;
            try {
                CIFSContext context = SambaUploader.createContext(settings);
                int total = selected.size();
                for (int index = 0; index < total; index++) {
                    RemotePhotoItem item = selected.get(index);
                    try {
                        SmbFile file = new SmbFile(item.url, context);
                        if (file.exists()) {
                            file.delete();
                        }
                        deleteRemoteThumbnail(context, item);
                        deleted++;
                    } catch (Exception ignored) {
                        failed++;
                    }
                    int done = index + 1;
                    main.post(() -> {
                        progress.setProgress(done);
                        setStatus(deletingFromSambaProgressText(done, total));
                    });
                }
            } catch (Exception ignored) {
                failed = selected.size() - deleted;
            }
            int deletedTotal = deleted;
            int failedTotal = failed;
            main.post(() -> finishRemoteDelete(deletedTotal, failedTotal));
        });
    }

    private void deleteRemoteThumbnail(CIFSContext context, RemotePhotoItem item) {
        try {
            SambaThumbnailStore.deleteIfPresent(context, item.thumbnailUrl);
        } catch (Exception ignored) {
            // The media file is authoritative; stale thumbnail cleanup is best effort.
        }
    }

    private void finishRemoteDelete(int deletedCount, int failedCount) {
        deleting = false;
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
        selectionMode = false;
        clearRemoteSelection();
        if (remoteAdapter != null) {
            remoteAdapter.notifyDataSetChanged();
        }
        updateButtons();

        if (deletedCount > 0) {
            remoteLoaded = false;
            if (remoteThumbLoader != null) {
                remoteThumbLoader.clear();
            }
            String message = failedCount > 0
                    ? deletedFailedText(deletedCount, failedCount)
                    : deletedText(deletedCount);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            setStatus(message);
            loadRemotePhotos();
        } else {
            setStatus(failedCount > 0 ? t("Delete failed", "删除失败") : t("Nothing deleted", "未删除任何内容"));
        }
    }

    private void requestRecoverableDeletePermission(RecoverableSecurityException exc, List<PhotoItem> remaining, int deletedSoFar) {
        pendingDeleteItems.clear();
        pendingDeleteItems.addAll(remaining);
        retryDeleteAfterPermission = true;
        if (deletedSoFar > 0) {
            setStatus(deletedWaitingPermissionText(deletedSoFar));
        }
        try {
            PendingIntent actionIntent = exc.getUserAction().getActionIntent();
            startIntentSenderForResult(actionIntent.getIntentSender(), REQUEST_DELETE_SELECTED, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException sendExc) {
            finishDeleteSelected(deletedSoFar > 0, deletedSoFar, deletedSoFar > 0 ? deletedText(deletedSoFar) : t("Delete not allowed", "不允许删除"));
        }
    }

    private void finishDeleteSelected(boolean changed, int deletedCount, String message) {
        deleting = false;
        retryDeleteAfterPermission = false;
        pendingDeleteItems.clear();
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
        if (changed) {
            selectionMode = false;
            clearLocalSelection();
            localLoaded = false;
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            loadPhotos();
        } else {
            updateButtons();
            setStatus(message);
        }
    }

    private void startUpload(SambaSettings settings, List<PhotoItem> items) {
        if (uploading || deleting) {
            return;
        }
        uploading = true;
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        updateButtons();
        uploadExecutor.execute(() -> {
            SambaUploader.Summary summary = SambaUploader.upload(
                    getApplicationContext(),
                    settings,
                    items,
                    new SambaUploader.Listener() {
                        @Override
                        public void onProgress(int done, int total, String message) {
                            main.post(() -> {
                                progress.setMax(Math.max(1, total));
                                progress.setProgress(done);
                                setStatus(message);
                            });
                        }

                        @Override
                        public void onItemFinished(PhotoItem item) {
                            main.post(() -> {
                                item.selected = false;
                                item.noSync = false;
                                item.sambaExists = true;
                                adapter.notifyDataSetChanged();
                            });
                        }
                    });

            main.post(() -> {
                uploading = false;
                remoteLoaded = false;
                remoteThumbLoader.clear();
                progress.setVisibility(View.GONE);
                selectionMode = false;
                clearLocalSelection();
                adapter.notifyDataSetChanged();
                updateButtons();
                setStatus(uploadSummaryText(summary.uploaded, summary.skipped, summary.failed));
            });
        });
    }

    private void showSettingsDialog() {
        SambaSettings current = SambaSettings.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, dp(8), pad, 0);

        EditText host = field(t("Host or IP", "主机名或 IP"), current.host, false);
        EditText share = field(t("Share", "共享名"), current.share, false);
        EditText folder = field(t("Folder", "文件夹"), current.folder, false);
        EditText domain = field(t("Domain", "域"), current.domain, false);
        EditText username = field(t("Username", "用户名"), current.username, false);
        EditText password = field(t("Password", "密码"), current.password, true);

        form.addView(host);
        form.addView(share);
        form.addView(folder);
        form.addView(domain);
        form.addView(username);
        form.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Samba folder", "Samba 文件夹"))
                .setView(form)
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("Save", "保存"), null)
                .create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                SambaSettings next = new SambaSettings(
                        host.getText().toString(),
                        share.getText().toString(),
                        folder.getText().toString(),
                        domain.getText().toString(),
                        username.getText().toString(),
                        password.getText().toString()
                );
                if (TextUtils.isEmpty(next.host)) {
                    host.setError(t("Required", "必填"));
                    return;
                }
                if (TextUtils.isEmpty(next.share)) {
                    share.setError(t("Required", "必填"));
                    return;
                }
                next.save(this);
                remoteLoaded = false;
                localLoaded = false;
                remoteThumbLoader.clear();
                updateDestinationLabel();
                hideKeyboard(host);
                dialog.dismiss();
                if (selectedTab == Tab.REMOTE) {
                    loadRemotePhotos();
                } else {
                    loadPhotos();
                }
            });
        });
        dialog.show();
    }

    private EditText field(String hint, String value, boolean password) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine(true);
        editText.setTextSize(15);
        editText.setSelectAllOnFocus(false);
        editText.setPadding(dp(10), 0, dp(10), 0);
        editText.setBackgroundResource(R.drawable.edit_text_bg);
        editText.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(8), 0, 0);
        editText.setLayoutParams(params);
        return editText;
    }

    private void updateDestinationLabel() {
        SambaSettings settings = SambaSettings.load(this);
        destination.setText(settings.isConfigured()
                ? settings.displayName()
                : t("No Samba folder set", "未设置 Samba 文件夹"));
    }

    private void enterSelectionMode(PhotoItem item) {
        if (uploading || deleting) {
            return;
        }
        selectionMode = true;
        item.selected = true;
        adapter.notifyDataSetChanged();
        updateButtons();
        updateSelectionStatus();
    }

    private void enterRemoteSelectionMode(RemotePhotoItem item) {
        if (uploading || deleting) {
            return;
        }
        selectionMode = true;
        item.selected = true;
        remoteAdapter.notifyDataSetChanged();
        updateButtons();
        updateSelectionStatus();
    }

    private void toggleLocalSelection(PhotoItem item) {
        if (!selectionMode || uploading || deleting) {
            return;
        }
        item.selected = !item.selected;
        adapter.notifyDataSetChanged();
        updateButtons();
        updateSelectionStatus();
    }

    private void toggleRemoteSelection(RemotePhotoItem item) {
        if (!selectionMode || uploading || deleting) {
            return;
        }
        item.selected = !item.selected;
        remoteAdapter.notifyDataSetChanged();
        updateButtons();
        updateSelectionStatus();
    }

    private void exitSelectionMode() {
        if (!selectionMode || uploading || deleting) {
            return;
        }
        selectionMode = false;
        clearLocalSelection();
        clearRemoteSelection();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (remoteAdapter != null) {
            remoteAdapter.notifyDataSetChanged();
        }
        updateButtons();
        if (selectedTab == Tab.LOCAL) {
            setStatus(localMediaStatus(photos.size()));
        } else {
            setStatus(remoteMediaStatus(remotePhotos.size()));
        }
    }

    private void clearLocalSelection() {
        for (PhotoItem item : allPhotos) {
            item.selected = false;
        }
    }

    private void clearRemoteSelection() {
        for (RemotePhotoItem item : remotePhotos) {
            item.selected = false;
        }
    }

    private int selectedLocalCount() {
        int selected = 0;
        for (PhotoItem item : photos) {
            if (item.selected) {
                selected++;
            }
        }
        return selected;
    }

    private int selectedLocalNoSyncCount() {
        int selected = 0;
        for (PhotoItem item : photos) {
            if (item.selected && item.noSync) {
                selected++;
            }
        }
        return selected;
    }

    private int selectedRemoteCount() {
        int selected = 0;
        for (RemotePhotoItem item : remotePhotos) {
            if (item.selected) {
                selected++;
            }
        }
        return selected;
    }

    private int syncedLocalCount() {
        int synced = 0;
        for (PhotoItem item : photos) {
            if (item.sambaExists) {
                synced++;
            }
        }
        return synced;
    }

    private int selectedCurrentCount() {
        return selectedTab == Tab.REMOTE ? selectedRemoteCount() : selectedLocalCount();
    }

    private void updateSelectionStatus() {
        int selected = selectedCurrentCount();
        setStatus(selectedStatusText(selected));
    }

    private String localMediaStatus(int count) {
        if (count == 0) {
            return t("No camera media found", "没有相机媒体");
        }
        return isChinese() ? count + " 个相机媒体文件" : count + " camera media files";
    }

    private void updateButtons() {
        if (localTabButton != null && remoteTabButton != null) {
            updateTabButton(localTabButton, selectedTab == Tab.LOCAL);
            updateTabButton(remoteTabButton, selectedTab == Tab.REMOTE);
        }

        boolean localVisible = selectedTab == Tab.LOCAL;
        boolean remoteVisible = selectedTab == Tab.REMOTE;
        boolean selecting = selectionMode && (localVisible || remoteVisible);
        if (localActionRow != null) {
            localActionRow.setVisibility(localVisible && !selecting ? View.VISIBLE : View.GONE);
        }
        if (selectionActionRow != null) {
            selectionActionRow.setVisibility(selecting ? View.VISIBLE : View.GONE);
        }
        if (selectionRowsColumn != null) {
            LinearLayout.LayoutParams rowsParams = (LinearLayout.LayoutParams) selectionRowsColumn.getLayoutParams();
            float targetWeight = localVisible && selecting ? 2f : 1f;
            if (rowsParams.weight != targetWeight) {
                rowsParams.weight = targetWeight;
                selectionRowsColumn.setLayoutParams(rowsParams);
            }
        }
        if (buttonRow != null) {
            buttonRow.setVisibility(selecting ? View.VISIBLE : View.GONE);
        }
        if (noSyncRow != null) {
            noSyncRow.setVisibility(localVisible && selecting ? View.VISIBLE : View.GONE);
        }
        if (syncAllButton == null || deleteSyncedButton == null || uploadSelectedButton == null || noSyncButton == null || reEnableSyncButton == null || deleteSelectedButton == null || cancelSelectionButton == null) {
            return;
        }

        int selected = selectedCurrentCount();
        int selectedLocal = selectedLocalCount();
        int selectedNoSync = selectedLocalNoSyncCount();
        boolean busy = uploading || deleting;
        syncAllButton.setEnabled(localVisible && !busy && !photos.isEmpty());
        int synced = syncedLocalCount();
        deleteSyncedButton.setEnabled(localVisible && !busy && synced > 0);
        uploadSelectedButton.setVisibility(localVisible && selecting ? View.VISIBLE : View.GONE);
        uploadSelectedButton.setEnabled(localVisible && selecting && !busy && selectedLocal > 0);
        uploadSelectedButton.setText(selectedLocal > 0 ? uploadCountText(selectedLocal) : t("Upload", "上传"));
        noSyncButton.setVisibility(localVisible && selecting ? View.VISIBLE : View.GONE);
        noSyncButton.setEnabled(localVisible && selecting && !busy && selectedLocal > 0);
        reEnableSyncButton.setVisibility(localVisible && selecting ? View.VISIBLE : View.GONE);
        reEnableSyncButton.setEnabled(localVisible && selecting && !busy && selectedNoSync > 0);
        ViewGroup.LayoutParams doneParams = cancelSelectionButton.getLayoutParams();
        int doneHeight = localVisible && selecting ? dp(92) : dp(44);
        if (doneParams.height != doneHeight) {
            doneParams.height = doneHeight;
            cancelSelectionButton.setLayoutParams(doneParams);
        }
        deleteSelectedButton.setEnabled(selecting && !busy && selected > 0);
        cancelSelectionButton.setEnabled(selecting && !busy);
    }

    private void updateTabButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : getColorCompat(R.color.ink));
        button.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_unselected);
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private String t(String english, String chinese) {
        return UiText.text(this, english, chinese);
    }

    private boolean isChinese() {
        return UiText.isChinese(this);
    }

    private String remoteMediaStatus(int count) {
        if (count == 0) {
            return t("No remote media found", "没有远程媒体");
        }
        return isChinese() ? count + " 个远程媒体文件" : count + " remote media files";
    }

    private String selectedStatusText(int count) {
        return isChinese() ? count + " 个已选择" : (count == 1 ? "1 selected" : count + " selected");
    }

    private String selectedItemLabel(int count) {
        return isChinese() ? count + " 个所选项目" : (count == 1 ? "1 selected item" : count + " selected items");
    }

    private String syncedItemLabel(int count) {
        return isChinese() ? count + " 个已同步项目" : (count == 1 ? "1 synced item" : count + " synced items");
    }

    private String markedNoSyncText(int count) {
        return isChinese() ? count + " 个项目已标记为不同步" : (count == 1 ? "1 item marked No Sync" : count + " items marked No Sync");
    }

    private String reEnabledSyncText(int count) {
        if (count == 0) {
            return t("No selected No Sync items", "所选项目中没有不同步项目");
        }
        return isChinese() ? count + " 个项目已重新启用同步" : (count == 1 ? "1 item re-enabled for sync" : count + " items re-enabled for sync");
    }

    private String deletingText(int count) {
        return isChinese() ? "正在删除 " + count + " 项" : "Deleting " + count;
    }

    private String deletingProgressText(int done, int total) {
        return isChinese() ? "正在删除 " + done + " / " + total : "Deleting " + done + " of " + total;
    }

    private String deletingFromSambaText(int count) {
        return isChinese() ? "正在从 Samba 删除 " + count + " 项" : "Deleting " + count + " from Samba";
    }

    private String deletingFromSambaProgressText(int done, int total) {
        return isChinese() ? "正在从 Samba 删除 " + done + " / " + total : "Deleting " + done + " of " + total + " from Samba";
    }

    private String deletedText(int count) {
        return isChinese() ? "已删除 " + count + " 项" : "Deleted " + count;
    }

    private String deletedFailedText(int deletedCount, int failedCount) {
        return isChinese()
                ? "已删除 " + deletedCount + " 项  失败 " + failedCount + " 项"
                : "Deleted " + deletedCount + "  Failed " + failedCount;
    }

    private String deletedWaitingPermissionText(int deletedSoFar) {
        return isChinese()
                ? "已删除 " + deletedSoFar + " 项；正在等待权限"
                : "Deleted " + deletedSoFar + "; waiting for permission";
    }

    private String uploadSummaryText(int uploaded, int skipped, int failed) {
        return isChinese()
                ? "已上传 " + uploaded + "  已跳过 " + skipped + "  失败 " + failed
                : "Uploaded " + uploaded + "  Skipped " + skipped + "  Failed " + failed;
    }

    private String uploadCountText(int count) {
        return isChinese() ? "上传 " + count + " 项" : "Upload " + count;
    }

    private boolean hasPhotoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhotoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            loadPhotos();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            }, REQUEST_IMAGES);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_IMAGES);
        }
    }

    private Button primaryButton(String text, int icon) {
        Button button = baseButton(text, icon);
        button.setTextColor(Color.WHITE);
        button.setBackgroundResource(R.drawable.button_primary);
        return button;
    }

    private Button secondaryButton(String text, int icon) {
        Button button = baseButton(text, icon);
        button.setTextColor(getColorCompat(R.color.ink));
        button.setBackgroundResource(R.drawable.button_secondary);
        return button;
    }

    private Button destructiveButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(Color.rgb(190, 34, 34));
        button.setBackground(background);
        return button;
    }

    private Button grayButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(Color.rgb(102, 110, 120));
        button.setBackground(background);
        return button;
    }

    private Button successButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(7));
        background.setColor(getColorCompat(R.color.success));
        button.setBackground(background);
        return button;
    }

    private Button baseButton(String text, int icon) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        Drawable drawable = getResources().getDrawable(icon);
        drawable.setBounds(0, 0, dp(20), dp(20));
        button.setCompoundDrawables(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(8));
        return button;
    }

    private Button tabButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }


    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setTooltipText(description);
        }
        return button;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String sizeLabel(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return (bytes / (1024 * 1024)) + " MB";
    }

    private int getColorCompat(int colorRes) {
        return getResources().getColor(colorRes);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
