using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    /// <summary>
    /// THEO DÕI TỪNG NICK + BẮN TIN TELEGRAM.
    ///
    /// Tách hẳn khỏi Form1.cs vì đây là lớp QUAN SÁT: nó chỉ đọc các gói client vốn đã gửi
    /// về, không ra lệnh gì cho client. Ghép vào Form1.cs là lẫn với phần điều khiển, sau
    /// này sửa một bên dễ đụng bên kia.
    ///
    /// Chỉ móc vào MỘT chỗ: ClientSession nhận được gói nào cũng gọi TheoDoi(). Nhờ vậy
    /// thêm loại sự kiện mới bên mod là bảng tự có, không phải sửa rải rác từng nhánh if.
    /// KHÔNG bóc chữ từ dòng log — log là để người đọc, đổi câu chữ lúc nào cũng được;
    /// bám vào "type" của gói mới là bám vào thứ có hợp đồng.
    /// </summary>
    public partial class Form1
    {
        // ── Trạng thái quan sát được của một nick ──────────────────────────
        private class TrangThaiNick
        {
            public string Username = "";
            public string Team = "";
            public string HoatDong = "";      // Nhiệm vụ / Địa cung / Cấm thuật / Gom đồ …
            public string Buoc = "";          // mô tả bước đang ở
            public int    LuotGom = 0;
            public readonly List<string> DaGui = new List<string>();   // món nick này giao đi
            public readonly List<string> DaNhan = new List<string>();  // món nick này nhận về
            public DateTime Luc = DateTime.Now;
        }

        private readonly Dictionary<string, TrangThaiNick> _theoDoi =
            new Dictionary<string, TrangThaiNick>(StringComparer.OrdinalIgnoreCase);

        // username -> tên team, dựng lại mỗi khi đọc doi_hinh.cfg.
        private Dictionary<string, string> _teamCuaNick =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        private TelegramBot _tele;
        private System.Windows.Forms.Timer _teleTimer;

        // Nick nhận đồ của lượt gom đang chạy — để quy "món mem giao" thành "món lead nhận".
        private string _gomNhanDo = "";

        // ── Khởi động / dọn ────────────────────────────────────────────────
        private void KhoiDongTheoDoi()
        {
            // Khởi động bộ lập lịch hẹn giờ
            _scheduler = new TaskScheduler();
            _scheduler.Start(
                async cmdLine =>
                {
                    string result = "";
                    await XuLyLenhTelegramAsync(cmdLine, r => result = r);
                    return result;
                },
                Log,
                msg => _tele?.Gui(msg));

            TelegramCauHinh.TaoMauNeuThieu();
            var cf = TelegramCauHinh.Doc();
            _tele = new TelegramBot(cf, Log);

            if (!cf.Bat)
            {
                Log($"📨 Telegram: TẮT — chưa điền token trong {TelegramCauHinh.DuongDan()}. "
                  + "Lấy token: nhắn @BotFather trên Telegram → /newbot. chat_id để trống cũng được, bot tự dò.");
                return;
            }

            _ = _tele.KiemTraAsync();

            // ĐỌC TIN NHẮN TỪ NHÓM / CHAT:
            // 1. Reply tin ảnh captcha -> giải bùa uế thổ
            // 2. Tin nhắn lệnh (/agt, /nv, /hengio, /team, ...) -> điều khiển Manager
            _tele.BatDauDocTinNhan(
                (replyToId, text) => NhanMaCaptchaTuTele(replyToId, text),
                (cmdText, repCallback) => _ = XuLyLenhTelegramAsync(cmdText, repCallback));

            if (cf.BangTrangThai)
            {
                _teleTimer = new System.Windows.Forms.Timer { Interval = Math.Max(5, cf.BangGiay) * 1000 };
                _teleTimer.Tick += async (s, e) =>
                {
                    try { await _tele.CapNhatBangAsync(DungBangTrangThai()); }
                    catch (Exception ex) { Log($"⚠ Telegram bảng lỗi: {ex.Message}"); }
                };
                _teleTimer.Start();
            }
        }

        private void DungTheoDoi()
        {
            try { _scheduler?.Dispose(); } catch { }
            try { _teleTimer?.Stop(); } catch { }
            try { _tele?.Dispose(); } catch { }
        }

        private void NapTeamCuaNick()
        {
            var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                foreach (var kv in LoadTeams())
                    foreach (var u in kv.Value)
                        if (!map.ContainsKey(u)) map[u] = kv.Key;
            }
            catch (Exception) { /* file đội hình hỏng thì để trống cột team, không chết */ }
            _teamCuaNick = map;
        }

        private string TeamCuaNick(string username)
        {
            if (string.IsNullOrEmpty(username)) return "";
            if (_teamCuaNick.Count == 0) NapTeamCuaNick();
            return _teamCuaNick.TryGetValue(username, out var t) ? t : "";
        }

        private TrangThaiNick Lay(string username)
        {
            if (!_theoDoi.TryGetValue(username, out var t))
            {
                t = new TrangThaiNick { Username = username, Team = TeamCuaNick(username) };
                _theoDoi[username] = t;
            }
            if (t.Team.Length == 0) t.Team = TeamCuaNick(username);
            return t;
        }

        // ── Cửa vào duy nhất: mọi gói client gửi về đều đi qua đây ─────────
        /// <summary>
        /// Ghi nhận một gói client vừa gửi. Gọi từ ClientSession trước khi phân nhánh xử lý.
        /// Chỉ ĐỌC — không được đổi hành vi của Manager theo bất cứ đường nào, vì mất một gói
        /// ở đây chỉ được phép mất một dòng thống kê, không phải mất một lượt chạy.
        /// </summary>
        internal void TheoDoi(string usernameSession, Dictionary<string, object> data)
        {
            try
            {
                if (data == null) return;
                if (!data.TryGetValue("type", out var tObj)) return;
                string type = tObj?.ToString() ?? "";
                if (type.Length == 0) return;

                string user = data.TryGetValue("username", out var uObj) && uObj != null
                            ? uObj.ToString() : usernameSession;
                if (string.IsNullOrEmpty(user)) return;

                string detail = data.TryGetValue("detail", out var dObj) && dObj != null ? dObj.ToString() : "";
                string mon    = data.TryGetValue("mon", out var mObj) && mObj != null ? mObj.ToString() : "";

                string hoatDong = HoatDongCua(type);
                if (hoatDong.Length == 0) return;   // gói chẩn đoán (scan/pos) — không vào bảng

                var t = Lay(user);
                t.HoatDong = hoatDong;
                t.Buoc = detail.Length > 0 ? detail : type;
                t.Luc = DateTime.Now;

                if (type.StartsWith("gom_", StringComparison.Ordinal))
                    GhiNhanGom(t, type, detail, mon);

                if (_tele != null && _tele.Bat && _tele.CauHinh.TinLoi && LaLoi(type))
                    _tele.Gui($"⚠️ <b>{TelegramBot.Esc(user)}</b>"
                            + (t.Team.Length > 0 ? $" (team {TelegramBot.Esc(t.Team)})" : "")
                            + $" — {TelegramBot.Esc(hoatDong)}: {TelegramBot.Esc(detail.Length > 0 ? detail : type)}");
            }
            catch (Exception) { /* theo dõi hỏng không được phép làm hỏng vòng đọc gói */ }
        }

        private void GhiNhanGom(TrangThaiNick t, string type, string detail, string mon)
        {
            if (type == "gom_mem_con" || type == "gom_mem_done")
            {
                t.LuotGom++;
                if (mon.Length > 0)
                {
                    t.DaGui.Add(mon);

                    // Món mem giao đi CHÍNH LÀ món lead nhận về — lead không tự đếm được
                    // vì nó chỉ mở cửa sổ chờ, món nằm bên phía mem.
                    if (_gomNhanDo.Length > 0
                        && !string.Equals(_gomNhanDo, t.Username, StringComparison.OrdinalIgnoreCase))
                        Lay(_gomNhanDo).DaNhan.Add($"{t.Username}: {mon}");
                }

                if (_tele != null && _tele.Bat && _tele.CauHinh.TinGiaoDich)
                    _tele.Gui($"🎒 <b>{TelegramBot.Esc(t.Username)}</b>"
                            + (t.Team.Length > 0 ? $" (team {TelegramBot.Esc(t.Team)})" : "")
                            + $" → <b>{TelegramBot.Esc(_gomNhanDo.Length > 0 ? _gomNhanDo : "lead")}</b>\n"
                            + (mon.Length > 0 ? $"📦 {TelegramBot.Esc(mon)}\n" : "📦 (không đọc được tên món)\n")
                            + $"<i>{TelegramBot.Esc(detail)}</i>");
            }
        }

        /// <summary>Nhóm loại gói về tên hoạt động người đọc hiểu được. Rỗng = không vào bảng.</summary>
        private static string HoatDongCua(string type)
        {
            if (type.StartsWith("gom_", StringComparison.Ordinal))        return "Gom đồ";
            if (type.StartsWith("cam_thuat", StringComparison.Ordinal))   return "Cấm thuật";
            if (type.StartsWith("son_cap", StringComparison.Ordinal))     return "Sơn cáp";
            if (type.StartsWith("agt_", StringComparison.Ordinal))        return "Ải gia tộc";
            if (type.StartsWith("dai_hoi", StringComparison.Ordinal))     return "Đại hội";
            if (type.StartsWith("dia_cung", StringComparison.Ordinal))    return "Địa cung";
            if (type.StartsWith("tinh_thach", StringComparison.Ordinal))  return "Tinh thạch";
            if (type.StartsWith("follow_", StringComparison.Ordinal))     return "Bám lead";
            if (type == "auto_nv_end")                                    return "Nhiệm vụ";
            if (type == "go_exit")                                        return "Di chuyển";
            return "";
        }

        private static bool LaLoi(string type)
        {
            return type.EndsWith("_loi", StringComparison.Ordinal)
                || type.EndsWith("_zone_full", StringComparison.Ordinal)
                || type.EndsWith("_dry", StringComparison.Ordinal);
        }

        // ── Lượt gom: gọi từ phần điều khiển ───────────────────────────────

        /// <summary>Việc cần người can thiệp: login hỏng hẳn, chọn sai server.</summary>
        internal void TeleLoi(string htmlNoiDung)
        {
            if (_tele == null || !_tele.Bat || !_tele.CauHinh.TinLoi) return;
            _tele.Gui($"❌ {htmlNoiDung}");
        }

        // ══════════════════════════════════════════════════════════════════
        // BÙA UẾ THỔ — người chơi khác khoá nick lại bằng một bảng captcha
        // ══════════════════════════════════════════════════════════════════
        //
        // Bùa KHÔNG tự hết: đã dính là nick nằm chết tới khi có người nhập đúng mã captcha,
        // hoặc tới khi tắt hẳn client. ("5 phút" là hạn dùng của lá bùa bên kẻ yểm, không phải
        // hạn chịu của nạn nhân.) Mà nằm chết thì KHÔNG sinh gói sự kiện nào — trên bảng theo
        // dõi nó im hệt nick đang cày ngon. Cả cơ chế ở đây tồn tại để rút ngắn đường từ "game
        // dựng bảng captcha" tới "người thật đọc được mã", vì không ai rút hộ được.
        //
        // Tool KHÔNG giải ảnh. Nó chuyển ảnh cho người thật xem rồi gõ hộ câu trả lời của người
        // đó — làm bàn phím nối dài, không làm cái đầu.

        /// <summary>
        /// Tên hiện trên Telegram: tên nhân vật nếu đã biết, không thì username.
        /// Người xem nhớ tên nhân vật chứ không nhớ username.
        /// </summary>
        private string TenHienThi(string username)
        {
            if (string.IsNullOrEmpty(username)) return "";
            string ch = GetCharName(username);
            return ch.Length > 0 ? ch : username;
        }

        // message_id của tin ẢNH captcha -> (nick, lúc gửi). Đây là toàn bộ cơ chế định tuyến:
        // người dùng reply vào tin nào thì mã đó thuộc nick ấy, không cần gõ tên, hai nick dính
        // bùa cùng lúc cũng không lẫn.
        //
        // GIỮ MỤC CHỜ SAU KHI ĐÃ NHẬN MỘT MÃ, không xoá ngay. Bản đầu xoá ngay với lý do "một mã,
        // một lần bấm" — nhưng đo thật cho thấy gõ sai thì game GIỮ NGUYÊN mã cũ và chỉ hiện
        // "Mã captcha không chính xác". Nghĩa là đường sửa sai đúng đắn là đọc lại chính tấm ảnh
        // đó rồi reply tiếp; mà mục chờ đã bị xoá thì reply lần hai rơi vào hư không — người dùng
        // hết cách, phải vào game gõ tay. Xoá khi bùa được GIẢI, không phải khi nhận mã.
        private readonly Dictionary<int, (string User, DateTime Luc)> _buaChoMa =
            new Dictionary<int, (string, DateTime)>();

        /// <summary>Bỏ mọi mục chờ của một nick — gọi khi nick đó đã giải bùa hoặc đã bỏ cuộc.</summary>
        private void DonChoMaCuaNick(string user)
        {
            foreach (var k in _buaChoMa.Where(x =>
                         string.Equals(x.Value.User, user, StringComparison.OrdinalIgnoreCase))
                     .Select(x => x.Key).ToList())
                _buaChoMa.Remove(k);
        }

        /// <summary>
        /// Nick bị yểm VÀ đọc được ảnh captcha → đẩy ảnh lên nhóm, chờ người dùng reply mã.
        ///
        /// Tool không giải ảnh. Nó chuyển ảnh cho người thật xem rồi gõ hộ câu trả lời của người
        /// đó — làm bàn phím nối dài, không làm cái đầu.
        /// </summary>
        internal void TeleBuaCaptcha(string user, string noiDung, byte[] png)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => TeleBuaCaptcha(user, noiDung, png))); return; }
            string ten = TenHienThi(user);
            Log($"🧿 [{user}] BỊ YỂM BÙA UẾ THỔ — {noiDung} (ảnh {png.Length} byte)");
            if (_tele == null || !_tele.Bat) return;

            string caption = $"🧿 <b>{TelegramBot.Esc(ten)}</b> bị yểm <b>bùa uế thổ</b>"
                           + $"\n<i>{TelegramBot.Esc(CatNgan(noiDung, 150))}</i>"
                           + "\n\n<b>Trả lời (reply) tin này bằng mã trong ảnh.</b>"
                           + "\n<i>Bùa KHÔNG tự hết: không nhập mã thì nick nằm chết mãi,"
                           + " trừ khi tắt hẳn client. Gõ sai cứ reply lại — mã không đổi.</i>";
            _ = Task.Run(async () =>
            {
                int id = await _tele.GuiAnhLayIdAsync(png, caption);
                if (id > 0)
                {
                    BeginInvoke(new Action(() =>
                    {
                        // Dọn mục QUÁ CŨ — 24 giờ, không phải 8 phút.
                        //
                        // Bản đầu để 8 phút vì tưởng bùa tự hết sau 5 phút. SAI: bùa yểm rồi thì
                        // nạn nhân đứng đó tới khi có người nhập đúng mã, không có đồng hồ nào
                        // đếm ngược cả — chỉ tắt hẳn client mới thoát. Nên một nick dính bùa lúc
                        // 8h sáng mà 9h mới có người rảnh đọc ảnh vẫn phải reply được. Dọn 8
                        // phút là tự tay cắt đúng đường cứu duy nhất, mà lại cắt LẶNG LẼ (reply
                        // rơi vào nhánh TryGetValue trượt rồi return, không một dòng log).
                        //
                        // 24 giờ vì Telegram cũng chỉ giữ bản cập nhật chừng đó — quá hạn thì
                        // reply có gõ cũng không tới nơi được nữa. Vẫn đủ chặn phình bộ nhớ:
                        // đường bình thường đã xoá mục lúc bùa được giải (bua_ue_tho_het), ở đây
                        // chỉ còn lại mấy mục mồ côi vì client bị tắt cứng.
                        foreach (var k in _buaChoMa
                                     .Where(x => (DateTime.Now - x.Value.Luc).TotalHours > 24)
                                     .Select(x => x.Key).ToList())
                            _buaChoMa.Remove(k);
                        _buaChoMa[id] = (user, DateTime.Now);
                        Log($"🧿 [{user}] đã gửi ảnh captcha lên Telegram (tin #{id}) — chờ reply mã");
                    }));
                }
                else
                {
                    BeginInvoke(new Action(() =>
                        Log($"🧿⚠️ [{user}] gửi ảnh captcha THẤT BẠI — vào game nhập mã bằng tay")));
                }
            });
        }

        /// <summary>Người dùng reply mã trên Telegram → chuyển xuống đúng client.</summary>
        internal void NhanMaCaptchaTuTele(int replyToId, string ma)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => NhanMaCaptchaTuTele(replyToId, ma))); return; }
            if (!_buaChoMa.TryGetValue(replyToId, out var muc)) return;   // reply vào tin khác
            string user = muc.User;
            ma = (ma ?? "").Trim();
            if (ma.Length == 0) return;
            // KHÔNG xoá mục chờ ở đây — xem chú thích ở _buaChoMa. Gõ sai thì game giữ nguyên mã
            // cũ, nên đường sửa sai là reply tiếp vào chính tin ảnh này; xoá mục là chặn mất đúng
            // đường đó. Mục được dọn khi nick báo đã giải bùa (hoặc bỏ cuộc), và tự hết hạn sau 8'.
            string ten = TenHienThi(user);
            var ss = FindSession(user);
            if (ss == null)
            {
                // Mục chờ VẪN GIỮ: client vào lại là reply tiếp ăn ngay, không phải gõ tay.
                Log($"🧿⚠️ [{user}] có mã '{ma}' nhưng client đã ngắt kết nối");
                _tele?.Gui($"🧿⚠️ <b>{TelegramBot.Esc(ten)}</b> — client đang ngắt kết nối nên chưa gõ được"
                         + $" mã <b>{TelegramBot.Esc(ma)}</b>. Client vào lại thì reply tin ảnh đó thêm lần nữa.");
                return;
            }
            // HỎI KẾT QUẢ GHI, đừng tin là xong. Socket đứt mà phiên chưa kịp bị gỡ khỏi danh
            // sách thì FindSession vẫn trả về phiên đó — báo "đã gõ mã vào game" lúc này là nói
            // dối người đang ngồi chờ, mà bùa thì không tự hết để cứu vãn.
            if (!ss.SendRawJson($"{{\"command\":\"bua_ma\",\"ma\":\"{EscapeJson(ma)}\"}}\n"))
            {
                Log($"🧿⚠️ [{user}] GỬI HỎNG mã '{ma}' xuống client — đường truyền đứt");
                _tele?.Gui($"🧿⚠️ <b>{TelegramBot.Esc(ten)}</b> — <b>không gửi được</b> mã"
                         + $" <b>{TelegramBot.Esc(ma)}</b> xuống client (đường truyền đứt)."
                         + " Reply lại, hoặc vào game gõ tay.");
                return;
            }
            Log($"🧿⌨️ [{user}] nhận mã '{ma}' từ Telegram → đã gửi xuống client");
            _tele?.Gui($"🧿⌨️ <b>{TelegramBot.Esc(ten)}</b> — đã gõ mã <b>{TelegramBot.Esc(ma)}</b> vào game");
        }

        /// <summary>
        /// Mã vừa gửi bị server từ chối ("Mã captcha không chính xác").
        ///
        /// KHÔNG gửi lại ảnh: game giữ nguyên mã cũ sau khi gõ sai, nên tấm ảnh đã có ở trên vẫn
        /// đúng — chỉ cần đọc lại rồi reply tiếp vào chính nó. Gửi thêm ảnh y hệt là làm rác nhóm
        /// và đẩy tấm đang cần nhìn trôi lên xa.
        ///
        /// `nhieuLan` = đã trượt quá ngưỡng khai trong cfg. Đây là LỜI KHUYÊN, KHÔNG phải dấu
        /// chấm hết — và đó là chỗ bản đầu làm sai: nó gọi DonChoMaCuaNick, tức xoá luôn đường
        /// reply. Hồi đó tưởng bùa tự tan sau 5 phút nên bỏ cuộc chỉ mất nốt vài phút. Thật ra
        /// bùa nằm đó tới khi có người nhập đúng mã, nên xoá mục chờ là TỰ TAY KHOÁ nick lại
        /// vĩnh viễn, đúng vào lúc người dùng đang cố cứu nó. Captcha cố tình khó đọc (I hoa với
        /// l thường, 0 với O) — trượt bốn lần là chuyện thường, không phải dấu hiệu vô vọng.
        /// </summary>
        internal void TeleBuaSaiMa(string user, string noiDung, bool nhieuLan)
        {
            if (InvokeRequired) { BeginInvoke(new Action(() => TeleBuaSaiMa(user, noiDung, nhieuLan))); return; }
            string ten = TenHienThi(user);
            Log($"🧿❌ [{user}] {noiDung}");
            // KHÔNG dọn mục chờ ở đây, dù đã trượt bao nhiêu lần. Mục chỉ mất khi bùa được GIẢI.
            if (_tele == null || !_tele.Bat) return;
            _tele.Gui(nhieuLan
                ? $"🧿⚠️ <b>{TelegramBot.Esc(ten)}</b> — {TelegramBot.Esc(noiDung)}"
                  + "\n<b>Vẫn reply tiếp vào tin ảnh đó được</b> — hoặc vào game gõ cho nhanh."
                : $"🧿❌ <b>{TelegramBot.Esc(ten)}</b> — mã sai."
                  + " <b>Đọc lại ảnh ở trên rồi reply tiếp vào đúng tin đó</b> (mã không đổi).");
        }

        internal void TeleBuaUeTho(string user, string noiDung, bool dangBiYem)
        {
            string ten = TenHienThi(user);
            if (dangBiYem)
                Log($"🧿 [{user}] BỊ YỂM BÙA UẾ THỔ — {noiDung}");
            else
            {
                Log($"🧿✅ [{user}] đã giải bùa uế thổ");
                DonChoMaCuaNick(user);   // hết bị yểm thì mục chờ hết ý nghĩa
            }

            if (_tele == null || !_tele.Bat) return;
            if (dangBiYem)
                _tele.Gui($"🧿 <b>{TelegramBot.Esc(ten)}</b> bị yểm <b>bùa uế thổ</b>"
                          + " — nằm chết tới khi nhập captcha"
                          + $"\n<i>{TelegramBot.Esc(CatNgan(noiDung, 150))}</i>"
                          + "\n<i>Không đọc được ảnh captcha nên KHÔNG reply được ở đây."
                          + " Bùa không tự hết — phải vào game nhập mã, hoặc tắt hẳn client.</i>");
            else
                _tele.Gui($"🧿✅ <b>{TelegramBot.Esc(ten)}</b> đã giải bùa uế thổ");
        }

        internal void TeleBatDauGom(string nhanDo, int soMem)
        {
            _gomNhanDo = nhanDo ?? "";
            foreach (var t in _theoDoi.Values) { t.LuotGom = 0; t.DaGui.Clear(); t.DaNhan.Clear(); }
            if (_tele == null || !_tele.Bat || !_tele.CauHinh.TinGiaoDich) return;
            _tele.Gui($"🎒 <b>Bắt đầu gom đồ</b> — {soMem} nick giao về <b>{TelegramBot.Esc(_gomNhanDo)}</b>");
        }

        internal void TeleXongGom(string viSao)
        {
            if (_tele == null || !_tele.Bat || !_tele.CauHinh.TinGiaoDich) return;

            var daGiao = _theoDoi.Values.Where(t => t.DaGui.Count > 0)
                                        .OrderBy(t => t.Username, StringComparer.OrdinalIgnoreCase).ToList();
            var sb = new StringBuilder();
            sb.Append($"🎒 <b>Xong lượt gom</b> ({TelegramBot.Esc(viSao)})\n");
            sb.Append($"Nhận đồ: <b>{TelegramBot.Esc(_gomNhanDo)}</b> · {daGiao.Count} nick đã giao\n");
            foreach (var t in daGiao)
                sb.Append($"\n• <b>{TelegramBot.Esc(t.Username)}</b>"
                        + (t.Team.Length > 0 ? $" [t{TelegramBot.Esc(t.Team)}]" : "")
                        + $" — {TelegramBot.Esc(string.Join(", ", t.DaGui))}");
            _tele.Gui(sb.ToString());
        }

        // ── Bảng theo dõi ─────────────────────────────────────────────────
        /// <summary>
        /// Dựng bảng trạng thái: nhóm theo team, mỗi nick một dòng.
        /// Telegram giới hạn 4096 ký tự một tin nên bước và danh sách món đều bị cắt ngắn —
        /// chi tiết đầy đủ nằm ở tin giao dịch rời, bảng chỉ để liếc một cái là biết ai đang ở đâu.
        /// </summary>
        private string DungBangTrangThai()
        {
            NapTeamCuaNick();

            var sb = new StringBuilder();
            sb.Append($"📊 <b>Làng Lá</b> · {DateTime.Now:HH:mm:ss}\n");

            var moiNick = new List<string>();
            foreach (var kv in _teamCuaNick) moiNick.Add(kv.Key);
            foreach (var u in _theoDoi.Keys) if (!moiNick.Contains(u, StringComparer.OrdinalIgnoreCase)) moiNick.Add(u);
            if (_config != null && _config.Accounts != null)
            {
                foreach (var acc in _config.Accounts)
                {
                    if (!string.IsNullOrWhiteSpace(acc.Username) && !moiNick.Contains(acc.Username, StringComparer.OrdinalIgnoreCase))
                        moiNick.Add(acc.Username);
                }
            }

            int onl = moiNick.Count(IsLoggedIn);
            sb.Append($"🟢 {onl}/{moiNick.Count} nick trong game\n");

            foreach (var team in moiNick.Select(TeamCuaNick).Distinct()
                                        .OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var nicks = moiNick.Where(u => string.Equals(TeamCuaNick(u), team, StringComparison.OrdinalIgnoreCase))
                                   .OrderBy(u => u, StringComparer.OrdinalIgnoreCase).ToList();
                if (nicks.Count == 0) continue;

                string teamTitle = team.Length > 0 ? $"Team {team}" : "Chưa phân team";
                sb.Append($"\n<b>── {TelegramBot.Esc(teamTitle)} ──</b>\n");
                foreach (var u in nicks)
                {
                    bool onlNick = IsLoggedIn(u);
                    _theoDoi.TryGetValue(u, out var t);

                    sb.Append(onlNick ? "🟢 " : "⚪ ");
                    sb.Append($"<b>{TelegramBot.Esc(u)}</b>");

                    string ch = GetCharName(u);
                    if (ch.Length > 0) sb.Append($" (<i>{TelegramBot.Esc(ch)}</i>)");

                    if (t != null && t.HoatDong.Length > 0)
                    {
                        sb.Append($" · {TelegramBot.Esc(t.HoatDong)}");
                        if (t.Buoc.Length > 0) sb.Append($": {TelegramBot.Esc(CatNgan(t.Buoc, 60))}");
                    }
                    else if (!onlNick) sb.Append(" · <i>chưa vào game</i>");

                    if (t != null && t.DaGui.Count > 0)
                        sb.Append($"\n     📤 {TelegramBot.Esc(CatNgan(string.Join(", ", t.DaGui), 90))}");
                    if (t != null && t.DaNhan.Count > 0)
                        sb.Append($"\n     📥 nhận {t.DaNhan.Count} lượt: "
                                + TelegramBot.Esc(CatNgan(string.Join(" | ", t.DaNhan), 120)));

                    sb.Append('\n');
                }
            }

            string s = sb.ToString();
            return s.Length > 3900 ? s.Substring(0, 3900) + "\n… (cắt bớt)" : s;
        }

        private static string CatNgan(string s, int n)
        {
            if (string.IsNullOrEmpty(s)) return "";
            s = s.Replace("\n", " ").Trim();
            return s.Length <= n ? s : s.Substring(0, n) + "…";
        }
    }
}
