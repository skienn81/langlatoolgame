using System;
using System.Linq;
using System.Text.Encodings.Web;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    // ── AccountConfig: Cấu trúc lưu thông tin của một tài khoản ─────────
    public class AccountConfig
    {
        public string Username { get; set; } = "";
        public string Password { get; set; } = "";
        public string Server { get; set; } = "Server 1";

        // ── AFK map/khu RIÊNG cho từng nick ──────────────────────────────────
        // 0 = chưa set riêng → lấy giá trị chung AppConfig.AfkMapId/AfkZone.
        // Nhờ vậy config.json cũ (chưa có 2 field này) vẫn chạy đúng như trước.
        public int AfkMapId { get; set; } = 0;
        public int AfkZone { get; set; } = 0;

        // ── Địa cung ─────────────────────────────────────────────────────────
        // Ngày (yyyy-MM-dd) gần nhất nick này đã BẤM nhận chìa. Khác hôm nay → nhận lại.
        // Lưu ý: đây là "đã bấm", KHÔNG phải bằng chứng server đã cấp chìa — bằng chứng
        // thật là vào được hầm (map đổi). Cờ này chỉ để khỏi bấm thừa mỗi lần chạy lại.
        public string DiaCungKeyDate { get; set; } = "";
        // Hầm mặc định của nick: 1=sơ 2=trung 3=cao 4=thượng. 0 = dùng giá trị trong cfg.
        public int DiaCungTier { get; set; } = 0;
    }

    // ── AppConfig: Cấu trúc lưu cấu hình người dùng ──────────────────────
    public class AppConfig
    {
        public string Username { get; set; } = "";
        public string Password { get; set; } = "";
        public string Server { get; set; } = "Server 1";
        public string GamePath { get; set; } = "";
        public bool HideConsole { get; set; } = true;
        // Map fallback cho nick chưa set riêng (AccountConfig.AfkMapId = 0).
        public int AfkMapId { get; set; } = 74;
        // KHÔNG còn là fallback cho khu — khu chưa set nghĩa là "để server tự xếp".
        // Giá trị này giờ chỉ dùng làm số gợi ý sẵn trong ô "Khu" trên giao diện.
        public int AfkZone { get; set; } = 1;
        public List<AccountConfig> Accounts { get; set; } = new List<AccountConfig>();

        private static string ConfigFilePath =>
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "config.json");

        public void Save()
        {
            try
            {
                var options = new JsonSerializerOptions { WriteIndented = true };
                string json = JsonSerializer.Serialize(this, options);
                File.WriteAllText(ConfigFilePath, json, Encoding.UTF8);
            }
            catch (Exception) { /* Bỏ qua lỗi ghi file */ }
        }

        public static AppConfig Load()
        {
            try
            {
                if (File.Exists(ConfigFilePath))
                {
                    string json = File.ReadAllText(ConfigFilePath, Encoding.UTF8);
                    var config = JsonSerializer.Deserialize<AppConfig>(json);
                    if (config != null)
                    {
                        if (config.Accounts == null)
                            config.Accounts = new List<AccountConfig>();
                        return config;
                    }
                }
            }
            catch (Exception) { /* Nếu lỗi thì trả về config mặc định */ }
            return new AppConfig();
        }
    }

    // ── ServerInfo: Thông tin server game ──────────────────────────────────
    public class ServerInfo
    {
        public string DisplayName { get; set; } = "";
        public string GroupName { get; set; } = "";
        public int Id { get; set; }
        public string Ip { get; set; } = "";
        public int Port { get; set; }
        public int Port2 { get; set; }

        public override string ToString() => $"{DisplayName}  ({Ip}:{Port})";
    }

    // ── Form chính ─────────────────────────────────────────────────────────
    public partial class Form1 : Form
    {
        private TcpListener _server;
        private bool _isRunning = false;
        private List<ClientSession> _sessions = new List<ClientSession>();
        private SynchronizationContext _syncContext;
        private AppConfig _config;
        private List<ServerInfo> _serverList = new List<ServerInfo>();
        private static readonly HttpClient _httpClient = new HttpClient();

        // ── Palette ────────────────────────────────────────────────────────
        private static readonly Color ColBg        = Color.FromArgb(11, 15, 25);    // Slate-950
        private static readonly Color ColPanel     = Color.FromArgb(17, 24, 39);    // Slate-900
        private static readonly Color ColBorder    = Color.FromArgb(31, 41, 55);    // Slate-800
        private static readonly Color ColPrimary   = Color.FromArgb(99, 102, 241);  // Indigo-500
        private static readonly Color ColPriHover  = Color.FromArgb(79, 70, 229);   // Indigo-600
        private static readonly Color ColEmerald   = Color.FromArgb(16, 185, 129);  // Emerald-500
        private static readonly Color ColRed       = Color.FromArgb(239, 68, 68);   // Red-500
        private static readonly Color ColGray400   = Color.FromArgb(156, 163, 175);
        private static readonly Color ColGray300   = Color.FromArgb(209, 213, 219);
        private static readonly Color ColInputBg   = Color.FromArgb(24, 32, 50);
        private const int Radius = 16;

        // ── UI Controls ────────────────────────────────────────────────────
        private Panel panelHeader;
        private Panel panelAccounts;
        private Panel panelLogs;
        private Panel panelControls;
        private Panel panelConfig;
        private Label lblTitle;
        private Button btnCheckUpdate;
        private ReleaseInfo? _latestReleaseInfo;
        private DataGridView dgvAccounts;
        private RichTextBox rtbLogs;
        private Button btnLaunch;
        private Button btnStartAuto;
        private Button btnStopAuto;
        private TextBox txtAfkMap;
        private TextBox txtAfkZone;
        private Button btnSetAfkMap;
        private Button btnSetAfkZone;

        // Config controls
        private TextBox txtUsername;
        private TextBox txtPassword;
        private ComboBox cboServer;
        private TextBox txtGamePath;
        private Button btnBrowseGamePath;
        private CheckBox chkHideConsole;
        private TableLayoutPanel mainLayout;
        private Button btnAddAccountInline, btnDeleteAccountInline;
        private Button btnGetPos, btnVillage, btnKillGame, btnCheckAll;
        private Button btnScanNpc, btnChangeZone;
        private Button btnDiaCung;
        private Button btnCamThuat;
        private Button btnSonCap;
        private Button btnAgt;
        private Button btnHarvest;
        private Button btnItemList;   // 📦 xuất bảng mẫu vật phẩm ra file, để tra mã thêm vào danh sách gom
        private Button btnGomDo;      // 🎒 gom đồ từ mem về lead
        private Button btnTinhThach;  // 💎 đổi đồ lấy tinh thạch ở NPC Kinkaku
        private Button btnQuiz;       // 🧠 Auto Quiz NPC
        private Button btnGiftCode;   // 🎁 Auto nhập Giftcode
        // Đã bỏ nút "🔍 Soi menu NPC": câu hỏi của nó đã trả lời xong (mục con nằm sẵn trong
        // chuỗi của mục cha, xác nhận 11:20 ngày 29/07) và kết luận đã ghi vào quest_anchors.cfg.
        private Button btnGoExit;
        // Nút Sơn cáp là CÔNG TẮC hai trạng thái: bấm lần hai = giải tán nhóm + tắt hẳn.
        private bool _sonCapOn = false;
        // Việc gần nhất mỗi nick báo lên (getStatusText của Mod) — chỉ để hiện trên lưới.
        private readonly Dictionary<string, string> _lastTask = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        private static readonly Color ColBtn = Color.FromArgb(35, 45, 60);
        private bool _allChecked = false; // toggle state cho Check All

        // Login tracking: username -> level (để chờ login tuần tự)
        // Cả hai dictionary dùng chung lock _accountLevels.
        private readonly Dictionary<string, int> _accountLevels = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        // username -> đã thực sự vào game chưa (xem NotifyAccountLogin)
        private readonly Dictionary<string, bool> _accountLoggedIn = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
        // username (tài khoản đăng nhập) -> TÊN NHÂN VẬT trong game.
        // Hai thứ này khác nhau, và mọi lệnh nhóm của game chạy bằng TÊN NHÂN VẬT chứ không
        // phải id hay tài khoản — nên doi_hinh.cfg khai theo username cho dễ đọc, còn lúc gửi
        // lệnh thì tra bảng này để đổi sang tên nhân vật.
        private readonly Dictionary<string, string> _charNames = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        // Đang chạy chuỗi khởi chạy tuần tự? != null nghĩa là đang chạy, bấm nút lần nữa = huỷ.
        private CancellationTokenSource _launchCts;
        private const string LaunchBtnText = "🚀  Khởi chạy";

        public Form1()
        {
            InitializeComponent();
            _config = AppConfig.Load();
            InitializeComponentCustom();
            _syncContext = SynchronizationContext.Current;
        }

        // ── Xây dựng giao diện ────────────────────────────────────────────
        private void InitializeComponentCustom()
        {
            this.Text = "Làng Lá Auto Manager";
            this.Size = new Size(960, 700);
            this.MinimumSize = new Size(750, 500);
            this.BackColor = ColBg;
            this.ForeColor = Color.White;
            this.Font = new Font("Segoe UI", 10F, FontStyle.Regular);
            this.StartPosition = FormStartPosition.CenterScreen;

            // ── Main TableLayoutPanel (5 rows, 1 column) ──
            mainLayout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 5,
                BackColor = ColBg,
                CellBorderStyle = TableLayoutPanelCellBorderStyle.None,
                Padding = new Padding(10, 8, 10, 8),
                Margin = new Padding(0)
            };
            mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));   // 0: Header
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 72));   // 1: Config
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));   // 2: Grid (fill)
            // 3: Buttons. Chiều cao = 6 + soHang*32 + (soHang-1)*4 + đệm.
            //   5 hàng ⇒ 6 + 160 + 16 = 182, làm tròn 192. Thêm hàng nữa thì phải nới số này,
            //   không thì hàng cuối bị cắt mất dù nút vẫn nằm trong build.
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 192));  // 3: Buttons (5 hàng)
            mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 80));   // 4: Logs

            // ── Row 0: Header ──
            panelHeader = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent, Margin = new Padding(0, 0, 0, 4) };
            
            btnCheckUpdate = new Button
            {
                Text = "🔄  Cập nhật",
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
                BackColor = ColPanel,
                ForeColor = ColGray300,
                FlatStyle = FlatStyle.Flat,
                Size = new Size(125, 28),
                Dock = DockStyle.Right,
                Cursor = Cursors.Hand
            };
            btnCheckUpdate.FlatAppearance.BorderColor = ColBorder;
            btnCheckUpdate.Click += async (s, e) => await CheckUpdateManuallyAsync();

            lblTitle = new Label
            {
                Text = $"🍃 LÀNG LÁ AUTO BOT  •  v{UpdateManager.CURRENT_VERSION}",
                Font = new Font("Segoe UI", 12F, FontStyle.Bold),
                ForeColor = ColGray300,
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleLeft
            };
            
            panelHeader.Controls.Add(lblTitle);
            panelHeader.Controls.Add(btnCheckUpdate);

            // ── Row 1: Config (2 dòng ngang) ──
            panelConfig = new Panel { Dock = DockStyle.Fill, BackColor = ColPanel, Margin = new Padding(0, 0, 0, 4) };
            panelConfig.Paint += PanelBorder_Paint;
            panelConfig.Resize += (s, e) => LayoutConfigCompact();
            BuildConfigCompact();

            // ── Row 2: Accounts Grid ──
            panelAccounts = new Panel { Dock = DockStyle.Fill, BackColor = ColPanel, Margin = new Padding(0, 0, 0, 4) };
            panelAccounts.Paint += PanelBorder_Paint;
            BuildAccountsGrid();

            // ── Row 3: Buttons (2×3) ──
            panelControls = new Panel { Dock = DockStyle.Fill, BackColor = ColPanel, Margin = new Padding(0, 0, 0, 4) };
            panelControls.Paint += PanelBorder_Paint;
            panelControls.Resize += (s, e) => LayoutControlButtons();
            BuildControlButtons();

            // ── Row 4: Logs ──
            panelLogs = new Panel { Dock = DockStyle.Fill, BackColor = ColPanel, Margin = new Padding(0) };
            panelLogs.Paint += PanelBorder_Paint;
            BuildLogsPanel();

            // Thêm vào layout theo thứ tự chính xác
            mainLayout.Controls.Add(panelHeader, 0, 0);
            mainLayout.Controls.Add(panelConfig, 0, 1);
            mainLayout.Controls.Add(panelAccounts, 0, 2);
            mainLayout.Controls.Add(panelControls, 0, 3);
            mainLayout.Controls.Add(panelLogs, 0, 4);

            this.Controls.Add(mainLayout);
        }

        // ══════════════════════════════════════════════════════════════════
        // CONFIG COMPACT (2 dòng ngang)
        // ══════════════════════════════════════════════════════════════════

        private void BuildConfigCompact()
        {
            // Row 1: Username | Password | Server | + | ×
            txtUsername = MkInput("Tài khoản");
            txtUsername.Text = _config.Username;
            panelConfig.Controls.Add(txtUsername);

            txtPassword = MkInput("Mật khẩu");
            txtPassword.UseSystemPasswordChar = true;
            txtPassword.Text = _config.Password;
            panelConfig.Controls.Add(txtPassword);

            cboServer = new ComboBox
            {
                BackColor = ColInputBg, ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat, DropDownStyle = ComboBoxStyle.DropDownList,
                Font = new Font("Segoe UI", 9F)
            };
            cboServer.Items.Add("Đang tải...");
            cboServer.SelectedIndex = 0;
            panelConfig.Controls.Add(cboServer);

            btnAddAccountInline = MkSmallBtn("+");
            btnAddAccountInline.Click += BtnAddAccount_Click;
            panelConfig.Controls.Add(btnAddAccountInline);

            btnDeleteAccountInline = MkSmallBtn("×");
            btnDeleteAccountInline.Click += BtnDeleteAccount_Click;
            panelConfig.Controls.Add(btnDeleteAccountInline);

            // Row 2: GamePath | ... | Map: [  ] Khu: [  ] [Set] ☐Ẩn
            txtGamePath = MkInput("Thư mục game...");
            txtGamePath.ReadOnly = true;
            txtGamePath.Text = _config.GamePath;
            panelConfig.Controls.Add(txtGamePath);

            btnBrowseGamePath = MkSmallBtn("...");
            btnBrowseGamePath.Click += BtnBrowseGamePath_Click;
            panelConfig.Controls.Add(btnBrowseGamePath);

            panelConfig.Controls.Add(MkLabel("Map:", "lblMap"));

            txtAfkMap = MkInput("");
            txtAfkMap.TextAlign = HorizontalAlignment.Center;
            txtAfkMap.Text = _config.AfkMapId.ToString();
            panelConfig.Controls.Add(txtAfkMap);

            // Mỗi ô có nút Set riêng → đổi map mà không đụng khu, và ngược lại
            btnSetAfkMap = MkSmallBtn("Set map");
            btnSetAfkMap.Click += BtnSetAfkMap_Click;
            panelConfig.Controls.Add(btnSetAfkMap);

            panelConfig.Controls.Add(MkLabel("Khu:", "lblZone"));

            txtAfkZone = MkInput("");
            txtAfkZone.TextAlign = HorizontalAlignment.Center;
            txtAfkZone.Text = _config.AfkZone.ToString();
            panelConfig.Controls.Add(txtAfkZone);

            btnSetAfkZone = MkSmallBtn("Set khu");
            btnSetAfkZone.Click += BtnSetAfkZone_Click;
            panelConfig.Controls.Add(btnSetAfkZone);

            chkHideConsole = new CheckBox
            {
                Text = "Ẩn console", ForeColor = ColGray400,
                Font = new Font("Segoe UI", 8F), AutoSize = true,
                Checked = _config.HideConsole
            };
            panelConfig.Controls.Add(chkHideConsole);

            LayoutConfigCompact();
        }

        private void LayoutConfigCompact()
        {
            if (txtUsername == null) return;
            int pad = 8, gap = 5, h = 26;
            int w = panelConfig.Width - pad * 2;
            int y1 = 6, y2 = y1 + h + 5;
            int smallBtn = 30;

            // Row 1: Username(30%) | Password(25%) | Server(flex) | +(30) | ×(30)
            int fixedR1 = smallBtn * 2 + gap * 4;
            int flexR1 = w - fixedR1;
            int uW = (int)(flexR1 * 0.30), pW = (int)(flexR1 * 0.25);
            int sW = flexR1 - uW - pW;
            int x = pad;
            txtUsername.SetBounds(x, y1, uW, h); x += uW + gap;
            txtPassword.SetBounds(x, y1, pW, h); x += pW + gap;
            cboServer.SetBounds(x, y1, sW, h); x += sW + gap;
            btnAddAccountInline.SetBounds(x, y1, smallBtn, h); x += smallBtn + gap;
            btnDeleteAccountInline.SetBounds(x, y1, smallBtn, h);

            // Row 2: GamePath | ... | Map: [55] [Set map] | Khu: [45] [Set khu] | ☐Ẩn
            // GamePath co lại còn 32% để nhét thêm nút Set thứ hai mà không tràn dòng.
            int browseW = 30, lblW = 38, setW = 62, chkW = 85, mapW = 55, zoneW = 45;
            int gpW = (int)(w * 0.32);
            int usedRight = browseW + lblW * 2 + mapW + zoneW + setW * 2 + chkW;
            int extraSpace = w - gpW - usedRight;
            int rGap = Math.Max(4, extraSpace / 8); // phân đều gap giữa 8 khoảng

            x = pad;
            txtGamePath.SetBounds(x, y2, gpW, h); x += gpW + rGap;
            btnBrowseGamePath.SetBounds(x, y2, browseW, h); x += browseW + rGap;

            var lblM = panelConfig.Controls.OfType<Label>().FirstOrDefault(l => "lblMap".Equals(l.Tag));
            if (lblM != null) { lblM.Location = new Point(x, y2 + 5); x += lblW; }
            txtAfkMap.SetBounds(x, y2, mapW, h); x += mapW + rGap;
            btnSetAfkMap.SetBounds(x, y2, setW, h); x += setW + rGap;

            var lblZ = panelConfig.Controls.OfType<Label>().FirstOrDefault(l => "lblZone".Equals(l.Tag));
            if (lblZ != null) { lblZ.Location = new Point(x, y2 + 5); x += lblW; }
            txtAfkZone.SetBounds(x, y2, zoneW, h); x += zoneW + rGap;
            btnSetAfkZone.SetBounds(x, y2, setW, h); x += setW + rGap;

            chkHideConsole.Location = new Point(x, y2 + 4);
        }

        // ══════════════════════════════════════════════════════════════════
        // ACCOUNTS GRID
        // ══════════════════════════════════════════════════════════════════

        private void BuildAccountsGrid()
        {
            dgvAccounts = new DataGridView
            {
                Dock = DockStyle.Fill,
                BackgroundColor = ColPanel,
                BorderStyle = BorderStyle.None,
                ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.Single,
                EnableHeadersVisualStyles = false,
                GridColor = ColBorder,
                RowHeadersVisible = false,
                SelectionMode = DataGridViewSelectionMode.FullRowSelect,
                MultiSelect = false,
                AllowUserToAddRows = false,
                AllowUserToDeleteRows = false,
                ReadOnly = false  // Cho phép tick checkbox
            };
            dgvAccounts.ColumnHeadersDefaultCellStyle.BackColor = Color.FromArgb(25, 33, 52);
            dgvAccounts.ColumnHeadersDefaultCellStyle.ForeColor = ColGray300;
            dgvAccounts.ColumnHeadersDefaultCellStyle.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            dgvAccounts.DefaultCellStyle.BackColor = ColPanel;
            dgvAccounts.DefaultCellStyle.ForeColor = Color.White;
            dgvAccounts.DefaultCellStyle.SelectionBackColor = Color.FromArgb(30, 40, 60);
            dgvAccounts.DefaultCellStyle.SelectionForeColor = Color.White;
            dgvAccounts.ColumnHeadersHeight = 28;
            dgvAccounts.RowTemplate.Height = 28;

            // Cột checkbox "Auto" — cho phép tick từng tài khoản
            var chkCol = new DataGridViewCheckBoxColumn
            {
                Name = "AutoCheck",
                HeaderText = "✔",
                Width = 36,
                AutoSizeMode = DataGridViewAutoSizeColumnMode.None,
                FalseValue = false,
                TrueValue = true,
                ReadOnly = false
            };
            dgvAccounts.Columns.Add(chkCol);

            var colUsername = new DataGridViewTextBoxColumn { Name = "Username", HeaderText = "Tài khoản", ReadOnly = true };
            var colStatus  = new DataGridViewTextBoxColumn { Name = "Status",   HeaderText = "Trạng thái", ReadOnly = true };
            var colChar    = new DataGridViewTextBoxColumn { Name = "CharInfo", HeaderText = "Nhân vật", ReadOnly = true };
            // Map/khu AFK riêng của từng nick — dạng "79 / 21", "—" nếu chưa set
            var colAfk     = new DataGridViewTextBoxColumn
            {
                Name = "AfkInfo", HeaderText = "Map / Khu", ReadOnly = true,
                Width = 90, AutoSizeMode = DataGridViewAutoSizeColumnMode.None
            };
            colAfk.DefaultCellStyle.Alignment = DataGridViewContentAlignment.MiddleCenter;
            dgvAccounts.Columns.Add(colUsername);
            dgvAccounts.Columns.Add(colStatus);
            dgvAccounts.Columns.Add(colChar);
            dgvAccounts.Columns.Add(colAfk);

            foreach (DataGridViewColumn col in dgvAccounts.Columns)
            {
                if (col.Name != "AutoCheck" && col.Name != "AfkInfo")
                    col.AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
            }

            // Commit checkbox thay đổi ngay lập tức (không cần click ra ngoài)
            dgvAccounts.CurrentCellDirtyStateChanged += (s, ev) =>
            {
                if (dgvAccounts.IsCurrentCellDirty)
                    dgvAccounts.CommitEdit(DataGridViewDataErrorContexts.Commit);
            };

            dgvAccounts.SelectionChanged += DgvAccounts_SelectionChanged;
            dgvAccounts.CellDoubleClick += DgvAccounts_CellDoubleClick;
            panelAccounts.Controls.Add(dgvAccounts);
        }

        // ══════════════════════════════════════════════════════════════════
        // CONTROL BUTTONS (monochrome 2×3)
        // ══════════════════════════════════════════════════════════════════

        private void BuildControlButtons()
        {
            btnLaunch    = MkBtn("🚀  Khởi chạy");
            btnStartAuto = MkBtn("▶  Auto NV hằng ngày");
            btnStopAuto  = MkBtn("⏹  Tắt Auto");
            btnCheckAll  = MkBtn("☐  Chọn tất cả");
            btnGetPos    = MkBtn("📍  Tọa độ");
            btnVillage   = MkBtn("🏠  Về làng");
            btnKillGame  = MkBtn("💀  Tắt Game");
            btnScanNpc   = MkBtn("🔍  Scan NPC");
            btnChangeZone = MkBtn("🔀  Đổi khu");
            btnDiaCung   = MkBtn("🏯  Địa cung");
            btnCamThuat  = MkBtn("⚔️  Cấm thuật");
            btnSonCap    = MkBtn("🪢  Sơn cáp");
            btnAgt       = MkBtn("🏰  Ải gia tộc");
            btnHarvest   = MkBtn("🧲  Thu số liệu map");
            btnGoExit    = MkBtn("🚪  Thử đi qua map");
            btnItemList  = MkBtn("📦  Danh sách vật phẩm");
            btnGomDo     = MkBtn("🎒  Gom đồ về lead");
            btnTinhThach = MkBtn("💎  Đổi tinh thạch");
            btnQuiz      = MkBtn("🧠  Auto Quiz NPC");
            btnGiftCode  = MkBtn("🎁  Nhập Giftcode");

            btnLaunch.Click += BtnLaunch_Click;
            btnStartAuto.Click += BtnStartAuto_Click;
            btnStopAuto.Click += BtnStopAuto_Click;
            btnCheckAll.Click += BtnCheckAll_Click;
            btnGetPos.Click += BtnGetPos_Click;
            btnVillage.Click += BtnVillage_Click;
            btnKillGame.Click += BtnKillGame_Click;
            btnScanNpc.Click += BtnScanNpc_Click;
            btnChangeZone.Click += BtnChangeZone_Click;
            btnDiaCung.Click += BtnDiaCung_Click;
            btnCamThuat.Click += BtnCamThuat_Click;
            btnSonCap.Click += BtnSonCap_Click;
            btnAgt.Click += BtnAgt_Click;
            btnHarvest.Click += BtnHarvest_Click;
            btnGoExit.Click += BtnGoExit_Click;
            btnItemList.Click += BtnItemList_Click;
            btnGomDo.Click += BtnGomDo_Click;
            btnTinhThach.Click += BtnTinhThach_Click;
            btnQuiz.Click += BtnQuiz_Click;
            btnGiftCode.Click += BtnGiftCode_Click;

            // ── NÚT HIỆN TRÊN GIAO DIỆN, theo đúng thứ tự xếp ──────────────────────────────
            //
            // Bốn nút CÔNG CỤ SOI đã bị ẩn khỏi đây: 📍 Toạ độ · 🔍 Scan NPC ·
            // 🔀 Đổi khu · 🚪 Thử đi qua map. Chúng vẫn được TẠO và VẪN NỐI handler ở trên — chỉ
            // không đưa lên panel. Không xoá vì đó là công cụ dò, mỗi lần cần lại phải viết lại;
            // muốn hiện lại thì thêm tên nút vào mảng này, không phải dựng lại gì cả.
            _nutHien = new Button[] {
                btnLaunch,   btnStartAuto, btnStopAuto,  btnCheckAll,
                btnVillage,  btnKillGame,  btnDiaCung,   btnCamThuat,
                btnSonCap,   btnGiftCode,
                btnAgt,      btnHarvest,   btnItemList,  btnGomDo,
                btnTinhThach, btnQuiz
            };
            panelControls.Controls.AddRange(_nutHien);
            LayoutControlButtons();
        }

        private Button[] _nutHien;

        /// <summary>
        /// Xếp nút thành lưới 4 cột, TỰ XUỐNG DÒNG.
        ///
        /// Xếp theo MỘT danh sách duy nhất chứ không chia sẵn thành hàng r1/r2/r3 như trước: chia
        /// cứng thì mỗi lần thêm hay ẩn một nút lại phải sửa lại từng hàng, mà sửa sót là nút rơi
        /// ra ngoài mép panel — nó vẫn nằm trong build, vẫn nhận sự kiện, chỉ là không ai nhìn
        /// thấy. Đã dính đúng lỗi đó một lần với nút thứ 5 của hàng hoạt động.
        /// </summary>
        private void LayoutControlButtons()
        {
            if (_nutHien == null || _nutHien.Length == 0) return;
            int cols = 4, gap = 5, pad = 8;
            int h = 32, rowGap = 4, y0 = 6;
            int w = (panelControls.Width - pad * 2 - gap * (cols - 1)) / cols;

            for (int i = 0; i < _nutHien.Length; i++)
            {
                int col = i % cols, row = i / cols;
                _nutHien[i].SetBounds(pad + col * (w + gap), y0 + row * (h + rowGap), w, h);
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // LOGS
        // ══════════════════════════════════════════════════════════════════

        private void BuildLogsPanel()
        {
            rtbLogs = new RichTextBox
            {
                Dock = DockStyle.Fill,
                BackColor = ColPanel,
                ForeColor = ColGray400,
                BorderStyle = BorderStyle.None,
                ReadOnly = true,
                Font = new Font("Consolas", 9F)
            };
            panelLogs.Controls.Add(rtbLogs);
        }

        // ══════════════════════════════════════════════════════════════════
        // UI HELPERS
        // ══════════════════════════════════════════════════════════════════

        private TextBox MkInput(string placeholder)
        {
            return new TextBox
            {
                BackColor = ColInputBg, ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Segoe UI", 9F),
                PlaceholderText = placeholder
            };
        }

        private Button MkSmallBtn(string text)
        {
            var btn = new Button
            {
                Text = text, BackColor = ColBorder, ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9F, FontStyle.Bold),
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
                Font = new Font("Segoe UI", 9.5F, FontStyle.Bold),
                Cursor = Cursors.Hand
            };
            btn.FlatAppearance.BorderColor = ColBorder;
            btn.FlatAppearance.BorderSize = 1;
            btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(50, 60, 80);
            return btn;
        }

        private Label MkLabel(string text, string tag)
        {
            return new Label
            {
                Text = text, ForeColor = ColGray400,
                Font = new Font("Segoe UI", 8.5F),
                AutoSize = true, BackColor = Color.Transparent,
                Tag = tag
            };
        }

        /// <summary>Vẽ viền bo góc nhẹ cho panel</summary>
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

        // ════════════════════════════════════════════════════════════════════
        // SỰ KIỆN VÀ LOGIC
        // ════════════════════════════════════════════════════════════════════

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            StartServer();
            _ = FetchServerListAsync();
            ReloadAccountsGrid();
            KhoiDongTheoDoi();
            _ = CheckUpdateInBackgroundAsync();
            Log($"Sẵn sàng! Trần {MaxClient()} client cùng lúc. Tick ✔ nick rồi bấm nút hoạt động.");
        }

        private async Task CheckUpdateInBackgroundAsync()
        {
            try
            {
                // Đợi 2 giây sau khi app khởi động để tránh nghẽn luồng chính
                await Task.Delay(2000);
                var info = await UpdateManager.CheckForUpdatesAsync();
                _latestReleaseInfo = info;

                if (info != null && info.HasUpdate)
                {
                    _syncContext?.Post(_ =>
                    {
                        if (btnCheckUpdate != null && !btnCheckUpdate.IsDisposed)
                        {
                            btnCheckUpdate.Text = $"🔔 Bản mới ({info.TagName})";
                            btnCheckUpdate.BackColor = ColEmerald;
                            btnCheckUpdate.ForeColor = Color.White;
                        }
                        Log($"🔔 Đã có bản cập nhật mới: {info.TagName}! Bấm nút 'Cập nhật' ở góc trên để nâng cấp.");
                    }, null);
                }
            }
            catch (Exception)
            {
                // Bỏ qua lỗi check ngầm
            }
        }

        private async Task CheckUpdateManuallyAsync()
        {
            if (btnCheckUpdate != null)
            {
                btnCheckUpdate.Enabled = false;
                btnCheckUpdate.Text = "⏳ Đang check...";
            }

            try
            {
                var info = await UpdateManager.CheckForUpdatesAsync();
                _latestReleaseInfo = info;

                using var dlg = new UpdateDialog(info);
                dlg.ShowDialog(this);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Không thể kiểm tra bản cập nhật lúc này:\n" + ex.Message, "Lỗi kết nối", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            finally
            {
                if (btnCheckUpdate != null && !btnCheckUpdate.IsDisposed)
                {
                    btnCheckUpdate.Enabled = true;
                    if (_latestReleaseInfo != null && _latestReleaseInfo.HasUpdate)
                    {
                        btnCheckUpdate.Text = $"🔔 Bản mới ({_latestReleaseInfo.TagName})";
                        btnCheckUpdate.BackColor = ColEmerald;
                        btnCheckUpdate.ForeColor = Color.White;
                    }
                    else
                    {
                        btnCheckUpdate.Text = "🔄  Cập nhật";
                        btnCheckUpdate.BackColor = ColPanel;
                        btnCheckUpdate.ForeColor = ColGray300;
                    }
                }
            }
        }

        /// <summary>Báo cho người dùng bằng hộp thoại — gom một chỗ để đổi cách báo là đổi một nơi.</summary>
        private void BaoNguoiDung(string noiDung, string tieuDe, MessageBoxIcon icon)
        {
            MessageBox.Show(noiDung, tieuDe, MessageBoxButtons.OK, icon);
        }

        private void ReloadAccountsGrid()
        {
            _syncContext.Post(_ =>
            {
                // Lưu lại trạng thái online + checkbox trước khi clear
                var onlineStatus = new Dictionary<string, (string Status, string CharInfo, bool Checked)>();
                foreach (DataGridViewRow row in dgvAccounts.Rows)
                {
                    string user = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(user))
                    {
                        bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                        onlineStatus[user] = (
                            row.Cells["Status"].Value?.ToString() ?? "Chưa kết nối",
                            row.Cells["CharInfo"].Value?.ToString() ?? "",
                            isChecked
                        );
                    }
                }

                dgvAccounts.Rows.Clear();
                foreach (var acc in _config.Accounts)
                {
                    string status = "Chưa kết nối";
                    string charInfo = "";
                    bool isChecked = false;

                    if (onlineStatus.TryGetValue(acc.Username, out var os))
                    {
                        status = os.Status;
                        charInfo = os.CharInfo;
                        isChecked = os.Checked;
                    }

                    int idx = dgvAccounts.Rows.Add(isChecked, acc.Username, status, charInfo, FormatAfkInfo(acc));
                    ApplyStatusColor(dgvAccounts.Rows[idx]);
                }

                foreach (var kvp in onlineStatus)
                {
                    if (!_config.Accounts.Exists(a => a.Username.Equals(kvp.Key, StringComparison.OrdinalIgnoreCase)))
                    {
                        int uidx = dgvAccounts.Rows.Add(kvp.Value.Checked, kvp.Key, kvp.Value.Status, kvp.Value.CharInfo, "—");
                        ApplyStatusColor(dgvAccounts.Rows[uidx]);
                    }
                }
            }, null);
        }

        private void DgvAccounts_SelectionChanged(object sender, EventArgs e)
        {
            if (dgvAccounts.SelectedRows.Count > 0)
            {
                var row = dgvAccounts.SelectedRows[0];
                string username = row.Cells["Username"].Value?.ToString();
                if (!string.IsNullOrEmpty(username))
                {
                    var acc = _config.Accounts.Find(a => a.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (acc != null)
                    {
                        txtUsername.Text = acc.Username;
                        txtPassword.Text = acc.Password;
                        var sv = TimServer(acc.Server);
                        if (sv != null) cboServer.SelectedIndex = _serverList.IndexOf(sv);
                    }
                }
            }
        }

        private void DgvAccounts_CellDoubleClick(object sender, DataGridViewCellEventArgs e)
        {
            if (e.RowIndex >= 0 && e.RowIndex < dgvAccounts.Rows.Count)
            {
                string username = dgvAccounts.Rows[e.RowIndex].Cells["Username"].Value?.ToString();
                if (!string.IsNullOrEmpty(username))
                {
                    var acc = _config.Accounts.Find(a => a.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (acc != null)
                    {
                        Log($"🖱️ Double click: Khởi chạy riêng lẻ tài khoản {acc.Username}...");
                        LaunchAccount(acc);
                    }
                }
            }
        }

        private void BtnAddAccount_Click(object sender, EventArgs e)
        {
            string username = txtUsername.Text.Trim();
            string password = txtPassword.Text;

            if (string.IsNullOrEmpty(username))
            {
                MessageBox.Show("Vui lòng nhập tên tài khoản!", "Thông báo", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (string.IsNullOrEmpty(password))
            {
                MessageBox.Show("Vui lòng nhập mật khẩu!", "Thông báo", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            string server = "";
            int selectedIdx = cboServer.SelectedIndex;
            if (selectedIdx >= 0 && selectedIdx < _serverList.Count)
                server = _serverList[selectedIdx].DisplayName;
            else
                server = cboServer.SelectedItem?.ToString() ?? "";

            var existing = _config.Accounts.Find(a => a.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
            if (existing != null)
            {
                existing.Password = password;
                existing.Server = server;
                Log($"✏️ Đã cập nhật tài khoản: {username}");
            }
            else
            {
                _config.Accounts.Add(new AccountConfig
                {
                    Username = username,
                    Password = password,
                    Server = server
                });
                Log($"➕ Đã thêm tài khoản mới: {username}");
            }

            SaveConfig();
            ReloadAccountsGrid();
        }

        private void BtnDeleteAccount_Click(object sender, EventArgs e)
        {
            if (dgvAccounts.SelectedRows.Count == 0)
            {
                MessageBox.Show("Vui lòng chọn tài khoản cần xóa trên danh sách!", "Thông báo", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            string username = dgvAccounts.SelectedRows[0].Cells["Username"].Value?.ToString();
            if (string.IsNullOrEmpty(username)) return;

            var confirmResult = MessageBox.Show($"Bạn có chắc chắn muốn xóa tài khoản {username} khỏi danh sách lưu?",
                                     "Xác nhận xóa",
                                     MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (confirmResult == DialogResult.Yes)
            {
                int removed = _config.Accounts.RemoveAll(a => a.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                if (removed > 0)
                {
                    Log($"❌ Đã xóa tài khoản {username} khỏi cấu hình.");
                    SaveConfig();
                    ReloadAccountsGrid();
                }
            }
        }

        // ── Fetch danh sách server từ URL game ────────────────────────────
        private static readonly string[] ServerListUrls = new[]
        {
            "https://laydata.site/zw2345zz/langla0a23132.txt",
            "https://cloudlangla.beatdz.dev/sg188/serverlistip.txt"
        };

        private async Task FetchServerListAsync()
        {
            Log("🔄 Đang tải danh sách server từ game...");
            string content = null;

            foreach (var url in ServerListUrls)
            {
                try
                {
                    content = await _httpClient.GetStringAsync(url);
                    if (!string.IsNullOrWhiteSpace(content))
                    {
                        Log($"✅ Đã tải danh sách server từ: {url}");
                        break;
                    }
                }
                catch (Exception ex)
                {
                    Log($"⚠ Không thể tải từ {url}: {ex.Message}");
                }
            }

            // Nếu không tải được từ URL, thử đọc arr_server.beatdz từ thư mục game
            if (string.IsNullOrWhiteSpace(content))
            {
                Log("⚠ Không tải được danh sách server online. Sử dụng server mặc định.");
                _serverList = GetDefaultServerList();
            }
            else
            {
                _serverList = ParseServerList(content);
            }

            if (_serverList.Count == 0)
                _serverList = GetDefaultServerList();

            // Cập nhật ComboBox trên UI thread
            _syncContext.Post(_ =>
            {
                cboServer.Items.Clear();
                foreach (var sv in _serverList)
                {
                    cboServer.Items.Add(sv.ToString());
                }
                // Khôi phục server đã lưu. Chọn ô TRƯỚC rồi mới lưu, vì SaveConfig lấy
                // _config.Server từ chính ô này — lưu sớm là ghi đè bằng giá trị cũ.
                string tenDaLuu = _config.Server;
                var svDaLuu = TimServer(tenDaLuu);
                cboServer.SelectedIndex = svDaLuu != null ? _serverList.IndexOf(svDaLuu) : 0;

                int doiTen = DongBoTenServerDaLuu();
                if (doiTen > 0) SaveConfig();

                if (svDaLuu == null && !string.IsNullOrWhiteSpace(tenDaLuu))
                    Log($"⚠ Server đã lưu \"{tenDaLuu}\" KHÔNG còn trong danh sách — tạm chọn " +
                        $"\"{_serverList[0].DisplayName}\". Chọn lại server rồi bấm ➕ để lưu, " +
                        $"nếu không nick sẽ không khởi chạy được.");

                Log($"📋 Đã tải {_serverList.Count} server."
                    + (doiTen > 0 ? $" Nhà phát hành đổi tên {doiTen} chỗ, đã cập nhật theo tên mới." : ""));
            }, null);
        }

        /// <summary>
        /// Parse nội dung file server list có định dạng:
        /// id = GroupName
        /// ServerName = ID:IP:Port1:Port2
        /// </summary>
        private List<ServerInfo> ParseServerList(string content)
        {
            var list = new List<ServerInfo>();
            string currentGroup = "";

            foreach (var rawLine in content.Split('\n'))
            {
                string line = rawLine.Trim().TrimEnd('\r');
                if (string.IsNullOrEmpty(line)) continue;

                // Line dạng: "id = GroupName" → đặt nhóm
                if (line.StartsWith("id", StringComparison.OrdinalIgnoreCase) && line.Contains("="))
                {
                    currentGroup = line.Substring(line.IndexOf('=') + 1).Trim();
                    continue;
                }

                // Line dạng: "ServerName = ID:IP:Port1:Port2"
                int eqIndex = line.IndexOf('=');
                if (eqIndex > 0)
                {
                    string name = line.Substring(0, eqIndex).Trim();
                    string value = line.Substring(eqIndex + 1).Trim();
                    string[] parts = value.Split(':');

                    if (parts.Length >= 3)
                    {
                        var info = new ServerInfo
                        {
                            DisplayName = !string.IsNullOrEmpty(currentGroup) ? $"[{currentGroup}] {name}" : name,
                            GroupName = currentGroup,
                            Ip = parts[1]
                        };
                        int.TryParse(parts[0], out int id); info.Id = id;
                        int.TryParse(parts[2], out int port); info.Port = port;
                        if (parts.Length >= 4)
                        {
                            int.TryParse(parts[3], out int port2); info.Port2 = port2;
                        }

                        // Bỏ qua DEV MOD (localhost)
                        if (info.Ip != "127.0.0.1")
                            list.Add(info);
                    }
                }
            }
            return list;
        }

        // ── Nhận ra server dù nhà phát hành đổi tên ───────────────────────
        /// <summary>
        /// Bỏ phần chú thích trong ngoặc ở ĐUÔI tên server để lấy khoá so sánh ổn định.
        ///
        /// Danh sách của game hay gắn chú thích ngày mở vào thẳng tên: "S14(10/06 OPEN)",
        /// "S15(13/08 TEST)". Chú thích đó bị gỡ hoặc đổi bất cứ lúc nào — S14 vừa rụng mất
        /// "(10/06 OPEN)" — nên so tên NGUYÊN VĂN là hôm nào cũng có thể trượt.
        /// Ngoặc của TÊN NHÓM ("Phong Quốc (NEW)") nằm trước "] " nên không bị đụng tới.
        /// Chuỗi ToString() có kèm "(ip:port)" ở đuôi cũng quy về đúng khoá này.
        /// </summary>
        private static string ChuanHoaTenServer(string ten)
        {
            if (string.IsNullOrWhiteSpace(ten)) return "";
            string nhom = "";
            string phanTen = ten.Trim();
            int cuoiNhom = phanTen.IndexOf("] ", StringComparison.Ordinal);
            if (cuoiNhom >= 0)
            {
                nhom = phanTen.Substring(0, cuoiNhom + 1);
                phanTen = phanTen.Substring(cuoiNhom + 2);
            }
            int moNgoac = phanTen.IndexOf('(');
            if (moNgoac >= 0) phanTen = phanTen.Substring(0, moNgoac);
            return (nhom + " " + phanTen.Trim()).Trim();
        }

        /// <summary>
        /// Tìm server trong danh sách đã tải theo tên đã lưu: so nguyên văn trước, không thấy
        /// thì so theo tên đã bỏ chú thích.
        ///
        /// Không tìm thấy thì trả null và người gọi PHẢI xử lý — tuyệt đối không lặng lẽ lấy
        /// server đầu danh sách. Cách so cũ (Contains hai chiều) còn khớp nhầm: tên đã lưu
        /// "[Hoả Quốc] S10" chứa "[Hoả Quốc] S1" nên bắt trúng S1 đứng trước trong danh sách.
        /// </summary>
        private ServerInfo TimServer(string ten)
        {
            if (string.IsNullOrWhiteSpace(ten) || _serverList.Count == 0) return null;

            var sv = _serverList.Find(s => s.DisplayName.Equals(ten, StringComparison.OrdinalIgnoreCase)
                                        || s.ToString().Equals(ten, StringComparison.OrdinalIgnoreCase));
            if (sv != null) return sv;

            string khoa = ChuanHoaTenServer(ten);
            if (khoa.Length == 0) return null;
            return _serverList.Find(
                s => ChuanHoaTenServer(s.DisplayName).Equals(khoa, StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>
        /// Kéo tên server đã lưu trong config về đúng tên hiện hành sau mỗi lần tải danh sách.
        ///
        /// Không làm thì tên lưu cứ lệch mãi: ô chọn server rơi về server đầu danh sách mỗi lần
        /// mở Manager, và chỉ cần bấm ➕ một cái là nick bị ghi đè sang server đó — lúc đó thì
        /// login nhầm server thật. Trả về số chỗ đã đổi tên.
        /// </summary>
        private int DongBoTenServerDaLuu()
        {
            int doi = 0;

            var svChung = TimServer(_config.Server);
            if (svChung != null && !string.Equals(_config.Server, svChung.DisplayName, StringComparison.Ordinal))
            {
                Log($"🔁 Server đổi tên: \"{_config.Server}\" → \"{svChung.DisplayName}\"");
                _config.Server = svChung.DisplayName;
                doi++;
            }

            foreach (var acc in _config.Accounts)
            {
                var sv = TimServer(acc.Server);
                if (sv == null || string.Equals(acc.Server, sv.DisplayName, StringComparison.Ordinal)) continue;
                acc.Server = sv.DisplayName;
                doi++;
            }
            return doi;
        }

        private List<ServerInfo> GetDefaultServerList()
        {
            return new List<ServerInfo>
            {
                new ServerInfo { DisplayName = "[Liên Server] Liên Server", Id = 1, Ip = "103.77.214.213", Port = 2913, Port2 = 2914 },
                new ServerInfo { DisplayName = "[Hỏa Quốc] S1", Id = 2, Ip = "103.126.161.25", Port = 2915, Port2 = 2916 },
                new ServerInfo { DisplayName = "[Hỏa Quốc] S2", Id = 3, Ip = "103.126.161.25", Port = 2915, Port2 = 2916 },
                new ServerInfo { DisplayName = "[Hỏa Quốc] S3", Id = 4, Ip = "103.126.161.25", Port = 2915, Port2 = 2916 }
            };
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            SaveConfig();
            DungTheoDoi();
            StopServer();
            base.OnFormClosing(e);
        }

        // ── Lưu / Tải cấu hình ────────────────────────────────────────────
        private void SaveConfig()
        {
            _config.Username = txtUsername.Text.Trim();
            _config.Password = txtPassword.Text;
            // Lưu tên server (không bao gồm IP) để dễ khôi phục
            int selectedIdx = cboServer.SelectedIndex;
            if (selectedIdx >= 0 && selectedIdx < _serverList.Count)
                _config.Server = _serverList[selectedIdx].DisplayName;
            else
                _config.Server = cboServer.SelectedItem?.ToString() ?? "";
            _config.GamePath = txtGamePath.Text.Trim();
            if (chkHideConsole != null)
                _config.HideConsole = chkHideConsole.Checked;
            GiuTaiKhoanThemNgoai();
            _config.Save();
        }

        /// <summary>
        /// Giữ lại tài khoản được thêm vào config.json TỪ BÊN NGOÀI trong lúc Manager đang chạy.
        ///
        /// Manager nạp config MỘT LẦN lúc khởi động rồi ghi đè nguyên file mỗi lần lưu (lưu xảy ra
        /// khá thường: bấm Khởi chạy, nick nhận chìa địa cung, đóng app). Sửa file bằng tay trong
        /// lúc app đang mở là lần lưu kế tiếp xoá sạch phần vừa thêm — mất im lặng, không ai biết
        /// cho tới khi mở app lên thấy thiếu nick. Đã xảy ra thật: năm nick thêm tay
        /// biến mất đúng kiểu này.
        ///
        /// Nên trước khi ghi thì đọc lại file: username nào có trên đĩa mà không có trong bộ nhớ
        /// thì gắn thêm vào cuối. KHÔNG đụng tới nick đã có trong bộ nhớ — bộ nhớ mới là bản mới
        /// hơn cho những nick đó (nó đang giữ DiaCungKeyDate của lượt hôm nay).
        /// </summary>
        private void GiuTaiKhoanThemNgoai()
        {
            try
            {
                var tren = AppConfig.Load();
                if (tren?.Accounts == null || tren.Accounts.Count == 0) return;

                var dangCo = new HashSet<string>(
                    _config.Accounts.Select(a => a.Username ?? ""), StringComparer.OrdinalIgnoreCase);

                var them = tren.Accounts
                    .Where(a => !string.IsNullOrWhiteSpace(a.Username) && !dangCo.Contains(a.Username))
                    .ToList();
                if (them.Count == 0) return;

                _config.Accounts.AddRange(them);
                Log($"📄 config.json có {them.Count} nick mới thêm từ bên ngoài — đã giữ lại: " +
                    string.Join(", ", them.Select(a => a.Username)) +
                    ". Mở lại Manager để mấy nick này lên lưới.");
            }
            catch (Exception ex)
            {
                // Đọc lại hỏng thì THÔI KHÔNG GHI phần này, chứ không được ghi đè liều.
                Log($"⚠️ Không đọc lại được config.json trước khi lưu: {ex.Message}");
            }
        }

        // ── Browse thư mục game ────────────────────────────────────────────
        private void BtnBrowseGamePath_Click(object sender, EventArgs e)
        {
            using (var dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Chọn thư mục cài đặt Game Làng Lá (thư mục có jre\\ và lib\\gdx.jar)";
                dialog.UseDescriptionForTitle = true;
                if (!string.IsNullOrEmpty(txtGamePath.Text) && Directory.Exists(txtGamePath.Text))
                {
                    dialog.InitialDirectory = txtGamePath.Text;
                }
                if (dialog.ShowDialog() == DialogResult.OK)
                {
                    txtGamePath.Text = dialog.SelectedPath;
                    // Validate ngay khi chọn
                    string javaExe = Path.Combine(dialog.SelectedPath, "jre", "bin", "java.exe");
                    string gdxJar = Path.Combine(dialog.SelectedPath, "lib", "gdx.jar");
                    if (!File.Exists(javaExe))
                        Log($"⚠ Không tìm thấy java.exe tại: {javaExe}");
                    if (!File.Exists(gdxJar))
                        Log($"⚠ Không tìm thấy gdx.jar tại: {gdxJar}");
                    if (File.Exists(javaExe) && File.Exists(gdxJar))
                        Log($"✅ Đường dẫn game hợp lệ: {dialog.SelectedPath}");
                    SaveConfig();
                }
            }
        }

        // ── Ghi kết quả soi map ra file ────────────────────────────────────
        // Một file cho cả phiên Manager, đặt cạnh doi_hinh.cfg (tức gốc repo) cho dễ tìm. Ghi
        // thẳng từ luồng socket nên phải khoá: 12 nick soi cùng lúc là 12 luồng cùng ghi.
        private readonly object _scanFileLock = new object();
        private string _scanFilePath;

        internal void WriteScanFile(string user, string detail)
        {
            try
            {
                lock (_scanFileLock)
                {
                    if (_scanFilePath == null)
                    {
                        string dir = Path.GetDirectoryName(DoiHinhFilePath) ?? AppDomain.CurrentDomain.BaseDirectory;
                        _scanFilePath = Path.Combine(dir, $"soi_map_{DateTime.Now:yyyyMMdd_HHmmss}.log");
                    }
                    File.AppendAllText(_scanFilePath,
                        $"[{DateTime.Now:HH:mm:ss}] [{user}] {detail}\n", Encoding.UTF8);
                }
            }
            catch { /* ghi log hỏng thì thôi, không được làm chết luồng đọc socket */ }
        }

        /// <summary>Đường dẫn file soi map của phiên này, null nếu chưa soi lần nào.</summary>
        internal string ScanFilePath { get { lock (_scanFileLock) { return _scanFilePath; } } }

        // ── Ghi bảng mẫu vật phẩm ra file RIÊNG ────────────────────────────
        // Tách khỏi file soi map vì bảng mẫu dài hàng nghìn dòng — trộn vào là chôn mất phần soi
        // map, đúng thứ phải đọc được trong lúc chạy hoạt động. Mỗi lần bấm nút ghi một file mới
        // chứ không nối thêm: bảng mẫu là ảnh chụp tại một thời điểm, nối hai lần chụp vào nhau
        // chỉ tạo ra một file nửa cũ nửa mới mà không ai biết ranh giới ở đâu.
        private readonly object _itemFileLock = new object();
        private string _itemFilePath;

        internal void StartItemListFile()
        {
            lock (_itemFileLock)
            {
                string dir = Path.GetDirectoryName(DoiHinhFilePath) ?? AppDomain.CurrentDomain.BaseDirectory;
                _itemFilePath = Path.Combine(dir, $"danh_sach_vat_pham_{DateTime.Now:yyyyMMdd_HHmmss}.log");
            }
        }

        internal void WriteItemListFile(string user, string detail)
        {
            try
            {
                lock (_itemFileLock)
                {
                    if (_itemFilePath == null)
                    {
                        string dir = Path.GetDirectoryName(DoiHinhFilePath) ?? AppDomain.CurrentDomain.BaseDirectory;
                        _itemFilePath = Path.Combine(dir, $"danh_sach_vat_pham_{DateTime.Now:yyyyMMdd_HHmmss}.log");
                    }
                    File.AppendAllText(_itemFilePath, $"[{user}] {detail}\n", Encoding.UTF8);
                }
            }
            catch { /* ghi log hỏng thì thôi, không được làm chết luồng đọc socket */ }
        }

        internal string ItemListFilePath { get { lock (_itemFileLock) { return _itemFilePath; } } }

        // ── Log ────────────────────────────────────────────────────────────
        internal void Log(string message)
        {
            _syncContext.Post(_ =>
            {
                rtbLogs.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}\n");
                rtbLogs.ScrollToCaret();
            }, null);
        }

        // ════════════════════════════════════════════════════════════════════
        // TCP SERVER
        // ════════════════════════════════════════════════════════════════════

        private void StartServer()
        {
            _server = new TcpListener(IPAddress.Any, 9090);
            _isRunning = true;
            _server.Start();
            Log("TCP Server đã khởi động trên cổng 9090.");
            Task.Run(ListenClientsAsync);
        }

        private void StopServer()
        {
            _isRunning = false;
            _server?.Stop();
            lock (_sessions)
            {
                foreach (var s in _sessions) s.Close();
                _sessions.Clear();
            }
        }

        private async Task ListenClientsAsync()
        {
            try
            {
                while (_isRunning)
                {
                    var client = await _server.AcceptTcpClientAsync();
                    var session = new ClientSession(client, this);
                    lock (_sessions) { _sessions.Add(session); }
                    _ = session.ProcessAsync();
                }
            }
            catch (ObjectDisposedException) { }
            catch (Exception ex) { Log($"Lỗi server loop: {ex.Message}"); }
        }

        // ════════════════════════════════════════════════════════════════════
        // KHỞI CHẠY GAME
        // ════════════════════════════════════════════════════════════════════

        private void LaunchAccount(AccountConfig acc)
        {
            string gamePath = txtGamePath.Text.Trim();
            if (string.IsNullOrEmpty(gamePath) || !Directory.Exists(gamePath))
            {
                Log($"❌ Thư mục game không hợp lệ khi chạy {acc.Username}!");
                return;
            }

            // Không dò ra server thì DỪNG, không lấy đại server đầu danh sách: login nhầm
            // server là nhân vật không tồn tại ở đó, còn tệ hơn không login.
            ServerInfo selectedServer = TimServer(acc.Server);
            if (selectedServer == null)
            {
                if (_serverList.Count == 0)
                    Log($"❌ {acc.Username}: danh sách server chưa tải xong → KHÔNG khởi chạy. Chờ dòng \"📋 Đã tải ... server\" rồi chạy lại.");
                else
                {
                    Log($"❌ {acc.Username}: server \"{acc.Server}\" không có trong {_serverList.Count} server hiện hành → KHÔNG khởi chạy. " +
                        $"Chọn lại server trên ô chọn rồi bấm ➕ để gán cho nick này.");
                    TeleLoi($"<b>{TelegramBot.Esc(acc.Username)}</b>: server "
                          + $"\"{TelegramBot.Esc(acc.Server)}\" không còn trong danh sách → không khởi chạy.");
                }
                return;
            }

            bool hideConsole = chkHideConsole != null && chkHideConsole.Checked;
            string javaExeName = hideConsole ? "javaw.exe" : "java.exe";
            string javaExe = Path.Combine(gamePath, "jre", "bin", javaExeName);
            if (!File.Exists(javaExe))
            {
                javaExe = Path.Combine(gamePath, "jre", "bin", "java.exe"); // Fallback
            }
            string gdxJar = Path.Combine(gamePath, "lib", "gdx.jar");

            if (!File.Exists(javaExe))
            {
                Log($"❌ Không tìm thấy java.exe/javaw.exe tại: {javaExe}");
                return;
            }
            if (!File.Exists(gdxJar))
            {
                Log($"❌ Không tìm thấy gdx.jar tại: {gdxJar}");
                return;
            }

            string clientModdedJar = FindClientModdedJar();
            if (string.IsNullOrEmpty(clientModdedJar))
            {
                Log($"❌ Không tìm thấy file client_modded.jar!");
                return;
            }

            try
            {
                string arrServerPath = Path.Combine(gamePath, "animesoft", "1", "arr_server.beatdz");
                string serverIpPort = $"{selectedServer.Ip}:{selectedServer.Port}";
                Directory.CreateDirectory(Path.GetDirectoryName(arrServerPath));
                File.WriteAllText(arrServerPath, serverIpPort, Encoding.UTF8);
                Log($"📝 Đã ghi server vào arr_server.beatdz: {serverIpPort} cho {acc.Username}");
            }
            catch (Exception ex)
            {
                Log($"⚠ Không thể ghi arr_server.beatdz cho {acc.Username}: {ex.Message}");
            }

            string classpath = $"{gdxJar};{clientModdedJar}";
            string serverDisplayName = selectedServer.DisplayName;
            string serverIpPortStr = $"{selectedServer.Ip}:{selectedServer.Port}";
            // Tìm quest_anchors.cfg cùng thư mục client_modded.jar
            string questAnchorsPath = "";
            if (!string.IsNullOrEmpty(clientModdedJar))
                questAnchorsPath = Path.Combine(Path.GetDirectoryName(clientModdedJar) ?? "", "quest_anchors.cfg");
            if (string.IsNullOrEmpty(questAnchorsPath) || !File.Exists(questAnchorsPath))
                questAnchorsPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quest_anchors.cfg");

            string arguments = $"-Dauto.username=\"{acc.Username}\" " +
                               $"-Dauto.password=\"{acc.Password}\" " +
                               $"-Dauto.server=\"{serverDisplayName}\" " +
                               $"-Dauto.server.ip=\"{selectedServer.Ip}\" " +
                               $"-Dauto.server.port=\"{selectedServer.Port}\" " +
                               $"-Dauto.afk.map=\"{EffectiveAfkMap(acc)}\" " +
                               $"-Dauto.afk.zone=\"{EffectiveAfkZone(acc)}\" " +
                               $"-Dquest.anchors.path=\"{Path.GetFullPath(questAnchorsPath)}\" " +
                               $"-cp \"{classpath}\" " +
                               "com.beatdz.langlalau.DesktopLauncher";

            Log($"🚀 Khởi chạy Game Client cho {acc.Username}...");
            Log($"📁 Config anchor: {Path.GetFullPath(questAnchorsPath)} (exists={File.Exists(questAnchorsPath)})");
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = javaExe,
                    Arguments = arguments,
                    WorkingDirectory = gamePath,
                    UseShellExecute = false
                };
                Process.Start(psi);
                Log($"✅ Game Client cho {acc.Username} đã được khởi chạy!");
            }
            catch (Exception ex)
            {
                Log($"❌ Lỗi khởi chạy game {acc.Username}: {ex.Message}");
            }
        }

        private async void BtnLaunch_Click(object sender, EventArgs e)
        {
            // Đang chạy chuỗi khởi chạy → bấm lần nữa = HUỶ (thoát vòng chờ 120s/nick)
            if (_launchCts != null)
            {
                _launchCts.Cancel();
                Log("⏹ Đang dừng khởi chạy...");
                return;
            }

            SaveConfig();

            string gamePath = txtGamePath.Text.Trim();
            if (string.IsNullOrEmpty(gamePath) || !Directory.Exists(gamePath))
            {
                MessageBox.Show("Vui lòng chọn thư mục Game hợp lệ!", "Thiếu thông tin",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            if (_config.Accounts.Count == 0)
            {
                string username = txtUsername.Text.Trim();
                string password = txtPassword.Text;
                if (!string.IsNullOrEmpty(username) && !string.IsNullOrEmpty(password))
                {
                    string server = "";
                    int selectedIdx = cboServer.SelectedIndex;
                    if (selectedIdx >= 0 && selectedIdx < _serverList.Count)
                        server = _serverList[selectedIdx].DisplayName;
                    else
                        server = cboServer.SelectedItem?.ToString() ?? "";

                    var tempAcc = new AccountConfig { Username = username, Password = password, Server = server };
                    LaunchAccount(tempAcc);
                }
                else
                {
                    MessageBox.Show("Danh sách tài khoản trống và không có thông tin tài khoản hợp lệ ở ô nhập!", "Thông báo",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
                return;
            }

            // Chạy theo Ô TÍCH ✔ trên lưới: muốn mở nick nào thì tích nick đó.
            string tenDot = "theo ô tích ✔";
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                BaoNguoiDung("Chưa tích chọn nick nào!\nHãy tick ✔ các nick cần khởi chạy.",
                    "Thông báo", MessageBoxIcon.Warning);
                return;
            }

            // Bỏ qua nick đã login sẵn — bấm nhầm Khởi chạy không mở thêm client trùng nick
            var toLaunch = new List<AccountConfig>();
            var already = new List<string>();
            var treoODangNhap = new List<string>();
            var thieuMatKhau = new List<string>();
            var khongCoTaiKhoan = new List<string>();
            foreach (string username in targets)
            {
                var acc = FindAccount(username);
                if (acc == null) { khongCoTaiKhoan.Add(username); continue; }
                // Có phiên TCP ≠ đã vào game. Client kết nối được nhưng kẹt ở màn đăng nhập vẫn
                // chiếm một chỗ trong trần client, nên KHÔNG mở thêm client trùng nick — chỉ tách
                // ra gọi đúng tên trạng thái để còn biết đường xử.
                if (IsAccountOnline(username))
                {
                    if (IsLoggedIn(username)) already.Add(username);
                    else treoODangNhap.Add(username);
                    continue;
                }
                // Chưa có mật khẩu thì client vẫn mở được nhưng không login nổi, và Manager
                // ngồi chờ trọn 120 giây cho MỖI nick như vậy. Loại từ đầu, nói rõ tên.
                if (string.IsNullOrWhiteSpace(acc.Password)) { thieuMatKhau.Add(username); continue; }
                toLaunch.Add(acc);
            }

            if (khongCoTaiKhoan.Count > 0)
                Log($"⚠️ {khongCoTaiKhoan.Count} nick khai trong doi_hinh.cfg mà KHÔNG có trong config.json: " +
                    string.Join(", ", khongCoTaiKhoan));
            if (thieuMatKhau.Count > 0)
                Log($"⛔ Bỏ qua {thieuMatKhau.Count} nick chưa có mật khẩu: {string.Join(", ", thieuMatKhau)}");
            if (already.Count > 0)
                Log($"⏭️ Bỏ qua {already.Count} nick đã vào game: {string.Join(", ", already)}");
            if (treoODangNhap.Count > 0)
                Log($"🚧 {treoODangNhap.Count} nick có client mở mà CHƯA vào được game — không mở thêm, " +
                    $"tắt client rồi chạy lại nếu cần: {string.Join(", ", treoODangNhap)}");

            if (toLaunch.Count == 0)
            {
                BaoNguoiDung($"Không có nick nào để khởi chạy ở {tenDot}.\n\n" +
                             $"Đã vào game: {already.Count}   ·   kẹt ở đăng nhập: {treoODangNhap.Count}\n" +
                             $"Thiếu mật khẩu: {thieuMatKhau.Count}" +
                             (khongCoTaiKhoan.Count > 0 ? $"   ·   không có trong config.json: {khongCoTaiKhoan.Count}" : ""),
                    "Khởi chạy", MessageBoxIcon.Information);
                return;
            }

            // Trần số client mở cùng lúc. Vượt trần là máy ì hoặc game chặn, mà lúc đó
            // đang ở giữa chuỗi login nên rất khó lần ra vì sao.
            int tran = MaxClient();
            int dangOnline = SoNickOnline();
            if (tran > 0 && dangOnline + toLaunch.Count > tran)
            {
                int conCho = tran - dangOnline;
                if (conCho <= 0)
                {
                    BaoNguoiDung($"Đang có {dangOnline} client online, đã chạm trần {tran}.\n\n" +
                                 "Tắt bớt client (hoặc sửa max_client trong doi_hinh.cfg) rồi khởi chạy tiếp.",
                        "Chạm trần số client", MessageBoxIcon.Warning);
                    return;
                }
                Log($"⚠️ Trần {tran} client: đang online {dangOnline}, chỉ mở thêm {conCho}/{toLaunch.Count} nick.");
                toLaunch = toLaunch.Take(conCho).ToList();
            }

            Log($"🚀 {tenDot}: khởi chạy tuần tự {toLaunch.Count} nick (chờ login từng nick)...");

            // Nút KHÔNG bị disable — bấm lần nữa để huỷ. Trước đây nút bị khoá suốt cả
            // chuỗi chờ (mỗi nick login lỗi = 120s), nhiều nick thì như treo, phải tắt app.
            _launchCts = new CancellationTokenSource();
            var ct = _launchCts.Token;
            try
            {
                for (int i = 0; i < toLaunch.Count; i++)
                {
                    if (ct.IsCancellationRequested) break;

                    var acc = toLaunch[i];
                    // Refresh() để vẽ lại NGAY: đoạn dưới chạy đồng bộ tới tận await đầu tiên,
                    // không ép vẽ thì nút đứng im cho tới lúc đó.
                    btnLaunch.Text = $"⏹  Dừng ({i + 1}/{toLaunch.Count})";
                    btnLaunch.Refresh();

                    // MỘT NICK, NHIỀU LẦN THỬ. Client mở lên mà kẹt ở màn đăng nhập là chuyện hay
                    // xảy ra (server bận, gói login rơi), và cái client kẹt đó KHÔNG tự khỏi: nó
                    // nằm im, ăn một suất trong trần client, còn nick thì không vào game.
                    // Thử lại phải TẮT CLIENT CŨ TRƯỚC — không thì thành hai client cùng một nick.
                    //
                    // Chờ login cho MỌI nick, kể cả nick cuối. Trước đây bỏ qua nick cuối
                    // (i < Count-1) nên tick 1 nick là không có await nào → nút không kịp
                    // đổi trạng thái, và trạng thái "Dừng" tắt ngay khi client vừa spawn.
                    //
                    // "Vào được game" = đủ TÊN NHÂN VẬT + MÁU + LEVEL (chính là dòng 🔓). Không lấy
                    // level làm mốc: a.i.a() tồn tại là j() trả Lv.1 mặc định — nhân vật chưa vào
                    // nổi thế giới mà lưới vẫn hiện Lv.1, đúng ca nick_10 ngày 06/08.
                    int thuLaiToiDa = SoLanThuLaiLogin();
                    int choGiay = SoGiayChoLogin();
                    bool loginOk = false;

                    for (int lan = 0; lan <= thuLaiToiDa && !loginOk; lan++)
                    {
                        if (ct.IsCancellationRequested) break;

                        if (lan > 0)
                        {
                            int daTat = TatClientCuaNick(new[] { acc.Username });
                            Log($"🔁 {acc.Username}: tắt {daTat} client kẹt rồi login lại " +
                                $"(lần {lan}/{thuLaiToiDa})");
                            await Task.Delay(2000, ct);
                        }

                        Log($"[{i + 1}/{toLaunch.Count}] 🎮 Khởi chạy: {acc.Username}" +
                            (lan > 0 ? $" — thử lại {lan}/{thuLaiToiDa}" : "") + "...");
                        LaunchAccount(acc);
                        Log($"⏳ Chờ {acc.Username} vào game (đủ tên nhân vật + máu + level)...");
                        loginOk = await WaitForAccountLogin(acc.Username, choGiay, ct);
                    }

                    if (loginOk)
                    {
                        Log($"✅ {acc.Username} đã vào game.");
                    }
                    else if (!ct.IsCancellationRequested)
                    {
                        // Hết lượt thử thì TẮT client kẹt. Để lại là nó ăn một suất của trần client
                        // suốt buổi mà chẳng làm gì, và nick sau bị chặn vì "chạm trần".
                        int daTat = TatClientCuaNick(new[] { acc.Username });
                        Log($"❌ {acc.Username} KHÔNG vào được game sau {thuLaiToiDa + 1} lần thử " +
                            $"(mỗi lần chờ {choGiay}s) — đã tắt {daTat} client, bỏ qua nick này.");
                        TeleLoi($"<b>{TelegramBot.Esc(acc.Username)}</b> không vào được game sau "
                              + $"{thuLaiToiDa + 1} lần thử — đã tắt client, bỏ qua nick này.");
                    }
                }

                if (ct.IsCancellationRequested)
                    Log("⏹ Đã dừng khởi chạy theo yêu cầu.");
                else
                    Log($"✅ Đã khởi chạy xong {toLaunch.Count} nick!");
            }
            catch (OperationCanceledException)
            {
                Log("⏹ Đã dừng khởi chạy theo yêu cầu.");
            }
            catch (Exception ex)
            {
                // async void: không bắt ở đây thì exception nổi lên UI thread → crash app
                Log($"❌ Lỗi khi khởi chạy: {ex.Message}");
            }
            finally
            {
                _launchCts?.Dispose();
                _launchCts = null;
                btnLaunch.Text = LaunchBtnText;
                btnLaunch.Enabled = true;
            }
        }

        /// <summary>Nick có session TCP đang kết nối không (= đã login vào game).</summary>
        private bool IsAccountOnline(string username)
        {
            lock (_sessions)
            {
                return _sessions.Any(s => !string.IsNullOrEmpty(s.Username)
                                       && string.Equals(s.Username, username, StringComparison.OrdinalIgnoreCase));
            }
        }

        /// <summary>
        /// Tìm file client_modded.jar bằng cách quét từ thư mục chứa Manager.exe
        /// ngược lên các thư mục cha (tối đa 5 cấp).
        /// </summary>
        private string FindClientModdedJar()
        {
            // CẮT DẤU '\' CUỐI TRƯỚC ĐÃ — không phải chi tiết vụn vặt, thiếu nó là hỏng hẳn.
            //
            // AppDomain.CurrentDomain.BaseDirectory LUÔN kết thúc bằng '\', mà
            // Directory.GetParent(@"...\net8.0-windows\") trả về @"...\net8.0-windows" chứ không
            // phải thư mục cha. Lượt đầu vì thế chỉ gỡ dấu gạch, lượt hai kiểm lại đúng thư mục
            // vừa kiểm ⇒ cháy mất một lượt, vòng dò 5 cấp thật ra chỉ với tới `Manager\` và
            // KHÔNG BAO GIỜ tới gốc dự án — đúng chỗ người dùng được bảo là hãy đặt file vào.
            //
            // Trước đây lỗi này bị che bởi một đường dẫn cứng trỏ về máy người viết, nên chạy ở
            // đó thì tốt còn máy người khác thì "Không tìm thấy file client_modded.jar!" trong
            // khi file nằm sờ sờ ở gốc dự án.
            string searchDir = AppDomain.CurrentDomain.BaseDirectory
                                        .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var daDo = new List<string>();
            for (int i = 0; i < 6; i++)
            {
                string candidate = Path.Combine(searchDir, "client_modded.jar");
                daDo.Add(searchDir);
                if (File.Exists(candidate))
                    return candidate;
                var parent = Directory.GetParent(searchDir);
                if (parent == null) break;
                searchDir = parent.FullName;
            }

            // Không thấy thì KỂ RA đã dò ở đâu. Không dò thêm ở đường dẫn cố định nào cả: đoán
            // bừa một chỗ rồi mở nhầm bản jar cũ còn khó truy hơn là báo không thấy. Nhưng "không
            // thấy" mà không nói đã tìm ở đâu thì người dùng chỉ còn nước đoán.
            Log("❌ Không tìm thấy client_modded.jar. Đã dò các thư mục:");
            foreach (var d in daDo) Log($"     {d}");
            Log("   → Chép file vào GỐC DỰ ÁN (cùng chỗ với doi_hinh.cfg) rồi bấm lại.");
            return null;
        }

        // ── Bật / Tắt Auto (dựa vào checkbox) ──────────────────────────────
        private void BtnStartAuto_Click(object sender, EventArgs e)
        {
            SendCommandChecked("start_auto");
        }

        private void BtnStopAuto_Click(object sender, EventArgs e)
        {
            SendCommandChecked("stop_auto");
            RefreshAllStatusColors();
        }

        /// <summary>
        /// Toggle Check All / Uncheck All cho checkbox trong grid
        /// </summary>
        private void BtnCheckAll_Click(object sender, EventArgs e)
        {
            _allChecked = !_allChecked;
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                row.Cells["AutoCheck"].Value = _allChecked;
            }
            btnCheckAll.Text = _allChecked ? "☑  Bỏ chọn tất cả" : "☐  Chọn tất cả";
            Log(_allChecked ? "☑ Đã chọn tất cả tài khoản" : "☐ Đã bỏ chọn tất cả tài khoản");
        }

        /// <summary>
        /// Gửi lệnh cho các tài khoản được tick checkbox trong grid.
        /// </summary>
        private void SendCommandChecked(string command)
        {
            var checkedUsernames = new List<string>();
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                if (isChecked)
                {
                    string username = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(username))
                        checkedUsernames.Add(username);
                }
            }

            if (checkedUsernames.Count == 0)
            {
                MessageBox.Show("Vui lòng tick chọn (✔) ít nhất 1 tài khoản trong danh sách!",
                    "Chưa chọn tài khoản", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            int sent = 0;
            lock (_sessions)
            {
                // Debug: log only connected sessions
                var connectedDebug = _sessions.Where(s => s.IsConnected && s.Username != null).Select(s => s.Username).ToList();
                Log($"🔍 Sessions kết nối: {connectedDebug.Count}/{_sessions.Count} — [{string.Join(", ", connectedDebug)}]");
                foreach (var username in checkedUsernames)
                {
                    var session = _sessions.Find(s =>
                        s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (session != null && session.IsConnected)
                    {
                        try
                        {
                            session.SendCommand(command);
                            Log($"📤 Đã gửi '{command}' tới {username}");
                            sent++;
                        }
                        catch (Exception ex)
                        {
                            Log($"⚠ Gửi '{command}' tới {username} thất bại: {ex.Message}");
                        }
                    }
                    else
                    {
                        Log($"⚠ {username}: Không có kết nối TCP, bỏ qua");
                    }
                }
            }
            Log($"✅ Kết quả '{command}': {sent}/{checkedUsernames.Count} nick nhận lệnh");
        }

        private void BtnGetPos_Click(object sender, EventArgs e)
        {
            SendCommandAll("get_pos");
        }

        private void BtnVillage_Click(object sender, EventArgs e)
        {
            SendCommandAll("go_village");
            Log("🏠 Đã gửi lệnh 'Về làng' cho tất cả tài khoản.");
        }

        // ══════════════════════════════════════════════════════════════════════════════════
        //  🎒 GOM ĐỒ VỀ LEAD
        // ══════════════════════════════════════════════════════════════════════════════════
        //
        // MỘT MEM MỘT LÚC — đây là ràng buộc của chính game, không phải cách làm cho gọn: cửa sổ
        // giao dịch chỉ nhận ĐƯỢC MỘT đối phương. Phát điểm hẹn cho cả 11 mem cùng lúc thì 11 nick
        // cùng chạy tới đòi giao dịch với một lead, một nick vào được còn 10 nick đứng chờ tới hết
        // giờ. Nên Manager giữ HÀNG ĐỢI và chỉ mở cửa cho mem đầu hàng.
        //
        // Cả nhóm về làng trước. Về làng là chỗ hẹn duy nhất chắc chắn tới được từ mọi nơi — mem
        // đang ở hầm, ở ải, ở map treo thì mỗi nick một đường về khác nhau, mà `go_village` đã lo
        // sẵn phần đó rồi.
        private GroupSetup _gomGroup;
        private readonly List<string> _gomQueue = new List<string>();
        private string _gomCurrent;          // username của mem đang tới lượt
        private string _gomLeadChar = "";    // tên NHÂN VẬT của lead
        private int _gomMap = -1, _gomZone = -1, _gomX = -1, _gomY = -1;
        private bool _gomOn = false;

        public void SetGomButton(bool on)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => SetGomButton(on))); return; }
            _gomOn = on;
            btnGomDo.Text = on ? "🛑  Ngừng gom đồ" : "🎒  Gom đồ về lead";
            btnGomDo.BackColor = on ? Color.FromArgb(120, 40, 40) : btnDiaCung.BackColor;
        }

        /// <summary>
        /// Đội hình GOM ĐỒ — khối riêng trong doi_hinh.cfg:
        ///     [gom]
        ///     nhan_do = &lt;username nhận hết đồ&gt;
        ///     team    = 1,2                       (hoặc liệt kê thẳng từng nick)
        ///
        /// KHÔNG dùng lại các khối [camthuat:...] của Cấm thuật/Sơn cáp. Ba nhóm CT-1/CT-2/CT-3
        /// có ba trưởng nhóm khác nhau, chạy theo đó là đồ nằm rải trên ba tài khoản — ngược hẳn
        /// mục đích gom. Gom đồ cần ĐÚNG MỘT túi đích, nên phải có khối khai riêng.
        /// </summary>
        private GroupSetup LoadGomSetup()
        {
            var ds = LoadNhom("gom");
            if (ds.Count == 0) return null;
            if (ds.Count > 1)
                Log($"🎒 ⚠️ Có {ds.Count} khối [gom] — chỉ dùng khối đầu tiên.");
            // KHÔNG đòi phải có mem khai sẵn: nút 🎒 tự lấy nick đang trong game làm mem, khối
            // này chỉ còn bắt buộc khai đúng một thứ — nick NHẬN đồ.
            return ds[0];
        }

        private void BtnGomDo_Click(object sender, EventArgs e)
        {
            if (_gomOn) { StopGom("người dùng bấm dừng"); return; }

            var g = LoadGomSetup();
            if (g == null)
            {
                // Dựng sẵn khối mẫu từ chính danh sách tài khoản đang có để anh chỉ việc chép vào,
                // khỏi phải tự gõ 12 username. Nick đầu danh sách làm lead — sửa lại nếu muốn nick khác.
                var all = _config.Accounts.Select(a => a.Username)
                                 .Where(u => !string.IsNullOrWhiteSpace(u)).ToList();
                string mau = "[gom]\nnhan_do = " + (all.Count > 0 ? all[0] : "<username nhận đồ>");
                Log("🎒 Chưa khai nick nhận đồ. Chép khối này vào doi_hinh.cfg:  " +
                    mau.Replace("\n", " / "));
                MessageBox.Show(
                    "Chưa khai khối [gom] trong doi_hinh.cfg.\n\n" +
                    "Chỉ cần khai ĐÚNG MỘT thứ — nick nhận đồ:\n\n" +
                    "    [gom]\n" +
                    "    nhan_do = <username nhận đồ>\n\n" +
                    "Không phải khai danh sách mem: nút này lấy MỌI NICK ĐANG TRONG GAME làm mem.\n\n" +
                    "Khối gợi ý theo tài khoản đầu danh sách (đã ghi ra ô log để chép):\n\n" +
                    mau + "\n\n" +
                    "Khối này TÁCH RIÊNG với [camthuat:...] của Cấm thuật/Sơn cáp — ba nhóm CT có " +
                    "ba trưởng nhóm khác nhau, gom theo đó thì đồ nằm rải trên ba tài khoản.\n\n" +
                    "Bấm nút này rồi: cả đội về làng → lead phát khu + toạ độ cho TỪNG mem một → " +
                    "mem tới nơi thì chuyển đồ trong gom_item_ids (quest_anchors.cfg) sang cho lead.",
                    "Gom đồ về lead");
                return;
            }

            // Lead chưa vào game thì không có gì để bàn — mọi thứ còn lại đều xoay quanh nó.
            if (FindSession(g.Leader) == null)
            {
                MessageBox.Show($"Nick nhận đồ '{g.Leader}' chưa kết nối.\n\n" +
                                "Khai ở khối [gom] trong doi_hinh.cfg — tài khoản ở dòng " +
                                "\"nhan_do =\" là nick nhận đồ.", "Gom đồ về lead");
                return;
            }

            // ĐỘI HÌNH = MỌI NICK ĐANG Ở TRONG GAME, trừ nick nhận đồ.
            //
            // Trước đây lấy theo danh sách khai trong file (team 1+2). Khai sẵn thì luôn lệch với
            // thực tế: nick khai mà không online thì hàng đợi phải bỏ qua, nick online mà không
            // khai thì ôm đồ ngồi đó — mà "ai đang online" là thứ Manager biết chắc chắn, khỏi
            // ai phải cập nhật tay. Nick nào không có món nào trong danh sách gom thì bên mod tự
            // bỏ qua ngay, không tốn lượt khoá 30 giây.
            var memOnline = _config.Accounts
                .Select(a => a.Username)
                .Where(u => !string.IsNullOrWhiteSpace(u)
                            && !string.Equals(u, g.Leader, StringComparison.OrdinalIgnoreCase)
                            && IsLoggedIn(u))
                .ToList();
            if (memOnline.Count == 0)
            {
                MessageBox.Show($"Ngoài nick nhận đồ '{g.Leader}' ra thì không nick nào đang ở " +
                                "trong game — không có ai để gom.", "Gom đồ về lead");
                return;
            }

            var gOnline = new GroupSetup { Name = "gom", Leader = g.Leader };
            gOnline.Members.AddRange(memOnline);
            Log($"🎒 Đội hình gom lấy theo nick ĐANG TRONG GAME: {memOnline.Count} nick giao đồ cho " +
                $"'{g.Leader}'.");
            BatDauGom(gOnline);
        }

        /// <summary>
        /// Khởi động máy gom cho MỘT đội hình cụ thể. Tách khỏi nút để gọi lại được từ chỗ khác
        /// được đúng đội hình của đợt đang chạy: đợt 2 gom team 3 về cùng cái lead của team 1
        /// (lead ở lại cả ngày), chứ không gom lại đúng danh sách khai trong file.
        /// </summary>
        private void BatDauGom(GroupSetup g)
        {
            var vang = g.Members.Where(m => FindSession(m) == null).ToList();
            if (vang.Count > 0)
                Log($"🎒 {vang.Count} nick chưa kết nối, sẽ bỏ qua: {string.Join(", ", vang)}");

            _gomGroup = g;
            _gomQueue.Clear();
            _gomQueue.AddRange(g.Members);
            _gomCurrent = null;
            _gomLeadChar = "";
            _gomMap = _gomZone = _gomX = _gomY = -1;

            // Mem: đẩy về làng ngay để đứng chờ sẵn. Chỉ một mem giao dịch tại một thời điểm, nên
            // những mem chưa tới lượt cứ về trước là lúc tới lượt chỉ còn bước áp sát.
            foreach (var m in g.Members) FindSession(m)?.SendRawJson("{\"command\":\"go_village\"}\n");

            // Lead: KHÔNG gửi go_village. `gom_lead_start` gọi stopCurrentActivity(), mà hàm đó
            // kết thúc bằng clearNavTarget() — nó xoá sạch đích mà go_village vừa đặt, lead đứng
            // chết tại map treo (đo được 03/08). Giờ lead tự có bước về làng trong tickGom và chỉ
            // báo vị trí khi đã tới nơi, nên gửi thêm go_village chỉ tạo hai lệnh giằng nhau.
            FindSession(g.Leader)?.SendRawJson("{\"command\":\"gom_lead_start\"}\n");

            SetGomButton(true);
            TeleBatDauGom(g.Leader, _gomQueue.Count);
            Log($"🎒 Gom đồ: cả đội về làng, đồ dồn về '{g.Leader}', hàng đợi {_gomQueue.Count} mem "
                + "(chạy tuần tự từng mem một)");

            // Lead báo vị trí sau khi về tới làng. Hỏi lặp lại chứ không hỏi một lần: lúc vừa bấm
            // thì lead còn đang trên đường, toạ độ lúc đó là chỗ cũ.
            _gomAskTimer?.Stop();
            _gomAskTimer = new System.Windows.Forms.Timer { Interval = 5000 };
            _gomAskTimer.Tick += (s2, e2) =>
            {
                if (!_gomOn) { _gomAskTimer.Stop(); return; }
                FindSession(_gomGroup?.Leader)?.SendRawJson("{\"command\":\"gom_lead_report\"}\n");
            };
            _gomAskTimer.Start();
        }

        private System.Windows.Forms.Timer _gomAskTimer;

        private void StopGom(string vi_sao) { StopGom(vi_sao, true); }

        /// <param name="xongCaLuot">
        /// true = đã chạy hết hàng đợi, hoặc người dùng chủ động bấm dừng ⇒ lead được đi treo.
        /// false = dừng vì lỗi khi còn mem chưa gom ⇒ lead ĐỨNG NGUYÊN tại chỗ hẹn.
        /// Phân biệt vì lead là điểm hẹn của cả hàng đợi: nó bỏ đi giữa chừng thì mem còn lại
        /// chạy tới chỗ trống, hoặc chạy theo lead sang tận map treo.
        /// </param>
        private void StopGom(string vi_sao, bool xongCaLuot)
        {
            _gomAskTimer?.Stop();
            if (_gomGroup != null)
            {
                string lenh = "{\"command\":\"gom_stop\",\"xong\":" + (xongCaLuot ? 1 : 0) + "}\n";
                FindSession(_gomGroup.Leader)?.SendRawJson(lenh);
                // Mem thì luôn được đi treo — mem xong việc đứng không mới là phí.
                foreach (var m in _gomGroup.Members)
                    FindSession(m)?.SendRawJson("{\"command\":\"gom_stop\",\"xong\":1}\n");
            }
            _gomQueue.Clear();
            _gomCurrent = null;
            _gomGroup = null;
            SetGomButton(false);
            TeleXongGom(vi_sao);
            Log($"🎒🛑 Dừng gom đồ ({vi_sao}).");
        }

        /// <summary>Lead báo đang đứng ở đâu → phát điểm hẹn cho member đang tới lượt.</summary>
        public void RelayGomLeadAt(string leaderUsername, int map, int zone, int x, int y, string leadChar)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayGomLeadAt(leaderUsername, map, zone, x, y, leadChar)));
                return;
            }
            if (!_gomOn || _gomGroup == null) return;
            if (!string.Equals(_gomGroup.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase)) return;

            bool doiCho = (map != _gomMap || zone != _gomZone);
            _gomMap = map; _gomZone = zone; _gomX = x; _gomY = y;
            if (!string.IsNullOrWhiteSpace(leadChar)) _gomLeadChar = leadChar;

            if (_gomCurrent == null) { GomNextMember(); return; }

            // Lead đổi chỗ giữa chừng thì phát lại cho ĐÚNG member đang tới lượt — không phát cho
            // cả nhóm, nếu không là hỏng đúng cái luật một-mem-một-lúc.
            if (doiCho) GomSendMeetingPoint(_gomCurrent, "lead đổi chỗ");
        }

        private void GomNextMember()
        {
            if (!_gomOn || _gomGroup == null) return;

            // BỎ QUA NICK CHƯA VÀO GAME. Không bỏ thì Manager gửi điểm hẹn vào hư không —
            // FindSession trả null, lệnh không đi đâu cả, và hàng đợi nằm chờ một nick không bao
            // giờ báo về. Đo thật lúc 12:50 ngày 03/08: chỉ 3 nick online mà hàng đợi vẫn phát cho
            // nick_06 rồi treo ở đó. Treo im không rõ lý do là kiểu hỏng khó truy nhất.
            while (_gomQueue.Count > 0 && FindSession(_gomQueue[0]) == null)
            {
                Log($"🎒⏭️ Bỏ qua '{_gomQueue[0]}' — chưa kết nối.");
                _gomQueue.RemoveAt(0);
            }

            if (_gomQueue.Count == 0)
            {
                Log("🎒✅ Gom đồ xong: đã chạy hết hàng đợi.");
                StopGom("hết hàng đợi");
                return;
            }
            _gomCurrent = _gomQueue[0];
            _gomQueue.RemoveAt(0);
            GomSendMeetingPoint(_gomCurrent, $"tới lượt, còn {_gomQueue.Count} nick sau");
        }

        private void GomSendMeetingPoint(string memberUsername, string vi_sao)
        {
            if (_gomMap < 0) return;   // chưa biết lead đứng đâu thì chưa phát được
            FindSession(memberUsername)?.SendRawJson(
                $"{{\"command\":\"gom_mem_start\",\"map\":{_gomMap},\"zone\":{_gomZone}," +
                $"\"x\":{_gomX},\"y\":{_gomY},\"lead\":\"{EscapeJson(_gomLeadChar)}\"}}\n");
            Log($"🎒 [{memberUsername}] điểm hẹn map {_gomMap} khu {_gomZone} ({_gomX},{_gomY}) — {vi_sao}");
        }

        /// <summary>Member đã tới chỗ lead, hoặc vừa xong một lượt và còn đồ → bảo lead mời tiếp.</summary>
        public void RelayGomInvite(string memberUsername, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayGomInvite(memberUsername, detail)));
                return;
            }
            if (!_gomOn || _gomGroup == null) return;
            if (!string.Equals(_gomCurrent, memberUsername, StringComparison.OrdinalIgnoreCase))
            {
                // Tiếng của member KHÔNG tới lượt — bỏ qua. Nếu không thì một member nhận lệnh
                // muộn có thể chen ngang lượt của người khác, đúng thứ hàng đợi sinh ra để tránh.
                Log($"🎒 [{memberUsername}] báo '{detail}' nhưng chưa tới lượt — bỏ qua");
                return;
            }
            string memChar = GetCharName(memberUsername);
            FindSession(_gomGroup.Leader)?.SendRawJson(
                $"{{\"command\":\"gom_invite\",\"who\":\"{EscapeJson(memChar)}\"}}\n");
            Log($"🎒 [{memberUsername}] {detail} → bảo lead mời '{memChar}' giao dịch");
        }

        /// <summary>Member hết đồ trong danh sách (hoặc hỏng) → sang member kế tiếp.</summary>
        public void RelayGomMemberDone(string memberUsername, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayGomMemberDone(memberUsername, detail)));
                return;
            }
            if (!_gomOn) return;
            if (!string.Equals(_gomCurrent, memberUsername, StringComparison.OrdinalIgnoreCase)) return;
            Log($"🎒 [{memberUsername}] {detail} → sang member kế tiếp");
            _gomCurrent = null;
            GomNextMember();
        }

        /// <summary>Member không chen được vào khu của lead → bảo lead nhảy khu khác.</summary>
        public void RelayGomZoneFull(string memberUsername, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayGomZoneFull(memberUsername, detail)));
                return;
            }
            if (!_gomOn || _gomGroup == null) return;
            if (!string.Equals(_gomCurrent, memberUsername, StringComparison.OrdinalIgnoreCase)) return;
            FindSession(_gomGroup.Leader)?.SendRawJson("{\"command\":\"gom_zone_hop\"}\n");
            Log($"🎒⚠️ [{memberUsername}] {detail} → bảo lead nhảy khu khác");
        }

        // 💎 ĐỔI TINH THẠCH — mỗi nick tự chạy, KHÔNG cần nhóm và không cần điều phối.
        //
        // Khác hẳn 🎒 Gom đồ: gom đồ phải xếp hàng vì cửa sổ giao dịch chỉ nhận một đối phương,
        // còn đổi tinh thạch là mỗi nick nói chuyện riêng với NPC — 12 nick chạy song song vô hại.
        //
        // KHÔNG khai danh sách món: món nào đổi được thì chính game đã tính sẵn qua `d_0.w()` —
        // đúng hàm mà bảng thông tin món dùng để in "Có thể đổi # Tinh thạch". Tool lấy mọi món
        // có w() > 0 và không khoá.
        private void BtnTinhThach_Click(object sender, EventArgs e)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Tích các nick muốn đổi tinh thạch.\n\n" +
                                "Mỗi nick tự đi tới NPC Kinkaku ở Làng Cỏ (toạ độ khai trong " +
                                "quest_anchors.cfg: tinh_thach_x / tinh_thach_y), mở NPC, chọn mục " +
                                "\"Đổi tinh thạch\", rồi đổi hết trang bị đổi được — 16 ô một lượt, " +
                                "lặp tới khi hết đồ.\n\n" +
                                "Không cần khai danh sách món: game đã tính sẵn món nào đổi được " +
                                "bao nhiêu tinh thạch, tool đọc thẳng con số đó. Món KHOÁ thì bỏ qua.\n\n" +
                                "Chạy song song được trên nhiều nick — mỗi nick nói chuyện riêng với NPC.");
                return;
            }
            int sent = 0;
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"tinh_thach_start\"}\n");
                sent++;
            }
            Log($"💎 Đổi tinh thạch: đã gửi lệnh cho {sent}/{targets.Count} nick.");
        }

        /// <summary>
        /// Một nick báo đã hết nhiệm vụ ngày.
        ///
        /// Giữ lại thành TẬP HỢP chứ không chỉ ghi log: cần đếm "đủ 12/12 nick
        /// xong chưa" để mở rào chắn trước bước gom đồ. Đếm bằng tín hiệu chứ không bằng đồng hồ.
        /// </summary>
        private readonly HashSet<string> _nvXong = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        public void RelayAutoNvEnd(string username, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayAutoNvEnd(username, detail)));
                return;
            }
            _nvXong.Add(username);
            Log($"📋 [{username}] {detail}");
        }

        /// <summary>Nick này đã báo hết nhiệm vụ ngày chưa (tính từ lần bật Auto NV gần nhất).</summary>
        internal bool DaXongNhiemVu(string username) { return _nvXong.Contains(username); }

        /// <summary>Xoá dấu đã-xong — gọi khi bắt đầu một lượt chạy mới.</summary>
        internal void XoaDauXongNhiemVu() { _nvXong.Clear(); }

        /// <summary>
        /// Một nick báo kết thúc lượt đổi tinh thạch. Giữ thành tập hợp vì cùng lý do với
        /// <see cref="_nvXong"/>: cần biết đủ nick báo xong chưa.
        /// Ghi cả lượt HỎNG — hỏng cũng là đã xong, chờ thêm cũng không đổi được gì.
        /// </summary>
        private readonly HashSet<string> _tinhThachXong = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        public void RelayTinhThachEnd(string username, bool ok, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayTinhThachEnd(username, ok, detail)));
                return;
            }
            _tinhThachXong.Add(username);
            Log($"{(ok ? "💎" : "💎❌")} [{username}] {detail}");
        }

        /// <summary>Một nick báo kết thúc lượt Địa cung (thành công hay không).</summary>
        private readonly HashSet<string> _diaCungXong = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        public void RelayDiaCungEnd(string username, bool ok, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayDiaCungEnd(username, ok, detail)));
                return;
            }
            _diaCungXong.Add(username);
            Log($"{(ok ? "🏯✅" : "🏯❌")} [{username}] {detail}");
        }

        // 📦 DANH SÁCH VẬT PHẨM — xuất bảng mẫu của game ra file để tra mã.
        //
        // Cần vì danh sách gom khai bằng MÃ chứ không bằng tên: tên có dấu, có khoảng trắng thừa
        // ('Mảnh huyết kế giới hạn ' thừa một dấu cách ở cuối), so tên là gãy âm thầm. Muốn thêm
        // món mới vào danh sách gom thì mở file này, tìm tên, lấy mã.
        //
        // Chỉ cần MỘT nick — bảng mẫu là dữ liệu chung của game, nick nào đọc cũng ra như nhau.
        private void BtnItemList_Click(object sender, EventArgs e)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Tích một nick bất kỳ (đang trong game).\n\n" +
                                "Nút này xuất BẢNG MẪU VẬT PHẨM của game ra file " +
                                "danh_sach_vat_pham_<ngày_giờ>.log cạnh doi_hinh.cfg — mỗi dòng là " +
                                "một món: mã, tên, loại, có xếp chồng hay không.\n\n" +
                                "Dùng để tra mã khi muốn thêm món mới vào danh sách gom " +
                                "(gom_item_ids trong quest_anchors.cfg).\n\n" +
                                "Thuần ĐỌC bộ nhớ client — không gửi gói nào lên server.");
                return;
            }

            // Chỉ hỏi MỘT nick. Bảng mẫu giống hệt nhau trên mọi nick nên hỏi 12 nick chỉ tạo ra
            // một file 12 bản sao chồng lên nhau, không thêm được thông tin gì.
            string u = targets[0];
            var ss = FindSession(u);
            if (ss == null) { Log($"📦 {u} chưa kết nối."); return; }

            StartItemListFile();
            ss.SendRawJson("{\"command\":\"item_list\"}\n");
            Log($"📦 Đang xuất bảng mẫu vật phẩm từ {u} → {ItemListFilePath}");
        }

        private void BtnKillGame_Click(object sender, EventArgs e)
        {
            try
            {
                int killCount = 0;
                // Tìm và kill tất cả process java/javaw đang chạy game client
                foreach (var proc in Process.GetProcesses())
                {
                    try
                    {
                        string name = proc.ProcessName.ToLower();
                        if (name == "java" || name == "javaw")
                        {
                            // Kiểm tra command line có chứa DesktopLauncher (game client)
                            string cmdLine = "";
                            try
                            {
                                using (var searcher = new System.Management.ManagementObjectSearcher(
                                    $"SELECT CommandLine FROM Win32_Process WHERE ProcessId = {proc.Id}"))
                                {
                                    foreach (var obj in searcher.Get())
                                    {
                                        cmdLine = obj["CommandLine"]?.ToString() ?? "";
                                    }
                                }
                            }
                            catch { }

                            if (cmdLine.Contains("DesktopLauncher") || cmdLine.Contains("client_modded"))
                            {
                                proc.Kill();
                                killCount++;
                            }
                        }
                    }
                    catch { }
                }

                // Đóng tất cả TCP sessions
                lock (_sessions)
                {
                    foreach (var s in _sessions) s.Close();
                    _sessions.Clear();
                }

                // Reset trạng thái bảng accounts (giữ danh sách, chỉ reset trạng thái)
                ReloadAccountsGrid();

                Log($"💀 Đã kill {killCount} game client(s) và đóng tất cả kết nối!");
            }
            catch (Exception ex)
            {
                Log($"❌ Lỗi khi kill game: {ex.Message}");
            }
        }

        /// <summary>
        /// Tắt client của ĐÚNG mấy nick truyền vào. Nhận ra client của nick nào bằng
        /// <c>-Dauto.username="..."</c> trong dòng lệnh — chính tham số Manager truyền lúc mở
        /// client, nên không cần nhớ pid và vẫn đúng cả với client mở từ phiên Manager trước.
        /// </summary>
        private int TatClientCuaNick(IEnumerable<string> nicks)
        {
            var can = new HashSet<string>(nicks, StringComparer.OrdinalIgnoreCase);
            if (can.Count == 0) return 0;

            int killed = 0;
            foreach (var proc in Process.GetProcesses())
            {
                try
                {
                    string name = proc.ProcessName.ToLower();
                    if (name != "java" && name != "javaw") continue;

                    string cmdLine = "";
                    try
                    {
                        using (var searcher = new System.Management.ManagementObjectSearcher(
                            $"SELECT CommandLine FROM Win32_Process WHERE ProcessId = {proc.Id}"))
                        {
                            foreach (var obj in searcher.Get())
                                cmdLine = obj["CommandLine"]?.ToString() ?? "";
                        }
                    }
                    catch { }
                    if (cmdLine.Length == 0) continue;
                    if (!cmdLine.Contains("DesktopLauncher") && !cmdLine.Contains("client_modded")) continue;

                    var m = System.Text.RegularExpressions.Regex.Match(
                        cmdLine, "-Dauto\\.username=\"?([^\"\\s]+)\"?");
                    if (!m.Success || !can.Contains(m.Groups[1].Value)) continue;

                    proc.Kill();
                    killed++;
                }
                catch { }
            }

            // Đóng luôn phiên TCP: process chết rồi mà phiên còn treo thì lưới vẫn hiện "online"
            // và số client đang online sẽ đếm sai.
            lock (_sessions)
            {
                foreach (var s in _sessions.Where(s => can.Contains(s.Username ?? "")).ToList())
                {
                    s.Close();
                    _sessions.Remove(s);
                }
            }
            ReloadAccountsGrid();
            return killed;
        }

        /// <summary>Nick đang được thao tác: ưu tiên dòng đang chọn, nếu không thì dòng tick đầu tiên.</summary>
        private string GetTargetUsername()
        {
            if (dgvAccounts.SelectedRows.Count > 0)
            {
                string sel = dgvAccounts.SelectedRows[0].Cells["Username"].Value?.ToString();
                if (!string.IsNullOrEmpty(sel)) return sel;
            }
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                if (isChecked)
                {
                    string username = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(username)) return username;
                }
            }
            return null;
        }

        internal ClientSession FindSession(string username)
        {
            if (string.IsNullOrEmpty(username)) return null;
            lock (_sessions)
            {
                return _sessions.Find(s =>
                    s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
            }
        }

        /// <summary>Danh sách username đang tick ✔ trong bảng.</summary>
        private List<string> GetCheckedUsernames()
        {
            var list = new List<string>();
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                if (isChecked)
                {
                    string u = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(u)) list.Add(u);
                }
            }
            return list;
        }

        private void RefreshAllStatusColors()
        {
            foreach (DataGridViewRow row in dgvAccounts.Rows)
                ApplyStatusColor(row);
        }

        /// <summary>Gửi 1 câu JSON thô tới mọi nick đang tick ✔. Trả về số nick nhận được.</summary>
        private int SendJsonChecked(string json, string label)
        {
            var checkedUsernames = new List<string>();
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                if (isChecked)
                {
                    string u = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(u)) checkedUsernames.Add(u);
                }
            }
            if (checkedUsernames.Count == 0)
            {
                MessageBox.Show("Vui lòng tick chọn (✔) ít nhất 1 tài khoản trong danh sách!",
                    "Chưa chọn tài khoản", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 0;
            }

            int sent = 0;
            lock (_sessions)
            {
                foreach (var username in checkedUsernames)
                {
                    var session = _sessions.Find(s =>
                        s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (session != null && session.IsConnected)
                    {
                        try
                        {
                            session.SendCommandJson(json);
                            sent++;
                        }
                        catch (Exception ex)
                        {
                            Log($"⚠ Gửi '{label}' tới {username} thất bại: {ex.Message}");
                        }
                    }
                    else
                    {
                        Log($"⚠ {username}: Không có kết nối TCP, bỏ qua");
                    }
                }
            }
            return sent;
        }

        private void BtnScanNpc_Click(object sender, EventArgs e)
        {
            SendCommandChecked("scan_npc");
            // Search NPC event by keyword
            var checkedUsernames = new List<string>();
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                bool isChecked = row.Cells["AutoCheck"].Value != null && (bool)row.Cells["AutoCheck"].Value;
                if (isChecked)
                {
                    string username = row.Cells["Username"].Value?.ToString();
                    if (!string.IsNullOrEmpty(username))
                        checkedUsernames.Add(username);
                }
            }
            // Từ khoá tìm NPC. Đổi ở đây khi cần dò một NPC khác — công cụ soi, dùng lúc
            // cần tra id/toạ độ một NPC chưa khai trong quest_anchors.cfg.
            string keyword = "";
            lock (_sessions)
            {
                foreach (var username in checkedUsernames)
                {
                    var session = _sessions.Find(s => s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (session != null && session.IsConnected)
                    {
                        try
                        {
                            session.SendCommandJson($"{{\"command\":\"search_npc\",\"keyword\":\"{keyword}\"}}");
                            Log($"🔎 Tìm NPC '{keyword}' trên {username}");
                        }
                        catch (Exception ex) { Log($"⚠ search_npc error: {ex.Message}"); }
                    }
                }
            }
            // Also search by HP for event NPC
            lock (_sessions)
            {
                foreach (var username in checkedUsernames)
                {
                    var session = _sessions.Find(s => s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                    if (session != null && session.IsConnected)
                    {
                        try
                        {
                            session.SendCommandJson("{\"command\":\"search_hp\",\"hp\":1000}");
                            Log($"🔎 Tìm HP≥1000 trên {username}");
                        }
                        catch { }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // AFK MAP / KHU — cấu hình RIÊNG theo từng nick
        // ══════════════════════════════════════════════════════════════════

        /// <summary>Map AFK hiệu lực của một nick: giá trị riêng, chưa set thì lấy giá trị chung.</summary>
        private int EffectiveAfkMap(AccountConfig acc)
            => (acc != null && acc.AfkMapId > 0) ? acc.AfkMapId : _config.AfkMapId;

        /// <summary>
        /// Khu AFK hiệu lực của một nick. KHÔNG fallback về giá trị chung như map:
        /// 0 = "không đụng tới khu" → client bỏ qua bước đổi khu, để server tự xếp
        /// (xem TaskManager.tickAfkFarm: chỉ đổi khu khi afkZone > 0).
        /// </summary>
        private int EffectiveAfkZone(AccountConfig acc)
            => (acc != null) ? acc.AfkZone : 0;

        private AccountConfig FindAccount(string username)
            => _config.Accounts.FirstOrDefault(a =>
                   string.Equals(a.Username, username, StringComparison.OrdinalIgnoreCase));

        /// <summary>Chuỗi hiển thị cột "Map / Khu". Khu = 0 → "tự do" (server tự xếp).</summary>
        private string FormatAfkInfo(AccountConfig acc)
        {
            int m = EffectiveAfkMap(acc), z = EffectiveAfkZone(acc);
            if (m <= 0) return "—";
            return (z > 0) ? $"{m} / {z}" : $"{m} / tự do";
        }

        private void BtnSetAfkMap_Click(object sender, EventArgs e)
        {
            if (!int.TryParse(txtAfkMap.Text.Trim(), out int mapId) || mapId <= 0)
            {
                MessageBox.Show("Vui lòng nhập Map ID hợp lệ (số > 0)!");
                return;
            }
            ApplyAfkToChecked(mapId, null);
        }

        private void BtnSetAfkZone_Click(object sender, EventArgs e)
        {
            if (!int.TryParse(txtAfkZone.Text.Trim(), out int zone) || zone <= 0)
            {
                MessageBox.Show("Vui lòng nhập số Khu hợp lệ (số > 0)!");
                return;
            }
            ApplyAfkToChecked(null, zone);
        }

        /// <summary>
        /// Áp map và/hoặc khu cho các nick ĐANG TICK. Tham số null = giữ nguyên giá trị cũ
        /// của nick đó — nhờ vậy "Set map" không đụng tới khu và ngược lại.
        /// </summary>
        private void ApplyAfkToChecked(int? mapId, int? zone)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Chưa tích chọn nick nào!\nHãy tick ✔ các nick cần áp dụng.");
                return;
            }

            foreach (string username in targets)
            {
                var acc = FindAccount(username);
                if (acc == null) continue;                       // nick lạ (không có trong config)
                if (mapId.HasValue) acc.AfkMapId = mapId.Value;
                if (zone.HasValue) acc.AfkZone = zone.Value;
            }
            _config.Save();

            // Gửi cho đúng các session đang tick & đang kết nối. Luôn gửi CẢ map lẫn khu
            // (lệnh set_afk_map ở client nhận cặp) — giá trị không đổi thì lấy lại từ account.
            int sentCount = 0;
            lock (_sessions)
            {
                foreach (var session in _sessions)
                {
                    if (string.IsNullOrEmpty(session.Username)) continue;
                    if (!targets.Any(u => string.Equals(u, session.Username, StringComparison.OrdinalIgnoreCase)))
                        continue;

                    var acc = FindAccount(session.Username);
                    int m = EffectiveAfkMap(acc), z = EffectiveAfkZone(acc);
                    if (m <= 0) continue;                        // chưa có map thì chưa gửi được

                    session.SendRawJson($"{{\"command\":\"set_afk_map\",\"map\":{m},\"zone\":{z}}}\n");
                    sentCount++;
                }
            }

            string what = mapId.HasValue ? $"map={mapId.Value}" : $"khu={zone.Value}";
            Log($"📍 Đã set {what} cho {targets.Count} nick đang tick (gửi ngay tới {sentCount} nick đang online)");
            RefreshAfkColumn();
        }

        /// <summary>
        /// Đổi khu NGAY cho các nick đang tick, không phụ thuộc nick đang làm gì.
        /// Khác "Set khu": nút kia chỉ LƯU cấu hình (có hiệu lực khi nick vào AFK farm),
        /// nút này gửi thẳng lệnh đổi khu tới game.
        /// </summary>
        private void BtnChangeZone_Click(object sender, EventArgs e)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Chưa tích chọn nick nào!\nHãy tick ✔ các nick cần đổi khu.");
                return;
            }

            // Khu đã cấu hình riêng của nick; chưa set thì lấy số đang gõ ở ô Khu.
            int.TryParse(txtAfkZone.Text.Trim(), out int typedZone);

            int sentCount = 0, skipped = 0;
            lock (_sessions)
            {
                foreach (var session in _sessions)
                {
                    if (string.IsNullOrEmpty(session.Username)) continue;
                    if (!targets.Any(u => string.Equals(u, session.Username, StringComparison.OrdinalIgnoreCase)))
                        continue;

                    var acc = FindAccount(session.Username);
                    int z = (acc != null && acc.AfkZone > 0) ? acc.AfkZone : typedZone;
                    if (z <= 0) { skipped++; continue; }

                    session.SendRawJson($"{{\"command\":\"change_zone_now\",\"zone\":{z}}}\n");
                    sentCount++;
                }
            }

            if (sentCount == 0 && skipped > 0)
                MessageBox.Show("Chưa có khu để đổi!\nNhập số vào ô Khu, hoặc bấm 'Set khu' trước.");
            else
                Log($"🔀 Đã gửi lệnh đổi khu ngay tới {sentCount} nick" +
                    (skipped > 0 ? $" (bỏ qua {skipped} nick chưa có khu)" : ""));
        }

        private void BtnQuiz_Click(object sender, EventArgs e)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Chưa tích chọn nick nào!\nHãy tick ✔ các nick muốn chạy Auto Quiz NPC.");
                return;
            }

            int sentCount = 0, notLoggedIn = 0;
            lock (_sessions)
            {
                foreach (var session in _sessions)
                {
                    if (string.IsNullOrEmpty(session.Username)) continue;
                    if (!targets.Any(u => string.Equals(u, session.Username, StringComparison.OrdinalIgnoreCase)))
                        continue;

                    if (!IsLoggedIn(session.Username)) { notLoggedIn++; continue; }

                    session.SendRawJson("{\"command\":\"quiz_start\"}\n");
                    sentCount++;
                }
            }

            Log($"🧠 Auto Quiz NPC — đã gửi lệnh tới {sentCount} nick" +
                (notLoggedIn > 0 ? $" (bỏ qua {notLoggedIn} nick chưa vào game)" : ""));
        }

        private void BtnGiftCode_Click(object sender, EventArgs e)
        {
            var form = new GiftCodeForm(this);
            form.Show(this);
        }

        public event Action<string, string, string, bool> GiftCodeResultReceived;

        public void OnGiftCodeResult(string username, string code, string msg, bool success)
        {
            Log($"🎁 [{username}] Code '{code}': {msg}");
            _syncContext?.Post(_ => GiftCodeResultReceived?.Invoke(username, code, msg, success), null);
        }

        public List<string> GetCheckedUsernamesForGiftCode()
        {
            return GetCheckedUsernames();
        }

        public List<string> GetOnlineUsernames()
        {
            lock (_sessions)
            {
                return _sessions
                    .Where(s => !string.IsNullOrEmpty(s.Username))
                    .Select(s => s.Username)
                    .Distinct(StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
        }

        public bool SendGiftCodeToUser(string username, string code)
        {
            if (string.IsNullOrEmpty(username) || string.IsNullOrEmpty(code)) return false;
            ClientSession session;
            lock (_sessions)
            {
                session = _sessions.Find(s =>
                    s.Username != null && s.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
            }
            if (session == null) return false;

            session.SendRawJson("{\"command\":\"giftcode\",\"code\":\"" + EscapeJson(code) + "\"}\n");
            return true;
        }

        /// <summary>
        /// Địa cung — bước 1: bảo đảm các nick đang tick là TRƯỞNG của một nhóm.
        /// Hoạt động Địa cung chỉ cần "có nhóm + là trưởng", không cần mời thêm ai.
        /// Mỗi lần bấm đi MỘT bước (gửi lệnh → chờ server trả CMD 43); bấm lại để xác nhận.
        /// </summary>
        private void BtnDiaCung_Click(object sender, EventArgs e)
        {
            // Bấm hoạt động khác là máy Sơn cáp bị dừng bên mod (stopCurrentActivity),
            // nên nút phải thôi hiện "đang chạy" — chữ trên nút phải nói đúng trạng thái thật.
            if (_sonCapOn) SetSonCapButton(false);
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Chưa tích chọn nick nào!\nHãy tick ✔ các nick cần tạo nhóm Địa cung.");
                return;
            }

            int sentCount = 0, notLoggedIn = 0, skipped = 0;
            lock (_sessions)
            {
                foreach (var session in _sessions)
                {
                    if (string.IsNullOrEmpty(session.Username)) continue;
                    if (!targets.Any(u => string.Equals(u, session.Username, StringComparison.OrdinalIgnoreCase)))
                        continue;

                    // Chưa vào game thì đối tượng nhóm phía client chưa tồn tại → gửi cũng vô nghĩa.
                    if (!IsLoggedIn(session.Username)) { notLoggedIn++; continue; }

                    var acc = FindAccount(session.Username);
                    string today = DateTime.Now.ToString("yyyy-MM-dd");
                    bool skipKey = acc != null && acc.DiaCungKeyDate == today;
                    int tier = (acc != null) ? acc.DiaCungTier : 0;
                    if (skipKey) skipped++;

                    session.SendRawJson(
                        $"{{\"command\":\"dia_cung_run\",\"tier\":{tier},\"skipKey\":{(skipKey ? "true" : "false")}}}\n");
                    sentCount++;
                }
            }

            Log($"🏯 Địa cung — đã gửi lệnh tới {sentCount} nick" +
                (skipped > 0 ? $", {skipped} nick hôm nay đã nhận chìa nên vào thẳng hầm" : "") +
                (notLoggedIn > 0 ? $" (bỏ qua {notLoggedIn} nick chưa vào game)" : ""));
        }

        // ══════════════════════════════════════════════════════════════════
        // CẤM THUẬT — GOM NHÓM THEO DANH SÁCH SETUP
        // ══════════════════════════════════════════════════════════════════
        //
        // Vì sao Manager phải đứng giữa: giao thức nhóm của game bắt buộc phải cùng KHU
        // (lời mời chỉ tìm người trong khu của người mời; và hoạt động cấm thuật kéo người
        // theo khu của trưởng nhóm). Khu lại KHÔNG chọn trước được — trưởng nhóm có thể
        // phải nhảy khu vì khu đã đủ số nhóm. Chỉ Manager thấy được cả hai phía nên nó
        // nhận khu từ trưởng nhóm rồi phát lại cho member.
        //
        // Danh sách nhóm khai theo TÀI KHOẢN ĐĂNG NHẬP (thứ hiện trên lưới), Manager tự đổi
        // sang TÊN NHÂN VẬT lúc gửi lệnh — tên nhân vật mod đã báo lên sẵn khi vào game.

        /// <summary>Một nhóm trong doi_hinh.cfg: 1 trưởng + n thành viên, khai theo username.</summary>
        public class GroupSetup
        {
            public string Name = "";
            public string Leader = "";
            public List<string> Members = new List<string>();
        }

        /// <summary>
        /// Nơi đặt doi_hinh.cfg. Ưu tiên để CẠNH quest_anchors.cfg ở gốc repo cho dễ tìm và
        /// không bị xoá khi build lại — thư mục bin\Release là sản phẩm build, không phải chỗ
        /// giữ file người dùng sửa tay. Đã có sẵn file cạnh Manager.exe thì vẫn dùng file đó.
        /// </summary>
        private static string DoiHinhFilePath => ResolveDoiHinhPath();

        private static string ResolveDoiHinhPath()
        {
            string local = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "doi_hinh.cfg");
            if (File.Exists(local)) return local;

            var dir = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.Parent)
            {
                string here = Path.Combine(dir.FullName, "doi_hinh.cfg");
                if (File.Exists(here)) return here;
                // Gốc repo nhận ra bằng quest_anchors.cfg — chưa có doi_hinh.cfg thì tạo ở đây.
                if (File.Exists(Path.Combine(dir.FullName, "quest_anchors.cfg"))) return here;
            }
            return local;
        }

        // Nhóm đang chạy: tên nhóm -> setup. Dùng để biết khu của trưởng nhóm phát cho ai.
        private readonly Dictionary<string, GroupSetup> _ctActive =
            new Dictionary<string, GroupSetup>(StringComparer.OrdinalIgnoreCase);
        // tên nhóm -> "map/khu" đã phát gần nhất, chỉ để khỏi ghi log lặp mỗi chu kỳ phát lại.
        private readonly Dictionary<string, string> _ctLastRelay =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        // Nhóm ĐÚNG NHƯ KHAI TRONG FILE, kể cả nick chưa vào game. _ctActive chỉ giữ những
        // nick thật sự nhận được lệnh, nên không dùng nó để tính "đủ hay thiếu" được.
        private readonly Dictionary<string, GroupSetup> _ctPlanned =
            new Dictionary<string, GroupSetup>(StringComparer.OrdinalIgnoreCase);

        // tên nhóm -> dòng tổng kết gần nhất, để chỉ ghi log khi đội hình thực sự đổi.
        private readonly Dictionary<string, string> _ctLastRoster =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// Đội hình thật của một nhóm, lấy từ danh sách server trả về cho trưởng nhóm (CMD 43).
        /// Đây là dữ liệu để trả lời "nhóm nào đủ, nhóm nào thiếu", và về sau là cửa chặn
        /// trước khi cho cả nhóm vào cấm thuật.
        /// </summary>
        public class GroupRoster
        {
            public string Name = "";
            public int Map;
            public int Zone;
            public List<string> Have = new List<string>();     // tên nhân vật đang ở trong nhóm
            public List<string> Missing = new List<string>();   // đã gửi lệnh nhưng chưa vào nhóm
            public List<string> Absent = new List<string>();    // khai trong file nhưng chưa vào game
            public List<string> Strangers = new List<string>(); // đang ở trong nhóm mà không có trong file
            public int Planned;                                  // sĩ số theo file (kể cả trưởng nhóm)
            // Đủ quân VÀ sạch người lạ. Bước vào cấm thuật sau này chỉ được đi khi cờ này bật:
            // người lạ đi ké là mất suất của member thật (nhóm tối đa 10).
            public bool Full => Missing.Count == 0 && Absent.Count == 0 && Strangers.Count == 0;
        }

        // Kết quả gom nhóm gần nhất của từng nhóm. Bước "vào map cấm thuật" sau này đọc bảng này.
        private readonly Dictionary<string, GroupRoster> _ctRosters =
            new Dictionary<string, GroupRoster>(StringComparer.OrdinalIgnoreCase);

        // ══ doi_hinh.cfg ═══════════════════════════════════════════════════
        // Thay cho doi_hinh.cfg kiểu "một dòng một nhóm, ngăn bằng dấu phẩy". Dòng gom đồ đã dài
        // 19 tên, thêm 6 nick nữa là không ai soát bằng mắt được nữa, mà soát sai thì chỉ lộ ra
        // lúc đang chạy giữa chừng. Dạng khối, mỗi dòng một nick, đọc và sửa đều thẳng mắt:
        //
        //     [camthuat:CT-1]
        //     truong = nick_01        ← dòng có '=' là khai VAI
        //     nick_02                 ← dòng trơn là THÀNH VIÊN
        //
        // Bốn loại khối: team / camthuat / soncap / agt / gom. Khối [agt] và [gom] khai được
        // "team = 1,2" để lấy trọn nick của team — sửa team một chỗ, hai khối kia tự theo.

        /// <summary>Một khối [...] trong doi_hinh.cfg, còn nguyên như trong file.</summary>
        private class DoiHinhKhoi
        {
            public string Loai = "";     // team | camthuat | soncap | agt | gom
            public string Ten = "";      // phần sau dấu ':' — rỗng nếu khối không đặt tên
            public string Vai = "";      // truong= / mo_cua= / nhan_do=
            public readonly List<string> Nicks = new List<string>();
            public readonly List<string> Teams = new List<string>();   // team= 1,2
            // Mọi dòng "khoá = giá trị" giữ nguyên ở đây, kể cả khoá riêng của khối [chung].
            public readonly Dictionary<string, string> Khai =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            public string Nhan => Ten.Length > 0 ? Loai + ":" + Ten : Loai;
        }

        /// <summary>
        /// Đọc nguyên doi_hinh.cfg thành danh sách khối. Đọc lại mỗi lần bấm nút chứ không nhớ
        /// vào bộ nhớ: sửa file xong bấm nút là ăn ngay, khỏi khởi động lại Manager.
        /// </summary>
        private List<DoiHinhKhoi> DocDoiHinh()
        {
            var kq = new List<DoiHinhKhoi>();
            try
            {
                if (!File.Exists(DoiHinhFilePath)) { TaoDoiHinhMau(); return kq; }

                DoiHinhKhoi cur = null;
                foreach (var raw in File.ReadAllLines(DoiHinhFilePath, Encoding.UTF8))
                {
                    // '#' ở bất kỳ đâu cũng cắt phần còn lại — cho phép ghi chú ngay sau tên nick.
                    string line = raw;
                    int cmt = line.IndexOf('#');
                    if (cmt >= 0) line = line.Substring(0, cmt);
                    line = line.Trim();
                    if (line.Length == 0) continue;

                    if (line.StartsWith("[") && line.EndsWith("]"))
                    {
                        string head = line.Substring(1, line.Length - 2).Trim();
                        int hai = head.IndexOf(':');
                        cur = new DoiHinhKhoi
                        {
                            Loai = (hai >= 0 ? head.Substring(0, hai) : head).Trim().ToLowerInvariant(),
                            Ten = hai >= 0 ? head.Substring(hai + 1).Trim() : ""
                        };
                        kq.Add(cur);
                        continue;
                    }

                    // Dòng lạc ngoài mọi khối: kêu lên chứ không nuốt im — nick khai hụt một khối
                    // là cả nhóm ngồi chờ một người không bao giờ tới.
                    if (cur == null)
                    {
                        Log($"⚠️ doi_hinh.cfg: dòng '{line}' nằm ngoài mọi khối [...] — bỏ qua");
                        continue;
                    }

                    int eq = line.IndexOf('=');
                    if (eq >= 0)
                    {
                        string k = line.Substring(0, eq).Trim().ToLowerInvariant();
                        string v = line.Substring(eq + 1).Trim();
                        cur.Khai[k] = v;
                        if (k == "team")
                        {
                            foreach (var t in v.Split(','))
                            {
                                string tt = t.Trim();
                                if (tt.Length > 0) cur.Teams.Add(tt);
                            }
                        }
                        else if (k == "truong" || k == "mo_cua" || k == "nhan_do" || k == "lead")
                        {
                            cur.Vai = v;
                        }
                        else if (cur.Loai != "chung")
                        {
                            Log($"⚠️ doi_hinh.cfg: khối [{cur.Nhan}] khai lạ '{k}' — bỏ qua");
                        }
                        continue;
                    }

                    cur.Nicks.Add(line);
                }
            }
            catch (Exception ex)
            {
                Log($"❌ Lỗi đọc doi_hinh.cfg: {ex.Message}");
            }
            return kq;
        }

        private void TaoDoiHinhMau()
        {
            try
            {
                File.WriteAllText(DoiHinhFilePath, string.Join(Environment.NewLine, new[]
                {
                    "# ═══════════════════════════════════════════════════════════════════════════",
                    "# ĐỘI HÌNH — DÙNG CHUNG CHO MỌI HOẠT ĐỘNG (Cấm thuật, Sơn cáp, Auto NV, Địa cung...)",
                    "#",
                    "# Cú pháp:",
                    "#   [team:tenTeam]  mở một khối đội hình",
                    "#   truong = nick   khai đội trưởng (hoặc nick đầu tiên trong khối)",
                    "#   nick_02         các thành viên trong nhóm",
                    "#   \"#\"             ghi chú, tính tới hết dòng",
                    "#",
                    "# Đội hình này được dùng chung cho tất cả các module:",
                    "#   - Cấm thuật: /ct hoặc nút Cấm thuật trên tool",
                    "#   - Sơn cáp:   /sc hoặc nút Sơn cáp trên tool",
                    "#   - Auto NV / Địa cung theo team: /nv team1, /dc team1, /kill team1, /wake team1",
                    "# Quản lý trực tiếp qua Telegram:",
                    "#   /team team1 add nick1 nick2",
                    "#   /team team1 del nick1",
                    "#   /team list",
                    "# ═══════════════════════════════════════════════════════════════════════════",
                    "",
                    "[chung]",
                    "max_client      = 12",
                    "login_cho_giay = 120",
                    "login_thu_lai  = 2",
                    "",
                    "#[team:1]",
                    "#truong = taikhoan01",
                    "#taikhoan02",
                    "#taikhoan03",
                    "#taikhoan04",
                    "",
                    "#[agt]",
                    "#mo_cua =",
                    "",
                    "#[gom]",
                    "#nhan_do = taikhoan01",
                }), new UTF8Encoding(false));
                Log($"📄 Chưa có doi_hinh.cfg — đã tạo file mẫu tại {DoiHinhFilePath}");
            }
            catch (Exception ex) { Log($"❌ Không tạo được doi_hinh.cfg: {ex.Message}"); }
        }

        /// <summary>Bảng team: tên team → danh sách nick (đội trưởng luôn đứng đầu), đúng thứ tự khai trong file.</summary>
        private Dictionary<string, List<string>> LoadTeams()
        {
            var kq = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
            foreach (var k in DocDoiHinh())
            {
                if (k.Loai != "team") continue;
                string ten = k.Ten.Length > 0 ? k.Ten : "?";
                if (!kq.TryGetValue(ten, out var ds)) { ds = new List<string>(); kq[ten] = ds; }

                // Đội trưởng khai qua truong= / lead= luôn đứng đầu danh sách
                if (!string.IsNullOrEmpty(k.Vai) && !ds.Any(x => string.Equals(x, k.Vai, StringComparison.OrdinalIgnoreCase)))
                {
                    ds.Add(k.Vai);
                }

                foreach (var n in k.Nicks)
                    if (!ds.Any(x => string.Equals(x, n, StringComparison.OrdinalIgnoreCase)))
                        ds.Add(n);
            }
            return kq;
        }

        // ══ TRẦN SỐ CLIENT ═════════════════════════════════════════════════
        // Máy chỉ mở nổi ngần này client cùng lúc, và máy chủ cũng thường chặn số client
        // trên một IP. Nút 🚀 Khởi chạy đọc số này để không mở quá tay.
        //
        //     [chung]
        //     max_client = 12

        private class CaiDatChung
        {
            public int MaxClient = 12;
        }

        private static List<string> TachDanhSach(string v)
        {
            var ds = new List<string>();
            if (string.IsNullOrWhiteSpace(v)) return ds;
            foreach (var s in v.Split(','))
            {
                string t = s.Trim();
                if (t.Length > 0) ds.Add(t);
            }
            return ds;
        }

        private CaiDatChung DocCaiDatChung()
        {
            var c = new CaiDatChung();
            foreach (var k in DocDoiHinh())
            {
                if (k.Loai != "chung") continue;
                if (k.Khai.TryGetValue("max_client", out var mc) && int.TryParse(mc.Trim(), out var n) && n > 0)
                    c.MaxClient = n;
            }
            return c;
        }

        private int MaxClient() { return DocCaiDatChung().MaxClient; }

        /// <summary>Số lần LOGIN LẠI khi client kẹt ở màn đăng nhập. 0 = không thử lại.</summary>
        private int SoLanThuLaiLogin() { return DocSoChung("login_thu_lai", 2, 0, 10); }

        /// <summary>Chờ mỗi lần thử bao nhiêu giây trước khi kết luận là kẹt.</summary>
        private int SoGiayChoLogin() { return DocSoChung("login_cho_giay", 120, 20, 600); }

        private int DocSoChung(string khoa, int macDinh, int min, int max)
        {
            foreach (var k in DocDoiHinh())
            {
                if (k.Loai != "chung") continue;
                if (!k.Khai.TryGetValue(khoa, out var v)) continue;
                if (!int.TryParse(v.Trim(), out var n)) continue;
                return (n < min) ? min : (n > max ? max : n);
            }
            return macDinh;
        }

        /// <summary>Số nick đang có phiên kết nối — dùng để canh trần số client.</summary>
        private int SoNickOnline()
        {
            int n = 0;
            foreach (var a in _config.Accounts)
                if (!string.IsNullOrWhiteSpace(a.Username) && IsAccountOnline(a.Username)) n++;
            return n;
        }

        private List<string> NickCuaTeams(IEnumerable<string> tenTeams, Dictionary<string, List<string>> teams)
        {
            var ds = new List<string>();
            foreach (var t in tenTeams)
            {
                if (!teams.TryGetValue(t, out var nicks))
                {
                    Log($"⚠️ doi_hinh.cfg: đợt khởi chạy gọi team '{t}' nhưng không có khối [team:{t}]");
                    continue;
                }
                foreach (var u in nicks)
                    if (!ds.Any(x => string.Equals(x, u, StringComparison.OrdinalIgnoreCase))) ds.Add(u);
            }
            return ds;
        }


        /// <summary>
        /// Đọc đội hình của MỘT loại hoạt động: "camthuat" | "soncap" | "agt" | "gom" | "team".
        /// Với Cấm thuật & Sơn cáp: Tự động dùng chung các khối [team:...] được khai hoặc setup qua Telegram.
        /// </summary>
        private List<GroupSetup> LoadNhom(string loai)
        {
            var result = new List<GroupSetup>();
            var khoi = DocDoiHinh();
            if (khoi.Count == 0) return result;

            Dictionary<string, List<string>> teams = null;   // chỉ dựng khi thật sự có khai "team ="
            int stt = 0;

            // Xác định xem có ưu tiên dùng khối [team:...] cho hoạt động theo nhóm (camthuat, soncap) không
            bool isTeamActivity = string.Equals(loai, "camthuat", StringComparison.OrdinalIgnoreCase)
                               || string.Equals(loai, "soncap", StringComparison.OrdinalIgnoreCase)
                               || string.Equals(loai, "team", StringComparison.OrdinalIgnoreCase);

            var matchingBlocks = new List<DoiHinhKhoi>();
            if (isTeamActivity)
            {
                var teamBlocks = khoi.Where(k => string.Equals(k.Loai, "team", StringComparison.OrdinalIgnoreCase)).ToList();
                if (teamBlocks.Count > 0)
                {
                    matchingBlocks = teamBlocks;
                }
                else
                {
                    matchingBlocks = khoi.Where(k => string.Equals(k.Loai, loai, StringComparison.OrdinalIgnoreCase)).ToList();
                }
            }
            else
            {
                matchingBlocks = khoi.Where(k => string.Equals(k.Loai, loai, StringComparison.OrdinalIgnoreCase)).ToList();
            }

            foreach (var k in matchingBlocks)
            {
                stt++;

                var tatCa = new List<string>();
                if (!string.IsNullOrEmpty(k.Vai) && !tatCa.Any(x => string.Equals(x, k.Vai, StringComparison.OrdinalIgnoreCase)))
                {
                    tatCa.Add(k.Vai);
                }

                if (k.Teams.Count > 0)
                {
                    teams = teams ?? LoadTeams();
                    foreach (var t in k.Teams)
                    {
                        if (teams.TryGetValue(t, out var ds))
                        {
                            foreach (var u in ds)
                                if (!tatCa.Any(x => string.Equals(x, u, StringComparison.OrdinalIgnoreCase)))
                                    tatCa.Add(u);
                        }
                        else Log($"⚠️ doi_hinh.cfg: khối [{k.Nhan}] gọi team '{t}' nhưng không có khối [team:{t}]");
                    }
                }
                foreach (var n in k.Nicks)
                {
                    if (!tatCa.Any(x => string.Equals(x, n, StringComparison.OrdinalIgnoreCase)))
                        tatCa.Add(n);
                }

                var g = new GroupSetup
                {
                    Name = k.Ten.Length > 0 ? k.Ten : (stt == 1 ? k.Loai.ToUpperInvariant() : k.Loai.ToUpperInvariant() + "-" + stt),
                    Leader = k.Vai
                };
                if (string.IsNullOrEmpty(g.Leader) && tatCa.Count > 0)
                {
                    g.Leader = tatCa[0];
                }

                foreach (var m in tatCa)
                {
                    if (string.Equals(m, g.Leader, StringComparison.OrdinalIgnoreCase)) continue;
                    if (g.Members.Any(x => string.Equals(x, m, StringComparison.OrdinalIgnoreCase))) continue;
                    g.Members.Add(m);
                }
                if (!string.IsNullOrEmpty(g.Leader)) result.Add(g);
            }

            // Một nick chỉ được thuộc ĐÚNG MỘT nhóm trong cùng hoạt động.
            var daGap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var g in result)
            {
                var moiNguoi = new List<string> { g.Leader };
                moiNguoi.AddRange(g.Members);
                foreach (var u in moiNguoi)
                {
                    if (daGap.TryGetValue(u, out var nhomTruoc))
                        Log($"⚠️ doi_hinh.cfg: nick '{u}' khai ở CẢ HAI nhóm '{nhomTruoc}' và '{g.Name}' " +
                            $"— chỉ nhóm '{nhomTruoc}' dùng được, nhóm '{g.Name}' sẽ treo");
                    else
                        daGap[u] = g.Name;
                }
            }
            return result;
        }

        /// <summary>Tên nhân vật đang dùng của một tài khoản; rỗng nếu nick chưa vào game.</summary>
        public string GetCharName(string username)
        {
            lock (_accountLevels)
            {
                return _charNames.TryGetValue(username, out var n) ? (n ?? "") : "";
            }
        }

        private void BtnCamThuat_Click(object sender, EventArgs e)
        {
            // Bấm hoạt động khác là máy Sơn cáp bị dừng bên mod (stopCurrentActivity),
            // nên nút phải thôi hiện "đang chạy" — chữ trên nút phải nói đúng trạng thái thật.
            if (_sonCapOn) SetSonCapButton(false);
            var setups = LoadNhom("camthuat");
            if (setups.Count == 0)
            {
                MessageBox.Show("doi_hinh.cfg chưa khai nhóm cấm thuật nào.\n\nMở file:\n" + DoiHinhFilePath +
                                "\n\nMỗi nhóm một khối:\n\n" +
                                "    [camthuat:CT-1]\n" +
                                "    truong = <username trưởng nhóm>\n" +
                                "    <username thành viên>\n" +
                                "    <username thành viên>");
                return;
            }

            // Gỡ tuyến bám theo của phiên TRƯỚC trước khi xoá bảng nhóm — xoá _ctPlanned rồi thì
            // không còn tra ngược ra trưởng nhóm nào để mà tắt, tuyến sẽ sống mồ côi.
            foreach (var name in _ctInDungeon.Keys.ToList())
                if (_ctPlanned.TryGetValue(name, out var old)) StopFollowGroup(old.Leader);
            _ctInDungeon.Clear();

            _ctActive.Clear();
            _ctLastRelay.Clear();
            _ctLastRoster.Clear();
            _ctPlanned.Clear();
            _ctRosters.Clear();
            _ctDoneLeaders.Clear();
            _ctPos.Clear();
            _ctAtNpc.Clear();
            _ctXY.Clear();
            _ctOpened.Clear();
            _ctLastWait.Clear();
            int started = 0;

            // Số thứ tự nhóm, phát kèm lệnh giao vai để mỗi trưởng nhóm XUẤT PHÁT Ở MỘT KHU KHÁC
            // NHAU khi phải nhảy khu. Không có nó thì cả ba nhóm cùng bắt đầu ở zone_min rồi +1 —
            // đi chung một cái thang, cách nhau vài giây, khu nào cũng giẫm chân nhau (đo 05/08).
            // Manager là bên duy nhất biết có mấy nhóm, nên số này phải phát từ đây.
            int soNhom = setups.Count;
            int thuTuNhom = -1;

            foreach (var g in setups)
            {
                thuTuNhom++;
                // Giữ danh sách ĐẦY ĐỦ theo file trước mọi bước lọc — nhóm "đủ hay thiếu"
                // phải tính theo file, không phải theo số nick may mắn đang online.
                _ctPlanned[g.Name] = g;

                var leaderSession = FindSession(g.Leader);
                if (leaderSession == null || !IsLoggedIn(g.Leader))
                {
                    Log($"⚔️ Nhóm '{g.Name}': bỏ qua — trưởng nhóm {g.Leader} chưa vào game");
                    continue;
                }

                // Chỉ gom những member đã vào game VÀ đã báo được tên nhân vật.
                // Chưa có tên nhân vật thì trưởng nhóm không có gì để mời, chờ cũng vô ích.
                var ready = new List<string>();
                var memberNames = new List<string>();
                foreach (var m in g.Members)
                {
                    string cn = GetCharName(m);
                    if (FindSession(m) == null || !IsLoggedIn(m) || cn.Length == 0)
                    {
                        Log($"⚔️ Nhóm '{g.Name}': bỏ qua member {m} (chưa vào game)");
                        continue;
                    }
                    ready.Add(m);
                    memberNames.Add(cn);
                }

                string leaderChar = GetCharName(g.Leader);
                if (leaderChar.Length == 0)
                {
                    Log($"⚔️ Nhóm '{g.Name}': chưa đọc được tên nhân vật của trưởng nhóm {g.Leader}");
                    continue;
                }

                var active = new GroupSetup { Name = g.Name, Leader = g.Leader, Members = ready };
                _ctActive[g.Name] = active;

                // expected = sĩ số theo FILE (kể cả nick chưa vào game). Phải gửi tách khỏi
                // members: members đã bị lọc bỏ nick chưa vào game, nếu trưởng nhóm lấy sĩ số
                // từ đó thì nhóm 4 người mà mới 2 nick mở sẽ tưởng đủ rồi khoá luôn.
                leaderSession.SendRawJson(
                    $"{{\"command\":\"cam_thuat_leader\",\"members\":\"{EscapeJson(string.Join(";", memberNames))}\"," +
                    $"\"expected\":{1 + g.Members.Count}," +
                    $"\"zone_slot\":{thuTuNhom},\"zone_slots\":{soNhom}}}\n");

                if (ready.Count < g.Members.Count)
                {
                    Log($"⚔️ Nhóm '{g.Name}': chỉ {1 + ready.Count}/{1 + g.Members.Count} nick đang mở " +
                        $"— nhóm sẽ KHÔNG khoá và sẽ chờ tới hết giờ");
                }

                // slot = thứ tự trong nhóm. Member dùng nó để lệch thời điểm gửi lời xin, khỏi
                // bắn cả chùm CMD 39 vào server cùng lúc. Gán theo thứ tự khai trong doi_hinh.cfg
                // nên chạy lại bao nhiêu lần cũng ra cùng một thứ tự — dễ soi log.
                for (int i = 0; i < ready.Count; i++)
                {
                    FindSession(ready[i])?.SendRawJson(
                        $"{{\"command\":\"cam_thuat_member\",\"leader\":\"{EscapeJson(leaderChar)}\"," +
                        $"\"slot\":{i}}}\n");
                }

                Log($"⚔️ Nhóm '{g.Name}': trưởng {g.Leader} ({leaderChar}) + {ready.Count} member → đang lập nhóm");
                started++;
            }

            if (started == 0)
                Log("⚔️ Cấm thuật — không nhóm nào chạy được (xem log phía trên).");
        }

        // ══════════════════════════════════════════════════════════════
        // BÁM THEO (kiểm thử) — member train cùng chỗ với lead
        // ══════════════════════════════════════════════════════════════
        // Chạy theo NICK ĐANG TÍCH ✔ trên lưới, không theo doi_hinh.cfg: đây là nút để thử,
        // tích 2 nick rồi bấm là xong, khỏi sửa file. Nick tích ĐẦU TIÊN là lead.
        // KHÔNG kéo ai đi đâu — bấm ở đúng chỗ đang đứng.
        // BẢNG ĐỊNH TUYẾN: lead -> member của ĐÚNG nhóm đó.
        //
        // Vì sao phải là bảng chứ không phải một lead: Cấm thuật chạy 3 nhóm, Sơn cáp 2 nhóm,
        // mỗi nhóm một trưởng riêng. Chỉ giữ một lead thì mục tiêu của trưởng nhóm 1 bị gửi
        // sang cả member nhóm 2 và 3 — cả 12 nick xúm vào một con quái của nhóm khác.
        // (AGT thì 12 nick là MỘT khối nên chỉ có một tuyến — vẫn dùng đúng bảng này.)
        //
        // Đọc từ luồng SOCKET nên KHÔNG sửa tại chỗ. Mỗi lần đổi thì dựng bảng MỚI rồi gán đè:
        // gán tham chiếu là thao tác nguyên tử trong .NET nên bên đọc luôn thấy một bảng nhất
        // quán, khỏi cần lock và không bao giờ gặp "collection modified".
        private volatile Dictionary<string, string[]> _flRoutes =
            new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, string> _flLastGap =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        // ══════════════════════════════════════════════════════════════
        // 🧲 THU SỐ LIỆU MAP — soi liên tục suốt một lượt hoạt động thật
        // ══════════════════════════════════════════════════════════════
        // Nút này TRƯỚC ĐÂY là công tắc bám theo thủ công, dùng để kiểm xem "member đánh theo con
        // của lead" có khả thi không. Đã kiểm xong và cơ chế đó nay nằm THẲNG trong Ải gia tộc và
        // Cấm thuật (tự bật khi cả nhóm vào tới nơi), nên không còn lý do bấm tay nữa — giữ lại
        // chỉ tạo ra một đường thứ hai dựng tuyến, mà bấm nhầm lúc hoạt động đang chạy là tắt
        // luôn tuyến của hoạt động.
        //
        // Việc của nó bây giờ: THU SỐ LIỆU. Khác nút 🗺️ ở chỗ 🗺️ soi MỘT PHÁT, còn nút này soi
        // ĐỀU TAY suốt lượt cho tới khi bấm tắt. Cần thế vì thứ đang thiếu là số liệu trong hoạt
        // động THẬT — có boss, có đủ 12 người chơi đứng chung — mà mỗi hoạt động một ngày chỉ
        // chạy được một lượt, ngồi canh bấm đúng khoảnh khắc thì lỡ là mất tới hôm sau.
        //
        // Nhịp soi nằm bên client (`scan_auto_ms` trong quest_anchors.cfg), Manager chỉ bật/tắt.
        private volatile bool _harvestOn = false;

        public void SetHarvestButton(bool on)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => SetHarvestButton(on))); return; }
            _harvestOn = on;
            btnHarvest.Text = on ? "🛑  Ngừng thu số liệu" : "🧲  Thu số liệu map";
            btnHarvest.BackColor = on ? Color.FromArgb(120, 40, 40) : btnDiaCung.BackColor;
        }

        private void BtnHarvest_Click(object sender, EventArgs e)
        {
            if (_harvestOn)
            {
                int off = 0;
                foreach (var u in _harvesting)
                {
                    var ss = FindSession(u);
                    if (ss == null) continue;
                    ss.SendRawJson("{\"command\":\"scan_auto\",\"on\":0}\n");
                    off++;
                }
                _harvesting.Clear();
                SetHarvestButton(false);
                Log($"🧲🛑 Ngừng thu số liệu trên {off} nick. File: {ScanFilePath ?? "(chưa soi lần nào)"}");
                return;
            }

            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Tích ít nhất một nick.\n\n" +
                                "Nút này bảo các nick đó SOI MAP ĐỀU TAY (nhịp scan_auto_ms trong " +
                                "quest_anchors.cfg) và ghi hết vào file soi_map_<ngày_giờ>.log.\n\n" +
                                "Thuần ĐỌC bộ nhớ client — không gửi gói nào lên server, không tốn " +
                                "lượt gì, không kéo ai đi đâu. Bật trước khi vào hoạt động rồi để đó.");
                return;
            }

            _harvesting.Clear();
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"scan_auto\",\"on\":1}\n");
                _harvesting.Add(u);
            }
            SetHarvestButton(true);
            Log($"🧲 Thu số liệu map trên {_harvesting.Count} nick — ghi vào file cạnh doi_hinh.cfg, "
                + "không đổ lên đây để khỏi lấp log hoạt động");
        }

        // Nick nào đang được bảo soi — để lúc tắt báo đúng những nick đó, kể cả khi người dùng đã
        // bỏ tích trên lưới. Bỏ tích mà không tắt được thì client soi mãi tới lúc thoát game.
        private readonly List<string> _harvesting = new List<string>();

        // ĐÃ BỎ `StartFollowGroup(leader, members, allowMove)`.
        // Nó gửi follow_start cho CẢ lead lẫn TOÀN BỘ member mỗi lần gọi — mà bên mod follow_start
        // là lệnh khởi tạo lại phiên (tắt đánh → xoá mục tiêu → chọn lại). Trong khi cả hai chỗ
        // dùng thật (Ải gia tộc, Cấm thuật) đều phải gọi NHIỀU LẦN vì server kéo người vào lệch
        // nhau vài giây ⇒ cứ thêm một người là lead bị giật mất con đang đánh dở.
        // Cả hai chỗ nay tự gửi cho ĐÚNG nick mới rồi cập nhật _flRoutes. Giữ lại hàm này chỉ để
        // sẵn một cái bẫy cho lần gắn Sơn cáp sau này.

        /// <summary>
        /// Đóng ĐÚNG MỘT tuyến. Cần riêng hàm này vì Cấm thuật chạy 3 nhóm song song: nhóm 1 ra
        /// khỏi hầm trước mà gọi StopFollowAll thì nhóm 2 và 3 đang đánh cũng bị tắt theo.
        /// </summary>
        public int StopFollowGroup(string leader)
        {
            if (string.IsNullOrEmpty(leader)) return 0;
            var routes = _flRoutes;
            if (!routes.TryGetValue(leader, out var mem)) return 0;

            var next = new Dictionary<string, string[]>(routes, StringComparer.OrdinalIgnoreCase);
            next.Remove(leader);
            _flRoutes = next;

            int n = 0;
            foreach (var u in new[] { leader }.Concat(mem))
            {
                var ss = FindSession(u);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"follow_stop\"}\n");
                _flLastGap.Remove(u);
                n++;
            }
            return n;
        }

        /// <summary>Đóng mọi tuyến, báo tất cả các nick liên quan dừng.</summary>
        public int StopFollowAll()
        {
            var routes = _flRoutes;
            _flRoutes = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
            _flLastGap.Clear();

            int n = 0;
            foreach (var kv in routes)
            {
                foreach (var u in new[] { kv.Key }.Concat(kv.Value))
                {
                    var ss = FindSession(u);
                    if (ss == null) continue;
                    ss.SendRawJson("{\"command\":\"follow_stop\"}\n");
                    n++;
                }
            }
            return n;
        }

        /// <summary>
        /// THỬ ĐI QUA MAP — tách phép thử chuyển map ra khỏi hoạt động thật.
        ///
        /// Vì sao cần một nút riêng: chuyển map là mắt xích cuối còn lại của cả Sơn cáp lẫn Ải
        /// gia tộc, mà hai hoạt động đó mỗi ngày chỉ chạy được MỘT lượt — sai là mất cả ngày.
        /// Ở map thường thì cơ chế y hệt (Làng Cỏ có sẵn hai lối ra), lại không tốn gì, nên thử
        /// ở đây bao nhiêu lần cũng được.
        ///
        /// Cách nó làm: đọc `z.K` — danh sách tấm biển của map, do game dựng từ bảng nối map có
        /// sẵn trong client — rồi đi bộ tới toạ độ tấm biển, đúng như cú bấm vào biển trong game
        /// (`z.e(x,y)`: đi tới, tắt auto đánh, tắt auto-nav; KHÔNG gửi gói nào).
        /// Không khai map đích thì lấy lối BÊN PHẢI NHẤT — theo quan sát "map tiếp theo thường
        /// nằm bên phải".
        ///
        /// Tool TỰ CHẤM: map đổi ⇒ báo qua được; hết giờ ⇒ báo không, kèm khoảng lệch so với
        /// tấm biển để phân biệt "đi hụt" với "tới nơi mà không kích hoạt được".
        /// </summary>
        private void BtnGoExit_Click(object sender, EventArgs e)
        {
            var targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                MessageBox.Show("Tích ít nhất một nick.\n\n" +
                                "Nút này đọc danh sách LỐI RA của map rồi cho nhân vật đi tới tấm " +
                                "biển chỉ đường — y hệt bấm vào biển trong game.\n\n" +
                                "Không gửi gói nào lên server, không tốn lượt gì. Thử ở Làng Cỏ " +
                                "được ngay: nó có lối sang map 74 (bên phải) và map 81 (bên trái).");
                return;
            }
            int n = 0;
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null) continue;
                // map <= 0 = để mod tự chọn lối bên phải nhất.
                ss.SendRawJson("{\"command\":\"go_exit\",\"map\":-1}\n");
                n++;
            }
            Log($"🚪 Thử đi qua map trên {n} nick — xem các dòng 🚪 bên dưới");
        }

        /// <summary>
        /// Lead báo vị trí → chuyển thẳng cho member. Member báo vị trí → ghi KHOẢNG CÁCH,
        /// vì đó chính là kết quả của phép thử: bám sát tới mức nào.
        /// </summary>
        public void RelayFollowPos(string username, int mapId, int zoneId, int x, int y,
                                   string role, string detail, int tx = -1, int ty = -1, int tid = -1)
        {
            // CỔNG CHẶN LÀ BẢNG TUYẾN, KHÔNG PHẢI TRẠNG THÁI NÚT.
            // Trước đây chỗ này là `if (!_flOn) return;` — mà _flOn chỉ được bật bởi nút 🧲 (hồi
            // nút đó còn là công tắc bám theo thủ công). Nghĩa là tuyến do HOẠT ĐỘNG dựng (Ải gia
            // tộc, và giờ thêm Cấm thuật) bị chặn ngay ở đây: bảng có tuyến, lead vẫn báo mục
            // tiêu đều, nhưng không một gói nào được chuyển tiếp — bám theo trong hoạt động im
            // lặng không chạy mà chẳng có dòng log nào nói vì sao.
            // Bảng rỗng đã là "không có gì đang chạy" rồi, đó mới đúng là cổng chặn.
            var routes = _flRoutes;
            if (routes.Count == 0) return;

            // ĐƯỜNG NÓNG — chạy thẳng trên luồng socket, KHÔNG BeginInvoke.
            // Chuyển tiếp vị trí chỉ đụng tới socket, không đụng giao diện. Đẩy qua luồng
            // giao diện thì mỗi lần chuyển tiếp phải xếp hàng sau việc vẽ và việc ghi log —
            // với 12 nick thì đó là độ trễ thật, mà lại đúng vào mắt xích cần nhanh nhất.
            // Tra bảng theo tên lead: chỉ chuyển tiếp cho member CỦA CHÍNH nhóm đó.
            if (role == "lead" && routes.TryGetValue(username, out var memArr))
            {
                // tx/ty = toạ độ con quái lead đang đánh (chế độ bám mục tiêu). Gửi kèm luôn,
                // member dùng hay không là do follow_mode bên client quyết.
                string msg = "{\"command\":\"follow_goto\",\"map\":" + mapId + ",\"zone\":" + zoneId +
                             ",\"x\":" + x + ",\"y\":" + y +
                             ",\"tx\":" + tx + ",\"ty\":" + ty + ",\"tid\":" + tid + "}\n";
                foreach (var m in memArr)
                    FindSession(m)?.SendRawJson(msg);
                return;
            }

            // Đường lạnh: chỉ để ghi log, phải về luồng giao diện.
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayFollowPos(username, mapId, zoneId, x, y, role, detail, tx, ty, tid)));
                return;
            }

            // Chỉ ghi khi NỘI DUNG đổi — nhịp báo 1.5s/nick, không lọc thì log ngập.
            if (!_flLastGap.TryGetValue(username, out var prev) || prev != detail)
            {
                _flLastGap[username] = detail;
                Log($"🧲 [{username}] {detail} · đang ở {mapId}/{zoneId} ({x},{y})");
            }
        }

        // ══════════════════════════════════════════════════════════════
        // ẢI GIA TỘC (AGT) — một nick mở cửa ải, phần còn lại vào
        // ══════════════════════════════════════════════════════════════
        // KHÔNG lập nhóm: hoạt động chạy theo gia tộc, mỗi nick tự bấm menu của mình.
        // Manager chỉ làm đúng một việc điều phối: nghe nick MỞ báo xong rồi phát tín hiệu
        // cho phần còn lại — vì mục "Vào ải gia tộc" chỉ có tác dụng sau khi cửa ải đã mở.
        private readonly List<string> _agtActiveUsers = new List<string>();
        private bool _agtOn = false;

        public void SetAgtButton(bool on)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => SetAgtButton(on))); return; }
            _agtOn = on;
            btnAgt.Text = on ? "🛑  Tắt Ải gia tộc" : "🏰  Ải gia tộc";
            btnAgt.BackColor = on ? Color.FromArgb(120, 40, 40) : btnDiaCung.BackColor;
        }

        private void BtnAgt_Click(object sender, EventArgs e)
        {
            if (_agtOn)
            {
                int stopped = 0;
                var targets = _agtActiveUsers.Count > 0 ? _agtActiveUsers : GetCheckedUsernames();
                foreach (var u in targets)
                {
                    var ss = FindSession(u);
                    if (ss == null) continue;
                    ss.SendRawJson("{\"command\":\"agt_stop\"}\n");
                    stopped++;
                }
                _agtActiveUsers.Clear();
                AgtFollowStop();          // tắt luôn bám theo, không để tuyến sống sót mồ côi
                SetAgtButton(false);
                Log($"🏰🛑 Đã tắt Ải gia tộc — báo {stopped} nick dừng.");
                return;
            }

            var checkedTargets = GetCheckedUsernames();
            if (checkedTargets.Count == 0)
            {
                MessageBox.Show("Tích ít nhất một nick trong danh sách để chạy Ải gia tộc.", "Ải gia tộc");
                return;
            }

            _agtActiveUsers.Clear();
            int n = 0;
            foreach (var u in checkedTargets)
            {
                var ss = FindSession(u);
                if (ss == null || !IsLoggedIn(u)) continue;
                ss.SendRawJson("{\"command\":\"agt_start\"}\n");
                _agtActiveUsers.Add(u);
                n++;
            }

            if (n == 0)
            {
                Log("🏰 Các nick được tích chưa vào game — không thể bắt đầu Ải gia tộc.");
                return;
            }

            SetAgtButton(true);
            Log($"🏰 Ải gia tộc — đã gửi lệnh bắt đầu cho {n} nick được tích → đang tự chạy về làng tìm NPC Onoki.");
        }

        // ── BÁM THEO trong ải: dồn hoả lực vào một con thay vì mỗi nick một góc ──
        // Danh sách các nick ĐÃ BÁO vào cổng hiện tại, theo thứ tự tới. Nick đầu làm lead.
        private readonly List<string> _agtInGate = new List<string>();
        private int _agtFollowMap = -1;   // tuyến hiện tại đang phục vụ cổng nào

        /// <summary>
        /// Một nick báo đã vào map cổng → nối nó vào tuyến bám theo của ĐÚNG cổng đó.
        ///
        /// Chỉ nối nick ĐÃ Ở TRONG cổng, không nối sẵn cả danh sách: nick chưa vào mà đã bám
        /// theo thì máy bám sẽ thấy khác map và phát lệnh đi xuyên map — giành tay lái với
        /// chính lệnh chuyển cổng của AGT. Đổi cổng (46→47) thì dựng lại tuyến từ đầu, cũng
        /// vì lý do đó.
        /// </summary>
        public void NotifyAgtInGate(string username, int mapId)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifyAgtInGate(username, mapId))); return; }
            if (!_agtOn) return;

            if (mapId != _agtFollowMap)
            {
                if (_agtFollowMap >= 0) StopFollowAll();
                _agtInGate.Clear();
                _agtFollowMap = mapId;
            }
            if (_agtInGate.Any(u => string.Equals(u, username, StringComparison.OrdinalIgnoreCase)))
                return;

            _agtInGate.Add(username);
            if (_agtInGate.Count == 1)
            {
                // move:0 — nói THẲNG ở chỗ gọi thay vì để client tự đọc config.
                // Trong ải, AGT là bên lái nhân vật (đi tới NPC, chuyển cổng). Bám theo mà cũng
                // được phát lệnh đi thì nó tắt đánh để đuổi lead, vài giây sau AGT thấy đánh bị
                // tắt lại bật lên — hai bên giằng nhau. Trước đây dòng này không có trường move
                // nên hành vi phụ thuộc `follow_move` trong cfg: sửa một con số trong file là
                // đổi luôn cách AGT chạy, mà chẳng có gì trong file nói ra mối liên hệ đó.
                FindSession(username)?.SendRawJson(
                    "{\"command\":\"follow_start\",\"role\":1,\"move\":0,\"owner\":\"agt\"}\n");
                _flRoutes = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase)
                    { { username, new string[0] } };
                Log($"🧲 Ải map {mapId}: {username} làm lead hoả lực");
                return;
            }

            string lead = _agtInGate[0];
            FindSession(username)?.SendRawJson(
                "{\"command\":\"follow_start\",\"role\":2,\"move\":0,\"owner\":\"agt\",\"leader\":\"" +
                EscapeJson(GetCharName(lead)) + "\"}\n");
            var next = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase)
                { { lead, _agtInGate.Skip(1).ToArray() } };
            _flRoutes = next;
            Log($"🧲 Ải map {mapId}: {_agtInGate.Count - 1} nick dồn hoả lực theo {lead}");
        }

        /// <summary>Kết thúc ải → tắt bám theo, xoá tuyến.</summary>
        public void AgtFollowStop()
        {
            if (InvokeRequired) { BeginInvoke(new Action(AgtFollowStop)); return; }
            if (_agtFollowMap < 0 && _agtInGate.Count == 0) return;
            StopFollowAll();
            _agtInGate.Clear();
            _agtFollowMap = -1;
        }

        public void NotifyAgtOpened(string leaderUsername)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifyAgtOpened(leaderUsername))); return; }
            Log($"🏰📣 [{leaderUsername}] đã mở cửa ải gia tộc");
        }

        // ══════════════════════════════════════════════════════════════
        // SƠN CÁP — gom 2 nhóm 6 người rồi tập kết về một toạ độ
        // ══════════════════════════════════════════════════════════════
        // Bộ trạng thái RIÊNG với Cấm thuật (_ct*): hai hoạt động chạy độc lập, dùng chung
        // biến là bấm cái này xoá mất tiến độ cái kia.
        private readonly Dictionary<string, GroupSetup> _scPlanned =
            new Dictionary<string, GroupSetup>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, GroupSetup> _scActive =
            new Dictionary<string, GroupSetup>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, Dictionary<string, string>> _scPos =
            new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, Dictionary<string, bool>> _scAtPoint =
            new Dictionary<string, Dictionary<string, bool>>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, string> _scLastRelay =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, string> _scLastWait =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _scDone =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// Nút Sơn cáp: đọc các khối [soncap:...] trong doi_hinh.cfg rồi giao vai cho từng nick.
        /// Gom nhóm → tập kết ở NPC Fukasaku → trưởng nhóm bấm "Sơn Cáp Myoboku" → cả nhóm vào
        /// → 5 tầng (dọn quái → boss → cửa mở) → game đẩy về làng.
        /// ⚠️ CÚ BẤM CỦA TRƯỞNG NHÓM TỐN LƯỢT — mỗi ngày chỉ một lượt. `son_cap_dry_run,1` dừng
        /// ngay trước cú bấm để kiểm phần gom nhóm mà không đốt lượt.
        /// </summary>
        /// <summary>
        /// Đặt lại giao diện nút Sơn cáp. Gọi cả từ nút hoạt động KHÁC: bấm Cấm thuật / Địa cung
        /// là máy sơn cáp bị dừng (stopCurrentActivity bên mod), nên nút không được phép còn hiện
        /// "đang chạy" — chữ trên nút phải nói đúng trạng thái thật, không thì nó lại là một dòng
        /// giao diện nói dối như mấy dòng log đã phải sửa.
        /// </summary>
        public void SetSonCapButton(bool on)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => SetSonCapButton(on))); return; }
            _sonCapOn = on;
            btnSonCap.Text = on ? "🛑  Tắt Sơn cáp" : "🪢  Sơn cáp";
            btnSonCap.BackColor = on ? Color.FromArgb(120, 40, 40) : btnDiaCung.BackColor;
        }

        // ── DỒN HOẢ LỰC trong sơn cáp: y hệt Cấm thuật, chỉ khác tên nhóm và chủ phiên ──
        // Sơn cáp có nhóm sẵn trong doi_hinh.cfg nên lead hoả lực là trưởng nhóm, không phải
        // "ai vào trước" như ải gia tộc.
        private readonly Dictionary<string, List<string>> _scInFloor =
            new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        // Map của tầng mà danh sách trên đang gom. ĐÂY MỚI LÀ MỐC "SANG TẦNG MỚI".
        // Xem chú thích dài ở đầu NotifySonCapInFloor.
        private readonly Dictionary<string, int> _scFloorMap =
            new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        private GroupSetup FindSonCapGroupOf(string username)
        {
            return _scPlanned.Values.FirstOrDefault(g =>
                string.Equals(g.Leader, username, StringComparison.OrdinalIgnoreCase)
                || g.Members.Any(m => string.Equals(m, username, StringComparison.OrdinalIgnoreCase)));
        }

        /// <summary>
        /// Một nick báo đã vào một tầng sơn cáp → nối vào tuyến dồn hoả lực của nhóm đó.
        ///
        /// MỐC "SANG TẦNG MỚI" LÀ MAP ĐỔI Ở ĐÂY, KHÔNG PHẢI DÒNG "RỜI TẦNG".
        /// Đây là chỗ đã sai và làm mất dồn hoả lực ngay sau khi qua cửa: mỗi nick tự đi qua
        /// cửa nên mười hai dòng rời/vào ĐAN XEN nhau, mà bản trước lấy dòng RỜI làm mốc gom
        /// lại cả cụm. Diễn biến thật: M1 qua cửa → xoá sạch danh sách → M1 báo vào → danh
        /// sách = [M1] → M2 qua cửa → XOÁ SẠCH LẦN NỮA, mất luôn M1 → ... → trưởng nhóm vào
        /// cuối cùng thì danh sách chỉ còn đúng mình nó, thành lead của một tuyến RỖNG, còn
        /// mọi member đã nhận follow_stop và không bao giờ được gọi lại.
        /// Lấy map làm mốc thì chỉ nick ĐẦU TIÊN của map mới gom lại, những nick sau CỘNG DỒN
        /// vào — thứ tự qua cửa không còn ảnh hưởng gì.
        /// (Ải gia tộc vốn đã làm đúng kiểu này từ đầu; chỗ này chỉ là chép lại cho khớp.)
        /// </summary>
        public void NotifySonCapInFloor(string username, int mapId)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifySonCapInFloor(username, mapId))); return; }
            var g = FindSonCapGroupOf(username);
            if (g == null) return;

            if (_scFloorMap.TryGetValue(g.Name, out int mapCu) && mapCu != mapId)
            {
                _scInFloor.Remove(g.Name);
                Log($"🧲 Nhóm '{g.Name}': sang tầng mới (map {mapCu} → {mapId}) → gom lại tuyến dồn hoả lực");
            }
            _scFloorMap[g.Name] = mapId;

            if (!_scInFloor.TryGetValue(g.Name, out var inside))
            {
                inside = new List<string>();
                _scInFloor[g.Name] = inside;
            }
            if (inside.Any(u => string.Equals(u, username, StringComparison.OrdinalIgnoreCase))) return;
            inside.Add(username);

            if (!inside.Any(u => string.Equals(u, g.Leader, StringComparison.OrdinalIgnoreCase)))
            {
                Log($"🧲 Nhóm '{g.Name}': {username} vào tầng (map {mapId}), chờ trưởng nhóm vào");
                return;
            }

            // Chỉ gửi cho nick CHƯA được gửi — follow_start là lệnh khởi tạo lại phiên, gửi lại
            // cho lead giữa trận là nó mất con đang đánh dở.
            string mv = "{\"command\":\"follow_start\",\"role\":";
            var routes = _flRoutes;
            var daGui = routes.TryGetValue(g.Leader, out var cu)
                ? new HashSet<string>(cu, StringComparer.OrdinalIgnoreCase)
                : null;
            if (daGui == null)
            {
                daGui = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                FindSession(g.Leader)?.SendRawJson(mv + "1,\"move\":0,\"owner\":\"son_cap\"}\n");
                Log($"🧲 Nhóm '{g.Name}': {g.Leader} làm lead hoả lực (map {mapId})");
            }

            string leaderChar = GetCharName(g.Leader);
            var mem = inside.Where(u => !string.Equals(u, g.Leader, StringComparison.OrdinalIgnoreCase)).ToList();
            int moi = 0;
            foreach (var m in mem)
            {
                if (daGui.Contains(m)) continue;
                var ss = FindSession(m);
                if (ss == null) continue;
                ss.SendRawJson(mv + "2,\"move\":0,\"owner\":\"son_cap\",\"leader\":\""
                               + EscapeJson(leaderChar) + "\"}\n");
                moi++;
            }

            var next = new Dictionary<string, string[]>(routes, StringComparer.OrdinalIgnoreCase);
            next[g.Leader] = mem.ToArray();
            _flRoutes = next;
            if (moi > 0)
                Log($"🧲 Nhóm '{g.Name}': +{moi} nick dồn hoả lực theo {g.Leader}"
                    + $" (tổng {mem.Count}, map {mapId})");
        }

        /// <summary>
        /// Một nick rời tầng — BỎ ĐÚNG NICK ĐÓ, không gỡ cả cụm.
        ///
        /// Mod gửi dòng này ở ba chỗ mang hai nghĩa khác nhau: "qua cửa sang tầng sau" và
        /// "đã ra khỏi sơn cáp". Manager phân biệt bằng SỐ NGƯỜI CÒN LẠI thay vì đọc chữ
        /// trong `detail` — qua cửa thì nick vừa rời sẽ tự báo vào ở map mới nên trong tầng
        /// vẫn còn người; còn hết hoạt động thì cả nhóm rời mà không ai báo vào, danh sách
        /// cạn tới rỗng. Rỗng mới là lúc gỡ tuyến.
        /// </summary>
        public void NotifySonCapOutFloor(string username)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifySonCapOutFloor(username))); return; }
            var g = FindSonCapGroupOf(username);
            if (g == null) return;
            if (!_scInFloor.TryGetValue(g.Name, out var inside)) return;

            inside.RemoveAll(u => string.Equals(u, username, StringComparison.OrdinalIgnoreCase));
            if (inside.Count > 0) return;   // còn người trong tầng ⇒ chỉ là qua cửa

            _scInFloor.Remove(g.Name);
            _scFloorMap.Remove(g.Name);
            int n = StopFollowGroup(g.Leader);
            if (n > 0) Log($"🧲🛑 Nhóm '{g.Name}': cả nhóm đã ra khỏi tầng → tắt dồn hoả lực ({n} nick)");
        }

        /// <summary>Bấm lần hai: bảo MỌI nick trong danh sách sơn cáp giải tán nhóm và tắt máy.</summary>
        private void StopSonCapAll()
        {
            foreach (var name in _scInFloor.Keys.ToList())
                if (_scPlanned.TryGetValue(name, out var old)) StopFollowGroup(old.Leader);
            _scInFloor.Clear();
            _scFloorMap.Clear();

            int n = 0;
            foreach (var g in _scPlanned.Values)
            {
                var all = new List<string> { g.Leader };
                all.AddRange(g.Members);
                foreach (var u in all)
                {
                    var ss = FindSession(u);
                    if (ss == null) continue;
                    ss.SendRawJson("{\"command\":\"son_cap_stop\"}\n");
                    n++;
                }
            }
            _scPlanned.Clear(); _scActive.Clear(); _scPos.Clear(); _scAtPoint.Clear();
            _scLastRelay.Clear(); _scLastWait.Clear(); _scDone.Clear();
            SetSonCapButton(false);
            Log($"🪢🛑 Đã tắt Sơn cáp — báo {n} nick giải tán nhóm và dừng hẳn.");
        }

        private void BtnSonCap_Click(object sender, EventArgs e)
        {
            if (_sonCapOn) { StopSonCapAll(); return; }

            var setups = LoadNhom("soncap");
            if (setups.Count == 0)
            {
                MessageBox.Show("doi_hinh.cfg chưa khai nhóm sơn cáp nào.\n\nMở file:\n" + DoiHinhFilePath +
                                "\n\nMỗi nhóm một khối:\n\n" +
                                "    [soncap:SC-1]\n" +
                                "    truong = <username trưởng nhóm>\n" +
                                "    <username thành viên>\n" +
                                "    <username thành viên>");
                return;
            }

            // Gỡ tuyến của phiên trước TRƯỚC khi xoá bảng nhóm — xoá rồi thì không tra ngược ra
            // trưởng nhóm nào để tắt, tuyến sẽ sống mồ côi.
            foreach (var name in _scInFloor.Keys.ToList())
                if (_scPlanned.TryGetValue(name, out var old)) StopFollowGroup(old.Leader);
            _scInFloor.Clear();
            _scFloorMap.Clear();

            _scPlanned.Clear(); _scActive.Clear(); _scPos.Clear(); _scAtPoint.Clear();
            _scLastRelay.Clear(); _scLastWait.Clear(); _scDone.Clear();

            // Cảnh báo nick khai ở hai nhóm — một nick không ở hai nhóm cùng lúc được, và lỗi này
            // chỉ lộ ra khi cả hai nhóm cùng chờ mãi không đủ quân.
            var seen = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var g in setups)
            {
                var all = new List<string> { g.Leader };
                all.AddRange(g.Members);
                foreach (var u in all)
                {
                    if (seen.TryGetValue(u, out var other))
                        Log($"🪢 ⚠️ {u} khai ở CẢ '{other}' và '{g.Name}' — sửa doi_hinh.cfg");
                    else seen[u] = g.Name;
                }
            }

            int started = 0;
            // Cùng lý do với Cấm thuật: hai nhóm sơn cáp phải xuất phát ở hai khu khác nhau.
            int soNhom = setups.Count;
            int thuTuNhom = -1;
            foreach (var g in setups)
            {
                thuTuNhom++;
                _scPlanned[g.Name] = g;

                var leaderSession = FindSession(g.Leader);
                if (leaderSession == null || !IsLoggedIn(g.Leader))
                {
                    Log($"🪢 Nhóm '{g.Name}': bỏ qua — trưởng nhóm {g.Leader} chưa vào game");
                    continue;
                }

                var ready = new List<string>();
                var memberNames = new List<string>();
                foreach (var m in g.Members)
                {
                    string cn = GetCharName(m);
                    if (FindSession(m) == null || !IsLoggedIn(m) || cn.Length == 0)
                    {
                        Log($"🪢 Nhóm '{g.Name}': bỏ qua member {m} (chưa vào game)");
                        continue;
                    }
                    ready.Add(m);
                    memberNames.Add(cn);
                }

                string leaderChar = GetCharName(g.Leader);
                if (leaderChar.Length == 0)
                {
                    Log($"🪢 Nhóm '{g.Name}': chưa đọc được tên nhân vật của trưởng nhóm {g.Leader}");
                    continue;
                }

                _scActive[g.Name] = new GroupSetup { Name = g.Name, Leader = g.Leader, Members = ready };

                // expected lấy theo FILE, không theo số nick đang mở — nếu không thì nhóm 6 người
                // mà mới 3 nick mở đã tưởng đủ rồi khoá luôn, chặn mất 3 người còn lại.
                leaderSession.SendRawJson(
                    "{\"command\":\"son_cap_leader\",\"members\":\"" + EscapeJson(string.Join(";", memberNames)) + "\"," +
                    "\"expected\":" + (1 + g.Members.Count) +
                    ",\"zone_slot\":" + thuTuNhom + ",\"zone_slots\":" + soNhom + "}\n");

                if (ready.Count < g.Members.Count)
                    Log($"🪢 Nhóm '{g.Name}': chỉ {1 + ready.Count}/{1 + g.Members.Count} nick đang mở — nhóm sẽ KHÔNG khoá");

                // slot = thứ tự khai trong file, để member lệch nhau lúc gửi CMD 39.
                for (int i = 0; i < ready.Count; i++)
                {
                    FindSession(ready[i])?.SendRawJson(
                        "{\"command\":\"son_cap_member\",\"leader\":\"" + EscapeJson(leaderChar) + "\",\"slot\":" + i + "}\n");
                }

                Log($"🪢 Nhóm '{g.Name}': trưởng {g.Leader} ({leaderChar}) + {ready.Count} member → đang lập nhóm");
                started++;
            }

            if (started == 0) { Log("🪢 Sơn cáp — không nhóm nào chạy được (xem log phía trên)."); return; }
            SetSonCapButton(true);
        }

        /// <summary>Trưởng nhóm sơn cáp báo map/khu/toạ độ nó đang đứng → phát cho member nhóm đó.</summary>
        public void RelaySonCapZone(string leaderUsername, int mapId, int zoneId, string leaderChar, int x, int y)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelaySonCapZone(leaderUsername, mapId, zoneId, leaderChar, x, y)));
                return;
            }
            var g = _scActive.Values.FirstOrDefault(a =>
                string.Equals(a.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (g == null) return;
            if (string.IsNullOrWhiteSpace(leaderChar)) leaderChar = GetCharName(leaderUsername);

            // Gửi kèm TOẠ ĐỘ THẬT của trưởng nhóm, không chỉ map/khu. Để member bám thẳng vào chỗ
            // trưởng nhóm đang đứng thay vì mỗi nick tự tra config của chính nó rồi hy vọng trùng.
            foreach (var m in g.Members)
                FindSession(m)?.SendRawJson(
                    "{\"command\":\"son_cap_goto\",\"map\":" + mapId + ",\"zone\":" + zoneId +
                    ",\"x\":" + x + ",\"y\":" + y + ",\"leader\":\"" + EscapeJson(leaderChar) + "\"}\n");

            string stamp = mapId + "/" + zoneId + "/(" + x + "," + y + ")";
            if (!_scLastRelay.TryGetValue(g.Name, out var prev) || prev != stamp)
            {
                _scLastRelay[g.Name] = stamp;
                Log($"🪢 Nhóm '{g.Name}': điểm tập kết map {mapId} khu {zoneId} ({x},{y}) → đã báo {g.Members.Count} member");
            }
        }

        /// <summary>
        /// Mỗi nick báo vị trí; đủ quân + cùng map/khu + tất cả đứng đúng điểm thì mới cho bấm.
        ///
        /// ĐÂY LÀ CỔNG CHẶN THẬT, không phải báo cáo. Sơn cáp CHỈ MỘT LƯỢT MỖI NGÀY và cú bấm
        /// của trưởng nhóm kéo cả nhóm vào — thiếu người lúc bấm là người đó mất lượt của ngày.
        /// (Chú thích cũ ở đây ghi "không có cú bấm nào tốn lượt" — sai, đã sửa.)
        /// </summary>
        public void UpdateSonCapPos(string username, int mapId, int zoneId, bool atPoint, int x, int y)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => UpdateSonCapPos(username, mapId, zoneId, atPoint, x, y)));
                return;
            }
            var planned = _scPlanned.Values.FirstOrDefault(p =>
                string.Equals(p.Leader, username, StringComparison.OrdinalIgnoreCase) ||
                p.Members.Any(m => string.Equals(m, username, StringComparison.OrdinalIgnoreCase)));
            if (planned == null) return;
            if (_scDone.Contains(planned.Name)) return;

            if (!_scPos.TryGetValue(planned.Name, out var pos))
            { pos = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase); _scPos[planned.Name] = pos; }
            pos[username] = mapId + "/" + zoneId;
            if (!_scAtPoint.TryGetValue(planned.Name, out var atMap))
            { atMap = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase); _scAtPoint[planned.Name] = atMap; }
            atMap[username] = atPoint;

            var everyone = new List<string> { planned.Leader };
            everyone.AddRange(planned.Members);

            // MỌI cửa dừng đều có dòng riêng. Dòng chờ chỉ in khi nội dung đổi nên không ngập,
            // nhưng cũng không có cửa nào im lặng để dòng cũ đứng lại đánh lừa người đọc —
            // đúng cái bẫy đã mất công truy bên Cấm thuật.
            var chuaBao = everyone.Where(u => !pos.ContainsKey(u)).ToList();
            if (chuaBao.Count > 0)
            {
                LogSonCapKhiDoi(planned.Name,
                    $"🪢⏳ Nhóm '{planned.Name}': chưa nhận vị trí của {string.Join(", ", chuaBao)}");
                return;
            }
            string at = pos[planned.Leader];
            var lech = everyone.Where(u => pos[u] != at).ToList();
            if (lech.Count > 0)
            {
                LogSonCapKhiDoi(planned.Name,
                    $"🪢⏳ Nhóm '{planned.Name}': chưa cùng chỗ — trưởng ở {at}, lệch: " +
                    string.Join(", ", lech.Select(u => $"{u} ở {pos[u]}")));
                return;
            }
            var xaDiem = everyone.Where(u => !atMap.TryGetValue(u, out bool ok) || !ok).ToList();
            if (xaDiem.Count > 0)
            {
                LogSonCapKhiDoi(planned.Name,
                    $"🪢⏳ Nhóm '{planned.Name}': chưa tới điểm tập kết — {string.Join(", ", xaDiem)}");
                return;
            }

            _scDone.Add(planned.Name);
            Log($"🪢✅ Nhóm '{planned.Name}': ĐỦ {everyone.Count} người, tất cả ở {at} — TẬP KẾT XONG");

            // ĐỦ QUÂN → BẢO TRƯỞNG NHÓM BẤM NPC. Game kéo CẢ NHÓM vào, đúng khuôn Cấm thuật.
            // Chỉ gửi cho trưởng nhóm: member bấm thêm là mỗi đứa vào một bản map riêng.
            // (Nhóm bị giải tán SAU khi cả đội đã vào — điều đó không làm nhóm mất vai trò đưa
            // người vào, nó chỉ bắt mọi phép kiểm nhóm bên mod phải đứng sau phép soi map.)
            FindSession(planned.Leader)?.SendRawJson("{\"command\":\"son_cap_enter\"}\n");
            Log($"🪢 Nhóm '{planned.Name}': báo trưởng nhóm {planned.Leader} bấm NPC — cả nhóm sẽ được đưa vào");
        }

        /// <summary>
        /// Trưởng nhóm sơn cáp chốt phiên — báo member dừng chờ, DÙ XONG HAY HỎNG.
        /// Chỉ báo khi hỏng là lỗi đã trả giá bên Cấm thuật: đường kết thúc bình thường lại
        /// thành đường member không ai báo, đứng nguyên tại chỗ tới khi hết hạn.
        /// </summary>
        public void NotifySonCapLeaderEnd(string leaderUsername, bool ok)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => NotifySonCapLeaderEnd(leaderUsername, ok)));
                return;
            }
            var planned = _scPlanned.Values.FirstOrDefault(p =>
                string.Equals(p.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (planned == null) return;

            // LƯỚI AN TOÀN CHO TUYẾN DỒN HOẢ LỰC.
            // Đường gỡ bình thường là NotifySonCapOutFloor cạn tới rỗng, nhưng nó cần MỌI nick
            // đều báo rời tầng. Nick nào rớt mạng giữa hoạt động là danh sách không bao giờ cạn
            // và tuyến treo lại tới hết phiên Manager. Trưởng nhóm chốt phiên thì chẳng còn gì
            // để dồn hoả lực nữa — gỡ ở đây là đúng chỗ.
            _scInFloor.Remove(planned.Name);
            _scFloorMap.Remove(planned.Name);
            StopFollowGroup(planned.Leader);

            int n = 0;
            foreach (var m in planned.Members)
            {
                var ss = FindSession(m);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"son_cap_stop\"}\n");
                n++;
            }
            if (n > 0)
                Log($"🪢🛑 Nhóm '{planned.Name}': trưởng nhóm đã chốt phiên ({(ok ? "xong" : "HỎNG")}) → đã báo {n} member dừng chờ");
        }

        // ── LỌC DÒNG TRÙNG: cả nhóm cùng nhìn một map thì cùng báo một câu ───────────────────
        // Sáu nick trong sơn cáp (mười hai trong cấm thuật) đọc cùng một vector quái, nên mỗi lần
        // số quái đổi là ngần ấy dòng y hệt nhau đổ vào log — chôn mất những dòng chỉ xảy ra một
        // lần, mà đó mới là thứ cần đọc.
        //
        // KHÔNG cắt ở nguồn (bắt member im, chỉ trưởng nhóm báo): làm vậy là mất luôn khả năng
        // phát hiện member THẤY KHÁC trưởng nhóm — chuyện sẽ xảy ra nếu có nick rơi vào bản map
        // khác. Lọc ở đây thì câu đầu tiên vẫn hiện, câu KHÁC vẫn hiện, chỉ những bản sao đúng
        // từng chữ trong cùng một khoảng thời gian mới bị nuốt.
        private readonly Dictionary<string, DateTime> _dupLog =
            new Dictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase);

        private void LogNhomMotLan(string icon, string groupName, string user, string detail, int windowSec = 20)
        {
            var now = DateTime.Now;
            string key = groupName + "|" + detail;
            if (_dupLog.TryGetValue(key, out var truoc) && (now - truoc).TotalSeconds < windowSec) return;

            if (_dupLog.Count > 500)
            {
                foreach (var k in _dupLog.Where(kv => (now - kv.Value).TotalSeconds > windowSec)
                                         .Select(kv => kv.Key).ToList())
                    _dupLog.Remove(k);
            }
            _dupLog[key] = now;
            Log($"{icon} [{groupName}/{user}] {detail}");
        }

        private void LogSonCapKhiDoi(string groupName, string line)
        {
            if (_scLastWait.TryGetValue(groupName, out var prev) && prev == line) return;
            _scLastWait[groupName] = line;
            Log(line);
        }

        /// <summary>
        /// Member báo không chen được vào khu của trưởng nhóm (khu đã đủ 15 người) → chuyển tiếng
        /// báo đó cho ĐÚNG trưởng nhóm của nó, để trưởng dời sang khu khác rồi báo lại khu mới.
        ///
        /// Vì sao phải qua Manager: member không thấy trưởng nhóm, và trưởng nhóm không thấy member.
        /// Hai bên chỉ nối được với nhau qua bảng nhóm ở đây.
        ///
        /// Vì sao KHÔNG để member tự bỏ cuộc (hành vi cũ): khu đầy người là chuyện chỉ trưởng nhóm
        /// sửa được. Member bỏ cuộc là mất lượt của cả nhóm vì một việc nó không có quyền sửa.
        /// </summary>
        public void RelayCamThuatZoneFull(string memberUsername, string detail, int wantZone)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelayCamThuatZoneFull(memberUsername, detail, wantZone)));
                return;
            }

            var g = _ctActive.Values.FirstOrDefault(x =>
                x.Members.Any(m => string.Equals(m, memberUsername, StringComparison.OrdinalIgnoreCase)));
            if (g == null)
            {
                Log($"⚔️ [{memberUsername}] báo khu đầy người nhưng không thuộc nhóm nào đang chạy");
                return;
            }

            var ss = FindSession(g.Leader);
            if (ss == null)
            {
                Log($"⚔️⚠️ Nhóm '{g.Name}': {memberUsername} kẹt ngoài khu mà trưởng nhóm {g.Leader} mất kết nối");
                return;
            }
            // want_zone đi kèm: trưởng nhóm bỏ những tiếng báo về khu nó đã rời, không thì nó dời
            // khu liên tục vì tin cũ luôn tới sau lúc đã dời (member bị game khoá 15s).
            ss.SendRawJson("{\"command\":\"cam_thuat_zone_full\",\"want_zone\":" + wantZone
                           + ",\"who\":\"" + EscapeJson(GetCharName(memberUsername)) + "\"}\n");
            LogNhomMotLan("⚔️🚧", g.Name, memberUsername,
                $"không chen được vào khu của {g.Leader} → báo trưởng nhóm dời khu", 10);
        }

        /// <summary>
        /// Y hệt RelayCamThuatZoneFull nhưng cho Sơn cáp — bảng nhóm khác (`_scPlanned`).
        ///
        /// Vì sao cần: log 14:58 ngày 31/07 cho thấy 5 member báo "khong vao duoc khu 4" rồi tự
        /// bỏ cuộc, mà `son_cap_after_afk` lại đưa chúng đi treo map nên nhìn vào tưởng bình
        /// thường — trong khi nhóm chưa hề lập xong và lượt coi như mất.
        /// </summary>
        public void RelaySonCapZoneFull(string memberUsername, string detail, int wantZone)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => RelaySonCapZoneFull(memberUsername, detail, wantZone)));
                return;
            }

            var g = FindSonCapGroupOf(memberUsername);
            if (g == null)
            {
                Log($"🪢 [{memberUsername}] báo khu đầy người nhưng không thuộc nhóm sơn cáp nào");
                return;
            }
            if (string.Equals(g.Leader, memberUsername, StringComparison.OrdinalIgnoreCase)) return;

            var ss = FindSession(g.Leader);
            if (ss == null)
            {
                Log($"🪢⚠️ Nhóm '{g.Name}': {memberUsername} kẹt ngoài khu mà trưởng nhóm {g.Leader} mất kết nối");
                return;
            }
            ss.SendRawJson("{\"command\":\"son_cap_zone_full\",\"want_zone\":" + wantZone
                           + ",\"who\":\"" + EscapeJson(GetCharName(memberUsername)) + "\"}\n");
            LogNhomMotLan("🪢🚧", g.Name, memberUsername,
                $"không chen được vào khu của {g.Leader} → báo trưởng nhóm dời khu", 10);
        }

        /// <summary>
        /// Trưởng nhóm báo đã lập xong nhóm và đang đứng ở map/khu nào → phát cho member
        /// của đúng nhóm đó. Gọi lại được nhiều lần: trưởng nhóm nhảy khu thì member bám theo.
        /// </summary>
        public void RelayCamThuatZone(string leaderUsername, int mapId, int zoneId, string leaderChar,
                                      int leaderX = -1, int leaderY = -1)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() =>
                    RelayCamThuatZone(leaderUsername, mapId, zoneId, leaderChar, leaderX, leaderY)));
                return;
            }

            var g = _ctActive.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (g == null)
            {
                Log($"⚔️ [{leaderUsername}] báo khu {zoneId} nhưng không thuộc nhóm nào đang chạy");
                return;
            }

            if (string.IsNullOrWhiteSpace(leaderChar)) leaderChar = GetCharName(leaderUsername);

            foreach (var m in g.Members)
            {
                // Kèm TOẠ ĐỘ THẬT của trưởng nhóm, không chỉ map/khu. Trước đây member phải tự
                // tra toạ độ NPC trong config của chính nó, nên "cả nhóm cùng một chỗ" phụ thuộc
                // vào việc 12 tiến trình đọc ra cùng một con số.
                FindSession(m)?.SendRawJson(
                    $"{{\"command\":\"cam_thuat_goto\",\"map\":{mapId},\"zone\":{zoneId}," +
                    $"\"x\":{leaderX},\"y\":{leaderY}," +
                    $"\"leader\":\"{EscapeJson(leaderChar)}\"}}\n");
            }

            // Trưởng nhóm phát lại điểm tập kết theo chu kỳ để vớt member nhận lệnh muộn.
            // Chỉ ghi log khi khu thực sự đổi, không thì log ngập mỗi 5 giây.
            string stamp = $"{mapId}/{zoneId}";
            if (!_ctLastRelay.TryGetValue(g.Name, out var prev) || prev != stamp)
            {
                _ctLastRelay[g.Name] = stamp;
                Log($"⚔️ Nhóm '{g.Name}': điểm tập kết map {mapId} khu {zoneId} → đã báo {g.Members.Count} member");
            }
        }

        /// <summary>
        /// Trưởng nhóm báo đội hình thật (danh sách server trả về). Gộp với danh sách khai
        /// trong doi_hinh.cfg để ra bản tổng kết: nhóm nào ĐỦ, nhóm nào THIẾU và thiếu vì đâu.
        /// Có hai kiểu thiếu, phải tách bạch vì cách xử lý khác nhau:
        ///   - "chưa vào game": nick còn chưa đăng nhập → chờ hoặc sửa file, tool bó tay.
        ///   - "chưa vào nhóm": nick đã chạy nhưng chưa gia nhập được → lỗi khu/khoá nhóm/nhóm đầy.
        /// </summary>
        public void UpdateCamThuatRoster(string leaderUsername, int mapId, int zoneId,
                                         string haveRaw, string missingRaw, string strangersRaw)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() =>
                    UpdateCamThuatRoster(leaderUsername, mapId, zoneId, haveRaw, missingRaw, strangersRaw)));
                return;
            }

            var planned = _ctPlanned.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (planned == null) return;

            var active = _ctActive.TryGetValue(planned.Name, out var a) ? a : null;

            var roster = new GroupRoster
            {
                Name = planned.Name,
                Map = mapId,
                Zone = zoneId,
                Planned = 1 + planned.Members.Count,
                Have = SplitNames(haveRaw),
                Missing = SplitNames(missingRaw),
                Strangers = SplitNames(strangersRaw)
            };

            // Nick khai trong file nhưng không hề được gửi lệnh (chưa vào game lúc bấm nút).
            foreach (var m in planned.Members)
            {
                bool started = active != null &&
                               active.Members.Any(x => string.Equals(x, m, StringComparison.OrdinalIgnoreCase));
                if (!started) roster.Absent.Add(m);
            }

            _ctRosters[planned.Name] = roster;

            string line = roster.Full
                ? $"⚔️✅ Nhóm '{roster.Name}': ĐỦ {roster.Have.Count}/{roster.Planned} tại khu {roster.Zone} — {string.Join(", ", roster.Have)}"
                : $"⚔️⏳ Nhóm '{roster.Name}': {roster.Have.Count}/{roster.Planned} tại khu {roster.Zone}"
                  + (roster.Missing.Count > 0 ? $" · chưa vào nhóm: {string.Join(", ", roster.Missing)}" : "")
                  + (roster.Absent.Count > 0 ? $" · chưa vào game: {string.Join(", ", roster.Absent)}" : "")
                  + (roster.Strangers.Count > 0 ? $" · ⚠️ người lạ: {string.Join(", ", roster.Strangers)}" : "");

            // Chỉ ghi khi đội hình thực sự đổi — trưởng nhóm báo lại theo chu kỳ.
            if (!_ctLastRoster.TryGetValue(roster.Name, out var prev) || prev != line)
            {
                _ctLastRoster[roster.Name] = line;
                Log(line);
            }
        }

        // Vị trí từng nick báo về ở bước chờ vào hầm: tên nhóm -> (username -> "map/khu").
        private readonly Dictionary<string, Dictionary<string, string>> _ctPos =
            new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        // tên nhóm -> (username -> đã đứng sát NPC chưa) và (username -> toạ độ, để ghi log)
        private readonly Dictionary<string, Dictionary<string, bool>> _ctAtNpc =
            new Dictionary<string, Dictionary<string, bool>>(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, Dictionary<string, string>> _ctXY =
            new Dictionary<string, Dictionary<string, string>>(StringComparer.OrdinalIgnoreCase);
        // Nhóm đã được phát lệnh mở hầm, để không phát hai lần.
        private readonly HashSet<string> _ctOpened =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// CỔNG CHẶN TRƯỚC KHI VÀO HẦM. Mỗi nick báo map/khu nó đang đứng; chỉ khi ĐỦ QUÂN,
        /// SẠCH NGƯỜI LẠ và MỌI NGƯỜI CÙNG MỘT MAP/KHU thì mới cho trưởng nhóm bấm NPC.
        ///
        /// Vì sao phải chặt tay đến vậy: server trừ lượt tham gia trong ngày NGAY TRƯỚC khi kiểm
        /// nhóm, và chỉ kéo những người đang ở CÙNG KHU với trưởng nhóm. Bấm lúc còn người lệch
        /// khu là mất một lượt của trưởng nhóm mà người đó vẫn bị bỏ lại.
        /// </summary>
        public void UpdateCamThuatPos(string username, int mapId, int zoneId, string role,
                                      int x, int y, bool atNpc)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => UpdateCamThuatPos(username, mapId, zoneId, role, x, y, atNpc)));
                return;
            }

            var planned = _ctPlanned.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, username, StringComparison.OrdinalIgnoreCase) ||
                x.Members.Any(m => string.Equals(m, username, StringComparison.OrdinalIgnoreCase)));
            if (planned == null) return;
            if (_ctOpened.Contains(planned.Name)) return;

            if (!_ctPos.TryGetValue(planned.Name, out var pos))
            {
                pos = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                _ctPos[planned.Name] = pos;
            }
            pos[username] = $"{mapId}/{zoneId}";
            if (!_ctAtNpc.TryGetValue(planned.Name, out var atMap))
            {
                atMap = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
                _ctAtNpc[planned.Name] = atMap;
            }
            atMap[username] = atNpc;
            if (!_ctXY.TryGetValue(planned.Name, out var xyMap))
            {
                xyMap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                _ctXY[planned.Name] = xyMap;
            }
            xyMap[username] = $"({x},{y})";

            // ĐIỀU KIỆN 1 và 2 TRƯỚC ĐÂY RETURN CÂM. Đó là một lỗi đã gây hiểu sai thật:
            // dòng chờ chỉ in khi NỘI DUNG đổi, nên khi luồng dừng sớm ở đây thì dòng
            // "chưa tới chỗ NPC — <cả 4 người>" in lúc đầu ĐỨNG NGUYÊN suốt 2 phút, đọc log
            // tưởng cả nhóm không ai về được NPC trong khi thực tế chỉ trưởng nhóm hỏng.
            // Nay mỗi cửa dừng đều có dòng riêng — vẫn lọc trùng nên không ngập.

            // Điều kiện 1: đội hình phải đủ và sạch (dữ liệu này lấy từ CMD 43 của server).
            if (!_ctRosters.TryGetValue(planned.Name, out var roster) || !roster.Full)
            {
                LogChoNhomKhiDoi(planned.Name,
                    $"⚔️⏳ Nhóm '{planned.Name}': đội hình chưa đủ/chưa sạch theo lần báo gần nhất — chờ trưởng nhóm báo lại");
                return;
            }

            // Điều kiện 2: mọi nick trong nhóm phải đã báo vị trí.
            var everyone = new List<string> { planned.Leader };
            everyone.AddRange(planned.Members);
            var chuaBao = everyone.Where(u => !pos.ContainsKey(u)).ToList();
            if (chuaBao.Count > 0)
            {
                LogChoNhomKhiDoi(planned.Name,
                    $"⚔️⏳ Nhóm '{planned.Name}': chưa nhận được vị trí của {string.Join(", ", chuaBao)}");
                return;
            }

            // Điều kiện 3: tất cả cùng một map và một khu.
            string at = pos[planned.Leader];
            var lech = everyone.Where(u => pos[u] != at).ToList();
            if (lech.Count > 0)
            {
                // Mỗi nick báo vị trí mỗi 2s nên dòng này in ra liên tục nếu không chặn.
                // Chỉ in khi NỘI DUNG đổi — đứng yên chờ thì im lặng, ai nhúc nhích mới có dòng mới.
                LogChoNhomKhiDoi(planned.Name,
                    $"⚔️⏳ Nhóm '{planned.Name}': chưa cùng chỗ — trưởng ở {at}, lệch: " +
                    string.Join(", ", lech.Select(u => $"{u} ở {pos[u]}")));
                return;
            }

            // Điều kiện 4: mọi người phải đứng SÁT NPC. Cùng khu vẫn chưa đủ — đứng xa thì
            // không được kéo vào, mà lượt của trưởng nhóm thì vẫn mất.
            var xaNpc = everyone.Where(u => !atMap.TryGetValue(u, out bool ok) || !ok).ToList();
            if (xaNpc.Count > 0)
            {
                // Chỉ in DANH SÁCH AI còn thiếu, KHÔNG kèm toạ độ: toạ độ đổi liên tục lúc đang
                // đi nên kèm vào là dòng nào cũng "mới" và log lại ngập y như cũ.
                LogChoNhomKhiDoi(planned.Name,
                    $"⚔️⏳ Nhóm '{planned.Name}': chưa tới chỗ NPC — {string.Join(", ", xaNpc)}");
                return;
            }

            _ctOpened.Add(planned.Name);
            Log($"⚔️🚪 Nhóm '{planned.Name}': đủ {roster.Have.Count} người, tất cả ở {at} sát NPC → cho trưởng nhóm mở cấm thuật");
            FindSession(planned.Leader)?.SendRawJson("{\"command\":\"cam_thuat_open\"}\n");
        }

        /// <summary>
        /// Trưởng nhóm báo đã xong một lượt → mở lại cổng chặn cho lượt kế tiếp. Sau khi ra hầm
        /// server có thể xếp mỗi người một khu khác nên phải kiểm lại vị trí từ đầu, không được
        /// dùng lại kết quả của lượt trước.
        /// </summary>
        public void NotifyCamThuatTurnDone(string leaderUsername, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => NotifyCamThuatTurnDone(leaderUsername, detail)));
                return;
            }
            var planned = _ctPlanned.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (planned == null) return;
            _ctOpened.Remove(planned.Name);
            _ctPos.Remove(planned.Name);
            _ctAtNpc.Remove(planned.Name);
            _ctXY.Remove(planned.Name);
            _ctLastWait.Remove(planned.Name);
            _ctLastRelay.Remove(planned.Name);
            Log($"⚔️🔁 Nhóm '{planned.Name}': {detail} — gom lại chỗ NPC cho lượt kế tiếp");
        }

        // ── BÁM THEO trong hầm Cấm thuật: dồn hoả lực vào một con ──────────────────────────
        // Khác Ải gia tộc ở một điểm: AGT không lập nhóm nên phải lấy "ai vào trước làm lead",
        // còn Cấm thuật ĐÃ CÓ nhóm và có trưởng nhóm sẵn trong doi_hinh.cfg. Dùng luôn trưởng
        // nhóm đó làm lead hoả lực: nó là nick mở hầm nên chắc chắn ở trong, và tuyến khớp đúng
        // ranh giới nhóm — không có chuyện mục tiêu của nhóm 1 lọt sang member nhóm 2.
        //
        // tên nhóm -> danh sách nick ĐÃ BÁO đang ở trong hầm.
        private readonly Dictionary<string, List<string>> _ctInDungeon =
            new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);

        /// <summary>Tìm nhóm Cấm thuật chứa nick này (trưởng nhóm hoặc member).</summary>
        private GroupSetup FindCamThuatGroupOf(string username)
        {
            return _ctPlanned.Values.FirstOrDefault(g =>
                string.Equals(g.Leader, username, StringComparison.OrdinalIgnoreCase)
                || g.Members.Any(m => string.Equals(m, username, StringComparison.OrdinalIgnoreCase)));
        }

        /// <summary>
        /// Một nick báo ĐÃ VÀO hầm → nối nó vào tuyến bám theo của đúng nhóm đó.
        ///
        /// Nối dần theo từng nick vào chứ không dựng sẵn cả nhóm: server kéo người vào không
        /// cùng lúc, dựng sẵn thì nick chưa vào đã nhận mục tiêu của một map nó chưa tới.
        /// Tuyến dựng với move=0 — trong hầm máy Cấm thuật đang lái nhân vật, bám theo chỉ được
        /// gán mục tiêu chứ không được ra lệnh đi.
        /// </summary>
        public void NotifyCamThuatInDungeon(string username, int mapId)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifyCamThuatInDungeon(username, mapId))); return; }
            var g = FindCamThuatGroupOf(username);
            if (g == null) return;

            if (!_ctInDungeon.TryGetValue(g.Name, out var inside))
            {
                inside = new List<string>();
                _ctInDungeon[g.Name] = inside;
            }
            if (inside.Any(u => string.Equals(u, username, StringComparison.OrdinalIgnoreCase))) return;
            inside.Add(username);

            // Lead hoả lực = TRƯỞNG NHÓM, và chỉ dựng tuyến khi nó đã ở trong hầm. Trưởng nhóm
            // là nick bấm mở nên bình thường nó vào trước tiên; chưa thấy nó thì cứ ghi nhận
            // người vào rồi chờ, nhịp sau nó vào là tuyến dựng đủ luôn.
            if (!inside.Any(u => string.Equals(u, g.Leader, StringComparison.OrdinalIgnoreCase)))
            {
                Log($"🧲 Nhóm '{g.Name}': {username} vào hầm (map {mapId}), chờ trưởng nhóm vào rồi mới dồn hoả lực");
                return;
            }

            // GỬI follow_start CHO ĐÚNG NICK CHƯA ĐƯỢC GỬI, không gọi StartFollowGroup.
            // Server kéo người vào lệch nhau vài giây, nên hàm này chạy nhiều lần cho một nhóm.
            // StartFollowGroup gửi lại cho CẢ lead lẫn mọi member cũ — mà bên mod follow_start là
            // lệnh khởi tạo lại phiên: nó tắt đánh, xoá mục tiêu rồi chọn lại. Nghĩa là cứ thêm
            // một người vào là lead bị giật mất con đang đánh dở, đúng cái mà dồn hoả lực sinh ra
            // để tránh.
            string mv = "{\"command\":\"follow_start\",\"role\":";
            var routes = _flRoutes;
            var daGui = routes.TryGetValue(g.Leader, out var cu)
                ? new HashSet<string>(cu, StringComparer.OrdinalIgnoreCase)
                : null;
            if (daGui == null)
            {
                daGui = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                FindSession(g.Leader)?.SendRawJson(mv + "1,\"move\":0,\"owner\":\"cam_thuat\"}\n");
                Log($"🧲 Nhóm '{g.Name}': {g.Leader} làm lead hoả lực trong hầm (map {mapId})");
            }

            string leaderChar = GetCharName(g.Leader);
            var mem = inside.Where(u => !string.Equals(u, g.Leader, StringComparison.OrdinalIgnoreCase)).ToList();
            int moi = 0;
            foreach (var m in mem)
            {
                if (daGui.Contains(m)) continue;
                var ss = FindSession(m);
                if (ss == null) continue;
                ss.SendRawJson(mv + "2,\"move\":0,\"owner\":\"cam_thuat\",\"leader\":\""
                               + EscapeJson(leaderChar) + "\"}\n");
                moi++;
            }

            var next = new Dictionary<string, string[]>(routes, StringComparer.OrdinalIgnoreCase);
            next[g.Leader] = mem.ToArray();
            _flRoutes = next;
            if (moi > 0)
                Log($"🧲 Nhóm '{g.Name}': +{moi} nick dồn hoả lực theo {g.Leader}"
                    + $" (tổng {mem.Count}, map {mapId}, chỉ gán mục tiêu)");
        }

        /// <summary>Một nick báo ĐÃ RA khỏi hầm → gỡ tuyến của nhóm đó (chỉ nhóm đó).</summary>
        public void NotifyCamThuatOutDungeon(string username)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NotifyCamThuatOutDungeon(username))); return; }
            var g = FindCamThuatGroupOf(username);
            if (g == null) return;
            if (!_ctInDungeon.ContainsKey(g.Name)) return;

            // XOÁ CẢ CỤM ngay từ nick ĐẦU TIÊN ra khỏi hầm, không gỡ lẻ từng nick.
            //
            // Hai lý do, cả hai đều là chuyện sẽ xảy ra thật khi chạy nhiều lượt:
            //  · Ra khỏi hầm là biến cố của CẢ NHÓM (xong vòng, hoặc hết giờ) — cả nhóm bị đẩy ra
            //    trong vài giây. Giữ tuyến lại thì lead vẫn báo mục tiêu trong lúc mọi người đang
            //    đi bộ về NPC cho lượt sau, đúng lúc không được xen vào tay lái nhất.
            //  · Gỡ lẻ thì nick nào RỚT MẠNG trong hầm sẽ không bao giờ báo ra, tên nó nằm lại
            //    trong danh sách vĩnh viễn. Lượt sau nó vào hầm, báo cam_thuat_in, bị phép chống
            //    trùng coi là "đã ở trong rồi" và bỏ qua ⇒ nick đó lặng lẽ không được nối vào
            //    tuyến, không có dòng log nào nói vì sao.
            // Xoá sạch thì lượt sau dựng lại từ đầu, không mang theo rác của lượt trước.
            _ctInDungeon.Remove(g.Name);
            int n = StopFollowGroup(g.Leader);
            if (n > 0) Log($"🧲🛑 Nhóm '{g.Name}': ra khỏi hầm → tắt dồn hoả lực ({n} nick)");
        }

        /// <summary>
        /// Trưởng nhóm chạy nháp xong (dừng trước cú bấm tốn lượt) → bảo member thôi chờ,
        /// khỏi đứng không tới hết giờ.
        /// </summary>
        public void NotifyCamThuatDryRun(string leaderUsername, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => NotifyCamThuatDryRun(leaderUsername, detail)));
                return;
            }
            var planned = _ctPlanned.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            Log($"⚔️🧪 CHẠY NHÁP [{leaderUsername}] {detail}");
            if (planned == null) return;
            foreach (var m in planned.Members)
                FindSession(m)?.SendRawJson("{\"command\":\"cam_thuat_stop\"}\n");
        }

        // tên nhóm -> dòng chờ gần nhất đã in, để không in lặp mỗi nhịp báo vị trí (2s/nick).
        private readonly Dictionary<string, string> _ctLastWait =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        private void LogChoNhomKhiDoi(string groupName, string line)
        {
            if (_ctLastWait.TryGetValue(groupName, out var prev) && prev == line) return;
            _ctLastWait[groupName] = line;
            Log(line);
        }

        private static List<string> SplitNames(string raw)
        {
            var list = new List<string>();
            if (string.IsNullOrWhiteSpace(raw)) return list;
            foreach (var s in raw.Split(';'))
                if (!string.IsNullOrWhiteSpace(s)) list.Add(s.Trim());
            return list;
        }

        /// <summary>
        /// Bản tổng kết cuối: nhóm nào đủ quân, nhóm nào không. In khi trưởng nhóm chốt phiên.
        /// Đây chính là cửa chặn cho bước vào map cấm thuật sau này — chỉ nhóm ĐỦ mới được đi.
        /// </summary>
        public void NotifyCamThuatLeaderDone(string leaderUsername)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => NotifyCamThuatLeaderDone(leaderUsername)));
                return;
            }
            _ctDoneLeaders.Add(leaderUsername);
            // Chỉ tổng kết khi MỌI trưởng nhóm đã chốt, để không in ba lần cho ba nhóm.
            if (_ctActive.Count > 0 && _ctDoneLeaders.Count >= _ctActive.Count)
                LogCamThuatSummary();
        }

        /// <summary>
        /// Trưởng nhóm CHỐT PHIÊN — dù xong bình thường hay hỏng. Phải báo member trong CẢ HAI
        /// trường hợp.
        ///
        /// Trước đây chỉ báo khi HỎNG, và đó là một lỗi thật: đường kết thúc hay gặp nhất là
        /// "có người trong nhóm hết lượt", mà đường đó được tính là kết thúc BÌNH THƯỜNG
        /// (ok = true). Nên member không ai báo — đứng ở chỗ NPC tới khi hết hạn 120s của chính
        /// nó mới tự bỏ, rồi còn bị ghi thành "❌ het gio o buoc 17" như thể có sự cố.
        /// Trưởng nhóm lúc đó đã rời nhóm và đi farm, còn member vẫn kẹt trong nhóm cũ.
        /// </summary>
        public void NotifyCamThuatLeaderEnd(string leaderUsername, bool ok, string detail)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => NotifyCamThuatLeaderEnd(leaderUsername, ok, detail)));
                return;
            }
            NotifyCamThuatLeaderDone(leaderUsername);
            var planned = _ctPlanned.Values.FirstOrDefault(x =>
                string.Equals(x.Leader, leaderUsername, StringComparison.OrdinalIgnoreCase));
            if (planned == null) return;
            _ctOpened.Remove(planned.Name);
            // Chốt phiên thì tuyến bám theo phải đi cùng. Bên mod đã báo cam_thuat_out ở mọi
            // đường kết thúc, nhưng nick rớt mạng thì không báo được gì — mà tuyến còn sống là
            // member vẫn nhận lệnh gán mục tiêu sau khi hoạt động đã tắt.
            StopFollowGroup(planned.Leader);
            _ctInDungeon.Remove(planned.Name);
            int n = 0;
            foreach (var m in planned.Members)
            {
                var ss = FindSession(m);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"cam_thuat_stop\"}\n");
                n++;
            }
            if (n > 0)
                Log($"⚔️🛑 Nhóm '{planned.Name}': trưởng nhóm đã chốt phiên ({(ok ? "xong bình thường" : "HỎNG")})"
                    + $" → đã báo {n} member dừng chờ, rời nhóm và đi treo map");
        }

        private readonly HashSet<string> _ctDoneLeaders =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        private void LogCamThuatSummary()
        {
            if (_ctPlanned.Count == 0) return;

            var ok = new List<string>();
            var bad = new List<string>();
            foreach (var g in _ctPlanned.Values)
            {
                // KHU lấy từ vị trí báo về gần nhất, KHÔNG lấy từ roster.
                // _ctRosters chỉ được cập nhật bởi bản báo đội hình của trưởng nhóm, mà từ lượt 2
                // trở đi trưởng nhóm không đi qua bước đó nữa — nên số khu trong roster đứng
                // nguyên từ lượt 1. Dòng tổng kết in ra "khu 41" trong khi cả nhóm đang ở khu 40
                // là in một con số của gần hai tiếng trước.
                string where = "?";
                if (_ctPos.TryGetValue(g.Name, out var p)
                    && p.TryGetValue(g.Leader, out var at)) where = at;

                if (_ctRosters.TryGetValue(g.Name, out var r) && r.Full)
                    ok.Add($"{g.Name} ({r.Have.Count} người, {where})");
                else if (_ctRosters.TryGetValue(g.Name, out var r2))
                    bad.Add($"{g.Name} ({r2.Have.Count}/{r2.Planned})");
                else
                    bad.Add($"{g.Name} (chưa lập được nhóm)");
            }

            Log($"⚔️ ── TỔNG KẾT — đủ quân: {(ok.Count > 0 ? string.Join(" · ", ok) : "không nhóm nào")}");
            if (bad.Count > 0)
                Log($"⚔️ ── TỔNG KẾT — còn thiếu: {string.Join(" · ", bad)}");
        }

        /// <summary>Escape chuỗi trước khi nhét vào JSON gửi mod (tên nhân vật có ký tự lạ).</summary>
        private static string EscapeJson(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return s.Replace("\\", "\\\\").Replace("\"", "\\\"")
                    .Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t");
        }

        /// <summary>
        /// Đóng dấu "nick này đã bấm nhận chìa hôm nay" và lưu xuống config.json,
        /// để lần chạy sau (kể cả sau khi tắt game/tắt Manager) không bấm lại nữa.
        /// Muốn ép nhận lại: xoá DiaCungKeyDate của nick trong config.json.
        /// </summary>
        public void MarkDiaCungKeyClaimed(string username)
        {
            if (string.IsNullOrWhiteSpace(username)) return;
            if (InvokeRequired) { BeginInvoke(new Action(() => MarkDiaCungKeyClaimed(username))); return; }

            var acc = FindAccount(username);
            if (acc == null) return;
            string today = DateTime.Now.ToString("yyyy-MM-dd");
            if (acc.DiaCungKeyDate == today) return;

            acc.DiaCungKeyDate = today;
            SaveConfig();
            Log($"🔑 [{username}] đã nhận chìa hôm nay ({today}) — lần chạy sau sẽ vào thẳng hầm");
        }

        /// <summary>
        /// Xoá dấu "đã nhận chìa hôm nay" khi hoá ra dấu đó không đúng — nick bỏ qua bước nhận
        /// chìa rồi bấm vào hầm mà không vào được. Dấu này vốn chỉ ghi "đã BẤM", không phải
        /// "server đã CẤP", nên nó có thể sai; xoá đi để lần chạy sau nhận lại chìa thay vì
        /// lặp lại đúng thất bại đó suốt ngày hôm nay.
        /// </summary>
        public void ClearDiaCungKeyDate(string username, string reason)
        {
            if (string.IsNullOrWhiteSpace(username)) return;
            if (InvokeRequired) { BeginInvoke(new Action(() => ClearDiaCungKeyDate(username, reason))); return; }

            var acc = FindAccount(username);
            if (acc == null || string.IsNullOrEmpty(acc.DiaCungKeyDate)) return;
            acc.DiaCungKeyDate = "";
            SaveConfig();
            Log($"🔑↩️ [{username}] {reason} — đã xoá dấu nhận chìa, lần sau sẽ nhận lại");
        }

        /// <summary>Cập nhật cột "Map/Khu" trên lưới theo cấu hình hiện tại.</summary>
        private void RefreshAfkColumn()
        {
            if (dgvAccounts == null || !dgvAccounts.Columns.Contains("AfkInfo")) return;
            foreach (DataGridViewRow row in dgvAccounts.Rows)
            {
                string username = row.Cells["Username"].Value?.ToString() ?? "";
                var acc = FindAccount(username);
                row.Cells["AfkInfo"].Value = FormatAfkInfo(acc);
            }
        }

        /// <summary>
        /// Gửi AFK config (map + zone) cho một session cụ thể.
        /// Được gọi khi game client mới kết nối và gửi status đầu tiên.
        /// </summary>
        public void SendAfkConfigToSession(ClientSession session)
        {
            var acc = FindAccount(session.Username);
            int m = EffectiveAfkMap(acc), z = EffectiveAfkZone(acc);
            if (m > 0)
            {
                session.SendRawJson($"{{\"command\":\"set_afk_map\",\"map\":{m},\"zone\":{z}}}\n");
                Log($"📍 Tự gửi AFK map={m} khu={z} cho {session.Username}");
            }
        }

        private void SendCommandAll(string command)
        {
            lock (_sessions)
            {
                var connectedSessions = _sessions.Where(s => s.IsConnected).ToList();
                int skipped = _sessions.Count - connectedSessions.Count;
                Log($"📡 Gửi '{command}' cho {connectedSessions.Count} session(s) đang kết nối (bỏ qua {skipped} đã ngắt)...");
                int sent = 0;
                foreach (var session in connectedSessions)
                {
                    try
                    {
                        session.SendCommand(command);
                        Log($"📤 Đã gửi lệnh '{command}' tới {session.Username}");
                        sent++;
                    }
                    catch (Exception ex)
                    {
                        Log($"⚠ Gửi '{command}' tới {session.Username} thất bại: {ex.Message}");
                    }
                }
                Log($"✅ Kết quả: {sent}/{connectedSessions.Count} nick nhận lệnh");
            }
        }

        private void SendCommandSelected(string command)
        {
            if (dgvAccounts.SelectedRows.Count == 0)
            {
                MessageBox.Show("Vui lòng chọn tài khoản từ danh sách!");
                return;
            }

            string username = dgvAccounts.SelectedRows[0].Cells["Username"].Value?.ToString();
            if (string.IsNullOrEmpty(username)) return;

            lock (_sessions)
            {
                var session = _sessions.Find(s => s.Username == username);
                if (session != null)
                {
                    session.SendCommand(command);
                    Log($"📤 Đã gửi lệnh '{command}' tới {username}");
                }
                else
                {
                    Log($"⚠ Không tìm thấy session cho {username}");
                }
            }
        }

        // ── Cập nhật UI tài khoản ─────────────────────────────────────────
        public void UpdateAccountUI(string username, string status, string charInfo, string task = "")
        {
            _syncContext.Post(_ =>
            {
                _lastTask[username] = task ?? "";
                bool found = false;
                foreach (DataGridViewRow row in dgvAccounts.Rows)
                {
                    if (row.Cells["Username"].Value?.ToString().Equals(username, StringComparison.OrdinalIgnoreCase) == true)
                    {
                        row.Cells["Status"].Value = status;
                        row.Cells["CharInfo"].Value = charInfo;
                        ApplyStatusColor(row);
                        found = true;
                        break;
                    }
                }
                if (!found)
                {
                    int newIdx = dgvAccounts.Rows.Add(false, username, status, charInfo);
                    ApplyStatusColor(dgvAccounts.Rows[newIdx]);
                    Log($"🔗 Tài khoản đã kết nối: {username}");
                }
            }, null);
        }

        // ── Tô màu cảnh báo cột "Trạng thái" (đèn giao thông) theo TỪNG nick ──
        // Đỏ = mất kết nối, trắng = bình thường. Đặt màu theo từng dòng nên chạy nhiều nick
        // mỗi nick có màu riêng, không lẫn nhau.
        private void ApplyStatusColor(DataGridViewRow row)
        {
            if (row == null) return;
            var cell = row.Cells["Status"];

            string s = cell.Value?.ToString() ?? "";
            bool offline = string.IsNullOrEmpty(s)
                || s.IndexOf("Chưa kết nối", StringComparison.OrdinalIgnoreCase) >= 0
                || s.IndexOf("Mất kết nối", StringComparison.OrdinalIgnoreCase) >= 0
                || s.IndexOf("Ngắt kết nối", StringComparison.OrdinalIgnoreCase) >= 0;

            Color c = offline ? ColRed : Color.White;
            cell.Style.ForeColor = c;
            cell.Style.SelectionForeColor = c;
        }

        /// <summary>
        /// Ghi nhận trạng thái login của account từ gói status.
        ///
        /// KHÔNG dùng riêng "level > 0" để kết luận đã login: level đọc từ gameData
        /// (a.i.a()), chỉ cần object đó tồn tại là j() trả 1 mặc định — mạng chậm hoặc
        /// server quá tải, nhân vật chưa vào được thế giới nhưng vẫn báo Lv.1.
        /// Mốc đáng tin là có ĐỦ dữ liệu nhân vật thật: tên + máu + level.
        /// </summary>
        public void NotifyAccountLogin(string username, int level, string charName, int maxHp)
        {
            bool loggedIn = level > 0
                         && !string.IsNullOrWhiteSpace(charName)
                         && maxHp > 0;

            bool justLoggedIn = false;
            lock (_accountLevels)
            {
                _accountLevels[username] = level;
                bool was = _accountLoggedIn.TryGetValue(username, out bool b) && b;
                _accountLoggedIn[username] = loggedIn;
                justLoggedIn = loggedIn && !was;
                if (loggedIn) _charNames[username] = charName;
            }

            if (justLoggedIn)
                Log($"🔓 {username} đã vào game: {charName} Lv.{level} (HP tối đa {maxHp})");
        }

        /// <summary>Account đã thực sự vào game chưa (đủ tên nhân vật + máu + level).</summary>
        private bool IsLoggedIn(string username)
        {
            lock (_accountLevels)
            {
                return _accountLoggedIn.TryGetValue(username, out bool b) && b;
            }
        }

        /// <summary>
        /// Chờ account có level > 0 (login thành công).
        /// Poll mỗi 2s, timeout sau maxWaitSeconds giây.
        /// </summary>
        private async Task<bool> WaitForAccountLogin(string username, int maxWaitSeconds,
                                                    CancellationToken ct = default)
        {
            int elapsed = 0;
            while (elapsed < maxWaitSeconds)
            {
                await Task.Delay(2000, ct); // huỷ giữa chừng → OperationCanceledException
                elapsed += 2;

                if (IsLoggedIn(username)) return true;
            }
            return false;
        }

        public void RemoveSession(ClientSession session)
        {
            lock (_sessions)
            {
                _sessions.Remove(session);
            }
            // Xoá level đã ghi nhận — nếu không, nick rớt rồi vẫn bị coi là "đã login"
            // ở lần khởi chạy sau, và WaitForAccountLogin trả true ngay lập tức.
            if (!string.IsNullOrEmpty(session.Username))
            {
                lock (_accountLevels)
                {
                    _accountLevels.Remove(session.Username);
                    _accountLoggedIn.Remove(session.Username);
                }
            }
        }

        public void RemoveAccountUI(string username)
        {
            _syncContext.Post(_ =>
            {
                bool isSaved = _config.Accounts.Exists(a => a.Username.Equals(username, StringComparison.OrdinalIgnoreCase));
                for (int i = dgvAccounts.Rows.Count - 1; i >= 0; i--)
                {
                    if (dgvAccounts.Rows[i].Cells["Username"].Value?.ToString().Equals(username, StringComparison.OrdinalIgnoreCase) == true)
                    {
                        if (isSaved)
                        {
                            dgvAccounts.Rows[i].Cells["Status"].Value = "Chưa kết nối";
                            dgvAccounts.Rows[i].Cells["CharInfo"].Value = "";
                            ApplyStatusColor(dgvAccounts.Rows[i]);
                            Log($"🔌 Tài khoản đã ngắt kết nối: {username}");
                        }
                        else
                        {
                            dgvAccounts.Rows.RemoveAt(i);
                            Log($"🔌 Tài khoản lạ đã ngắt kết nối: {username}");
                        }
                        break;
                    }
                }
            }, null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CLIENT SESSION (TCP)
    // ════════════════════════════════════════════════════════════════════════
    public class ClientSession
    {
        private TcpClient _client;
        private Form1 _mainForm;
        private NetworkStream _stream;
        private bool _afkConfigSent = false;
        public string Username { get; private set; }
        public bool IsConnected => _client?.Connected == true;

        public ClientSession(TcpClient client, Form1 mainForm)
        {
            _client = client;
            _mainForm = mainForm;
            _stream = client.GetStream();
        }

        public async Task ProcessAsync()
        {
            var reader = new StreamReader(_stream, Encoding.UTF8);
            try
            {
                while (true)
                {
                    string line = await reader.ReadLineAsync();
                    if (line == null) break;

                    try
                    {
                        var data = JsonSerializer.Deserialize<Dictionary<string, object>>(line);
                        if (data != null)
                        {
                            // Quan sát TRƯỚC khi phân nhánh: mỗi loại gói chỉ đi vào đúng một
                            // nhánh rồi `continue`, đặt sau là hụt gần hết. Hàm này chỉ đọc.
                            _mainForm.TheoDoi(Username, data);

                            // Xử lý response tọa độ
                            if (data.TryGetValue("type", out var typeObj) && typeObj.ToString() == "pos_info")
                            {
                                string user = data.TryGetValue("username", out var uP) ? uP.ToString() : Username;
                                string x = data.TryGetValue("x", out var xObj) ? xObj.ToString() : "?";
                                string y = data.TryGetValue("y", out var yObj) ? yObj.ToString() : "?";
                                string map = data.TryGetValue("map", out var mObj) ? mObj.ToString() : "?";
                                _mainForm.Log($"📍 [{user}] Pos=({x},{y}) Map={map}");
                                continue;
                            }

                            // ── dia_cung_key_claimed: client vừa bấm nhận chìa → đóng dấu ngày ──
                            if (data.TryGetValue("type", out var typeKc) && typeKc.ToString() == "dia_cung_key_claimed")
                            {
                                string user = data.TryGetValue("username", out var uKc) ? uKc.ToString() : Username;
                                _mainForm.MarkDiaCungKeyClaimed(user);
                                continue;
                            }

                            // ── dia_cung_key_reset: dấu "đã nhận chìa" hoá ra sai → xoá đi ──
                            if (data.TryGetValue("type", out var typeKr) && typeKr.ToString() == "dia_cung_key_reset")
                            {
                                string user = data.TryGetValue("username", out var uKr) ? uKr.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dKr) ? dKr.ToString() : "";
                                _mainForm.ClearDiaCungKeyDate(user, detail);
                                continue;
                            }

                            // ── dia_cung_progress: mốc tiến trình giữa chừng ──
                            if (data.TryGetValue("type", out var typeDcP) && typeDcP.ToString() == "dia_cung_progress")
                            {
                                string user = data.TryGetValue("username", out var uP2) ? uP2.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dP2) ? dP2.ToString() : "";
                                _mainForm.Log($"🏯 [{user}] {detail}");
                                continue;
                            }

                            // ── dia_cung: kết quả cuối của hoạt động Địa cung ──
                            if (data.TryGetValue("type", out var typeDc) && typeDc.ToString() == "dia_cung")
                            {
                                string user = data.TryGetValue("username", out var uD) ? uD.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dD) ? dD.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okD)
                                          && bool.TryParse(okD.ToString(), out bool b) && b;
                                _mainForm.RelayDiaCungEnd(user, ok, detail);
                                continue;
                            }

                            // ── giftcode_result: kết quả nhập giftcode ──
                            if (data.TryGetValue("type", out var typeGc) && typeGc.ToString() == "giftcode_result")
                            {
                                string user = data.TryGetValue("username", out var uGc) ? uGc.ToString() : Username;
                                string code = data.TryGetValue("code", out var cGc) ? cGc.ToString() : "";
                                string msg = data.TryGetValue("msg", out var mGc) ? mGc.ToString() : "";
                                bool success = data.TryGetValue("success", out var sGc) && bool.TryParse(sGc.ToString(), out bool b) && b;
                                _mainForm.OnGiftCodeResult(user, code, msg, success);
                                continue;
                            }

                            // ── cam_thuat_zone: trưởng nhóm đã lập nhóm xong, báo map/khu tập kết ──
                            // Đây là mắt xích bắt buộc: khu KHÔNG chọn trước được (trưởng nhóm
                            // có thể phải nhảy khu vì khu đầy nhóm), nên member chỉ biết khu
                            // qua đường này.
                            if (data.TryGetValue("type", out var typeCtZ) && typeCtZ.ToString() == "cam_thuat_zone")
                            {
                                string user = data.TryGetValue("username", out var uCtZ) ? uCtZ.ToString() : Username;
                                int mapId = data.TryGetValue("map", out var mCtZ)
                                            && int.TryParse(mCtZ.ToString(), out int mv) ? mv : 0;
                                int zoneId = data.TryGetValue("zone", out var zCtZ)
                                             && int.TryParse(zCtZ.ToString(), out int zv) ? zv : -1;
                                string leaderChar = data.TryGetValue("extra", out var eCtZ) ? eCtZ.ToString() : "";
                                int lx = data.TryGetValue("x", out var xCtZ)
                                         && int.TryParse(xCtZ.ToString(), out int lxv) ? lxv : -1;
                                int ly = data.TryGetValue("y", out var yCtZ)
                                         && int.TryParse(yCtZ.ToString(), out int lyv) ? lyv : -1;
                                _mainForm.RelayCamThuatZone(user, mapId, zoneId, leaderChar, lx, ly);
                                continue;
                            }

                            // ── cam_thuat_roster: trưởng nhóm báo đội hình thật của nhóm ──
                            if (data.TryGetValue("type", out var typeCtR) && typeCtR.ToString() == "cam_thuat_roster")
                            {
                                string user = data.TryGetValue("username", out var uCtR) ? uCtR.ToString() : Username;
                                int mapId = data.TryGetValue("map", out var mCtR)
                                            && int.TryParse(mCtR.ToString(), out int mvR) ? mvR : 0;
                                int zoneId = data.TryGetValue("zone", out var zCtR)
                                             && int.TryParse(zCtR.ToString(), out int zvR) ? zvR : -1;
                                string have = data.TryGetValue("have", out var hCtR) ? hCtR.ToString() : "";
                                string missing = data.TryGetValue("missing", out var msCtR) ? msCtR.ToString() : "";
                                string strangers = data.TryGetValue("strangers", out var stCtR) ? stCtR.ToString() : "";
                                _mainForm.UpdateCamThuatRoster(user, mapId, zoneId, have, missing, strangers);
                                continue;
                            }

                            // ── cam_thuat_zone_full: member không chen được vào khu của trưởng ──
                            if (data.TryGetValue("type", out var typeCtZF)
                                && typeCtZF.ToString() == "cam_thuat_zone_full")
                            {
                                string user = data.TryGetValue("username", out var uZF) ? uZF.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dZF) ? dZF.ToString() : "";
                                // want_zone = KHU BỊ TỪ CHỐI, khác với `zone` (khu member đang đứng).
                                int wz = data.TryGetValue("want_zone", out var wZF)
                                         && int.TryParse(wZF.ToString(), out int wvZF) ? wvZF : -1;
                                _mainForm.RelayCamThuatZoneFull(user, detail, wz);
                                continue;
                            }

                            // ── cam_thuat_ready: một nick báo vị trí ở bước chờ vào hầm ──
                            if (data.TryGetValue("type", out var typeCtRd) && typeCtRd.ToString() == "cam_thuat_ready")
                            {
                                string user = data.TryGetValue("username", out var uRd) ? uRd.ToString() : Username;
                                int mapId = data.TryGetValue("map", out var mRd)
                                            && int.TryParse(mRd.ToString(), out int mvRd) ? mvRd : 0;
                                int zoneId = data.TryGetValue("zone", out var zRd)
                                             && int.TryParse(zRd.ToString(), out int zvRd) ? zvRd : -1;
                                string role = data.TryGetValue("extra", out var eRd) ? eRd.ToString() : "";
                                int px = data.TryGetValue("x", out var xRd)
                                         && int.TryParse(xRd.ToString(), out int xv) ? xv : -1;
                                int py = data.TryGetValue("y", out var yRd)
                                         && int.TryParse(yRd.ToString(), out int yv) ? yv : -1;
                                bool atNpc = data.TryGetValue("atNpc", out var aRd)
                                             && bool.TryParse(aRd.ToString(), out bool av) && av;
                                _mainForm.UpdateCamThuatPos(user, mapId, zoneId, role, px, py, atNpc);
                                continue;
                            }

                            // ── cam_thuat_turn: xong một lượt, mở lại cổng chặn cho lượt sau ──
                            if (data.TryGetValue("type", out var typeCtT) && typeCtT.ToString() == "cam_thuat_turn")
                            {
                                string user = data.TryGetValue("username", out var uT) ? uT.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dT) ? dT.ToString() : "";
                                _mainForm.NotifyCamThuatTurnDone(user, detail);
                                continue;
                            }

                            // ── cam_thuat_dry: chạy nháp, dừng trước cú bấm tốn lượt ──
                            if (data.TryGetValue("type", out var typeCtD) && typeCtD.ToString() == "cam_thuat_dry")
                            {
                                string user = data.TryGetValue("username", out var uD2) ? uD2.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dD2) ? dD2.ToString() : "";
                                _mainForm.NotifyCamThuatDryRun(user, detail);
                                continue;
                            }

                            // ── cam_thuat_in / cam_thuat_out: vào hoặc ra khỏi hầm ──
                            // Dùng để bật/tắt BÁM THEO dồn hoả lực cho đúng nhóm đó.
                            if (data.TryGetValue("type", out var typeCtI) && typeCtI.ToString() == "cam_thuat_in")
                            {
                                string user = data.TryGetValue("username", out var uCtI) ? uCtI.ToString() : Username;
                                int mapId = data.TryGetValue("map", out var mCtI)
                                            && int.TryParse(mCtI.ToString(), out int miI) ? miI : -1;
                                _mainForm.NotifyCamThuatInDungeon(user, mapId);
                                continue;
                            }
                            if (data.TryGetValue("type", out var typeCtO) && typeCtO.ToString() == "cam_thuat_out")
                            {
                                string user = data.TryGetValue("username", out var uCtO) ? uCtO.ToString() : Username;
                                _mainForm.NotifyCamThuatOutDungeon(user);
                                continue;
                            }

                            // ── bua_ue_tho: nick bị người chơi khác khoá lại bằng bảng captcha ──
                            // Tin này phải đi NGAY và không đi chung cổng với tin lỗi: đây không
                            // phải lỗi của tool, mà tắt nhầm nó thì nick nằm chết VÔ THỜI HẠN —
                            // bùa không tự tan, chỉ nhập đúng mã (hoặc tắt hẳn client) mới thoát.
                            // Kết quả NHẬP MÃ. Trước đây gói này rơi vào hư không: mod báo đầy đủ
                            // "đã ghi vào trường nào, gọi hàm nào, hỏng ra sao" mà Manager không
                            // bắt, nên nhìn từ ngoài chỉ thấy "mã vào ô rồi đứng im" — không có
                            // cách nào biết là không tìm thấy hàm hay gọi rồi mà hỏng.
                            // Mã sai / bỏ cuộc. Gõ sai là chuyện thường vì captcha cố tình khó đọc
                            // (I hoa với l thường, 0 với O). Game giữ nguyên mã cũ nên chỉ cần báo
                            // để người dùng đọc lại tấm ảnh CŨ và reply tiếp — không gửi ảnh mới.
                            if (data.TryGetValue("type", out var typeBuaS)
                                && (typeBuaS.ToString() == "bua_ue_tho_sai"
                                    || typeBuaS.ToString() == "bua_ue_tho_bo"))
                            {
                                string user = data.TryGetValue("username", out var uBS) ? uBS.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dBS) ? dBS.ToString() : "";
                                _mainForm.TeleBuaSaiMa(user, detail, typeBuaS.ToString() == "bua_ue_tho_bo");
                                continue;
                            }

                            if (data.TryGetValue("type", out var typeBuaN)
                                && typeBuaN.ToString() == "bua_ue_tho_nhap")
                            {
                                string user = data.TryGetValue("username", out var uBN) ? uBN.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dBN) ? dBN.ToString() : "";
                                _mainForm.Log($"🧿⌨️ [{user}] {detail}");
                                continue;
                            }

                            if (data.TryGetValue("type", out var typeBua)
                                && (typeBua.ToString() == "bua_ue_tho" || typeBua.ToString() == "bua_ue_tho_het"))
                            {
                                string user = data.TryGetValue("username", out var uBua) ? uBua.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dBua) ? dBua.ToString() : "";
                                // Có kèm ảnh thì đi đường ảnh: đẩy lên Telegram để người dùng đọc
                                // và reply mã. Không có ảnh thì vẫn báo — biết mà vào game gõ tay
                                // còn hơn không biết gì.
                                byte[] png = null;
                                if (data.TryGetValue("anh", out var aBua))
                                {
                                    try { png = Convert.FromBase64String(aBua.ToString()); }
                                    catch { png = null; }
                                }
                                if (png != null && png.Length > 0)
                                    _mainForm.TeleBuaCaptcha(user, detail, png);
                                else
                                    _mainForm.TeleBuaUeTho(user, detail, typeBua.ToString() == "bua_ue_tho");
                                continue;
                            }

                            // ── cam_thuat_progress: mốc tiến trình gom nhóm ──
                            if (data.TryGetValue("type", out var typeCtP) && typeCtP.ToString() == "cam_thuat_progress")
                            {
                                string user = data.TryGetValue("username", out var uCtP) ? uCtP.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dCtP) ? dCtP.ToString() : "";
                                _mainForm.Log($"⚔️ [{user}] {detail}");
                                continue;
                            }

                            // ── cam_thuat_group: kết quả gom nhóm của một nick ──
                            if (data.TryGetValue("type", out var typeCtG) && typeCtG.ToString() == "cam_thuat_group")
                            {
                                string user = data.TryGetValue("username", out var uCtG) ? uCtG.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dCtG) ? dCtG.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okCtG)
                                          && bool.TryParse(okCtG.ToString(), out bool bCtG) && bCtG;
                                string role = data.TryGetValue("extra", out var eCtG) ? eCtG.ToString() : "";
                                _mainForm.Log($"{(ok ? "⚔️✅" : "⚔️❌")} [{user}] {detail}");
                                continue;
                            }

                            // ── cam_thuat_end: một nick CHỐT PHIÊN (xong hẳn hoặc hỏng hẳn) ──
                            if (data.TryGetValue("type", out var typeCtE) && typeCtE.ToString() == "cam_thuat_end")
                            {
                                string user = data.TryGetValue("username", out var uCtE) ? uCtE.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dCtE) ? dCtE.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okCtE)
                                          && bool.TryParse(okCtE.ToString(), out bool bCtE) && bCtE;
                                string role = data.TryGetValue("extra", out var eCtE) ? eCtE.ToString() : "";
                                _mainForm.Log($"{(ok ? "⚔️✅" : "⚔️❌")} [{user}] {detail}");
                                if (role == "leader") _mainForm.NotifyCamThuatLeaderEnd(user, ok, detail);
                                continue;
                            }

                            // ── map_scan: kết quả soi map ──
                            // LUÔN ghi ra file. Ô log trên giao diện có giới hạn và tự cuộn, mà
                            // một lượt Cấm thuật soi vài chục lần × mỗi lần vài chục dòng thì thứ
                            // cần đọc lại trôi mất — trong khi hoạt động mỗi ngày chỉ chạy được
                            // một lượt, mất là mất luôn tới hôm sau.
                            //
                            // Giao diện thì chỉ nhận dòng của lần bấm nút TAY (auto=false). Soi tự
                            // động mà cũng đổ hết lên giao diện thì nó nhấn chìm log ⚔️ của chính
                            // Cấm thuật — đúng thứ phải nhìn được trong lúc chạy.
                            if (data.TryGetValue("type", out var typeMs)
                                && typeMs.ToString() == "map_scan")
                            {
                                string user = data.TryGetValue("username", out var uMs) ? uMs.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dMs) ? dMs.ToString() : "";
                                bool auto = data.TryGetValue("auto", out var aMs)
                                            && bool.TryParse(aMs.ToString(), out bool bMs) && bMs;
                                _mainForm.WriteScanFile(user, detail);
                                if (!auto) _mainForm.Log($"🗺️ [{user}] {detail}");
                                continue;
                            }

                            // ── auto_nv_end: một nick báo đã hết nhiệm vụ ngày ──
                            // Mắt xích để biết khi nào cả team xong bước nhiệm
                            // vụ. Client chỉ bắn MỘT LẦN cho mỗi lượt bật Auto NV.
                            if (data.TryGetValue("type", out var typeNv)
                                && typeNv.ToString() == "auto_nv_end")
                            {
                                string user = data.TryGetValue("username", out var uNv) ? uNv.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dNv) ? dNv.ToString() : "";
                                _mainForm.RelayAutoNvEnd(user, detail);
                                continue;
                            }

                            // ── tinh_thach_end: một nick báo xong lượt đổi tinh thạch ──
                            if (data.TryGetValue("type", out var typeTt)
                                && typeTt.ToString() == "tinh_thach_end")
                            {
                                string user = data.TryGetValue("username", out var uTt) ? uTt.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dTt) ? dTt.ToString() : "";
                                bool okTt = data.TryGetValue("ok", out var oTt)
                                            && bool.TryParse(oTt.ToString(), out bool bTt) && bTt;
                                _mainForm.RelayTinhThachEnd(user, okTt, detail);
                                continue;
                            }

                            // ── gom đồ: lead báo vị trí / member báo trạng thái ──
                            if (data.TryGetValue("type", out var typeGm)
                                && typeGm.ToString().StartsWith("gom_"))
                            {
                                string t = typeGm.ToString();
                                string user = data.TryGetValue("username", out var uGm) ? uGm.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dGm) ? dGm.ToString() : "";
                                if (t == "gom_lead_at")
                                {
                                    int mGm = data.TryGetValue("map", out var vM)
                                              && int.TryParse(vM.ToString(), out int pM) ? pM : -1;
                                    int zGm = data.TryGetValue("zone", out var vZ)
                                              && int.TryParse(vZ.ToString(), out int pZ) ? pZ : -1;
                                    int xGm = data.TryGetValue("x", out var vX)
                                              && int.TryParse(vX.ToString(), out int pX) ? pX : -1;
                                    int yGm = data.TryGetValue("y", out var vY)
                                              && int.TryParse(vY.ToString(), out int pY) ? pY : -1;
                                    _mainForm.RelayGomLeadAt(user, mGm, zGm, xGm, yGm,
                                        data.TryGetValue("lead", out var lGm) ? lGm.ToString() : "");
                                }
                                else if (t == "gom_mem_ready" || t == "gom_mem_con")
                                    _mainForm.RelayGomInvite(user, detail);
                                else if (t == "gom_mem_done" || t == "gom_loi")
                                    _mainForm.RelayGomMemberDone(user, detail);
                                else if (t == "gom_zone_full")
                                    _mainForm.RelayGomZoneFull(user, detail);
                                else if (t == "gom_lead_luot")
                                    _mainForm.Log($"🎒 [{user}] {detail}");
                                continue;
                            }

                            // ── item_list: bảng mẫu vật phẩm, ghi ra file riêng ──
                            // Không đổ lên giao diện: bảng này hàng nghìn dòng, đổ lên là lấp
                            // sạch log hoạt động.
                            if (data.TryGetValue("type", out var typeIl)
                                && typeIl.ToString() == "item_list")
                            {
                                string user = data.TryGetValue("username", out var uIl) ? uIl.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dIl) ? dIl.ToString() : "";
                                _mainForm.WriteItemListFile(user, detail);
                                // Dòng mở/đóng và dòng lỗi thì ĐỔ LÊN GIAO DIỆN. Toàn bộ bảng thì
                                // không — nó hàng nghìn dòng. Nhưng nếu chỉ ghi lặng vào file thì
                                // lúc hỏng người dùng không biết gì cả, chỉ thấy "đang xuất..." rồi
                                // thôi — đúng chuyện xảy ra lúc 16:00 ngày 03/08.
                                if (detail.StartsWith("=== HET") || detail.StartsWith("LOI:")
                                    || detail.StartsWith("bang ="))
                                    _mainForm.Log($"📦 [{user}] {detail}");
                                continue;
                            }

                            // ── go_exit: kết quả thử đi qua map ──
                            if (data.TryGetValue("type", out var typeGe)
                                && typeGe.ToString() == "go_exit")
                            {
                                string user = data.TryGetValue("username", out var uGe) ? uGe.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dGe) ? dGe.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okGe)
                                          && bool.TryParse(okGe.ToString(), out bool bGe) && bGe;
                                _mainForm.Log($"{(ok ? "🚪" : "🚪❌")} [{user}] {detail}");
                                continue;
                            }

                            // ── quiz_*: module Auto Trả lời câu hỏi NPC Sự kiện ──
                            if (data.TryGetValue("type", out var typeQz) && typeQz.ToString().StartsWith("quiz_"))
                            {
                                string qzType = typeQz.ToString();
                                string user = data.TryGetValue("username", out var uQz) ? uQz.ToString() : Username;

                                if (qzType == "quiz_query")
                                {
                                    string qText = data.TryGetValue("question", out var qObj) ? qObj.ToString() : "";
                                    string ans = QuizManager.Instance.GetCorrectAnswer(qText);
                                    string safeQ = JsonSerializer.Serialize(qText);
                                    string safeA = JsonSerializer.Serialize(ans ?? "");
                                    SendRawJson($"{{\"command\":\"quiz_query_res\",\"question\":{safeQ},\"correctAnswer\":{safeA}}}\n");
                                    if (!string.IsNullOrEmpty(ans))
                                        _mainForm.Log($"🧠 [{user}] Quiz DB hit: \"{qText}\" -> \"{ans}\"");
                                    else
                                        _mainForm.Log($"🧠 [{user}] Quiz DB miss: \"{qText}\" (chờ người dùng chọn...)");
                                }
                                else if (qzType == "quiz_record_correct")
                                {
                                    string qText = data.TryGetValue("question", out var qObj) ? qObj.ToString() : "";
                                    string aText = data.TryGetValue("answer", out var aObj) ? aObj.ToString() : "";
                                    if (QuizManager.Instance.SaveCorrectAnswer(qText, aText))
                                    {
                                        _mainForm.Log($"🧠✨ [{user}] Đã ghi nhớ câu hỏi mới: \"{qText}\" -> \"{aText}\"");
                                    }
                                }
                                else if (qzType == "quiz_status")
                                {
                                    string detail = data.TryGetValue("detail", out var dQz) ? dQz.ToString() : "";
                                    _mainForm.Log($"🧠 [{user}] {detail}");
                                }
                                continue;
                            }

                            // ── follow_*: bám theo lead ──
                            if (data.TryGetValue("type", out var typeFl)
                                && typeFl.ToString().StartsWith("follow_"))
                            {
                                string user = data.TryGetValue("username", out var uFl) ? uFl.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dFl) ? dFl.ToString() : "";
                                string role = data.TryGetValue("extra", out var eFl) ? eFl.ToString() : "";
                                int mapId = data.TryGetValue("map", out var mFl)
                                            && int.TryParse(mFl.ToString(), out int mvFl) ? mvFl : 0;
                                int zoneId = data.TryGetValue("zone", out var zFl)
                                             && int.TryParse(zFl.ToString(), out int zvFl) ? zvFl : -1;
                                int px = data.TryGetValue("x", out var xFl)
                                         && int.TryParse(xFl.ToString(), out int xvFl) ? xvFl : -1;
                                int py = data.TryGetValue("y", out var yFl)
                                         && int.TryParse(yFl.ToString(), out int yvFl) ? yvFl : -1;
                                // tx/ty: toạ độ con quái lead đang đánh. Chỉ lead gửi; -1 = không có.
                                int tx = data.TryGetValue("tx", out var txFl)
                                         && int.TryParse(txFl.ToString(), out int txvFl) ? txvFl : -1;
                                int ty = data.TryGetValue("ty", out var tyFl)
                                         && int.TryParse(tyFl.ToString(), out int tyvFl) ? tyvFl : -1;
                                // tid: mã cá thể của con quái lead đang đánh (a.x.aZ).
                                int tid = data.TryGetValue("tid", out var tidFl)
                                          && int.TryParse(tidFl.ToString(), out int tidvFl) ? tidvFl : -1;

                                if (typeFl.ToString() == "follow_pos")
                                    _mainForm.RelayFollowPos(user, mapId, zoneId, px, py, role, detail, tx, ty, tid);
                                else
                                    _mainForm.Log($"🧲 [{user}] {detail}");
                                continue;
                            }

                            // ── agt_*: Ải gia tộc ──
                            if (data.TryGetValue("type", out var typeAg)
                                && typeAg.ToString().StartsWith("agt_"))
                            {
                                string agType = typeAg.ToString();
                                string user = data.TryGetValue("username", out var uAg) ? uAg.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dAg) ? dAg.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okAg)
                                          && bool.TryParse(okAg.ToString(), out bool bAg) && bAg;

                                if (agType == "agt_opened")
                                {
                                    _mainForm.Log($"🏰🔓 [{user}] {detail}");
                                    _mainForm.NotifyAgtOpened(user);
                                }
                                else if (agType == "agt_dry")
                                {
                                    _mainForm.Log($"🏰🧪 [{user}] {detail}");
                                }
                                else if (agType == "agt_in_gate")
                                {
                                    int mAg = data.TryGetValue("map", out var mvAg)
                                              && int.TryParse(mvAg.ToString(), out int mviAg) ? mviAg : -1;
                                    _mainForm.Log($"🏰 [{user}] {detail}");
                                    if (mAg >= 0) _mainForm.NotifyAgtInGate(user, mAg);
                                }
                                else if (agType == "agt_end")
                                {
                                    _mainForm.Log($"{(ok ? "🏰✅" : "🏰❌")} [{user}] {detail}");
                                    // Nick nào ra khỏi ải thì nick đó thôi bám. Nick cuối ra là
                                    // tuyến rỗng — AgtFollowStop dọn nốt phần còn lại.
                                    _mainForm.AgtFollowStop();
                                }
                                else
                                {
                                    _mainForm.Log($"🏰 [{user}] {detail}");
                                }
                                continue;
                            }

                            // ── son_cap_*: hoạt động Sơn cáp (gom nhóm + tập kết) ──
                            if (data.TryGetValue("type", out var typeSc)
                                && typeSc.ToString().StartsWith("son_cap_"))
                            {
                                string scType = typeSc.ToString();
                                string user = data.TryGetValue("username", out var uSc) ? uSc.ToString() : Username;
                                string detail = data.TryGetValue("detail", out var dSc) ? dSc.ToString() : "";
                                string role = data.TryGetValue("extra", out var eSc) ? eSc.ToString() : "";
                                bool ok = data.TryGetValue("ok", out var okSc)
                                          && bool.TryParse(okSc.ToString(), out bool bSc) && bSc;
                                int mapId = data.TryGetValue("map", out var mSc)
                                            && int.TryParse(mSc.ToString(), out int mvSc) ? mvSc : 0;
                                int zoneId = data.TryGetValue("zone", out var zSc)
                                             && int.TryParse(zSc.ToString(), out int zvSc) ? zvSc : -1;
                                int px = data.TryGetValue("x", out var xSc)
                                         && int.TryParse(xSc.ToString(), out int xvSc) ? xvSc : -1;
                                int py = data.TryGetValue("y", out var ySc)
                                         && int.TryParse(ySc.ToString(), out int yvSc) ? yvSc : -1;

                                if (scType == "son_cap_zone")
                                {
                                    _mainForm.RelaySonCapZone(user, mapId, zoneId, role, px, py);
                                }
                                else if (scType == "son_cap_ready")
                                {
                                    _mainForm.UpdateSonCapPos(user, mapId, zoneId, ok, px, py);
                                }
                                else if (scType == "son_cap_end")
                                {
                                    _mainForm.Log($"{(ok ? "🪢✅" : "🪢❌")} [{user}] {detail}");
                                    // Trưởng nhóm chốt phiên thì báo member NGAY, xong hay hỏng đều báo.
                                    // Bài học từ Cấm thuật: chỉ báo khi hỏng thì đường kết thúc bình
                                    // thường lại là đường member không ai báo, đứng chờ tới hết giờ.
                                    if (role == "leader") _mainForm.NotifySonCapLeaderEnd(user, ok);
                                }
                                else if (scType == "son_cap_progress")
                                {
                                    _mainForm.Log($"🪢 [{user}] {detail}");
                                }
                                else if (scType == "son_cap_dry")
                                {
                                    // Chạy nháp: trưởng nhóm dừng trước cú bấm. Phải báo member
                                    // thôi chờ, không thì cả nhóm đứng tới khi hết hạn 300s.
                                    _mainForm.Log($"🪢🧪 CHẠY NHÁP [{user}] {detail}");
                                    _mainForm.NotifySonCapLeaderEnd(user, true);
                                }
                                else if (scType == "son_cap_zone_full")
                                {
                                    // Member không chen được vào khu của trưởng nhóm (khu đủ 15
                                    // người). Chuyển cho trưởng nhóm dời khu — member KHÔNG bỏ cuộc.
                                    int wzSc = data.TryGetValue("want_zone", out var wSc)
                                               && int.TryParse(wSc.ToString(), out int wvSc) ? wvSc : -1;
                                    _mainForm.RelaySonCapZoneFull(user, detail, wzSc);
                                }
                                else if (scType == "son_cap_in")
                                {
                                    _mainForm.NotifySonCapInFloor(user, mapId);
                                }
                                else if (scType == "son_cap_out")
                                {
                                    _mainForm.NotifySonCapOutFloor(user);
                                }
                                else
                                {
                                    _mainForm.Log($"{(ok ? "🪢✅" : "🪢⏳")} [{user}] {detail}");
                                }
                                continue;
                            }

                            // ── scan_npc_result: Hiển thị danh sách NPC ──
                            if (data.TryGetValue("type", out var typeSnr) && typeSnr.ToString() == "scan_npc_result")
                            {
                                string user = data.TryGetValue("username", out var uSnr) ? uSnr.ToString() : Username;
                                if (data.TryGetValue("npcs", out var npcsObj) && npcsObj is JsonElement npcsEl && npcsEl.ValueKind == JsonValueKind.Array)
                                {
                                    _mainForm.Log($"🔍 [{user}] NPC list ({npcsEl.GetArrayLength()}):");
                                    foreach (var npc in npcsEl.EnumerateArray())
                                    {
                                        string npcName = npc.TryGetProperty("name", out var nN) ? nN.GetString() : "?";
                                        string npcId = npc.TryGetProperty("id", out var nI) ? nI.ToString() : "?";
                                        string tplId = npc.TryGetProperty("templateId", out var nT) ? nT.ToString() : "?";
                                        string npcHp = npc.TryGetProperty("hp", out var nH) ? nH.ToString() : "?";
                                        string npcX = npc.TryGetProperty("x", out var nX) ? nX.ToString() : "?";
                                        string npcY = npc.TryGetProperty("y", out var nY) ? nY.ToString() : "?";
                                        _mainForm.Log($"   NPC: {npcName} (id={npcId} tpl={tplId} hp={npcHp}) @ ({npcX},{npcY})");
                                    }
                                }
                                else
                                {
                                    _mainForm.Log($"🔍 [{user}] Không tìm thấy NPC nào");
                                }
                                // ── Display MOBs ──
                                if (data.TryGetValue("mobs", out var mobsObj) && mobsObj is JsonElement mobsEl && mobsEl.ValueKind == JsonValueKind.Array && mobsEl.GetArrayLength() > 0)
                                {
                                    _mainForm.Log($"🐾 [{user}] MOB list ({mobsEl.GetArrayLength()}):");
                                    foreach (var mob in mobsEl.EnumerateArray())
                                    {
                                        string mobName = mob.TryGetProperty("name", out var mn) ? mn.GetString() : "";
                                        string mobId = mob.TryGetProperty("id", out var mi) ? mi.ToString() : "?";
                                        string mobTpl = mob.TryGetProperty("tplId", out var mt) ? mt.ToString() : "?";
                                        string mobHp = mob.TryGetProperty("hp", out var mh) ? mh.ToString() : "?";
                                        string mobX = mob.TryGetProperty("x", out var mx) ? mx.ToString() : "?";
                                        string mobY = mob.TryGetProperty("y", out var my) ? my.ToString() : "?";
                                        string vec = mob.TryGetProperty("vec", out var mv) ? mv.GetString() : "?";
                                        _mainForm.Log($"   MOB: {(string.IsNullOrEmpty(mobName) ? "[noname]" : mobName)} (id={mobId} tpl={mobTpl} hp={mobHp}) @ ({mobX},{mobY}) [z.{vec}]");
                                    }
                                }
                                continue;
                            }

                            // ── deep_scan_result: Hiển thị tất cả entity vectors ──
                            if (data.TryGetValue("type", out var typeDsr) && typeDsr.ToString() == "deep_scan_result")
                            {
                                string user = data.TryGetValue("username", out var uDsr) ? uDsr.ToString() : Username;
                                if (data.TryGetValue("data", out var dataObj) && dataObj is JsonElement dataEl)
                                {
                                    if (dataEl.TryGetProperty("fields", out var fieldsEl) && fieldsEl.ValueKind == JsonValueKind.Array)
                                    {
                                        _mainForm.Log($"🔬 [{user}] Deep scan - {fieldsEl.GetArrayLength()} vector fields:");
                                        foreach (var field in fieldsEl.EnumerateArray())
                                        {
                                            string fName = field.TryGetProperty("field", out var fn) ? fn.GetString() : "?";
                                            string eClass = field.TryGetProperty("elementClass", out var ec) ? ec.GetString() : "?";
                                            int size = field.TryGetProperty("size", out var sz) ? sz.GetInt32() : 0;
                                            _mainForm.Log($"   📦 z.{fName} [{eClass}] size={size}:");

                                            if (field.TryGetProperty("items", out var items) && items.ValueKind == JsonValueKind.Array)
                                            {
                                                foreach (var item in items.EnumerateArray())
                                                {
                                                    // Tìm tên NPC (tplName hoặc l)
                                                    string name = "";
                                                    if (item.TryGetProperty("tplName", out var tn)) name = tn.GetString();
                                                    else if (item.TryGetProperty("l", out var ln)) name = ln.GetString();

                                                    string hp = item.TryGetProperty("tplHp", out var th) ? th.ToString() : "?";
                                                    string ah = item.TryGetProperty("ah", out var ahv) ? ahv.ToString() : "?";
                                                    string aZ = item.TryGetProperty("aZ", out var azv) ? azv.ToString() : "?";
                                                    string ar = item.TryGetProperty("ar", out var arv) ? arv.ToString() : "?";
                                                    string asf = item.TryGetProperty("as", out var asv) ? asv.ToString() : "?";

                                                    if (!string.IsNullOrEmpty(name))
                                                    {
                                                        _mainForm.Log($"      → {name} (tpl={ah} id={aZ} hp={hp}) @ ({ar},{asf})");
                                                    }
                                                    else
                                                    {
                                                        // Log raw nếu không có tên
                                                        _mainForm.Log($"      → [unnamed] tpl={ah} id={aZ} @ ({ar},{asf})");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else if (dataEl.TryGetProperty("error", out var errEl))
                                    {
                                        _mainForm.Log($"🔬 [{user}] Deep scan error: {errEl.GetString()}");
                                    }
                                }
                                continue;
                            }

                            // ── search_npc_result: Kết quả tìm NPC theo keyword ──
                            if (data.TryGetValue("type", out var typeSnr2) && typeSnr2.ToString() == "search_npc_result")
                            {
                                string user = data.TryGetValue("username", out var uSnr2) ? uSnr2.ToString() : Username;
                                if (data.TryGetValue("data", out var sData) && sData is JsonElement sDataEl)
                                {
                                    string kw = sDataEl.TryGetProperty("keyword", out var kwEl) ? kwEl.GetString() : "?";
                                    if (sDataEl.TryGetProperty("results", out var resEl) && resEl.ValueKind == JsonValueKind.Array)
                                    {
                                        _mainForm.Log($"🔎 [{user}] Search '{kw}' → {resEl.GetArrayLength()} kết quả:");
                                        foreach (var r in resEl.EnumerateArray())
                                        {
                                            string fld = r.TryGetProperty("f", out var rf) ? rf.GetString() : "?";
                                            string cls = r.TryGetProperty("c", out var rc) ? rc.GetString() : "?";
                                            string mtch = r.TryGetProperty("m", out var rm) ? rm.GetString() : "?";
                                            string idx = r.TryGetProperty("i", out var ri) ? ri.ToString() : (r.TryGetProperty("k", out var rk) ? rk.GetString() : "?");
                                            // Dump all fields
                                            string dump = "";
                                            if (r.TryGetProperty("d", out var rd) && rd.ValueKind == JsonValueKind.Object)
                                            {
                                                var parts = new List<string>();
                                                foreach (var prop in rd.EnumerateObject())
                                                {
                                                    parts.Add($"{prop.Name}={prop.Value}");
                                                }
                                                dump = string.Join(" | ", parts);
                                            }
                                            _mainForm.Log($"   🎯 z.{fld}[{idx}] ({cls}) match='{mtch}'");
                                            _mainForm.Log($"      {dump}");
                                        }
                                        if (resEl.GetArrayLength() == 0)
                                            _mainForm.Log($"   ⚠ Không tìm thấy entity nào chứa '{kw}'");
                                    }
                                    else if (sDataEl.TryGetProperty("error", out var errEl2))
                                    {
                                        _mainForm.Log($"🔎 [{user}] Search error: {errEl2.GetString()}");
                                    }
                                }
                                continue;
                            }

                            // ── search_hp_result: Entity với HP lớn ──
                            if (data.TryGetValue("type", out var typeHp) && typeHp.ToString() == "search_hp_result")
                            {
                                string user = data.TryGetValue("username", out var uHp) ? uHp.ToString() : Username;
                                if (data.TryGetValue("data", out var hpData) && hpData is JsonElement hpEl)
                                {
                                    string target = hpEl.TryGetProperty("target", out var tgt) ? tgt.ToString() : "?";
                                    if (hpEl.TryGetProperty("results", out var hpRes) && hpRes.ValueKind == JsonValueKind.Array)
                                    {
                                        _mainForm.Log($"💪 [{user}] HP≥{target} → {hpRes.GetArrayLength()} kết quả:");
                                        foreach (var r in hpRes.EnumerateArray())
                                        {
                                            string fld = r.TryGetProperty("f", out var rf) ? rf.GetString() : "?";
                                            string cls = r.TryGetProperty("c", out var rc) ? rc.GetString() : "?";
                                            string hp = r.TryGetProperty("hp", out var rh) ? rh.GetString() : "?";
                                            string idx = r.TryGetProperty("i", out var ri) ? ri.ToString() : "?";
                                            string dump = "";
                                            if (r.TryGetProperty("d", out var rd) && rd.ValueKind == JsonValueKind.Object)
                                            {
                                                var parts = new List<string>();
                                                foreach (var prop in rd.EnumerateObject())
                                                    parts.Add($"{prop.Name}={prop.Value}");
                                                dump = string.Join(" | ", parts);
                                            }
                                            _mainForm.Log($"   💪 z.{fld}[{idx}] ({cls}) hp='{hp}'");
                                            _mainForm.Log($"      {dump}");
                                        }
                                        if (hpRes.GetArrayLength() == 0)
                                            _mainForm.Log($"   ⚠ Không tìm thấy entity nào HP≥{target}");
                                    }
                                }
                                continue;
                            }

                            if (data.TryGetValue("username", out var uObj))
                                Username = uObj.ToString();
                            string status = data.TryGetValue("status", out var sObj) ? sObj.ToString() : "N/A";
                            string level = data.TryGetValue("level", out var lObj) ? lObj.ToString() : "0";
                            string charNameVal = data.TryGetValue("charName", out var cnObj) ? cnObj.ToString() : "";
                            string charInfo = !string.IsNullOrEmpty(charNameVal) ? $"Lv.{level} = {charNameVal}" : (level != "0" ? $"Lv.{level}" : "");
                            string task = data.TryGetValue("task", out var tskObj) ? tskObj.ToString() : "";

                            if (!string.IsNullOrEmpty(Username))
                            {
                                _mainForm.UpdateAccountUI(Username, status, charInfo, task);

                                // Trạng thái login cho khởi chạy tuần tự.
                                // hp gửi dạng "hp/maxHp" → tách lấy maxHp làm bằng chứng
                                // dữ liệu nhân vật đã về thật, không chỉ có level mặc định.
                                int levelInt = 0;
                                int.TryParse(level, out levelInt);

                                string hpStr = data.TryGetValue("hp", out var hpObj) ? hpObj.ToString() : "";
                                int maxHp = 0;
                                int slashPos = hpStr.IndexOf('/');
                                if (slashPos >= 0 && slashPos + 1 < hpStr.Length)
                                    int.TryParse(hpStr.Substring(slashPos + 1).Trim(), out maxHp);

                                _mainForm.NotifyAccountLogin(Username, levelInt, charNameVal, maxHp);

                                // Tự gửi AFK config cho client mới kết nối (1 lần)
                                if (!_afkConfigSent)
                                {
                                    _afkConfigSent = true;
                                    _mainForm.SendAfkConfigToSession(this);
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        _mainForm.UpdateAccountUI("Unknown", "Error JSON", ex.Message);
                    }
                }
            }
            catch (Exception) { }
            finally
            {
                Close();
                _mainForm.RemoveSession(this);
                if (!string.IsNullOrEmpty(Username))
                    _mainForm.RemoveAccountUI(Username);
            }
        }

        public void SendCommand(string command)
        {
            try
            {
                var data = new Dictionary<string, string> { { "command", command } };
                string json = JsonSerializer.Serialize(data) + "\n";
                byte[] bytes = Encoding.UTF8.GetBytes(json);
                System.Diagnostics.Debug.WriteLine($"[TCP] Sending {bytes.Length} bytes: {json.Trim()}");
                _stream.Write(bytes, 0, bytes.Length);
                _stream.Flush();
                System.Diagnostics.Debug.WriteLine($"[TCP] Send + Flush OK");
            }
            catch (Exception ex)
            {
                throw new Exception($"TCP write failed: {ex.Message}");
            }
        }

        public void SendCommandJson(string json)
        {
            try
            {
                byte[] bytes = Encoding.UTF8.GetBytes(json + "\n");
                _stream.Write(bytes, 0, bytes.Length);
                _stream.Flush();
            }
            catch (Exception ex)
            {
                throw new Exception($"TCP write failed: {ex.Message}");
            }
        }

        public void SendCommandWithData(string command, string key, int value)
        {
            try
            {
                string json = $"{{\"command\":\"{command}\",\"{key}\":{value}}}\n";
                byte[] bytes = Encoding.UTF8.GetBytes(json);
                _stream.Write(bytes, 0, bytes.Length);
                _stream.Flush();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[TCP] SendCommandWithData error: {ex.Message}");
            }
        }

        /// <summary>
        /// Gửi một dòng JSON xuống client. TRẢ VỀ false nếu không ghi được.
        ///
        /// Trước đây hàm này trả void và nuốt lỗi vào `Console.WriteLine` — mà Manager chạy dạng
        /// WinForms thì không ai thấy Console. Hậu quả không phải "mất một lệnh": nó làm chỗ gọi
        /// TƯỞNG đã gửi xong. Đường nhập mã bùa uế thổ vì thế báo lên Telegram "đã gõ mã vào
        /// game" kể cả khi socket đã đứt — người dùng đọc dòng đó rồi ngồi chờ một việc không hề
        /// xảy ra, trong khi nick nằm chết vô thời hạn (bùa KHÔNG tự hết).
        ///
        /// Trả bool chứ không ném: 40+ chỗ gọi khác đều là lệnh "gửi được thì tốt", không chỗ nào
        /// chịu nổi một ngoại lệ bay ra giữa vòng xử lý gói. Chỗ nào cần chắc thì tự hỏi kết quả.
        /// </summary>
        public bool SendRawJson(string json)
        {
            try
            {
                byte[] bytes = Encoding.UTF8.GetBytes(json);
                _stream.Write(bytes, 0, bytes.Length);
                _stream.Flush();
                return true;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[TCP] SendRawJson error: {ex.Message}");
                return false;
            }
        }

        public void Close()
        {
            _stream?.Close();
            _client?.Close();
        }
    }
}
