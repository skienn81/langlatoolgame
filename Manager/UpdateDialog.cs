using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    public class UpdateDialog : Form
    {
        private readonly ReleaseInfo _releaseInfo;
        private CancellationTokenSource _cts;

        // ── Palette (đồng bộ với Form1) ──────────────────────────────────
        private static readonly Color ColBg        = Color.FromArgb(11, 15, 25);    // Slate-950
        private static readonly Color ColPanel     = Color.FromArgb(17, 24, 39);    // Slate-900
        private static readonly Color ColBorder    = Color.FromArgb(31, 41, 55);    // Slate-800
        private static readonly Color ColPrimary   = Color.FromArgb(99, 102, 241);  // Indigo-500
        private static readonly Color ColPriHover  = Color.FromArgb(79, 70, 229);   // Indigo-600
        private static readonly Color ColEmerald   = Color.FromArgb(16, 185, 129);  // Emerald-500
        private static readonly Color ColEmeHover  = Color.FromArgb(5, 150, 105);   // Emerald-600
        private static readonly Color ColGray400   = Color.FromArgb(156, 163, 175);
        private static readonly Color ColGray300   = Color.FromArgb(209, 213, 219);
        private static readonly Color ColInputBg   = Color.FromArgb(24, 32, 50);

        // ── Controls ─────────────────────────────────────────────────────
        private Label lblHeaderTitle;
        private Label lblVersionInfo;
        private RichTextBox rtbChangelog;
        private ProgressBar progressBar;
        private Label lblStatus;
        private Button btnUpdateNow;
        private Button btnClose;

        public UpdateDialog(ReleaseInfo releaseInfo)
        {
            _releaseInfo = releaseInfo;
            InitializeComponentCustom();
        }

        private void InitializeComponentCustom()
        {
            this.Text = "Cập Nhật Phiên Bản - Làng Lá Auto Bot";
            this.Size = new Size(580, 480);
            this.MinimumSize = new Size(500, 400);
            this.BackColor = ColBg;
            this.ForeColor = Color.White;
            this.Font = new Font("Segoe UI", 9.5F, FontStyle.Regular);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.StartPosition = FormStartPosition.CenterParent;

            // ── Main Container Panel ──
            var mainPanel = new Panel
            {
                Dock = DockStyle.Fill,
                Padding = new Padding(20),
                BackColor = ColBg
            };
            this.Controls.Add(mainPanel);

            // ── 1. Header ──
            lblHeaderTitle = new Label
            {
                Text = _releaseInfo.HasUpdate ? "🎉 ĐÃ CÓ BẢN CẬP NHẬT MỚI!" : "✅ BẠN ĐANG DÙNG BẢN MỚI NHẤT",
                Font = new Font("Segoe UI", 12F, FontStyle.Bold),
                ForeColor = _releaseInfo.HasUpdate ? ColEmerald : ColPrimary,
                AutoSize = true,
                Location = new Point(20, 16)
            };
            mainPanel.Controls.Add(lblHeaderTitle);

            string currentVerStr = "v" + UpdateManager.CURRENT_VERSION.TrimStart('v');
            string newVerStr = string.IsNullOrWhiteSpace(_releaseInfo.TagName) ? currentVerStr : _releaseInfo.TagName;
            
            lblVersionInfo = new Label
            {
                Text = _releaseInfo.HasUpdate 
                    ? $"Phiên bản hiện tại: {currentVerStr}   ➜   Phiên bản mới: {newVerStr}" 
                    : $"Phiên bản hiện tại: {currentVerStr} (Không có bản cập nhật mới)",
                Font = new Font("Segoe UI", 9.5F, FontStyle.Regular),
                ForeColor = ColGray300,
                AutoSize = true,
                Location = new Point(20, 46)
            };
            mainPanel.Controls.Add(lblVersionInfo);

            // ── 2. Changelog Label ──
            var lblChangeTitle = new Label
            {
                Text = "Nội dung cập nhật (Changelog):",
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold),
                ForeColor = ColGray300,
                AutoSize = true,
                Location = new Point(20, 80)
            };
            mainPanel.Controls.Add(lblChangeTitle);

            // ── 3. Changelog RichTextBox ──
            rtbChangelog = new RichTextBox
            {
                Location = new Point(20, 105),
                Size = new Size(525, 200),
                BackColor = ColInputBg,
                ForeColor = Color.FromArgb(240, 240, 240),
                BorderStyle = BorderStyle.None,
                Font = new Font("Consolas", 9.5F, FontStyle.Regular),
                ReadOnly = true,
                Text = string.IsNullOrWhiteSpace(_releaseInfo.Body) 
                    ? (_releaseInfo.HasUpdate ? "Bản cập nhật tối ưu hiệu năng và sửa lỗi." : "Hệ thống đang hoạt động ở trạng thái mới nhất.") 
                    : _releaseInfo.Body.Replace("\r\n", "\n").Replace("\n", "\r\n")
            };
            mainPanel.Controls.Add(rtbChangelog);

            // ── 4. Progress Bar & Status ──
            progressBar = new ProgressBar
            {
                Location = new Point(20, 318),
                Size = new Size(525, 14),
                Style = ProgressBarStyle.Continuous,
                Visible = false
            };
            mainPanel.Controls.Add(progressBar);

            lblStatus = new Label
            {
                Location = new Point(20, 340),
                Size = new Size(525, 22),
                Font = new Font("Segoe UI", 9F, FontStyle.Regular),
                ForeColor = ColGray400,
                TextAlign = ContentAlignment.MiddleLeft,
                Text = _releaseInfo.HasUpdate 
                    ? "Bấm 'Cập nhật ngay' để tải và tự động ghi đè bản mới." 
                    : "Bạn đã có đầy đủ tất cả các tính năng và bản vá mới nhất."
            };
            mainPanel.Controls.Add(lblStatus);

            // ── 5. Buttons ──
            int btnY = 375;

            btnUpdateNow = new Button
            {
                Text = "🚀  Cập nhật ngay",
                Font = new Font("Segoe UI", 10F, FontStyle.Bold),
                BackColor = ColEmerald,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Size = new Size(160, 38),
                Location = new Point(205, btnY),
                Cursor = Cursors.Hand,
                Visible = _releaseInfo.HasUpdate
            };
            btnUpdateNow.FlatAppearance.BorderSize = 0;
            btnUpdateNow.Click += async (s, e) => await StartUpdateProcessAsync();
            mainPanel.Controls.Add(btnUpdateNow);

            btnClose = new Button
            {
                Text = _releaseInfo.HasUpdate ? "Để sau" : "Đóng",
                Font = new Font("Segoe UI", 9.5F, FontStyle.Regular),
                BackColor = ColPanel,
                ForeColor = ColGray300,
                FlatStyle = FlatStyle.Flat,
                Size = new Size(110, 38),
                Location = new Point(_releaseInfo.HasUpdate ? 375 : 435, btnY),
                Cursor = Cursors.Hand
            };
            btnClose.FlatAppearance.BorderColor = ColBorder;
            btnClose.Click += (s, e) =>
            {
                _cts?.Cancel();
                this.Close();
            };
            mainPanel.Controls.Add(btnClose);
        }

        private async Task StartUpdateProcessAsync()
        {
            if (string.IsNullOrEmpty(_releaseInfo.DownloadUrl))
            {
                MessageBox.Show("Không tìm thấy link tải bản cập nhật trên GitHub!", "Lỗi", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            btnUpdateNow.Enabled = false;
            btnUpdateNow.Text = "⏳ Đang xử lý...";
            btnClose.Enabled = false;
            progressBar.Visible = true;
            progressBar.Value = 0;

            _cts = new CancellationTokenSource();

            var progress = new Progress<(long downloaded, long total, int percent)>(report =>
            {
                if (progressBar.IsDisposed) return;

                progressBar.Value = Math.Clamp(report.percent, 0, 100);
                if (report.total > 0)
                {
                    double mbDl = report.downloaded / (1024.0 * 1024.0);
                    double mbTotal = report.total / (1024.0 * 1024.0);
                    lblStatus.Text = $"Đang tải bản cập nhật: {mbDl:F1} MB / {mbTotal:F1} MB ({report.percent}%)...";
                }
                else
                {
                    double mbDl = report.downloaded / (1024.0 * 1024.0);
                    lblStatus.Text = $"Đang tải: {mbDl:F1} MB...";
                }
            });

            try
            {
                lblStatus.Text = "Đang kết nối tới GitHub Releases...";
                string zipPath = await UpdateManager.DownloadUpdateAsync(_releaseInfo.DownloadUrl, progress, _cts.Token);

                lblStatus.Text = "Tải thành công! Đang chuẩn bị áp dụng cập nhật...";
                progressBar.Value = 100;
                await Task.Delay(800);

                // Khởi động trình ghi đè và tự khởi động lại
                UpdateManager.ApplyUpdateAndRestart(zipPath);
            }
            catch (OperationCanceledException)
            {
                lblStatus.Text = "Đã hủy cập nhật.";
                btnUpdateNow.Enabled = true;
                btnUpdateNow.Text = "🚀  Cập nhật ngay";
                btnClose.Enabled = true;
            }
            catch (Exception ex)
            {
                lblStatus.Text = "Lỗi khi tải bản cập nhật: " + ex.Message;
                lblStatus.ForeColor = Color.FromArgb(239, 68, 68);
                btnUpdateNow.Enabled = true;
                btnUpdateNow.Text = "Thử lại";
                btnClose.Enabled = true;
                MessageBox.Show("Có lỗi xảy ra trong quá trình cập nhật:\n" + ex.Message, "Lỗi cập nhật", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            _cts?.Cancel();
            base.OnFormClosing(e);
        }
    }
}
