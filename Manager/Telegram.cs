using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Manager
{
    /// <summary>
    /// Cấu hình bắn tin Telegram — đọc từ telegram.cfg, cùng kiểu khối như doi_hinh.cfg.
    ///
    /// Token bot LÀ BÍ MẬT: ai có nó là chiếm được bot. Nên file này để cạnh config.json
    /// (trong thư mục bin, đã bị gitignore) và tên file cũng được ghi thẳng vào .gitignore.
    /// Không có file thì mọi thứ tắt lặng lẽ — Manager chạy y như trước, không lỗi.
    /// </summary>
    public class TelegramCauHinh
    {
        public string Token = "";
        public string ChatId = "";
        public bool Bat = false;

        // Bảng theo dõi: MỘT tin nhắn được sửa tại chỗ, không đẩy tin mới mỗi lần cập nhật.
        // 18 nick mà mỗi thay đổi một tin thì Telegram thành bãi rác trong mười phút.
        public bool BangTrangThai = true;
        public int  BangGiay = 20;

        // Tin rời cho việc đáng nhớ: giao dịch gom đồ, lỗi cần người can thiệp.
        public bool TinGiaoDich = true;
        public bool TinLoi = true;

        public static string DuongDan()
        {
            string local = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "telegram.cfg");
            if (File.Exists(local)) return local;

            var dir = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.Parent)
            {
                string here = Path.Combine(dir.FullName, "telegram.cfg");
                if (File.Exists(here)) return here;
            }
            return local;
        }

        public static TelegramCauHinh Doc()
        {
            var c = new TelegramCauHinh();
            string path = DuongDan();
            if (!File.Exists(path)) return c;

            try
            {
                foreach (var raw in File.ReadAllLines(path, Encoding.UTF8))
                {
                    string line = raw;
                    int cmt = line.IndexOf('#');
                    if (cmt >= 0) line = line.Substring(0, cmt);
                    line = line.Trim();
                    if (line.Length == 0 || line.StartsWith("[")) continue;

                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    string k = line.Substring(0, eq).Trim().ToLowerInvariant();
                    string v = line.Substring(eq + 1).Trim();

                    switch (k)
                    {
                        case "token":           c.Token = v; break;
                        case "chat_id":         c.ChatId = v; break;
                        case "bat":             c.Bat = La1(v); break;
                        case "bang_trang_thai": c.BangTrangThai = La1(v); break;
                        case "bang_giay":       c.BangGiay = SoNguyen(v, c.BangGiay, 5, 3600); break;
                        case "tin_giao_dich":   c.TinGiaoDich = La1(v); break;
                        case "tin_loi":         c.TinLoi = La1(v); break;
                    }
                }
            }
            catch (Exception) { /* file hỏng → coi như chưa cấu hình, không làm chết Manager */ }

            // Thiếu token là chịu, không đoán được. Thiếu chat_id thì VẪN BẬT: bot tự dò ra
            // từ câu người dùng nhắn cho nó (xem TelegramBot.DoChatIdAsync).
            if (c.Token.Length == 0) c.Bat = false;
            return c;
        }

        /// <summary>Ghi chat_id vừa dò được vào lại telegram.cfg để lần sau khỏi dò.</summary>
        public static void GhiChatId(string chatId)
        {
            try
            {
                string path = DuongDan();
                if (!File.Exists(path)) return;
                var dong = File.ReadAllLines(path, Encoding.UTF8);
                bool da = false;
                for (int i = 0; i < dong.Length; i++)
                {
                    string t = dong[i].TrimStart();
                    if (t.StartsWith("#")) continue;
                    int eq = t.IndexOf('=');
                    if (eq <= 0) continue;
                    if (!t.Substring(0, eq).Trim().Equals("chat_id", StringComparison.OrdinalIgnoreCase)) continue;
                    dong[i] = "chat_id = " + chatId;
                    da = true;
                    break;
                }
                if (!da) return;
                File.WriteAllLines(path, dong, new UTF8Encoding(false));
            }
            catch (Exception) { /* không ghi được thì lần sau dò lại, không sao */ }
        }

        private static bool La1(string v)
        {
            v = (v ?? "").Trim().ToLowerInvariant();
            return v == "1" || v == "true" || v == "yes" || v == "on" || v == "co" || v == "có";
        }

        private static int SoNguyen(string v, int macDinh, int min, int max)
        {
            if (!int.TryParse((v ?? "").Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out int n))
                return macDinh;
            return n < min ? min : (n > max ? max : n);
        }

        /// <summary>Ghi ra file mẫu để người dùng chỉ việc điền token — chỉ khi chưa có file.</summary>
        public static void TaoMauNeuThieu()
        {
            string path = DuongDan();
            if (File.Exists(path)) return;
            try
            {
                File.WriteAllText(path,
@"# BẮN TIN TELEGRAM — để trống token là tắt hẳn, Manager chạy y như cũ.
#
# CHỈ CẦN ĐIỀN token. Các bước:
#   1. Mở Telegram, tìm @BotFather -> /newbot -> đặt tên -> nó trả về token, chép vào đây.
#   2. Mở Manager.
#   3. Tìm bot vừa tạo trên Telegram, bấm Start (hoặc nhắn một câu bất kỳ).
#      Muốn bắn vào NHÓM thì thêm bot vào nhóm rồi nhắn một câu trong nhóm đó.
#   4. Manager tự dò chat_id và tự ghi xuống dòng dưới. Không phải mở getUpdates.
#
# FILE NÀY CHỨA BÍ MẬT — đừng commit, đừng gửi cho ai.

[bot]
token   =
# Để trống — Manager tự điền sau khi nhắn cho bot.
chat_id =
bat     = 1

[bang]
# Bảng theo dõi là MỘT tin nhắn được sửa tại chỗ, không đẩy tin mới mỗi lần đổi.
bang_trang_thai = 1
bang_giay       = 20

[tin]
# Tin rời, chỉ cho việc đáng nhớ.
tin_giao_dich    = 1
tin_loi          = 1
", new UTF8Encoding(false));
            }
            catch (Exception) { /* không tạo được thì thôi, tính năng vẫn tắt an toàn */ }
        }
    }

    /// <summary>
    /// Đẩy tin lên Telegram bằng một hàng đợi + một luồng nền.
    ///
    /// Vì sao phải qua hàng đợi: gọi thẳng HTTP từ chỗ xử lý gói tin của client là mỗi lần
    /// mạng lag lại chặn luôn vòng đọc gói của nick đó. Telegram cũng chặn tốc độ (~20 tin/
    /// phút vào một nhóm), gọi bừa là ăn 429 rồi mất tin. Ở đây tin xếp hàng, luồng nền gửi
    /// giãn ra, quá tải thì BỎ TIN CŨ chứ không phình bộ nhớ vô hạn.
    ///
    /// Mọi lỗi đều nuốt: bắn tin hỏng thì cùng lắm mất thông báo, không được phép làm hỏng
    /// một lượt chạy đang tốt.
    /// </summary>
    public class TelegramBot : IDisposable
    {
        private readonly HttpClient _http;
        private readonly TelegramCauHinh _cf;
        private readonly Queue<string> _hangDoi = new Queue<string>();
        private readonly object _khoa = new object();
        private readonly CancellationTokenSource _dung = new CancellationTokenSource();
        private readonly Action<string> _log;

        // Tin bảng theo dõi: gửi lần đầu rồi SỬA mãi tin đó. -1 = chưa gửi lần nào.
        private int _idTinBang = -1;
        private string _bangCuoi = "";

        private const int TOI_DA_HANG_DOI = 200;

        public bool Bat => _cf.Bat;
        public TelegramCauHinh CauHinh => _cf;

        public TelegramBot(TelegramCauHinh cf, Action<string> log)
        {
            _cf = cf;
            _log = log ?? (s => { });
            _http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            if (_cf.Bat) Task.Run(() => VongGuiAsync(_dung.Token));
        }

        /// <summary>Xếp một tin vào hàng đợi. Không chặn người gọi, không ném lỗi.</summary>
        public void Gui(string text)
        {
            if (!_cf.Bat || string.IsNullOrWhiteSpace(text)) return;
            lock (_khoa)
            {
                // Đầy thì bỏ tin CŨ NHẤT: tin mới luôn sát thực tế hơn tin cũ.
                while (_hangDoi.Count >= TOI_DA_HANG_DOI) _hangDoi.Dequeue();
                _hangDoi.Enqueue(text);
            }
        }

        private async Task VongGuiAsync(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                // Chưa biết gửi về đâu thì GIỮ tin lại, đừng lấy ra rồi vứt: người dùng nhắn cho
                // bot xong là chat_id có ngay, lúc đó những gì xảy ra trước đó vẫn còn để đọc.
                if (_cf.ChatId.Length == 0) { try { await Task.Delay(1000, ct); } catch { return; } continue; }

                string tin = null;
                lock (_khoa) { if (_hangDoi.Count > 0) tin = _hangDoi.Dequeue(); }

                if (tin == null) { try { await Task.Delay(500, ct); } catch { return; } continue; }

                await GuiThatAsync(tin, ct);
                // Giãn nhịp cho khỏi ăn 429. 20 tin/phút là trần của nhóm.
                try { await Task.Delay(3200, ct); } catch { return; }
            }
        }

        private async Task GuiThatAsync(string text, CancellationToken ct)
        {
            try
            {
                var noi = new Dictionary<string, object>
                {
                    ["chat_id"] = _cf.ChatId,
                    ["text"] = text,
                    ["parse_mode"] = "HTML",
                    ["disable_web_page_preview"] = true
                };
                var body = new StringContent(JsonSerializer.Serialize(noi), Encoding.UTF8, "application/json");
                var rep = await _http.PostAsync($"https://api.telegram.org/bot{_cf.Token}/sendMessage", body, ct);
                if (!rep.IsSuccessStatusCode)
                {
                    string errBody = await DocGon(rep);
                    _log($"⚠ Telegram sendMessage {(int)rep.StatusCode}: {errBody}");
                    // Nếu lỗi do parse HTML (400), thử gửi lại dạng text thường để không bao giờ bị nuốt tin
                    if ((int)rep.StatusCode == 400)
                    {
                        try
                        {
                            var fallback = new Dictionary<string, object>
                            {
                                ["chat_id"] = _cf.ChatId,
                                ["text"] = StripHtml(text),
                                ["disable_web_page_preview"] = true
                            };
                            var fbBody = new StringContent(JsonSerializer.Serialize(fallback), Encoding.UTF8, "application/json");
                            await _http.PostAsync($"https://api.telegram.org/bot{_cf.Token}/sendMessage", fbBody, ct);
                        }
                        catch { }
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { _log($"⚠ Telegram sendMessage lỗi: {ex.Message}"); }
        }

        public static string StripHtml(string input)
        {
            if (string.IsNullOrEmpty(input)) return "";
            return System.Text.RegularExpressions.Regex.Replace(input, "<.*?>", string.Empty);
        }

        /// <summary>
        /// Gửi ẢNH captcha lên nhóm và trả về message_id của tin đó.
        ///
        /// message_id là mấu chốt của cả cơ chế trả lời: người dùng REPLY vào đúng tin này, và
        /// Telegram kèm `reply_to_message.message_id` trong bản cập nhật ⇒ Manager biết mã vừa gõ
        /// là của nick nào mà KHÔNG cần người dùng gõ tên. Hai nick dính bùa cùng lúc cũng không
        /// lẫn được.
        ///
        /// Reply cũng là cách né privacy mode: bot trong nhóm mặc định chỉ nhận được lệnh /xxx và
        /// tin trả lời chính nó. Dùng reply thì khỏi phải vào BotFather tắt privacy.
        ///
        /// Đi thẳng, KHÔNG qua hàng đợi 3.2 giây của tin thường: nick đang nằm chết chờ tấm ảnh
        /// này, và nó nằm cho tới khi có người nhập đúng mã chứ không có đồng hồ nào đếm ngược.
        /// </summary>
        public async Task<int> GuiAnhLayIdAsync(byte[] png, string caption)
        {
            if (!_cf.Bat || png == null || png.Length == 0) return -1;
            try
            {
                using (var form = new MultipartFormDataContent())
                {
                    form.Add(new StringContent(_cf.ChatId), "chat_id");
                    form.Add(new StringContent(caption ?? ""), "caption");
                    form.Add(new StringContent("HTML"), "parse_mode");
                    var anh = new ByteArrayContent(png);
                    anh.Headers.ContentType =
                        new System.Net.Http.Headers.MediaTypeHeaderValue("image/png");
                    form.Add(anh, "photo", "captcha.png");

                    var rep = await _http.PostAsync(
                        $"https://api.telegram.org/bot{_cf.Token}/sendPhoto", form, _dung.Token);
                    string body = await rep.Content.ReadAsStringAsync();
                    if (!rep.IsSuccessStatusCode)
                    {
                        _log($"⚠ Telegram sendPhoto {(int)rep.StatusCode}: {Gon(body)}");
                        return -1;
                    }
                    using (var doc = JsonDocument.Parse(body))
                    {
                        if (doc.RootElement.TryGetProperty("result", out var r)
                            && r.TryGetProperty("message_id", out var mid))
                            return mid.GetInt32();
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { _log($"⚠ Telegram sendPhoto lỗi: {ex.Message}"); }
            return -1;
        }

        private int _docLoiDem;
        public string BotUsername { get; private set; } = "";

        /// <summary>
        /// Vòng ĐỌC tin trả lời (giải captcha) và NHẬN LỆNH điều khiển từ nhóm/chat Telegram.
        ///
        /// Nhịp 2 giây: vừa bảo đảm phản hồi lệnh nhanh chóng, vừa kịp thời giải bùa uế thổ.
        /// `offset` phải giữ và luôn tiến: Telegram trả lại y nguyên các bản cập nhật chưa được
        /// xác nhận, không giữ offset là mỗi vòng lại xử lý lại đúng tin cũ.
        /// </summary>
        public void BatDauDocTinNhan(Action<int, string> khiCoTraLoiCaptcha, Action<string, Action<string>> khiCoLenh)
        {
            if (!_cf.Bat) return;
            _ = Task.Run(async () =>
            {
                long offset = 0;
                while (!_dung.IsCancellationRequested)
                {
                    try
                    {
                        var rep = await _http.GetAsync(
                            $"https://api.telegram.org/bot{_cf.Token}/getUpdates"
                            + $"?offset={offset}&timeout=0&allowed_updates=[\"message\"]",
                            _dung.Token);
                        if (rep.IsSuccessStatusCode)
                        {
                            using (var doc = JsonDocument.Parse(await rep.Content.ReadAsStringAsync()))
                            {
                                if (doc.RootElement.TryGetProperty("result", out var arr))
                                {
                                    foreach (var up in arr.EnumerateArray())
                                    {
                                        if (up.TryGetProperty("update_id", out var uid))
                                            offset = uid.GetInt64() + 1;
                                        if (!up.TryGetProperty("message", out var msg)) continue;

                                        // Tự động nhận diện chat_id nếu chưa có
                                        if (msg.TryGetProperty("chat", out var chatElem) && chatElem.TryGetProperty("id", out var idElem))
                                        {
                                            string cid = idElem.ValueKind == JsonValueKind.Number
                                                ? idElem.GetInt64().ToString(CultureInfo.InvariantCulture)
                                                : idElem.ToString();
                                            if (string.IsNullOrEmpty(_cf.ChatId) && !string.IsNullOrEmpty(cid))
                                            {
                                                _cf.ChatId = cid;
                                                TelegramCauHinh.GhiChatId(cid);
                                                _log($"📨 Telegram: đã nhận diện chat_id {cid} và lưu vào telegram.cfg. Bắt đầu nhận lệnh.");
                                            }
                                        }

                                        if (!msg.TryGetProperty("text", out var txtElement)) continue;
                                        string rawText = txtElement.GetString() ?? "";
                                        if (string.IsNullOrWhiteSpace(rawText)) continue;

                                        // Kiểm tra nếu là reply vào tin ảnh captcha
                                        if (msg.TryGetProperty("reply_to_message", out var rt)
                                            && rt.TryGetProperty("message_id", out var rid))
                                        {
                                            khiCoTraLoiCaptcha?.Invoke(rid.GetInt32(), rawText.Trim());
                                            continue;
                                        }

                                        // Nếu là tin nhắn lệnh (bắt đầu bằng / hoặc văn bản)
                                        if (khiCoLenh != null)
                                        {
                                            string text = rawText.Trim();
                                            // Xoá @bot_username ở lệnh nếu có (ví dụ /agt@mybot all -> /agt all)
                                            if (text.StartsWith("/") && !string.IsNullOrEmpty(BotUsername))
                                            {
                                                string botTag = "@" + BotUsername;
                                                int tagIdx = text.IndexOf(botTag, StringComparison.OrdinalIgnoreCase);
                                                if (tagIdx > 0)
                                                {
                                                    text = text.Remove(tagIdx, botTag.Length);
                                                }
                                            }

                                            // Gửi phản hồi lại qua hàm Gui
                                            khiCoLenh(text, repText => Gui(repText));
                                        }
                                    }
                                }
                            }
                            if (_docLoiDem > 0)
                            {
                                _log($"📨 Telegram: đọc tin nhắn nối lại được (hỏng {_docLoiDem} nhịp).");
                                _docLoiDem = 0;
                            }
                        }
                        else if (++_docLoiDem == 1)
                            _log($"⚠ Telegram getUpdates {(int)rep.StatusCode}: {await DocGon(rep)}");
                    }
                    catch (OperationCanceledException) when (_dung.IsCancellationRequested) { return; }
                    catch (Exception ex)
                    {
                        if (++_docLoiDem == 1)
                            _log($"⚠ Telegram: đọc tin nhắn lỗi — {Gon(ex.Message)}"
                               + " (tự thử lại mỗi 2 giây, KHÔNG chết luồng)");
                    }
                    try { await Task.Delay(2000, _dung.Token); } catch { return; }
                }
                _log("⚠ Telegram: vòng đọc tin nhắn đã dừng.");
            });
        }

        public void BatDauDocTraLoi(Action<int, string> khiCoTraLoi)
        {
            BatDauDocTinNhan(khiCoTraLoi, null);
        }

        /// <summary>
        /// Cập nhật BẢNG theo dõi: gửi lần đầu, các lần sau sửa đúng tin đó.
        /// Nội dung không đổi thì không gọi API — Telegram trả lỗi "message is not modified"
        /// và mỗi lần gọi thừa đều ăn vào hạn mức.
        /// </summary>
        public async Task CapNhatBangAsync(string text)
        {
            if (!_cf.Bat || !_cf.BangTrangThai || string.IsNullOrWhiteSpace(text)) return;
            if (text == _bangCuoi) return;
            _bangCuoi = text;

            try
            {
                if (_idTinBang < 0)
                {
                    int id = await GuiLayIdAsync(text);
                    if (id > 0) _idTinBang = id;
                    return;
                }

                var noi = new Dictionary<string, object>
                {
                    ["chat_id"] = _cf.ChatId,
                    ["message_id"] = _idTinBang,
                    ["text"] = text,
                    ["parse_mode"] = "HTML",
                    ["disable_web_page_preview"] = true
                };
                var body = new StringContent(JsonSerializer.Serialize(noi), Encoding.UTF8, "application/json");
                var rep = await _http.PostAsync(
                    $"https://api.telegram.org/bot{_cf.Token}/editMessageText", body, _dung.Token);

                if (!rep.IsSuccessStatusCode)
                {
                    string chiTiet = await DocGon(rep);
                    // Tin bị xoá / quá cũ để sửa → dựng lại tin mới, đừng chết cứng ở đây.
                    if (chiTiet.Contains("message to edit not found")
                        || chiTiet.Contains("message can't be edited")
                        || chiTiet.Contains("MESSAGE_ID_INVALID"))
                    {
                        _idTinBang = -1;
                        int id = await GuiLayIdAsync(text);
                        if (id > 0) _idTinBang = id;
                    }
                    else if (!chiTiet.Contains("message is not modified"))
                    {
                        _log($"⚠ Telegram editMessageText {(int)rep.StatusCode}: {chiTiet}");
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { _log($"⚠ Telegram cập nhật bảng lỗi: {ex.Message}"); }
        }

        private async Task<int> GuiLayIdAsync(string text)
        {
            try
            {
                var noi = new Dictionary<string, object>
                {
                    ["chat_id"] = _cf.ChatId,
                    ["text"] = text,
                    ["parse_mode"] = "HTML",
                    ["disable_web_page_preview"] = true
                };
                var body = new StringContent(JsonSerializer.Serialize(noi), Encoding.UTF8, "application/json");
                var rep = await _http.PostAsync(
                    $"https://api.telegram.org/bot{_cf.Token}/sendMessage", body, _dung.Token);
                string s = await rep.Content.ReadAsStringAsync();
                if (!rep.IsSuccessStatusCode)
                {
                    _log($"⚠ Telegram gửi bảng {(int)rep.StatusCode}: {Gon(s)}");
                    return -1;
                }
                using (var doc = JsonDocument.Parse(s))
                {
                    if (doc.RootElement.TryGetProperty("result", out var r)
                        && r.TryGetProperty("message_id", out var mid))
                        return mid.GetInt32();
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { _log($"⚠ Telegram gửi bảng lỗi: {ex.Message}"); }
            return -1;
        }

        /// <summary>Thử kết nối và báo lại ngay — để biết token/chat_id đúng chưa mà không phải đoán.</summary>
        public async Task<bool> KiemTraAsync()
        {
            if (!_cf.Bat) return false;
            try
            {
                var rep = await _http.GetAsync($"https://api.telegram.org/bot{_cf.Token}/getMe", _dung.Token);
                string s = await rep.Content.ReadAsStringAsync();
                if (!rep.IsSuccessStatusCode)
                {
                    _log($"❌ Telegram: token sai hoặc bot bị khoá ({(int)rep.StatusCode}) — {Gon(s)}");
                    return false;
                }
                string ten = "?";
                using (var doc = JsonDocument.Parse(s))
                {
                    if (doc.RootElement.TryGetProperty("result", out var r)
                        && r.TryGetProperty("username", out var u))
                        ten = u.GetString() ?? "?";
                }
                BotUsername = ten;
                if (_cf.ChatId.Length > 0)
                {
                    _log($"📨 Telegram: đã nối bot @{ten}, bắn về chat {_cf.ChatId}.");
                    return true;
                }

                _log($"📨 Telegram: đã nối bot @{ten}. CHƯA BIẾT GỬI VỀ ĐÂU — mở Telegram, "
                   + $"tìm @{ten} rồi bấm Start (hoặc nhắn một câu bất kỳ). "
                   + $"Muốn bắn vào NHÓM thì thêm @{ten} vào nhóm rồi nhắn một câu trong đó. "
                   + $"Tin đang được giữ lại, có chat_id là gửi hết.");
                _ = DoChatIdAsync();
                return true;
            }
            catch (Exception ex) { _log($"❌ Telegram: không nối được — {ex.Message}"); return false; }
        }

        /// <summary>
        /// Tự dò chat_id từ câu người dùng vừa nhắn cho bot, thay vì bắt họ mở getUpdates
        /// trên trình duyệt rồi chép tay một con số.
        ///
        /// Dò lặp chứ không hỏi một lần: lúc Manager khởi động thì người dùng chưa kịp nhắn.
        /// Ưu tiên chat NHÓM (id âm) nếu có — thêm bot vào nhóm là chủ đích muốn bắn vào nhóm,
        /// còn câu nhắn riêng lúc tạo bot chỉ là để kích hoạt.
        /// </summary>
        private async Task DoChatIdAsync()
        {
            for (int lan = 0; lan < 60 && !_dung.IsCancellationRequested; lan++)   // ~5 phút
            {
                try
                {
                    var rep = await _http.GetAsync(
                        $"https://api.telegram.org/bot{_cf.Token}/getUpdates?limit=100", _dung.Token);
                    if (rep.IsSuccessStatusCode)
                    {
                        string s = await rep.Content.ReadAsStringAsync();
                        string rieng = null, nhom = null;
                        using (var doc = JsonDocument.Parse(s))
                        {
                            if (doc.RootElement.TryGetProperty("result", out var arr)
                                && arr.ValueKind == JsonValueKind.Array)
                            {
                                foreach (var up in arr.EnumerateArray())
                                {
                                    foreach (var khoa in new[] { "message", "channel_post",
                                                                 "edited_message", "my_chat_member" })
                                    {
                                        if (!up.TryGetProperty(khoa, out var m)) continue;
                                        if (!m.TryGetProperty("chat", out var chat)) continue;
                                        if (!chat.TryGetProperty("id", out var id)) continue;
                                        string sid = id.ValueKind == JsonValueKind.Number
                                                   ? id.GetInt64().ToString(CultureInfo.InvariantCulture)
                                                   : id.ToString();
                                        if (sid.StartsWith("-", StringComparison.Ordinal)) nhom = sid;
                                        else rieng = sid;
                                    }
                                }
                            }
                        }

                        string tim = nhom ?? rieng;
                        if (!string.IsNullOrEmpty(tim))
                        {
                            _cf.ChatId = tim;
                            TelegramCauHinh.GhiChatId(tim);
                            _log($"📨 Telegram: đã dò ra chat_id {tim}"
                               + (nhom != null ? " (nhóm)" : " (chat riêng)")
                               + " và ghi vào telegram.cfg. Bắt đầu bắn tin.");
                            return;
                        }
                    }
                }
                catch (OperationCanceledException) { return; }
                catch (Exception) { /* mạng chập chờn thì vòng sau thử lại */ }

                try { await Task.Delay(5000, _dung.Token); } catch { return; }
            }
            _log("⚠ Telegram: 5 phút không thấy ai nhắn cho bot — chưa bắn được tin nào. "
               + "Nhắn cho bot một câu rồi mở lại Manager.");
        }

        private static async Task<string> DocGon(HttpResponseMessage rep)
        {
            try { return Gon(await rep.Content.ReadAsStringAsync()); } catch { return "?"; }
        }

        private static string Gon(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            s = s.Replace("\n", " ").Trim();
            return s.Length > 300 ? s.Substring(0, 300) + "…" : s;
        }

        /// <summary>Thoát ký tự HTML — tên nhân vật có thể chứa &lt; &gt; &amp; làm hỏng cả tin.</summary>
        public static string Esc(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");
        }

        public void Dispose()
        {
            try { _dung.Cancel(); } catch { }
            try { _http.Dispose(); } catch { }
            try { _dung.Dispose(); } catch { }
        }
    }
}
