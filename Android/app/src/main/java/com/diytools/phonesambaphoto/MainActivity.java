package com.diytools.phonesambaphoto;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMAGES = 1001;

    private enum Tab {
        LOCAL,
        REMOTE
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService remoteExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    private final List<PhotoItem> photos = new ArrayList<>();
    private final List<RemotePhotoItem> remotePhotos = new ArrayList<>();

    private ThumbLoader thumbLoader;
    private RemoteThumbLoader remoteThumbLoader;
    private PhotoGridAdapter adapter;
    private RemotePhotoGridAdapter remoteAdapter;
    private GridView localGrid;
    private GridView remoteGrid;
    private LinearLayout buttonRow;
    private TextView status;
    private TextView destination;
    private ProgressBar progress;
    private Button localTabButton;
    private Button remoteTabButton;
    private Button syncAllButton;
    private Button uploadSelectedButton;
    private Button sambaSetupButton;
    private Tab selectedTab = Tab.LOCAL;
    private boolean uploading;
    private boolean localLoaded;
    private boolean remoteLoaded;
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
            item.selected = !item.selected;
            adapter.notifyDataSetChanged();
            updateButtons();
        });

        SambaSettings settings = SambaSettings.load(this);
        remoteAdapter = new RemotePhotoGridAdapter(this, remotePhotos, remoteThumbLoader, settings);
        remoteGrid.setAdapter(remoteAdapter);
        remoteGrid.setOnItemClickListener((parent, view, position, id) -> {
            RemotePhotoItem item = remotePhotos.get(position);
            setStatus(item.name + "  " + sizeLabel(item.size));
        });

        selectTab(Tab.LOCAL);
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
        if (requestCode == REQUEST_IMAGES && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPhotos();
        } else if (selectedTab == Tab.LOCAL) {
            setStatus("Photo permission is needed to show the gallery");
        }
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.surface));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(10), dp(8), dp(8));
        toolbar.setBackgroundColor(getColorCompat(R.color.panel));

        TextView title = new TextView(this);
        title.setText("Photos");
        title.setTextColor(getColorCompat(R.color.ink));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        ImageButton refreshButton = iconButton(R.drawable.ic_refresh, "Refresh");
        refreshButton.setOnClickListener(v -> refreshCurrentTab());
        toolbar.addView(refreshButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageButton settingsButton = iconButton(R.drawable.ic_settings, "Samba folder");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        toolbar.addView(settingsButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(12), 0, dp(12), dp(8));
        tabs.setBackgroundColor(getColorCompat(R.color.panel));

        localTabButton = tabButton("Local");
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

        sambaSetupButton = smallButton("Setup");
        sambaSetupButton.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(dp(76), dp(32));
        setupParams.setMargins(dp(8), 0, 0, 0);
        destinationRow.addView(sambaSetupButton, setupParams);
        actions.addView(destinationRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        buttonRow = new LinearLayout(this);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        syncAllButton = primaryButton("Sync all", R.drawable.ic_sync);
        syncAllButton.setOnClickListener(v -> syncAll());
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        syncParams.setMargins(0, 0, dp(8), 0);
        buttonRow.addView(syncAllButton, syncParams);

        uploadSelectedButton = secondaryButton("Upload selected", R.drawable.ic_upload);
        uploadSelectedButton.setOnClickListener(v -> uploadSelected());
        buttonRow.addView(uploadSelectedButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(buttonRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

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
        setStatus("Ready");
        return root;
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
                setStatus(photos.isEmpty() ? "No photos found" : photos.size() + " photos");
            }
        } else {
            if (!settings.isConfigured()) {
                remotePhotos.clear();
                if (remoteAdapter != null) {
                    remoteAdapter.notifyDataSetChanged();
                }
                setStatus("Set Samba folder to view remote media files");
                showSettingsDialog();
                return;
            }
            if (!remoteLoaded || !settings.identityKey().equals(remoteLoadedIdentity)) {
                loadRemotePhotos();
            } else {
                setStatus(remotePhotos.isEmpty() ? "No remote media found" : remotePhotos.size() + " remote media files");
            }
        }
    }

    private void refreshCurrentTab() {
        if (selectedTab == Tab.REMOTE) {
            remoteLoaded = false;
            remoteThumbLoader.clear();
            loadRemotePhotos();
        } else {
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
            setStatus("Scanning photos");
        }
        scanExecutor.execute(() -> {
            SambaSettings settings = SambaSettings.load(getApplicationContext());
            List<PhotoItem> loaded = PhotoRepository.loadPhotos(getApplicationContext(), settings);
            main.post(() -> {
                photos.clear();
                photos.addAll(loaded);
                localLoaded = true;
                localLoadedIdentity = settings.identityKey();
                adapter.notifyDataSetChanged();
                if (selectedTab == Tab.LOCAL) {
                    setStatus(loaded.isEmpty() ? "No photos found" : loaded.size() + " photos");
                }
                updateDestinationLabel();
                updateButtons();
            });
        });
    }

    private void loadRemotePhotos() {
        SambaSettings settings = SambaSettings.load(this);
        updateDestinationLabel();
        if (remoteAdapter != null) {
            remoteAdapter.setSettings(settings);
        }
        if (!settings.isConfigured()) {
            remotePhotos.clear();
            remoteLoaded = false;
            if (remoteAdapter != null) {
                remoteAdapter.notifyDataSetChanged();
            }
            setStatus("Set Samba folder to view remote media files");
            return;
        }

        setStatus("Scanning Samba folder");
        remoteExecutor.execute(() -> {
            try {
                List<RemotePhotoItem> loaded = RemotePhotoRepository.loadPhotos(settings);
                main.post(() -> {
                    remotePhotos.clear();
                    remotePhotos.addAll(loaded);
                    remoteLoaded = true;
                    remoteLoadedIdentity = settings.identityKey();
                    remoteAdapter.setSettings(settings);
                    remoteAdapter.notifyDataSetChanged();
                    if (selectedTab == Tab.REMOTE) {
                        setStatus(loaded.isEmpty() ? "No remote media found" : loaded.size() + " remote media files");
                    }
                    updateButtons();
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    remotePhotos.clear();
                    remoteLoaded = false;
                    remoteAdapter.notifyDataSetChanged();
                    if (selectedTab == Tab.REMOTE) {
                        setStatus("Cannot read Samba folder");
                    }
                    updateButtons();
                });
            }
        });
    }

    private void syncAll() {
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showSettingsDialog();
            return;
        }
        List<PhotoItem> pending = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (!photo.uploaded) {
                pending.add(photo);
            }
        }
        if (pending.isEmpty()) {
            setStatus("All photos are synced");
            return;
        }
        startUpload(settings, pending);
    }

    private void uploadSelected() {
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showSettingsDialog();
            return;
        }
        List<PhotoItem> selected = new ArrayList<>();
        for (PhotoItem photo : photos) {
            if (photo.selected) {
                selected.add(photo);
            }
        }
        if (selected.isEmpty()) {
            setStatus("Select photos to upload");
            return;
        }
        startUpload(settings, selected);
    }

    private void startUpload(SambaSettings settings, List<PhotoItem> items) {
        if (uploading) {
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
                                adapter.notifyDataSetChanged();
                            });
                        }
                    });

            main.post(() -> {
                uploading = false;
                remoteLoaded = false;
                remoteThumbLoader.clear();
                progress.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
                updateButtons();
                setStatus("Uploaded " + summary.uploaded + "  Skipped " + summary.skipped + "  Failed " + summary.failed);
            });
        });
    }

    private void showSettingsDialog() {
        SambaSettings current = SambaSettings.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, dp(8), pad, 0);

        EditText host = field("Host or IP", current.host, false);
        EditText share = field("Share", current.share, false);
        EditText folder = field("Folder", current.folder, false);
        EditText domain = field("Domain", current.domain, false);
        EditText username = field("Username", current.username, false);
        EditText password = field("Password", current.password, true);

        form.addView(host);
        form.addView(share);
        form.addView(folder);
        form.addView(domain);
        form.addView(username);
        form.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Samba folder")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
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
                    host.setError("Required");
                    return;
                }
                if (TextUtils.isEmpty(next.share)) {
                    share.setError("Required");
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
        destination.setText(SambaSettings.load(this).displayName());
    }

    private void updateButtons() {
        if (localTabButton != null && remoteTabButton != null) {
            updateTabButton(localTabButton, selectedTab == Tab.LOCAL);
            updateTabButton(remoteTabButton, selectedTab == Tab.REMOTE);
        }
        if (buttonRow != null) {
            buttonRow.setVisibility(selectedTab == Tab.LOCAL ? View.VISIBLE : View.GONE);
        }
        if (sambaSetupButton != null) {
            sambaSetupButton.setEnabled(!uploading);
        }
        if (syncAllButton == null || uploadSelectedButton == null) {
            return;
        }

        int selected = 0;
        for (PhotoItem item : photos) {
            if (item.selected) {
                selected++;
            }
        }
        boolean localVisible = selectedTab == Tab.LOCAL;
        syncAllButton.setEnabled(localVisible && !uploading && !photos.isEmpty());
        uploadSelectedButton.setEnabled(localVisible && !uploading && selected > 0);
        uploadSelectedButton.setText(selected > 0 ? "Upload " + selected : "Upload selected");
    }

    private void updateTabButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : getColorCompat(R.color.ink));
        button.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_unselected);
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private boolean hasPhotoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhotoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            loadPhotos();
            return;
        }
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{permission}, REQUEST_IMAGES);
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

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(getColorCompat(R.color.ink));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.button_secondary);
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
