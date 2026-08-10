# Hướng dẫn cài đặt — từ máy trắng tới lúc chạy được

README nói dự án này *là gì*. File này chỉ tập trung vào **làm sao chạy được trên máy bạn**, kèm
đầy đủ lệnh gõ.

> ### ⚠ Đọc dòng này trước
>
> **Repo chỉ chứa MÃ NGUỒN — tải về sẽ KHÔNG thấy `Manager.exe`.**
> Bạn tự build ra nó ở **Bước 4**. Không thấy file chạy sau khi tải về là bình thường,
> không phải thiếu file.

## Làm nhanh (5 dòng)

```bat
git clone https://github.com/hoang123-123/langlatool.git
cd langlatool
:: chép jar game của bạn vào đây, đặt tên client_modded.jar
set LANGLA_GAME=C:\Duong\Dan\Game
build_run.bat
```

Xong thì mở `Manager\bin\Release\net8.0-windows\Manager.exe`.

Chạy được rồi thì làm tiếp **Bước 6** (map & toạ độ) — không làm thì nhân vật sẽ đứng sai chỗ.

Bên dưới là chi tiết từng bước, đánh số 0 → 8. **Bước 6 tốn công nhất** — cứ làm chậm ở đó.

---

## Bước 0 — Tải mã nguồn về

```bat
git clone https://github.com/hoang123-123/langlatool.git
cd langlatool
```

Không dùng git thì lên trang repo, bấm nút xanh **Code → Download ZIP** rồi giải nén ra một thư
mục bất kỳ.

Tải xong thư mục sẽ có đúng chừng này:

```
Injector\        Manager\        Mod\
build_run.bat    config.mau.json doi_hinh.cfg    quest_anchors.cfg
README.md        HUONG_DAN_SETUP.md              .gitignore
```

**Chưa có `Manager.exe`, chưa có thư mục `bin\`, chưa có `client_modded.jar`.** Ba thứ đó sinh ra
ở Bước 2 và Bước 4.

---

## Bước 1 — Cài phần mềm cần có

### Cách nhanh: winget (có sẵn trên Windows 10/11)

Mở **PowerShell** rồi gõ:

```powershell
winget install --id Microsoft.DotNet.SDK.8 -e
winget install --id Python.Python.3.13 -e
winget install --id Git.Git -e
```

**Đóng cửa sổ terminal rồi mở lại** — cài xong PATH mới có, cửa sổ cũ chưa thấy lệnh mới.

### Cách thủ công

| Cần | Lấy ở đâu |
|---|---|
| .NET 8 SDK | https://dotnet.microsoft.com/download |
| Python 3 | https://python.org — nhớ **tick "Add Python to PATH"** lúc cài |
| Git (không bắt buộc) | https://git-scm.com — không cài thì tải ZIP |
| Game Làng Lá | bản bạn đang chơi, mở được bằng tay |

### Kiểm tra đã cài đủ chưa

```powershell
dotnet --version    # phải ra 8.x trở lên
python --version    # phải ra 3.x
git --version       # nếu có cài
```

Lệnh nào báo `is not recognized` nghĩa là **chưa cài** hoặc **cài rồi mà chưa mở lại terminal**.

> **Không cần cài JDK.** Script mượn JRE 1.8 nằm sẵn trong thư mục game, cộng với `ecj` (trình
> biên dịch Java của Eclipse) làm `javac`. `ecj` và `javassist` tự tải về từ Maven ở lần build đầu.

---

## Bước 2 — Lấy jar gốc của game

Bản này **không kèm file jar của game** vì đó là mã của nhà phát hành.

Trong thư mục cài game, tìm file `.jar` có chứa lớp `com.beatdz.langlalau.DesktopLauncher`.
Một số bản đóng gói nó bên trong file `.exe` khởi chạy — file `.exe` đó thực chất là một file nén,
đổi đuôi thành `.zip` rồi giải nén ra là thấy.

Chép file jar đó vào **thư mục gốc dự án** (chỗ có `doi_hinh.cfg`) và **đặt tên
`client_modded.jar`**:

```bat
copy "C:\Duong\Dan\Game\langla.jar" client_modded.jar
```

> Chỉ phải làm một lần. Từ lần build sau, script tự đọc và ghi đè lên chính file đó.

Nếu quên bước này, chạy build sẽ hiện:

```
THIEU client_modded.jar — chua co jar nguon de va.
```

---

## Bước 3 — Trỏ tới thư mục game

Script cần **thư mục cài game** (không phải chỉ một JRE bất kỳ), vì nó mượn cả hai thứ nằm trong
đó: `jre\bin\java.exe` để biên dịch, và `lib\gdx.jar` làm classpath.

**Cách 1 — biến môi trường (khỏi sửa file):**

```bat
:: chỉ có tác dụng trong cửa sổ hiện tại
set LANGLA_GAME=D:\Duong\Dan\Game
```

```powershell
# PowerShell
$env:LANGLA_GAME = "D:\Duong\Dan\Game"

# muốn nhớ luôn cho các lần sau
[Environment]::SetEnvironmentVariable("LANGLA_GAME", "D:\Duong\Dan\Game", "User")
```

**Cách 2 — sửa thẳng file.** Mở `Injector/inject.py`, đổi dòng gần đầu:

```python
game_dir = os.environ.get("LANGLA_GAME", r"C:\Games\LangLa")
```

Kiểm tra trỏ đúng chưa — hai file này phải tồn tại:

```bat
dir "%LANGLA_GAME%\jre\bin\java.exe"
dir "%LANGLA_GAME%\lib\gdx.jar"
```

---

## Bước 4 — Build

Bấm đúp `build_run.bat`. Hoặc gõ tay:

```bat
dotnet build Manager\Manager.csproj -c Release
python Injector\inject.py
```

Chạy đúng thì thấy:

```
0 Error(s)
Modded client jar created at: ...\client_modded.jar
Verification: Patched game client is fully ready!
```

### Sau khi build có gì, nằm ở đâu

| File | Từ đâu ra | Ghi chú |
|---|---|---|
| `Manager\bin\Release\net8.0-windows\Manager.exe` | `dotnet build` | file để chạy |
| `client_modded.jar` (bị ghi đè) | `inject.py` | jar game đã vá — đây là file client sẽ mở |
| `lib\javassist.jar` | tự tải từ Maven | không phải kiếm ở đâu |
| `tools\ecj.jar` | tự tải từ Maven | |
| `Mod\classes\` | ecj biên dịch | class trung gian |

Ba file cuối bị `.gitignore` bỏ qua, nên `git clone` sẽ không có — build lần đầu tự sinh.

### Lỗi thường gặp khi build

| Báo lỗi | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Could not copy ... Manager.exe ... being used` (`MSB3027`) | Manager đang mở | Đóng Manager rồi build lại |
| `Khong thay thu muc game` | `LANGLA_GAME` / `game_dir` sai | Xem lại Bước 3 |
| `THIEU client_modded.jar` | chưa làm Bước 2 | |
| `'dotnet' is not recognized` | chưa cài .NET 8 SDK | Bước 1 |
| `'python' is not recognized` | chưa cài Python, hoặc quên tick "Add to PATH" | Bước 1 |
| `Failed to compile mod Java files` | jar game không khớp bản đang cài | Lấy lại jar ở Bước 2 từ đúng bản game |

> **Muốn build thử mà không phải đóng Manager** (chỉ để xem code có lỗi biên dịch không):
> ```bat
> dotnet build Manager\Manager.csproj -c Release -p:OutDir=%TEMP%\ktbuild\
> ```
> Nó ghi ra chỗ khác nên không đụng `Manager.exe` đang chạy. Nhưng muốn **dùng** bản mới thì vẫn
> phải đóng Manager rồi build lại cho đúng chỗ.

---

## Bước 5 — Khai tài khoản

Mở `Manager\bin\Release\net8.0-windows\Manager.exe`.

Trên giao diện: gõ **tài khoản đăng nhập** (không phải tên nhân vật), mật khẩu, chọn server ở ô
thả xuống, bấm **➕**. Làm lần lượt cho từng nick.

File `config.json` **tự sinh ra** ngay lần bấm ➕ đầu tiên — không phải tạo tay.

> Muốn sửa bằng tay thì chép `config.mau.json` ở gốc dự án vào
> `Manager\bin\Release\net8.0-windows\`, đổi tên thành `config.json` rồi điền theo mẫu trong đó:
> ```bat
> copy config.mau.json Manager\bin\Release\net8.0-windows\config.json
> ```

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

Mỗi tham số đều có ghi chú giải thích ngay trong `quest_anchors.cfg`.

---

## Bước 7 — Khai đội hình (chỉ cần nếu chạy hoạt động nhóm)

Mở `doi_hinh.cfg`, thay `nick_01`, `nick_02`… bằng **tài khoản đăng nhập** thật.

Chỉ dùng nút 🚀 Khởi chạy, ▶ Auto NV hằng ngày, 🏯 Địa cung, 💎 Đổi tinh thạch thì **không cần khai gì cả** —
mấy nút đó chạy theo ô tích ✔ trên lưới.

Cần khai khi dùng:

| Nút | Cần khai |
|---|---|
| ⚔️ Cấm thuật | khối `[camthuat:…]` — mỗi nhóm 1 trưởng + 3 thành viên |
| 🪢 Sơn cáp | khối `[soncap:…]` — mỗi nhóm 1 trưởng + 5 thành viên |
| 🏰 Ải gia tộc | chỉ `mo_cua` — người vào ải lấy theo nick đang trong game |
| 🎒 Gom đồ về lead | chỉ `nhan_do` — người giao đồ lấy theo nick đang trong game |

Nick trong nhóm cấm thuật / sơn cáp **phải đang trong game** lúc bấm chạy. Thiếu một người là cả
nhóm ngồi chờ một người không bao giờ tới.

---

## Bước 8 — Telegram (không bắt buộc, nhưng nên bật)

Bật lên thì điện thoại nhận được: một **bảng trạng thái** sửa tại chỗ (ai đang làm gì, tới đâu,
giao/nhận món gì), tin rời cho mỗi lượt gom đồ và mỗi lỗi cần người can thiệp, và — quan trọng
nhất — **đường gỡ bùa uế thổ từ xa** (xem mục 8.4).

### 8.1. File `telegram.cfg` nằm ở đâu

**Không có sẵn trong repo.** Manager **tự sinh file mẫu** ở lần chạy đầu tiên, tại:

```
Manager\bin\Release\net8.0-windows\telegram.cfg
```

Nên cứ mở Manager một lần rồi tắt đi, file sẽ có ở đó với `token` để trống.

> Manager tìm file theo thứ tự: thư mục chứa `Manager.exe` trước, không thấy thì đi ngược lên tối
> đa 6 cấp thư mục cha. Nghĩa là đặt `telegram.cfg` ở **gốc dự án** cũng được — tiện khi build lại
> nhiều lần mà không muốn chép qua chép lại.

### 8.2. Lấy token và bật

1. Mở Telegram, tìm **@BotFather** → gõ `/newbot` → đặt tên
   (tên định danh `t.me/...` phải chưa ai lấy và **kết thúc bằng `bot`**)
2. BotFather trả về một chuỗi dạng `1234567890:AAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` — đó là **token**
3. Dán vào dòng `token =` trong `telegram.cfg`
4. Mở Manager
5. Tìm bot vừa tạo trên Telegram, bấm **Start** (hoặc nhắn một câu bất kỳ).
   Muốn bắn vào **nhóm** thì thêm bot vào nhóm rồi nhắn một câu **trong nhóm đó**
6. Manager **tự dò `chat_id` và tự ghi vào file** — không phải tự mở `getUpdates` tìm

Để trống `token` thì tính năng **tắt lặng lẽ**, mọi thứ khác chạy y như cũ.

### 8.3. Các dòng trong `telegram.cfg`

```ini
[bot]
token   =            # BotFather cho. Để trống = tắt hẳn tính năng
chat_id =            # để trống, Manager tự điền sau khi bạn nhắn cho bot
bat     = 1          # 0 = tắt dù đã có token

[bang]
bang_trang_thai = 1  # 1 = bật bảng theo dõi (MỘT tin nhắn, sửa tại chỗ)
bang_giay       = 20 # bao nhiêu giây cập nhật bảng một lần (tối thiểu 5)

[tin]
tin_giao_dich   = 1  # tin rời mỗi lượt gom đồ
tin_loi         = 1  # tin rời khi có lỗi cần người can thiệp
```

Tin bùa uế thổ **không chịu ảnh hưởng của `tin_loi`** — nó luôn đi khi Telegram đang bật. Cố ý
như vậy: bùa không phải lỗi của tool, mà tắt nhầm nó thì nick nằm chết vô thời hạn.

### 8.4. Gỡ bùa uế thổ qua Telegram

Người chơi khác dùng vật phẩm **"Bùa uế thổ"** lên nick bạn: nhân vật chết và **không được tự hồi
sinh hay về thành** cho tới khi có người nhập đúng mã captcha.

> **Bùa KHÔNG tự hết.** Con số "5 phút" hay nghe nói là hạn dùng của lá bùa bên phía kẻ yểm, không
> phải hạn chịu đựng của nạn nhân. Đã dính rồi thì nick nằm đó **tới khi nhập đúng mã, hoặc tới
> khi tắt hẳn client**. Đây là lý do cả đường này tồn tại: nick nằm chết không sinh sự kiện nào,
> trên bảng theo dõi nó im hệt nick đang cày ngon — không có tin báo thì cả buổi không ai biết.

**Cách dùng — chỉ có hai thao tác:**

1. Bot gửi vào nhóm **tấm ảnh captcha** kèm tên nick bị yểm
2. Bạn **trả lời (reply) chính tin ảnh đó** bằng mã trong ảnh (gõ hoa hay thường đều được)

Xong. Tool gõ hộ vào ô trong game rồi bấm xác nhận.

**Vì sao phải reply chứ không nhắn thẳng:** mã được định tuyến bằng `message_id` của tấm ảnh, nên
không cần gõ tên nick, và **hai nick dính bùa cùng lúc cũng không lẫn**. Reply cũng là cách né
privacy mode của bot trong nhóm — khỏi phải vào BotFather tắt.

**Gõ sai thì sao:** game **giữ nguyên mã cũ** sau khi gõ sai, chỉ hiện "Mã captcha không chính
xác". Nên **đọc lại đúng tấm ảnh cũ đó rồi reply tiếp** — không có ảnh mới, và reply bao nhiêu lần
cũng được. Captcha cố tình viết khó đọc (I hoa với l thường, 0 với O), trượt vài lần là chuyện
thường.

**Ý nghĩa các tin bot gửi:**

| Tin | Nghĩa |
|---|---|
| 🧿 *…bị yểm bùa uế thổ* + ảnh | Reply tin này bằng mã trong ảnh |
| 🧿⌨️ *đã gõ mã … vào game* | Đã gửi xuống client trót lọt |
| 🧿❌ *mã sai* | Đọc lại **đúng tấm ảnh cũ**, reply tiếp |
| 🧿⚠️ *đã thử N lần* | Chỉ là lời nhắc — reply tiếp **vẫn ăn**, hoặc vào game gõ cho nhanh |
| 🧿⚠️ *client đang ngắt kết nối* | Client vào lại thì reply tin ảnh đó **thêm lần nữa** |
| 🧿⚠️ *không gửi được … (đường truyền đứt)* | Reply lại, hoặc vào game gõ tay |
| 🧿✅ *đã giải bùa uế thổ* | Xong, nhân vật hồi sinh |
| 🧿 *…* (không kèm ảnh) | Không đọc được ảnh captcha ⇒ **không reply được**, phải vào game gõ |

**Chỉnh sâu hơn** (hiếm khi cần): các khoá `bua_ue_tho_*` trong `quest_anchors.cfg`, mỗi khoá có
ghi chú ngay tại chỗ. Đáng để ý mấy cái này:

| Khoá | Mặc định | Làm gì |
|---|---|---|
| `bua_ue_tho_bao` | `1` | `0` = tắt hẳn việc dò bùa |
| `bua_ue_tho_soi_ms` | `3000` | bao nhiêu mili giây soi một lần |
| `bua_ue_tho_phong_to` | `4` | phóng to ảnh captcha mấy lần (ảnh gốc chỉ 80×50) |
| `bua_ue_tho_lat_anh` | `1` | lật ảnh lại cho đúng chiều |
| `bua_ue_tho_thu_lai_max` | `4` | **ngưỡng NHẮC**, không phải trần dừng — quá số này chỉ đổi câu báo, đường reply vẫn mở |

Các khoá còn lại (`lop_bang`, `truong_ma`, `ma_bang`, `fm_tao`, `fm_gui`…) là toạ độ trong bộ nhớ
game — chỉ động tới khi game cập nhật làm hỏng phép dò.

---

## Chạy thử lần đầu

Chạy từng bước một, xem log sau mỗi bước, đừng bấm liền tay:

```
1. Tích 1 nick  →  🚀 Khởi chạy
   Log phải hiện:  🔓 <nick> đã vào game: <tên nhân vật> Lv.xx (HP tối đa xxxxx)
   Chỉ khi thấy ĐỦ tên nhân vật + máu + level mới là vào được thật.

2. ▶ Auto NV hằng ngày      → xem nhân vật có đi nhận và đánh quái không
3. 🏯 Địa cung          → xem có nhận chìa và vào hầm không
4. 💎 Đổi tinh thạch
5. 🎒 Gom đồ            → chạy với 2 nick trước, đừng chạy cả chục nick ngay
```

Chạy được từng nút rồi mới tăng số nick.

**Trần số client:** `max_client` trong `doi_hinh.cfg`. Máy chủ thường chặn số client trên một IP —
lấy số nhỏ hơn giữa "máy chịu nổi" và "server cho phép". Nút 🚀 Khởi chạy đếm số client đang mở và
chặn nếu vượt.

---

## Sửa code xong thì phải build lại cái gì

Nhầm chỗ này là ngồi thắc mắc "sửa rồi mà sao không đổi gì".

| Sửa cái gì | Phải làm | Có phải tắt client không |
|---|---|---|
| `Manager\*.cs` | đóng Manager → `dotnet build Manager\Manager.csproj -c Release` | không |
| `Mod\src\...\*.java` | `python Injector\inject.py` | **có** — đóng hẳn client rồi mở lại |
| `quest_anchors.cfg` | không phải build | **có** — mod đọc file này một lần lúc khởi động |
| `doi_hinh.cfg` | không phải build | không — Manager đọc lại mỗi lần bấm nút |
| `telegram.cfg` | không phải build | không, nhưng **phải mở lại Manager** |

---

## Khi có gì đó không chạy

Đọc **ô log trong Manager** trước tiên — mọi bước đều ghi lý do vào đó.

| Hiện tượng | Xem chỗ này |
|---|---|
| Login báo thành công mà lưới vẫn Lv.1 | client kẹt ở màn đăng nhập. Manager tự tắt và login lại `login_thu_lai` lần |
| Vào nhầm server | xem console client có dòng `SERVER: da chon "<tên>" <ip>:<port>` không |
| Nhân vật đứng yên không làm gì | sai toạ độ trong `quest_anchors.cfg` — quay lại Bước 6 |
| Nhóm cấm thuật ngồi chờ mãi | thiếu người: có nick trong nhóm chưa vào game |
| Gom đồ bỏ qua hết mọi nick | `gom_item_ids` chưa khai mã món nào có trong túi |
| Telegram im hoàn toàn | `token` trống, hoặc chưa nhắn cho bot lần nào (chưa có `chat_id`) |
| Bot gửi được tin nhưng **reply mã không ăn** | xem bảng ngay dưới |

### Reply mã captcha không ăn

| Dòng log | Nghĩa | Xử lý |
|---|---|---|
| `⚠ Telegram: đọc trả lời lỗi — …` | mạng đang chập chờn | tự thử lại mỗi 2 giây, **không chết luồng** — chờ dòng `nối lại được` |
| `⚠ Telegram getUpdates 409: …` | **hai Manager cùng chạy trên một token** | tắt bớt một cái. Telegram chỉ giao mỗi tin cho MỘT bên, reply rơi sang bên kia rồi mất |
| `⚠ Telegram: vòng đọc trả lời đã dừng` | Manager đang tắt | mở lại Manager |
| `🧿⚠️ … client đã ngắt kết nối` | nick rớt khỏi Manager | client vào lại rồi reply tin ảnh đó lần nữa |
| `🧿⚠️ … GỬI HỎNG … đường truyền đứt` | socket tới client đứt | reply lại, hoặc vào game gõ tay |
| **không có dòng nào cả** | reply không tới được Manager | kiểm tra: có reply đúng **tin ảnh** không (không phải nhắn rời)? bot còn trong nhóm không? |

Client mở bằng `java.exe` (bỏ tick "Ẩn console") sẽ hiện cửa sổ console in log của mod — chi tiết
hơn ô log của Manager nhiều, dùng khi cần truy sâu.

---

## Tra nhanh — mọi lệnh gom một chỗ

```bat
:: ── cài (PowerShell, chạy một lần) ─────────────────────────────
winget install --id Microsoft.DotNet.SDK.8 -e
winget install --id Python.Python.3.13 -e
winget install --id Git.Git -e

:: ── kiểm tra ───────────────────────────────────────────────────
dotnet --version
python --version

:: ── lấy mã nguồn ───────────────────────────────────────────────
git clone https://github.com/hoang123-123/langlatool.git
cd langlatool

:: ── trỏ tới game ───────────────────────────────────────────────
set LANGLA_GAME=D:\Duong\Dan\Game
dir "%LANGLA_GAME%\jre\bin\java.exe"
dir "%LANGLA_GAME%\lib\gdx.jar"

:: ── build cả hai phần ──────────────────────────────────────────
build_run.bat
::   hoặc tách ra:
dotnet build Manager\Manager.csproj -c Release
python Injector\inject.py

:: ── build thử mà không đụng Manager đang chạy ───────────────────
dotnet build Manager\Manager.csproj -c Release -p:OutDir=%TEMP%\ktbuild\

:: ── chạy ───────────────────────────────────────────────────────
Manager\bin\Release\net8.0-windows\Manager.exe
```

---

## Nhắc cuối

Đây là công cụ tự động hoá cho tài khoản của chính bạn. Điều khoản sử dụng của game có thể cấm
việc này — cân nhắc trước khi dùng, rủi ro tài khoản bạn tự chịu.

Hai file **không bao giờ được chia sẻ**:

```
Manager\bin\Release\net8.0-windows\config.json     mật khẩu tài khoản
Manager\bin\Release\net8.0-windows\telegram.cfg    token bot
```

Cả hai đã nằm sẵn trong `.gitignore`. Ai có token bot là **chiếm được bot** — đọc được mọi tin
trong nhóm và gửi tin giả danh.
