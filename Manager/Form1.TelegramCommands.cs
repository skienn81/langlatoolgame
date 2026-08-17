using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Manager
{
    public partial class Form1
    {
        private TaskScheduler _scheduler;

        /// <summary>
        /// Xử lý tin nhắn nhận được từ Telegram Bot.
        /// Chạy bất đồng bộ, tự động Invoke về UI thread khi thao tác với Manager.
        /// </summary>
        public async Task XuLyLenhTelegramAsync(string rawText, Action<string> traLoiTele)
        {
            if (string.IsNullOrWhiteSpace(rawText)) return;
            string text = rawText.Trim();

            // Nếu không bắt đầu bằng / thì chỉ xử lý nếu là lệnh trực tiếp
            if (!text.StartsWith("/"))
            {
                text = "/" + text;
            }

            // Tách lệnh và các tham số
            var tokens = Regex.Matches(text, @"[\""].+?[\""]|[^ ]+")
                              .Cast<Match>()
                              .Select(m => m.Value.Trim('\"', ' '))
                              .Where(s => s.Length > 0)
                              .ToList();

            if (tokens.Count == 0) return;

            string cmd = tokens[0].Substring(1).ToLowerInvariant();
            var args = tokens.Skip(1).ToList();

            try
            {
                string rep = await Task.Run(() => ThucThiLenh(cmd, args));
                if (!string.IsNullOrWhiteSpace(rep))
                {
                    traLoiTele?.Invoke(rep);
                }
            }
            catch (Exception ex)
            {
                traLoiTele?.Invoke($"❌ <b>Lỗi thực thi:</b> {TelegramBot.Esc(ex.Message)}");
            }
        }

        /// <summary>
        /// Thực thi lệnh và trả về chuỗi thông báo HTML cho Telegram.
        /// </summary>
        private string ThucThiLenh(string cmd, List<string> args)
        {
            // Bảo đảm chạy trên UI thread nếu cần
            if (InvokeRequired)
            {
                return (string)Invoke(new Func<string>(() => ThucThiLenh(cmd, args)));
            }

            switch (cmd)
            {
                // ── Ải Gia Tộc ──
                case "agt":
                case "ai":
                case "aigiatoc":
                    if (args.Count > 0 && (args[0].Equals("stop", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("tat", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("dung", StringComparison.OrdinalIgnoreCase)))
                    {
                        return TeleDungAgt();
                    }
                    return TeleChayAgt(ResolveTargets(args));

                // ── Auto Nhiệm Vụ Ngày ──
                case "nv":
                case "autonv":
                case "auto":
                case "nhiemvu":
                    if (args.Count > 0 && (args[0].Equals("stop", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("tat", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("dung", StringComparison.OrdinalIgnoreCase)))
                    {
                        return TeleDungAutoNv(ResolveTargets(args.Skip(1).ToList(), defaultToAll: true));
                    }
                    return TeleChayAutoNv(ResolveTargets(args));

                // ── Địa Cung ──
                case "diacung":
                case "dc":
                    return TeleChayDiaCung(ResolveTargets(args));

                // ── Cấm Thuật ──
                case "camthuat":
                case "ct":
                    if (args.Count > 0 && (args[0].Equals("stop", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("tat", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("dung", StringComparison.OrdinalIgnoreCase)))
                    {
                        return TeleDungCamThuat();
                    }
                    return TeleChayCamThuat(args);

                // ── Sơn Cáp ──
                case "soncap":
                case "sc":
                    if (args.Count > 0 && (args[0].Equals("stop", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("tat", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("dung", StringComparison.OrdinalIgnoreCase)))
                    {
                        return TeleDungSonCap();
                    }
                    return TeleChaySonCap(args);

                // ── Gom Đồ ──
                case "gom":
                case "gomdo":
                    if (args.Count > 0 && (args[0].Equals("stop", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("tat", StringComparison.OrdinalIgnoreCase)
                                        || args[0].Equals("dung", StringComparison.OrdinalIgnoreCase)))
                    {
                        return TeleDungGomDo();
                    }
                    return TeleChayGomDo();

                // ── Tinh Thạch ──
                case "tinhthach":
                case "tt":
                    return TeleChayTinhThach(ResolveTargets(args));

                // ── Auto Quiz NPC ──
                case "quiz":
                    return TeleChayQuiz(ResolveTargets(args));

                // ── Về Làng ──
                case "velang":
                case "vl":
                case "village":
                    return TeleChayVeLang(ResolveTargets(args));

                // ── Tắt Toàn Bộ / Dừng Module ──
                case "stop":
                case "tat":
                case "dung":
                    return TeleXuLyStop(args);

                // ── Tắt Game (Kill java) ──
                case "kill":
                case "killgame":
                    return TeleChayKill(args);

                // ── Khởi Chạy Game (Wake / Launch) ──
                case "wake":
                case "launch":
                case "khoichay":
                case "open":
                    return TeleChayWake(args);

                // ── Quản Lý Đội Hình / Team ──
                case "team":
                case "doihinh":
                    return TeleXuLyTeam(args);

                // ── Trạng Thái Tổng Quan ──
                case "status":
                case "st":
                case "trangthai":
                    return DungBangTrangThai();

                // ── Hẹn Giờ ──
                case "hengio":
                case "schedule":
                case "lich":
                    return TeleThemHenGio(args);

                case "timer":
                    return TeleThemTimer(args);

                case "dshengio":
                case "schedules":
                case "dslich":
                    return TeleDanhSachHenGio();

                case "huyhengio":
                case "cancelschedule":
                case "xoalich":
                    return TeleHuyHenGio(args);

                // ── Hướng Dẫn ──
                case "help":
                case "start":
                case "giftcode":
                case "code":
                case "gift":
                    if (args.Count == 0)
                    {
                        return "ℹ️ <b>Cách dùng:</b> <code>/giftcode &lt;mã_1&gt; [mã_2]...</code>\nVí dụ: <code>/giftcode TRIAN2026 TANTHU2026</code>";
                    }
                    return TeleNhapGiftCode(args);

                case "huongdan":
                    return TeleHuongDan();

                default:
                    return $"❓ <b>Không nhận diện được lệnh:</b> <code>/{TelegramBot.Esc(cmd)}</code>\n"
                         + "Gõ <code>/help</code> để xem danh sách các lệnh hỗ trợ.";
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // RESOLVE TARGET NICKS (USERNAME HOẶC TÊN NHÂN VẬT)
        // ══════════════════════════════════════════════════════════════════

        private List<string> ResolveTargets(List<string> args, bool defaultToAll = true)
        {
            var targets = new List<string>();
            bool isAll = args.Count == 0 && defaultToAll;
            if (args.Any(a => a.Equals("all", StringComparison.OrdinalIgnoreCase) || a.Equals("*")))
            {
                isAll = true;
            }

            if (isAll)
            {
                // Lấy tất cả nick đang kết nối và đã login
                lock (_sessions)
                {
                    foreach (var s in _sessions)
                    {
                        if (s.IsConnected && !string.IsNullOrEmpty(s.Username)
                            && !targets.Any(x => x.Equals(s.Username, StringComparison.OrdinalIgnoreCase)))
                        {
                            targets.Add(s.Username);
                        }
                    }
                }
                return targets;
            }

            var teams = LoadTeams();

            foreach (var arg in args)
            {
                if (string.IsNullOrWhiteSpace(arg)) continue;
                string cleanArg = arg.Trim();

                // Kiểm tra xem tham số có phải tên team không (vd: team1, 1, team:1, @team1)
                string teamKey = cleanArg;
                if (teamKey.StartsWith("@")) teamKey = teamKey.Substring(1).Trim();
                if (teamKey.StartsWith("team:", StringComparison.OrdinalIgnoreCase))
                    teamKey = teamKey.Substring(5).Trim();

                var matchedTeam = teams.FirstOrDefault(kv => string.Equals(kv.Key, cleanArg, StringComparison.OrdinalIgnoreCase)
                                                          || string.Equals(kv.Key, teamKey, StringComparison.OrdinalIgnoreCase)
                                                          || string.Equals(kv.Key, "team:" + teamKey, StringComparison.OrdinalIgnoreCase));

                if (!string.IsNullOrEmpty(matchedTeam.Key) && matchedTeam.Value != null && matchedTeam.Value.Count > 0)
                {
                    foreach (var tn in matchedTeam.Value)
                    {
                        string ru = TimUsername(tn);
                        if (!string.IsNullOrEmpty(ru) && !targets.Any(x => x.Equals(ru, StringComparison.OrdinalIgnoreCase)))
                        {
                            targets.Add(ru);
                        }
                    }
                    continue;
                }

                string resolvedUser = TimUsername(cleanArg);
                if (!string.IsNullOrEmpty(resolvedUser)
                    && !targets.Any(x => x.Equals(resolvedUser, StringComparison.OrdinalIgnoreCase)))
                {
                    targets.Add(resolvedUser);
                }
            }

            return targets;
        }

        private string TimUsername(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return null;
            input = input.Trim();

            // 1. Khớp username trong sessions
            lock (_sessions)
            {
                var s = _sessions.FirstOrDefault(x => string.Equals(x.Username, input, StringComparison.OrdinalIgnoreCase));
                if (s != null && !string.IsNullOrEmpty(s.Username)) return s.Username;
            }

            // 2. Khớp tên nhân vật
            lock (_charNames)
            {
                var kv = _charNames.FirstOrDefault(x => string.Equals(x.Value, input, StringComparison.OrdinalIgnoreCase));
                if (!string.IsNullOrEmpty(kv.Key)) return kv.Key;
            }

            // 3. Khớp username trong cấu hình
            if (_config != null && _config.Accounts != null)
            {
                var acc = _config.Accounts.FirstOrDefault(x => string.Equals(x.Username, input, StringComparison.OrdinalIgnoreCase));
                if (acc != null) return acc.Username;
            }

            return input;
        }

        // ══════════════════════════════════════════════════════════════════
        // CÁC HÀM THỰC THI MODULE CHO TELEGRAM
        // ══════════════════════════════════════════════════════════════════

        private string TeleChayAgt(List<string> targets)
        {
            if (targets.Count == 0)
            {
                return "🏰⚠️ <b>Ải gia tộc:</b> Không có nick nào đang online để chạy.";
            }

            _agtActiveUsers.Clear();
            int count = 0;
            var startedUsers = new List<string>();

            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !IsLoggedIn(u)) continue;
                ss.SendRawJson("{\"command\":\"agt_start\"}\n");
                _agtActiveUsers.Add(u);
                startedUsers.Add(TenHienThi(u));
                count++;
            }

            if (count == 0)
            {
                return "🏰⚠️ <b>Ải gia tộc:</b> Các nick chỉ định chưa đăng nhập vào game.";
            }

            SetAgtButton(true);
            Log($"🏰 Telegram: Đã gửi lệnh Ải gia tộc cho {count} nick: [{string.Join(", ", startedUsers)}]");
            return $"🏰 <b>Ải gia tộc:</b> Đã bắt đầu chạy trên <b>{count}</b> nick:\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", startedUsers))}\n"
                 + "<i>(Các nick đang tự về làng tìm NPC Onoki)</i>";
        }

        private string TeleDungAgt()
        {
            int stopped = 0;
            var targets = _agtActiveUsers.Count > 0 ? _agtActiveUsers.ToList() : GetCheckedUsernames();
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null) continue;
                ss.SendRawJson("{\"command\":\"agt_stop\"}\n");
                stopped++;
            }
            _agtActiveUsers.Clear();
            AgtFollowStop();
            SetAgtButton(false);
            Log($"🏰🛑 Telegram: Đã tắt Ải gia tộc — báo {stopped} nick dừng.");
            return $"🏰🛑 <b>Ải gia tộc:</b> Đã gửi lệnh dừng tới <b>{stopped}</b> nick.";
        }

        private string TeleChayAutoNv(List<string> targets)
        {
            if (targets.Count == 0)
            {
                return "▶️⚠️ <b>Auto NV:</b> Không có nick nào đang online.";
            }

            int count = 0;
            var started = new List<string>();
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !ss.IsConnected) continue;
                ss.SendCommand("start_auto");
                started.Add(TenHienThi(u));
                count++;
            }

            Log($"▶️ Telegram: Bật Auto NV cho {count} nick.");
            return $"▶️ <b>Auto Nhiệm Vụ Ngày:</b> Đã bật cho <b>{count}</b> nick:\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", started))}";
        }

        private string TeleDungAutoNv(List<string> targets)
        {
            if (targets.Count == 0) targets = GetCheckedUsernames();
            int count = 0;
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !ss.IsConnected) continue;
                ss.SendCommand("stop_auto");
                count++;
            }
            RefreshAllStatusColors();
            Log($"⏹ Telegram: Tắt Auto NV cho {count} nick.");
            return $"⏹ <b>Auto Nhiệm Vụ Ngày:</b> Đã tắt cho <b>{count}</b> nick.";
        }

        private string TeleChayDiaCung(List<string> targets)
        {
            if (_sonCapOn) SetSonCapButton(false);
            if (targets.Count == 0)
            {
                return "🏯⚠️ <b>Địa cung:</b> Không có nick nào được chọn hoặc đang online.";
            }

            int sentCount = 0, skipped = 0;
            var names = new List<string>();

            lock (_sessions)
            {
                foreach (var session in _sessions)
                {
                    if (string.IsNullOrEmpty(session.Username)) continue;
                    if (!targets.Any(u => string.Equals(u, session.Username, StringComparison.OrdinalIgnoreCase)))
                        continue;
                    if (!IsLoggedIn(session.Username)) continue;

                    var acc = FindAccount(session.Username);
                    string today = DateTime.Now.ToString("yyyy-MM-dd");
                    bool skipKey = acc != null && acc.DiaCungKeyDate == today;
                    int tier = (acc != null) ? acc.DiaCungTier : 0;
                    if (skipKey) skipped++;

                    session.SendRawJson(
                        $"{{\"command\":\"dia_cung_run\",\"tier\":{tier},\"skipKey\":{(skipKey ? "true" : "false")}}}\n");
                    sentCount++;
                    names.Add(TenHienThi(session.Username));
                }
            }

            if (sentCount == 0)
            {
                return "🏯⚠️ <b>Địa cung:</b> Các nick chỉ định chưa vào game.";
            }

            Log($"🏯 Telegram: Đã gửi lệnh Địa cung cho {sentCount} nick.");
            return $"🏯 <b>Địa cung:</b> Đã gửi lệnh tới <b>{sentCount}</b> nick:\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", names))}"
                 + (skipped > 0 ? $"\n<i>(Có {skipped} nick hôm nay đã nhận chìa nên vào thẳng hầm)</i>" : "");
        }

        private string TeleChayCamThuat(List<string> args = null)
        {
            if (_sonCapOn) SetSonCapButton(false);
            var setups = LoadNhom("camthuat");
            if (setups.Count == 0)
            {
                return "⚔️⚠️ <b>Cấm thuật:</b> Chưa khai nhóm trong <code>doi_hinh.cfg</code>.\n"
                     + "Dùng <code>/team team1 truong mem1 mem2...</code> để tạo đội hình.";
            }

            // Lọc theo team nếu người dùng chỉ định (vd: /ct team1 hoặc /ct 1)
            if (args != null && args.Count > 0 && !args.Any(a => a.Equals("all", StringComparison.OrdinalIgnoreCase) || a.Equals("*")))
            {
                var filtered = new List<GroupSetup>();
                foreach (var arg in args)
                {
                    string t = arg.Trim();
                    if (t.StartsWith("team:", StringComparison.OrdinalIgnoreCase)) t = t.Substring(5).Trim();
                    var match = setups.FirstOrDefault(s => string.Equals(s.Name, t, StringComparison.OrdinalIgnoreCase)
                                                        || string.Equals(s.Name, "team:" + t, StringComparison.OrdinalIgnoreCase));
                    if (match != null && !filtered.Contains(match)) filtered.Add(match);
                }
                if (filtered.Count > 0)
                {
                    setups = filtered;
                }
            }

            // Gỡ tuyến cũ
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
            int soNhom = setups.Count;
            int thuTuNhom = -1;
            var groupSummaries = new List<string>();

            foreach (var g in setups)
            {
                thuTuNhom++;
                _ctPlanned[g.Name] = g;

                var leaderSession = FindSession(g.Leader);
                if (leaderSession == null || !IsLoggedIn(g.Leader))
                {
                    groupSummaries.Add($"• <b>{g.Name}</b>: ❌ Trưởng nhóm <b>{TenHienThi(g.Leader)}</b> chưa vào game");
                    continue;
                }

                var ready = new List<string>();
                var memberNames = new List<string>();
                foreach (var m in g.Members)
                {
                    string cn = GetCharName(m);
                    if (FindSession(m) == null || !IsLoggedIn(m) || cn.Length == 0)
                        continue;
                    ready.Add(m);
                    memberNames.Add(cn);
                }

                string leaderChar = GetCharName(g.Leader);
                if (leaderChar.Length == 0)
                {
                    groupSummaries.Add($"• <b>{g.Name}</b>: ❌ Chưa đọc được tên nhân vật của trưởng nhóm <b>{TenHienThi(g.Leader)}</b>");
                    continue;
                }

                var active = new GroupSetup { Name = g.Name, Leader = g.Leader, Members = ready };
                _ctActive[g.Name] = active;

                leaderSession.SendRawJson(
                    $"{{\"command\":\"cam_thuat_leader\",\"members\":\"{EscapeJson(string.Join(";", memberNames))}\"," +
                    $"\"expected\":{1 + g.Members.Count}," +
                    $"\"zone_slot\":{thuTuNhom},\"zone_slots\":{soNhom}}}\n");

                for (int i = 0; i < ready.Count; i++)
                {
                    FindSession(ready[i])?.SendRawJson(
                        $"{{\"command\":\"cam_thuat_member\",\"leader\":\"{EscapeJson(leaderChar)}\"," +
                        $"\"slot\":{i}}}\n");
                }

                started++;
                groupSummaries.Add($"• <b>{g.Name}</b>: 👑 {TenHienThi(g.Leader)} + {ready.Count} thành viên");
            }

            Log($"⚔️ Telegram: Bắt đầu Cấm thuật — {started}/{setups.Count} nhóm xuất phát.");
            return $"⚔️ <b>Cấm thuật:</b> Bắt đầu {started}/{setups.Count} nhóm:\n"
                 + string.Join("\n", groupSummaries);
        }

        private string TeleDungCamThuat()
        {
            int stopped = 0;
            foreach (var g in _ctPlanned.Values)
            {
                StopFollowGroup(g.Leader);
                var ssL = FindSession(g.Leader);
                if (ssL != null) { ssL.SendRawJson("{\"command\":\"cam_thuat_stop\"}\n"); stopped++; }
                foreach (var m in g.Members)
                {
                    var ssM = FindSession(m);
                    if (ssM != null) { ssM.SendRawJson("{\"command\":\"cam_thuat_stop\"}\n"); stopped++; }
                }
            }
            _ctInDungeon.Clear();
            _ctActive.Clear();
            _ctPlanned.Clear();
            Log($"⚔️🛑 Telegram: Đã dừng Cấm thuật.");
            return $"⚔️🛑 <b>Cấm thuật:</b> Đã gửi lệnh dừng tới các nhóm ({stopped} nick).";
        }

        private string TeleChaySonCap(List<string> args = null)
        {
            if (_sonCapOn) { StopSonCapAll(); return "🪢🛑 <b>Sơn cáp:</b> Đã dừng."; }

            var setups = LoadNhom("soncap");
            if (setups.Count == 0)
            {
                return "🪢⚠️ <b>Sơn cáp:</b> Chưa khai nhóm trong <code>doi_hinh.cfg</code>.\n"
                     + "Dùng <code>/team team1 truong mem1 mem2...</code> để tạo đội hình.";
            }

            // Lọc theo team nếu người dùng chỉ định (vd: /sc team1 hoặc /sc 1)
            if (args != null && args.Count > 0 && !args.Any(a => a.Equals("all", StringComparison.OrdinalIgnoreCase) || a.Equals("*")))
            {
                var filtered = new List<GroupSetup>();
                foreach (var arg in args)
                {
                    string t = arg.Trim();
                    if (t.StartsWith("team:", StringComparison.OrdinalIgnoreCase)) t = t.Substring(5).Trim();
                    var match = setups.FirstOrDefault(s => string.Equals(s.Name, t, StringComparison.OrdinalIgnoreCase)
                                                        || string.Equals(s.Name, "team:" + t, StringComparison.OrdinalIgnoreCase));
                    if (match != null && !filtered.Contains(match)) filtered.Add(match);
                }
                if (filtered.Count > 0)
                {
                    setups = filtered;
                }
            }

            foreach (var name in _scInFloor.Keys.ToList())
                if (_scPlanned.TryGetValue(name, out var old)) StopFollowGroup(old.Leader);
            _scInFloor.Clear();
            _scFloorMap.Clear();
            _scPlanned.Clear(); _scActive.Clear(); _scPos.Clear(); _scAtPoint.Clear();
            _scLastRelay.Clear(); _scLastWait.Clear(); _scDone.Clear();

            int started = 0;
            int soNhom = setups.Count;
            int thuTuNhom = -1;
            var groupSummaries = new List<string>();

            foreach (var g in setups)
            {
                thuTuNhom++;
                _scPlanned[g.Name] = g;

                var leaderSession = FindSession(g.Leader);
                if (leaderSession == null || !IsLoggedIn(g.Leader))
                {
                    groupSummaries.Add($"• <b>{g.Name}</b>: ❌ Trưởng nhóm <b>{TenHienThi(g.Leader)}</b> chưa vào game");
                    continue;
                }

                var ready = new List<string>();
                var memberNames = new List<string>();
                foreach (var m in g.Members)
                {
                    string cn = GetCharName(m);
                    if (FindSession(m) == null || !IsLoggedIn(m) || cn.Length == 0)
                        continue;
                    ready.Add(m);
                    memberNames.Add(cn);
                }

                string leaderChar = GetCharName(g.Leader);
                if (leaderChar.Length == 0)
                {
                    groupSummaries.Add($"• <b>{g.Name}</b>: ❌ Chưa đọc được tên nhân vật của trưởng nhóm <b>{TenHienThi(g.Leader)}</b>");
                    continue;
                }

                _scActive[g.Name] = new GroupSetup { Name = g.Name, Leader = g.Leader, Members = ready };

                leaderSession.SendRawJson(
                    "{\"command\":\"son_cap_leader\",\"members\":\"" + EscapeJson(string.Join(";", memberNames)) + "\"," +
                    "\"expected\":" + (1 + g.Members.Count) +
                    ",\"zone_slot\":" + thuTuNhom + ",\"zone_slots\":" + soNhom + "}\n");

                for (int i = 0; i < ready.Count; i++)
                {
                    FindSession(ready[i])?.SendRawJson(
                        "{\"command\":\"son_cap_member\",\"leader\":\"" + EscapeJson(leaderChar) + "\",\"slot\":" + i + "}\n");
                }

                started++;
                groupSummaries.Add($"• <b>{g.Name}</b>: 👑 {TenHienThi(g.Leader)} + {ready.Count} thành viên");
            }

            if (started == 0)
            {
                return "🪢⚠️ <b>Sơn cáp:</b> Không nhóm nào chạy được (các trưởng nhóm chưa vào game).";
            }

            SetSonCapButton(true);
            Log($"🪢 Telegram: Bắt đầu Sơn cáp — {started}/{setups.Count} nhóm.");
            return $"🪢 <b>Sơn cáp:</b> Bắt đầu {started}/{setups.Count} nhóm:\n"
                 + string.Join("\n", groupSummaries);
        }

        private string TeleDungSonCap()
        {
            StopSonCapAll();
            Log($"🪢🛑 Telegram: Đã dừng Sơn cáp.");
            return "🪢🛑 <b>Sơn cáp:</b> Đã dừng toàn bộ các nhóm.";
        }

        private string TeleChayGomDo()
        {
            if (_gomOn) { StopGom("lệnh dừng từ Telegram"); return "🎒🛑 <b>Gom đồ:</b> Đã dừng."; }

            var g = LoadGomSetup();
            if (g == null)
            {
                return "🎒⚠️ <b>Gom đồ:</b> Chưa khai khối <code>[gom]</code> (nhan_do = ...) trong <code>doi_hinh.cfg</code>.";
            }

            if (FindSession(g.Leader) == null)
            {
                return $"🎒⚠️ <b>Gom đồ:</b> Nick nhận đồ <b>{TenHienThi(g.Leader)}</b> chưa vào game.";
            }

            // Lấy danh sách mem
            var members = g.Members.Count > 0 ? g.Members.ToList() : GetCheckedUsernames();
            members = members.Where(m => !string.Equals(m, g.Leader, StringComparison.OrdinalIgnoreCase)
                                      && FindSession(m) != null && IsLoggedIn(m)).ToList();

            if (members.Count == 0)
            {
                return "🎒⚠️ <b>Gom đồ:</b> Không có thành viên nào đang online để giao đồ.";
            }

            _gomGroup = new GroupSetup { Name = g.Name, Leader = g.Leader, Members = members };
            _gomQueue.Clear();
            _gomQueue.AddRange(members);
            _gomCurrent = null;
            _gomLeadChar = GetCharName(g.Leader);
            _gomMap = -1; _gomZone = -1; _gomX = -1; _gomY = -1;

            SetGomButton(true);
            TeleBatDauGom(g.Leader, members.Count);

            foreach (var m in members) FindSession(m)?.SendRawJson("{\"command\":\"go_village\"}\n");
            FindSession(g.Leader)?.SendRawJson("{\"command\":\"gom_lead_start\"}\n");

            Log($"🎒 Telegram: Bắt đầu gom đồ về {g.Leader} từ {members.Count} nick.");
            return $"🎒 <b>Gom đồ:</b> Bắt đầu gom về <b>{TenHienThi(g.Leader)}</b> từ <b>{members.Count}</b> nick.";
        }

        private string TeleDungGomDo()
        {
            StopGom("lệnh dừng từ Telegram");
            Log($"🎒🛑 Telegram: Đã dừng gom đồ.");
            return "🎒🛑 <b>Gom đồ:</b> Đã dừng lượt gom đồ.";
        }

        private string TeleChayTinhThach(List<string> targets)
        {
            if (targets.Count == 0)
            {
                return "💎⚠️ <b>Đổi tinh thạch:</b> Không có nick nào online.";
            }

            int sent = 0;
            var names = new List<string>();
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !IsLoggedIn(u)) continue;
                ss.SendRawJson("{\"command\":\"tinh_thach_start\"}\n");
                names.Add(TenHienThi(u));
                sent++;
            }

            Log($"💎 Telegram: Gửi lệnh đổi tinh thạch tới {sent} nick.");
            return $"💎 <b>Đổi tinh thạch:</b> Đã gửi lệnh tới <b>{sent}</b> nick:\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", names))}";
        }

        private string TeleChayQuiz(List<string> targets)
        {
            if (targets.Count == 0)
            {
                return "🧠⚠️ <b>Auto Quiz NPC:</b> Không có nick nào online.";
            }

            int sent = 0;
            var names = new List<string>();
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !IsLoggedIn(u)) continue;
                ss.SendRawJson("{\"command\":\"quiz_start\"}\n");
                names.Add(TenHienThi(u));
                sent++;
            }

            Log($"🧠 Telegram: Gửi lệnh Auto Quiz NPC tới {sent} nick.");
            return $"🧠 <b>Auto Quiz NPC:</b> Đã gửi lệnh tới <b>{sent}</b> nick:\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", names))}";
        }

        private string TeleNhapGiftCode(List<string> codes)
        {
            var targets = GetOnlineUsernames();
            if (targets.Count == 0) targets = GetCheckedUsernames();
            if (targets.Count == 0)
            {
                return "🎁⚠️ <b>Nhập Giftcode:</b> Chưa có nick nào online hoặc được tick chọn trên Manager.";
            }

            int sent = 0;
            foreach (var code in codes)
            {
                foreach (var u in targets)
                {
                    if (SendGiftCodeToUser(u, code)) sent++;
                }
            }

            Log($"🎁 Telegram: Đã gửi {codes.Count} mã Giftcode tới {targets.Count} nick.");
            return $"🎁 <b>Nhập Giftcode:</b> Đã gửi {codes.Count} mã tới <b>{targets.Count}</b> nick ({sent} lượt gửi):\n"
                 + $"👉 Mã: {string.Join(", ", codes.Select(c => $"<code>{TelegramBot.Esc(c)}</code>"))}\n"
                 + "<i>(Kết quả chi tiết được tự động cập nhật trên Manager)</i>";
        }

        private string TeleChayVeLang(List<string> targets)
        {
            if (targets.Count == 0)
            {
                return "🏠⚠️ <b>Về làng:</b> Không có nick nào online.";
            }

            int sent = 0;
            foreach (var u in targets)
            {
                var ss = FindSession(u);
                if (ss == null || !ss.IsConnected) continue;
                ss.SendRawJson("{\"command\":\"go_village\"}\n");
                sent++;
            }

            Log($"🏠 Telegram: Gửi lệnh về làng tới {sent} nick.");
            return $"🏠 <b>Về làng:</b> Đã gửi lệnh về làng tới <b>{sent}</b> nick.";
        }

        private string TeleChayKill(List<string> args)
        {
            bool isAll = args.Count == 0 || args.Any(a => a.Equals("all", StringComparison.OrdinalIgnoreCase) || a.Equals("*"));
            if (isAll)
            {
                return TeleChayKillGameAll();
            }

            var targets = ResolveTargets(args, defaultToAll: false);
            if (targets.Count == 0)
            {
                return "💀⚠️ <b>Tắt game:</b> Không tìm thấy nick nào phù hợp để tắt.";
            }

            int killed = TatClientCuaNick(targets);
            Log($"💀 Telegram: Đã tắt client cho {targets.Count} nick ({killed} tiến trình).");
            return $"💀 <b>Đã tắt client cho {targets.Count} nick:</b>\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", targets.Select(TenHienThi)))}\n"
                 + $"<i>(Đã tắt {killed} cửa sổ game)</i>";
        }

        private string TeleChayKillGameAll()
        {
            int killCount = 0;
            try
            {
                foreach (var proc in Process.GetProcesses())
                {
                    try
                    {
                        string name = proc.ProcessName.ToLower();
                        if (name == "java" || name == "javaw")
                        {
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

                            if (cmdLine.Contains("DesktopLauncher") || cmdLine.Contains("client_modded"))
                            {
                                proc.Kill();
                                killCount++;
                            }
                        }
                    }
                    catch { }
                }

                lock (_sessions)
                {
                    foreach (var s in _sessions) s.Close();
                    _sessions.Clear();
                }

                ReloadAccountsGrid();
            }
            catch (Exception ex)
            {
                return $"💀❌ <b>Lỗi tắt game:</b> {TelegramBot.Esc(ex.Message)}";
            }

            Log($"💀 Telegram: Đã tắt toàn bộ {killCount} game client.");
            return $"💀 <b>Tắt toàn bộ game:</b> Đã tắt thành công <b>{killCount}</b> cửa sổ game client.";
        }

        private string TeleChayWake(List<string> args)
        {
            bool isAll = args.Count == 0 || args.Any(a => a.Equals("all", StringComparison.OrdinalIgnoreCase) || a.Equals("*"));
            var toLaunch = new List<AccountConfig>();
            var alreadyOnline = new List<string>();
            var missingInConfig = new List<string>();

            if (isAll)
            {
                if (_config != null && _config.Accounts != null)
                {
                    foreach (var acc in _config.Accounts)
                    {
                        if (string.IsNullOrWhiteSpace(acc.Username)) continue;
                        if (IsLoggedIn(acc.Username) || IsAccountOnline(acc.Username))
                        {
                            alreadyOnline.Add(acc.Username);
                        }
                        else
                        {
                            toLaunch.Add(acc);
                        }
                    }
                }
            }
            else
            {
                var targets = ResolveTargets(args, defaultToAll: false);
                foreach (var u in targets)
                {
                    if (IsLoggedIn(u) || IsAccountOnline(u))
                    {
                        alreadyOnline.Add(u);
                        continue;
                    }

                    var acc = FindAccount(u);
                    if (acc != null)
                    {
                        toLaunch.Add(acc);
                    }
                    else
                    {
                        missingInConfig.Add(u);
                    }
                }
            }

            if (toLaunch.Count == 0)
            {
                if (alreadyOnline.Count > 0)
                    return $"🚀 <b>Các nick yêu cầu ({alreadyOnline.Count}) đều đã online trong game:</b>\n"
                         + $"👉 {TelegramBot.Esc(string.Join(", ", alreadyOnline.Select(TenHienThi)))}";
                if (missingInConfig.Count > 0)
                    return $"🚀⚠️ Không tìm thấy tài khoản trong danh sách Manager cho: <b>{TelegramBot.Esc(string.Join(", ", missingInConfig))}</b>";
                return "🚀⚠️ Không có nick nào cần khởi chạy.";
            }

            // Khởi chạy tuần tự trong background Task
            _ = Task.Run(async () =>
            {
                _launchCts?.Cancel();
                _launchCts = new CancellationTokenSource();
                var ct = _launchCts.Token;

                int thanhCong = 0;
                int choGiay = SoGiayChoLogin();
                int thuLai = SoLanThuLaiLogin();

                for (int i = 0; i < toLaunch.Count; i++)
                {
                    if (ct.IsCancellationRequested) break;
                    var acc = toLaunch[i];

                    Log($"🚀 Telegram [Wake {i + 1}/{toLaunch.Count}]: Khởi chạy {acc.Username}...");
                    bool loginOk = false;

                    for (int lan = 0; lan <= thuLai && !loginOk; lan++)
                    {
                        if (ct.IsCancellationRequested) break;
                        if (lan > 0)
                        {
                            TatClientCuaNick(new[] { acc.Username });
                            await Task.Delay(2000, ct);
                        }

                        BeginInvoke(new Action(() => LaunchAccount(acc)));
                        loginOk = await WaitForAccountLogin(acc.Username, choGiay, ct);
                    }

                    if (loginOk) thanhCong++;
                }

                _tele?.Gui($"🚀✅ <b>Khởi chạy hoàn tất:</b> {thanhCong}/{toLaunch.Count} nick đã đăng nhập thành công!");
            });

            var names = toLaunch.Select(a => TenHienThi(a.Username)).ToList();
            return $"🚀 <b>Bắt đầu khởi chạy {toLaunch.Count} nick:</b>\n"
                 + $"👉 {TelegramBot.Esc(string.Join(", ", names))}\n"
                 + (alreadyOnline.Count > 0 ? $"<i>(Bỏ qua {alreadyOnline.Count} nick đã online sẵn)</i>\n" : "")
                 + "<i>(Hệ thống đang mở và đăng nhập tuần tự trong nền...)</i>";
        }

        private string TeleXuLyStop(List<string> args)
        {
            if (args.Count == 0 || args[0].Equals("all", StringComparison.OrdinalIgnoreCase))
            {
                TeleDungAgt();
                TeleDungCamThuat();
                TeleDungSonCap();
                TeleDungGomDo();
                TeleDungAutoNv(ResolveTargets(new List<string> { "all" }));
                return "🛑 <b>Đã dừng toàn bộ:</b> Tắt Auto NV, Ải gia tộc, Cấm thuật, Sơn cáp, Gom đồ.";
            }

            string mod = args[0].ToLowerInvariant();
            switch (mod)
            {
                case "agt":
                case "ai":
                    return TeleDungAgt();
                case "nv":
                case "auto":
                    return TeleDungAutoNv(ResolveTargets(args.Skip(1).ToList()));
                case "ct":
                case "camthuat":
                    return TeleDungCamThuat();
                case "sc":
                case "soncap":
                    return TeleDungSonCap();
                case "gom":
                case "gomdo":
                    return TeleDungGomDo();
                default:
                    // Dừng theo nick chỉ định
                    return TeleDungAutoNv(ResolveTargets(args));
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // QUẢN LÝ ĐỘI HÌNH (/team)
        // ══════════════════════════════════════════════════════════════════

        private string TeleXuLyTeam(List<string> args)
        {
            // 1. /team hoặc /team list hoặc /team ds -> xem danh sách tất cả team
            if (args.Count == 0 || args[0].Equals("list", StringComparison.OrdinalIgnoreCase)
                                || args[0].Equals("ds", StringComparison.OrdinalIgnoreCase))
            {
                return TeleXemDanhSachTeam();
            }

            // 2. /team del <ten_team> hoặc /team xoa <ten_team>
            if (args.Count == 2 && (args[0].Equals("del", StringComparison.OrdinalIgnoreCase)
                                 || args[0].Equals("xoa", StringComparison.OrdinalIgnoreCase)
                                 || args[0].Equals("remove", StringComparison.OrdinalIgnoreCase)))
            {
                return TeleXoaTeam(args[1].Trim());
            }

            string tenTeam = args[0].Trim();
            if (tenTeam.StartsWith("team:", StringComparison.OrdinalIgnoreCase))
                tenTeam = tenTeam.Substring(5).Trim();

            // 3. /team <ten_team> (chỉ có 1 tham số) -> xem chi tiết đội hình
            if (args.Count == 1)
            {
                return TeleXemChiTietTeam(tenTeam);
            }

            string subCmd = args[1].Trim().ToLowerInvariant();

            // 4. Thêm nick: /team <ten_team> add <nick1> <nick2> ...
            if (subCmd == "add" || subCmd == "them" || subCmd == "+")
            {
                if (args.Count < 3)
                {
                    return "👥⚠️ <b>Cú pháp thêm nick vào đội hình:</b>\n"
                         + $"<code>/team {TelegramBot.Esc(tenTeam)} add &lt;nick1&gt; [nick2]...</code>\n"
                         + $"<i>Ví dụ: <code>/team {TelegramBot.Esc(tenTeam)} add user05 user06</code></i>";
                }
                return TeleThemNickVaoTeam(tenTeam, args.Skip(2).ToList());
            }

            // 5. Xoá nick / xoá team: /team <ten_team> del [nick1 nick2 ...]
            if (subCmd == "del" || subCmd == "xoa" || subCmd == "remove" || subCmd == "-")
            {
                if (args.Count == 2)
                {
                    // Không nhập nick phía sau -> Xoá toàn bộ team này
                    return TeleXoaTeam(tenTeam);
                }
                return TeleXoaNickKhoiTeam(tenTeam, args.Skip(2).ToList());
            }

            // 6. Đổi trưởng nhóm: /team <ten_team> lead <nick>
            if (subCmd == "lead" || subCmd == "truong" || subCmd == "leader")
            {
                if (args.Count < 3)
                {
                    return "👥⚠️ <b>Cú pháp đổi trưởng nhóm:</b>\n"
                         + $"<code>/team {TelegramBot.Esc(tenTeam)} lead &lt;tên_nick&gt;</code>";
                }
                return TeleDoiTruongTeam(tenTeam, args[2]);
            }

            // 7. Khởi tạo / ghi đè toàn bộ đội hình: /team <ten_team> <đội_trưởng> <nick2> <nick3> ...
            var rawNicks = args.Skip(1).ToList();
            if (rawNicks.Count > 6)
            {
                return $"👥⚠️ Một đội hình tối đa <b>6 nick</b> (1 trưởng + 5 thành viên). Bạn vừa nhập {rawNicks.Count} nick.";
            }

            string leader = TimUsername(rawNicks[0]);
            var members = new List<string>();
            for (int i = 1; i < rawNicks.Count; i++)
            {
                string m = TimUsername(rawNicks[i]);
                if (!string.IsNullOrEmpty(m) && !members.Contains(m, StringComparer.OrdinalIgnoreCase)
                                            && !string.Equals(m, leader, StringComparison.OrdinalIgnoreCase))
                {
                    members.Add(m);
                }
            }

            return CapNhatDoiHinhFile(tenTeam, leader, members);
        }

        private string TeleXemDanhSachTeam()
        {
            var teams = LoadTeams();
            if (teams.Count == 0)
            {
                return "👥 <b>Danh sách đội hình:</b> Chưa có nhóm nào trong <code>doi_hinh.cfg</code>.\n"
                     + "Dùng <code>/team [tên] add [nick1] [nick2]...</code> để tạo nhóm mới.";
            }

            var sb = new StringBuilder();
            sb.Append("👥 <b>Danh sách đội hình dùng chung (doi_hinh.cfg):</b>\n");
            foreach (var kv in teams)
            {
                var nicks = kv.Value;
                if (nicks.Count == 0) continue;
                string lead = nicks[0];
                var mems = nicks.Skip(1).Select(TenHienThi).ToList();

                sb.Append($"\n• <b>[team:{TelegramBot.Esc(kv.Key)}]</b> ({nicks.Count} nick):");
                sb.Append($"\n  👑 Trưởng: <b>{TelegramBot.Esc(TenHienThi(lead))}</b>");
                if (mems.Count > 0)
                {
                    sb.Append($"\n  ⚔️ Thành viên: {TelegramBot.Esc(string.Join(", ", mems))}");
                }
            }
            sb.Append("\n\n<i>👉 Đội hình dùng chung cho Cấm thuật (<code>/ct</code>), Sơn cáp (<code>/sc</code>), <code>/nv [team]</code>, <code>/dc [team]</code>,...</i>\n"
                    + "<i>👉 Chi tiết: <code>/team [tên_team]</code> | Thêm nick: <code>/team [tên_team] add [nick]</code></i>");
            return sb.ToString();
        }

        private string TeleXemChiTietTeam(string tenTeam)
        {
            var teams = LoadTeams();
            string key = teams.Keys.FirstOrDefault(k => string.Equals(k, tenTeam, StringComparison.OrdinalIgnoreCase));
            if (key == null)
            {
                return $"👥⚠️ Không tìm thấy đội hình <b>[team:{TelegramBot.Esc(tenTeam)}]</b> trong <code>doi_hinh.cfg</code>.\n"
                     + $"Dùng <code>/team {TelegramBot.Esc(tenTeam)} add &lt;nick1&gt;...</code> để tạo mới.";
            }

            var nicks = teams[key];
            if (nicks.Count == 0)
            {
                return $"👥 Đội hình <b>[team:{TelegramBot.Esc(key)}]</b> hiện đang trống.";
            }

            string lead = nicks[0];
            var mems = nicks.Skip(1).ToList();

            var sb = new StringBuilder();
            sb.Append($"👥 <b>Chi tiết đội hình [team:{TelegramBot.Esc(key)}] ({nicks.Count} nick):</b>\n");

            bool leadOnline = IsLoggedIn(lead);
            string leadChar = GetCharName(lead);
            string leadInfo = leadOnline ? $"🟢 Online (NV: {TelegramBot.Esc(leadChar)})" : "🔴 Offline";
            sb.Append($"\n👑 <b>Đội trưởng:</b> <b>{TelegramBot.Esc(TenHienThi(lead))}</b> — <i>{leadInfo}</i>");

            if (mems.Count > 0)
            {
                sb.Append("\n\n⚔️ <b>Thành viên:</b>");
                for (int i = 0; i < mems.Count; i++)
                {
                    string m = mems[i];
                    bool mOnline = IsLoggedIn(m);
                    string mChar = GetCharName(m);
                    string mInfo = mOnline ? $"🟢 (NV: {TelegramBot.Esc(mChar)})" : "🔴 Offline";
                    sb.Append($"\n  {i + 1}. <b>{TelegramBot.Esc(TenHienThi(m))}</b> — <i>{mInfo}</i>");
                }
            }

            sb.Append("\n\n<i>➕ Thêm nick: <code>/team " + TelegramBot.Esc(key) + " add nick1 nick2</code></i>\n"
                    + "<i>➖ Xoá nick: <code>/team " + TelegramBot.Esc(key) + " del nick1</code></i>\n"
                    + "<i>👑 Đổi trưởng: <code>/team " + TelegramBot.Esc(key) + " lead nick2</code></i>");
            return sb.ToString();
        }

        private string TeleThemNickVaoTeam(string tenTeam, List<string> rawNicks)
        {
            var teams = LoadTeams();
            string key = teams.Keys.FirstOrDefault(k => string.Equals(k, tenTeam, StringComparison.OrdinalIgnoreCase)) ?? tenTeam;

            string leader = "";
            var members = new List<string>();

            if (teams.TryGetValue(key, out var currentNicks) && currentNicks.Count > 0)
            {
                leader = currentNicks[0];
                members = currentNicks.Skip(1).ToList();
            }

            var added = new List<string>();
            var ignored = new List<string>();

            foreach (var raw in rawNicks)
            {
                string u = TimUsername(raw);
                if (string.IsNullOrEmpty(u)) continue;

                if (string.IsNullOrEmpty(leader))
                {
                    leader = u;
                    added.Add(u);
                }
                else if (string.Equals(u, leader, StringComparison.OrdinalIgnoreCase)
                         || members.Contains(u, StringComparer.OrdinalIgnoreCase))
                {
                    ignored.Add(u);
                }
                else
                {
                    members.Add(u);
                    added.Add(u);
                }
            }

            if (added.Count == 0)
            {
                if (ignored.Count > 0)
                    return $"👥⚠️ Các nick sau đã có sẵn trong đội hình <b>[team:{TelegramBot.Esc(key)}]</b>: {TelegramBot.Esc(string.Join(", ", ignored.Select(TenHienThi)))}.";
                return "👥⚠️ Không có nick hợp lệ nào để thêm vào đội hình.";
            }

            string err = CapNhatDoiHinhFile(key, leader, members);
            if (err.Contains("❌")) return err;

            var sb = new StringBuilder();
            sb.Append($"👥✅ <b>Đã thêm thành công {added.Count} nick vào [team:{TelegramBot.Esc(key)}]:</b>\n");
            sb.Append($"➕ Đã thêm: <b>{TelegramBot.Esc(string.Join(", ", added.Select(TenHienThi)))}</b>\n");
            if (ignored.Count > 0)
            {
                sb.Append($"ℹ️ Đã có sẵn: {TelegramBot.Esc(string.Join(", ", ignored.Select(TenHienThi)))}\n");
            }
            sb.Append($"\n👑 Đội trưởng: <b>{TelegramBot.Esc(TenHienThi(leader))}</b>\n");
            if (members.Count > 0)
            {
                sb.Append($"⚔️ Thành viên ({members.Count}): {TelegramBot.Esc(string.Join(", ", members.Select(TenHienThi)))}");
            }
            return sb.ToString();
        }

        private string TeleXoaNickKhoiTeam(string tenTeam, List<string> rawNicks)
        {
            var teams = LoadTeams();
            string key = teams.Keys.FirstOrDefault(k => string.Equals(k, tenTeam, StringComparison.OrdinalIgnoreCase));
            if (key == null || !teams.TryGetValue(key, out var currentNicks) || currentNicks.Count == 0)
            {
                return $"👥⚠️ Không tìm thấy đội hình <b>[team:{TelegramBot.Esc(tenTeam)}]</b> trong <code>doi_hinh.cfg</code>.";
            }

            string leader = currentNicks[0];
            var members = currentNicks.Skip(1).ToList();

            var removed = new List<string>();
            var notFound = new List<string>();
            bool leaderRemoved = false;

            foreach (var raw in rawNicks)
            {
                string u = TimUsername(raw);
                if (string.IsNullOrEmpty(u)) continue;

                if (string.Equals(u, leader, StringComparison.OrdinalIgnoreCase))
                {
                    removed.Add(u);
                    leaderRemoved = true;
                    leader = "";
                }
                else if (members.RemoveAll(m => string.Equals(m, u, StringComparison.OrdinalIgnoreCase)) > 0)
                {
                    removed.Add(u);
                }
                else
                {
                    notFound.Add(u);
                }
            }

            if (removed.Count == 0)
            {
                return $"👥⚠️ Không tìm thấy nick nào trong danh sách cần xoá thuộc đội hình <b>[team:{TelegramBot.Esc(key)}]</b>.";
            }

            string donLeadMsg = "";
            if (leaderRemoved)
            {
                if (members.Count > 0)
                {
                    leader = members[0];
                    members.RemoveAt(0);
                    donLeadMsg = $"\n👑 <i>Đã tự động đôn <b>{TelegramBot.Esc(TenHienThi(leader))}</b> lên làm Đội trưởng mới.</i>";
                }
                else
                {
                    // Nhóm không còn ai -> Xoá luôn nhóm khỏi file
                    XoaTeamFile(key);
                    return $"👥🗑️ <b>Đã xoá toàn bộ đội hình [team:{TelegramBot.Esc(key)}]</b> do không còn thành viên nào sau khi xoá.";
                }
            }

            string err = CapNhatDoiHinhFile(key, leader, members);
            if (err.Contains("❌")) return err;

            var sb = new StringBuilder();
            sb.Append($"👥🗑️ <b>Đã xoá {removed.Count} nick khỏi đội hình [team:{TelegramBot.Esc(key)}]:</b>\n");
            sb.Append($"➖ Đã xoá: <b>{TelegramBot.Esc(string.Join(", ", removed.Select(TenHienThi)))}</b>\n");
            if (notFound.Count > 0)
            {
                sb.Append($"ℹ️ Không có trong nhóm: {TelegramBot.Esc(string.Join(", ", notFound.Select(TenHienThi)))}\n");
            }
            if (!string.IsNullOrEmpty(donLeadMsg)) sb.Append(donLeadMsg + "\n");

            sb.Append($"\n👑 Đội trưởng: <b>{TelegramBot.Esc(TenHienThi(leader))}</b>\n");
            if (members.Count > 0)
            {
                sb.Append($"⚔️ Thành viên ({members.Count}): {TelegramBot.Esc(string.Join(", ", members.Select(TenHienThi)))}");
            }
            return sb.ToString();
        }

        private string TeleDoiTruongTeam(string tenTeam, string newLeaderRaw)
        {
            var teams = LoadTeams();
            string key = teams.Keys.FirstOrDefault(k => string.Equals(k, tenTeam, StringComparison.OrdinalIgnoreCase));
            if (key == null || !teams.TryGetValue(key, out var currentNicks) || currentNicks.Count == 0)
            {
                return $"👥⚠️ Không tìm thấy đội hình <b>[team:{TelegramBot.Esc(tenTeam)}]</b> trong <code>doi_hinh.cfg</code>.";
            }

            string newLeader = TimUsername(newLeaderRaw);
            if (string.IsNullOrEmpty(newLeader))
            {
                return "👥⚠️ Tên nick trưởng nhóm không hợp lệ.";
            }

            string oldLeader = currentNicks[0];
            if (string.Equals(oldLeader, newLeader, StringComparison.OrdinalIgnoreCase))
            {
                return $"👥 <b>{TelegramBot.Esc(TenHienThi(newLeader))}</b> đã là trưởng nhóm của [team:{TelegramBot.Esc(key)}] rồi.";
            }

            var members = currentNicks.Skip(1).ToList();
            members.RemoveAll(m => string.Equals(m, newLeader, StringComparison.OrdinalIgnoreCase));
            if (!members.Contains(oldLeader, StringComparer.OrdinalIgnoreCase))
            {
                members.Insert(0, oldLeader);
            }

            string err = CapNhatDoiHinhFile(key, newLeader, members);
            if (err.Contains("❌")) return err;

            return $"👥👑 <b>Đã đổi trưởng nhóm [team:{TelegramBot.Esc(key)}]:</b>\n"
                 + $"👑 Đội trưởng mới: <b>{TelegramBot.Esc(TenHienThi(newLeader))}</b>\n"
                 + $"⚔️ Thành viên: {TelegramBot.Esc(string.Join(", ", members.Select(TenHienThi)))}";
        }

        private string TeleXoaTeam(string tenTeam)
        {
            if (string.IsNullOrWhiteSpace(tenTeam))
            {
                return "👥⚠️ Vui lòng nhập tên đội hình cần xoá: <code>/team del &lt;tên_team&gt;</code>";
            }
            if (tenTeam.StartsWith("team:", StringComparison.OrdinalIgnoreCase))
                tenTeam = tenTeam.Substring(5).Trim();

            return XoaTeamFile(tenTeam);
        }

        private string XoaTeamFile(string tenTeam)
        {
            try
            {
                string path = DoiHinhFilePath;
                if (!File.Exists(path)) return $"👥⚠️ File <code>doi_hinh.cfg</code> chưa tồn tại.";

                List<string> lines = File.ReadAllLines(path, Encoding.UTF8).ToList();
                string targetHeader1 = $"[team:{tenTeam}]";
                string targetHeader2 = $"[{tenTeam}]";
                int blockStart = -1;
                int blockEnd = lines.Count;

                for (int i = 0; i < lines.Count; i++)
                {
                    string t = lines[i].Trim();
                    if (t.StartsWith("[") && t.EndsWith("]"))
                    {
                        if (string.Equals(t, targetHeader1, StringComparison.OrdinalIgnoreCase)
                            || string.Equals(t, targetHeader2, StringComparison.OrdinalIgnoreCase))
                        {
                            blockStart = i;
                        }
                        else if (blockStart >= 0)
                        {
                            blockEnd = i;
                            break;
                        }
                    }
                }

                if (blockStart < 0)
                {
                    return $"👥⚠️ Không tìm thấy đội hình <b>[team:{TelegramBot.Esc(tenTeam)}]</b> trong <code>doi_hinh.cfg</code>.";
                }

                lines.RemoveRange(blockStart, blockEnd - blockStart);
                File.WriteAllLines(path, lines, new UTF8Encoding(false));
                NapTeamCuaNick();

                Log($"👥 Telegram: Đã xóa đội hình [team:{tenTeam}] khỏi doi_hinh.cfg");
                return $"👥🗑️ <b>Đã xóa thành công đội hình [team:{TelegramBot.Esc(tenTeam)}]</b> khỏi <code>doi_hinh.cfg</code>.";
            }
            catch (Exception ex)
            {
                return $"👥❌ <b>Lỗi khi xóa đội hình:</b> {TelegramBot.Esc(ex.Message)}";
            }
        }

        private string CapNhatDoiHinhFile(string tenTeam, string leader, List<string> members)
        {
            try
            {
                string path = DoiHinhFilePath;
                List<string> lines = File.Exists(path)
                    ? File.ReadAllLines(path, Encoding.UTF8).ToList()
                    : new List<string>();

                string targetHeader1 = $"[team:{tenTeam}]";
                string targetHeader2 = $"[{tenTeam}]";
                int blockStart = -1;
                int blockEnd = lines.Count;

                for (int i = 0; i < lines.Count; i++)
                {
                    string t = lines[i].Trim();
                    if (t.StartsWith("[") && t.EndsWith("]"))
                    {
                        if (string.Equals(t, targetHeader1, StringComparison.OrdinalIgnoreCase)
                            || string.Equals(t, targetHeader2, StringComparison.OrdinalIgnoreCase))
                        {
                            blockStart = i;
                        }
                        else if (blockStart >= 0)
                        {
                            blockEnd = i;
                            break;
                        }
                    }
                }

                var newBlock = new List<string>
                {
                    targetHeader1,
                    $"truong = {leader}"
                };
                foreach (var m in members)
                {
                    if (!string.IsNullOrWhiteSpace(m) && !string.Equals(m, leader, StringComparison.OrdinalIgnoreCase))
                    {
                        newBlock.Add(m);
                    }
                }

                if (blockStart >= 0)
                {
                    // Thay thế khối cũ
                    lines.RemoveRange(blockStart, blockEnd - blockStart);
                    lines.InsertRange(blockStart, newBlock);
                }
                else
                {
                    // Thêm khối mới vào cuối
                    if (lines.Count > 0 && !string.IsNullOrWhiteSpace(lines[lines.Count - 1]))
                        lines.Add("");
                    lines.AddRange(newBlock);
                }

                File.WriteAllLines(path, lines, new UTF8Encoding(false));
                NapTeamCuaNick();

                Log($"👥 Telegram: Đã cập nhật đội hình [team:{tenTeam}] (trưởng: {leader}, {members.Count} mem)");
                return $"👥✅ <b>Đã cập nhật đội hình [team:{TelegramBot.Esc(tenTeam)}]</b> ({1 + members.Count} nick):\n"
                     + $"👑 Đội trưởng: <b>{TelegramBot.Esc(TenHienThi(leader))}</b>\n"
                     + (members.Count > 0 ? $"⚔️ Thành viên: {TelegramBot.Esc(string.Join(", ", members.Select(TenHienThi)))}" : "");
            }
            catch (Exception ex)
            {
                return $"👥❌ <b>Lỗi ghi doi_hinh.cfg:</b> {TelegramBot.Esc(ex.Message)}";
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // HỆ THỐNG HẸN GIỜ (SCHEDULER)
        // ══════════════════════════════════════════════════════════════════

        private string TeleThemHenGio(List<string> args)
        {
            if (args.Count < 2)
            {
                return "⏰⚠️ <b>Cú pháp hẹn giờ:</b>\n"
                     + "• <code>/hengio HH:mm [lệnh]</code> (Ví dụ: <code>/hengio 14:30 agt all</code>)\n"
                     + "• <code>/hengio daily HH:mm [lệnh]</code> (Lặp lại mỗi ngày: <code>/hengio daily 06:30 nv all</code>)\n"
                     + "• <code>/timer 30m [lệnh]</code> (Đếm ngược: <code>/timer 30m diacung all</code>)";
            }

            bool isDaily = false;
            int argIdx = 0;

            if (args[0].Equals("daily", StringComparison.OrdinalIgnoreCase)
                || args[0].Equals("hangngay", StringComparison.OrdinalIgnoreCase))
            {
                isDaily = true;
                argIdx = 1;
            }

            if (args.Count <= argIdx + 1)
            {
                return "⏰⚠️ Thiếu thời gian hoặc lệnh cần chạy. Ví dụ: <code>/hengio 14:30 agt all</code>";
            }

            string timeStr = args[argIdx].Trim();
            string command = string.Join(" ", args.Skip(argIdx + 1));

            // Kiểm tra định dạng HH:mm
            if (!DateTime.TryParseExact(timeStr, new[] { "H:m", "HH:mm", "H:mm", "HH:m" },
                System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.None, out DateTime parsedTime))
            {
                // Kiểm tra nếu là dạng +30m
                if (timeStr.StartsWith("+") && timeStr.EndsWith("m", StringComparison.OrdinalIgnoreCase)
                    && int.TryParse(timeStr.Substring(1, timeStr.Length - 2), out int plusMin))
                {
                    return ThemHenGioTimer(plusMin, command);
                }
                return $"⏰⚠️ Định dạng giờ không hợp lệ: <b>{TelegramBot.Esc(timeStr)}</b>. Vui lòng dùng <code>HH:mm</code> (ví dụ <code>14:30</code>).";
            }

            var now = DateTime.Now;
            var runAt = new DateTime(now.Year, now.Month, now.Day, parsedTime.Hour, parsedTime.Minute, 0);
            if (runAt <= now && !isDaily)
            {
                // Nếu giờ đã qua trong ngày và không phải daily, đặt cho ngày mai
                runAt = runAt.AddDays(1);
            }
            else if (runAt <= now && isDaily)
            {
                runAt = runAt.AddDays(1);
            }

            var task = _scheduler.ThemLich(runAt, command, isDaily, timeStr);
            Log($"⏰ Telegram: Đã tạo lịch hẹn #{task.Id} chạy '{command}' lúc {runAt:dd/MM HH:mm}" + (isDaily ? " (Hằng ngày)" : ""));

            return $"⏰✅ <b>Đã tạo lịch hẹn #{task.Id}</b>\n"
                 + $"📅 Thời gian: <b>{runAt:dd/MM/yyyy HH:mm}</b>" + (isDaily ? " <i>(Lặp lại hàng ngày)</i>" : "") + "\n"
                 + $"⚙️ Lệnh: <code>/{TelegramBot.Esc(command)}</code>\n"
                 + $"<i>(Gõ <code>/dshengio</code> để xem danh sách hoặc <code>/huyhengio {task.Id}</code> để huỷ)</i>";
        }

        private string TeleThemTimer(List<string> args)
        {
            if (args.Count < 2)
            {
                return "⏰⚠️ <b>Cú pháp đếm ngược (timer):</b>\n"
                     + "<code>/timer [số_phút]m [lệnh]</code>\n"
                     + "<i>Ví dụ: <code>/timer 30m diacung all</code> hoặc <code>/timer 15m gomdo</code></i>";
            }

            string minStr = args[0].ToLowerInvariant().Replace("m", "").Replace("p", "").Replace("phut", "").Trim();
            if (!int.TryParse(minStr, out int minutes) || minutes <= 0)
            {
                return $"⏰⚠️ Số phút không hợp lệ: <b>{TelegramBot.Esc(args[0])}</b>.";
            }

            string command = string.Join(" ", args.Skip(1));
            return ThemHenGioTimer(minutes, command);
        }

        private string ThemHenGioTimer(int minutes, string command)
        {
            var runAt = DateTime.Now.AddMinutes(minutes);
            var task = _scheduler.ThemLich(runAt, command, isDaily: false);
            Log($"⏰ Telegram: Đã tạo timer #{task.Id} chạy '{command}' sau {minutes} phút (lúc {runAt:HH:mm:ss})");

            return $"⏰✅ <b>Đã đặt hẹn giờ đếm ngược #{task.Id}</b>\n"
                 + $"⏳ Sẽ chạy sau: <b>{minutes} phút</b> (lúc <b>{runAt:HH:mm:ss}</b>)\n"
                 + $"⚙️ Lệnh: <code>/{TelegramBot.Esc(command)}</code>";
        }

        private string TeleDanhSachHenGio()
        {
            var list = _scheduler.LayDanhSach();
            if (list.Count == 0)
            {
                return "⏰ <b>Danh sách hẹn giờ:</b> Hiện không có lịch hẹn nào đang chờ.";
            }

            var sb = new StringBuilder();
            sb.Append($"⏰ <b>Danh sách hẹn giờ đang chờ ({list.Count} lịch):</b>\n");
            foreach (var t in list)
            {
                var conLai = t.RunAt - DateTime.Now;
                string conLaiStr = conLai.TotalMinutes > 0
                    ? $" (còn {(int)conLai.TotalMinutes}p{conLai.Seconds:D2}s)"
                    : " (đang kích hoạt)";

                sb.Append($"\n• <b>#{t.Id}</b>: <code>/{TelegramBot.Esc(t.Command)}</code>\n"
                        + $"  🕒 <b>{t.RunAt:dd/MM HH:mm}</b>{conLaiStr}"
                        + (t.IsDaily ? " <i>[Hàng ngày]</i>" : ""));
            }
            sb.Append("\n\n<i>Huỷ lịch: <code>/huyhengio [ID]</code> hoặc <code>/huyhengio all</code></i>");
            return sb.ToString();
        }

        private string TeleHuyHenGio(List<string> args)
        {
            if (args.Count == 0)
            {
                return "⏰⚠️ Vui lòng nhập ID lịch cần huỷ hoặc <code>all</code>. Ví dụ: <code>/huyhengio a1b2c3</code>";
            }

            string id = args[0].Trim();
            if (id.Equals("all", StringComparison.OrdinalIgnoreCase) || id.Equals("*"))
            {
                int c = _scheduler.HuyTatCa();
                return $"⏰🗑️ Đã huỷ toàn bộ <b>{c}</b> lịch hẹn giờ.";
            }

            bool ok = _scheduler.HuyLich(id);
            if (ok)
            {
                return $"⏰🗑️ Đã huỷ thành công lịch hẹn <b>#{id}</b>.";
            }
            return $"⏰⚠️ Không tìm thấy lịch hẹn nào có ID <b>#{id}</b>.";
        }

        // ══════════════════════════════════════════════════════════════════
        // HƯỚNG DẪN LỆNH TELEGRAM
        // ══════════════════════════════════════════════════════════════════

        private string TeleHuongDan()
        {
            return
@"🎮 <b>DANH SÁCH LỆNH BOT MANAGER LÀNG LÁ</b> 🎮

👥 <b>1. QUẢN LÝ ĐỘI HÌNH & DÙNG CHUNG CÁC MODULE</b>
• <code>/team list</code> (hoặc <code>/team</code>) — Xem tất cả đội hình hiện có
• <code>/team [tên_team]</code> — Xem chi tiết đội hình & trạng thái online
• <code>/team [tên_team] add nick1 nick2 ...</code> — Thêm nick vào đội hình
• <code>/team [tên_team] del nick1 nick2 ...</code> — Xoá nick khỏi đội hình (tự đôn trưởng nhóm mới)
• <code>/team [tên_team] lead [nick]</code> — Đổi trưởng nhóm
• <code>/team [tên_team] truong mem1 mem2 ...</code> — Tạo/ghi đè mới đội hình (tối đa 6 nick)
• <code>/team del [tên_team]</code> — Xoá toàn bộ 1 đội hình
👉 <i>Đội hình được dùng chung cho Cấm thuật (<code>/ct</code>), Sơn cáp (<code>/sc</code>) hoặc chạy theo team: <code>/nv team1</code>, <code>/dc team1</code>, <code>/kill team1</code>, <code>/wake team1</code>,...</i>

⚔️ <b>2. CẤM THUẬT & SƠN CÁP</b>
• <code>/camthuat</code> (hoặc <code>/ct</code>) — Chạy Cấm thuật cho tất cả nhóm trong <code>doi_hinh.cfg</code>
• <code>/ct [tên_team]</code> — Chạy Cấm thuật cho riêng 1 team (ví dụ: <code>/ct team1</code>)
• <code>/camthuat stop</code> — Dừng Cấm thuật
• <code>/soncap</code> (hoặc <code>/sc</code>) — Chạy Sơn cáp cho tất cả nhóm
• <code>/sc [tên_team]</code> — Chạy Sơn cáp cho riêng 1 team (ví dụ: <code>/sc team1</code>)
• <code>/soncap stop</code> — Dừng Sơn cáp

🏰 <b>3. ẢI GIA TỘC</b>
• <code>/agt all</code> (hoặc <code>/agt team1</code>) — Chạy Ải gia tộc cho toàn bộ hoặc theo team/nick
• <code>/agt stop</code> — Dừng Ải gia tộc

▶️ <b>4. AUTO NHIỆM VỤ NGÀY</b>
• <code>/nv all</code> (hoặc <code>/nv team1</code>) — Bật Auto NV ngày cho tất cả hoặc theo team
• <code>/nv stop</code> — Tắt Auto NV ngày

🏯 <b>5. ĐỊA CUNG</b>
• <code>/diacung all</code> (hoặc <code>/dc team1</code>) — Chạy Địa cung (tự nhận chìa/vào hầm)

🎒 <b>6. GOM ĐỒ & TIỆN ÍCH</b>
• <code>/gomdo</code> (hoặc <code>/gom</code>) — Gom đồ về lead theo <code>[gom]</code>
• <code>/tinhthach all</code> (hoặc <code>/tt team1</code>) — Đổi tinh thạch tại NPC Kinkaku
• <code>/quiz all</code> — Tự động trả lời Auto Quiz NPC
• <code>/velang all</code> (hoặc <code>/vl team1</code>) — Cho các nick về làng
• <code>/stop all</code> — Dừng tất cả hoạt động

💻 <b>7. QUẢN LÝ CLIENT GAME</b>
• <code>/wake all</code> (hoặc <code>/wake team1</code>) — Khởi chạy client game
• <code>/kill all</code> (hoặc <code>/kill team1</code>) — Tắt client game

⏰ <b>8. HẸN GIỜ TỰ ĐỘNG</b>
• <code>/hengio 14:30 agt all</code> — Hẹn chạy lúc 14:30
• <code>/hengio daily 06:30 nv all</code> — Hẹn chạy lặp lại mỗi ngày lúc 06:30
• <code>/timer 30m diacung all</code> — Đếm ngược sau 30 phút nữa chạy
• <code>/dshengio</code> — Xem danh sách lịch hẹn đang chờ
• <code>/huyhengio [ID hoặc all]</code> — Huỷ lịch hẹn (ví dụ: <code>/huyhengio a1b2c3</code> hoặc <code>/huyhengio all</code>)

📊 <b>9. TRẠNG THÁI</b>
• <code>/status</code> (hoặc <code>/st</code>) — Xem bảng trạng thái tức thì
• <code>/help</code> — Xem lại hướng dẫn này";
        }
    }
}
