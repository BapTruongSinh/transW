using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace LopDich;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new Launcher());
    }
}

internal sealed class Launcher : Form
{
    private readonly Gemini gemini = new();
    private readonly Button translate = new() { Text = "文  Dịch", AutoSize = true };
    private readonly Button settingsButton = new() { Text = "Cài đặt", AutoSize = true };
    private readonly Label status = new() { AutoSize = true, Padding = new Padding(2, 7, 0, 0) };
    private readonly CancellationTokenSource closing = new();
    private Settings settings = new(Settings.DefaultModel, []);
    private TranslationOverlay? overlay;
    private (byte[] Image, IReadOnlyList<Translation> Results)? cache;
    private bool busy;

    public Launcher()
    {
        Text = "Lớp Dịch";
        Font = new Font("Segoe UI", 10);
        BackColor = Color.FromArgb(239, 248, 242);
        ForeColor = Color.FromArgb(24, 45, 43);
        FormBorderStyle = FormBorderStyle.FixedToolWindow;
        StartPosition = FormStartPosition.Manual;
        TopMost = true;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        var controls = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Padding = new Padding(8) };
        controls.Controls.AddRange([translate, settingsButton, status]);
        Controls.Add(controls);
        translate.Click += async (_, _) => await TranslateAsync();
        settingsButton.Click += (_, _) => EditSettings();
        FormClosing += (_, _) => { closing.Cancel(); HideOverlay(); };
        Shown += (_, _) =>
        {
            Rectangle area = Screen.FromControl(this).WorkingArea;
            Location = new Point(area.Right - Width - 14, area.Top + 18);
            try { settings = Settings.Read(); }
            catch { status.Text = "Cần nhập lại API key."; }
        };
    }

    private void EditSettings()
    {
        HideOverlay();
        using var dialog = new SettingsDialog(settings);
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        settings = dialog.Value;
        cache = null;
        status.Text = "Đã lưu.";
    }

    private async Task TranslateAsync()
    {
        if (busy) return;
        if (overlay is not null) { HideOverlay(); return; }
        if (settings.Keys.Length == 0) { EditSettings(); return; }
        busy = true;
        translate.Enabled = settingsButton.Enabled = false;
        Rectangle bounds = Screen.FromControl(this).Bounds;
        try
        {
            Hide();
            await Task.Delay(160, closing.Token);
            byte[] image = CaptureScreen(bounds);
            Visible = true;
            status.Text = "Đang dịch…";
            IReadOnlyList<Translation> results;
            if (cache is { } previous && image.AsSpan().SequenceEqual(previous.Image))
            {
                results = previous.Results;
            }
            else
            {
                results = await gemini.TranslateAsync(settings, image, closing.Token);
                cache = (image, results);
            }
            if (IsDisposed || closing.IsCancellationRequested) return;
            if (results.Count == 0)
            {
                status.Text = "Không thấy chữ cần dịch.";
                return;
            }
            overlay = new TranslationOverlay(bounds, results);
            overlay.Show();
            BringToFront();
            translate.Text = "×  Ẩn";
            status.Text = "";
        }
        catch (Exception error) when (!closing.IsCancellationRequested)
        {
            if (IsDisposed) return;
            Visible = true;
            status.Text = error is InvalidOperationException
                ? error.Message : "Không nhận được bản dịch. Thử lại.";
        }
        catch (OperationCanceledException) when (closing.IsCancellationRequested) { }
        finally
        {
            busy = false;
            if (!IsDisposed) translate.Enabled = settingsButton.Enabled = true;
        }
    }

    private void HideOverlay()
    {
        overlay?.Dispose();
        overlay = null;
        translate.Text = "文  Dịch";
    }

    private static byte[] CaptureScreen(Rectangle bounds)
    {
        using var bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb);
        using (Graphics screen = Graphics.FromImage(bitmap))
            screen.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size);
        float scale = Math.Min(1, 1600f / Math.Max(bounds.Width, bounds.Height));
        using var output = new MemoryStream();
        if (scale < 1)
        {
            using var resized = new Bitmap(bitmap, new Size(
                Math.Max(1, (int)Math.Round(bounds.Width * scale)), Math.Max(1, (int)Math.Round(bounds.Height * scale))));
            resized.Save(output, ImageFormat.Jpeg);
        }
        else
        {
            bitmap.Save(output, ImageFormat.Jpeg);
        }
        return output.ToArray();
    }
}

internal sealed class SettingsDialog : Form
{
    public Settings Value { get; private set; }

    public SettingsDialog(Settings value)
    {
        Value = value;
        Text = "Cài đặt Lớp Dịch";
        Font = new Font("Segoe UI", 10);
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(490, 330);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        ShowInTaskbar = false;
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 1 };
        var keys = new TextBox { Multiline = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill,
            ShortcutsEnabled = true };
        var keysLabel = new Label { AutoSize = true, Text = value.Keys.Length == 0
            ? "Gemini API keys · Mỗi dòng một key"
            : $"Đã lưu {value.Keys.Length} key · Để trống để giữ danh sách hiện tại" };
        var model = new TextBox { Text = value.Model, Dock = DockStyle.Fill };
        var info = new Label { Text = "Khi bấm dịch, ảnh màn hình được gửi đến Gemini qua HTTPS.",
            AutoSize = true, Padding = new Padding(0, 7, 0, 7) };
        var save = new Button { Text = "Lưu cài đặt", AutoSize = true };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.Controls.Add(keysLabel);
        layout.Controls.Add(keys);
        layout.Controls.Add(new Label { Text = "Model Gemini", AutoSize = true });
        layout.Controls.Add(model);
        layout.Controls.Add(info);
        layout.Controls.Add(save);
        Controls.Add(layout);
        save.Click += (_, _) =>
        {
            try
            {
                var entries = string.IsNullOrWhiteSpace(keys.Text) ? value.Keys : keys.Lines;
                Settings next = Settings.Validate(model.Text.Trim(), entries);
                next.Save();
                Value = next;
                DialogResult = DialogResult.OK;
            }
            catch (ArgumentException error)
            {
                MessageBox.Show(this, error.Message, Text);
            }
            catch
            {
                MessageBox.Show(this, "Không lưu được cài đặt.", Text);
            }
        };
        // Ask Windows to exclude settings from supported screen-capture APIs.
        Shown += (_, _) => SetWindowDisplayAffinity(Handle, 0x11);
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowDisplayAffinity(IntPtr window, uint affinity);
}

internal sealed class TranslationOverlay : Form
{
    private static readonly Color TransparentMarker = Color.Magenta;
    private readonly IReadOnlyList<Translation> results;

    public TranslationOverlay(Rectangle bounds, IReadOnlyList<Translation> results)
    {
        this.results = results;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        AutoScaleMode = AutoScaleMode.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = bounds;
        BackColor = TransparencyKey = TransparentMarker;
        DoubleBuffered = true;
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var parameters = base.CreateParams;
            parameters.ExStyle |= 0x20 | 0x08000000; // WS_EX_TRANSPARENT | WS_EX_NOACTIVATE
            return parameters;
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        const TextFormatFlags flags = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix |
            TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter;
        foreach (Translation result in results)
        {
            var box = Rectangle.FromLTRB(ClientSize.Width * result.Left / 1000,
                ClientSize.Height * result.Top / 1000, ClientSize.Width * result.Right / 1000,
                ClientSize.Height * result.Bottom / 1000);
            if (box.Width < 2 || box.Height < 2) continue;
            e.Graphics.FillRectangle(Brushes.White, Rectangle.Inflate(box, 2, 2));
            var label = result.Text.Replace('\n', ' ').Replace('\r', ' ');
            float size = Math.Clamp(box.Height * .82f, 7, 40);
            using var initial = new Font("Segoe UI", size, FontStyle.Bold, GraphicsUnit.Pixel);
            int measured = TextRenderer.MeasureText(e.Graphics, label, initial, Size.Empty, flags).Width;
            float fitted = Math.Max(7, size * Math.Min(1f, box.Width * .95f / Math.Max(1, measured)));
            using var font = new Font("Segoe UI", fitted, FontStyle.Bold, GraphicsUnit.Pixel);
            TextRenderer.DrawText(e.Graphics, label, font, box, Color.FromArgb(7, 21, 18), flags);
        }
    }
}
