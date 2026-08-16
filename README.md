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

| Phần mềm | Chi tiết & Cách lấy |
|---|---|
| **.NET 8 SDK** | `build_run.bat` **tự động kiểm tra và tải/cài đặt** nếu máy chưa có. Hoặc tải tay tại: https://dotnet.microsoft.com/download |
| **Python 3** | https://python.org (nhớ tick *"Add Python to PATH"* khi cài) hoặc `winget install Python.Python.3.13 -e` |
| **Bản cài game Làng Lá** | Mượn JRE 1.8 và `lib/gdx.jar` có sẵn trong thư mục game |

> **Không cần JDK riêng** — script mượn JRE của game kết hợp `ecj` (Eclipse Compiler for Java) để biên dịch Mod. `ecj` và `javassist` sẽ tự động tải về trong lần build đầu tiên.

---

## Cài đặt siêu nhanh (3 bước)

> Repo chứa **mã nguồn** — `build_run.bat` sẽ tự động biên dịch ra `Manager.exe` và patch Mod vào client game.

### 1. Chuẩn bị mã nguồn & `client_modded.jar`
- Tải mã nguồn về máy (hoặc dùng `git clone https://github.com/skienn81/langlatoolgame.git`).
- Chép file `client_modded.jar` (xin từ người chia sẻ tool) vào **thư mục gốc dự án** (chỗ chứa file `build_run.bat` và `doi_hinh.cfg`).

### 2. Trỏ thư mục game & Chạy Build
- Thiết lập đường dẫn thư mục cài game bằng biến môi trường (hoặc sửa dòng `game_dir` trong `Injector/inject.py`):
  ```bat
  set LANGLA_GAME=C:\Games\LangLa
  ```
- **Chạy `build_run.bat`** (khuyến nghị chuột phải chọn *"Run as administrator"* để tool tự động cài đặt .NET 8 SDK nếu máy bạn chưa có).

### 3. Khai báo tài khoản & Chạy ngay
- Mở `Manager\bin\Release\net8.0-windows\Manager.exe`.
- Nhập tài khoản, mật khẩu, chọn Server rồi bấm ➕ (file `config.json` sẽ tự động khởi tạo).
- Tích chọn các nick cần chạy và bấm **🚀 Khởi chạy**.

---

## Vận hành game tinh gọn (Daily Workflow)

Không cần phải thao tác thủ công từng bước phức tạp trong game, bạn có thể vận hành theo các cách tối ưu sau:

### Cách 1: Vận hành 1-Click trên Manager
Khi mở Manager, các chu trình đã được lập trình thông minh tự chuyển tiếp:
```
🚀 Khởi chạy → ▶ Auto NV hằng ngày → 🏯 Địa cung → 💎 Đổi tinh thạch → 🎒 Gom đồ → Tự động treo AFK
```
*(Mỗi hoạt động sau khi hoàn tất sẽ tự động đưa nhân vật vào trạng thái AFK an toàn theo cấu hình `quest_anchors.cfg`).*

### Cách 2: Tự động hóa hoàn toàn với Scheduler & Telegram
- **Hẹn giờ (Scheduler)**: Cài đặt khung giờ trong ngày để Manager tự đánh thức client, chạy nhiệm vụ, gom đồ và đổi quà mà bạn không cần chạm vào máy tính.
- **Điều khiển từ xa qua Telegram**: Nhắn tin điều khiển mọi hoạt động (`/nv`, `/dc`, `/gom`, `/ct`, `/agt`, `/status`) và nhận ảnh captcha giải bùa uế thổ tức thì từ điện thoại.

---

## Bảng tính năng điều khiển

| Nút / Tính năng | Chức năng tự động |
|---|---|
| 🚀 **Khởi chạy** | Tự động mở client và đăng nhập tài khoản. Nếu kẹt mạng/màn hình login sẽ tự khởi động lại client. |
| ▶ **Auto NV hằng ngày** | Tự nhận nhiệm vụ, tìm đường tới map mục tiêu, đánh quái, tự ăn thức ăn/hồi phục và trả nhiệm vụ. |
| 🏯 **Địa cung** | Tự động nhận chìa khóa và vào tầng địa cung tương ứng. |
| 💎 **Đổi tinh thạch** | Tự di chuyển đến NPC và đổi toàn bộ trang bị rác lấy tinh thạch. |
| 🎒 **Gom đồ về lead** | Các nick phụ tự động xếp hàng giao dịch vật phẩm/trang bị cho nick chính (lead), tự giãn cách 30s chống khóa. |
| ⚔️ **Cấm thuật · 🪢 Sơn cáp** | Trưởng nhóm tự tìm khu trống, lập nhóm, mời thành viên theo cấu hình `doi_hinh.cfg` và dẫn đội vượt ải. |
| 🏰 **Ải gia tộc** | Một nick mở cửa ải, toàn bộ dàn nick trong game tự động vào ải và dồn sát thương vào boss/mục tiêu. |
| ❓ **Auto Quiz NPC** | Tự động đọc và trả lời chính xác câu hỏi trắc nghiệm/kiểm tra khi tương tác với NPC. |
| ⏰ **Scheduler / Hẹn giờ** | Đặt lịch trình tự động thực thi các tác vụ theo giờ cố định hàng ngày. |
| 🏠 **Về làng · 💀 Tắt game** | Đưa toàn bộ đội hình về làng an toàn hoặc đóng nhanh toàn bộ client. |

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

