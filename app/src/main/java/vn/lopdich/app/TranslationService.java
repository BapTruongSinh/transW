package vn.lopdich.app;

import android.app.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TranslationService extends Service {
    static final String CONSENT = "projectionConsent";
    private static final String STOP = "stop";
    static boolean active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextView bubble;
    private View overlay;
    private WindowManager.LayoutParams bubbleParams;
    private int width, height, density;
    private volatile int generation;
    private boolean busy, waitingForFrame, destroyed;
    private SettingsStore.Options options;
    private final Gemini gemini = new Gemini();
    // Accessed only by the network executor.
    private CachedTranslation lastTranslation;

    private record CachedTranslation(SettingsStore.Options options, byte[] image,
                                     List<Gemini.Translation> translations) {}

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        startForeground(1, notification());
        try {
            Intent consent = intent.getParcelableExtra(CONSENT);
            windows = getSystemService(WindowManager.class);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
                @Override public void onCapturedContentResize(int w, int h) { resize(w, h); }
            }, main);
            density = getResources().getDisplayMetrics().densityDpi;
            Point dimensions = screenSize();
            resize(dimensions.x, dimensions.y);
            showBubble();
            active = true;
        } catch (RuntimeException failure) {
            toast("Không thể bật nút nổi. Kiểm tra quyền và thử lại.");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("projection", "Dịch màn hình",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TranslationService.class)
                .setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "projection")
                .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Lớp Dịch đang hoạt động")
                .setContentText("Bấm nút nổi để dịch màn hình hiện tại").setContentIntent(settings)
                .addAction(new Notification.Action.Builder(null, "Tắt", stop).build()).setOngoing(true).build();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (Build.VERSION.SDK_INT < 34 && projection != null) {
            Point size = screenSize();
            resize(size.x, size.y);
        }
    }

    private Point screenSize() {
        Point size = new Point();
        windows.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private void resize(int w, int h) {
        if (destroyed || (width == w && height == h)) return;
        generation++;
        waitingForFrame = false;
        busy = false;
        clearOverlay();
        width = w;
        height = h;
        ImageReader previous = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onFrame, main);
        if (display == null) {
            display = projection.createVirtualDisplay("LopDich", w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
        } else {
            display.resize(w, h, density);
            display.setSurface(reader.getSurface());
        }
        if (previous != null) previous.close();
        if (bubble != null) {
            placeBubble();
            showReadyBubble();
        }
    }

    private void showBubble() {
        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(24);
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setElevation(dp(6));
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(MainActivity.ACCENT);
        shape.setStroke(dp(2), 0xffa4dbc5);
        bubble.setBackground(shape);
        bubble.setText("文");
        bubble.setContentDescription("Dịch màn hình");
        bubbleParams = params(dp(50), dp(50), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        bubbleParams.x = width - dp(64);
        bubbleParams.y = height / 2;
        bubble.setOnClickListener(view -> toggle());
        bubble.setOnLongClickListener(view -> {
            generation++;
            showReadyBubble();
            clearOverlay();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { return bubble.performClick(); }
            @Override public void onLongPress(MotionEvent event) { bubble.performLongClick(); }
            @Override public boolean onScroll(MotionEvent start, MotionEvent event, float dx, float dy) {
                if (start == null) return false;
                bubbleParams.x = Math.round(event.getRawX()) - dp(25);
                bubbleParams.y = Math.round(event.getRawY()) - dp(25);
                placeBubble();
                return true;
            }
        });
        bubble.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);
            return true;
        });
        windows.addView(bubble, bubbleParams);
        placeBubble();
    }

    private void placeBubble() {
        bubbleParams.x = Math.max(0, Math.min(width - dp(50), bubbleParams.x));
        bubbleParams.y = Math.max(dp(28), Math.min(height - dp(76), bubbleParams.y));
        windows.updateViewLayout(bubble, bubbleParams);
    }

    private void toggle() {
        if (busy) return;
        if (overlay != null) {
            clearOverlay();
            return;
        }
        try {
            options = SettingsStore.read(this);
            if (options.keys().isEmpty()) {
                toast("Hãy nhập Gemini API key trong cài đặt.");
                return;
            }
        } catch (Exception failure) {
            toast("Không đọc được API key. Hãy lưu lại trong cài đặt.");
            return;
        }
        int request = ++generation;
        busy = true;
        bubble.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> {
            if (request == generation) waitingForFrame = true;
        }, 200);
        // A static display may produce only the frame caused by hiding the bubble.
        main.postDelayed(() -> {
            if (request == generation && waitingForFrame && lastFrame != null) {
                Bitmap frame = lastFrame;
                lastFrame = null;
                consumeFrame(frame);
            }
        }, 450);
        main.postDelayed(() -> {
            if (request == generation && waitingForFrame) fail("Không đọc được màn hình. Hãy thử lại.");
        }, 2500);
    }

    private Bitmap lastFrame;

    private void onFrame(ImageReader source) {
        if (source != reader || destroyed) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null || bubble == null || bubble.getVisibility() == View.VISIBLE || !busy) return;
            Image.Plane plane = image.getPlanes()[0];
            int strideWidth = plane.getRowStride() / plane.getPixelStride();
            Bitmap padded = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap frame = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (frame != padded) padded.recycle();
            discardFrame();
            if (waitingForFrame) consumeFrame(frame);
            else lastFrame = frame;
        } catch (RuntimeException error) {
            if (busy) fail("Không đọc được khung hình. Hãy thử lại.");
        }
    }

    private void consumeFrame(Bitmap frame) {
        waitingForFrame = false;
        bubble.setText("…");
        bubble.setVisibility(View.VISIBLE);
        int request = generation;
        SettingsStore.Options current = options;
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        network.execute(() -> {
            Bitmap upload = frame;
            try {
                if (request != generation) return;
                float scale = Math.min(1f, 1600f / Math.max(frameWidth, frameHeight));
                if (scale < 1f) {
                    upload = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frameWidth * scale)),
                            Math.max(1, Math.round(frameHeight * scale)), true);
                }
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                if (!upload.compress(Bitmap.CompressFormat.JPEG, 90, image)) {
                    throw new IllegalStateException("Không mã hóa được khung hình.");
                }
                byte[] jpeg = image.toByteArray();
                CachedTranslation cached = lastTranslation;
                List<Gemini.Translation> translated;
                if (cached != null && cached.options().equals(current) && Arrays.equals(cached.image(), jpeg)) {
                    translated = cached.translations();
                } else {
                    if (request != generation) return;
                    translated = gemini.translate(current, jpeg);
                    lastTranslation = new CachedTranslation(current, jpeg, translated);
                }
                main.post(() -> {
                    if (request == generation) showTranslations(translated, frameWidth, frameHeight);
                });
            } catch (Exception error) {
                android.util.Log.d("LopDich", "Translation failed: " + error.getClass().getSimpleName());
                String message = error.getMessage();
                main.post(() -> {
                    if (request != generation) return;
                    if (error instanceof java.net.SocketTimeoutException) {
                        fail("Gemini phản hồi quá lâu. Hãy thử lại.");
                    } else {
                        fail(message != null && message.startsWith("Gemini ") ? message
                                : "Không nhận được bản dịch. Kiểm tra kết nối và thử lại.");
                    }
                });
            } finally {
                if (upload != frame) upload.recycle();
                frame.recycle();
            }
        });
    }

    private void showTranslations(List<Gemini.Translation> translations, int frameWidth, int frameHeight) {
        List<Rect> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Gemini.Translation translation : translations) {
            Rect box = new Rect(Math.round(translation.left() * frameWidth / 1000f),
                    Math.round(translation.top() * frameHeight / 1000f),
                    Math.round(translation.right() * frameWidth / 1000f),
                    Math.round(translation.bottom() * frameHeight / 1000f));
            if (!box.isEmpty()) {
                boxes.add(box);
                labels.add(translation.text());
            }
        }
        showReadyBubble();
        if (boxes.isEmpty()) {
            toast("Không tìm thấy chữ cần dịch.");
            return;
        }
        View layer = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas canvas) {
                int[] origin = new int[2];
                getLocationOnScreen(origin);
                canvas.save();
                canvas.translate(-origin[0], -origin[1]);
                for (int i = 0; i < boxes.size(); i++) {
                    Rect box = boxes.get(i);
                    float padding = Math.max(2, box.height() * .08f);
                    int availableWidth = Math.max(1, box.width());
                    float minSize = Math.min(sp(10), box.height() * .62f);
                    text.setColor(0xff071512);
                    text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                    text.setTextSize(Math.max(minSize, Math.min(sp(28), box.height() * .81f)));
                    String label = labels.get(i).replace('\n', ' ').replace('\r', ' ');
                    float scale = availableWidth * .94f / Math.max(1, text.measureText(label));
                    text.setTextSize(Math.max(minSize, text.getTextSize() * Math.min(1, scale)));
                    int maxLines = text.getFontSpacing() * 2 <= box.height() ? 2 : 1;
                    StaticLayout layout = StaticLayout.Builder.obtain(label, 0, label.length(), text, availableWidth)
                            .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
                    paint.setColor(Color.WHITE);
                    canvas.drawRoundRect(box.left - padding, box.top - padding, box.right + padding,
                            box.bottom + padding, padding * 2, padding * 2, paint);
                    canvas.save();
                    canvas.translate(box.left, box.top + (box.height() - layout.getHeight()) / 2f);
                    text.setColor(Color.WHITE);
                    text.setStyle(Paint.Style.STROKE);
                    text.setStrokeJoin(Paint.Join.ROUND);
                    text.setStrokeWidth(Math.max(1f, text.getTextSize() * .12f));
                    layout.draw(canvas);
                    text.setStyle(Paint.Style.FILL);
                    text.setColor(0xff071512);
                    layout.draw(canvas);
                    canvas.restore();
                }
                canvas.restore();
            }
        };
        WindowManager.LayoutParams layout = params(-1, -1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        layout.alpha = Build.VERSION.SDK_INT >= 31
                ? getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch() : 1f;
        try {
            windows.addView(layer, layout);
            overlay = layer;
            windows.removeViewImmediate(bubble);
            windows.addView(bubble, bubbleParams);
            bubble.setText("×");
            bubble.setContentDescription("Ẩn bản dịch");
        } catch (RuntimeException error) {
            fail("Không hiển thị được lớp dịch.");
        }
    }

    private WindowManager.LayoutParams params(int w, int h, int flags) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private void discardFrame() {
        if (lastFrame != null) {
            lastFrame.recycle();
            lastFrame = null;
        }
    }

    private void showReadyBubble() {
        discardFrame();
        busy = false;
        waitingForFrame = false;
        if (bubble != null) {
            bubble.setText("文");
            bubble.setContentDescription("Dịch màn hình");
            bubble.setVisibility(View.VISIBLE);
        }
    }

    private void clearOverlay() {
        remove(overlay);
        overlay = null;
        if (!busy) showReadyBubble();
    }

    private void fail(String message) {
        showReadyBubble();
        clearOverlay();
        toast(message);
    }

    private void remove(View view) {
        if (view != null && view.isAttachedToWindow()) windows.removeViewImmediate(view);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private float sp(int value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onDestroy() {
        destroyed = true;
        generation++;
        active = false;
        main.removeCallbacksAndMessages(null);
        discardFrame();
        remove(overlay);
        remove(bubble);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        network.shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
