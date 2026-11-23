package vn.lopdich.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class MainActivity extends Activity {
    private static final int SHARE_SCREEN = 41;
    static final int INK = 0xff182d2b;
    static final int ACCENT = 0xff116b59;
    private static final int PAPER = 0xfff5f8f2;
    private LinearLayout form;
    private EditText key;
    private EditText model;
    private boolean awaitingOverlay;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(PAPER);
        page.setFillViewport(true);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(26), dp(24), dp(24));
        page.addView(form);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(page);

        TextView logo = label("文  /  A", 16, ACCENT);
        logo.setTypeface(null, Typeface.BOLD);
        TextView title = label("Lớp Dịch", 38, INK);
        title.setTypeface(null, Typeface.BOLD);
        label("Dịch chữ ngay trên màn hình bạn đang xem.", 16, INK);
        spacer(20);
        label("Gemini API keys", 14, INK);
        key = new EditText(this);
        key.setHint("Mỗi dòng một API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setGravity(android.view.Gravity.TOP);
        key.setMinLines(3);
        key.setMaxLines(6);
        key.setTextSize(15);
        key.setTextColor(INK);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(key, -1, -2);
        CheckBox revealKeys = new CheckBox(this);
        revealKeys.setText("Hiện API keys");
        revealKeys.setOnCheckedChangeListener((button, checked) -> key.setTransformationMethod(
                checked ? null : PasswordTransformationMethod.getInstance()));
        form.addView(revealKeys, -1, -2);
        button("Lấy API key ↗", false, () -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))));
        spacer(10);
        label("Model Gemini", 14, INK);
        model = field(SettingsStore.DEFAULT_MODEL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        label("Tự nhận diện ngôn ngữ → Tiếng Việt", 14, INK);
        spacer(10);
        button("Lưu cài đặt", false, () -> { if (save()) message("Đã lưu cài đặt"); });
        button("Bật nút dịch nổi", true, this::start);
        button("Tắt nút dịch nổi", false, () -> {
            stopService(new Intent(this, TranslationService.class));
            message("Đã tắt nút nổi");
        });
        spacer(12);
        label("Chọn Toàn bộ màn hình nếu Android hỏi. Bấm nút 文 để dịch, chạm lần nữa để ẩn. Giữ nút nổi để mở cài đặt.", 14, INK);
        spacer(8);
        label("Khi bấm dịch, khung hình hiện tại được gửi đến Gemini để đọc và dịch chữ.", 14, INK);
        try {
            SettingsStore.Options options = SettingsStore.read(this);
            key.setText(String.join("\n", options.keys()));
            model.setText(options.model());
        } catch (Exception ignored) {
            message("Hãy nhập lại Gemini API key.");
        }
        form.setFocusableInTouchMode(true);
        form.requestFocus();
    }

    private void start() {
        if (!save()) return;
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (TranslationService.active) {
            moveTaskToBack(true);
            return;
        }
        requestScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingOverlay) {
            awaitingOverlay = false;
            if (Settings.canDrawOverlays(this)) requestScreen();
        }
    }

    private void requestScreen() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, SHARE_SCREEN);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SHARE_SCREEN) return;
        if (result != RESULT_OK || data == null) {
            message("Cần quyền chia sẻ màn hình để bật nút dịch.");
            return;
        }
        startForegroundService(new Intent(this, TranslationService.class)
                .putExtra(TranslationService.CONSENT, data));
        moveTaskToBack(true);
    }

    private boolean save() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String[] rows = key.getText().toString().split("\\R");
        for (int i = 0; i < rows.length; i++) {
            String value = rows[i].trim();
            if (value.isEmpty()) continue;
            if (!value.matches("[A-Za-z0-9_.-]+")) {
                key.setError("API key ở dòng " + (i + 1) + " có ký tự không hợp lệ");
                return false;
            }
            keys.add(value);
        }
        if (keys.isEmpty()) {
            key.setError("Nhập ít nhất một Gemini API key");
            return false;
        }
        String selectedModel = model.getText().toString().trim();
        if (!selectedModel.matches("gemini-[a-z0-9.-]+")) {
            model.setError("Nhập tên model Gemini");
            return false;
        }
        try {
            SettingsStore.save(this, new SettingsStore.Options(new ArrayList<>(keys), selectedModel));
            return true;
        } catch (Exception ignored) {
            message("Không thể lưu cài đặt.");
            return false;
        }
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        form.addView(view, -1, -2);
        return view;
    }

    private EditText field(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setHint(hint);
        edit.setTextColor(INK);
        edit.setTextSize(16);
        form.addView(edit, -1, dp(51));
        return edit;
    }

    private void button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? ACCENT : 0xffe4efe7);
        shape.setCornerRadius(dp(14));
        button.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        form.addView(button, params);
        button.setOnClickListener(view -> action.run());
    }

    private void spacer(int height) { form.addView(new View(this), 1, dp(height)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
