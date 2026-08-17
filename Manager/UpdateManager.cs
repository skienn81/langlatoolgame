using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    public class ReleaseInfo
    {
        public string TagName { get; set; } = "";
        public string Name { get; set; } = "";
        public string Body { get; set; } = "";
        public string DownloadUrl { get; set; } = "";
        public long AssetSize { get; set; } = 0;
        public DateTime PublishedAt { get; set; }
        public bool HasUpdate { get; set; } = false;
    }

    public static class UpdateManager
    {
        // ── Phiên bản hiện tại của Tool ──────────────────────────────────────
        public const string CURRENT_VERSION = "1.0.1";
        public const string GITHUB_REPO = "skienn81/langlatoolgame";

        private static readonly HttpClient _httpClient = new HttpClient();

        static UpdateManager()
        {
            // GitHub API bắt buộc phải có User-Agent header
            _httpClient.DefaultRequestHeaders.UserAgent.Add(
                new ProductInfoHeaderValue("LangLa-AutoManager", CURRENT_VERSION));
            _httpClient.Timeout = TimeSpan.FromSeconds(30);
        }

        /// <summary>
        /// Chuẩn hóa chuỗi version (ví dụ "v1.0.2" -> "1.0.2") thành đối tượng Version để so sánh.
        /// </summary>
        public static Version ParseVersion(string tag)
        {
            if (string.IsNullOrWhiteSpace(tag)) return new Version(0, 0, 0);
            string clean = tag.Trim().TrimStart('v', 'V').Split('-')[0]; // Bỏ tiền tố v và hậu tố -beta
            string[] parts = clean.Split('.');
            int major = parts.Length > 0 && int.TryParse(parts[0], out int mj) ? mj : 0;
            int minor = parts.Length > 1 && int.TryParse(parts[1], out int mn) ? mn : 0;
            int build = parts.Length > 2 && int.TryParse(parts[2], out int b) ? b : 0;
            int revision = parts.Length > 3 && int.TryParse(parts[3], out int r) ? r : 0;
            return new Version(major, minor, build, revision >= 0 ? revision : 0);
        }

        /// <summary>
        /// So sánh xem phiên bản trên mạng có mới hơn phiên bản hiện tại không.
        /// </summary>
        public static bool IsNewerVersion(string remoteTag)
        {
            try
            {
                var currentVer = ParseVersion(CURRENT_VERSION);
                var remoteVer = ParseVersion(remoteTag);
                return remoteVer > currentVer;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Gọi GitHub Releases API để kiểm tra bản phát hành mới nhất.
        /// </summary>
        public static async Task<ReleaseInfo> CheckForUpdatesAsync(CancellationToken ct = default)
        {
            var info = new ReleaseInfo();
            try
            {
                string url = $"https://api.github.com/repos/{GITHUB_REPO}/releases/latest";
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                using var response = await _httpClient.SendAsync(request, ct);

                if (!response.IsSuccessStatusCode)
                {
                    // Nếu repo chưa có Release nào hoặc lỗi mạng
                    return info;
                }

                string json = await response.Content.ReadAsStringAsync(ct);
                using var doc = JsonDocument.Parse(json);
                var root = doc.RootElement;

                info.TagName = root.TryGetProperty("tag_name", out var tagEl) ? tagEl.GetString() ?? "" : "";
                info.Name = root.TryGetProperty("name", out var nameEl) ? nameEl.GetString() ?? "" : info.TagName;
                info.Body = root.TryGetProperty("body", out var bodyEl) ? bodyEl.GetString() ?? "" : "";
                
                if (root.TryGetProperty("published_at", out var pubEl) && pubEl.TryGetDateTime(out var dt))
                {
                    info.PublishedAt = dt;
                }

                // Tìm file update.zip trong danh sách assets đính kèm
                if (root.TryGetProperty("assets", out var assetsEl) && assetsEl.ValueKind == JsonValueKind.Array)
                {
                    foreach (var asset in assetsEl.EnumerateArray())
                    {
                        string assetName = asset.TryGetProperty("name", out var aName) ? aName.GetString() ?? "" : "";
                        if (assetName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                        {
                            info.DownloadUrl = asset.TryGetProperty("browser_download_url", out var dlUrl) 
                                ? dlUrl.GetString() ?? "" : "";
                            info.AssetSize = asset.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                            break;
                        }
                    }
                }

                // Nếu không có asset zip riêng, fallback sang source zipball
                if (string.IsNullOrEmpty(info.DownloadUrl) && root.TryGetProperty("zipball_url", out var zipUrl))
                {
                    info.DownloadUrl = zipUrl.GetString() ?? "";
                }

                info.HasUpdate = IsNewerVersion(info.TagName) && !string.IsNullOrEmpty(info.DownloadUrl);
            }
            catch (Exception)
            {
                info.HasUpdate = false;
            }

            return info;
        }

        /// <summary>
        /// Tìm thư mục gốc của Tool (nơi chứa client_modded.jar hoặc Manager.exe)
        /// </summary>
        public static string GetToolRootDir()
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            string searchDir = baseDir;
            for (int i = 0; i < 5; i++)
            {
                if (File.Exists(Path.Combine(searchDir, "client_modded.jar")) ||
                    File.Exists(Path.Combine(searchDir, "doi_hinh.cfg")) ||
                    File.Exists(Path.Combine(searchDir, "quest_anchors.cfg")))
                {
                    return searchDir;
                }
                var parent = Directory.GetParent(searchDir);
                if (parent == null) break;
                searchDir = parent.FullName;
            }
            return baseDir;
        }

        /// <summary>
        /// Tải file update.zip về thư mục tạm và báo cáo tiến trình (downloadedBytes, totalBytes, percent)
        /// </summary>
        public static async Task<string> DownloadUpdateAsync(
            string downloadUrl, 
            IProgress<(long downloaded, long total, int percent)> progress, 
            CancellationToken ct = default)
        {
            string rootDir = GetToolRootDir();
            string tempDir = Path.Combine(rootDir, "temp_update");
            if (!Directory.Exists(tempDir))
            {
                Directory.CreateDirectory(tempDir);
            }

            string zipPath = Path.Combine(tempDir, "update.zip");
            if (File.Exists(zipPath))
            {
                try { File.Delete(zipPath); } catch { }
            }

            using var response = await _httpClient.GetAsync(downloadUrl, HttpCompletionOption.ResponseHeadersRead, ct);
            response.EnsureSuccessStatusCode();

            long totalBytes = response.Content.Headers.ContentLength ?? -1L;

            using var contentStream = await response.Content.ReadAsStreamAsync(ct);
            using var fileStream = new FileStream(zipPath, FileMode.Create, FileAccess.Write, FileShare.None, 8192, true);

            var buffer = new byte[16384];
            long totalRead = 0;
            int bytesRead;
            int lastPercent = -1;

            while ((bytesRead = await contentStream.ReadAsync(buffer, 0, buffer.Length, ct)) > 0)
            {
                await fileStream.WriteAsync(buffer, 0, bytesRead, ct);
                totalRead += bytesRead;

                if (totalBytes > 0)
                {
                    int percent = (int)((totalRead * 100) / totalBytes);
                    if (percent != lastPercent)
                    {
                        lastPercent = percent;
                        progress?.Report((totalRead, totalBytes, percent));
                    }
                }
                else
                {
                    progress?.Report((totalRead, -1, 50));
                }
            }

            progress?.Report((totalRead, totalBytes > 0 ? totalBytes : totalRead, 100));
            return zipPath;
        }

        /// <summary>
        /// Tạo file updater.bat và kích hoạt tiến trình ghi đè an toàn, sau đó thoát ứng dụng.
        /// </summary>
        public static void ApplyUpdateAndRestart(string zipPath)
        {
            string rootDir = GetToolRootDir();
            string updaterBatPath = Path.Combine(rootDir, "temp_update", "updater.bat");
            int currentPid = Process.GetCurrentProcess().Id;

            // Kịch bản Updater:
            // 1. Chờ Manager.exe có PID hiện tại thoát hẳn (mở khóa file)
            // 2. Dùng PowerShell giải nén update.zip ghi đè vào rootDir
            //    - Bảo vệ tuyệt đối: KHÔNG ghi đè file config.json (chứa nick/pass của khách)
            // 3. Khởi động lại Manager.exe
            // 4. Tự dọn dẹp thư mục tạm
            string scriptContent = $@"@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ======================================================
echo   DANG CAP NHAT LANG LA AUTO BOT...
echo   Vui long cho trong giay lat...
echo ======================================================

set ""TARGET_DIR={rootDir}""
set ""ZIP_PATH={zipPath}""
set ""WAIT_PID={currentPid}""

:: 1. Cho tien trinh Manager cu dong han
if not ""!WAIT_PID!""=="""" (
    :wait_loop
    tasklist /fi ""PID eq !WAIT_PID!"" 2>nul | find ""!WAIT_PID!"" >nul
    if not errorlevel 1 (
        timeout /t 1 /nobreak >nul
        goto wait_loop
    )
)
timeout /t 1 /nobreak >nul

:: 2. Giai nen ghi de file moi (Bao ve config.json)
powershell -NoProfile -ExecutionPolicy Bypass -Command ""try {{ Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip = [System.IO.Compression.ZipFile]::OpenRead('$ZIP_PATH'); foreach ($e in $zip.Entries) {{ if ($e.FullName.EndsWith('/') -or $e.FullName.EndsWith('\\')) {{ continue; }}; $dest = [System.IO.Path]::Combine('$TARGET_DIR', $e.FullName); $fName = [System.IO.Path]::GetFileName($e.FullName); if ($fName -ieq 'config.json' -and [System.IO.File]::Exists($dest)) {{ continue; }}; $dir = [System.IO.Path]::GetDirectoryName($dest); if (-not [System.IO.Directory]::Exists($dir)) {{ [System.IO.Directory]::CreateDirectory($dir) | Out-Null; }}; [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true); }}; $zip.Dispose(); exit 0; }} catch {{ Write-Error $_.Exception.Message; exit 1; }}""

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [LOI] Khong the giai nen ban cap nhat.
    pause
    exit /b 1
)

echo Cap nhat thanh cong! Dang khoi dong lai Manager...

:: 3. Tim va mo lai Manager.exe
if exist ""!TARGET_DIR!\Manager.exe"" (
    start """" ""!TARGET_DIR!\Manager.exe""
) else if exist ""!TARGET_DIR!\Manager\bin\Release\net8.0-windows\Manager.exe"" (
    start """" ""!TARGET_DIR!\Manager\bin\Release\net8.0-windows\Manager.exe""
) else (
    for /r ""!TARGET_DIR!"" %%f in (Manager.exe) do (
        start """" ""%%f""
        goto launched
    )
)
:launched

:: 4. Xoa thu muc tam sau khi hoan tat
timeout /t 2 /nobreak >nul
if exist ""!TARGET_DIR!\temp_update"" (
    rd /s /q ""!TARGET_DIR!\temp_update"" 2>nul
)

exit 0
";

            File.WriteAllText(updaterBatPath, scriptContent, new UTF8Encoding(false));

            // Kích hoạt updater.bat chạy độc lập
            var psi = new ProcessStartInfo
            {
                FileName = updaterBatPath,
                UseShellExecute = true,
                CreateNoWindow = false,
                WindowStyle = ProcessWindowStyle.Normal,
                WorkingDirectory = rootDir
            };
            Process.Start(psi);

            // Đóng Manager ngay lập tức
            Application.Exit();
            Environment.Exit(0);
        }
    }
}
