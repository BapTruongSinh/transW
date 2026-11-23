# Lớp Dịch

Ứng dụng dịch chữ trên màn hình sang tiếng Việt và đặt bản dịch ngay trên dòng gốc. Repo chứa bản Android ở `app/` và bản Windows ở `desktop/`.

## Android

1. Build APK theo lệnh bên dưới và cài trên Android 8 trở lên.
2. Mở Lớp Dịch và nhập các Gemini API key, mỗi dòng một key. App tự nhận diện ngôn ngữ và dịch sang tiếng Việt.
3. Bấm **Bật nút dịch nổi**. Cho phép hiển thị trên ứng dụng khác và xác nhận chia sẻ **Toàn bộ màn hình** nếu Android hỏi.
4. Mở app hoặc game cần dịch và bấm **文**. Khi bản dịch hiện, bấm **×** để ẩn rồi bấm **文** để đọc màn hình mới. Có thể kéo nút sang chỗ khác hoặc nhấn giữ để mở cài đặt.

App gửi một khung hình sang Gemini mỗi lần cần bản dịch mới. Gemini đọc chữ, dịch và trả về tọa độ. API key được mã hóa bằng khóa trong Android Keystore. Khi Gemini báo hết hạn mức (HTTP 429), app thử key tiếp theo và ưu tiên key dùng thành công cho lần dịch sau. Các key cùng dự án Google dùng chung hạn mức của dự án. Màn hình được ứng dụng khác đặt chế độ bảo vệ có thể không đọc được.

Cài JDK 17 trở lên và Android SDK Platform 36. Đặt `ANDROID_HOME` trỏ tới SDK hoặc thêm `sdk.dir` vào `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug
```

APK thử nằm tại `app/build/outputs/apk/debug/app-debug.apk`. Khi phân phối, build bản release và ký bằng khóa riêng được lưu ngoài repo.

## Windows

Cài .NET 9 SDK trên Windows và chạy:

```powershell
dotnet run --project desktop/LopDich.csproj -c Release
```

Mở **Cài đặt**, nhập các API key, mỗi dòng một key, rồi lưu. Kéo thanh nổi sang màn hình muốn dịch và bấm **文 Dịch**. Bấm **× Ẩn** để đóng lớp dịch. Bản Windows lưu key bằng Windows DPAPI theo tài khoản người dùng.

## Dữ liệu

Ứng dụng chỉ đọc khung hình khi bạn bấm dịch. Mỗi lượt yêu cầu bản dịch mới gửi khung hình cùng API key qua HTTPS đến Gemini. App giữ ảnh và kết quả dịch trong bộ nhớ để dùng lại. Tránh bấm dịch trên màn hình chứa dữ liệu nhạy cảm khi dùng dịch vụ miễn phí. Xem [điều khoản sử dụng dữ liệu của Gemini](https://aistudio.google.com/docs/terms#data-use-unpaid).
