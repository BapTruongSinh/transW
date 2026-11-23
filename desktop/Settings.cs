using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace LopDich;

internal sealed record Settings(string Model, string[] Keys)
{
    public const string DefaultModel = "gemini-3.5-flash-lite";
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LopDich");
    private static readonly string FilePath = Path.Combine(DirectoryPath, "settings.bin");

    public static Settings Read()
    {
        if (!File.Exists(FilePath)) return new(DefaultModel, []);
        byte[] data = ProtectedData.Unprotect(File.ReadAllBytes(FilePath), null, DataProtectionScope.CurrentUser);
        try
        {
            Settings settings = JsonSerializer.Deserialize<Settings>(data) ?? throw new InvalidDataException();
            return Validate(settings.Model, settings.Keys);
        }
        finally { CryptographicOperations.ZeroMemory(data); }
    }

    public static Settings Validate(string model, IEnumerable<string> keys)
    {
        if (!Regex.IsMatch(model, @"\Agemini-[a-z0-9.-]+\z"))
            throw new ArgumentException("Nhập tên model Gemini hợp lệ.");
        string[] entries = keys.Select(key => key.Trim()).Where(key => key.Length != 0)
            .Distinct(StringComparer.Ordinal).ToArray();
        if (entries.Length == 0 || entries.Any(key => !Regex.IsMatch(key, @"\A[A-Za-z0-9_.-]+\z")))
            throw new ArgumentException("Nhập mỗi API key hợp lệ trên một dòng.");
        return new(model, entries);
    }

    public void Save()
    {
        Directory.CreateDirectory(DirectoryPath);
        byte[] data = JsonSerializer.SerializeToUtf8Bytes(this);
        string temp = Path.Combine(DirectoryPath, $"{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllBytes(temp, ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser));
            File.Move(temp, FilePath, true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
            File.Delete(temp);
        }
    }
}
