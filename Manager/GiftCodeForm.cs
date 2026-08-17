using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    public class GiftCodeForm : Form
    {
        private readonly Form1 _mainForm;
        private CancellationTokenSource _cts;
        private bool _isProcessing = false;

        // ── Palette ────────────────────────────────────────────────────────
        private static readonly Color ColBg        = Color.FromArgb(11, 15, 25);    // Slate-950
        private static readonly Color ColPanel     = Color.FromArgb(17, 24, 39);    // Slate-900
        private static readonly Color ColBorder    = Color.FromArgb(31, 41, 55);    // Slate-800
        private static readonly Color ColPrimary   = Color.FromArgb(99, 102, 241);  // Indigo-500
        private static readonly Color ColPriHover  = Color.FromArgb(79, 70, 229);   // Indigo-600
        private static readonly Color ColEmerald   = Color.FromArgb(16, 185, 129);  // Emerald-500
        private static readonly Color ColRed       = Color.FromArgb(239, 68, 68);   // Red-500
        private static readonly Color ColAmber     = Color.FromArgb(245, 158, 11);  // Amber-500
        private static readonly Color ColGray400   = Color.FromArgb(156, 163, 175);
        private static readonly Color ColGray300   = Color.FromArgb(209, 213, 219);
        private static readonly Color ColInputBg   = Color.FromArgb(24, 32, 50);
        private static readonly Color ColBtn       = Color.FromArgb(35, 45, 60);

        // ── Controls ───────────────────────────────────────────────────────
        private TextBox txtGiftCodes;
        private RadioButton rbCheckedOnly;
        private RadioButton rbAllOnline;
        private NumericUpDown numDelay;
        private Button btnStart;
        private Button btnStop;
        private Button btnClear;
        private Button btnPaste;
        private Button btnCopyReport;
        private DataGridView dgvResults;
        private ProgressBar progressBar;
        private Label lblStatus;
        private Label lblCountInfo;

        public GiftCodeForm(Form1 mainForm)
        {
            _mainForm = mainForm;
            InitializeComponent();
            SubscribeEvents();
        }

        private void InitializeComponent()
        {
            this.Text = "🎁 Tự Động Nhập Giftcode - Làng Lá Auto Bot";
            this.Size = new Size(880, 640);
            this.MinimumSize = new Size(760, 520);
            this.BackColor = ColBg;
            this.ForeColor = Color.White;
            this.Font = new Font("Segoe UI", 9.5F, FontStyle.Regular);
            this.StartPosition = FormStartPosition.CenterParent;

            // Main layout (Header, Content Split, Bottom Bar)
            var mainLayout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 3,
                Padding = new Padding(12, 10, 12, 10),
                Margin = new Padding(0)
            };
            mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));  // Header
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));  // Content Split
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 45));  // Bottom Bar

            // ── 1. Header Panel ──
            var panelHeader = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent, Margin = new Padding(0) };
            var lblTitle = new Label
            {
                Text = "🎁 AUTO NHẬP GIFTCODE (MÃ QUÀ TẶNG)",
                Font = new Font("Segoe UI", 12F, FontStyle.Bold),
                ForeColor = Color.White,
                Location = new Point(0, 2),
                AutoSize = true
            };
            var lblSubtitle = new Label
            {
                Text = "Tự động gửi mã quà tặng đồng loạt tới các tài khoản đã chọn. Không cần click UI trong game.",
                Font = new Font("Segoe UI", 8.5F),
                ForeColor = ColGray400,
                Location = new Point(2, 26),
                AutoSize = true
            };
            panelHeader.Controls.Add(lblTitle);
            panelHeader.Controls.Add(lblSubtitle);

            // ── 2. Content Split (Left: Input & Settings, Right: Real-time Results) ──
            var splitLayout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                RowCount = 1,
                Margin = new Padding(0, 6, 0, 6)
            };
            splitLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 340)); // Left Input
            splitLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));  // Right Results
            splitLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

            // ── Left Panel (Inputs & Options) ──
            var panelLeft = new Panel
            {
                Dock = DockStyle.Fill,
                BackColor = ColPanel,
                Padding = new Padding(10),
                Margin = new Padding(0, 0, 6, 0)
            };
            panelLeft.Paint += PanelBorder_Paint;

            var lblCodesTitle = new Label
            {
                Text = "Danh sách mã Giftcode (mỗi dòng 1 mã):",
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                ForeColor = ColGray300,
                Location = new Point(10, 8),
                AutoSize = true
            };
            panelLeft.Controls.Add(lblCodesTitle);

            txtGiftCodes = new TextBox
            {
                Multiline = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = ColInputBg,
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Consolas", 10F),
                Location = new Point(10, 30),
                Size = new Size(320, 180),
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            txtGiftCodes.TextChanged += (s, e) => UpdateCountInfo();
            panelLeft.Controls.Add(txtGiftCodes);

            btnPaste = MkSmallBtn("📋 Dán");
            btnPaste.Location = new Point(10, 216);
            btnPaste.Size = new Size(70, 26);
            btnPaste.Click += (s, e) =>
            {
                if (Clipboard.ContainsText())
                {
                    string cb = Clipboard.GetText();
                    if (!string.IsNullOrWhiteSpace(txtGiftCodes.Text)) txtGiftCodes.AppendText(Environment.NewLine);
                    txtGiftCodes.AppendText(cb.Trim());
                }
            };
            panelLeft.Controls.Add(btnPaste);

            btnClear = MkSmallBtn("🗑 Xoá mã");
            btnClear.Location = new Point(86, 216);
            btnClear.Size = new Size(80, 26);
            btnClear.Click += (s, e) => { txtGiftCodes.Clear(); dgvResults.Rows.Clear(); };
            panelLeft.Controls.Add(btnClear);

            lblCountInfo = new Label
            {
                Text = "0 mã",
                Font = new Font("Segoe UI", 8.5F),
                ForeColor = ColGray400,
                Location = new Point(175, 220),
                AutoSize = true
            };
            panelLeft.Controls.Add(lblCountInfo);

            // Group: Mục tiêu áp dụng
            var lblTargetTitle = new Label
            {
                Text = "Áp dụng cho:",
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                ForeColor = ColGray300,
                Location = new Point(10, 254),
                AutoSize = true
            };
            panelLeft.Controls.Add(lblTargetTitle);

            rbCheckedOnly = new RadioButton
            {
                Text = "Chỉ các nick đang tick (✔)",
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9F),
                Location = new Point(12, 276),
                AutoSize = true,
                Checked = true
            };
            panelLeft.Controls.Add(rbCheckedOnly);

            rbAllOnline = new RadioButton
            {
                Text = "Tất cả nick đang kết nối",
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9F),
                Location = new Point(12, 300),
                AutoSize = true
            };
            panelLeft.Controls.Add(rbAllOnline);

            // Group: Độ trễ
            var lblDelay = new Label
            {
                Text = "Độ trễ giữa mỗi mã:",
                Font = new Font("Segoe UI", 9F),
                ForeColor = ColGray400,
                Location = new Point(10, 332),
                AutoSize = true
            };
            panelLeft.Controls.Add(lblDelay);

            numDelay = new NumericUpDown
            {
                Minimum = 200,
                Maximum = 5000,
                Value = 600,
                Increment = 100,
                BackColor = ColInputBg,
                ForeColor = Color.White,
                Location = new Point(140, 330),
                Size = new Size(80, 24),
                TextAlign = HorizontalAlignment.Center
            };
            panelLeft.Controls.Add(numDelay);

            var lblMs = new Label
            {
                Text = "ms",
                Font = new Font("Segoe UI", 9F),
                ForeColor = ColGray400,
                Location = new Point(226, 332),
                AutoSize = true
            };
            panelLeft.Controls.Add(lblMs);

            // Action Buttons
            btnStart = new Button
            {
                Text = "🚀 BẮT ĐẦU NHẬP",
                BackColor = ColEmerald,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 10F, FontStyle.Bold),
                Location = new Point(10, 370),
                Size = new Size(320, 38),
                Cursor = Cursors.Hand,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            btnStart.FlatAppearance.BorderSize = 0;
            btnStart.Click += BtnStart_Click;
            panelLeft.Controls.Add(btnStart);

            btnStop = new Button
            {
                Text = "⏹ DỪNG NHẬP",
                BackColor = ColRed,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold),
                Location = new Point(10, 414),
                Size = new Size(320, 32),
                Cursor = Cursors.Hand,
                Enabled = false,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            btnStop.FlatAppearance.BorderSize = 0;
            btnStop.Click += BtnStop_Click;
            panelLeft.Controls.Add(btnStop);

            // ── Right Panel (Results DataGridView) ──
            var panelRight = new Panel
            {
                Dock = DockStyle.Fill,
                BackColor = ColPanel,
                Padding = new Padding(10),
                Margin = new Padding(6, 0, 0, 0)
            };
            panelRight.Paint += PanelBorder_Paint;

            var lblResultTitle = new Label
            {
                Text = "Kết quả nhập Giftcode theo thời gian thực:",
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                ForeColor = ColGray300,
                Location = new Point(10, 8),
                AutoSize = true
            };
            panelRight.Controls.Add(lblResultTitle);

            dgvResults = new DataGridView
            {
                Location = new Point(10, 30),
                Size = new Size(panelRight.Width - 20, panelRight.Height - 40),
                Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right,
                BackgroundColor = ColInputBg,
                BorderStyle = BorderStyle.None,
                ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.Single,
                EnableHeadersVisualStyles = false,
                GridColor = ColBorder,
                RowHeadersVisible = false,
                SelectionMode = DataGridViewSelectionMode.FullRowSelect,
                MultiSelect = false,
                AllowUserToAddRows = false,
                AllowUserToDeleteRows = false,
                ReadOnly = true
            };
            dgvResults.ColumnHeadersDefaultCellStyle.BackColor = Color.FromArgb(25, 33, 52);
            dgvResults.ColumnHeadersDefaultCellStyle.ForeColor = ColGray300;
            dgvResults.ColumnHeadersDefaultCellStyle.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            dgvResults.DefaultCellStyle.BackColor = ColInputBg;
            dgvResults.DefaultCellStyle.ForeColor = Color.White;
            dgvResults.DefaultCellStyle.SelectionBackColor = Color.FromArgb(40, 50, 75);
            dgvResults.DefaultCellStyle.SelectionForeColor = Color.White;
            dgvResults.ColumnHeadersHeight = 28;
            dgvResults.RowTemplate.Height = 26;

            dgvResults.Columns.Add(new DataGridViewTextBoxColumn { Name = "Username", HeaderText = "Tài khoản", Width = 110 });
            dgvResults.Columns.Add(new DataGridViewTextBoxColumn { Name = "CharName", HeaderText = "Nhân vật", Width = 110 });
            dgvResults.Columns.Add(new DataGridViewTextBoxColumn { Name = "Code", HeaderText = "Mã Giftcode", Width = 130 });
            dgvResults.Columns.Add(new DataGridViewTextBoxColumn { Name = "Status", HeaderText = "Trạng thái / Phản hồi", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill });
            dgvResults.Columns.Add(new DataGridViewTextBoxColumn { Name = "Time", HeaderText = "Thời gian", Width = 75 });

            panelRight.Controls.Add(dgvResults);

            splitLayout.Controls.Add(panelLeft, 0, 0);
            splitLayout.Controls.Add(panelRight, 1, 0);

            // ── 3. Bottom Bar ──
            var panelBottom = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent, Margin = new Padding(0) };
            
            progressBar = new ProgressBar
            {
                Location = new Point(0, 10),
                Size = new Size(340, 20),
                Style = ProgressBarStyle.Continuous
            };
            panelBottom.Controls.Add(progressBar);

            lblStatus = new Label
            {
                Text = "Sẵn sàng",
                Font = new Font("Segoe UI", 9F),
                ForeColor = ColGray400,
                Location = new Point(350, 11),
                AutoSize = true
            };
            panelBottom.Controls.Add(lblStatus);

            btnCopyReport = MkBtn("📋 Sao chép báo cáo");
            btnCopyReport.Location = new Point(this.ClientSize.Width - 200, 5);
            btnCopyReport.Size = new Size(160, 32);
            btnCopyReport.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            btnCopyReport.Click += BtnCopyReport_Click;
            panelBottom.Controls.Add(btnCopyReport);

            mainLayout.Controls.Add(panelHeader, 0, 0);
            mainLayout.Controls.Add(splitLayout, 0, 1);
            mainLayout.Controls.Add(panelBottom, 0, 2);

            this.Controls.Add(mainLayout);
            UpdateCountInfo();
        }

        private void SubscribeEvents()
        {
            _mainForm.GiftCodeResultReceived += OnGiftCodeResultReceived;
            this.FormClosing += (s, e) =>
            {
                _mainForm.GiftCodeResultReceived -= OnGiftCodeResultReceived;
                _cts?.Cancel();
            };
        }

        private List<string> ParseCodes()
        {
            if (string.IsNullOrWhiteSpace(txtGiftCodes.Text)) return new List<string>();
            return txtGiftCodes.Text
                .Split(new[] { '\r', '\n', ',', ';', '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(s => s.Trim())
                .Where(s => s.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
        }

        private void UpdateCountInfo()
        {
            var codes = ParseCodes();
            lblCountInfo.Text = $"{codes.Count} mã";
        }

        private List<string> GetTargetUsernames()
        {
            if (rbAllOnline.Checked)
            {
                return _mainForm.GetOnlineUsernames();
            }
            return _mainForm.GetCheckedUsernamesForGiftCode();
        }

        private async void BtnStart_Click(object sender, EventArgs e)
        {
            if (_isProcessing) return;

            var codes = ParseCodes();
            if (codes.Count == 0)
            {
                MessageBox.Show("Vui lòng nhập hoặc dán ít nhất 1 mã Giftcode!", "Chưa có mã", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                txtGiftCodes.Focus();
                return;
            }

            var targets = GetTargetUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Không có tài khoản nào phù hợp!\nHãy tick chọn ✔ tài khoản hoặc chọn 'Tất cả nick đang kết nối'.",
                    "Chưa chọn nick", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            _isProcessing = true;
            _cts = new CancellationTokenSource();
            btnStart.Enabled = false;
            btnStop.Enabled = true;
            dgvResults.Rows.Clear();

            int delayMs = (int)numDelay.Value;
            int totalSteps = targets.Count * codes.Count;
            int currentStep = 0;
            progressBar.Maximum = totalSteps;
            progressBar.Value = 0;
            lblStatus.Text = $"Đang nhập {codes.Count} mã cho {targets.Count} tài khoản...";

            try
            {
                foreach (var code in codes)
                {
                    if (_cts.IsCancellationRequested) break;

                    foreach (var username in targets)
                    {
                        if (_cts.IsCancellationRequested) break;

                        string charName = _mainForm.GetCharName(username);
                        
                        // Add row to grid
                        int rowIdx = dgvResults.Rows.Add(username, charName, code, "Đang gửi...", DateTime.Now.ToString("HH:mm:ss"));
                        dgvResults.Rows[rowIdx].DefaultCellStyle.ForeColor = ColGray300;
                        dgvResults.FirstDisplayedScrollingRowIndex = rowIdx;

                        // Send packet to session
                        bool sent = _mainForm.SendGiftCodeToUser(username, code);
                        if (!sent)
                        {
                            dgvResults.Rows[rowIdx].Cells["Status"].Value = "Chưa kết nối game";
                            dgvResults.Rows[rowIdx].DefaultCellStyle.ForeColor = ColRed;
                        }

                        currentStep++;
                        progressBar.Value = Math.Min(currentStep, totalSteps);
                        lblStatus.Text = $"Đang xử lý: {currentStep}/{totalSteps} ({username} -> {code})";

                        // Wait delay
                        try
                        {
                            await Task.Delay(delayMs, _cts.Token);
                        }
                        catch (TaskCanceledException) { break; }
                    }
                }

                if (_cts.IsCancellationRequested)
                {
                    lblStatus.Text = "Đã dừng tiến trình nhập.";
                }
                else
                {
                    lblStatus.Text = $"Hoàn tất nhập {codes.Count} mã cho {targets.Count} tài khoản!";
                }
            }
            catch (Exception ex)
            {
                lblStatus.Text = "Lỗi: " + ex.Message;
            }
            finally
            {
                _isProcessing = false;
                btnStart.Enabled = true;
                btnStop.Enabled = false;
            }
        }

        private void BtnStop_Click(object sender, EventArgs e)
        {
            _cts?.Cancel();
            btnStop.Enabled = false;
            lblStatus.Text = "Đang dừng...";
        }

        private void OnGiftCodeResultReceived(string username, string code, string msg, bool success)
        {
            if (this.IsDisposed || !this.IsHandleCreated) return;

            this.BeginInvoke(new Action(() =>
            {
                // Find matching row in grid
                DataGridViewRow targetRow = null;
                for (int i = dgvResults.Rows.Count - 1; i >= 0; i--)
                {
                    var r = dgvResults.Rows[i];
                    string u = r.Cells["Username"].Value?.ToString();
                    string c = r.Cells["Code"].Value?.ToString();
                    if (string.Equals(u, username, StringComparison.OrdinalIgnoreCase) &&
                        string.Equals(c, code, StringComparison.OrdinalIgnoreCase))
                    {
                        targetRow = r;
                        break;
                    }
                }

                if (targetRow != null)
                {
                    targetRow.Cells["Status"].Value = msg;
                    targetRow.Cells["Time"].Value = DateTime.Now.ToString("HH:mm:ss");
                    
                    string low = (msg ?? "").ToLower();
                    if (success)
                    {
                        targetRow.DefaultCellStyle.ForeColor = ColEmerald;
                    }
                    else if (low.Contains("hết hạn") || low.Contains("đã dùng") || low.Contains("đã được sử dụng") || low.Contains("đã sử dụng"))
                    {
                        targetRow.DefaultCellStyle.ForeColor = ColAmber;
                    }
                    else
                    {
                        targetRow.DefaultCellStyle.ForeColor = ColRed;
                    }
                }
                else
                {
                    // If row doesn't exist, add new row
                    string charName = _mainForm.GetCharName(username);
                    int rIdx = dgvResults.Rows.Add(username, charName, code, msg, DateTime.Now.ToString("HH:mm:ss"));
                    targetRow = dgvResults.Rows[rIdx];
                    targetRow.DefaultCellStyle.ForeColor = success ? ColEmerald : ColRed;
                }
            }));
        }

        private void BtnCopyReport_Click(object sender, EventArgs e)
        {
            if (dgvResults.Rows.Count == 0)
            {
                MessageBox.Show("Chưa có kết quả nào để sao chép!", "Thông báo", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            var sb = new StringBuilder();
            sb.AppendLine("=== BÁO CÁO NHẬP GIFTCODE LÀNG LÁ ===");
            sb.AppendLine($"Thời gian: {DateTime.Now:dd/MM/yyyy HH:mm:ss}");
            sb.AppendLine("--------------------------------------------------");
            foreach (DataGridViewRow row in dgvResults.Rows)
            {
                string u = row.Cells["Username"].Value?.ToString();
                string ch = row.Cells["CharName"].Value?.ToString();
                string c = row.Cells["Code"].Value?.ToString();
                string st = row.Cells["Status"].Value?.ToString();
                string t = row.Cells["Time"].Value?.ToString();
                sb.AppendLine($"[{t}] {u} ({ch}) | Code: {c} -> {st}");
            }
            sb.AppendLine("--------------------------------------------------");

            Clipboard.SetText(sb.ToString());
            MessageBox.Show("Đã sao chép toàn bộ kết quả vào Clipboard!", "Thành công", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private Button MkSmallBtn(string text)
        {
            var btn = new Button
            {
                Text = text, BackColor = ColBorder, ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 8.5F, FontStyle.Bold),
                Cursor = Cursors.Hand
            };
            btn.FlatAppearance.BorderSize = 0;
            btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(50, 60, 80);
            return btn;
        }

        private Button MkBtn(string text)
        {
            var btn = new Button
            {
                Text = text, BackColor = ColBtn, ForeColor = ColGray300,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                Cursor = Cursors.Hand
            };
            btn.FlatAppearance.BorderColor = ColBorder;
            btn.FlatAppearance.BorderSize = 1;
            btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(50, 60, 80);
            return btn;
        }

        private void PanelBorder_Paint(object sender, PaintEventArgs e)
        {
            Panel panel = (Panel)sender;
            using (var pen = new Pen(ColBorder, 1))
            {
                e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                int r = 10;
                var path = new GraphicsPath();
                path.AddArc(0, 0, r, r, 180, 90);
                path.AddArc(panel.Width - r - 1, 0, r, r, 270, 90);
                path.AddArc(panel.Width - r - 1, panel.Height - r - 1, r, r, 0, 90);
                path.AddArc(0, panel.Height - r - 1, r, r, 90, 90);
                path.CloseAllFigures();
                panel.Region = new Region(path);
                e.Graphics.DrawPath(pen, path);
            }
        }
    }
}
