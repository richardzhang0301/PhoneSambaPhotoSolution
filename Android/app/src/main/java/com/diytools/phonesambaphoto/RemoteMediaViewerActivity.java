package com.diytools.phonesambaphoto;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

public final class RemoteMediaViewerActivity extends Activity {
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_SIZE = "size";
    private static final String EXTRA_MODIFIED = "modified";
    private static final String EXTRA_VIDEO = "video";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ProgressBar progress;
    private TextView status;
    private VideoView videoView;
    private File videoFile;
    private String name;
    private String url;
    private String uriString;
    private long size;
    private long modified;
    private boolean video;

    static void open(Context context, RemotePhotoItem item) {
        Intent intent = new Intent(context, RemoteMediaViewerActivity.class);
        intent.putExtra(EXTRA_NAME, item.name);
        intent.putExtra(EXTRA_URL, item.url);
        intent.putExtra(EXTRA_SIZE, item.size);
        intent.putExtra(EXTRA_MODIFIED, item.lastModifiedMillis);
        intent.putExtra(EXTRA_VIDEO, item.video);
        context.startActivity(intent);
    }

    static void open(Context context, PhotoItem item) {
        Intent intent = new Intent(context, RemoteMediaViewerActivity.class);
        intent.putExtra(EXTRA_NAME, item.name);
        intent.putExtra(EXTRA_URI, item.uri.toString());
        intent.putExtra(EXTRA_SIZE, item.size);
        intent.putExtra(EXTRA_MODIFIED, item.dateModifiedSeconds * 1000L);
        intent.putExtra(EXTRA_VIDEO, item.video);
        context.startActivity(intent);
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
        uriString = intent.getStringExtra(EXTRA_URI);
        size = intent.getLongExtra(EXTRA_SIZE, 0L);
        modified = intent.getLongExtra(EXTRA_MODIFIED, 0L);
        video = intent.getBooleanExtra(EXTRA_VIDEO, false);

        if (TextUtils.isEmpty(url) && TextUtils.isEmpty(uriString)) {
            finish();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
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
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (videoView != null) {
            videoView.stopPlayback();
        }
        if (videoFile != null && videoFile.exists()) {
            videoFile.delete();
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
            try {
                Bitmap bitmap = isRemote() ? decodeRemotePhoto() : decodeLocalPhoto();
                main.post(() -> showPhoto(bitmap));
            } catch (Exception exc) {
                main.post(() -> showError("Could not open photo"));
            }
        });
    }

    private void showPhoto(Bitmap bitmap) {
        if (isFinishing()) {
            return;
        }
        hideLoading();
        ZoomImageView imageView = new ZoomImageView(this);
        imageView.setBitmap(bitmap);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        enterImmersiveMode();
    }

    private Bitmap decodeRemotePhoto() throws Exception {
        SambaSettings settings = SambaSettings.load(this);
        CIFSContext context = SambaUploader.createContext(settings);
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
        executor.execute(() -> {
            try {
                File file = copyVideoToCache();
                main.post(() -> playVideo(file));
            } catch (Exception exc) {
                main.post(() -> showError("Could not open video"));
            }
        });
    }

    private File copyVideoToCache() throws Exception {
        long total = size;
        SmbFile remoteSource = null;
        if (isRemote()) {
            SambaSettings settings = SambaSettings.load(this);
            CIFSContext context = SambaUploader.createContext(settings);
            remoteSource = new SmbFile(url, context);
            total = total > 0L ? total : remoteSource.length();
        }

        File dir = new File(getCacheDir(), "media_viewer_videos");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create video cache");
        }

        File target = new File(dir, videoCacheName());
        File temp = new File(dir, target.getName() + ".tmp");
        if (target.exists() && total > 0L && target.length() == total) {
            return target;
        }
        if (temp.exists()) {
            temp.delete();
        }

        InputStream rawInput = isRemote()
                ? new SmbFileInputStream(remoteSource)
                : getContentResolver().openInputStream(Uri.parse(uriString));
        if (rawInput == null) {
            throw new IOException("Video cannot be opened");
        }

        byte[] buffer = new byte[128 * 1024];
        long copied = 0L;
        long lastUpdate = 0L;
        try (InputStream input = new BufferedInputStream(rawInput);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Video load cancelled");
                }
                output.write(buffer, 0, read);
                copied += read;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 400L || copied == total) {
                    lastUpdate = now;
                    updateVideoProgress(copied, total);
                }
            }
            output.flush();
        }

        if (target.exists()) {
            target.delete();
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Cannot prepare video cache");
        }
        return target;
    }

    private void playVideo(File file) {
        if (isFinishing()) {
            return;
        }
        videoFile = file;
        hideLoading();

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoPath(file.getAbsolutePath());
        videoView.setOnPreparedListener(player -> {
            player.setLooping(false);
            videoView.start();
            controller.show(1500);
            enterImmersiveMode();
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            showError("Could not play video");
            return true;
        });
        videoView.requestFocus();
    }

    private void showLoading(String message) {
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
    }

    private void updateVideoProgress(long copied, long total) {
        if (total <= 0L) {
            main.post(() -> {
                if (status != null) {
                    status.setText("Loading video");
                }
            });
            return;
        }
        int percent = (int) Math.min(100L, copied * 100L / total);
        main.post(() -> {
            if (status != null) {
                status.setText(String.format(Locale.US, "Loading video %d%%", percent));
            }
        });
    }

    private String videoCacheName() throws Exception {
        String extension = extensionFor(name);
        String sourceKey = isRemote() ? url : uriString;
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest((sourceKey + "|" + size + "|" + modified).getBytes("UTF-8"));
        StringBuilder builder = new StringBuilder("viewer_");
        for (byte value : hash) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        builder.append(extension);
        return builder.toString();
    }

    private boolean isRemote() {
        return !TextUtils.isEmpty(url);
    }

    private static String extensionFor(String fileName) {
        if (fileName == null) {
            return ".mp4";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot).toLowerCase(Locale.US);
        }
        return ".mp4";
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