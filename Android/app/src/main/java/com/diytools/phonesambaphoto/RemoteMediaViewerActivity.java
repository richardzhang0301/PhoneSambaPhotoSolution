package com.diytools.phonesambaphoto;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbRandomAccessFile;

public final class RemoteMediaViewerActivity extends Activity {

    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_THUMBNAIL_URL = "thumbnail_url";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_SIZE = "size";
    private static final String EXTRA_MODIFIED = "modified";
    private static final String EXTRA_VIDEO = "video";
    private static final String EXTRA_INDEX = "index";
    private static final int REQUEST_DELETE_LOCAL = 2001;
    private static final int REQUEST_DOWNLOAD_REMOTE = 2002;
    private static final long REMOTE_VIDEO_INITIAL_BUFFER_MS = 5_000L;
    private static final int REMOTE_VIDEO_STARTUP_RECOVERY_LIMIT = 2;
    private static final Object NAVIGATION_LOCK = new Object();
    private static final Object MEDIA_CHANGED_LOCK = new Object();
    private static ArrayList<ViewerItem> navigationSession = new ArrayList<>();
    private static boolean mediaChanged;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ProgressBar progress;
    private TextView status;
    private ImageButton backButton;
    private ImageButton rotateButton;
    private LinearLayout actionBar;
    private Button uploadButton;
    private Button downloadButton;
    private Button deleteButton;
    private ZoomImageView thumbnailView;
    private ZoomImageView photoView;
    private VideoView videoView;
    private TextureView remoteVideoTexture;
    private Surface remoteVideoOutputSurface;
    private MediaPlayer remoteMediaPlayer;
    private MediaController remoteMediaController;
    private RemoteVideoCacheDataSource remoteMediaDataSource;
    private View videoControllerAnchor;
    private String name;
    private String url;
    private String thumbnailUrl;
    private String uriString;
    private long size;
    private long modified;
    private boolean video;
    private GestureDetector navigationGestureDetector;
    private ArrayList<ViewerItem> navigationItems = new ArrayList<>();
    private int currentIndex;
    private boolean multiTouchGesture;
    private boolean mediaNavigationInProgress;
    private int remoteVideoWidth;
    private int remoteVideoHeight;
    private boolean actionInProgress;
    private boolean retryDownloadAfterPermission;
    private int photoRotationDegrees;
    private int previewPhotoWidth;
    private int previewPhotoHeight;
    private boolean remoteVideoPrepared;
    private boolean remoteVideoUserPaused;
    private boolean remoteVideoInitialBuffering;
    private boolean remoteVideoAutoBuffering;
    private boolean remoteVideoResumeAfterBuffering;
    private boolean remoteVideoSeekStartPending;
    private boolean remoteVideoStartupRecoveryPending;
    private int remoteVideoStartupRecoveryCount;
    private long remoteVideoLastFrameMillis;
    private final Runnable remoteVideoStartupWatchdog = this::checkRemoteVideoStartupFrames;

    static void open(Context context, RemotePhotoItem item) {
        ArrayList<ViewerItem> items = new ArrayList<>();
        items.add(ViewerItem.from(item));
        open(context, items, 0);
    }

    static void open(Context context, PhotoItem item) {
        ArrayList<ViewerItem> items = new ArrayList<>();
        items.add(ViewerItem.from(item));
        open(context, items, 0);
    }

    static void openRemote(Context context, List<RemotePhotoItem> photos, int index) {
        ArrayList<ViewerItem> items = new ArrayList<>();
        for (RemotePhotoItem photo : photos) {
            items.add(ViewerItem.from(photo));
        }
        open(context, items, index);
    }

    static void openLocal(Context context, List<PhotoItem> photos, int index) {
        ArrayList<ViewerItem> items = new ArrayList<>();
        for (PhotoItem photo : photos) {
            items.add(ViewerItem.from(photo));
        }
        open(context, items, index);
    }

    private static void open(Context context, ArrayList<ViewerItem> items, int index) {
        if (items.isEmpty()) {
            return;
        }
        int safeIndex = Math.max(0, Math.min(index, items.size() - 1));
        setNavigationSession(items);
        context.startActivity(intentFor(context, items.get(safeIndex), safeIndex));
    }

    private static void setNavigationSession(ArrayList<ViewerItem> items) {
        synchronized (NAVIGATION_LOCK) {
            navigationSession = new ArrayList<>(items);
        }
    }

    private static ArrayList<ViewerItem> navigationSessionSnapshot() {
        synchronized (NAVIGATION_LOCK) {
            return new ArrayList<>(navigationSession);
        }
    }

    static boolean consumeMediaChanged() {
        synchronized (MEDIA_CHANGED_LOCK) {
            boolean changed = mediaChanged;
            mediaChanged = false;
            return changed;
        }
    }

    private static void markMediaChanged() {
        synchronized (MEDIA_CHANGED_LOCK) {
            mediaChanged = true;
        }
    }

    private static Intent intentFor(Context context, ViewerItem item, int index) {
        Intent intent = new Intent(context, RemoteMediaViewerActivity.class);
        intent.putExtra(EXTRA_NAME, item.name);
        intent.putExtra(EXTRA_URL, item.url);
        intent.putExtra(EXTRA_THUMBNAIL_URL, item.thumbnailUrl);
        intent.putExtra(EXTRA_URI, item.uriString);
        intent.putExtra(EXTRA_SIZE, item.size);
        intent.putExtra(EXTRA_MODIFIED, item.modified);
        intent.putExtra(EXTRA_VIDEO, item.video);
        intent.putExtra(EXTRA_INDEX, index);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent intent = getIntent();
        name = intent.getStringExtra(EXTRA_NAME);
        url = intent.getStringExtra(EXTRA_URL);
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL);
        uriString = intent.getStringExtra(EXTRA_URI);
        size = intent.getLongExtra(EXTRA_SIZE, 0L);
        modified = intent.getLongExtra(EXTRA_MODIFIED, 0L);
        video = intent.getBooleanExtra(EXTRA_VIDEO, false);
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0);
        navigationItems = navigationSessionSnapshot();
        if (currentIndex < 0 || currentIndex >= navigationItems.size()) {
            navigationItems.clear();
            ViewerItem current = ViewerItem.from(intent);
            if (current.hasMedia()) {
                navigationItems.add(current);
            }
            currentIndex = 0;
        }
        navigationGestureDetector = new GestureDetector(this, new NavigationGestureListener());

        if (TextUtils.isEmpty(url) && TextUtils.isEmpty(uriString)) {
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        addBackButton();
        addRotateButton();
        addActionButtons();
        enterImmersiveMode();

        if (video) {
            showLoading(t("Loading video", "正在加载视频"));
            loadVideo();
        } else {
            showLoading(t("Loading photo", "正在加载照片"));
            loadPhoto();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouchGesture = false;
        } else if (event.getPointerCount() > 1) {
            multiTouchGesture = true;
        }
        if (navigationGestureDetector != null) {
            navigationGestureDetector.onTouchEvent(event);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            multiTouchGesture = false;
        }
        return handled;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        releaseRemoteVideoPlayer();
        removeVideoViews();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DELETE_LOCAL) {
            return;
        }
        actionInProgress = false;
        updateActionButtons();
        if (resultCode == RESULT_OK) {
            markMediaChanged();
            showActionToast(t("Deleted", "已删除"));
            afterDeleteCurrentItem();
        } else {
            showActionToast(t("Delete cancelled", "已取消删除"));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_DOWNLOAD_REMOTE) {
            return;
        }
        if (hasDownloadPermission() && retryDownloadAfterPermission) {
            retryDownloadAfterPermission = false;
            downloadRemoteCurrentItem();
        } else {
            retryDownloadAfterPermission = false;
            showActionToast(t("Storage permission is needed to download", "下载需要存储权限"));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    private void loadPhoto() {
        executor.execute(() -> {
            if (isRemote()) {
                loadRemotePhotoWithPreview();
                return;
            }

            try {
                Bitmap bitmap = decodeLocalPhoto();
                main.post(() -> showPhoto(bitmap));
            } catch (Exception exc) {
                main.post(() -> showError(t("Could not open photo", "无法打开照片")));
            }
        });
    }

    private void loadRemotePhotoWithPreview() {
        boolean previewShown = false;
        try {
            SambaSettings settings = SambaSettings.load(this);
            CIFSContext context = SambaUploader.createContext(settings);
            if (!TextUtils.isEmpty(thumbnailUrl)) {
                try {
                    Bitmap thumbnail = decodeRemoteThumbnail(context);
                    main.post(() -> showPhotoThumbnail(thumbnail));
                    previewShown = true;
                } catch (Exception ignored) {
                    // Fall through to the full image; thumbnails are a speed boost, not a requirement.
                }
            }

            Bitmap bitmap = decodeRemotePhoto(context);
            main.post(() -> showPhoto(bitmap));
        } catch (Exception exc) {
            boolean hadPreview = previewShown;
            main.post(() -> {
                if (hadPreview) {
                    showError(t("Could not load full photo", "无法加载完整照片"));
                } else {
                    showError(t("Could not open photo", "无法打开照片"));
                }
            });
        }
    }

    private void showPhotoThumbnail(Bitmap bitmap) {
        if (isFinishing()) {
            return;
        }
        hideLoading();
        photoView = null;
        thumbnailView = new ZoomImageView(this);
        thumbnailView.setZoomEnabled(false);
        thumbnailView.setBitmap(bitmap);
        thumbnailView.setRotationDegrees(photoRotationDegrees);
        previewPhotoWidth = bitmap.getWidth();
        previewPhotoHeight = bitmap.getHeight();
        root.addView(thumbnailView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        showLoading(t("Loading full photo", "正在加载完整照片"));
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private void showPhoto(Bitmap bitmap) {
        if (isFinishing()) {
            return;
        }
        hideLoading();
        if (thumbnailView != null) {
            root.removeView(thumbnailView);
            thumbnailView = null;
        }
        autoRotateFullPhotoForPreview(bitmap);
        ZoomImageView imageView = new ZoomImageView(this);
        photoView = imageView;
        imageView.setBitmap(bitmap);
        imageView.setRotationDegrees(photoRotationDegrees);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private Bitmap decodeRemoteThumbnail(CIFSContext context) throws Exception {
        SmbFile file = new SmbFile(thumbnailUrl, context);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("Thumbnail cannot be decoded");
            }
            return bitmap;
        }
    }

    private Bitmap decodeRemotePhoto(CIFSContext context) throws Exception {
        SmbFile file = new SmbFile(url, context);
        PhotoOrientation photoOrientation = readRemotePhotoOrientation(file);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = photoDecodeOptions(bounds.outWidth, bounds.outHeight);
        try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("Image cannot be decoded");
            }
            return applyExifOrientation(bitmap, photoOrientation, bounds.outWidth, bounds.outHeight);
        }
    }

    private Bitmap decodeLocalPhoto() throws Exception {
        Uri uri = Uri.parse(uriString);
        PhotoOrientation photoOrientation = readLocalPhotoOrientation(uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Image cannot be opened");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = photoDecodeOptions(bounds.outWidth, bounds.outHeight);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Image cannot be opened");
            }
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("Image cannot be decoded");
            }
            return applyExifOrientation(bitmap, photoOrientation, bounds.outWidth, bounds.outHeight);
        }
    }

    private PhotoOrientation readRemotePhotoOrientation(SmbFile file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return PhotoOrientation.undefined();
        }
        try (InputStream input = new BufferedInputStream(new SmbFileInputStream(file))) {
            ExifInterface exif = new ExifInterface(input);
            return PhotoOrientation.from(exif);
        } catch (Exception ignored) {
            return PhotoOrientation.undefined();
        }
    }

    private PhotoOrientation readLocalPhotoOrientation(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return PhotoOrientation.undefined();
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return PhotoOrientation.undefined();
            }
            ExifInterface exif = new ExifInterface(input);
            return PhotoOrientation.from(exif);
        } catch (Exception ignored) {
            return PhotoOrientation.undefined();
        }
    }

    private Bitmap applyExifOrientation(Bitmap bitmap, PhotoOrientation photoOrientation, int decodedBoundsWidth, int decodedBoundsHeight) {
        int orientation = photoOrientation.orientation;
        if (shouldKeepDecodedOrientation(bitmap, photoOrientation, decodedBoundsWidth, decodedBoundsHeight)) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return bitmap;
        }
        try {
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (oriented != bitmap) {
                bitmap.recycle();
            }
            return oriented;
        } catch (RuntimeException ignored) {
            return bitmap;
        }
    }

    private void autoRotateFullPhotoForPreview(Bitmap bitmap) {
        if (bitmap == null || previewPhotoWidth <= 0 || previewPhotoHeight <= 0) {
            return;
        }
        if (previewPhotoWidth == previewPhotoHeight || bitmap.getWidth() == bitmap.getHeight()) {
            return;
        }

        boolean previewPortrait = isDisplayedPortrait(previewPhotoWidth, previewPhotoHeight, photoRotationDegrees);
        boolean fullPortrait = isDisplayedPortrait(bitmap.getWidth(), bitmap.getHeight(), photoRotationDegrees);
        if (previewPortrait == fullPortrait) {
            return;
        }

        int rotatedDegrees = (photoRotationDegrees + 90) % 360;
        if (isDisplayedPortrait(bitmap.getWidth(), bitmap.getHeight(), rotatedDegrees) != previewPortrait) {
            return;
        }

        if (fittedPhotoArea(bitmap.getWidth(), bitmap.getHeight(), rotatedDegrees)
                > fittedPhotoArea(bitmap.getWidth(), bitmap.getHeight(), photoRotationDegrees)) {
            photoRotationDegrees = rotatedDegrees;
        }
    }

    private boolean isDisplayedPortrait(int width, int height, int rotationDegrees) {
        boolean quarterTurn = normalizedQuarterTurn(rotationDegrees);
        int displayedWidth = quarterTurn ? height : width;
        int displayedHeight = quarterTurn ? width : height;
        return displayedHeight > displayedWidth;
    }

    private boolean normalizedQuarterTurn(int rotationDegrees) {
        int normalized = rotationDegrees % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        return normalized == 90 || normalized == 270;
    }

    private double fittedPhotoArea(int width, int height, int rotationDegrees) {
        if (width <= 0 || height <= 0) {
            return 0d;
        }
        boolean quarterTurn = normalizedQuarterTurn(rotationDegrees);
        int displayedWidth = quarterTurn ? height : width;
        int displayedHeight = quarterTurn ? width : height;
        int viewportWidth = root != null && root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int viewportHeight = root != null && root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return 0d;
        }
        double scale = Math.min(viewportWidth / (double) displayedWidth, viewportHeight / (double) displayedHeight);
        return displayedWidth * scale * displayedHeight * scale;
    }

    private boolean shouldKeepDecodedOrientation(Bitmap bitmap, PhotoOrientation photoOrientation, int decodedBoundsWidth, int decodedBoundsHeight) {
        if (!swapsDimensions(photoOrientation.orientation)) {
            return false;
        }

        int rawWidth = decodedBoundsWidth;
        int rawHeight = decodedBoundsHeight;
        if (rawWidth <= 0 || rawHeight <= 0 || rawWidth == rawHeight) {
            return false;
        }

        boolean rawLandscape = rawWidth > rawHeight;
        boolean decodedLandscape = bitmap.getWidth() > bitmap.getHeight();
        return rawLandscape != decodedLandscape;
    }

    private boolean swapsDimensions(int orientation) {
        return orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_270;
    }

    private BitmapFactory.Options photoDecodeOptions(int width, int height) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int target = Math.max(metrics.widthPixels, metrics.heightPixels) * 3;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inSampleSize = sampleSize(width, height, target);
        return options;
    }

    private void loadVideo() {
        if (isRemote()) {
            prepareRemoteVideoStream();
            return;
        }

        playLocalVideo(Uri.parse(uriString));
    }

    private void prepareRemoteVideoStream() {
        if (isFinishing()) {
            return;
        }
        releaseRemoteVideoPlayer();
        removeVideoViews();
        photoView = null;
        showLoading(t("Opening video", "正在打开视频"));

        remoteVideoTexture = new TextureView(this);
        remoteVideoTexture.setOpaque(true);
        remoteVideoTexture.setClickable(true);
        remoteVideoTexture.setOnClickListener(view -> {
            if (remoteMediaController != null) {
                remoteMediaController.show();
            }
        });
        root.addView(remoteVideoTexture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        addVideoControllerAnchor();
        remoteVideoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                useRemoteVideoTexture(surfaceTexture);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                updateRemoteVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (remoteMediaController != null) {
                    remoteMediaController.hide();
                }
                if (remoteMediaPlayer != null) {
                    remoteMediaPlayer.setSurface(null);
                }
                releaseRemoteVideoSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                remoteVideoLastFrameMillis = SystemClock.uptimeMillis();
            }
        });
        if (remoteVideoTexture.isAvailable()) {
            useRemoteVideoTexture(remoteVideoTexture.getSurfaceTexture());
        }
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private void useRemoteVideoTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || remoteVideoOutputSurface != null) {
            return;
        }
        remoteVideoOutputSurface = new Surface(surfaceTexture);
        if (remoteMediaPlayer != null) {
            remoteMediaPlayer.setSurface(remoteVideoOutputSurface);
            updateRemoteVideoTransform();
            return;
        }
        openRemoteVideoPlayer(remoteVideoOutputSurface);
    }

    private void openRemoteVideoPlayer(Surface surface) {
        executor.execute(() -> {
            RemoteVideoCacheDataSource dataSource = null;
            try {
                SambaSettings settings = SambaSettings.load(this);
                CIFSContext context = SambaUploader.createContext(settings);
                SmbFile source = new SmbFile(url, context);
                dataSource = new RemoteVideoCacheDataSource(source, size, remoteVideoCacheDirectory());
                RemoteVideoCacheDataSource playableDataSource = dataSource;
                playableDataSource.setListener(new RemoteVideoCacheDataSource.Listener() {
                    @Override
                    public void onBuffering() {
                        main.post(() -> {
                            if (remoteMediaDataSource == playableDataSource) {
                                pauseRemoteVideoForBuffering();
                            }
                        });
                    }

                    @Override
                    public void onReady() {
                        main.post(() -> {
                            if (remoteMediaDataSource == playableDataSource) {
                                resumeRemoteVideoAfterBuffering();
                            }
                        });
                    }
                });
                main.post(() -> startRemoteVideoPlayer(surface, playableDataSource));
            } catch (Exception exc) {
                closeRemoteDataSource(dataSource);
                main.post(() -> showError(t("Could not open video", "无法打开视频")));
            }
        });
    }

    private void startRemoteVideoPlayer(Surface surface, RemoteVideoCacheDataSource dataSource) {
        if (isFinishing() || remoteVideoTexture == null || remoteVideoOutputSurface != surface) {
            closeRemoteDataSource(dataSource);
            return;
        }
        releaseRemoteVideoPlayer();
        remoteMediaDataSource = dataSource;
        try {
            remoteMediaPlayer = new MediaPlayer();
            remoteMediaPlayer.setDataSource(remoteMediaDataSource);
            remoteMediaPlayer.setSurface(surface);
            remoteMediaPlayer.setScreenOnWhilePlaying(true);
            remoteMediaPlayer.setOnVideoSizeChangedListener((player, width, height) -> {
                remoteVideoWidth = width;
                remoteVideoHeight = height;
                updateRemoteVideoTransform();
            });
            remoteMediaPlayer.setOnPreparedListener(player -> {
                remoteVideoPrepared = true;
                remoteVideoUserPaused = false;
                remoteVideoInitialBuffering = false;
                remoteVideoAutoBuffering = false;
                remoteVideoResumeAfterBuffering = false;
                remoteVideoSeekStartPending = false;
                remoteVideoStartupRecoveryPending = false;
                remoteVideoStartupRecoveryCount = 0;
                remoteVideoLastFrameMillis = 0L;
                setupRemoteMediaController();
                startRemotePlaybackAfterInitialBuffer(dataSource);
            });
            remoteMediaPlayer.setOnSeekCompleteListener(player -> {
                if (remoteVideoSeekStartPending) {
                    finishRemoteVideoSeekStart();
                } else if (remoteVideoStartupRecoveryPending) {
                    finishRemoteVideoStartupRecovery();
                }
            });
            remoteMediaPlayer.setOnErrorListener((player, what, extra) -> {
                showError(t("Could not play video", "无法播放视频"));
                return true;
            });
            remoteMediaPlayer.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    pauseRemoteVideoForBuffering();
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    resumeRemoteVideoAfterBuffering();
                }
                return false;
            });
            remoteMediaPlayer.prepareAsync();
        } catch (Exception exc) {
            releaseRemoteVideoPlayer();
            showError(t("Could not open video", "无法打开视频"));
        }
    }

    private File remoteVideoCacheDirectory() throws IOException {
        File directory = new File(getCacheDir(), "samba_video_stream");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create Samba video cache");
        }
        return directory;
    }

    private void startRemotePlaybackAfterInitialBuffer(RemoteVideoCacheDataSource dataSource) {
        remoteVideoInitialBuffering = true;
        remoteVideoResumeAfterBuffering = true;
        showLoading(remoteVideoBufferingText(dataSource.playbackBufferPercent(0L, remoteVideoDurationMillis(), REMOTE_VIDEO_INITIAL_BUFFER_MS)));
        if (remoteMediaController != null) {
            remoteMediaController.show(0);
        }
        checkInitialRemoteVideoBuffer(dataSource);
    }

    private void checkInitialRemoteVideoBuffer(RemoteVideoCacheDataSource dataSource) {
        if (!remoteVideoInitialBuffering || remoteMediaDataSource != dataSource || remoteMediaPlayer == null || isFinishing()) {
            return;
        }
        long durationMillis = remoteVideoDurationMillis();
        if (!dataSource.hasPlaybackBuffer(0L, durationMillis, REMOTE_VIDEO_INITIAL_BUFFER_MS)) {
            updateLoadingMessage(remoteVideoBufferingText(dataSource.playbackBufferPercent(0L, durationMillis, REMOTE_VIDEO_INITIAL_BUFFER_MS)));
            main.postDelayed(() -> checkInitialRemoteVideoBuffer(dataSource), 300L);
            return;
        }

        boolean shouldStart = remoteVideoResumeAfterBuffering && !remoteVideoUserPaused && !actionInProgress;
        remoteVideoInitialBuffering = false;
        remoteVideoResumeAfterBuffering = false;
        if (shouldStart) {
            startRemoteVideoFromZeroSeek();
        } else {
            hideLoading();
            bringOverlayControlsToFront();
            enterImmersiveMode();
        }
    }

    private long remoteVideoDurationMillis() {
        try {
            return remoteMediaPlayer != null ? Math.max(0L, remoteMediaPlayer.getDuration()) : 0L;
        } catch (IllegalStateException exc) {
            return 0L;
        }
    }

    private void startRemoteVideoFromZeroSeek() {
        if (remoteMediaPlayer == null || isFinishing()) {
            return;
        }
        remoteVideoSeekStartPending = true;
        showLoading(t("Buffering video", "正在缓冲视频"));
        try {
            seekRemoteVideoTo(0);
        } catch (IllegalStateException ignored) {
            remoteVideoSeekStartPending = false;
            hideLoading();
        }
        bringOverlayControlsToFront();
    }

    private void finishRemoteVideoSeekStart() {
        if (!remoteVideoSeekStartPending || remoteMediaPlayer == null || isFinishing()) {
            return;
        }
        remoteVideoSeekStartPending = false;
        hideLoading();
        if (!remoteVideoUserPaused && !actionInProgress) {
            try {
                remoteVideoLastFrameMillis = 0L;
                remoteMediaPlayer.start();
                scheduleRemoteVideoStartupWatchdog();
                if (remoteMediaController != null) {
                    remoteMediaController.show(1500);
                }
            } catch (IllegalStateException ignored) {
                // Leave playback stopped if the player state changed while seeking.
            }
        }
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private void scheduleRemoteVideoStartupWatchdog() {
        main.removeCallbacks(remoteVideoStartupWatchdog);
        main.postDelayed(remoteVideoStartupWatchdog, 2200L);
    }

    private void checkRemoteVideoStartupFrames() {
        if (!remoteVideoPrepared || remoteVideoInitialBuffering || remoteVideoAutoBuffering || remoteVideoStartupRecoveryPending
                || remoteMediaPlayer == null || remoteMediaDataSource == null || isFinishing()) {
            return;
        }
        int position;
        boolean playing;
        try {
            position = remoteMediaPlayer.getCurrentPosition();
            playing = remoteMediaPlayer.isPlaying();
        } catch (IllegalStateException ignored) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean noFreshFrames = remoteVideoLastFrameMillis <= 0L || now - remoteVideoLastFrameMillis > 1200L;
        if (playing && position >= 900 && noFreshFrames && remoteVideoStartupRecoveryCount < REMOTE_VIDEO_STARTUP_RECOVERY_LIMIT) {
            recoverRemoteVideoStartup();
            return;
        }
        if (playing && position < 8000) {
            main.postDelayed(remoteVideoStartupWatchdog, 1200L);
        }
    }

    private void recoverRemoteVideoStartup() {
        if (remoteMediaPlayer == null || remoteVideoStartupRecoveryPending || isFinishing()) {
            return;
        }
        remoteVideoStartupRecoveryCount++;
        remoteVideoStartupRecoveryPending = true;
        remoteVideoResumeAfterBuffering = true;
        showLoading(t("Buffering video", "正在缓冲视频"));
        try {
            remoteMediaPlayer.pause();
            seekRemoteVideoTo(0);
        } catch (IllegalStateException ignored) {
            remoteVideoStartupRecoveryPending = false;
            hideLoading();
        }
        bringOverlayControlsToFront();
    }

    private void finishRemoteVideoStartupRecovery() {
        if (!remoteVideoStartupRecoveryPending || remoteMediaPlayer == null || isFinishing()) {
            return;
        }
        boolean shouldResume = remoteVideoResumeAfterBuffering && !remoteVideoUserPaused && !actionInProgress;
        remoteVideoStartupRecoveryPending = false;
        remoteVideoResumeAfterBuffering = false;
        hideLoading();
        if (shouldResume) {
            try {
                remoteVideoLastFrameMillis = 0L;
                remoteMediaPlayer.start();
                scheduleRemoteVideoStartupWatchdog();
            } catch (IllegalStateException ignored) {
                // Leave playback paused if the player state changed during recovery.
            }
        }
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private void seekRemoteVideoTo(int positionMillis) {
        if (remoteMediaPlayer == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            remoteMediaPlayer.seekTo(positionMillis, MediaPlayer.SEEK_CLOSEST_SYNC);
        } else {
            remoteMediaPlayer.seekTo(positionMillis);
        }
    }

    private void pauseRemoteVideoForBuffering() {
        if (remoteVideoSeekStartPending || remoteVideoStartupRecoveryPending || remoteVideoInitialBuffering || !remoteVideoPrepared || remoteMediaPlayer == null || actionInProgress || isFinishing()) {
            return;
        }
        if (!remoteVideoAutoBuffering) {
            remoteVideoAutoBuffering = true;
            remoteVideoResumeAfterBuffering = false;
            try {
                remoteVideoResumeAfterBuffering = remoteMediaPlayer.isPlaying() && !remoteVideoUserPaused;
                if (remoteVideoResumeAfterBuffering) {
                    remoteMediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
                remoteVideoResumeAfterBuffering = false;
            }
            showLoading(t("Buffering video", "正在缓冲视频"));
            if (remoteMediaController != null) {
                remoteMediaController.show(0);
            }
        }
        bringOverlayControlsToFront();
    }

    private void resumeRemoteVideoAfterBuffering() {
        if (remoteVideoSeekStartPending || remoteVideoStartupRecoveryPending || remoteVideoInitialBuffering || !remoteVideoAutoBuffering || remoteMediaPlayer == null || isFinishing()) {
            return;
        }
        boolean shouldResume = remoteVideoResumeAfterBuffering && !remoteVideoUserPaused && !actionInProgress;
        remoteVideoAutoBuffering = false;
        remoteVideoResumeAfterBuffering = false;
        hideLoading();
        if (shouldResume) {
            try {
                remoteMediaPlayer.start();
                if (remoteMediaController != null) {
                    remoteMediaController.show(1500);
                }
            } catch (IllegalStateException ignored) {
                // Playback state changed while buffering; leave it paused.
            }
        }
        bringOverlayControlsToFront();
        enterImmersiveMode();
    }

    private void playLocalVideo(Uri uri) {
        if (isFinishing()) {
            return;
        }
        releaseRemoteVideoPlayer();
        removeVideoViews();
        photoView = null;

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        addVideoControllerAnchor();

        MediaController controller = new MediaController(this);
        videoView.setMediaController(controller);
        videoView.setVideoURI(uri);
        controller.setAnchorView(videoControllerAnchor);
        videoView.setOnPreparedListener(player -> {
            hideLoading();
            player.setLooping(false);
            controller.setAnchorView(videoControllerAnchor);
            videoView.start();
            controller.show(1500);
            enterImmersiveMode();
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            showError(t("Could not play video", "无法播放视频"));
            return true;
        });
        videoView.requestFocus();
        bringOverlayControlsToFront();
    }

    private void addVideoControllerAnchor() {
        if (videoControllerAnchor != null) {
            root.removeView(videoControllerAnchor);
        }
        videoControllerAnchor = new View(this);
        videoControllerAnchor.setClickable(false);
        FrameLayout.LayoutParams anchorParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        anchorParams.setMargins(0, 0, 0, videoControllerBottomMargin());
        root.addView(videoControllerAnchor, anchorParams);
    }

    private void removeVideoViews() {
        if (videoView != null) {
            videoView.stopPlayback();
            root.removeView(videoView);
            videoView = null;
        }
        if (root == null) {
            releaseRemoteVideoSurface();
            return;
        }
        if (remoteVideoTexture != null) {
            root.removeView(remoteVideoTexture);
            remoteVideoTexture = null;
            releaseRemoteVideoSurface();
        }
        if (videoControllerAnchor != null) {
            root.removeView(videoControllerAnchor);
            videoControllerAnchor = null;
        }
    }

    private void releaseRemoteVideoSurface() {
        if (remoteVideoOutputSurface != null) {
            remoteVideoOutputSurface.release();
            remoteVideoOutputSurface = null;
        }
        remoteVideoWidth = 0;
        remoteVideoHeight = 0;
    }

    private void updateRemoteVideoTransform() {
        if (remoteVideoTexture == null || remoteVideoWidth <= 0 || remoteVideoHeight <= 0) {
            return;
        }
        int viewWidth = remoteVideoTexture.getWidth();
        int viewHeight = remoteVideoTexture.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float scale = Math.min(viewWidth / (float) remoteVideoWidth, viewHeight / (float) remoteVideoHeight);
        float scaledWidth = remoteVideoWidth * scale;
        float scaledHeight = remoteVideoHeight * scale;
        Matrix transform = new Matrix();
        transform.setScale(scaledWidth / viewWidth, scaledHeight / viewHeight, viewWidth / 2f, viewHeight / 2f);
        remoteVideoTexture.setTransform(transform);
    }

    private void setupRemoteMediaController() {
        if (videoControllerAnchor == null || remoteMediaPlayer == null) {
            return;
        }
        remoteMediaController = new MediaController(this);
        remoteMediaController.setMediaPlayer(new RemoteMediaControl());
        remoteMediaController.setAnchorView(videoControllerAnchor);
        remoteMediaController.setEnabled(true);
        videoControllerAnchor.setClickable(true);
        videoControllerAnchor.setOnClickListener(view -> remoteMediaController.show());
    }

    private void releaseRemoteVideoPlayer() {
        if (remoteMediaController != null) {
            remoteMediaController.hide();
            remoteMediaController = null;
        }
        if (remoteMediaPlayer != null) {
            try {
                remoteMediaPlayer.release();
            } catch (RuntimeException ignored) {
                // The player may already be releasing after an error callback.
            }
            remoteMediaPlayer = null;
        }
        closeRemoteDataSource(remoteMediaDataSource);
        remoteMediaDataSource = null;
        main.removeCallbacks(remoteVideoStartupWatchdog);
        remoteVideoPrepared = false;
        remoteVideoUserPaused = false;
        remoteVideoInitialBuffering = false;
        remoteVideoAutoBuffering = false;
        remoteVideoResumeAfterBuffering = false;
        remoteVideoSeekStartPending = false;
        remoteVideoStartupRecoveryPending = false;
        remoteVideoStartupRecoveryCount = 0;
        remoteVideoLastFrameMillis = 0L;
    }

    private static void closeRemoteDataSource(RemoteVideoCacheDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread closer = new Thread(() -> closeRemoteDataSourceNow(dataSource), "Samba video close");
            closer.start();
            return;
        }
        closeRemoteDataSourceNow(dataSource);
    }

    private static void closeRemoteDataSourceNow(RemoteVideoCacheDataSource dataSource) {
        try {
            dataSource.close();
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private void showLoading(String message) {
        hideLoading();
        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(progress, progressParams);

        status = new TextView(this);
        status.setText(message);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Color.argb(150, 0, 0, 0));
        int padX = dp(16);
        int padY = dp(8);
        status.setPadding(padX, padY, padX, padY);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM
        );
        statusParams.setMargins(dp(16), dp(16), dp(16), bottomStatusMargin());
        root.addView(status, statusParams);
        bringOverlayControlsToFront();
    }

    private void hideLoading() {
        if (progress != null) {
            root.removeView(progress);
            progress = null;
        }
        if (status != null) {
            root.removeView(status);
            status = null;
        }
    }

    private void showError(String message) {
        hideLoading();
        if (status == null) {
            status = new TextView(this);
            status.setTextColor(Color.WHITE);
            status.setTextSize(16);
            status.setGravity(Gravity.CENTER);
            status.setPadding(dp(20), dp(14), dp(20), dp(14));
            status.setBackgroundColor(Color.argb(170, 0, 0, 0));
            root.addView(status, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            ));
        }
        status.setText(message);
        bringOverlayControlsToFront();
    }

    private void uploadLocalCurrentItem() {
        if (actionInProgress || isRemote() || TextUtils.isEmpty(uriString)) {
            return;
        }
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showActionToast(t("Set Samba folder first", "请先设置 Samba 文件夹"));
            return;
        }

        ArrayList<PhotoItem> item = new ArrayList<>();
        item.add(currentLocalPhotoItem());
        actionInProgress = true;
        updateActionButtons();
        showLoading(t("Uploading", "正在上传"));

        executor.execute(() -> {
            SambaUploader.Summary summary = SambaUploader.upload(
                    getApplicationContext(),
                    settings,
                    item,
                    new SambaUploader.Listener() {
                        @Override
                        public void onProgress(int done, int total, String message) {
                            main.post(() -> updateLoadingMessage(message));
                        }

                        @Override
                        public void onItemFinished(PhotoItem item) {
                        }
                    }
            );

            main.post(() -> {
                actionInProgress = false;
                hideLoading();
                updateActionButtons();
                if (summary.failed == 0) {
                    markMediaChanged();
                    if (uploadButton != null) {
                        uploadButton.setEnabled(false);
                    }
                    showActionToast(summary.skipped > 0 ? t("Already synced", "已同步") : t("Upload complete", "上传完成"));
                } else {
                    showActionToast(t("Upload failed", "上传失败"));
                }
                bringOverlayControlsToFront();
            });
        });
    }

    private void downloadRemoteCurrentItem() {
        if (actionInProgress || !isRemote() || TextUtils.isEmpty(url)) {
            return;
        }
        SambaSettings settings = SambaSettings.load(this);
        if (!settings.isConfigured()) {
            showActionToast(t("Set Samba folder first", "请先设置 Samba 文件夹"));
            return;
        }
        if (!hasDownloadPermission()) {
            retryDownloadAfterPermission = true;
            requestDownloadPermission();
            return;
        }

        ArrayList<RemotePhotoItem> item = new ArrayList<>();
        item.add(currentRemotePhotoItem());
        actionInProgress = true;
        updateActionButtons();
        showLoading(t("Downloading", "正在下载"));

        executor.execute(() -> {
            SambaDownloader.Summary summary = SambaDownloader.download(
                    getApplicationContext(),
                    settings,
                    item,
                    new SambaDownloader.Listener() {
                        @Override
                        public void onProgress(int done, int total, String message) {
                            main.post(() -> updateLoadingMessage(message));
                        }

                        @Override
                        public void onItemFinished(RemotePhotoItem remoteItem, PhotoItem localItem) {
                        }
                    }
            );

            main.post(() -> {
                actionInProgress = false;
                hideLoading();
                updateActionButtons();
                if (summary.failed == 0 && summary.downloaded + summary.skipped > 0) {
                    markMediaChanged();
                    showActionToast(summary.skipped > 0 ? t("Already on phone", "已在手机上") : t("Download complete", "下载完成"));
                } else {
                    showActionToast(t("Download failed", "下载失败"));
                }
                bringOverlayControlsToFront();
            });
        });
    }

    private RemotePhotoItem currentRemotePhotoItem() {
        String itemName = TextUtils.isEmpty(name) ? (video ? t("video", "视频") : t("photo", "照片")) : name;
        return new RemotePhotoItem(itemName, url, thumbnailUrl, size, modified, video);
    }

    private PhotoItem currentLocalPhotoItem() {
        Uri uri = Uri.parse(uriString);
        long id = 0L;
        try {
            id = ContentUris.parseId(uri);
        } catch (RuntimeException ignored) {
            // Some providers do not expose a numeric media id.
        }
        long modifiedSeconds = modified > 0L ? modified / 1000L : 0L;
        String itemName = TextUtils.isEmpty(name) ? uri.getLastPathSegment() : name;
        if (TextUtils.isEmpty(itemName)) {
            itemName = video ? t("video", "视频") : t("photo", "照片");
        }
        return new PhotoItem(id, uri, itemName, size, modifiedSeconds, modified, false, video);
    }

    private void confirmDeleteCurrentItem() {
        if (actionInProgress) {
            return;
        }
        String mediaName = TextUtils.isEmpty(name) ? t("this media", "此媒体") : "\"" + name + "\"";
        String title = isRemote() ? t("Delete from Samba?", "从 Samba 删除？") : t("Delete from phone?", "从手机删除？");
        String source = isRemote() ? "Samba" : t("this phone", "手机");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(isChinese()
                        ? "要从" + source + "删除" + mediaName + "吗？此操作无法撤销。"
                        : "Delete " + mediaName + " from " + source + "? This cannot be undone.")
                .setNegativeButton(t("Cancel", "取消"), null)
                .setPositiveButton(t("Delete", "删除"), (d, which) -> deleteCurrentItem())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(190, 34, 34)));
        dialog.show();
    }

    private void deleteCurrentItem() {
        if (isRemote()) {
            deleteRemoteCurrentItem();
        } else {
            deleteLocalCurrentItem();
        }
    }

    private void deleteRemoteCurrentItem() {
        if (TextUtils.isEmpty(url)) {
            showActionToast(t("Nothing to delete", "没有可删除的内容"));
            return;
        }
        stopCurrentPlaybackForAction();
        actionInProgress = true;
        updateActionButtons();
        showLoading(t("Deleting", "正在删除"));

        executor.execute(() -> {
            boolean deleted = false;
            try {
                SambaSettings settings = SambaSettings.load(this);
                CIFSContext context = SambaUploader.createContext(settings);
                SmbFile file = new SmbFile(url, context);
                if (file.exists()) {
                    file.delete();
                }
                deleteRemoteThumbnail(context);
                deleted = true;
            } catch (Exception ignored) {
            }
            boolean success = deleted;
            main.post(() -> {
                actionInProgress = false;
                hideLoading();
                updateActionButtons();
                if (success) {
                    markMediaChanged();
                    showActionToast(t("Deleted", "已删除"));
                    afterDeleteCurrentItem();
                } else {
                    showActionToast(t("Delete failed", "删除失败"));
                    bringOverlayControlsToFront();
                }
            });
        });
    }

    private void deleteRemoteThumbnail(CIFSContext context) {
        try {
            SambaThumbnailStore.deleteIfPresent(context, thumbnailUrl);
        } catch (Exception ignored) {
            // The main file is what matters; stale thumbnail cleanup is best effort.
        }
    }

    private void deleteLocalCurrentItem() {
        if (TextUtils.isEmpty(uriString)) {
            showActionToast(t("Nothing to delete", "没有可删除的内容"));
            return;
        }
        stopCurrentPlaybackForAction();
        Uri uri = Uri.parse(uriString);
        actionInProgress = true;
        updateActionButtons();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestLocalDeletePermission(uri);
            return;
        }

        try {
            int deleted = getContentResolver().delete(uri, null, null);
            actionInProgress = false;
            updateActionButtons();
            if (deleted > 0) {
                markMediaChanged();
                showActionToast(t("Deleted", "已删除"));
                afterDeleteCurrentItem();
            } else {
                showActionToast(t("Delete failed", "删除失败"));
            }
        } catch (SecurityException exc) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exc instanceof RecoverableSecurityException) {
                requestRecoverableLocalDelete((RecoverableSecurityException) exc);
            } else {
                actionInProgress = false;
                updateActionButtons();
                showActionToast(t("Delete not allowed", "不允许删除"));
            }
        }
    }

    private void requestLocalDeletePermission(Uri uri) {
        try {
            PendingIntent deleteRequest = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(uri));
            startIntentSenderForResult(deleteRequest.getIntentSender(), REQUEST_DELETE_LOCAL, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException exc) {
            actionInProgress = false;
            updateActionButtons();
            showActionToast(t("Delete not allowed", "不允许删除"));
        }
    }

    private void requestRecoverableLocalDelete(RecoverableSecurityException exc) {
        try {
            PendingIntent actionIntent = exc.getUserAction().getActionIntent();
            startIntentSenderForResult(actionIntent.getIntentSender(), REQUEST_DELETE_LOCAL, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException sendExc) {
            actionInProgress = false;
            updateActionButtons();
            showActionToast(t("Delete not allowed", "不允许删除"));
        }
    }

    private void stopCurrentPlaybackForAction() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        releaseRemoteVideoPlayer();
    }

    private void afterDeleteCurrentItem() {
        removeCurrentNavigationItem();
        if (!navigationItems.isEmpty()) {
            int nextIndex = Math.min(currentIndex, navigationItems.size() - 1);
            setNavigationSession(navigationItems);
            startActivity(intentFor(this, navigationItems.get(nextIndex), nextIndex));
            overridePendingTransition(0, 0);
        }
        finish();
    }

    private void removeCurrentNavigationItem() {
        if (currentIndex >= 0 && currentIndex < navigationItems.size()) {
            navigationItems.remove(currentIndex);
            return;
        }
        String currentRemote = url;
        String currentLocal = uriString;
        for (int index = 0; index < navigationItems.size(); index++) {
            ViewerItem item = navigationItems.get(index);
            if (TextUtils.equals(currentRemote, item.url) && TextUtils.equals(currentLocal, item.uriString)) {
                navigationItems.remove(index);
                currentIndex = Math.min(index, navigationItems.size());
                return;
            }
        }
    }

    private void updateLoadingMessage(String message) {
        if (status != null) {
            status.setText(message);
        }
    }

    private void showActionToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean hasDownloadPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestDownloadPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_DOWNLOAD_REMOTE);
    }

    private String t(String english, String chinese) {
        return UiText.text(this, english, chinese);
    }

    private boolean isChinese() {
        return UiText.isChinese(this);
    }

    private String remoteVideoBufferingText(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        return isChinese() ? "正在缓冲视频 " + safePercent + "%" : "Buffering video " + safePercent + "%";
    }

    private void addBackButton() {
        backButton = new ImageButton(this);
        backButton.setImageResource(R.drawable.ic_arrow_back);
        backButton.setContentDescription(t("Back", "返回"));
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(155, 0, 0, 0));
        backButton.setBackground(background);
        backButton.setOnClickListener(view -> finish());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START);
        params.setMargins(dp(14), topControlMargin(), 0, 0);
        root.addView(backButton, params);
    }

    private void addRotateButton() {
        if (video) {
            return;
        }
        rotateButton = new ImageButton(this);
        rotateButton.setImageResource(R.drawable.ic_rotate_photo);
        rotateButton.setContentDescription(t("Rotate", "旋转"));
        rotateButton.setScaleType(ImageView.ScaleType.CENTER);
        rotateButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(155, 0, 0, 0));
        rotateButton.setBackground(background);
        rotateButton.setOnClickListener(view -> rotateCurrentPhoto());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        params.setMargins(0, topControlMargin(), dp(14), 0);
        root.addView(rotateButton, params);
    }

    private void rotateCurrentPhoto() {
        if (video || actionInProgress) {
            return;
        }
        photoRotationDegrees = (photoRotationDegrees + 90) % 360;
        if (photoView != null) {
            photoView.setRotationDegrees(photoRotationDegrees);
        }
        if (thumbnailView != null) {
            thumbnailView.setRotationDegrees(photoRotationDegrees);
        }
        bringOverlayControlsToFront();
    }

    private void addActionButtons() {
        actionBar = new LinearLayout(this);
        actionBar.setGravity(Gravity.CENTER);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);

        if (isRemote()) {
            downloadButton = actionButton(t("Download", "下载"), false);
            downloadButton.setOnClickListener(view -> downloadRemoteCurrentItem());
            LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            downloadParams.setMargins(0, 0, dp(8), 0);
            actionBar.addView(downloadButton, downloadParams);

            deleteButton = actionButton(t("Delete", "删除"), true);
            deleteButton.setOnClickListener(view -> confirmDeleteCurrentItem());
            actionBar.addView(deleteButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        } else {
            uploadButton = actionButton(t("Upload", "上传"), false);
            uploadButton.setOnClickListener(view -> uploadLocalCurrentItem());
            LinearLayout.LayoutParams uploadParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            uploadParams.setMargins(0, 0, dp(8), 0);
            actionBar.addView(uploadButton, uploadParams);

            deleteButton = actionButton(t("Delete", "删除"), true);
            deleteButton.setOnClickListener(view -> confirmDeleteCurrentItem());
            actionBar.addView(deleteButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        params.setMargins(dp(14), 0, dp(14), bottomControlMargin());
        root.addView(actionBar, params);
        updateActionButtons();
    }

    private Button actionButton(String text, boolean destructive) {
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
        background.setColor(destructive ? Color.rgb(190, 34, 34) : Color.rgb(23, 104, 172));
        button.setBackground(background);
        return button;
    }

    private void updateActionButtons() {
        if (uploadButton != null) {
            uploadButton.setEnabled(!actionInProgress && !isRemote());
        }
        if (downloadButton != null) {
            downloadButton.setEnabled(!actionInProgress && isRemote());
        }
        if (deleteButton != null) {
            deleteButton.setEnabled(!actionInProgress);
        }
        if (rotateButton != null) {
            rotateButton.setEnabled(!actionInProgress && !video);
        }
    }

    private int topControlMargin() {
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        return Math.max(dp(40), statusBarHeight + dp(12));
    }

    private int bottomControlMargin() {
        int navigationBarHeight = 0;
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navigationBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        return Math.max(dp(24), navigationBarHeight + dp(10));
    }

    private void bringOverlayControlsToFront() {
        if (backButton != null) {
            backButton.bringToFront();
        }
        if (rotateButton != null) {
            rotateButton.bringToFront();
        }
        if (actionBar != null) {
            actionBar.bringToFront();
        }
        if (progress != null) {
            progress.bringToFront();
        }
        if (status != null) {
            status.bringToFront();
        }
    }

    private int bottomStatusMargin() {
        return actionBar != null ? bottomControlMargin() + dp(64) : dp(36);
    }

    private int videoControllerBottomMargin() {
        return actionBar != null ? bottomControlMargin() + dp(72) : dp(56);
    }

    private boolean canNavigateBySwipe() {
        return photoView == null || photoView.isAtBaseScale();
    }

    private void navigateByOffset(int offset) {
        if (mediaNavigationInProgress || navigationItems.size() <= 1) {
            return;
        }
        int nextIndex = currentIndex + offset;
        if (nextIndex < 0 || nextIndex >= navigationItems.size()) {
            return;
        }
        mediaNavigationInProgress = true;
        startActivity(intentFor(this, navigationItems.get(nextIndex), nextIndex));
        overridePendingTransition(0, 0);
        finish();
    }

    private boolean isRemote() {
        return !TextUtils.isEmpty(url);
    }


    private static int sampleSize(int width, int height, int target) {
        if (width <= 0 || height <= 0 || target <= 0) {
            return 1;
        }
        int sample = 1;
        while (height / sample > target * 2 || width / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class PhotoOrientation {
        final int orientation;

        PhotoOrientation(int orientation) {
            this.orientation = orientation;
        }

        static PhotoOrientation undefined() {
            return new PhotoOrientation(ExifInterface.ORIENTATION_UNDEFINED);
        }

        static PhotoOrientation from(ExifInterface exif) {
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            return new PhotoOrientation(orientation);
        }
    }

    private static final class ViewerItem {
        final String name;
        final String url;
        final String thumbnailUrl;
        final String uriString;
        final long size;
        final long modified;
        final boolean video;

        ViewerItem(String name, String url, String thumbnailUrl, String uriString, long size, long modified, boolean video) {
            this.name = name;
            this.url = url;
            this.thumbnailUrl = thumbnailUrl;
            this.uriString = uriString;
            this.size = size;
            this.modified = modified;
            this.video = video;
        }

        static ViewerItem from(RemotePhotoItem item) {
            return new ViewerItem(item.name, item.url, item.thumbnailUrl, null, item.size, item.lastModifiedMillis, item.video);
        }

        static ViewerItem from(PhotoItem item) {
            return new ViewerItem(item.name, null, null, item.uri.toString(), item.size, item.dateModifiedSeconds * 1000L, item.video);
        }

        static ViewerItem from(Intent intent) {
            return new ViewerItem(
                    intent.getStringExtra(EXTRA_NAME),
                    intent.getStringExtra(EXTRA_URL),
                    intent.getStringExtra(EXTRA_THUMBNAIL_URL),
                    intent.getStringExtra(EXTRA_URI),
                    intent.getLongExtra(EXTRA_SIZE, 0L),
                    intent.getLongExtra(EXTRA_MODIFIED, 0L),
                    intent.getBooleanExtra(EXTRA_VIDEO, false)
            );
        }

        boolean hasMedia() {
            return !TextUtils.isEmpty(url) || !TextUtils.isEmpty(uriString);
        }
    }

    private final class NavigationGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
            if (down == null || up == null || multiTouchGesture || !canNavigateBySwipe()) {
                return false;
            }
            float deltaX = up.getX() - down.getX();
            float deltaY = up.getY() - down.getY();
            if (Math.abs(deltaX) < dp(80) || Math.abs(deltaX) < Math.abs(deltaY) * 1.4f || Math.abs(velocityX) < dp(260)) {
                return false;
            }
            navigateByOffset(deltaX < 0f ? 1 : -1);
            return true;
        }
    }

    private final class RemoteMediaControl implements MediaController.MediaPlayerControl {
        @Override
        public void start() {
            if (remoteMediaPlayer != null) {
                remoteVideoUserPaused = false;
                if (remoteVideoInitialBuffering || remoteVideoAutoBuffering || remoteVideoSeekStartPending || remoteVideoStartupRecoveryPending) {
                    remoteVideoResumeAfterBuffering = true;
                    return;
                }
                remoteMediaPlayer.start();
                scheduleRemoteVideoStartupWatchdog();
            }
        }

        @Override
        public void pause() {
            if (remoteMediaPlayer != null) {
                remoteVideoUserPaused = true;
                remoteVideoResumeAfterBuffering = false;
                if (remoteVideoInitialBuffering) {
                    return;
                }
                remoteMediaPlayer.pause();
            }
        }

        @Override
        public int getDuration() {
            try {
                return remoteMediaPlayer != null ? remoteMediaPlayer.getDuration() : 0;
            } catch (IllegalStateException exc) {
                return 0;
            }
        }

        @Override
        public int getCurrentPosition() {
            try {
                return remoteMediaPlayer != null ? remoteMediaPlayer.getCurrentPosition() : 0;
            } catch (IllegalStateException exc) {
                return 0;
            }
        }

        @Override
        public void seekTo(int pos) {
            if (remoteMediaPlayer != null) {
                remoteMediaPlayer.seekTo(pos);
            }
        }

        @Override
        public boolean isPlaying() {
            try {
                return remoteMediaPlayer != null && remoteMediaPlayer.isPlaying();
            } catch (IllegalStateException exc) {
                return false;
            }
        }

        @Override
        public int getBufferPercentage() {
            return remoteMediaDataSource == null ? 0 : remoteMediaDataSource.bufferPercentage();
        }

        @Override
        public boolean canPause() {
            return true;
        }

        @Override
        public boolean canSeekBackward() {
            return true;
        }

        @Override
        public boolean canSeekForward() {
            return true;
        }

        @Override
        public int getAudioSessionId() {
            try {
                return remoteMediaPlayer != null ? remoteMediaPlayer.getAudioSessionId() : 0;
            } catch (IllegalStateException exc) {
                return 0;
            }
        }
    }

    private static final class RemoteVideoCacheDataSource extends MediaDataSource {
        interface Listener {
            void onBuffering();

            void onReady();
        }

        private static final int COPY_BUFFER_BYTES = 256 * 1024;
        private static final long RANDOM_READ_GAP_BYTES = 8L * 1024L * 1024L;
        private static final long REBUFFER_READY_BYTES = 12L * 1024L * 1024L;
        private static final long MIN_INITIAL_READY_BYTES = 4L * 1024L * 1024L;
        private static final long FALLBACK_INITIAL_READY_BYTES = 6L * 1024L * 1024L;
        private static final long MAX_INITIAL_READY_BYTES = 12L * 1024L * 1024L;

        private final Object stateLock = new Object();
        private final Object randomReadLock = new Object();
        private final SmbFile source;
        private final File cacheFile;
        private final RandomAccessFile cacheReader;
        private final long length;
        private Thread copyThread;
        private SmbRandomAccessFile randomReader;
        private long cachedLength;
        private boolean complete;
        private boolean closed;
        private IOException copyFailure;
        private Listener listener;

        RemoteVideoCacheDataSource(SmbFile source, long declaredLength, File cacheDirectory) throws IOException {
            this.source = source;
            length = declaredLength > 0L ? declaredLength : source.length();
            cacheFile = File.createTempFile("samba_video_", ".cache", cacheDirectory);
            cacheReader = new RandomAccessFile(cacheFile, "r");
            startCopyThread();
        }

        void setListener(Listener listener) {
            synchronized (stateLock) {
                this.listener = listener;
            }
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (position < 0L) {
                return -1;
            }
            if (size == 0) {
                return 0;
            }
            if (length > 0L && position >= length) {
                return -1;
            }
            int maxBytes = size;
            if (length > 0L) {
                maxBytes = (int) Math.min(size, length - position);
            }

            boolean pausedForBuffering = false;
            while (true) {
                long available;
                boolean canWaitForSequentialCache;
                boolean readyAfterBuffering;
                synchronized (stateLock) {
                    if (closed) {
                        return -1;
                    }
                    available = cachedLength - position;
                    readyAfterBuffering = pausedForBuffering
                            && (complete || available >= rebufferReadyBytes(position, maxBytes));
                    if (available > 0L && (!pausedForBuffering || readyAfterBuffering)) {
                        break;
                    }
                    if (complete) {
                        return copyFailure == null ? -1 : readFromRemote(position, buffer, offset, maxBytes);
                    }
                    canWaitForSequentialCache = position <= cachedLength + RANDOM_READ_GAP_BYTES;
                    if (!canWaitForSequentialCache) {
                        break;
                    }
                }

                if (!pausedForBuffering) {
                    pausedForBuffering = true;
                    notifyBuffering();
                }

                synchronized (stateLock) {
                    try {
                        stateLock.wait(350L);
                    } catch (InterruptedException exc) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                if (!canWaitForSequentialCache) {
                    break;
                }
            }
            if (pausedForBuffering) {
                notifyReady();
            }

            synchronized (stateLock) {
                long available = cachedLength - position;
                if (available > 0L) {
                    return readFromCache(position, buffer, offset, (int) Math.min(maxBytes, available));
                }
            }
            return readFromRemote(position, buffer, offset, maxBytes);
        }

        @Override
        public long getSize() {
            return length;
        }

        int bufferPercentage() {
            if (length <= 0L) {
                return 0;
            }
            synchronized (stateLock) {
                return (int) Math.max(0L, Math.min(100L, cachedLength * 100L / length));
            }
        }

        boolean hasPlaybackBuffer(long position, long durationMillis, long targetMillis) {
            long targetBytes = playbackBufferBytes(position, durationMillis, targetMillis);
            synchronized (stateLock) {
                return complete || cachedLength - position >= targetBytes;
            }
        }

        int playbackBufferPercent(long position, long durationMillis, long targetMillis) {
            long targetBytes = playbackBufferBytes(position, durationMillis, targetMillis);
            if (targetBytes <= 0L) {
                return 100;
            }
            synchronized (stateLock) {
                if (complete) {
                    return 100;
                }
                long available = Math.max(0L, cachedLength - position);
                return (int) Math.max(0L, Math.min(100L, available * 100L / targetBytes));
            }
        }

        @Override
        public void close() throws IOException {
            Thread threadToJoin;
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                closed = true;
                threadToJoin = copyThread;
                stateLock.notifyAll();
            }
            if (threadToJoin != null) {
                threadToJoin.interrupt();
            }
            synchronized (randomReadLock) {
                if (randomReader != null) {
                    randomReader.close();
                    randomReader = null;
                }
            }
            cacheReader.close();
            if (!cacheFile.delete()) {
                cacheFile.deleteOnExit();
            }
        }

        private void startCopyThread() {
            copyThread = new Thread(this::copyRemoteToCache, "Samba video cache");
            copyThread.setDaemon(true);
            copyThread.start();
        }

        private void copyRemoteToCache() {
            long written = 0L;
            try (BufferedInputStream input = new BufferedInputStream(new SmbFileInputStream(source));
                 FileOutputStream output = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while (!isClosed() && (read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                    synchronized (stateLock) {
                        cachedLength = written;
                        stateLock.notifyAll();
                    }
                }
                output.flush();
                synchronized (stateLock) {
                    complete = true;
                    cachedLength = Math.max(cachedLength, written);
                    stateLock.notifyAll();
                }
            } catch (IOException exc) {
                synchronized (stateLock) {
                    copyFailure = exc;
                    complete = true;
                    stateLock.notifyAll();
                }
            }
        }

        private boolean isClosed() {
            synchronized (stateLock) {
                return closed;
            }
        }

        private int readFromCache(long position, byte[] buffer, int offset, int bytesToRead) throws IOException {
            synchronized (cacheReader) {
                cacheReader.seek(position);
                return cacheReader.read(buffer, offset, bytesToRead);
            }
        }

        private int readFromRemote(long position, byte[] buffer, int offset, int bytesToRead) throws IOException {
            synchronized (randomReadLock) {
                if (randomReader == null) {
                    randomReader = new SmbRandomAccessFile(source, "r");
                }
                for (int attempt = 0; attempt < 3; attempt++) {
                    randomReader.seek(position);
                    int read = randomReader.read(buffer, offset, bytesToRead);
                    if (read >= 0 || length <= 0L || position >= length) {
                        return read;
                    }
                    try {
                        Thread.sleep(80L);
                    } catch (InterruptedException exc) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                throw new IOException("Could not read remote video bytes");
            }
        }

        private long rebufferReadyBytes(long position, int maxBytes) {
            long target = Math.max((long) maxBytes, REBUFFER_READY_BYTES);
            if (length <= 0L) {
                return target;
            }
            return Math.max(1L, Math.min(target, length - position));
        }

        private long playbackBufferBytes(long position, long durationMillis, long targetMillis) {
            long target;
            if (length > 0L && durationMillis > 0L) {
                target = (long) Math.ceil(length * (targetMillis / (double) durationMillis));
            } else {
                target = FALLBACK_INITIAL_READY_BYTES;
            }
            target = Math.max(MIN_INITIAL_READY_BYTES, target);
            target = Math.min(MAX_INITIAL_READY_BYTES, target);
            if (length > 0L) {
                target = Math.min(target, Math.max(1L, length - position));
            }
            return Math.max(1L, target);
        }

        private void notifyBuffering() {
            Listener current;
            synchronized (stateLock) {
                current = listener;
            }
            if (current != null) {
                current.onBuffering();
            }
        }

        private void notifyReady() {
            Listener current;
            synchronized (stateLock) {
                current = listener;
            }
            if (current != null) {
                current.onReady();
            }
        }
    }

    private static final class ZoomImageView extends View {
        private final Matrix matrix = new Matrix();
        private final RectF bitmapBounds = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private Bitmap bitmap;
        private float minScale = 1f;
        private float maxScale = 5f;
        private float currentScale = 1f;
        private float lastX;
        private float lastY;
        private int rotationDegrees;
        private boolean zoomEnabled = true;

        ZoomImageView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (bitmap == null) {
                        return false;
                    }
                    float factor = detector.getScaleFactor();
                    float next = currentScale * factor;
                    if (next < minScale) {
                        factor = minScale / currentScale;
                    } else if (next > maxScale) {
                        factor = maxScale / currentScale;
                    }
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    currentScale *= factor;
                    clampTranslation();
                    invalidate();
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent event) {
                    if (bitmap == null) {
                        return true;
                    }
                    float target = currentScale > minScale * 1.2f ? minScale : Math.min(maxScale, minScale * 2.5f);
                    float factor = target / currentScale;
                    matrix.postScale(factor, factor, event.getX(), event.getY());
                    currentScale = target;
                    clampTranslation();
                    invalidate();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
            resetMatrix();
            invalidate();
        }

        void setZoomEnabled(boolean zoomEnabled) {
            this.zoomEnabled = zoomEnabled;
        }

        void setRotationDegrees(int rotationDegrees) {
            int normalized = rotationDegrees % 360;
            if (normalized < 0) {
                normalized += 360;
            }
            this.rotationDegrees = normalized;
            resetMatrix();
            invalidate();
        }

        boolean isAtBaseScale() {
            return currentScale <= minScale * 1.05f;
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            resetMatrix();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, matrix, null);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!zoomEnabled) {
                return true;
            }
            gestureDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);

            if (bitmap == null || scaleDetector.isInProgress()) {
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && currentScale > minScale) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        clampTranslation();
                        invalidate();
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                default:
                    return true;
            }
        }

        private void resetMatrix() {
            if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            boolean quarterTurn = rotationDegrees == 90 || rotationDegrees == 270;
            int rotatedWidth = quarterTurn ? bitmap.getHeight() : bitmap.getWidth();
            int rotatedHeight = quarterTurn ? bitmap.getWidth() : bitmap.getHeight();
            float scale = Math.min(
                    getWidth() / (float) rotatedWidth,
                    getHeight() / (float) rotatedHeight
            );
            matrix.reset();
            matrix.postTranslate(-bitmap.getWidth() / 2f, -bitmap.getHeight() / 2f);
            matrix.postRotate(rotationDegrees);
            matrix.postScale(scale, scale);
            matrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
            minScale = scale;
            currentScale = scale;
            maxScale = Math.max(scale * 5f, 5f);
        }

        private void clampTranslation() {
            if (bitmap == null) {
                return;
            }
            bitmapBounds.set(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
            matrix.mapRect(bitmapBounds);
            float dx = 0f;
            float dy = 0f;

            if (bitmapBounds.width() <= getWidth()) {
                dx = getWidth() / 2f - bitmapBounds.centerX();
            } else if (bitmapBounds.left > 0f) {
                dx = -bitmapBounds.left;
            } else if (bitmapBounds.right < getWidth()) {
                dx = getWidth() - bitmapBounds.right;
            }

            if (bitmapBounds.height() <= getHeight()) {
                dy = getHeight() / 2f - bitmapBounds.centerY();
            } else if (bitmapBounds.top > 0f) {
                dy = -bitmapBounds.top;
            } else if (bitmapBounds.bottom < getHeight()) {
                dy = getHeight() - bitmapBounds.bottom;
            }

            if (dx != 0f || dy != 0f) {
                matrix.postTranslate(dx, dy);
            }
        }
    }
}
