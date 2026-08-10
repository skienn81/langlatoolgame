# Auto Làng Lá

> **Cài lần đầu → đọc [HUONG_DAN_SETUP.md](HUONG_DAN_SETUP.md).** File này nói dự án *là gì* và
> hoạt động ra sao; file kia cầm tay chỉ việc từng bước tới lúc chạy được.

Bộ công cụ tự động hoá cho game Làng Lá, chạy nhiều tài khoản cùng lúc. Gồm hai phần:

| Phần | Là gì |
|---|---|
| **Manager** (C#, WinForms) | Bảng điều khiển. Mở client, ra lệnh, nhận báo cáo. |
| **Mod** (Java) | Chạy bên trong client game. Nhận lệnh từ Manager, thao tác trong game, báo kết quả về. |

Hai bên nói chuyện qua TCP `127.0.0.1:9090`, mỗi dòng một gói JSON.

---

## Nguyên tắc thiết kế

Đọc mã sẽ thấy hai luật này lặp đi lặp lại, biết trước thì đỡ ngạc nhiên:

**Bằng chứng, không phải đồng hồ.** Không bước nào chuyển tiếp bằng `sleep(5s)` rồi coi như xong.
Mỗi bước chờ một BÁO CÁO cụ thể từ client. Đợi theo giờ thì lúc mạng lag là cả dây chuyền lệch,
mà lệch im lặng — hỏng kiểu khó truy nhất.

**Một cửa ra duy nhất.** Mọi đường kết thúc của một hoạt động đều đi qua đúng một hàm. Rải việc
bàn giao (đi treo, đóng cửa sổ) ở từng nhánh thì luôn sót một đường, mà sót đường nào là nhân vật
đứng không cả buổi.

---

## Cần có trước

| | |
|---|---|
| .NET 8 SDK | https://dotnet.microsoft.com/download |
| Python 3 | để chạy `Injector/inject.py` |
| Bản cài game Làng Lá | mượn JRE 1.8 và `lib/gdx.jar` của nó |

Không cần JDK — script mượn JRE của game cộng với `ecj` (trình biên dịch Java của Eclipse) làm
`javac`. `ecj` và `javassist` tự tải về lần chạy đầu.

---

## Cài đặt

> Repo chỉ chứa **mã nguồn** — tải về chưa có `Manager.exe`, bước 3 build ra nó.

### 0. Tải mã nguồn

```
git clone https://github.com/hoang123-123/langlatool.git
```

### 1. Lấy jar gốc của game

Bản này **không kèm jar game** — đó là mã của nhà phát hành, không phải mã của dự án.

Tìm trong thư mục cài game file jar có chứa class `com.beatdz.langlalau.DesktopLauncher`.
Một số bản đóng gói nó bên trong file `.exe` khởi chạy, giải nén ra để lấy.

Chép file jar đó vào **gốc dự án** và đặt tên **`client_modded.jar`**.

> Từ lần build thứ hai trở đi không phải làm gì: script vừa đọc vừa ghi đè lên chính file đó.

### 2. Trỏ tới thư mục game

Sửa `game_dir` ở đầu `Injector/inject.py`, hoặc đặt biến môi trường:

```
set LANGLA_GAME=C:\Games\LangLa
```

### 3. Build

```
build_run.bat
```

Hoặc chạy tay:

```
dotnet build Manager\Manager.csproj -c Release
python Injector\inject.py
```

### 4. Khai tài khoản

Mở Manager, gõ tài khoản rồi bấm ➕ — `config.json` tự sinh ra.
Muốn sửa tay thì chép `config.mau.json` (ở gốc dự án) vào `Manager\bin\Release\net8.0-windows\`,
đổi tên thành `config.json`.

> `config.json` chứa mật khẩu. Đừng commit, đừng gửi cho ai.

### 5. Khai đội hình

Sửa `doi_hinh.cfg` — thay `nick_01`, `nick_02`… bằng username thật. File có ghi chú đầy đủ
từng khối. Sửa xong bấm nút là ăn ngay, không cần khởi động lại Manager.

### 6. Kiểm toạ độ

`quest_anchors.cfg` giữ ID map và toạ độ NPC. Số trong file là của một server cụ thể — server
bạn chơi có thể khác. Đối chiếu lại trước khi chạy thật.

---

## Dùng

Build xong (bước 4 trong hướng dẫn) sẽ có `Manager\bin\Release\net8.0-windows\Manager.exe`.
Mở nó, tích các nick muốn chạy rồi bấm nút.

### Các nút

| Nút | Làm gì |
|---|---|
| 🚀 Khởi chạy | Mở client và login các nick đang tích. Kẹt ở màn đăng nhập thì tự tắt client login lại. |
| ▶ Auto NV hằng ngày | Chạy nhiệm vụ ngày: nhận, đi tới map, đánh quái, trả nhiệm vụ. |
| 🏯 Địa cung | Nhận chìa rồi vào hầm. |
| 💎 Đổi tinh thạch | Đổi trang bị lấy tinh thạch ở NPC. |
| 🎒 Gom đồ về lead | Mọi nick đang trong game giao đồ cho một nick nhận. Tuần tự từng nick vì game khoá giao dịch 30 giây. |
| ⚔️ Cấm thuật · 🪢 Sơn cáp | Hoạt động nhóm. Trưởng nhóm đi tìm khu trống, lập nhóm, gọi thành viên. Các nhóm xuất phát ở khu lệch nhau để khỏi giẫm chân. |
| 🏰 Ải gia tộc | Một nick mở cửa ải, mọi nick đang trong game vào theo, dồn hoả lực vào cùng một mục tiêu. |
| 🏠 Về làng · 💀 Tắt game | |

Mỗi nút chạy một việc, **bấm tay từng bước**. Trình tự thường dùng trong ngày:

```
🚀 Khởi chạy → ▶ Auto NV hằng ngày → 🏯 Địa cung → 💎 Đổi tinh thạch → 🎒 Gom đồ
rồi tuỳ lúc: ⚔️ Cấm thuật · 🪢 Sơn cáp · 🏰 Ải gia tộc
```

Không có bước "đi treo" riêng: xong nhiệm vụ ngày, gom xong, đổi tinh thạch xong thì mod tự
chuyển sang treo (`gom_after_afk` / `tinh_thach_after_afk` trong `quest_anchors.cfg`).

Nhiều nick hơn `max_client` thì tích một nhóm chạy trước, xong tắt client nhóm đó rồi tích
nhóm sau — nút 🚀 Khởi chạy đếm số client đang mở và chặn nếu vượt trần.

### Theo dõi qua Telegram (tuỳ chọn)

Điền token bot vào `telegram.cfg` là Manager bắn tin về điện thoại: một bảng trạng thái sửa tại
chỗ (ai đang làm gì, tới đâu, đưa/nhận món gì, thuộc team nào), cộng tin rời cho mỗi lượt giao
đồ và lỗi cần người can thiệp.

File `telegram.cfg` **không có sẵn trong repo** — Manager tự sinh mẫu (token rỗng) ở
`Manager\bin\Release\net8.0-windows\` ngay lần chạy đầu. `chat_id` cũng không phải tự tìm: nhắn
cho bot một câu là Manager tự dò rồi ghi vào file.

Để trống token thì tính năng tắt lặng lẽ, mọi thứ khác chạy y như cũ.

### Gỡ bùa uế thổ từ xa

Đường **hai chiều** duy nhất của tool — mọi thứ còn lại chỉ bắn tin đi.

Người chơi khác yểm "bùa uế thổ" lên nick bạn thì nhân vật chết và không tự hồi sinh được cho tới
khi có người nhập đúng mã captcha — **bùa không tự hết**, chỉ nhập đúng mã hoặc tắt hẳn client mới
thoát. Mà nick nằm chết thì không sinh sự kiện nào: trên bảng theo dõi nó im hệt nick đang cày
ngon, không có tin báo thì cả buổi không ai biết.

Mod chụp bảng captcha rồi đẩy ảnh lên Telegram; bạn **reply chính tin ảnh đó** bằng mã trong ảnh;
Manager gõ hộ vào ô trong game. Định tuyến bằng `message_id` của tấm ảnh nên không phải gõ tên
nick, và hai nick dính bùa cùng lúc cũng không lẫn.

Tool **không giải ảnh** — nó làm bàn phím nối dài cho người thật, không làm cái đầu.

Chi tiết cách dùng và ý nghĩa từng tin: [HUONG_DAN_SETUP.md](HUONG_DAN_SETUP.md) mục 8.4.

---

## Cấu trúc

```
Manager/            Bảng điều khiển C#
  Form1.cs            giao diện, điều phối hoạt động
  Form1.TheoDoi.cs    theo dõi từng nick, dựng bảng trạng thái
  Telegram.cs         bot Telegram
Mod/src/com/mybot/  Mod Java chạy trong client
  Auto.java           vòng tick, login, kết nối Manager
  TaskManager.java    toàn bộ máy trạng thái của các hoạt động
Injector/           Vá bytecode: chèn Auto.tick() vào vòng render của game
HUONG_DAN_SETUP.md  Cài đặt từng bước
config.mau.json     Mẫu khai tài khoản — chép vào bin\ rồi đổi tên thành config.json
doi_hinh.cfg        Khai team, nhóm cấm thuật / sơn cáp, nick mở ải, nick nhận đồ
quest_anchors.cfg   ID map, toạ độ NPC, mã vật phẩm
```

Mod đọc `quest_anchors.cfg` lúc khởi động, nên sửa file đó xong phải **đóng hẳn client rồi mở
lại** mới ăn. `doi_hinh.cfg` thì Manager đọc lại mỗi lần bấm nút, sửa xong dùng ngay.

Bảng đầy đủ "sửa gì thì build lại gì" nằm ở [HUONG_DAN_SETUP.md](HUONG_DAN_SETUP.md).

---

## Lưu ý

Đây là công cụ tự động hoá cho tài khoản của chính bạn. Điều khoản sử dụng của game có thể cấm
việc này — cân nhắc trước khi dùng, rủi ro tài khoản bạn tự chịu.

Không kèm mã của game (`decompiled/`, jar client). Muốn tự truy mã game thì tự giải ngược từ bản
cài của mình.

Các file **không bao giờ được chia sẻ**:

```
Manager/bin/Release/net8.0-windows/config.json     mật khẩu tài khoản
Manager/bin/Release/net8.0-windows/telegram.cfg    token bot
```

Cả hai đã nằm trong `.gitignore`.
