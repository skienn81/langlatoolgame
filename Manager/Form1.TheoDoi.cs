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

            int onl = moiNick.Count(IsLoggedIn);
            sb.Append($"🟢 {onl}/{moiNick.Count} nick trong game");
            sb.Append('\n');

            foreach (var team in moiNick.Select(TeamCuaNick).Distinct()
                                        .OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var nicks = moiNick.Where(u => string.Equals(TeamCuaNick(u), team, StringComparison.OrdinalIgnoreCase))
                                   .OrderBy(u => u, StringComparer.OrdinalIgnoreCase).ToList();
                if (nicks.Count == 0) continue;

                sb.Append($"\n<b>── Team {TelegramBot.Esc(team.Length > 0 ? team : "?")} ──</b>\n");
                foreach (var u in nicks)
                {
                    bool onlNick = IsLoggedIn(u);
                    _theoDoi.TryGetValue(u, out var t);

                    sb.Append(onlNick ? "🟢 " : "⚪ ");
                    sb.Append($"<b>{TelegramBot.Esc(u)}</b>");

                    string ch = GetCharName(u);
                    if (ch.Length > 0) sb.Append($" <i>{TelegramBot.Esc(ch)}</i>");

                    if (t != null && t.HoatDong.Length > 0)
                    {
                        sb.Append($" · {TelegramBot.Esc(t.HoatDong)}");
                        if (t.Buoc.Length > 0) sb.Append($": {TelegramBot.Esc(CatNgan(t.Buoc, 60))}");
                    }
                    else if (!onlNick) sb.Append(" · chưa vào game");

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
