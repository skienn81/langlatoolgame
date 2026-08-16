# Auto Làng Lá

> **Cài lần đầu → đọc [HUONG_DAN_SETUP.md](HUONG_DAN_SETUP.md).** File này nói dự án *là gì* và
> hoạt động ra sao; file kia cầm tay chỉ việc từng bước tới lúc chạy được.

Bộ công cụ tự động hoá cho game Làng Lá, chạy nhiều tài khoản cùng lúc. Gồm hai phần:

| Phần | Là gì |
|---|---|
| **Manager** (C#, WinForms) | Bảng điều khiển & Server điều phối. Mở client, ra lệnh, hẹn giờ, nhận báo cáo, điều khiển từ xa qua Telegram. |
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
git clone https://github.com/skienn81/langlatoolgame.git
```

### 1. Lấy `client_modded.jar`

Repo **không kèm file này** — đó là mã của nhà phát hành, không phải mã của dự án. Và **không tự
bóc ra từ thư mục game được**: bản game hiện tại giữ mã trong `META-INF/client.payload` đã mã hoá,
nạp qua `com.beatdz.protect.ProtectedLauncher`, nên giải nén file `.exe` chỉ ra lớp vỏ bảo vệ.

**Xin file từ người đưa bạn bộ tool**, chép vào **gốc dự án** (chỗ có `doi_hinh.cfg`).
Bản game hai bên phải khớp — xem [HUONG_DAN_SETUP.md](HUONG_DAN_SETUP.md) Bước 2.

Đây là giới hạn thật: bộ tool **không tự đứng một mình được**, luôn cần một file truyền tay.

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

### Các nút & Tính năng chính

| Nút / Tính năng | Làm gì |
|---|---|
| 🚀 Khởi chạy | Mở client và login các nick đang tích. Kẹt ở màn đăng nhập thì tự tắt client login lại. |
| ▶ Auto NV hằng ngày | Chạy nhiệm vụ ngày: nhận, đi tới map, đánh quái, trả nhiệm vụ. |
| 🏯 Địa cung | Nhận chìa rồi vào hầm. |
| 💎 Đổi tinh thạch | Đổi trang bị lấy tinh thạch ở NPC. |
| 🎒 Gom đồ về lead | Mọi nick đang trong game giao đồ cho một nick nhận. Tuần tự từng nick vì game khoá giao dịch 30 giây. |
| ⚔️ Cấm thuật · 🪢 Sơn cáp | Hoạt động nhóm. Trưởng nhóm đi tìm khu trống, lập nhóm, gọi thành viên. Các nhóm xuất phát ở khu lệch nhau để khỏi giẫm chân. |
| 🏰 Ải gia tộc | Một nick mở cửa ải, mọi nick đang trong game vào theo, dồn hoả lực vào cùng một mục tiêu. |
| ❓ Auto Quiz NPC | Tự động trả lời câu hỏi trắc nghiệm / câu hỏi kiểm tra tại NPC. |
| ⏰ Scheduler / Hẹn giờ | Lập lịch tự động chạy các hoạt động (NV ngày, cấm thuật, gom đồ...) theo khung giờ cố định mỗi ngày. |
| 🏠 Về làng · 💀 Tắt game | Đưa nhân vật về làng an toàn hoặc đóng nhanh client game. |

Mỗi nút chạy một việc, **bấm tay từng bước** hoặc **lập lịch / điều khiển từ xa qua Telegram**. Trình tự thường dùng trong ngày:

```
🚀 Khởi chạy → ▶ Auto NV hằng ngày → 🏯 Địa cung → 💎 Đổi tinh thạch → 🎒 Gom đồ
rồi tuỳ lúc: ⚔️ Cấm thuật · 🪢 Sơn cáp · 🏰 Ải gia tộc · ❓ Auto Quiz
```

Không có bước "đi treo" riêng: xong nhiệm vụ ngày, gom xong, đổi tinh thạch xong thì mod tự
chuyển sang treo (`gom_after_afk` / `tinh_thach_after_afk` trong `quest_anchors.cfg`).

Nhiều nick hơn `max_client` thì tích một nhóm chạy trước, xong tắt client nhóm đó rồi tích
nhóm sau — nút 🚀 Khởi chạy đếm số client đang mở và chặn nếu vượt trần.

---

## Điều khiển & Theo dõi qua Telegram

Điền token bot vào `telegram.cfg` để bật tính năng bot hai chiều mạnh mẽ:

### 1. Bảng theo dõi trạng thái tự động
- Một tin nhắn duy nhất được chỉnh sửa tại chỗ (live update), không gây rác nhóm chat.
- Hiển thị danh sách nick, cấp độ, máu, vị trí, công việc đang làm, trạng thái kết nối.
- Báo riêng các sự kiện quan trọng: gom đồ thành công, lỗi hệ thống, nhặt vật phẩm hiếm.

### 2. Gỡ bùa uế thổ từ xa (Bàn phím nối dài)
- Khi bị người chơi khác yểm bùa uế thổ, client tự chụp ảnh mã captcha gửi lên Telegram.
- Bạn chỉ cần **reply chính tin nhắn ảnh đó** bằng chuỗi ký tự captcha.
- Manager sẽ nhận lệnh và tự động gõ mã vào game để giải bùa, hồi sinh nhân vật.

### 3. Điều khiển toàn diện bằng lệnh Telegram
Bạn có thể nhắn lệnh trực tiếp cho Bot hoặc nhắn trong Group:

| Lệnh | Ý nghĩa & Cú pháp |
|---|---|
| `/status` (hoặc `/st`) | Xem bảng trạng thái tổng quan các tài khoản |
| `/nv [nick]` | Chạy Auto NV ngày (bỏ trống [nick] để chạy tất cả nick đang mở) |
| `/agt` · `/ai` | Chạy Ải Gia Tộc (`/agt stop` để dừng) |
| `/ct` · `/camthuat` | Chạy hoạt động Cấm Thuật (`/ct stop` để dừng) |
| `/sc` · `/soncap` | Chạy hoạt động Sơn Cáp (`/sc stop` để dừng) |
| `/dc` · `/diacung` | Chạy hoạt động Địa Cung |
| `/gom` · `/gomdo` | Chạy gom đồ về nick Lead (`/gom stop` để dừng) |
| `/tt` · `/tinhthach` | Đổi tinh thạch tại NPC |
| `/quiz` | Bật auto trả lời câu hỏi trắc nghiệm NPC |
| `/vl` · `/velang` | Đưa các nick về làng |
| `/wake [nick]` | Mở và đăng nhập client game |
| `/kill [nick]` | Tắt client game |
| `/stop` | Dừng hoạt động hiện tại |
| `/hengio <hh:mm> <lệnh>` | Hẹn giờ chạy lệnh (vd: `/hengio 06:00 nv`) |
| `/timer <phút> <lệnh>` | Đặt đồng hồ đếm ngược chạy lệnh (vd: `/timer 30 gom`) |
| `/dshengio` | Xem danh sách các lịch hẹn giờ đang chạy |
| `/huyhengio <id>` | Huỷ lịch hẹn theo ID |
| `/help` | Xem bảng trợ giúp các câu lệnh |

---

## Cấu trúc thư mục

```
Manager/                      Bảng điều khiển C# (WinForms)
  Form1.cs                      Giao diện, quản lý luồng điều phối chính
  Form1.TheoDoi.cs              Theo dõi trạng thái từng nick, dựng bảng live-status
  Form1.TelegramCommands.cs     Xử lý bộ câu lệnh điều khiển từ xa qua Telegram
  Telegram.cs                   Giao tiếp API Telegram (Gửi tin, nhận webhook/polling, ảnh captcha)
  QuizManager.cs                Bộ xử lý & ngân hàng dữ liệu câu hỏi trắc nghiệm NPC
  Scheduler.cs                  Hệ thống hẹn giờ & lập lịch tác vụ tự động
Mod/src/com/mybot/            Mã nguồn Mod Java chạy bên trong client game
  Auto.java                     Vòng lặp tick, đăng nhập, giữ kết nối Socket với Manager
  TaskManager.java              Toàn bộ máy trạng thái (State Machine) các hoạt động trong game
Injector/                     Script vá bytecode Java (chèn hook Auto.tick vào game engine)
  inject.py
HUONG_DAN_SETUP.md            Hướng dẫn cài đặt chi tiết từng bước
SYSTEM_MAP.md                 Bản đồ kiến trúc và luồng dữ liệu hệ thống
PHAN_TICH_CONG_CU.md          Tài liệu phân tích kỹ thuật sâu
config.mau.json               Mẫu cấu hình tài khoản
doi_hinh.cfg                  Cấu hình đội hình, phân nhóm cấm thuật/sơn cáp/gom đồ
quest_anchors.cfg             Cấu hình ID map, toạ độ NPC, mã vật phẩm
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

