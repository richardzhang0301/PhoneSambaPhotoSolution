package com.diytools.phonesambaphoto;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
    private static final Object NAVIGATION_LOCK = new Object();
    private static ArrayList<ViewerItem> navigationSession = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ProgressBar progress;
    private TextView status;
    private ImageButton backButton;
    private ImageView thumbnailView;
    private ZoomImageView photoView;
    private VideoView videoView;
    private TextureView remoteVideoTexture;
    private Surface remoteVideoOutputSurface;
    private MediaPlayer remoteMediaPlayer;
    private MediaController remoteMediaController;
    private RemoteSmbMediaDataSource remoteMediaDataSource;
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
        enterImmersiveMode();

        if (video) {
            showLoading("Loading video");
            loadVideo();
        } else {
            showLoading("Loading photo");
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
                main.post(() -> showError("Could not open photo"));
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
                    showError("Could not load full photo");
                } else {
                    showError("Could not open photo");
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
        thumbnailView = new ImageView(this);
        thumbnailView.setBackgroundColor(Color.BLACK);
        thumbnailView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnailView.setImageBitmap(bitmap);
        root.addView(thumbnailView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        showLoading("Loading full photo");
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
        ZoomImageView imageView = new ZoomImageView(this);
        photoView = imageView;
        imageView.setBitmap(bitmap);
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
            return bitmap;
        }
    }

    private Bitmap decodeLocalPhoto() throws Exception {
        Uri uri = Uri.parse(uriString);
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
            return bitmap;
        }
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
        showLoading("Opening video");

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
            RemoteSmbMediaDataSource dataSource = null;
            try {
                SambaSettings settings = SambaSettings.load(this);
                CIFSContext context = SambaUploader.createContext(settings);
                SmbFile source = new SmbFile(url, context);
                dataSource = new RemoteSmbMediaDataSource(source, size);
                RemoteSmbMediaDataSource playableDataSource = dataSource;
                main.post(() -> startRemoteVideoPlayer(surface, playableDataSource));
            } catch (Exception exc) {
                closeRemoteDataSource(dataSource);
                main.post(() -> showError("Could not open video"));
            }
        });
    }

    private void startRemoteVideoPlayer(Surface surface, RemoteSmbMediaDataSource dataSource) {
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
                hideLoading();
                setupRemoteMediaController();
                player.start();
                if (remoteMediaController != null) {
                    remoteMediaController.show(1500);
                }
                bringOverlayControlsToFront();
                enterImmersiveMode();
            });
            remoteMediaPlayer.setOnErrorListener((player, what, extra) -> {
                showError("Could not play video");
                return true;
            });
            remoteMediaPlayer.prepareAsync();
        } catch (Exception exc) {
            releaseRemoteVideoPlayer();
            showError("Could not open video");
        }
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
            showError("Could not play video");
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
        anchorParams.setMargins(0, 0, 0, dp(56));
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
    }

    private static void closeRemoteDataSource(RemoteSmbMediaDataSource dataSource) {
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

    private static void closeRemoteDataSourceNow(RemoteSmbMediaDataSource dataSource) {
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
        statusParams.setMargins(dp(16), dp(16), dp(16), dp(36));
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

    private void addBackButton() {
        backButton = new ImageButton(this);
        backButton.setImageResource(R.drawable.ic_arrow_back);
        backButton.setContentDescription("Back");
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

    private int topControlMargin() {
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        return Math.max(dp(40), statusBarHeight + dp(12));
    }

    private void bringOverlayControlsToFront() {
        if (backButton != null) {
            backButton.bringToFront();
        }
        if (progress != null) {
            progress.bringToFront();
        }
        if (status != null) {
            status.bringToFront();
        }
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
                remoteMediaPlayer.start();
            }
        }

        @Override
        public void pause() {
            if (remoteMediaPlayer != null) {
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
            return 100;
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

    private static final class RemoteSmbMediaDataSource extends MediaDataSource {
        private final SmbRandomAccessFile file;
        private final long length;
        private boolean closed;

        RemoteSmbMediaDataSource(SmbFile source, long declaredLength) throws IOException {
            file = new SmbRandomAccessFile(source, "r");
            length = declaredLength > 0L ? declaredLength : file.length();
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (closed || position < 0L) {
                return -1;
            }
            if (size == 0) {
                return 0;
            }
            if (length > 0L && position >= length) {
                return -1;
            }
            int bytesToRead = size;
            if (length > 0L) {
                bytesToRead = (int) Math.min(size, length - position);
            }
            file.seek(position);
            return file.read(buffer, offset, bytesToRead);
        }

        @Override
        public long getSize() {
            return length;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            file.close();
        }
    }

    private static final class ZoomImageView extends View {
        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private Bitmap bitmap;
        private float minScale = 1f;
        private float maxScale = 5f;
        private float currentScale = 1f;
        private float lastX;
        private float lastY;

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
            float scale = Math.min(
                    getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight()
            );
            float dx = (getWidth() - bitmap.getWidth() * scale) / 2f;
            float dy = (getHeight() - bitmap.getHeight() * scale) / 2f;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy);
            minScale = scale;
            currentScale = scale;
            maxScale = Math.max(scale * 5f, 5f);
        }

        private void clampTranslation() {
            if (bitmap == null) {
                return;
            }
            matrix.getValues(values);
            float scale = values[Matrix.MSCALE_X];
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            float translateX = values[Matrix.MTRANS_X];
            float translateY = values[Matrix.MTRANS_Y];

            if (scaledWidth <= getWidth()) {
                translateX = (getWidth() - scaledWidth) / 2f;
            } else {
                translateX = Math.min(0f, Math.max(getWidth() - scaledWidth, translateX));
            }

            if (scaledHeight <= getHeight()) {
                translateY = (getHeight() - scaledHeight) / 2f;
            } else {
                translateY = Math.min(0f, Math.max(getHeight() - scaledHeight, translateY));
            }

            values[Matrix.MTRANS_X] = translateX;
            values[Matrix.MTRANS_Y] = translateY;
            matrix.setValues(values);
            currentScale = scale;
        }
    }
}
