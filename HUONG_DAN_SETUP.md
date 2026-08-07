# Hướng dẫn cài đặt — từ đầu tới lúc chạy được

README nói dự án này *là gì*. File này chỉ tập trung vào **làm sao chạy được trên máy bạn**.

Đi lần lượt 6 bước. Bước 5 (map & toạ độ) là bước tốn công nhất — cứ làm chậm ở đó.

---

## Bước 1 — Cài phần mềm cần có

| Cần | Lấy ở đâu | Kiểm tra đã cài chưa |
|---|---|---|
| .NET 8 SDK | https://dotnet.microsoft.com/download | mở CMD gõ `dotnet --version` → ra `8.x` |
| Python 3 | https://python.org | gõ `python --version` → ra `3.x` |
| Game Làng Lá | bản bạn đang chơi | mở được game bằng tay |

Không cần cài JDK. Script mượn sẵn JRE bên trong thư mục game để biên dịch phần mod.

---

## Bước 2 — Lấy jar gốc của game

Bản này **không kèm file jar của game** vì đó là mã của nhà phát hành.

Trong thư mục cài game, tìm file `.jar` có chứa lớp `com.beatdz.langlalau.DesktopLauncher`.
Một số bản đóng gói nó bên trong file `.exe` khởi chạy — file `.exe` đó thực chất là một file nén,
đổi đuôi thành `.zip` rồi giải nén ra là thấy.

Chép file jar đó vào **thư mục gốc dự án** (chỗ có `doi_hinh.cfg`) và **đặt tên
`client_modded.jar`**.

> Chỉ phải làm một lần. Từ lần build sau, script tự đọc và ghi đè lên chính file đó.

Nếu quên bước này, chạy build sẽ hiện:

```
THIEU client_modded.jar — chua co jar nguon de va.
```

---

## Bước 3 — Trỏ tới thư mục game

Mở `Injector/inject.py`, sửa dòng gần đầu file:

```python
game_dir = os.environ.get("LANGLA_GAME", r"C:\Games\LangLa")
```

Đổi `C:\Games\LangLa` thành thư mục game của bạn — thư mục có `jre\` và `lib\gdx.jar` bên trong.

Hoặc khỏi sửa file, đặt biến môi trường trước khi build:

```
set LANGLA_GAME=D:\Duong\Dan\Game
```

---

## Bước 4 — Build

Bấm đúp `build_run.bat`. Hoặc gõ tay:

```
dotnet build Manager\Manager.csproj -c Release
python Injector\inject.py
```

Chạy đúng thì thấy:

```
Modded client jar created at: ...\client_modded.jar
Verification: Patched game client is fully ready!
```

**Gặp lỗi thường gặp:**

| Báo lỗi | Nguyên nhân |
|---|---|
| `Could not copy ... Manager.exe ... being used` | Manager đang mở — đóng rồi build lại |
| `Khong thay thu muc game` | `game_dir` ở bước 3 sai |
| `THIEU client_modded.jar` | chưa làm bước 2 |

---

## Bước 5 — Khai tài khoản

Mở `Manager\bin\Release\net8.0-windows\Manager.exe`.

Trên giao diện: gõ **tài khoản đăng nhập** (không phải tên nhân vật), mật khẩu, chọn server ở ô
thả xuống, bấm **➕**. Làm lần lượt cho từng nick.

File `config.json` **tự sinh ra** ngay lần bấm ➕ đầu tiên — không phải tạo tay.

> Muốn sửa bằng tay thì chép `config.mau.json` ở gốc dự án vào `Manager\bin\Release\net8.0-windows\`, đổi tên thành `config.json` rồi điền theo mẫu trong đó.

Nhớ bấm nút chọn thư mục game trên giao diện để đặt `GamePath`.

**`config.json` chứa mật khẩu để nguyên văn.** Đừng commit, đừng gửi cho ai. Nó đã nằm sẵn trong
`.gitignore`.

---

## Bước 6 — Map và toạ độ (phần tốn công nhất)

`quest_anchors.cfg` giữ **ID map và toạ độ NPC**. Số trong file là đo trên **một server cụ thể** —
server bạn chơi rất có thể khác. Chạy mà không sửa bước này thì nhân vật sẽ đứng sai chỗ, mở nhầm
NPC, hoặc đứng yên không làm gì.

### 6.1. Hiểu ba dòng quan trọng nhất

```
village,59,810,493            ← LÀNG NHÀ: map 59, toạ độ (810,493)
npc,dia_cung,59,635,493       ← NPC địa cung ở map 59, toạ độ (635,493)
npc,cam_thuat,59,810,493
```

Dòng `village` quyết định làng nhà. **Cấm thuật, Sơn cáp, Ải gia tộc, Địa cung và Gom đồ đều lấy
map của dòng này** — đổi làng chỉ cần sửa một dòng, rồi khai lại toạ độ NPC của map mới.

### 6.2. Cách dò số cho server của bạn

Tool có sẵn công cụ dò, **thuần đọc bộ nhớ client — không gửi gói nào lên server**, nên không tốn
lượt và không kéo nhân vật đi đâu:

1. Login một nick, tự tay đi tới làng bạn muốn dùng
2. Tích nick đó trên lưới → bấm **🧲 Thu số liệu map**
3. Đi bộ tới từng NPC (Tuần Hoàn, Linh Thú, Địa cung, Cấm thuật, Ải gia tộc, Sơn cáp)
4. Bấm 🧲 lần nữa để dừng
5. Mở file `soi_map_<ngày_giờ>.log` sinh ra ở thư mục gốc — trong đó có ID map, ID NPC và toạ độ
6. Chép số vào `quest_anchors.cfg`

Làm tương tự với **📦 Danh sách vật phẩm** để lấy **mã vật phẩm** điền vào `gom_item_ids` — danh
sách gom khai bằng **mã** chứ không bằng tên, vì tên có dấu và hay thừa khoảng trắng, so tên là
gãy âm thầm.

### 6.3. Lưu ý

Mod đọc `quest_anchors.cfg` **một lần lúc khởi động**. Sửa file xong phải **đóng hẳn client rồi mở
lại** mới ăn. (`doi_hinh.cfg` thì khác — Manager đọc lại mỗi lần bấm nút, sửa xong dùng ngay.)

Mỗi tham số đều có ghi chú giải thích ngay trong `quest_anchors.cfg`.

---

## Bước 7 — Khai đội hình (chỉ cần nếu chạy hoạt động nhóm)

Mở `doi_hinh.cfg`, thay `nick_01`, `nick_02`… bằng **tài khoản đăng nhập** thật.

Chỉ dùng nút 🚀 Khởi chạy, ▶ Auto nhiệm vụ, 🏯 Địa cung, 💎 Tinh thạch thì **không cần khai gì cả** —
mấy nút đó chạy theo ô tích ✔ trên lưới.

Cần khai khi dùng:

| Nút | Cần khai |
|---|---|
| ⚔️ Cấm thuật | khối `[camthuat:…]` — mỗi nhóm 1 trưởng + 3 thành viên |
| 🪢 Sơn cáp | khối `[soncap:…]` — mỗi nhóm 1 trưởng + 5 thành viên |
| 🏰 Ải gia tộc | chỉ `mo_cua` — người vào ải lấy theo nick đang trong game |
| 🎒 Gom đồ | chỉ `nhan_do` — người giao đồ lấy theo nick đang trong game |

Nick trong nhóm cấm thuật / sơn cáp **phải đang trong game** lúc bấm chạy. Thiếu một người là cả
nhóm ngồi chờ một người không bao giờ tới.

---

## Chạy thử lần đầu

Chạy từng bước một, xem log sau mỗi bước, đừng bấm liền tay:

```
1. Tích 1 nick  →  🚀 Khởi chạy
   Log phải hiện:  🔓 <nick> đã vào game: <tên nhân vật> Lv.xx (HP tối đa xxxxx)
   Chỉ khi thấy ĐỦ tên nhân vật + máu + level mới là vào được thật.

2. ▶ Auto nhiệm vụ      → xem nhân vật có đi nhận và đánh quái không
3. 🏯 Địa cung          → xem có nhận chìa và vào hầm không
4. 💎 Tinh thạch
5. 🎒 Gom đồ            → chạy với 2 nick trước, đừng chạy cả chục nick ngay
```

Chạy được từng nút rồi mới tăng số nick.

**Trần số client:** `max_client` trong `doi_hinh.cfg`. Máy chủ thường chặn số client trên một IP —
lấy số nhỏ hơn giữa "máy chịu nổi" và "server cho phép". Nút 🚀 Khởi chạy đếm số client đang mở và
chặn nếu vượt.

---

## Theo dõi qua Telegram (không bắt buộc)

Muốn xem tiến độ trên điện thoại thì mở `telegram.cfg`:

1. Telegram → tìm **@BotFather** → `/newbot` → đặt tên → nó trả về token
   (tên định danh `t.me/...` phải chưa ai lấy và kết thúc bằng `bot`)
2. Dán token vào dòng `token =`
3. Mở Manager
4. Tìm bot vừa tạo, bấm **Start**. Muốn bắn vào nhóm thì thêm bot vào nhóm rồi nhắn một câu ở đó
5. Manager tự dò `chat_id` và tự ghi vào file — không phải tự tìm

Để trống `token` thì tính năng tắt lặng lẽ, mọi thứ khác chạy y như cũ.

---

## Khi có gì đó không chạy

Đọc **ô log trong Manager** trước tiên — mọi bước đều ghi lý do vào đó.

| Hiện tượng | Xem chỗ này |
|---|---|
| Login báo thành công mà lưới vẫn Lv.1 | client kẹt ở màn đăng nhập. Manager tự tắt và login lại `login_thu_lai` lần |
| Vào nhầm server | xem console client có dòng `SERVER: da chon "<tên>" <ip>:<port>` không |
| Nhân vật đứng yên không làm gì | sai toạ độ trong `quest_anchors.cfg` — quay lại bước 6 |
| Nhóm cấm thuật ngồi chờ mãi | thiếu người: có nick trong nhóm chưa vào game |
| Gom đồ bỏ qua hết mọi nick | `gom_item_ids` chưa khai mã món nào có trong túi |

Client mở bằng `java.exe` (bỏ tick "Ẩn console") sẽ hiện cửa sổ console in log của mod — chi tiết
hơn ô log của Manager nhiều, dùng khi cần truy sâu.

---

## Nhắc cuối

Đây là công cụ tự động hoá cho tài khoản của chính bạn. Điều khoản sử dụng của game có thể cấm
việc này — cân nhắc trước khi dùng, rủi ro tài khoản bạn tự chịu.

Hai file **không bao giờ được chia sẻ**:

```
Manager\bin\Release\net8.0-windows\config.json     mật khẩu tài khoản
Manager\bin\Release\net8.0-windows\telegram.cfg    token bot
```
