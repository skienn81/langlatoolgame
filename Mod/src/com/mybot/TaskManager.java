package com.mybot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.*;
import java.util.HashMap;

/**
 * TaskManager - Quản lý tự động nhiệm vụ Tuần hoàn & Linh thú
 * 
 * Sử dụng Reflection để truy cập các class game obfuscated (a.z, a.i, a.dq, v.v.)
 * tránh lỗi ambiguous method do decompiler.
 * 
 * State Machine:
 * IDLE → MOVE_TO_NPC → INTERACT_NPC → WAIT_TASK_DATA → MOVE_TO_MAP → DO_TASK → MOVE_TO_TURN_IN → TURN_IN → COOLDOWN → IDLE
 */
public class TaskManager {

    // ═══════════════════════════════════════════════════════════════
    // ENUMS
    // ═══════════════════════════════════════════════════════════════

    public enum TaskState {
        IDLE,
        MOVE_TO_NPC,
        INTERACT_NPC,
        WAIT_TASK_DATA,
        MOVE_TO_MAP,
        DO_TASK,
        MOVE_TO_TURN_IN,
        TURN_IN,
        COOLDOWN,
        AFK_FARM
    }

    public enum TaskType {
        TUAN_HOAN,
        LINH_THU
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════

    private static final int NPC_TUAN_HOAN = 102;
    private static final int NPC_LINH_THU = 98;  // Rasa (a.n.a().a[98] từ eg_0.java)
    private static final int NPC_INTERACT_RANGE = 80;
    private static final long COOLDOWN_MS = 1500;
    private static final long WAIT_TASK_DATA_TIMEOUT = 5000;
    private static final long MOVE_CHECK_INTERVAL = 1000;
    private static final int DEFAULT_X = 500;
    private static final int DEFAULT_Y = 500;

    // ═══════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════

    private TaskState state = TaskState.IDLE;
    private TaskType currentTaskType = TaskType.TUAN_HOAN;
    private long lastActionTime = 0;
    private long stateEnteredTime = 0;
    private long lastMoveCheckTime = 0;
    private boolean enabled = false;
    private long enabledTime = 0;
    private boolean tuanHoanEnabled = true;
    private boolean linhThuEnabled = true;
    private int menuIndexTuanHoan = 0;
    private int menuIndexLinhThu = 5;
    private String lastLogMessage = "";
    private int currentNpcRealId = -1;
    private long lastInteractDebugTime = 0;
    private boolean autoCombatRequested = false;
    private int afkMapId = 0;  // Map AFK khi hết NV (0 = không AFK)
    private int afkZone = 1;   // Khu AFK (default 1)
    private boolean afkZoneChanged = false; // Đã đổi khu chưa
    private int interactStep = 0;
    private long lastInteractStepTime = 0;
    private byte savedPriorityByte = 0;   // Lưu giá trị gốc e[40] trước khi tắt
    private boolean priorityDisabled = false; // Đã tắt ưu tiên hay chưa
    private long navStartTime = 0;           // Timeout tracker cho z.ap navigation
    private short bossAnchorX = 0;           // Tọa độ anchor boss quest (sau nudge)
    private short bossAnchorY = 0;
    private long lastBossAnchorCheck = 0;    // Thời điểm check anchor gần nhất
    private long lastPosLogTime = 0;         // Log tọa độ nhân vật

    // Farm anchor: lưu vị trí farm ban đầu, quay về khi bị kéo đi xa
    private short farmAnchorX = 0;
    private short farmAnchorY = 0;
    private boolean farmAnchorSet = false;
    private boolean farmHasConfigAnchor = false; // true = map có config → bật drift check
    private int farmMobId = 0;           // Mob đang farm (ci)
    private long lastReturnTime = 0;     // Tránh spam return
    private static final int FARM_DRIFT_THRESHOLD = 350; // Khoảng cách pixel tối đa cho phép trôi

    // ═══════════════════════════════════════════════════════════════
    // CONFIG TẬP TRUNG (đọc từ file quest_anchors.cfg)
    // ═══════════════════════════════════════════════════════════════
    // Anchor: key "type_mapId_mobId" → short[]{x, y}
    private static HashMap<String, short[]> anchorConfig = new HashMap<>();
    // Village: key "village" → int[]{mapId, x, y}
    private static int[] villageConfig = null;
    // NPC: key "npc_tuan_hoan_68" → int[]{mapId, x, y}  (per-map NPC coordinates)
    private static HashMap<String, int[]> npcConfig = new HashMap<>();
    // NPC default map: key "npc_tuan_hoan" → first mapId loaded (fallback)
    private static HashMap<String, Integer> npcDefaultMap = new HashMap<>();
    // Tham số rời dạng "set,khoa,giatri" trong quest_anchors.cfg
    private static HashMap<String, String> settings = new HashMap<>();
    private static boolean anchorConfigLoaded = false;

    /** Đọc tham số kiểu chuỗi từ config; trả defaultValue nếu thiếu. */
    private static String getSetting(String key, String defaultValue) {
        loadAnchorConfig();
        String v = settings.get(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    /** Đọc tham số kiểu số từ config; trả defaultValue nếu thiếu hoặc sai định dạng. */
    private static int getSettingInt(String key, int defaultValue) {
        loadAnchorConfig();
        String v = settings.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static void loadAnchorConfig() {
        if (anchorConfigLoaded) return;
        anchorConfigLoaded = true;
        
        String configPath = System.getProperty("quest.anchors.path", "quest_anchors.cfg");
        File f = new File(configPath);
        if (!f.exists()) {
            f = new File(System.getProperty("user.dir"), "quest_anchors.cfg");
        }
        if (!f.exists()) {
            System.out.println("[TaskManager] quest_anchors.cfg not found, using defaults");
            return;
        }
        
        try {
            // Đọc UTF-8 TƯỜNG MINH. FileReader dùng charset mặc định của JVM nên keyword
            // tiếng Việt ("chìa khóa", "sơ cấp") sẽ hỏng âm thầm — lỗi rất khó truy.
            BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            int anchorCount = 0, npcCount = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                String type = parts[0].trim();

                if (type.equals("village") && parts.length >= 4) {
                    // village,mapId,x,y
                    int mapId = Integer.parseInt(parts[1].trim());
                    int x = Integer.parseInt(parts[2].trim());
                    int y = Integer.parseInt(parts[3].trim());
                    villageConfig = new int[]{mapId, x, y};
                    System.out.println("[Config] Village: map=" + mapId + " (" + x + "," + y + ")");

                } else if (type.equals("npc") && parts.length >= 5) {
                    // npc,npcType,mapId,x,y
                    String npcType = parts[1].trim(); // tuan_hoan | linh_thu
                    int mapId = Integer.parseInt(parts[2].trim());
                    int x = Integer.parseInt(parts[3].trim());
                    int y = Integer.parseInt(parts[4].trim());
                    // Store per-map: "npc_tuan_hoan_68" → {68, x, y}
                    npcConfig.put("npc_" + npcType + "_" + mapId, new int[]{mapId, x, y});
                    // First entry becomes default fallback
                    if (!npcDefaultMap.containsKey("npc_" + npcType)) {
                        npcDefaultMap.put("npc_" + npcType, mapId);
                    }
                    npcCount++;
                    System.out.println("[Config] NPC " + npcType + ": map=" + mapId + " (" + x + "," + y + ")");

                } else if (type.equals("set") && parts.length >= 3) {
                    // set,khoa,giatri — tham số rời, tránh nhúng giá trị cứng trong code.
                    // Ghép lại phần sau dấu phẩy thứ hai để giá trị có thể chứa dấu phẩy.
                    String key = parts[1].trim();
                    StringBuilder valSb = new StringBuilder();
                    for (int pi = 2; pi < parts.length; pi++) {
                        if (pi > 2) valSb.append(",");
                        valSb.append(parts[pi]);
                    }
                    String val = valSb.toString().trim();
                    settings.put(key, val);
                    System.out.println("[Config] set " + key + " = " + val);

                } else if ((type.equals("boss") || type.equals("farm")) && parts.length >= 5) {
                    // boss/farm,mapId,mobId,x,y
                    int mapId = Integer.parseInt(parts[1].trim());
                    int mobId = Integer.parseInt(parts[2].trim());
                    short x = Short.parseShort(parts[3].trim());
                    short y = Short.parseShort(parts[4].trim());
                    String key = type + "_" + mapId + "_" + mobId;
                    anchorConfig.put(key, new short[]{x, y});
                    anchorCount++;
                }
            }
            br.close();
            System.out.println("[TaskManager] Config loaded: " + anchorCount + " anchor(s), " 
                    + npcCount + " npc(s), village=" + (villageConfig != null) 
                    + " from " + f.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[TaskManager] Error loading config: " + e.getMessage());
        }
    }

    /** Tìm anchor từ config. Trả về null nếu không có. */
    private static short[] findAnchor(String type, int mapId, int mobId) {
        loadAnchorConfig();
        String exactKey = type + "_" + mapId + "_" + mobId;
        short[] result = anchorConfig.get(exactKey);
        if (result != null) {
            System.out.println("[TaskManager] findAnchor HIT: " + exactKey + " → (" + result[0] + "," + result[1] + ")");
            return result;
        }
        // Thử wildcard mob=0 (boss không cần mobId)
        String wildcardKey = type + "_" + mapId + "_0";
        result = anchorConfig.get(wildcardKey);
        if (result != null) {
            System.out.println("[TaskManager] findAnchor HIT (wildcard): " + wildcardKey + " → (" + result[0] + "," + result[1] + ")");
            return result;
        }
        System.out.println("[TaskManager] findAnchor MISS: " + exactKey + " / " + wildcardKey + " (total entries=" + anchorConfig.size() + ")");
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // CACHED REFLECTION REFERENCES
    // ═══════════════════════════════════════════════════════════════

    private boolean reflectionReady = false;

    /** Đảm bảo reflection đã init (gọi từ bên ngoài khi cần) */
    public boolean ensureReflection() {
        if (!reflectionReady) initReflection();
        return reflectionReady;
    }

    // a.z class
    private Class<?> zClass;
    private Method zGetInstance;     // z.a() → static z
    private Field zFieldA;           // z.a → dq (tuần hoàn task)
    private Field zFieldB;           // z.b → dq (linh thú task)
    private Field zFieldU;           // z.u → short (current map ID)
    private Field zFieldF;           // z.F → Vector (NPC list)
    private Field zFieldV;           // z.v → short (KHU hiện tại). Packet -103 đọc zoneID rồi mới tới mapID
    private Field zFieldAh;          // z.ah → boolean: ô "tự cho vào nhóm" (tự duyệt lời xin gia nhập)
    private Field zFieldNavTarget;   // z.b → bi_0 (navigation target for cross-map auto-walk)
    // z.a kiểu a.x = MỤC TIÊU ĐANG ĐÁNH. z.a(boolean) = hàm chọn mục tiêu (nút "chuyển mục tiêu"
    // trong game). Đọc từ bytecode a.z.a(Z)Z: nó mở đầu bằng `if (fE.a().bo) return false`
    // — tức TỰ TỪ CHỐI khi auto-combat đang bật, nên chỉ gọi được lúc combat còn TẮT.
    // Cùng hàm đó gán `z.a = null` ở 4 nhánh ⇒ null là trạng thái HỢP LỆ, xoá không vỡ gì.
    private Field zFieldTarget;      // z.a → a.x (mục tiêu đang đánh)
    private Method zMethodPickTarget;// z.a(boolean) → boolean (chọn mục tiêu mới)
    // z.O = danh sách entity mà chính hàm chọn mục tiêu của game duyệt qua
    // (a.z.a(Z)Z: Collections.sort(this.O, a.x.b) rồi elementAt(i) ép kiểu a.x).
    // Dùng đúng danh sách đó để tra con quái lead đang đánh, không tự chế danh sách khác.
    private Field zFieldO;           // z.O → Vector các entity nhắm được
    // z.E = danh sách QUÁI, chỉ chứa a.fn, không lẫn người chơi. Xác định từ bytecode:
    // a.z.a(I)La/fn; và a.z.b(I)La/fn; đều duyệt z.E rồi ép kiểu thẳng sang a.fn không
    // kiểm instanceof — ép mù như vậy chỉ an toàn nếu vector thuần một loại.
    private Field zFieldE;           // z.E → Vector quái
    private Field zFieldD;           // z.D → Vector NGƯỜI CHƠI (a.i) của khu — xem initReflection
    // a.x.aZ = MÃ ĐỊNH DANH CÁ THỂ do MÁY CHỦ cấp. Ba bằng chứng, không phải suy đoán:
    //  1. a.z.b(int id) duyệt z.E, lọc DUY NHẤT bằng `m.aZ == id`, trúng là trả về ngay —
    //     tức game coi giá trị này là duy nhất, đó là định nghĩa của mã định danh.
    //  2. Hàm anh em a.z.a(int) cũng nhận int cũng trả a.fn nhưng so `a.fn.V` rồi GOM VÀO
    //     LIST + sắp xếp + lấy phần tử đầu — tức nhiều con chia chung một V. Hai hàm cạnh
    //     nhau cho thấy chính game phân biệt "khoá duy nhất" với "phân loại".
    //  3. Chỗ DUY NHẤT ghi vào nó là a.fn.a(a.fm) — hàm đọc GÓI TIN mô tả quái, và aZ là
    //     trường ĐẦU TIÊN đọc ra (`fm.c()` = readShort). Máy chủ cấp ⇒ mọi client cùng giá
    //     trị. Đây là điều kiện sống còn của chế độ 2: member phải tra ra ĐÚNG con của lead.
    private Field mobFieldId;        // a.x.aZ → int (mã cá thể)
    // Tên và loại — đọc từ chính gói mô tả thực thể (a.fn.a(a.fm)):
    //   fm.a.k() -> String  đặt vào a.fn.ad   = TÊN hiển thị
    //   fm.c()   -> short   đặt vào a.fn.D    = MÃ LOẠI (nhiệm vụ so nó với dq.ci để biết săn gì)
    private Field mobFieldName;      // a.fn.ad → String
    private Field mobFieldType;      // a.fn.D  → short
    // PHÂN LOẠI THẬT của thực thể, lấy từ chính hàm chọn mục tiêu a.z.a(Z)Z:
    //   fn.a().r == 11  -> bộ chọn của game BỎ QUA, luôn luôn (không đánh được)
    //   fn.a().r == 10  -> xử lý riêng, đối chiếu với mục tiêu nhiệm vụ
    //   fn.V == 3       -> quái thường (bị bỏ khi bật ưu tiên tinh anh)
    // Không có hai trường này thì "đếm quái sống" chỉ là đếm phần tử trong vector.
    private Method mobMethodFo;      // a.fn.a() → a.fo
    private Field foFieldR;          // a.fo.r  → byte
    private Field mobFieldV;         // a.fn.V  → byte
    // ── Đọc được từ bản mổ xẻ trường ngày 29/07 (file soi_map_20260729_144957.log) ──
    // Không suy đoán: mổ hết trường của mỗi loại thực thể rồi đối chiếu giá trị giữa các loại.
    // ĐÃ XÁC NHẬN bằng ảnh chụp màn hình game (29/07): con "Trâu Rừng" hiện trên đầu
    //   HP 65000/65000 · "Lv: 53 + 4739 Exp" · nhãn "Thủ lĩnh"
    // khớp đúng con loai=196 duy nhất trong bản soi: A=65000, cQ=53, cI=4739, aZ=true.
    // Ba số một lúc, không phải trùng hợp.
    //
    // PHẠM VI CỦA BẰNG CHỨNG: con đó là boss của MAP TRAIN NGOÀI (map 74), KHÔNG phải boss trong
    // các hoạt động. Nên "aZ = cờ thủ lĩnh" mới chỉ đúng cho boss ngoài. Boss của sơn cáp / ải
    // gia tộc có mang cùng cờ đó không thì CHƯA ĐO — phải soi ngay trong hoạt động mới biết,
    // đừng dựa vào nó để viết luật cho sơn cáp trước khi có số.
    private Field mobFieldHp;        // a.fn.y  → int, HP hiện tại
    private Field mobFieldHpMax;     // a.fn.A  → int, HP tối đa. VẬT THỂ KHÔNG ĐÁNH ĐƯỢC = 1
    private Field mobFieldLevel;     // a.fn.cQ → int, CẤP (ảnh: Lv 53)
    private Field mobFieldExp;       // a.fn.cI → int, EXP thưởng (ảnh: + 4739 Exp)
    private Field mobFieldElite;     // a.fn.aZ → BOOLEAN (khác a.x.aZ kiểu int!) = THỦ LĨNH
    // Toạ độ của mục tiêu: a.x thừa kế a.bf thừa kế a.fR, và ar/as nằm tận a.fR.
    private Field mobFieldAr;        // a.fR.ar → short (x của entity)
    private Field mobFieldAs;        // a.fR.as → short (y của entity)
    private Field xFieldState;       // a.x.v → byte (4/5/6 = đã chết), nạp lười
    // ỨNG VIÊN "tầm đánh": a.z.a(Z)Z mở đầu bằng (i.a.am + i.Q) rồi đem so với 200.
    // CHƯA XÁC NHẬN là tầm đánh — giá trị đó bị hàm vứt đi ngay sau (code chết do bấm mã)
    // nên không suy ra ý nghĩa từ cách dùng được. In ra để đối chiếu giữa các nick khác lớp.
    private Field iFieldWeapon;      // a.i.a → a.fF (vũ khí/kỹ năng đang trang bị)
    private Field iFieldQ;           // a.i.Q → int
    private Field ffFieldAm;         // a.fF.am → short
    private Field zFieldAp;          // z.ap → boolean (auto navigation flag)

    // a.bi_0 class (navigation target)
    private Class<?> bi0Class;
    private java.lang.reflect.Constructor<?> bi0Constructor6; // bi_0(int, int, int, int, int, int)

    // a.i class
    private Class<?> iClass;
    private Method iGetInstance;     // i.a() → static i
    private Field iFieldV;           // i.v → int (tuần hoàn remaining)
    private Field iFieldW;           // i.w → int (linh thú remaining)
    private Field iFieldAr;          // inherited from fr_0: ar → short (X position)
    private Field iFieldAs;          // inherited from fr_0: as → short (Y position)
    // a.i.f (byte) = CỜ TRẠNG THÁI. Tra ra từ mã nguồn, không phải suy đoán:
    //   · ba nút C.Trắng/C.Xanh/C.Đỏ mang mã 7001/7002/7003 và gọi a.z.i(0)/i(2)/i(3)
    //   · a.z.i(int) gửi gói fm(-15) + writeByte(v)
    //   · server báo về qua a.z.bE(): `this.b(id).f = readByte()`
    //   · a.i vẽ icon 722 khi f==2 và 728 khi f==3 — đúng icon của hai cái nút xanh/đỏ
    // ⚠️ a.i có NHIỀU trường tên "f" khác kiểu (byte, long, short, D[], Vector) nên phải lọc
    // theo KIỂU byte, y như bẫy "aZ" và "a" ở phần trên.
    private Field iFieldFlag;        // a.i.f → byte: 0 trắng · 2 xanh · 3 đỏ (1 = chưa rõ)

    // a.dq class
    private Class<?> dqClass;
    private Method dqMethodP;        // dq.p() → boolean (completion check: as >= ax)
    private Field dqFieldAY;         // dq.aY → int (task category: 0=tuần hoàn, 1=linh thú)
    private Field dqFieldAr;         // dq.ar → int (step/sub-type)
    private Field dqFieldAs;         // dq.as → int (progress / current count)
    private Field dqFieldAu;         // dq.au → int (data field 1)
    private Field dqFieldAv;         // dq.av → int (data field 2 / NPC talk target)
    private Field dqFieldCi;         // dq.ci → int (mob ID for kill tasks)
    private Field dqFieldK;          // dq.k → int (data field 4)
    private Field dqFieldA;          // dq.a → int (data field 5)
    private Field dqFieldAw;         // dq.aw → int (data field 6)
    private Field dqFieldAx;         // dq.ax → int (required count, default=1)
    private Field dqFieldS;          // dq.S → String (task text 1)
    private Field dqFieldX;          // dq.X → String (task text 2)
    private Field dqFieldC;          // dq.c → String (task text 3)

    // a.fe_0 class (auto combat)
    private Class<?> fe0Class;
    private Method fe0GetInstance;   // fe_0.a() → static fe_0
    private Field fe0FieldBo;        // fe_0.bo → boolean (auto combat)
    private Field fe0FieldE;         // fe_0.e → byte[] (auto combat settings, e[40]=priority toggle)
    // fe_0 static fields for auto-navigation
    private Field fe0FieldAs;        // fe_0.as → static int (target map ID for auto-nav)
    private Field fe0FieldAu;        // fe_0.au → static int (zone for auto-nav)
    private Field fe0FieldAv;        // fe_0.av → static int (mob type for auto-nav)

    // a.fp class (navigation)
    private Class<?> fpClass;
    private Method fpMethodC;        // fp.c(int, int, int) → static void

    // a.fm class (packet)
    private Class<?> fmClass;
    private Method fmWriteUTF;       // fm.m(String) → writeUTF. CHÚ Ý: 'm' bị overload (boolean/String)

    // a.em class (Nhóm / tổ đội) — client tự parse CMD 43 vào đây, không cần hook packet
    private Class<?> emClass;
    private Field zFieldGroup;       // z.<field kiểu em> → em (nhóm hiện tại, không bao giờ null)
    private Method emMethodQ;        // em.q() → boolean: CHƯA có nhóm (r.size() == 0)
    private Method emMethodP;        // em.p() → boolean: MÌNH là trưởng nhóm (r[0].j == tên nhân vật mình)
    private Field emFieldR;          // em.r → Vector<bQ> thành viên, phần tử 0 LÀ TRƯỞNG NHÓM
    private Field emFieldH;          // em.h → boolean: nhóm đang khoá

    // a.au class (NPC Dialog)
    private Class<?> auClass;
    private Field auFieldAs;         // au.as → int (dialog type: >=0 NPC entity, -2 OpenMenu)
    private Method auMethodAw;       // au.aw() → void (close dialog)
    private Field auFieldMenuItems;  // au.c → String[] (menu options text)

    // fk.an field (dialog stack Vector)
    private Field fkFieldAn;         // fk.an → Vector (panel/dialog stack)

    // a.fr class (NPC)
    private Class<?> frClass;
    private Field frFieldAh;         // fr.ah → short (NPC Template ID)
    private Field frFieldAr;         // inherited from bf: ar → short (X position)
    private Field frFieldAs;         // inherited from bf: as → short (Y position)
    private Field frFieldAZ;         // inherited from x: aZ → int (NPC Entity ID / index)

    // a.fs class (NPC Template)
    private Class<?> fsClass;
    private Field fsFieldL;          // fs.l → String (NPC name)
    private Field fsFieldY;          // fs.y → int (NPC HP)

    // au: các field phụ của lớp dialog
    private Field auFieldV;          // au.v → Vector (question text lines)
    private Field auFieldAr;         // au.ar → int (parent menu index)

    // ═══════════════════════════════════════════════════════════════
    // SINGLETON
    // ═══════════════════════════════════════════════════════════════

    private static TaskManager instance;

    public static TaskManager getInstance() {
        if (instance == null) {
            instance = new TaskManager();
        }
        return instance;
    }

    private TaskManager() {
        String indexTH = System.getProperty("auto.index_tuanhoan");
        if (indexTH != null && !indexTH.isEmpty()) {
            try { menuIndexTuanHoan = Integer.parseInt(indexTH); }
            catch (NumberFormatException e) { /* giữ default */ }
        }
        String indexLT = System.getProperty("auto.index_linhthu");
        if (indexLT != null && !indexLT.isEmpty()) {
            try { menuIndexLinhThu = Integer.parseInt(indexLT); }
            catch (NumberFormatException e) { /* giữ default */ }
        }
        log("TaskManager initialized. TH_index=" + menuIndexTuanHoan + ", LT_index=" + menuIndexLinhThu);
    }

    // ═══════════════════════════════════════════════════════════════
    // REFLECTION INIT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Khởi tạo tất cả Reflection references.
     * Gọi 1 lần khi game đã sẵn sàng.
     */
    private void initReflection() {
        if (reflectionReady) return;

        log("═══ Initializing Reflection ═══");
        int successCount = 0;

        // ── a.z (Game State) ──
        try {
            zClass = Class.forName("a.z");
            for (Method m : zClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getReturnType() == zClass) {
                    zGetInstance = m;
                    zGetInstance.setAccessible(true);
                    break;
                }
            }
            // ── z.a (a.x) = mục tiêu đang đánh, + z.a(boolean) = hàm chọn mục tiêu ──
            // Bytecode có NHIỀU field cùng tên "a" khác kiểu (a.m[], a.cq[], Vector, a.aV, a.by...)
            // nên getDeclaredField("a") trả về cái nào là không xác định — phải lọc theo KIỂU.
            try {
                Class<?> xClassTemp = Class.forName("a.x");
                for (Field f : zClass.getDeclaredFields()) {
                    if (f.getType() == xClassTemp && f.getName().equals("a")) {
                        zFieldTarget = f;
                        zFieldTarget.setAccessible(true);
                        break;
                    }
                }
                // Method cung vay: a.z co HAI method ten "a" nhan 1 boolean —
                //     public  a(Z)Z        <- hàm chọn mục tiêu, cái mình cần
                //     private a(Z)La/D;    <- hàm khác hẳn
                // getDeclaredMethod("a", boolean.class) KHÔNG phân biệt theo kiểu trả về nên
                // lấy đại một cái; vớ phải cái sau thì invoke trả object D, không phải Boolean,
                // và hàm chọn mục tiêu coi như không bao giờ chạy. Phải lọc theo KIỂU TRẢ VỀ.
                for (Method m : zClass.getDeclaredMethods()) {
                    if (!m.getName().equals("a")) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != boolean.class) continue;
                    if (m.getReturnType() != boolean.class) continue;
                    zMethodPickTarget = m;
                    zMethodPickTarget.setAccessible(true);
                    break;
                }
                Class<?> frUpper = Class.forName("a.fR");   // KHÁC a.fr (chữ thường) đang dùng chỗ khác
                mobFieldAr = frUpper.getDeclaredField("ar");
                mobFieldAr.setAccessible(true);
                mobFieldAs = frUpper.getDeclaredField("as");
                mobFieldAs.setAccessible(true);
                // BẪY LẦN THỨ BA trong ngày: có HAI trường tên aZ — `a.x.aZ` kiểu int (mã quái,
                // thừa kế xuống a.fn) và `a.fn.aZ` kiểu boolean (tự khai). Bytecode phân biệt
                // bằng mô tả `aZ(I)` với `aZ(Z)`; reflection theo TÊN thì không. Vớ nhầm bản
                // boolean là getInt ném lỗi mỗi lần tra. Ép kiểm kiểu, không dựa vào việc mình
                // nhớ phải hỏi lớp nào.
                for (Field f : Class.forName("a.x").getDeclaredFields()) {
                    if (f.getName().equals("aZ") && f.getType() == int.class) {
                        f.setAccessible(true); mobFieldId = f; break;
                    }
                }
                Class<?> fnCls = Class.forName("a.fn");
                Class<?> foCls = Class.forName("a.fo");
                for (Field f : fnCls.getDeclaredFields()) {
                    if (f.getName().equals("ad") && f.getType() == String.class) {
                        f.setAccessible(true); mobFieldName = f;
                    }
                    if (f.getName().equals("D") && f.getType() == short.class) {
                        f.setAccessible(true); mobFieldType = f;
                    }
                    if (f.getName().equals("V") && f.getType() == byte.class) {
                        f.setAccessible(true); mobFieldV = f;
                    }
                    // ── Đọc từ bản mổ xẻ trường 14:49-14:50 ngày 29/07, không phải suy đoán ──
                    // y = HP hiện tại, A = HP tối đa. Bằng chứng: con loai=180 đang bị đánh cho ra
                    //   y=26097 / A=51000, mọi con nguyên vẹn cho y == A. Trùng đúng cặp chữ mà
                    //   a.i dùng cho HP của chính nhân vật (y/A = HP, z/B = nội lực).
                    if (f.getName().equals("y") && f.getType() == int.class) {
                        f.setAccessible(true); mobFieldHp = f;
                    }
                    if (f.getName().equals("A") && f.getType() == int.class) {
                        f.setAccessible(true); mobFieldHpMax = f;
                    }
                    // cQ = cấp, cI = exp thưởng. Ảnh chụp game: "Lv: 53 + 4739 Exp" — đúng cặp
                    // (cQ=53, cI=4739) của con loai=196.
                    if (f.getName().equals("cQ") && f.getType() == int.class) {
                        f.setAccessible(true); mobFieldLevel = f;
                    }
                    if (f.getName().equals("cI") && f.getType() == int.class) {
                        f.setAccessible(true); mobFieldExp = f;
                    }
                    // aZ (BOOLEAN, không phải aZ int của a.x) = THỦ LĨNH. Chỉ con loai=196 bật,
                    // và đúng con đó: HP tối đa 65000 (cao nhất map), khung hình a.bf.n=86 (to
                    // gấp đôi quái thường 29-39), trong game hiện nhãn "Thủ lĩnh".
                    if (f.getName().equals("aZ") && f.getType() == boolean.class) {
                        f.setAccessible(true); mobFieldElite = f;
                    }
                }
                for (Method m : fnCls.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 0
                            && m.getReturnType() == foCls) {
                        m.setAccessible(true); mobMethodFo = m; break;
                    }
                }
                for (Field f : foCls.getDeclaredFields()) {
                    if (f.getName().equals("r") && f.getType() == byte.class) {
                        f.setAccessible(true); foFieldR = f; break;
                    }
                }

                // Ứng viên tầm đánh — lại phải lọc theo KIỂU, a.i có nhiều field tên "a".
                Class<?> iCls = Class.forName("a.i");
                Class<?> ffCls = Class.forName("a.fF");
                for (Field f : iCls.getDeclaredFields()) {
                    if (f.getType() == ffCls && f.getName().equals("a")) {
                        iFieldWeapon = f; iFieldWeapon.setAccessible(true); break;
                    }
                }
                for (Field f : iCls.getDeclaredFields()) {
                    if (f.getName().equals("Q") && f.getType() == int.class) {
                        iFieldQ = f; iFieldQ.setAccessible(true); break;
                    }
                }
                for (Field f : ffCls.getDeclaredFields()) {
                    if (f.getName().equals("am") && f.getType() == short.class) {
                        ffFieldAm = f; ffFieldAm.setAccessible(true); break;
                    }
                }
            } catch (Exception e2) {
                log("  z target fields NOT found: " + e2.getMessage());
            }
            log("  z muc tieu: field=" + (zFieldTarget != null)
                    + " pickTarget=" + (zMethodPickTarget != null)
                    + " toado=" + (mobFieldAr != null && mobFieldAs != null));

            Class<?> dqClassTemp = Class.forName("a.dq");
            int dqFieldCount = 0;
            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == dqClassTemp && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    dqFieldCount++;
                    if (dqFieldCount == 1) zFieldA = f;
                    if (dqFieldCount == 2) zFieldB = f;
                }
            }
            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("u") && f.getType() == short.class) {
                    zFieldU = f;
                }
                if (f.getName().equals("F") && f.getType() == java.util.Vector.class) {
                    zFieldF = f;
                }
                if (f.getName().equals("O") && f.getType() == java.util.Vector.class) {
                    zFieldO = f;
                }
                if (f.getName().equals("E") && f.getType() == java.util.Vector.class) {
                    zFieldE = f;
                }
                // z.D = DANH SÁCH NGƯỜI CHƠI (a.i) của khu, do server gửi từng người một
                // (a.z.s(fm) nạp vào, chống trùng theo mã cá thể aZ). KHÁC z.O: z.O là bản đã
                // lọc lại theo khung camera và lẫn cả quái — vòng vẽ duyệt hết z.D rồi mới
                // nhặt sang z.O những ai nằm trong khung. Muốn biết KHU CÓ BAO NHIÊU NGƯỜI thì
                // phải đọc z.D.
                //
                // LỌC THEO KIỂU, KHÔNG THEO TÊN: class z có HAI trường tên 'D' — một Vector và
                // một long. Đây đúng là cái bẫy đã sập mấy lần trong dự án này.
                if (f.getName().equals("D") && f.getType() == java.util.Vector.class) {
                    zFieldD = f;
                }
                // z.v = KHU hiện tại. Client đọc theo đúng thứ tự server ghi ở packet -103:
                // readShort() -> zoneID (z.v), rồi readShort() -> mapID (z.u).
                if (f.getName().equals("v") && f.getType() == short.class) {
                    zFieldV = f;
                }
                // z.ah = ô "tự cho vào nhóm" trên giao diện (al_0 đọc nó ra checkbox aq.aD).
                // Khi bật, client của TRƯỞNG NHÓM tự trả lời CMD 41 cho mọi lời xin gia nhập
                // (k.java case 39) — không hiện popup, không phải bấm tay.
                if (f.getName().equals("ah") && f.getType() == boolean.class) {
                    zFieldAh = f;
                }
            }
            log("✓ z: getInstance=" + (zGetInstance != null) + " fieldA=" + (zFieldA != null)
                    + " fieldB=" + (zFieldB != null) + " fieldU=" + (zFieldU != null) + " fieldF=" + (zFieldF != null)
                    + " fieldV/khu=" + (zFieldV != null) + " fieldAh/tuchovao=" + (zFieldAh != null));
            successCount++;
        } catch (Exception e) {
            log("✗ z FAILED: " + e.getMessage());
        }

        // ── a.bI (Navigation Target - decompiled as bi_0) ──
        try {
            // CFR renamed a.bI → a.bi_0, nhưng runtime class thật là a.bI
            bi0Class = Class.forName("a.bI");
            // Constructor: bI(int, int, int, int, int, int)
            bi0Constructor6 = bi0Class.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, int.class);
            bi0Constructor6.setAccessible(true);

            // Tìm field type bI trong z (= z.b type bi_0 = navigation target)
            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == bi0Class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    zFieldNavTarget = f;
                    break;
                }
            }
            log("✓ bI(bi_0): class=" + (bi0Class != null) + " ctor6=" + (bi0Constructor6 != null)
                    + " zNavTarget=" + (zFieldNavTarget != null));
            // Tìm z.ap (boolean auto navigation flag)
            try {
                zFieldAp = zClass.getDeclaredField("ap");
                zFieldAp.setAccessible(true);
                log("  z.ap field found!");
            } catch (NoSuchFieldException e2) {
                log("  z.ap field NOT found");
            }
        } catch (Exception e) {
            log("✗ bI(bi_0) FAILED: " + e.getMessage());
        }

        // ── a.i (Player Data) ──
        try {
            iClass = Class.forName("a.i");
            for (Method m : iClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getReturnType() == iClass) {
                    iGetInstance = m;
                    iGetInstance.setAccessible(true);
                    break;
                }
            }
            for (Field f : iClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("v") && f.getType() == int.class) iFieldV = f;
                if (f.getName().equals("w") && f.getType() == int.class) iFieldW = f;
                // CỜ trắng/xanh/đỏ. Lọc theo kiểu byte: cùng tên "f" còn có long, short, D[], Vector.
                if (f.getName().equals("f") && f.getType() == byte.class) iFieldFlag = f;
            }
            // ar, as - position, tìm trong class hierarchy
            Class<?> parentClass = iClass;
            while (parentClass != null && parentClass != Object.class) {
                for (Field f : parentClass.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().equals("ar") && f.getType() == short.class) iFieldAr = f;
                    if (f.getName().equals("as") && f.getType() == short.class) iFieldAs = f;
                }
                if (iFieldAr != null && iFieldAs != null) break;
                parentClass = parentClass.getSuperclass();
            }
            log("✓ i: getInstance=" + (iGetInstance != null) + " v=" + (iFieldV != null)
                    + " w=" + (iFieldW != null) + " ar=" + (iFieldAr != null) + " as=" + (iFieldAs != null)
                    + " co=" + (iFieldFlag != null));
            successCount++;
        } catch (Exception e) {
            log("✗ i FAILED: " + e.getMessage());
        }

        // ── a.dq (Task Object) ──
        try {
            dqClass = Class.forName("a.dq");
            dqMethodP = dqClass.getDeclaredMethod("p");
            dqMethodP.setAccessible(true);
            dqFieldAY = dqClass.getDeclaredField("aY");  dqFieldAY.setAccessible(true);
            dqFieldAr = dqClass.getDeclaredField("ar");  dqFieldAr.setAccessible(true);
            dqFieldAs = dqClass.getDeclaredField("as");  dqFieldAs.setAccessible(true);
            dqFieldAu = dqClass.getDeclaredField("au");  dqFieldAu.setAccessible(true);
            dqFieldAv = dqClass.getDeclaredField("av");  dqFieldAv.setAccessible(true);
            dqFieldCi = dqClass.getDeclaredField("ci");  dqFieldCi.setAccessible(true);
            dqFieldK  = dqClass.getDeclaredField("k");   dqFieldK.setAccessible(true);
            dqFieldA  = dqClass.getDeclaredField("a");   dqFieldA.setAccessible(true);
            dqFieldAw = dqClass.getDeclaredField("aw");  dqFieldAw.setAccessible(true);
            dqFieldAx = dqClass.getDeclaredField("ax");  dqFieldAx.setAccessible(true);
            dqFieldS  = dqClass.getDeclaredField("S");   dqFieldS.setAccessible(true);
            dqFieldX  = dqClass.getDeclaredField("X");   dqFieldX.setAccessible(true);
            dqFieldC  = dqClass.getDeclaredField("c");   dqFieldC.setAccessible(true);
            log("✓ dq: all fields OK");
            successCount++;
        } catch (Exception e) {
            log("✗ dq FAILED: " + e.getMessage());
        }

        // ── a.fE (Auto Combat - decompiled as fe_0) ──
        try {
            fe0Class = Class.forName("a.fE");
            for (Method m : fe0Class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getReturnType() == fe0Class) {
                    fe0GetInstance = m;
                    fe0GetInstance.setAccessible(true);
                    break;
                }
            }
            // Tìm field boolean 'bo' - nếu ko có, tìm bất kỳ boolean nào tên bo
            try {
                fe0FieldBo = fe0Class.getDeclaredField("bo");
                fe0FieldBo.setAccessible(true);
            } catch (NoSuchFieldException e2) {
                log("  bo field not found, searching all boolean fields...");
                for (Field f : fe0Class.getDeclaredFields()) {
                    f.setAccessible(true);
                    log("  fe field: " + f.getName() + " type=" + f.getType().getSimpleName());
                }
            }
            // Tìm field byte[] 'e' chứa settings auto combat (e[40] = priority targeting toggle)
            // Chú ý: fe_0 có 2 field tên 'e' (byte[] và short[]), cần tìm đúng byte[]
            for (Field f : fe0Class.getDeclaredFields()) {
                if (f.getName().equals("e") && f.getType() == byte[].class) {
                    fe0FieldE = f;
                    fe0FieldE.setAccessible(true);
                    break;
                }
            }
            if (fe0FieldE == null) {
                log("  fe0 byte[] 'e' field not found");
            }
            // Tìm static fields: as (target map), au (zone), av (mob type) cho auto-navigation
            try {
                fe0FieldAs = fe0Class.getDeclaredField("as");
                fe0FieldAs.setAccessible(true);
                fe0FieldAu = fe0Class.getDeclaredField("au");
                fe0FieldAu.setAccessible(true);
                fe0FieldAv = fe0Class.getDeclaredField("av");
                fe0FieldAv.setAccessible(true);
                log("  fe0 nav fields: as/au/av found!");
            } catch (NoSuchFieldException e2) {
                log("  fe0 nav fields (as/au/av) NOT found: " + e2.getMessage());
            }
            log("✓ fe: getInstance=" + (fe0GetInstance != null) + " bo=" + (fe0FieldBo != null)
                    + " e[]=" + (fe0FieldE != null) + " navFields=" + (fe0FieldAs != null));
            successCount++;
        } catch (Exception e) {
            log("✗ fe (auto combat) FAILED: " + e.getMessage() + " - sẽ skip auto combat");
        }

        // ── a.fp (Navigation) ──
        try {
            fpClass = Class.forName("a.fp");
            fpMethodC = fpClass.getDeclaredMethod("c", int.class, int.class, int.class);
            fpMethodC.setAccessible(true);
            log("✓ fp: c(int,int,int) OK");
            successCount++;
        } catch (Exception e) {
            log("✗ fp (navigation) FAILED: " + e.getMessage());
        }

        // ── a.fm (Packet) ──
        try {
            fmClass = Class.forName("a.fm");
            // fm.m(String) = writeUTF. Phải lấy tường minh theo tham số String vì 'm' có 2 overload
            // (m(boolean) = writeBoolean). Quét theo tên sẽ vớ nhầm bản boolean.
            fmWriteUTF = fmClass.getDeclaredMethod("m", String.class);
            fmWriteUTF.setAccessible(true);
            log("✓ fm: class loaded, writeUTF(m:String)=" + (fmWriteUTF != null));
            successCount++;
        } catch (Exception e) {
            log("✗ fm (packet) FAILED: " + e.getMessage());
        }

        // ── a.em (Nhóm / tổ đội) ──
        // Client tự parse CMD 43 (MeGroup) vào đối tượng này (z.aV), nên chỉ cần ĐỌC FIELD,
        // không cần hook packet vào. em.p()/q() là hàm sẵn có của client.
        try {
            emClass = Class.forName("a.em");
            emMethodQ = emClass.getDeclaredMethod("q");  emMethodQ.setAccessible(true);
            emMethodP = emClass.getDeclaredMethod("p");  emMethodP.setAccessible(true);
            emFieldR  = emClass.getDeclaredField("r");   emFieldR.setAccessible(true);
            emFieldH  = emClass.getDeclaredField("h");   emFieldH.setAccessible(true);

            // z có NHIỀU field trùng tên 'a' khác kiểu → bắt buộc lọc theo TYPE, không theo tên.
            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == emClass && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    zFieldGroup = f;
                    break;
                }
            }
            log("✓ em (nhom): q()=" + (emMethodQ != null) + " p()=" + (emMethodP != null)
                    + " r=" + (emFieldR != null) + " h=" + (emFieldH != null)
                    + " zField=" + (zFieldGroup != null ? zFieldGroup.getName() : "NOT FOUND"));
            successCount++;
        } catch (Exception e) {
            log("✗ em (nhom) FAILED: " + e.getMessage());
        }

        // ── a.au (NPC Dialog) ──
        try {
            auClass = Class.forName("a.au");
            auFieldAs = auClass.getDeclaredField("as");
            auFieldAs.setAccessible(true);
            // au.aw() is defined in parent cd.java → close dialog
            Class<?> cdClass = Class.forName("a.cd");
            auMethodAw = cdClass.getDeclaredMethod("aw");
            auMethodAw.setAccessible(true);
            // Find String[] field named "c" in au (menu items)
            for (Field f : auClass.getDeclaredFields()) {
                if (f.getName().equals("c") && f.getType() == String[].class) {
                    auFieldMenuItems = f;
                    f.setAccessible(true);
                    break;
                }
            }
            // au.v (Vector - nội dung dialog) va au.ar (int - index dialog cha)
            for (Field f : auClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("v") && f.getType() == java.util.Vector.class) auFieldV = f;
                if (f.getName().equals("ar") && f.getType() == int.class) auFieldAr = f;
            }
            log("✓ au: as=" + (auFieldAs != null) + " aw()=" + (auMethodAw != null)
                    + " menuItems=" + (auFieldMenuItems != null));
            successCount++;
        } catch (Exception e) {
            log("✗ au (dialog) FAILED: " + e.getMessage());
        }

        // ── fk.an (Dialog Stack) ──
        try {
            Class<?> fkClass = Class.forName("a.fk");
            fkFieldAn = fkClass.getDeclaredField("an");
            fkFieldAn.setAccessible(true);
            log("✓ fk.an: " + (fkFieldAn != null));
            successCount++;
        } catch (Exception e) {
            log("✗ fk.an (dialog stack) FAILED: " + e.getMessage());
        }

        // ── a.fr (NPC) ──
        try {
            frClass = Class.forName("a.fr");
            frFieldAh = frClass.getDeclaredField("ah");
            frFieldAh.setAccessible(true);
            
            Class<?> parentClass = frClass;
            while (parentClass != null && parentClass != Object.class) {
                for (Field f : parentClass.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().equals("aZ") && f.getType() == int.class) frFieldAZ = f;
                    if (f.getName().equals("ar") && f.getType() == short.class) frFieldAr = f;
                    if (f.getName().equals("as") && f.getType() == short.class) frFieldAs = f;
                }
                parentClass = parentClass.getSuperclass();
            }
            log("✓ fr NPC: ah=" + (frFieldAh != null) + " aZ=" + (frFieldAZ != null) 
                + " ar=" + (frFieldAr != null) + " as=" + (frFieldAs != null));
            successCount++;
        } catch (Exception e) {
            log("✗ fr NPC FAILED: " + e.getMessage());
        }

        // ── a.fs (NPC Template) ──
        try {
            fsClass = Class.forName("a.fs");
            for (Field f : fsClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("l") && f.getType() == String.class) fsFieldL = f;
                if (f.getName().equals("y") && f.getType() == int.class) fsFieldY = f;
            }
            log("✓ fs (NPC Template): l=" + (fsFieldL != null) + " y=" + (fsFieldY != null));
            successCount++;
        } catch (Exception e) {
            log("✗ fs (NPC Template) FAILED: " + e.getMessage());
        }

        reflectionReady = true;
        log("═══ Reflection done: " + successCount + "/11 modules OK ═══");

        // Debug: thử đọc game state ngay
        try {
            if (zGetInstance != null) {
                Object zInst = zGetInstance.invoke(null);
                log("DEBUG z instance: " + (zInst != null ? "OK" : "NULL"));
                if (zInst != null && zFieldU != null) {
                    log("DEBUG current map: " + zFieldU.getShort(zInst));
                }
                if (zInst != null && zFieldA != null) {
                    Object taskA = zFieldA.get(zInst);
                    log("DEBUG tuanHoan task: " + (taskA != null ? "EXISTS" : "NULL"));
                }
            }
            if (iGetInstance != null) {
                Object iInst = iGetInstance.invoke(null);
                log("DEBUG i instance: " + (iInst != null ? "OK" : "NULL"));
                if (iInst != null && iFieldV != null) {
                    log("DEBUG tuanHoan remaining (v): " + iFieldV.getInt(iInst));
                }
                if (iInst != null && iFieldW != null) {
                    log("DEBUG linhThu remaining (w): " + iFieldW.getInt(iInst));
                }
            }
        } catch (Exception e) {
            log("DEBUG read failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // REFLECTION HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Lấy z singleton instance */
    private Object getZ() throws Exception {
        return zGetInstance.invoke(null);
    }

    /** Lấy i singleton instance */
    private Object getI() throws Exception {
        return iGetInstance.invoke(null);
    }

    /** Lấy fe_0 singleton instance */
    private Object getFe0() throws Exception {
        return fe0GetInstance.invoke(null);
    }

    /** Lấy current map ID */
    public short getCurrentMapId() throws Exception {
        return zFieldU.getShort(getZ());
    }

    /** Lấy player X position */
    public short getPlayerX() throws Exception {
        return iFieldAr.getShort(getI());
    }

    /** Lấy player Y position */
    public short getPlayerY() throws Exception {
        return iFieldAs.getShort(getI());
    }

    /**
     * Đọc CỜ TRẠNG THÁI của nhân vật mình: 0 = trắng · 2 = xanh · 3 = đỏ.
     *
     * Trả -1 khi không đọc được (chưa nạp được trường, chưa vào game). KHÔNG ném lỗi: hàm này
     * được gọi trong vòng lặp soi 30s và ở đường kết thúc hoạt động — ném ở đó là mất luôn cả
     * bước bàn giao AFK vì một con số phụ.
     *
     * Còn một giá trị thứ tư là 1, mà ba cái nút trong game KHÔNG BAO GIỜ tạo ra được (chúng chỉ
     * gửi 0/2/3). a.i vẽ một nhãn riêng cho f==1 và a.z chặn một mục menu khi f==1 ⇒ rất có thể
     * là trạng thái server tự đặt lúc đang thi đấu. CHƯA ĐO ĐƯỢC, nên không có luật nào trong
     * tool dựa vào nó — chỉ in ra để lượt chạy thật trả lời.
     */
    private int readPlayerFlag() {
        if (iFieldFlag == null) return -1;
        try {
            return iFieldFlag.getByte(getI());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Tên đọc được của cờ, để log không phải đoán con số. */
    private String flagName(int f) {
        switch (f) {
            case 0:  return "trang";
            case 1:  return "1-chua-ro";
            case 2:  return "xanh";
            case 3:  return "do";
            case -1: return "khong-doc-duoc";
            default: return "la-" + f;
        }
    }

    /**
     * Gửi gói ĐỔI CỜ. Chép đúng đường mà chính game đi khi bấm ba cái nút C.Trắng/C.Xanh/C.Đỏ:
     * a.z.i(int) = new fm(-15) → writeByte(v) → aG().
     *
     * @param v 0 = trắng, 2 = xanh, 3 = đỏ. Không nhận giá trị khác — 1 là trạng thái server đặt,
     *          gửi lên là gửi thứ client thật không bao giờ gửi.
     */
    private void sendSetFlag(int v) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) -15);
        Method writeByte = fmClass.getDeclaredMethod("s", int.class);
        writeByte.setAccessible(true);
        writeByte.invoke(packet, v);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /** Lấy tuần hoàn remaining count */
    private int getTuanHoanRemaining() throws Exception {
        return iFieldV.getInt(getI());
    }

    /** Lấy linh thú remaining count */
    private int getLinhThuRemaining() throws Exception {
        return iFieldW.getInt(getI());
    }

    /** Lấy dq task object cho tuần hoàn */
    private Object getTuanHoanTask() throws Exception {
        return zFieldA.get(getZ());
    }

    /** Lấy dq task object cho linh thú */
    private Object getLinhThuTask() throws Exception {
        return zFieldB.get(getZ());
    }

    /** Kiểm tra task hoàn thành (dq.p()) */
    private boolean isTaskCompleted(Object task) throws Exception {
        if (task == null) return false;
        return (Boolean) dqMethodP.invoke(task);
    }

    /** Lấy field int từ task */
    private int getTaskField(Object task, Field field) throws Exception {
        return field.getInt(task);
    }

    /** Lấy field String từ task */
    private String getTaskString(Object task, Field field) throws Exception {
        return (String) field.get(task);
    }

    /** Lấy tên mob từ mob template ID (hardcoded từ DB) */
    private String getMobNameById(int mobId) {
        switch (mobId) {
            case 175: return "Ốc ma";
            case 176: return "Bọ cạp càng";
            default: return "Unknown(" + mobId + ")";
        }
    }

    /** Bật/tắt auto combat */
    private void setAutoCombat(boolean value) {
        if (fe0FieldBo == null || fe0GetInstance == null) return; // Skip nếu reflection fail
        try {
            fe0FieldBo.setBoolean(getFe0(), value);
        } catch (Exception e) {
            log("setAutoCombat error: " + e.getMessage());
        }
    }

    /**
     * Xoá MỤC TIÊU đang đánh (z.a = null).
     *
     * Vì sao cần: bật/tắt auto-combat KHÔNG đụng tới mục tiêu. Nên khi member bám theo lead
     * chạy tới nơi rồi bật đánh lại, nó vẫn còn khoá vào con quái xa tít lúc nãy và tự đi
     * ngược lại chỗ cũ — sinh vòng lặp đuổi/quay-lui cho tới khi con quái đó chết.
     *
     * An toàn: chính game gán null vào field này ở 4 nhánh trong a.z.a(Z)Z, nên null là
     * trạng thái hợp lệ chứ không phải trạng thái mình bịa ra.
     */
    private void clearCombatTarget() {
        if (zFieldTarget == null) return;
        try {
            zFieldTarget.set(getZ(), null);
        } catch (Exception e) {
            log("clearCombatTarget error: " + e.getMessage());
        }
    }

    /**
     * Gọi đúng hàm của nút "chuyển mục tiêu" trong game để khoá vào mục tiêu gần nhất.
     *
     * BẮT BUỘC gọi khi auto-combat đang TẮT: bytecode a.z.a(Z)Z mở đầu bằng
     * `if (fE.a().bo) return false` — bật đánh rồi thì nó không làm gì cả.
     * Tham số false = áp đủ bộ lọc của game (bỏ qua người chơi khác, mục tiêu cấm đánh...),
     * đúng thứ nút trong game làm.
     *
     * @return true nếu game đã chọn được mục tiêu mới
     */
    private boolean pickNearestTarget() {
        if (zMethodPickTarget == null) return false;
        try {
            if (isAutoCombatOn()) return false;   // gọi lúc này là vô nghĩa, game từ chối
            Object r = zMethodPickTarget.invoke(getZ(), Boolean.FALSE);
            return (r instanceof Boolean) && ((Boolean) r).booleanValue();
        } catch (Exception e) {
            log("pickNearestTarget error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Bật đánh lại một cách "sạch": bỏ đích đi bộ, bỏ mục tiêu cũ, chọn mục tiêu gần nhất,
     * rồi mới bật. Thứ tự này quan trọng — chọn mục tiêu phải làm lúc combat còn tắt.
     *
     * @return mô tả ngắn để ghi log, ví dụ " (da doi muc tieu)"
     */
    private String combatOnFresh(boolean retarget) {
        clearNavTarget();
        String note = "";
        if (retarget) {
            String before = describeTarget();
            clearCombatTarget();
            String after;
            if (!pickNearestTarget()) {
                // Không chọn được cũng KHÔNG sao: z.a đã null, tức đã bỏ con quái xa cũ —
                // đúng yêu cầu. Engine sẽ tự khoá mục tiêu mới ở khung hình sau.
                after = "de trong (engine tu chon)";
            } else {
                // Hàm chọn của game KHÔNG xếp theo khoảng cách. Log 08:55 ngày 29/07 bắt được
                // 4 lần nó trả về mục tiêu XA HƠN cái vừa bỏ (317->344, 271->327, 250->286,
                // 341->343). Nhận cái đó là tự chuốc thêm một chuyến chạy xa, mà lại vừa tốn
                // một nhịp tắt/bật đánh. Xa quá thì thà để trống cho engine tự chọn.
                int max = getSettingInt("follow_target_max_px", 250);
                int nd = targetDistance();
                if (max > 0 && nd > max) {
                    clearCombatTarget();
                    after = "chon ra con cach " + nd + " van qua xa -> de trong";
                } else {
                    after = describeTarget();
                }
            }
            note = " [MT " + before + " -> " + after + "]";
        }
        setAutoCombat(true);
        autoCombatRequested = true;
        return note;
    }

    /**
     * Khoảng cách từ nhân vật tới MỤC TIÊU đang đánh, hoặc -1 nếu không có / không đọc được.
     * Dùng để phát hiện sớm mục tiêu đã lạc, thay vì đợi nhân vật chạy theo nó rồi mới kéo về.
     */
    /**
     * ỨNG VIÊN tầm đánh của nhân vật: (vũ khí/kỹ năng đang trang bị).am + i.Q.
     * Lấy từ đầu hàm chọn mục tiêu a.z.a(Z)Z. CHƯA XÁC NHẬN là tầm đánh — in ra để đối chiếu
     * giữa các nick khác lớp; nếu nick cận chiến và nick đánh xa cho hai số khác hẳn nhau thì
     * mới kết luận, rồi mới dùng nó thay cho follow_near_px cứng.
     * @return -1 nếu không đọc được
     */
    private int getAttackRangeGuess() {
        if (iFieldWeapon == null || iFieldQ == null || ffFieldAm == null) return -1;
        try {
            Object inst = getI();
            Object w = iFieldWeapon.get(inst);
            if (w == null) return -1;
            return ffFieldAm.getShort(w) + iFieldQ.getInt(inst);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Tra entity đứng ở khoảng toạ độ (tx,ty) trong danh sách nhắm được của CHÍNH client này.
     *
     * Không cần ID: cùng một toạ độ thì là cùng một con. Có trùng cũng vô hại — hai con đứng
     * cùng chỗ thì đánh con nào cũng như nhau, mà mục đích chỉ là "đánh cùng chỗ với lead".
     * Dung sai cần thiết vì quái có di chuyển và báo cáo của lead trễ vài trăm ms.
     *
     * Duyệt z.O — đúng danh sách hàm chọn mục tiêu của game dùng — rồi mới tới z.F.
     *
     * @return entity gần (tx,ty) nhất trong dung sai, hoặc null
     */
    private Object findEntityNear(int tx, int ty, int tol) {
        if (mobFieldAr == null || mobFieldAs == null) return null;
        Object best = null;
        int bestD = Integer.MAX_VALUE;
        try {
            Object z = getZ();
            Class<?> playerCls = null;
            try { playerCls = Class.forName("a.i"); } catch (Exception ignore) {}
            for (int pass = 0; pass < 2; pass++) {
                Field src = (pass == 0) ? zFieldO : zFieldF;
                if (src == null) continue;
                Object listObj = src.get(z);
                if (!(listObj instanceof java.util.Vector)) continue;
                java.util.Vector<?> v = (java.util.Vector<?>) listObj;
                for (int i = 0; i < v.size(); i++) {
                    Object e = v.elementAt(i);
                    if (e == null) continue;
                    // Bỏ NGƯỜI CHƠI. Ghi thẳng vào z.a là đi vòng qua bộ lọc của game, mà bộ
                    // lọc đó có nhánh riêng cho a.i — không loại ra thì có ngày nhắm vào người.
                    if (playerCls != null && playerCls.isInstance(e)) continue;
                    if (isEntityDead(e)) continue;              // bỏ xác
                    if (!isKillableMob(e)) continue;            // bỏ vật thể HP 1 (không đánh được)
                    int ex, ey;
                    try {
                        ex = mobFieldAr.getShort(e);
                        ey = mobFieldAs.getShort(e);
                    } catch (Exception ignore) {
                        continue;   // phần tử không phải entity có toạ độ
                    }
                    int d = Math.abs(ex - tx) + Math.abs(ey - ty);
                    if (d <= tol && d < bestD) { bestD = d; best = e; }
                }
                if (best != null) return best;   // thấy ở z.O rồi thì khỏi quét z.F
            }
        } catch (Exception e) {
            log("findEntityNear error: " + e.getMessage());
        }
        return best;
    }

    /**
     * Tra quái theo MÃ ĐỊNH DANH — làm đúng như a.z.b(int) của game: duyệt z.E so aZ.
     * Chính xác tuyệt đối, khỏi dung sai toạ độ, và không thể nhắm nhầm vào người chơi
     * vì z.E không chứa người chơi.
     * @return quái còn sống mang mã đó, hoặc null (chết rồi / chưa thấy)
     */
    private Object findMobById(int id) {
        if (zFieldE == null || mobFieldId == null || id < 0) return null;
        try {
            Object listObj = zFieldE.get(getZ());
            if (!(listObj instanceof java.util.Vector)) return null;
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null) continue;
                if (mobFieldId.getInt(e) != id) continue;
                if (isEntityDead(e) || !isKillableMob(e)) return null;
                return e;
            }
        } catch (Exception e) {
            log("findMobById error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Đây có phải QUÁI ĐÁNH ĐƯỢC không, hay chỉ là vật thể trang trí / điểm tương tác.
     *
     * Tiêu chí là HP TỐI ĐA, và nó đo được chứ không suy ra:
     *   · Làng Cỏ (map 68) có 13 thực thể `a.fn` loai=148, tên rỗng, xếp thành ba hàng ngay ngắn
     *     ở y=99/127/237 — và HP của chúng là **1/1**, sát thương 0, `fn.a().r == 10` (nhánh
     *     "đối chiếu mục tiêu nhiệm vụ" của bộ chọn trong game, không phải nhánh quái thường).
     *   · Quái thật ở map train 74: 48140 / 51000 / 65000.
     * Chênh nhau bốn bậc độ lớn, không có vùng xám nào để phải đoán.
     *
     * Vì sao phải lọc: `countAliveMobs()` là điều kiện "map đã sạch quái chưa" của Ải gia tộc.
     * Đứng ở Làng Cỏ nó trả về 12 — mà 12 con đó không ai giết được, nên nếu map cổng cũng có
     * loại vật thể này thì AGT sẽ chờ mãi một con số không bao giờ về 0. Bộ tra mục tiêu cũng
     * dùng: gán một vật thể HP 1 vào `z.a` là cả nhóm xúm vào đánh một cái cột.
     *
     * Đọc hụt trường ⇒ trả true. Không có số thì đừng loại — loại nhầm quái thật thì AGT qua cổng
     * khi quái còn đầy, tệ hơn hẳn việc đếm dư mấy vật thể.
     */
    private boolean isKillableMob(Object e) {
        if (mobFieldHpMax == null) return true;
        try {
            return mobFieldHpMax.getInt(e) > 1;
        } catch (Exception ignore) {
            return true;
        }
    }

    /** Entity đã chết chưa (byte trạng thái a.x.v, 4/5/6 = chết). Nạp field kiểu lười. */
    private boolean isEntityDead(Object e) {
        try {
            if (xFieldState == null) {
                Class<?> xClass = Class.forName("a.x");
                for (Field f : xClass.getDeclaredFields()) {
                    if (f.getName().equals("v") && f.getType() == byte.class) {
                        f.setAccessible(true); xFieldState = f; break;
                    }
                }
            }
            if (xFieldState == null) return false;
            byte v = xFieldState.getByte(e);
            return v == 4 || v == 5 || v == 6;
        } catch (Exception ex) {
            return false;   // không đọc được thì coi như còn sống, đừng loại oan
        }
    }

    /**
     * Mục tiêu đang đánh còn ĐÁNG GIỮ không?
     *
     * Vì sao cần: lead đổi mục tiêu liên tục (quái HP cao thì đánh lâu rồi đổi, HP thấp thì
     * chết nhanh nên đổi còn nhanh hơn). Gán lại theo mỗi nhịp báo 400ms thì member bị giật
     * qua giật lại, chưa tới con này đã bị nhắm sang con khác — log 09:35 ngày 29/07: cả phút
     * khoảng cách quanh quẩn 77-345px, không tới được con nào.
     *
     * Nên chỉ nhận mục tiêu mới khi mục tiêu cũ hết giá trị: đang trống, đã chết, hoặc đã lạc
     * quá xa khỏi khu vực của lead.
     *
     * @param leadX,leadY vị trí lead; -1 nếu chưa biết thì bỏ qua phép kiểm khoảng cách
     */
    private boolean keepCurrentTarget(int leadX, int leadY) {
        if (!myTargetAlive()) return false;
        try {
            Object t = zFieldTarget.get(getZ());
            if (leadX >= 0 && leadY >= 0) {
                int d = Math.abs(mobFieldAr.getShort(t) - leadX)
                      + Math.abs(mobFieldAs.getShort(t) - leadY);
                if (d > getSettingInt("follow_target_lead_max_px", 500)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mục tiêu hiện tại còn tồn tại và còn sống không? */
    private boolean myTargetAlive() {
        if (zFieldTarget == null || mobFieldAr == null || mobFieldAs == null) return false;
        try {
            Object t = zFieldTarget.get(getZ());
            if (t == null) return false;

            if (xFieldState == null) {
                Class<?> xClass = Class.forName("a.x");
                for (Field f : xClass.getDeclaredFields()) {
                    if (f.getName().equals("v") && f.getType() == byte.class) {
                        f.setAccessible(true); xFieldState = f; break;
                    }
                }
            }
            if (xFieldState != null) {
                byte v = xFieldState.getByte(t);
                if (v == 4 || v == 5 || v == 6) return false;  // chết → nhận con mới
            }
            return true;
        } catch (Exception e) {
            return false;   // đọc không được thì cứ nhận con của lead
        }
    }

    private int targetDistance() {
        if (zFieldTarget == null || mobFieldAr == null || mobFieldAs == null) return -1;
        try {
            Object t = zFieldTarget.get(getZ());
            if (t == null) return -1;
            return Math.abs(mobFieldAr.getShort(t) - getPlayerX())
                 + Math.abs(mobFieldAs.getShort(t) - getPlayerY());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Mô tả mục tiêu đang đánh để ghi log: toạ độ + cách nhân vật bao xa.
     * Có cái này thì lượt chạy sau ĐỌC được chuyện gì xảy ra thay vì phải suy đoán —
     * phân biệt được "không đọc nổi field", "đang không có mục tiêu", và "mục tiêu ở xa".
     */
    private String describeTarget() {
        if (zFieldTarget == null) return "?doc khong duoc";
        try {
            Object t = zFieldTarget.get(getZ());
            if (t == null) return "trong";
            if (mobFieldAr == null || mobFieldAs == null) return t.getClass().getSimpleName();
            int tx = mobFieldAr.getShort(t);
            int ty = mobFieldAs.getShort(t);
            int d = Math.abs(tx - getPlayerX()) + Math.abs(ty - getPlayerY());
            return "(" + tx + "," + ty + ") cach " + d;
        } catch (Exception e) {
            return "?loi";
        }
    }

    /** Kiểm tra auto combat đang bật */
    private boolean isAutoCombatOn() {
        try {
            return fe0FieldBo.getBoolean(getFe0());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tạm tắt cơ chế "ưu tiên đánh tinh anh/thủ lĩnh" khi đang farm quái NV.
     * Game engine sử dụng fe_0.j() = (bo && e[40]==0) để skip quái thường.
     * Set e[40] = -1 để j() trả false → cho phép đánh tất cả quái.
     */
    private void disablePriorityTargeting() {
        if (priorityDisabled) return;
        if (fe0FieldE == null || fe0GetInstance == null) return;
        try {
            Object fe0Inst = getFe0();
            byte[] eArray = (byte[]) fe0FieldE.get(fe0Inst);
            if (eArray != null && eArray.length > 40) {
                savedPriorityByte = eArray[40];
                if (savedPriorityByte == 0) {
                    // e[40] == 0 nghĩa là ưu tiên đang BẬT → cần tắt
                    eArray[40] = -1; // Set != 0 để j() trả false
                    priorityDisabled = true;
                    log("Tam tat uu tien danh tinh anh/thu linh (e[40]: " + savedPriorityByte + " -> -1)");
                } else {
                    // Ưu tiên đã tắt sẵn, không cần làm gì
                    priorityDisabled = false;
                }
            }
        } catch (Exception e) {
            log("disablePriorityTargeting error: " + e.getMessage());
        }
    }

    /**
     * Khôi phục cơ chế "ưu tiên đánh tinh anh/thủ lĩnh" về giá trị ban đầu.
     */
    private void restorePriorityTargeting() {
        if (!priorityDisabled) return;
        if (fe0FieldE == null || fe0GetInstance == null) return;
        try {
            Object fe0Inst = getFe0();
            byte[] eArray = (byte[]) fe0FieldE.get(fe0Inst);
            if (eArray != null && eArray.length > 40) {
                eArray[40] = savedPriorityByte;
                log("Khoi phuc uu tien danh (e[40]: -1 -> " + savedPriorityByte + ")");
            }
        } catch (Exception e) {
            log("restorePriorityTargeting error: " + e.getMessage());
        }
        priorityDisabled = false;
    }

    /** Gọi fp.c(mapId, x, y) để tìm đường (SAME MAP ONLY) */
    private void navigateTo(int mapId, int x, int y) {
        try {
            fpMethodC.invoke(null, mapId, x, y);
        } catch (Exception e) {
            log("navigateTo error: " + e.getMessage());
        }
    }

    /** Về làng: đọc tọa độ từ quest_anchors.cfg (village,mapId,x,y) */
    public void navigateToVillage() {
        try {
            loadAnchorConfig();
            int mapId = 68, x = 819, y = 514; // defaults
            if (villageConfig != null) {
                mapId = villageConfig[0]; x = villageConfig[1]; y = villageConfig[2];
            }
            short currentMap = getCurrentMapId();
            if (currentMap != mapId) {
                // Khác map → dùng navigateToMap (cross-map, bật z.ap)
                setAutoCombat(false);
                navigateToMap(mapId);
                log("Ve lang: cross-map " + currentMap + " -> " + mapId);
            } else {
                // Cùng map → navigate đến tọa độ cụ thể
                navigateTo(mapId, x, y);
                log("Ve lang: same-map (" + x + "," + y + ")");
            }
        } catch (Exception e) {
            log("navigateToVillage error: " + e.getMessage());
        }
    }

    /** Tắt z.ap auto-navigation (tránh xung đột khi navigate bằng config anchor) */
    private void disableAutoNav() {
        try {
            if (zFieldAp != null) {
                Object zInstance = getZ();
                zFieldAp.setBoolean(zInstance, false);
                System.out.println("[TaskManager] z.ap = false (disabled auto-nav)");
            }
        } catch (Exception e) {
            System.out.println("[TaskManager] disableAutoNav error: " + e.getMessage());
        }
    }

    /**
     * Navigate cross-map bằng cách set z.b (bi_0) = navigation target + z.ap=true.
     * Giống y hệt khi player click vào quest info panel.
     */
    private void navigateToQuest(Object task) {
        try {
            if (bi0Constructor6 == null || zFieldNavTarget == null) {
                log("navigateToQuest: bi_0 reflection not ready, fallback fp.c");
                int mapK = getTaskField(task, dqFieldK);
                navigateTo(mapK, DEFAULT_X, DEFAULT_Y);
                return;
            }
            int av = getTaskField(task, dqFieldAv);
            int au = getTaskField(task, dqFieldAu);
            int ci = getTaskField(task, dqFieldCi);
            int k  = getTaskField(task, dqFieldK);
            int a  = getTaskField(task, dqFieldA);
            int aw = getTaskField(task, dqFieldAw);

            Object navTarget = bi0Constructor6.newInstance(av, au, ci, k, a, aw);
            Object zInstance = getZ();
            zFieldNavTarget.set(zInstance, navTarget);
            if (fe0FieldAs != null && zFieldAp != null) {
                fe0FieldAs.setInt(null, k);
                fe0FieldAu.setInt(null, a);
                fe0FieldAv.setInt(null, aw);
                zFieldAp.setBoolean(zInstance, true);
                navStartTime = System.currentTimeMillis(); // timeout tracker
            }
            log("navigateToQuest: bi_0(" + av + "," + au + "," + ci + "," + k + "," + a + "," + aw + ") z.ap=true");
        } catch (Exception e) {
            log("navigateToQuest error: " + e.getMessage());
        }
    }

    /** Navigate cross-map đến map cụ thể (AFK farm). */
    private void navigateToMap(int targetMapId) {
        try {
            Object zInstance = getZ();

            // Tạo bi_0 navigation target — 6 params
            if (bi0Constructor6 != null && zFieldNavTarget != null) {
                Object navTarget = bi0Constructor6.newInstance(0, 0, 0, targetMapId, 0, 0);
                zFieldNavTarget.set(zInstance, navTarget);
            }

            // Kích hoạt auto-navigation native (giống click quest panel)
            if (fe0FieldAs != null && zFieldAp != null) {
                fe0FieldAs.setInt(null, targetMapId);
                fe0FieldAu.setInt(null, 0);
                fe0FieldAv.setInt(null, 0);
                zFieldAp.setBoolean(zInstance, true);
                navStartTime = System.currentTimeMillis(); // timeout tracker
            }
            log("navigateToMap: bi_0(0,0,0," + targetMapId + ",0,0) z.ap=true");
        } catch (Exception e) {
            log("navigateToMap error: " + e.getMessage());
        }
    }

    /**
     * Auto-nav gốc của game tới MỘT TOẠ ĐỘ CỤ THỂ trên map đích.
     *
     * Vì sao phải có bản này: navigateToMap(map) ở trên truyền thẳng 0, 0 làm đích — tức bảo game
     * "đi tới map N rồi tới điểm (0,0)". Đó chính là nguồn của toạ độ ~(80,108) không hề có trong
     * config mà cả 12 nick đều ghé qua: auto-nav kéo nhân vật về góc trên-trái rồi dừng ở ô đi
     * được gần (0,0) nhất. Đối chiếu navigateToQuest thấy rõ hai tham số cuối là toạ độ đích —
     * nhiệm vụ truyền số thật, còn navigateToMap truyền 0.
     *
     * KHÔNG sửa navigateToMap(int) cũ: AFK farm và Địa cung đang dùng và đang chạy được, đổi đích
     * của chúng là đổi hành vi ngoài phạm vi việc này.
     */
    private void navigateToMapXY(int targetMapId, int x, int y) {
        try {
            Object zInstance = getZ();
            if (bi0Constructor6 != null && zFieldNavTarget != null) {
                Object navTarget = bi0Constructor6.newInstance(0, 0, 0, targetMapId, x, y);
                zFieldNavTarget.set(zInstance, navTarget);
            }
            if (fe0FieldAs != null && zFieldAp != null) {
                fe0FieldAs.setInt(null, targetMapId);
                fe0FieldAu.setInt(null, x);
                fe0FieldAv.setInt(null, y);
                zFieldAp.setBoolean(zInstance, true);
                navStartTime = System.currentTimeMillis();
            }
            log("navigateToMapXY: bi_0(0,0,0," + targetMapId + "," + x + "," + y + ") z.ap=true");
        } catch (Exception e) {
            log("navigateToMapXY error: " + e.getMessage());
        }
    }

    /** Check timeout — nếu z.ap stuck quá 20s, force tắt để unlock game. */
    private void checkNavTimeout() {
        if (navStartTime > 0 && System.currentTimeMillis() - navStartTime > 20000) {
            try {
                Object zInstance = getZ();
                if (zFieldAp != null) {
                    boolean apVal = zFieldAp.getBoolean(zInstance);
                    if (apVal) {
                        log("NAV TIMEOUT: z.ap stuck > 20s! Force clear...");
                        clearNavTarget();
                    }
                }
                navStartTime = 0;
            } catch (Exception e) {
                log("checkNavTimeout error: " + e.getMessage());
            }
        }
    }

    /**
     * Clear navigation target.
     * Reset fe_0.as = -1, z.ap = false, z.b = null.
     * Gọi khi đã đến đúng map để game không cố navigate nữa.
     */
    private void clearNavTarget() {
        try {
            Object zInstance = getZ();
            // Reset fe_0 auto-nav
            if (fe0FieldAs != null) {
                fe0FieldAs.setInt(null, -1);
            }
            if (zFieldAp != null) {
                zFieldAp.setBoolean(zInstance, false);
            }
            // Clear bi_0 target
            if (zFieldNavTarget != null) {
                zFieldNavTarget.set(zInstance, null);
            }
        } catch (Exception e) {
            log("clearNavTarget error: " + e.getMessage());
        }
    }

    /**
     * Gửi packet mở NPC (CMD 54).
     * new fm(54) → writeShort(npcId) → send()
     */
    private void sendOpenNpc(int npcId) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 54);
        Method writeShort = fmClass.getDeclaredMethod("t", int.class);
        writeShort.setAccessible(true);
        writeShort.invoke(packet, npcId);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * Gửi packet chọn menu NPC (CMD 53) - format 2 bytes (top-level click, no sub-menu).
     * new fm(53) → writeShort(npcId) → writeByte(menuIndex) → send()
     */
    private void sendSelectMenu(int npcId, int menuIndex) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 53);
        Method writeShort = fmClass.getDeclaredMethod("t", int.class);
        writeShort.setAccessible(true);
        writeShort.invoke(packet, npcId);
        Method writeByte = fmClass.getDeclaredMethod("s", int.class);
        writeByte.setAccessible(true);
        writeByte.invoke(packet, menuIndex);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * Gửi packet chọn sub-menu NPC (CMD 53) - format 3 bytes.
     * Khi menu item có dấu ',' (sub-options), client gửi CMD 53 với:
     * writeShort(npcId) → writeByte(parentMenuIndex) → writeByte(subOptionIndex) → send()
     */
    private void sendSelectMenuWithSub(int npcId, int parentIndex, int subIndex) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 53);
        Method writeShort = fmClass.getDeclaredMethod("t", int.class);
        writeShort.setAccessible(true);
        writeShort.invoke(packet, npcId);
        Method writeByte = fmClass.getDeclaredMethod("s", int.class);
        writeByte.setAccessible(true);
        writeByte.invoke(packet, parentIndex);
        writeByte.invoke(packet, subIndex);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * Gửi packet chọn menu phụ NPC (CMD 5).
     * new fm(5) → writeByte(menuIndex) → send()
     */
    private void sendSelectSubMenu(int menuIndex) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 5);
        Method writeByte = fmClass.getDeclaredMethod("s", int.class);
        writeByte.setAccessible(true);
        writeByte.invoke(packet, menuIndex);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    // ── GAME KHOÁ 15s GIỮA HAI LẦN ĐỔI KHU ──────────────────────────────────────────────────
    // Đây là LUẬT CỦA GAME (người dùng xác nhận 30/07), không phải của một hoạt động nào — nên
    // một con số dùng chung, và chốt đặt ở đúng chỗ gửi packet chứ không rải ở từng máy.
    //
    // Hậu quả khi chưa biết luật này: mọi máy đều để nhịp chờ 2.5s và 3 lần thử, tức kết luận
    // "đổi khu không ăn" ở giây thứ 7.5 — LÚC KHOÁ VẪN CÒN. Nó bèn tiến con trỏ khu rồi gửi lại,
    // cũng trong khoá, cũng không ăn. Vòng đó lặp tới hết giờ: nhân vật không hề đổi khu lần nào
    // mà log lại đầy dòng "thu khu N", và câu chốt "khu nao cung day nhom" là KẾT LUẬN SAI —
    // chưa từng vào khu nào để mà biết nó đầy.
    private long zoneChangeAt = 0;

    /** Còn bao nhiêu ms nữa mới được đổi khu. 0 = gửi được ngay. */
    private long zoneCooldownLeft(long now) {
        int cd = getSettingInt("zone_cooldown_ms", 15000);
        if (cd <= 0 || zoneChangeAt == 0) return 0;
        long troi = now - zoneChangeAt;
        return (troi >= cd) ? 0 : (cd - troi);
    }

    /**
     * Khu kế tiếp cần thử khi khu đang đứng không dùng được (đầy nhóm, hoặc đầy người nên member
     * không chen vào được).
     *
     * QUÉT TỪ KHU CAO NHẤT XUỐNG (`zone_scan_desc,1`) — theo quan sát của người dùng 30/07: khu
     * cuối dãy thường vắng, ít bị đầy nhóm và cũng ít bị đầy người. Quét lên từ khu đang đứng là
     * đi ngược lại: khu thấp là khu đông nhất, tức mỗi lượt nhảy 15s lại rơi vào một khu gần như
     * chắc chắn cũng đầy.
     *
     * KHÔNG dùng ngẫu nhiên: log phải tái hiện được. Cái giá của tuần tự là 12 nick cùng xuất phát
     * một khu sẽ nhảy đồng loạt và tự chen nhau — nhưng chuyện đó chỉ xảy ra ở Địa cung (mỗi nick
     * đi một mình); Cấm thuật và Sơn cáp chỉ có trưởng nhóm nhảy.
     *
     * @param cursor  khu đang nhắm; < 1 = chưa nhắm khu nào (lần nhảy đầu)
     * @param nowZone khu đang đứng — để lần đầu không "đổi" sang chính khu mình đang ở, việc đó
     *                tốn trọn một lượt khoá 15s mà chẳng đi đâu
     * @param maxZone số khu lớn nhất của hoạt động gọi tới
     */
    /** Cận dưới của dãy khu được phép quét — `zone_min`. Mặc định 1 = quét từ khu đầu. */
    private int zoneMin() {
        int m = getSettingInt("zone_min", 1);
        return (m < 1) ? 1 : m;
    }

    /** Số khu trong dãy quét — dùng để suy ra số lượt thử khi config để 0. */
    private int soKhuTrongDay(int maxZone) {
        if (maxZone <= 0) maxZone = 30;
        int min = zoneMin();
        return (maxZone < min) ? 1 : (maxZone - min + 1);
    }

    private int nextZoneToTry(int cursor, int nowZone, int maxZone) {
        if (maxZone <= 0) maxZone = 30;
        int min = zoneMin();
        if (min > maxZone) min = maxZone;          // khai lộn ngược thì thu về một khu, đừng treo
        boolean xuong = getSettingInt("zone_scan_desc", 1) == 1;

        // Con trỏ nằm ngoài dãy (lần nhảy đầu, hoặc vừa đổi cấu hình) ⇒ bắt đầu lại từ đầu dãy.
        if (cursor < min || cursor > maxZone) {
            int dau = xuong ? maxZone : min;
            // Đang đứng sẵn ở khu đầu dãy thì bỏ qua nó — "đổi khu" sang chính khu đang đứng là
            // tốn trọn một lượt khoá 15 giây mà không đi đâu cả.
            if (dau == nowZone && maxZone > min) dau = xuong ? maxZone - 1 : min + 1;
            return dau;
        }
        if (xuong) return (cursor <= min) ? maxZone : cursor - 1;
        return (cursor >= maxZone) ? min : cursor + 1;
    }

    /**
     * Gửi packet CMD -7 để đổi khu.
     * Server: case -7 → client.mChar.zone.changeZone(client, msg.readByte())
     *
     * @return 0 nếu đã gửi; > 0 = CHƯA GỬI, còn bấy nhiêu ms khoá.
     *         Chốt nằm ở đây để cả 11 chỗ gọi đều được che, kể cả chỗ bỏ qua giá trị trả về:
     *         bỏ qua thì cùng lắm là không gửi, còn hơn gửi vào chỗ chắc chắn bị game bỏ.
     */
    private long sendChangeZone(int zoneId) throws Exception {
        long now = System.currentTimeMillis();
        long con = zoneCooldownLeft(now);
        if (con > 0) {
            log("Doi khu: game khoa " + (getSettingInt("zone_cooldown_ms", 15000) / 1000)
                    + "s giua hai lan doi -> con " + (con / 1000 + 1) + "s moi gui duoc"
                    + " (dinh sang khu " + zoneId + ")");
            return con;
        }
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) -7);
        Method writeByte = fmClass.getDeclaredMethod("s", int.class);
        writeByte.setAccessible(true);
        writeByte.invoke(packet, zoneId);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
        zoneChangeAt = now;
        return 0;
    }

    /**
     * Đổi khu NGAY LẬP TỨC, không phụ thuộc state hiện tại.
     * Gọi từ lệnh change_zone_now của Manager (nút "Đổi khu").
     *
     * Khác setAfkConfig: hàm kia chỉ LƯU cấu hình, việc đổi khu thật phải chờ nhân vật
     * vào state AFK_FARM (tức auto NV bật + đã hết nhiệm vụ). Hàm này gửi packet luôn.
     */
    public void changeZoneNow(int zoneId) {
        try {
            if (!reflectionReady) initReflection();
            // BÁO THẬT KHI KHÔNG GỬI ĐƯỢC. Nút bấm tay mà log ghi "đã đổi" trong khi packet
            // chưa hề đi là kiểu hỏng tệ nhất: người bấm tin là xong rồi đi làm việc khác.
            // Và cờ afkZoneChanged đặt oan còn chặn luôn cả nhánh đổi khu của AFK_FARM.
            long khoa = sendChangeZone(zoneId);
            if (khoa > 0) {
                log("Doi khu NGAY: CHUA GUI DUOC - game con khoa " + (khoa / 1000 + 1)
                        + "s nua (dinh sang khu " + zoneId + "). Bam lai sau chung do giay.");
                return;
            }
            // Trùng khu đã cấu hình → đánh dấu đã đổi để AFK_FARM khỏi gửi lại lần nữa
            if (zoneId == afkZone) afkZoneChanged = true;
            log("Doi khu NGAY -> khu " + zoneId);
        } catch (Exception e) {
            log("changeZoneNow error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NHÓM / TỔ ĐỘI
    // ═══════════════════════════════════════════════════════════════

    /**
     * Đối tượng nhóm phía client (a.em). Client khởi tạo ngay trong constructor của z nên
     * field không null; "chưa có nhóm" thể hiện bằng em.q() == true (danh sách thành viên rỗng).
     */
    private Object getGroupObj() throws Exception {
        if (zFieldGroup == null || zGetInstance == null) return null;
        Object zInst = getZ();
        if (zInst == null) return null;
        return zFieldGroup.get(zInst);
    }

    /** CHƯA ở trong nhóm nào (em.r rỗng). */
    private boolean hasNoGroup(Object group) throws Exception {
        return group == null || Boolean.TRUE.equals(emMethodQ.invoke(group));
    }

    /** Mình có phải trưởng nhóm không — em.p() so r[0].j với tên nhân vật của mình. */
    private boolean isGroupLeader(Object group) throws Exception {
        return group != null && Boolean.TRUE.equals(emMethodP.invoke(group));
    }

    /** Tên thành viên theo đúng thứ tự server gửi; phần tử 0 LÀ TRƯỞNG NHÓM. */
    @SuppressWarnings("unchecked")
    private java.util.List<String> getGroupMemberNames(Object group) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<String>();
        if (group == null || emFieldR == null) return names;
        java.util.Vector<Object> members = (java.util.Vector<Object>) emFieldR.get(group);
        if (members == null) return names;
        for (Object m : members) {
            if (m == null) continue;
            // bQ.j = tên nhân vật. Đọc field trên chính class của instance để khỏi phụ thuộc
            // tên class lúc runtime (CFR đổi a.bQ → bq_0 khi decompile, runtime vẫn là a.bQ).
            Field fj = m.getClass().getDeclaredField("j");
            fj.setAccessible(true);
            Object v = fj.get(m);
            names.add(v != null ? v.toString() : "?");
        }
        return names;
    }

    /**
     * Gửi CMD 41 KHÔNG payload = "Lập nhóm" (tạo nhóm 1 người).
     * Đây đúng là việc client thật làm khi bấm nút lập nhóm: server đọc readUTF() gặp payload
     * rỗng nên nhảy vào nhánh catch → newGroup(). Không phải mẹo, là đường thiết kế của game.
     */
    private void sendCreateGroup() throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 41);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /** Gửi CMD 44 = rời nhóm (không payload). */
    private void sendLeaveGroup() throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 44);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * Gửi một lệnh nhóm có kèm TÊN NHÂN VẬT.
     * Toàn bộ giao thức nhóm chạy bằng tên (writeUTF), KHÔNG có id nhân vật: client chỉ
     * nhận được tên trong CMD 43, và server tra người nhận bằng tên.
     */
    private void sendGroupCmdWithName(int cmd, String name) throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) cmd);
        fmWriteUTF.invoke(packet, name);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * CMD 41 + tên = MỜI người đó vào nhóm.
     * Ràng buộc của server: chỉ tìm đối phương trong ĐÚNG KHU của mình
     * (zone.findCharInMap). Khác khu thì lệnh rơi vào hư không, không có báo lỗi.
     */
    private void sendInviteByName(String name) throws Exception {
        sendGroupCmdWithName(41, name);
    }

    /**
     * CMD 39 + tên = XIN VÀO NHÓM của người đó — và đây cũng chính là gói mà client
     * thật gửi khi bấm "Đồng ý" trên lời mời. Server tra người này trên TOÀN SERVER
     * (PlayerManager.getChar) chứ không giới hạn khu:
     *   - nhóm KHÔNG khoá → addMember ngay, trưởng nhóm không phải duyệt;
     *   - nhóm ĐANG khoá  → chuyển thành lời xin, trưởng nhóm phải bấm duyệt.
     * Vì vậy đường vào nhóm rẻ nhất là: trưởng mở khoá, member tự gửi CMD 39.
     */
    private void sendJoinByName(String leaderName) throws Exception {
        sendGroupCmdWithName(39, leaderName);
    }

    /**
     * CMD 47 + tên = ĐUỔI người đó khỏi nhóm. Server chỉ nghe khi người gửi là trưởng nhóm.
     * Client thật cũng gửi đúng gói này (al_0 case 57), lấy tên từ chính bQ.j của thành viên.
     */
    private void sendKickByName(String name) throws Exception {
        sendGroupCmdWithName(47, name);
    }

    /** CMD 42 = ĐẢO trạng thái khoá nhóm (server toggle, không nhận tham số). */
    private void sendToggleGroupLock() throws Exception {
        Object packet = fmClass.getConstructor(byte.class).newInstance((byte) 42);
        Method send = fmClass.getDeclaredMethod("aG");
        send.setAccessible(true);
        send.invoke(packet);
    }

    /**
     * Bật/tắt ô "tự cho vào nhóm" (z.ah). Đây là cơ chế của CHÍNH GAME, không phải mẹo:
     * khi bật, client của trưởng nhóm nhận lời xin gia nhập sẽ tự trả CMD 41 chấp thuận
     * ngay trong k.java case 39, không hiện popup và không cần bấm tay.
     * @return giá trị CŨ, để trả lại nguyên trạng khi xong việc
     */
    private boolean setAutoAcceptGroup(boolean on) throws Exception {
        if (zFieldAh == null) return false;
        Object zInst = getZ();
        if (zInst == null) return false;
        boolean prev = zFieldAh.getBoolean(zInst);
        zFieldAh.setBoolean(zInst, on);
        return prev;
    }

    private boolean isAutoAcceptGroup() {
        try {
            if (zFieldAh == null) return false;
            Object zInst = getZ();
            return zInst != null && zFieldAh.getBoolean(zInst);
        } catch (Exception e) {
            return false;
        }
    }

    /** Nhóm đang khoá (không cho tự vào) — em.h, chính là byte đầu của CMD 43. */
    private boolean isGroupLocked(Object group) throws Exception {
        return group != null && emFieldH != null && emFieldH.getBoolean(group);
    }

    /** Trong nhóm hiện tại có ai tên đúng như vậy không (so khớp đầy đủ, bỏ khoảng trắng thừa). */
    private boolean groupHasMember(Object group, String name) throws Exception {
        if (group == null || name == null) return false;
        for (String n : getGroupMemberNames(group)) {
            if (n != null && n.trim().equals(name.trim())) return true;
        }
        return false;
    }

    /** Mô tả trạng thái nhóm hiện tại, dùng cho log và hiển thị trên Manager. */
    public String getGroupStatusText() {
        try {
            if (!reflectionReady) initReflection();
            if (zFieldGroup == null) return "chua map duoc field nhom";
            Object g = getGroupObj();
            String where = " (khu " + getCurrentZoneId() + ")";
            if (hasNoGroup(g)) return "chua co nhom" + where;
            java.util.List<String> names = getGroupMemberNames(g);
            String lock = "";
            if (emFieldH != null && emFieldH.getBoolean(g)) lock = " [dang khoa]";
            return (isGroupLeader(g) ? "TRUONG NHOM" : "thanh vien")
                    + " - " + names.size() + " nguoi: " + names + lock + where;
        } catch (Exception e) {
            return "loi doc nhom: " + e.getMessage();
        }
    }

    /** Map hiện tại, -1 nếu chưa đọc được. Bản không ném của getCurrentMapId(). */
    public int getMapAnToan() {
        try { return getCurrentMapId(); } catch (Exception e) { return -1; }
    }

    /** KHU hiện tại đang đứng (z.v). -1 nếu chưa đọc được. */
    public int getCurrentZoneId() {
        try {
            if (zFieldV == null) return -1;
            Object zInst = getZ();
            if (zInst == null) return -1;
            return zFieldV.getShort(zInst);
        } catch (Exception e) {
            return -1;
        }
    }


    private Field frFieldTplId;   // a.fr.ah → short (MÃ BẢN MẪU của NPC), nạp lười

    /**
     * TRA NPC — đường dùng chung của MỌI hoạt động (Địa cung · Cấm thuật · Sơn cáp · Ải gia tộc).
     *
     * Hai nấc, theo đúng thứ tự này:
     *   1. THEO TÊN  — đường chính. Tên hiển thị gần như không đổi giữa các bản server.
     *   2. THEO MÃ BẢN MẪU — đường lui. Tên là chuỗi tiếng Việt có dấu nên so chuỗi có thể hụt
     *      vì đặt dấu hay hoa thường; số thì khớp là khớp chính xác.
     *
     * Vì sao mã bản mẫu KHÔNG làm đường chính: số đó lấy từ SQL của bản server MẪU, server thật
     * có thể đánh số khác. Sai số ở đây là mở nhầm NPC.
     *
     * Có lý do để gom về một hàm: tra NPC sống ĐÃ TỪNG HỤT trong lượt chạy thật (log Cấm thuật
     * 28/07 — cùng lúc, cùng khu, member tra ra còn trưởng nhóm tra hụt). Đường lui vá đúng chỗ
     * đó, nên mọi hoạt động đều cần, không riêng hoạt động viết sau cùng.
     *
     * @param tplId 0 = không có số để lui, chỉ tra theo tên
     * @return {mã cá thể, x, y} hoặc null
     */
    private int[] findNpc(String name, int tplId) {
        int[] r = findNpcByName(name);
        if (r != null) return r;
        if (tplId > 0) {
            r = findNpcByTemplateId(tplId);
            if (r != null) {
                log("Tra NPC '" + name + "' theo TEN truot -> tra duoc theo MA BAN MAU " + tplId
                        + " (ma ca the " + r[0] + " tai " + r[1] + "," + r[2] + ")");
            }
        }
        return r;
    }

    /**
     * Tìm NPC trên map hiện tại theo MÃ BẢN MẪU — số ghi trong SQL của server,
     * ví dụ 105 = Fukasaku 'Nhị đại hiền nhân', 32 = Onoki, 59 = Raikage.
     *
     * PHÂN BIỆT HAI CON SỐ, chỗ này từng gây nhầm:
     *   · MÃ BẢN MẪU (a.fr.ah) — cho biết ĐÂY LÀ NPC NÀO. Giống nhau ở mọi map, mọi bản chơi.
     *   · MÃ CÁ THỂ  (a.x.aZ) — số riêng của con NPC đó TRONG BẢN MAP hiện tại. Gói mở NPC
     *     (CMD 54) cần đúng số này, không phải số kia.
     * Hàm này nhận vào số thứ nhất và trả về số thứ hai.
     *
     * Dùng làm ĐƯỜNG LUI cho tra theo tên: tên là chuỗi tiếng Việt có dấu, so chuỗi thì dính
     * bẫy đặt dấu và hoa thường; mã bản mẫu là số nguyên, khớp thì khớp chính xác.
     * Nhưng KHÔNG dùng làm đường chính — số trong SQL là của bản server mẫu, server thật có thể
     * đánh số khác, mà tên hiển thị thì gần như không đổi.
     *
     * @return {realNpcId, x, y} hoặc null nếu không thấy
     */
    @SuppressWarnings("unchecked")
    private int[] findNpcByTemplateId(int tplId) {
        if (!reflectionReady || zFieldF == null || frClass == null || tplId <= 0) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<Object> npcVector = (java.util.Vector<Object>) zFieldF.get(zInst);
            if (npcVector == null || npcVector.isEmpty()) return null;

            if (frFieldTplId == null) {
                for (Field f : frClass.getDeclaredFields()) {
                    if (f.getName().equals("ah") && f.getType() == short.class) {
                        f.setAccessible(true); frFieldTplId = f; break;
                    }
                }
            }
            if (frFieldTplId == null) return null;

            for (Object npc : npcVector) {
                if (npc == null || !frClass.isInstance(npc)) continue;
                if (frFieldTplId.getShort(npc) != tplId) continue;
                return new int[]{ frFieldAZ.getInt(npc), frFieldAr.getShort(npc), frFieldAs.getShort(npc) };
            }
        } catch (Exception e) {
            log("findNpcByTemplateId error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Tìm NPC trên map hiện tại theo TÊN (khớp một phần, không phân biệt hoa thường).
     * Tìm theo tên thay vì template id để không phụ thuộc id của bản server mẫu.
     * @return {realNpcId, x, y} hoặc null nếu không thấy
     */
    @SuppressWarnings("unchecked")
    private int[] findNpcByName(String nameKeyword) {
        return findNpcByName(nameKeyword, null);
    }

    private int[] findNpcByName(String nameKeyword, java.util.Set<Integer> ignoredIds) {
        if (!reflectionReady || zFieldF == null || frClass == null || fsClass == null) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<Object> npcVector = (java.util.Vector<Object>) zFieldF.get(zInst);
            if (npcVector == null || npcVector.isEmpty()) return null;

            Method frGetTemplate = null;
            for (Method m : frClass.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0
                        && m.getReturnType() == fsClass) {
                    frGetTemplate = m;
                    frGetTemplate.setAccessible(true);
                    break;
                }
            }
            if (frGetTemplate == null || fsFieldL == null) return null;

            String kw = nameKeyword.toLowerCase();
            for (Object npc : npcVector) {
                if (npc == null || !frClass.isInstance(npc)) continue;
                int npcId = frFieldAZ.getInt(npc);
                if (ignoredIds != null && ignoredIds.contains(Integer.valueOf(npcId))) continue;
                Object template = frGetTemplate.invoke(npc);
                if (template == null) continue;
                Object nameObj = fsFieldL.get(template);
                if (!(nameObj instanceof String)) continue;
                if (!((String) nameObj).toLowerCase().contains(kw)) continue;
                return new int[]{ npcId, frFieldAr.getShort(npc), frFieldAs.getShort(npc) };
            }
        } catch (Exception e) {
            log("findNpcByName error: " + e.getMessage());
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    // ĐỊA CUNG — một máy trạng thái chạy trọn hoạt động
    // ══════════════════════════════════════════════════════════════
    // Chạy độc lập với Auto NV vì mọi bước đều phải chờ server:
    // CMD 43 trả về sau khi lập nhóm, dialog NPC về sau khi mở, v.v.
    // Thứ tự quan trọng: VỀ MAP TRƯỚC rồi mới lập nhóm. Lập nhóm ở map đang đứng là vô ích
    // vì đi sang map khác thì khu đổi hết, và nếu phải nhảy khu thì cũng nhảy nhầm map.
    private static final int DC_GOTO_MAP = 1;   // về map có NPC
    private static final int DC_GROUP    = 2;   // bảo đảm là trưởng của một nhóm
    private static final int DC_FIND_NPC = 3;   // tìm NPC + đi tới
    private static final int DC_OPEN_NPC = 4;   // gửi CMD 54
    private static final int DC_GET_KEY  = 5;   // chờ dialog + chọn mục nhận chìa
    private static final int DC_REOPEN_NPC = 6; // đóng dialog sót + mở lại NPC
    private static final int DC_ENTER    = 7;   // chọn hầm theo tier
    private static final int DC_VERIFY   = 8;   // xác nhận đã vào (mapId đổi)
    private static final int DC_IN_DUNGEON = 9; // đang trong hầm, chờ ra rồi bàn giao AFK

    private int dcStep = 0;           // 0 = tắt
    private long dcNextTime = 0;
    private long dcDeadline = 0;
    private int dcNpcId = -1;
    private int dcWalkTries = 0;      // số lần đã đi tới toạ độ dự phòng trong config
    private int dcKickTries = 0;      // số lần đã đuổi người thừa khỏi nhóm để đi Địa cung một mình
    private String tierPicked = "";   // mục hầm đã bấm, để câu báo lỗi nêu đích danh
    private boolean dcLockSent = false; // đã gửi CMD 42 khoá nhóm chưa (lệnh ĐẢO, chỉ gửi một lần)
    private int dcGroupSentZone = -1; // khu đã gửi CMD 41 và đang chờ kết quả
    private int dcZoneCursor = -1;    // khu đang nhắm tới khi phải nhảy khu
    private int dcZoneHops = 0;       // số lần đã nhảy khu vì khu đầy nhóm
    private boolean dcZonePending = false; // đã gửi lệnh đổi khu, đang chờ tới nơi
    private int dcZoneWaits = 0;      // số vòng đã chờ đổi khu mà chưa tới
    private int dcTier = 0;           // hầm cần vào (1..4); 0 = lấy từ config
    private boolean dcSkipKey = false; // hôm nay đã nhận chìa rồi (Manager báo sang)
    private int dcMapBefore = -1;     // map ngay trước khi bấm vào hầm, để so sánh
    private int dcVerifyWaits = 0;    // số vòng đã chờ map đổi
    private int dcDungeonMap = -1;    // map của hầm đang ở trong; rời map này = hầm kết thúc

    /**
     * Bắt đầu hoạt động Địa cung. Kết quả đẩy về Manager bất đồng bộ.
     * @param tier    hầm cần vào 1..4 (sơ/trung/cao/thượng cấp); 0 = lấy từ config
     * @param skipKey hôm nay đã nhận chìa rồi thì bỏ qua bước nhận, vào thẳng hầm
     */
    public String startDiaCung(int tier, boolean skipKey) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        if (zFieldGroup == null || emMethodQ == null || emMethodP == null) {
            return "LOI: chua map duoc doi tuong nhom (a.em) - xem log reflection";
        }
        stopCurrentActivity();
        dcStep = DC_GOTO_MAP;
        dcNextTime = 0;
        dcNpcId = -1;
        dcWalkTries = 0;
        dcKickTries = 0;
        dcLockSent = false;
        dcGroupSentZone = -1;
        dcZoneCursor = -1;
        dcZoneHops = 0;
        dcZonePending = false;
        dcZoneWaits = 0;
        dcTier = tier;
        dcSkipKey = skipKey;
        dcMapBefore = -1;
        dcVerifyWaits = 0;
        dcDungeonMap = -1;
        dcDeadline = System.currentTimeMillis() + getSettingInt("dia_cung_timeout_ms", 120000);
        String mapTxt = "?";
        try { mapTxt = String.valueOf(getCurrentMapId()); } catch (Exception ignore) {}
        log("Dia cung: bat dau (map " + mapTxt + ", khu " + getCurrentZoneId() + ")");
        return "da bat dau Dia cung";
    }

    /** Đẩy một mốc tiến trình về Manager để theo dõi, không kết thúc luồng. */
    private void sendDiaCungProgress(String detail) {
        log("Dia cung: " + detail);
        pushDiaCung("dia_cung_progress", false, detail);
    }

    /** Kết thúc hoạt động và đẩy kết quả về Manager. */
    private void finishDiaCung(boolean ok, String detail) {
        dcStep = 0;
        // GIẢI TÁN NHÓM khi xong. Địa cung đi một mình nên nhóm này không còn việc gì; để lại
        // thì lượt hoạt động sau phải mất công dọn. Quan trọng nhất là chiều Địa cung → Cấm
        // thuật: Cấm thuật lập nhóm từ đầu, gặp nick còn dính nhóm cũ (lại đang KHOÁ) là phải
        // rời ra rồi mới lập được, chậm và dễ hỏng. Rời sạch ở đây thì lượt sau vào thẳng luồng.
        if (getSettingInt("dia_cung_leave_group_after", 1) == 1) {
            try {
                Object g = getGroupObj();
                if (!hasNoGroup(g)) {
                    sendLeaveGroup();
                    log("Dia cung: ket thuc -> gui CMD 44 roi nhom cho sach");
                }
            } catch (Exception e) {
                log("Dia cung: khong roi duoc nhom: " + e.getMessage());
            }
        }
        // Kết thúc kiểu gì cũng không để nhân vật đứng lại giữa làng. Trước đây chỉ đường THÀNH
        // CÔNG mới bàn giao cho treo map, còn 6 đường hỏng (không thấy NPC, khu nào cũng đầy
        // nhóm, hết giờ, bấm mà map không đổi...) thì bỏ mặc — nick đứng chôn chân ở làng cho
        // tới khi có người để ý.
        diaCungHandoffAfk();
        log("Dia cung: " + (ok ? "XONG - " : "THAT BAI - ") + detail);
        pushDiaCung("dia_cung", ok, detail);
    }

    private void pushDiaCung(String type, boolean ok, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushDiaCung error: " + e.getMessage());
        }
    }

    /**
     * Hạn giờ khi ĐANG Ở TRONG HẦM Địa cung. Mặc định KHÔNG giới hạn, giống Cấm thuật và cùng
     * một lý do: xong hoặc hết giờ là game tự đẩy ra ngoài, nên không thể kẹt lại trong hầm.
     * Đặt thêm một mốc của tool chỉ tạo ra rủi ro cắt oan một lượt đang chạy bình thường.
     * Chỉ bật (giá trị > 0) nếu có lúc nào đó thấy nhân vật thật sự đứng lì trong hầm.
     */
    private long diaCungDungeonDeadline(long now) {
        int ms = getSettingInt("dia_cung_run_timeout_ms", 0);
        return ms > 0 ? now + ms : Long.MAX_VALUE;
    }

    /**
     * Bàn giao cho treo map AFK sau khi hoạt động Địa cung kết thúc. Không làm gì nếu đã ở sẵn
     * trạng thái đó (đường thành công tự chuyển trước khi gọi finish) hoặc chưa cấu hình map treo.
     */
    private void diaCungHandoffAfk() {
        try {
            if (getSettingInt("dia_cung_after_afk", 1) != 1) return;
            if (afkMapId <= 0) return;
            if (state == TaskState.AFK_FARM) return;
            setAutoCombat(false);
            afkZoneChanged = false;
            autoCombatRequested = false;
            setEnabled(true);
            setState(TaskState.AFK_FARM);
            log("Dia cung: ket thuc -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
        } catch (Exception e) {
            log("diaCungHandoffAfk error: " + e.getMessage());
        }
    }

    private void tickDiaCung(long now) {
        try {
            if (now > dcDeadline) {
                finishDiaCung(false, "het gio o buoc " + dcStep);
                return;
            }
            if (now < dcNextTime) return;

            // Mọi mốc thời gian đều lấy từ quest_anchors.cfg, không nhúng số cứng trong code.
            final int groupWait = getSettingInt("dia_cung_group_wait_ms", 1500);
            final int zoneWait  = getSettingInt("dia_cung_zone_wait_ms", 2500);
            final int mapWait   = getSettingInt("dia_cung_map_wait_ms", 2500);
            final int walkWait  = getSettingInt("dia_cung_walk_wait_ms", 1500);
            final int pollMs    = getSettingInt("dia_cung_poll_ms", 3000);
            final int stepMs    = getSettingInt("dia_cung_step_ms", 200);
            final int npcWait   = getSettingInt("dia_cung_npc_wait_ms", 600);
            final int dlgPoll   = getSettingInt("dia_cung_dialog_poll_ms", 300);
            final int enterWait = getSettingInt("dia_cung_enter_wait_ms", 1500);

            // Bước 2: bảo đảm đang là TRƯỞNG của một nhóm (nhóm 1 người là đủ).
            // Chạy SAU khi đã về đúng map — lập nhóm ở map cũ rồi đi map khác là phí công.
            if (dcStep == DC_GROUP) {
                Object g = getGroupObj();

                if (!hasNoGroup(g) && isGroupLeader(g)) {
                    java.util.List<String> names = getGroupMemberNames(g);

                    // Là trưởng nhóm nhưng nhóm CÒN NGƯỜI KHÁC — xảy ra thật khi chạy Địa cung
                    // ngay sau Cấm thuật: nhóm 4 người vẫn còn nguyên và đang khoá. Địa cung chỉ
                    // cần nhóm một người, mà cú vào hầm của trưởng nhóm rất có thể KÉO THEO
                    // người cùng khu như bên Cấm thuật ⇒ member bị lôi vào hầm của người khác
                    // giữa lúc đang chạy lượt của chính mình, và có thể mất luôn lượt trong ngày.
                    // Chưa kiểm chứng được là có kéo hay không, nên dọn sạch nhóm trước cho chắc.
                    if (names.size() > 1 && getSettingInt("dia_cung_solo_group", 1) == 1) {
                        int maxKick = getSettingInt("dia_cung_kick_tries", 3);
                        if (dcKickTries < maxKick) {
                            dcKickTries++;
                            for (int i = 1; i < names.size(); i++) sendKickByName(names.get(i));
                            sendDiaCungProgress("nhom con " + (names.size() - 1)
                                    + " nguoi khac -> duoi ra de di mot minh (lan " + dcKickTries + ")");
                            dcNextTime = now + groupWait;
                            return;
                        }
                        // Không dọn được thì vẫn đi tiếp — chặn hẳn ở đây là làm hỏng một tính
                        // năng vốn chạy được. Nhưng phải kêu to để còn biết mà xử lý.
                        sendDiaCungProgress("CANH BAO: khong duoi het duoc nguoi trong nhom " + names
                                + " -> van vao ham, ho co the bi keo theo");
                    }

                    // Còn lại một mình thì KHOÁ nhóm lại rồi mới đi. Không khoá thì suốt quãng
                    // đi tới NPC và ở trong hầm vẫn có người chen vào được — công dọn nhóm ở
                    // trên thành vô nghĩa. CMD 42 là lệnh ĐẢO trạng thái nên chỉ gửi ĐÚNG MỘT
                    // LẦN; vòng sau đọc lại em.h để biết ăn chưa rồi đi tiếp dù thế nào.
                    if (names.size() <= 1 && getSettingInt("dia_cung_lock_group", 1) == 1
                            && !isGroupLocked(g) && !dcLockSent) {
                        dcLockSent = true;
                        sendToggleGroupLock();
                        log("Dia cung: nhom con mot minh -> gui CMD 42 khoa nhom");
                        dcNextTime = now + groupWait;
                        return;
                    }

                    sendDiaCungProgress("da la truong nhom (" + names.size() + " thanh vien) " + names
                            + (isGroupLocked(g) ? " [da khoa nhom]" : " [chua khoa duoc nhom]"));
                    dcStep = DC_FIND_NPC;
                    dcNextTime = now + stepMs;
                    return;
                }

                if (!hasNoGroup(g)) {
                    java.util.List<String> names = getGroupMemberNames(g);
                    sendLeaveGroup();
                    log("Dia cung: dang o nhom cua " + (names.isEmpty() ? "?" : names.get(0))
                            + " -> gui CMD 44 roi nhom");
                    dcNextTime = now + groupWait;
                    return;
                }

                int zone = getCurrentZoneId();

                // Đang chờ đổi khu: phải xác nhận ĐÃ TỚI khu đích rồi mới lập nhóm tiếp.
                // Log thực tế cho thấy lệnh đổi khu có thể không ăn (khu đích đông người),
                // nếu cứ gửi CMD 41 ngay thì lại lập nhóm ở đúng khu đầy vừa rời.
                if (dcZonePending) {
                    long khoa = zoneCooldownLeft(now);
                    if (zone == dcZoneCursor) {
                        dcZonePending = false;
                        dcZoneWaits = 0;
                    } else if (khoa > 0) {
                        // CÒN KHOÁ THÌ "vẫn ở khu cũ" LÀ ĐÚNG, không phải lệnh không ăn.
                        // KHÔNG tăng dcZoneWaits ở đây: đếm trong lúc khoá là kết luận hỏng khi
                        // chưa có gì hỏng, rồi gửi lại vào giữa khoá — vừa sai vừa vô ích.
                        dcNextTime = now + khoa + 250;
                        return;
                    } else if (++dcZoneWaits > getSettingInt("dia_cung_zone_wait_tries", 3)) {
                        dcZoneCursor = nextZoneToTry(dcZoneCursor, zone,
                                getSettingInt("dia_cung_max_zone", 30));
                        sendChangeZone(dcZoneCursor);
                        dcZoneWaits = 0;
                        log("Dia cung: doi khu khong an (van o khu " + zone + ") -> thu khu "
                                + dcZoneCursor);
                        dcNextTime = now + zoneWait;
                        return;
                    } else {
                        dcNextTime = now + zoneWait;   // chờ thêm cho lệnh đổi khu kịp ăn
                        return;
                    }
                }

                // Đã gửi CMD 41 ngay tại khu này mà vẫn chưa có nhóm ⇒ khu đã đủ số nhóm.
                // Server chỉ hiện banner "Số nhóm trong khu vực đã đạt tối đa", không trả mã
                // lỗi nào đọc được, nên chỉ có thể suy ra bằng hành vi như thế này.
                if (dcGroupSentZone >= 0 && dcGroupSentZone == zone) {
                    int maxZone = getSettingInt("dia_cung_max_zone", 30);
                    // 0 = quét trọn một vòng: số lượt thử BẰNG số khu.
                    // Con trỏ khu tiến đúng 1 mỗi lượt và vòng lại ở maxZone, nên maxHop == maxZone
                    // là đi qua mỗi khu đúng một lần — không trùng, không sót. Buộc hai con số này
                    // dính nhau ở đây thay vì bắt người dùng ghi tay cả hai: sửa max_zone mà quên
                    // sửa số lượt thì thành quét thiếu (hoặc quét lặp) mà chẳng có gì báo.
                    int maxHop = getSettingInt("dia_cung_max_zone_hop", 0);
                    // Số lượt thử = SỐ KHU TRONG DÃY, không phải số khu lớn nhất. Từ khi có
                    // `zone_min` thì dãy quét là [zone_min, max] chứ không còn [1, max] — lấy
                    // maxZone làm số lượt là thừa ra đúng phần đầu dãy đã bị cắt.
                    if (maxHop <= 0) maxHop = soKhuTrongDay(maxZone);
                    if (dcZoneHops >= maxHop) {
                        finishDiaCung(false, "da thu " + dcZoneHops + "/" + maxHop
                                + " khu, khu nao cung day nhom");
                        return;
                    }
                    // Con trỏ khu luôn tiến, kể cả khi lần đổi khu trước thất bại câm
                    // (khu đích đông người) — nếu bám theo khu hiện tại sẽ lặp mãi một đích.
                    dcZoneCursor = nextZoneToTry(dcZoneCursor, zone, maxZone);
                    sendChangeZone(dcZoneCursor);
                    dcZoneHops++;
                    dcGroupSentZone = -1;
                    dcZonePending = true;    // chờ xác nhận tới nơi rồi mới lập nhóm
                    dcZoneWaits = 0;
                    sendDiaCungProgress("khu " + zone + " day nhom -> doi sang khu " + dcZoneCursor);
                    dcNextTime = now + zoneWait;
                    return;
                }

                sendCreateGroup();
                dcGroupSentZone = zone;
                log("Dia cung: chua co nhom -> gui CMD 41 lap nhom (khu " + zone + ")");
                dcNextTime = now + groupWait;
                return;
            }

            // Bước 1: về đúng map có NPC (mặc định 68 = Làng Cỏ)
            if (dcStep == DC_GOTO_MAP) {
                // Không khai dia_cung_map thì dùng luôn map của config "village" (nút Về làng),
                // tránh ghi trùng map ở hai chỗ rồi lệch nhau về sau.
                int wantMap = getSettingInt("dia_cung_map", 0);
                if (wantMap <= 0) {
                    loadAnchorConfig();
                    if (villageConfig != null) wantMap = villageConfig[0];
                }
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    navigateToMap(wantMap);
                    log("Dia cung: dang o map " + curMap + " -> di toi map " + wantMap);
                    dcNextTime = now + mapWait;   // đang đi xuyên map, lát kiểm lại
                    return;
                }
                dcStep = DC_GROUP;
                dcNextTime = now + stepMs;
                return;
            }

            // Bước 3: tìm NPC theo tên rồi đi tới. Không thấy thì lui về toạ độ trong config
            // và thử lại — vì NPC chỉ nằm trong z.F khi đã ở đúng map/khu và dữ liệu đã về.
            if (dcStep == DC_FIND_NPC) {
                String npcName = getSetting("dia_cung_npc", "Raikage");
                int curMap = getCurrentMapId();
                int[] npc = findNpc(npcName, getSettingInt("dia_cung_npc_id", 59));

                if (npc == null) {
                    int[] cfg = npcConfig.get("npc_dia_cung_" + curMap);
                    if (cfg != null && dcWalkTries < getSettingInt("dia_cung_walk_tries", 3)) {
                        dcWalkTries++;
                        navigateTo(curMap, cfg[1], cfg[2]);
                        log("Dia cung: chua thay NPC '" + npcName + "' -> di toi toa do config ("
                                + cfg[1] + "," + cfg[2] + ") lan " + dcWalkTries);
                        dcNextTime = now + walkWait;
                        return;
                    }
                    dumpAllNpcsOnMap();
                    finishDiaCung(false, "khong thay NPC '" + npcName + "' tren map " + curMap
                            + " - xem log client de biet ten NPC that");
                    return;
                }

                dcNpcId = npc[0];
                int dx = Math.abs(getPlayerX() - npc[1]);
                int dy = Math.abs(getPlayerY() - npc[2]);
                int range = getSettingInt("dia_cung_npc_range", 60);
                if (dx > range || dy > range) {
                    navigateTo(curMap, npc[1], npc[2]);
                    dcNextTime = now + walkWait;   // đang đi, lát nữa kiểm lại khoảng cách
                    return;
                }
                dcStep = DC_OPEN_NPC;
                dcNextTime = now + stepMs;
                return;
            }

            // Bước 4: mở NPC. Đóng dialog còn sót trước, nếu không bước sau sẽ đọc phải
            // menu của dialog cũ (dễ xảy ra khi chạy lại lần hai trong cùng phiên).
            if (dcStep == DC_OPEN_NPC) {
                closeAnyDialog();
                sendOpenNpc(dcNpcId);
                log("Dia cung: gui CMD 54 mo NPC id=" + dcNpcId);
                // Hôm nay đã nhận chìa rồi thì bỏ qua bước nhận, vào thẳng bước chọn hầm
                dcStep = dcSkipKey ? DC_ENTER : DC_GET_KEY;
                if (dcSkipKey) log("Dia cung: hom nay da nhan chia -> bo qua, vao thang chon ham");
                dcNextTime = now + npcWait;
                return;
            }

            // Bước 5: chờ dialog, đọc menu, chọn mục nhận chìa
            if (dcStep == DC_GET_KEY) {
                if (detectDialog() == null) { dcNextTime = now + dlgPoll; return; }
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) { dcNextTime = now + dlgPoll; return; }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < menu.length; i++) sb.append("  [").append(i).append("] ").append(menu[i]).append("\n");
                log("Dia cung: menu NPC:\n" + sb);

                // CHỌN THEO INDEX là chính. Thứ tự menu NPC 59 đã đối chiếu khớp giữa bản mẫu
                // và server thật, trong khi so text từng gãy vì dấu thanh ("khoá" vs "khóa").
                // Keyword chỉ còn là lưới an toàn khi index cấu hình nằm ngoài phạm vi menu.
                int idx = getSettingInt("dia_cung_key_index", 0);
                if (idx < 0 || idx >= menu.length) {
                    String kw = getSetting("dia_cung_key_menu", "chìa");
                    int byKw = findMenuIndexByKeyword(menu, kw);
                    if (byKw < 0) {
                        finishDiaCung(false, "index " + idx + " ngoai pham vi (menu co " + menu.length
                                + " muc) va khong thay keyword '" + kw + "' | menu: "
                                + java.util.Arrays.toString(menu));
                        return;
                    }
                    log("Dia cung: index " + idx + " ngoai pham vi -> dung keyword, ra index " + byKw);
                    idx = byKw;
                }
                if (getSubMenuCount(menu, idx) > 0) sendSelectMenuWithSub(dcNpcId, idx, 0);
                else sendSelectMenu(dcNpcId, idx);

                // Báo riêng để Manager đóng dấu "đã nhận chìa hôm nay". Tách khỏi kết quả cuối
                // vì bước vào hầm phía sau có thể hỏng mà chìa thì đã nhận rồi.
                pushDiaCung("dia_cung_key_claimed", true, "da bam nhan chia: [" + idx + "] " + menu[idx]);
                sendDiaCungProgress("da bam nhan chia [" + idx + "] " + menu[idx]);

                dcStep = DC_REOPEN_NPC;
                dcNextTime = now + npcWait;
                return;
            }

            // Bước 6: mở lại NPC để chọn hầm. Đóng dialog còn sót trước — nếu server đẩy
            // thông báo ("đã nhận rồi", "túi đầy"...) thì dialog cũ vẫn nằm trên stack và
            // bước sau sẽ đọc nhầm menu của nó.
            if (dcStep == DC_REOPEN_NPC) {
                closeAnyDialog();
                sendOpenNpc(dcNpcId);
                log("Dia cung: mo lai NPC de chon ham");
                dcStep = DC_ENTER;
                dcNextTime = now + npcWait;
                return;
            }

            // Bước 7: chọn hầm theo tier rồi ghi lại map hiện tại làm mốc so sánh
            if (dcStep == DC_ENTER) {
                if (detectDialog() == null) { dcNextTime = now + dlgPoll; return; }
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) { dcNextTime = now + dlgPoll; return; }

                // Menu NPC 59: index 0 = nhận chìa, 1..4 = sơ/trung/cao/thượng cấp
                // ⇒ index của hầm TRÙNG số tier, không cần bảng ánh xạ riêng.
                int tier = (dcTier > 0) ? dcTier : getSettingInt("dia_cung_tier", 1);
                if (tier < 1 || tier >= menu.length) {
                    finishDiaCung(false, "tier " + tier + " khong hop le (menu co " + menu.length
                            + " muc) | menu: " + java.util.Arrays.toString(menu));
                    return;
                }

                // In nguyên menu trước khi bấm. Bước này chọn THUẦN theo index, không đối chiếu
                // chữ — nên nếu server đổi thứ tự menu thì phải nhìn log mới truy ra được.
                // (Menu NPC 32 bên Cấm thuật đã lệch bản mẫu đúng kiểu này.)
                log("Dia cung: menu chon ham: " + java.util.Arrays.toString(menu));
                sendDiaCungProgress("menu chon ham: " + java.util.Arrays.toString(menu));

                // Chặn cú bấm tệ nhất: trùng vào mục NHẬN CHÌA. Nếu thứ tự menu xê dịch mà cứ
                // bấm theo index thì có thể bấm nhầm vào đó, vừa phí thao tác vừa đánh dấu sai.
                int keyIdx = getSettingInt("dia_cung_key_index", 0);
                if (tier == keyIdx) {
                    finishDiaCung(false, "tier " + tier + " trung voi muc nhan chia (index "
                            + keyIdx + ") -> khong bam | menu: " + java.util.Arrays.toString(menu));
                    return;
                }

                dcMapBefore = getCurrentMapId();
                tierPicked = tier + " " + menu[tier];
                if (getSubMenuCount(menu, tier) > 0) sendSelectMenuWithSub(dcNpcId, tier, 0);
                else sendSelectMenu(dcNpcId, tier);
                sendDiaCungProgress("chon ham [" + tier + "] " + menu[tier] + " (dang o map " + dcMapBefore + ")");

                dcStep = DC_VERIFY;
                dcNextTime = now + enterWait;
                return;
            }

            // Bước 8: xác nhận đã vào hầm — bằng chứng duy nhất đáng tin là MAP ĐỔI.
            // Nó bao trùm mọi thất bại phía trên: chưa có nhóm, chưa có chìa, hết lượt hôm nay.
            if (dcStep == DC_VERIFY) {
                int nowMap = getCurrentMapId();
                if (nowMap != dcMapBefore) {
                    dcDungeonMap = nowMap;
                    sendDiaCungProgress("da vao dia cung - map " + dcMapBefore + " -> " + nowMap);
                    // Không bật đánh thì nhân vật đứng không trong hầm, hầm chẳng bao giờ "kết thúc"
                    if (getSettingInt("dia_cung_combat", 1) == 1) {
                        // BẮT BUỘC xoá đích di chuyển trước. Vừa nãy đã gọi navigateToMap(lang)
                        // và navigateTo(NPC) nên z.ap còn bật và fe0.as vẫn trỏ về map làng —
                        // để nguyên thì nhân vật lo đi về làng chứ không đánh. tickAfkFarm cũng
                        // gọi clearNavTarget() ngay trước khi bật đánh, đúng lý do này.
                        clearNavTarget();
                        setAutoCombat(true);
                        autoCombatRequested = true;
                        log("Dia cung: xoa dich di chuyen + bat auto combat trong ham");
                    }
                    dcDeadline = diaCungDungeonDeadline(now);
                    dcStep = DC_IN_DUNGEON;
                    dcNextTime = now + pollMs;
                    return;
                }
                if (++dcVerifyWaits > getSettingInt("dia_cung_verify_tries", 6)) {
                    // TỰ CHỮA: lượt này bỏ qua bước nhận chìa vì Manager bảo "hôm nay bấm rồi",
                    // mà bấm vào hầm lại không vào được ⇒ dấu ngày đó không đáng tin (nó ghi
                    // "đã BẤM", không phải "server đã CẤP"). Xoá dấu để lượt sau nhận lại chìa,
                    // thay vì lặp lại đúng thất bại này mọi lần chạy trong ngày.
                    if (dcSkipKey) {
                        pushDiaCung("dia_cung_key_reset", true,
                                "bo qua nhan chia nhung khong vao duoc ham -> xoa dau ngay");
                    }
                    // Đọc NGUYÊN VĂN thông báo server đẩy lên thay vì đoán lý do. Trước đây câu
                    // "nhieu kha nang chua co chia hoac het luot" chỉ là suy đoán của tool, không
                    // dựa trên gì cả — mà server thì luôn nói rõ vì sao không vào được.
                    String why = readAnyDialogText();
                    closeAnyDialog();

                    // GỌI ĐÚNG TÊN TRẠNG THÁI. Bấm đúng mục, server nhận lệnh, map không đổi, và
                    // server KHÔNG nói gì (chỉ còn câu chào của NPC) — đo thật 05/08 trên nick đã
                    // đi hầm từ trước. Đây là cách server từ chối lượt thứ hai trong ngày: im lặng.
                    // Ghi thành "het luot" chứ không phải "that bai": tool không làm sai bước nào,
                    // và đọc log sáng hôm sau mà thấy ❌ là đi tìm lỗi không tồn tại.
                    //
                    // Vẫn để ok=false vì rốt cuộc KHÔNG vào được hầm — chỉ đổi cách gọi tên, không
                    // đổi kết luận. Server có nói lý do thật thì in nguyên văn, khỏi đoán.
                    boolean serverImLang = (why == null || why.trim().isEmpty()
                                            || noAccent(why).toLowerCase().contains("xin chao"));
                    finishDiaCung(false, (serverImLang
                                ? "khong vao duoc ham - nhieu kha nang DA DI HAM HOM NAY"
                                  + " (bam dung muc, server khong bao loi gi)"
                                : "khong vao duoc ham")
                            + " [muc " + tierPicked + ", van o map " + dcMapBefore + "]"
                            + (dcSkipKey ? " (lan nay da bo qua buoc nhan chia - da xoa dau ngay)" : "")
                            + (serverImLang ? "" : " | Server bao: " + why));
                    return;
                }
                dcNextTime = now + getSettingInt("dia_cung_verify_ms", 1000);
                return;
            }

            // Bước 9: đang trong hầm. Rời khỏi map hầm nghĩa là hoạt động đã kết thúc
            // (hết giờ hoặc hoàn thành) — lúc đó bàn giao cho AFK farm, không để đứng ở làng.
            if (dcStep == DC_IN_DUNGEON) {
                int nowMap = getCurrentMapId();
                if (nowMap == dcDungeonMap) {
                    // Vẫn trong hầm. Bật lại đánh nếu bị tắt giữa chừng (chết/hồi sinh, đổi
                    // trạng thái map...) — tickAfkFarm cũng tự bật lại theo chu kỳ như vậy.
                    if (getSettingInt("dia_cung_combat", 1) == 1 && !isAutoCombatOn()) {
                        clearNavTarget();
                        setAutoCombat(true);
                        log("Dia cung: auto combat bi tat -> bat lai");
                    }
                    dcNextTime = now + pollMs;
                    return;
                }

                setAutoCombat(false);
                autoCombatRequested = false;

                int homeMap = getSettingInt("dia_cung_map", 0);
                if (homeMap <= 0) {
                    loadAnchorConfig();
                    if (villageConfig != null) homeMap = villageConfig[0];
                }
                String where = (nowMap == homeMap)
                        ? ("da ve lang (map " + nowMap + ")")
                        : ("da ra khoi ham, dang o map " + nowMap);

                if (afkMapId > 0 && getSettingInt("dia_cung_after_afk", 1) == 1) {
                    // Bàn giao cho AFK_FARM sẵn có: nó tự đi tới map treo, đổi khu rồi bật đánh.
                    // Reset 2 cờ để nó chịu đổi khu và bật lại combat cho lượt mới.
                    afkZoneChanged = false;
                    autoCombatRequested = false;
                    setEnabled(true);
                    setState(TaskState.AFK_FARM);
                    finishDiaCung(true, where + " -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
                } else {
                    finishDiaCung(true, where + " (chua cau hinh map treo nen dung yen)");
                }
                return;
            }
        } catch (Exception e) {
            finishDiaCung(false, "loi: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CẤM THUẬT — GOM NHÓM
    // ══════════════════════════════════════════════════════════════
    //
    // Giao thức nhóm chạy hoàn toàn bằng TÊN NHÂN VẬT, không có id:
    //   CMD 41 rỗng   = lập nhóm
    //   CMD 41 + tên  = mời (server chỉ tìm trong ĐÚNG KHU của người mời)
    //   CMD 39 + tên  = xin/vào nhóm của người đó (server tra toàn server; nhóm không
    //                   khoá thì vào thẳng, không cần trưởng nhóm duyệt). Chính gói này
    //                   cũng là cái client gửi khi bấm "Đồng ý" trên lời mời.
    //   CMD 42        = đảo khoá nhóm
    //   CMD 43        = server trả danh sách {khoá, lớp, nvChar, level, TÊN}
    //
    // Vì vậy luồng rẻ nhất là: trưởng lập nhóm + mở khoá + báo khu → member sang khu đó
    // rồi tự gửi CMD 39. Lời mời (CMD 41) chỉ dùng làm ĐƯỜNG LUI khi CMD 39 không ăn —
    // server thật có thể khác bản mẫu nên giữ cả hai đường cho chắc.
    //
    // Vẫn phải đồng bộ khu dù CMD 39 không đòi, vì bản thân hoạt động cấm thuật kéo
    // người theo khu của trưởng nhóm (zone.getVecChar) — ai khác khu sẽ bị bỏ lại.

    private static final int CT_L_GOTO_MAP = 1;   // trưởng: về map làng
    private static final int CT_L_GROUP    = 2;   // trưởng: lập nhóm (nhảy khu nếu khu đầy nhóm)
    private static final int CT_L_UNLOCK   = 3;   // trưởng: mở khoá để member tự vào được
    private static final int CT_L_ANNOUNCE = 4;   // trưởng: báo map/khu/tên về Manager
    private static final int CT_L_WAIT     = 5;   // trưởng: chờ đủ member, mời lại người còn thiếu

    // ── Pha 2: VÀO HẦM ──────────────────────────────────────────────────────
    // Luật server quyết định toàn bộ thiết kế pha này (NPC_Action, NPCMenu 32 / TypeMenu 1):
    //   soLanCamThuat -= 1  chạy TRƯỚC mọi kiểm tra nhóm ⇒ bấm sai là MẤT LƯỢT mà không được gì.
    //   Vì vậy mọi điều kiện phải kiểm xong trước cú bấm, không dựa vào thông báo lỗi của server.
    //   Cú bấm của TRƯỞNG NHÓM kéo mọi thành viên CÙNG KHU vào hầm ⇒ member không được bấm,
    //   bấm là mỗi người mất thêm một lượt của riêng mình để vào đúng chỗ đó.
    private static final int CT_L_READY     = 6;  // trưởng: đủ quân, báo Manager, chờ lệnh mở
    private static final int CT_L_GOTO_NPC  = 7;  // trưởng: tìm NPC + đi tới
    private static final int CT_L_OPEN_NPC  = 8;  // trưởng: gửi CMD 54 mở NPC
    private static final int CT_L_MENU      = 9;  // trưởng: đọc danh sách hành động, chọn mục cấm thuật
    private static final int CT_L_VERIFY    = 16; // trưởng: đã bấm, chờ map đổi
    private static final int CT_M_STANDBY   = 17; // member: đã vào nhóm, đứng yên chờ được kéo vào hầm
    private static final int CT_IN_DUNGEON  = 18; // cả hai vai: đang trong hầm

    private static final int CT_M_GOTO_MAP  = 11; // member: về map làng
    private static final int CT_M_WAIT_ZONE = 12; // member: chờ Manager báo khu của trưởng
    private static final int CT_M_GOTO_ZONE = 13; // member: sang đúng khu của trưởng
    private static final int CT_M_JOIN      = 14; // member: gửi CMD 39 tới khi vào được nhóm

    private int ctStep = 0;               // 0 = tắt
    private int ctRole = 0;               // 1 = trưởng nhóm, 2 = thành viên
    private long ctNextTime = 0;
    private long ctDeadline = 0;
    private String ctLeaderName = "";     // member: tên nhân vật trưởng nhóm cần bám vào
    private int ctWantMap = 0;            // map tập kết (0 = lấy từ config village)
    private int ctWantZone = -1;          // member: khu trưởng nhóm đang đứng
    private long ctStartAt = 0;           // mốc bắt đầu lượt gom — trần cứng của việc gia hạn
    private java.util.List<String> ctMembers = new java.util.ArrayList<String>(); // trưởng: tên member cần gom
    private int ctGroupSentZone = -1;
    private int ctZoneCursor = -1;
    private int ctZoneHops = 0;
    private boolean ctZonePending = false;
    private int ctZoneWaits = 0;
    private int ctJoinTries = 0;
    private long ctNextInvite = 0;        // trưởng: mốc mời lại người còn thiếu
    private String ctLastRoster = "";     // trưởng: danh sách thành viên lần báo gần nhất, để khỏi báo lặp
    private boolean ctLockSent = false;   // trưởng: đã gửi CMD 42 khoá nhóm chưa (lệnh ĐẢO, chỉ gửi một lần)
    private long ctFullSince = 0;         // trưởng: mốc đủ quân, để chờ lắng rồi mới khoá nhóm
    // trưởng: mốc member ĐẦU TIÊN báo không chen được vào khu (khu đầy người). 0 = chưa ai báo.
    // Chỉ có dòng báo này mới cho phép trưởng nhóm dời khu — xem nhánh khu-đầy-người ở CT_L_WAIT.
    private long ctZoneFullAt = 0;
    private long ctNextJoinSend = 0;      // member: mốc được phép GỬI LẠI CMD 39 (khác nhịp soi nhóm)
    private int ctSlot = 0;               // member: số thứ tự trong nhóm, dùng để lệch lượt xin
    private int ctExpected = 0;           // trưởng: SĨ SỐ ĐÍCH theo doi_hinh.cfg, kể cả nick chưa vào game
    private boolean ctAutoAcceptChanged = false; // trưởng: có tự tay đổi z.ah không (để trả lại nguyên trạng)
    private boolean ctPrevAutoAccept = false;    // giá trị z.ah trước khi đổi
    private int ctNpcId = -1;             // id thật của NPC mở cấm thuật trên map hiện tại
    private int ctWalkTries = 0;          // số lần đã đi tới toạ độ dự phòng trong config
    private int ctMapBefore = -1;         // map ngay trước cú bấm, để so sánh xác nhận đã vào
    private int ctVerifyWaits = 0;        // số vòng đã chờ map đổi
    private int ctDungeonMap = -1;        // map của hầm đang ở trong; rời map này = hoạt động xong
    private String[] ctParentMenu = null; // danh sách hành động của NPC, giữ để nhận ra dialog lạ hiện sau
    private int ctTurnsDone = 0;          // số lượt cấm thuật đã đi xong trong phiên này
    private int ctMaxTurns = 0;           // số lượt tối đa được đi (0 = lấy từ config)
    private String ctNpcXySrc = "?";      // nguồn toạ độ điểm tập kết lần tra gần nhất (chỉ để in khi có sự cố)
    private int ctNoXyTries = 0;          // số lần liên tiếp tra hụt toạ độ điểm tập kết
    private long ctNextDiag = 0;          // mốc được phép in lại dòng chẩn đoán vị trí
    private int ctLastX = -99999;         // toạ độ nhịp đi trước, để biết nhân vật CÓ nhúc nhích không
    private int ctLastY = -99999;
    private int ctStuckTries = 0;         // số nhịp liền không nhúc nhích
    private boolean ctAfterDungeon = false; // trưởng nhóm VỪA RA KHỎI HẦM, chưa chỉnh xong vị trí
    private int ctFixTries = 0;           // số nhịp đã chỉnh vị trí sau khi ra hầm
    private int ctWantX = -1;             // member: TOẠ ĐỘ THẬT trưởng nhóm đang đứng (-1 = chưa có)
    private int ctWantY = -1;
    private int ctMapWaits = 0;           // số nhịp đã chờ map đọc ra ổn định sau khi ra hầm

    /** Bắt đầu vai TRƯỞNG NHÓM: lập nhóm, mở khoá, báo khu rồi chờ member. */
    /**
     * Dừng hẳn việc đang làm trước khi bắt đầu một hoạt động mới.
     *
     * Bắt buộc phải có: xong Địa cung là nhân vật được bàn giao sang AFK_FARM, và vòng lặp AFK
     * đó vẫn chạy. Bấm Cấm thuật ngay sau đó mà không tắt thì HAI luồng cùng điều khiển một
     * nhân vật — Cấm thuật bảo về làng, AFK farm kéo lại map treo — kết quả là đứng ì tại chỗ
     * up, đúng hiện tượng đã gặp.
     */
    /**
     * DỪNG HẲN mọi thứ tool đang làm — dùng cho nút "Tắt Auto".
     *
     * Cần hàm riêng vì máy trạng thái Địa cung và Cấm thuật chạy TRƯỚC cổng `enabled` trong
     * tick() (cố ý, để chúng chạy được khi Auto NV đang tắt). Hệ quả: `setEnabled(false)` một
     * mình KHÔNG dừng được chúng — bấm Tắt Auto giữa chừng thì nhân vật vẫn đi tiếp lượt sau.
     *
     * Khác `finishCamThuat`/`finishDiaCung` ở chỗ KHÔNG bàn giao sang treo map: người dùng bấm
     * tắt là muốn đứng yên, không phải đổi sang việc khác.
     */
    public String stopAllActivities() {
        StringBuilder what = new StringBuilder();
        try {
            if (ctStep > 0) {
                what.append("Cam thuat(buoc ").append(ctStep).append(") ");
                // Trả ô "tự cho vào nhóm" về nguyên trạng — nó là công tắc của người chơi.
                if (ctAutoAcceptChanged) {
                    try { setAutoAcceptGroup(ctPrevAutoAccept); } catch (Exception ignore) {}
                }
                resetCamThuat();
            }
            if (dcStep > 0) {
                what.append("Dia cung(buoc ").append(dcStep).append(") ");
                dcStep = 0;
            }
            if (agtStep > 0) {
                what.append("AGT(buoc ").append(agtStep).append(") ");
                resetAgt();
            }
            if (dhStep > 0) {
                what.append("Dai hoi(buoc ").append(dhStep).append(") ");
                resetDaiHoi();
            }
            if (flStep > 0) {
                what.append("Bam theo ");
                resetFollow();
            }
            if (scStep > 0) {
                what.append("Son cap(buoc ").append(scStep).append(") ");
                if (scAutoAcceptChanged) {
                    try { setAutoAcceptGroup(scPrevAutoAccept); } catch (Exception ignore) {}
                }
                resetSonCap();
            }
            if (quizStep > 0) {
                what.append("Quiz ");
                quizStep = QUIZ_STEP_IDLE;
            }
            setAutoCombat(false);
            autoCombatRequested = false;
            setEnabled(false);
            clearNavTarget();
        } catch (Exception e) {
            log("stopAllActivities error: " + e.getMessage());
        }
        String msg = what.length() == 0 ? "khong co hoat dong nao dang chay" : ("da dung: " + what);
        log("Tat Auto -> " + msg);
        return msg;
    }

    private void stopCurrentActivity() {
        try {
            // DỪNG CẢ HAI MÁY TRẠNG THÁI, không chỉ hạ cờ enabled.
            //
            // tick() gọi tickDiaCung() và tickCamThuat() TRƯỚC cổng `enabled` (cố ý, để chạy được
            // khi Auto NV tắt). Nên setEnabled(false) KHÔNG dừng được chúng. Trước đây:
            //   - bấm Cấm thuật mà dcStep vẫn > 0  ⇒ Địa cung tiếp tục chạy song song
            //   - bấm Địa cung  mà ctStep vẫn > 0  ⇒ Cấm thuật tiếp tục chạy song song
            // Hai máy trạng thái cùng phát navigateTo lên MỘT nhân vật, tranh nhau kéo đi hai
            // hướng. Đây là lời giải cho "nhân vật tự chạy tới một toạ độ không có trong bước
            // nào của Cấm thuật": lệnh đó do máy trạng thái kia phát.
            dcStep = 0;
            resetCamThuat();
            resetSonCap();
            resetAgt();
            resetDaiHoi();      // Đại hội cũng chạy TRƯỚC cổng `enabled`, hạ cờ đó không dừng được nó
            resetFollow();      // bám theo là phần PHỤ của ba máy trên, không được sống lâu hơn chúng
            quizStep = QUIZ_STEP_IDLE;

            setAutoCombat(false);
            // Xoá luôn MỤC TIÊU đang đánh. Tắt cờ auto đánh không đụng tới z.a, nên nhân vật vừa
            // rời map treo vẫn còn khoá vào con quái ở đó và tự đi ngược lại — giằng với lệnh đi
            // của hoạt động sắp bắt đầu. Đây là cửa vào chung của mọi hoạt động nên phải sạch.
            clearCombatTarget();
            autoCombatRequested = false;
            setEnabled(false);
            clearNavTarget();   // bỏ đích di chuyển cũ của AFK farm, không thì nó đi tiếp
        } catch (Exception e) {
            log("stopCurrentActivity error: " + e.getMessage());
        }
    }

    /**
     * Khu xuất phát của MỘT nhóm trong dãy [zone_min, maxZone], chia đều theo số nhóm.
     *
     * Vì sao phải chia: mọi máy đều dùng chung nextZoneToTry với con trỏ khởi tạo -1, nên trưởng
     * nhóm nào cũng nhảy phát đầu vào đúng zone_min rồi +1 — ba nhóm đi chung một cái thang, cách
     * nhau vài giây, khu nào cũng giẫm chân nhau. Đo thật 05/08: CT-2 vào khu 15/16/17 lúc
     * 11:07:44 / 11:08:33 / 11:08:57, CT-1 bám sau đúng 2–27 giây ở cả ba khu và không lần nào
     * chen được, tới lúc member hết giờ bỏ đi train.
     *
     * Vẫn TUẦN TỰ và vẫn tái hiện được khi soi log — chỉ là ba nhóm không còn bước cùng một nhịp.
     */
    private int khuXuatPhatNhom(int slot, int slots, int maxZone) {
        int min = zoneMin();
        if (maxZone <= 0) maxZone = 30;
        if (maxZone <= min) return min;
        if (slots <= 1 || slot <= 0) return min;
        int range = maxZone - min + 1;
        return min + (int) (((long) slot * range) / slots);
    }

    public String startCamThuatLeader(java.util.List<String> memberNames, int expected) {
        return startCamThuatLeader(memberNames, expected, 0, 1);
    }

    public String startCamThuatLeader(java.util.List<String> memberNames, int expected,
                                      int zoneSlot, int zoneSlots) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        if (zFieldGroup == null || emMethodQ == null || emMethodP == null) {
            return "LOI: chua map duoc doi tuong nhom (a.em)";
        }
        stopCurrentActivity();
        resetCamThuat();
        ctRole = 1;
        ctStep = CT_L_GOTO_MAP;
        ctMembers = (memberNames != null) ? memberNames : new java.util.ArrayList<String>();
        // Sĩ số đích lấy theo doi_hinh.cfg (Manager gửi sang), KHÔNG suy từ số member gửi kèm:
        // Manager đã lọc bỏ nick chưa vào game khỏi danh sách đó, bám vào nó thì nhóm 4 người
        // mà mới 2 nick mở đã tưởng là đủ rồi khoá luôn, chặn mất 2 người còn lại.
        ctExpected = (expected > 0) ? expected : (1 + ctMembers.size());
        // Khu xuất phát riêng cho nhóm này. Đặt con trỏ lùi một bậc để cú nhảy ĐẦU TIÊN rơi đúng
        // vào khu đó — nextZoneToTry luôn trả về "khu kế tiếp" chứ không trả về chính con trỏ.
        int khuDau = khuXuatPhatNhom(zoneSlot, zoneSlots, getSettingInt("cam_thuat_max_zone", 20));
        ctZoneCursor = khuDau - 1;
        ctStartAt = System.currentTimeMillis();
        ctDeadline = ctStartAt + getSettingInt("cam_thuat_group_timeout_ms", 180000);
        log("Cam thuat: vai TRUONG NHOM, si so dich " + ctExpected + ", dang cho "
                + ctMembers.size() + " member " + ctMembers
                + " | nhom " + (zoneSlot + 1) + "/" + Math.max(1, zoneSlots)
                + " -> phai nhay khu thi bat dau tu khu " + khuDau);
        return "da bat dau gom nhom (truong nhom)";
    }

    /** Bắt đầu vai THÀNH VIÊN: về làng rồi chờ Manager báo khu của trưởng nhóm. */
    public String startCamThuatMember(String leaderName, int slot) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        if (zFieldGroup == null || emMethodQ == null) return "LOI: chua map duoc doi tuong nhom (a.em)";
        if (leaderName == null || leaderName.trim().isEmpty()) return "LOI: thieu ten truong nhom";
        stopCurrentActivity();
        resetCamThuat();
        ctRole = 2;
        ctStep = CT_M_GOTO_MAP;
        ctLeaderName = leaderName.trim();
        ctSlot = slot < 0 ? 0 : slot;
        ctStartAt = System.currentTimeMillis();
        ctDeadline = ctStartAt + getSettingInt("cam_thuat_group_timeout_ms", 180000);
        log("Cam thuat: vai THANH VIEN, bam theo truong nhom '" + ctLeaderName + "' (slot " + ctSlot + ")");
        return "da bat dau gom nhom (thanh vien)";
    }

    /**
     * Manager chuyển tiếp map/khu mà trưởng nhóm đang đứng. Gọi được nhiều lần:
     * trưởng nhóm có thể phải nhảy khu vì khu đầy nhóm, lúc đó member phải bám theo khu mới.
     */
    public String setCamThuatTarget(int mapId, int zoneId, String leaderName) {
        return setCamThuatTarget(mapId, zoneId, leaderName, -1, -1);
    }

    /**
     * Nhận điểm tập kết kèm TOẠ ĐỘ THẬT của trưởng nhóm.
     *
     * x,y &lt; 0 nghĩa là không có (Manager đời cũ, hoặc trưởng nhóm đọc hụt) — lúc đó member vẫn
     * quay về cách cũ: tự tra toạ độ trong config của chính nó.
     */
    public String setCamThuatTarget(int mapId, int zoneId, String leaderName, int leaderX, int leaderY) {
        if (ctRole != 2 || ctStep == 0) return "LOI: nick nay khong o vai thanh vien";
        if (leaderName != null && !leaderName.trim().isEmpty()) ctLeaderName = leaderName.trim();
        if (mapId > 0) ctWantMap = mapId;
        if (leaderX >= 0 && leaderY >= 0) {
            ctWantX = leaderX;
            ctWantY = leaderY;
        }
        boolean moved = (zoneId != ctWantZone);
        ctWantZone = zoneId;
        if (moved && ctStep >= CT_M_WAIT_ZONE) {
            // Khu đích đổi → phải đi lại từ bước sang khu, kể cả khi đang thử vào nhóm.
            ctStep = CT_M_GOTO_ZONE;
            ctZonePending = false;
            ctZoneWaits = 0;
            ctJoinTries = 0;
            ctNextTime = 0;
            // Trưởng nhóm vừa dời khu = BẰNG CHỨNG nó còn đang gom. Không gia hạn thì member
            // đếm đúng một ngân sách 3 phút cho cả quá trình, mà mỗi lần nhảy khu game bắt chờ
            // 15 giây — nhảy vài lần là hết veo. Đo thật 05/08: CT-2 mở được hầm ở giây thứ 173,
            // còn hai member của CT-1 chết ở giây 140 và 165 trong lúc trưởng nhóm vẫn đang nhảy.
            giaHanCamThuat("truong nhom doi sang khu " + zoneId);
        }
        log("Cam thuat: nhan diem tap ket map " + ctWantMap + " khu " + ctWantZone
                + " cua '" + ctLeaderName + "'");
        return "da nhan khu " + zoneId;
    }

    /**
     * Nới hạn chót khi có bằng chứng lượt gom còn sống — KHÔNG phải nới vô hạn.
     *
     * Trần cứng tính từ lúc bắt đầu (cam_thuat_member_han_toi_da_ms) để một trưởng nhóm hỏng
     * nhưng vẫn nhảy khu đều đều không giữ member đứng đó cả buổi. Trưởng nhóm cũng có trần
     * riêng của nó (cam_thuat_max_zone_hop), nên hai bên chặn nhau.
     */
    private void giaHanCamThuat(String viSao) {
        long now = System.currentTimeMillis();
        long moi = now + getSettingInt("cam_thuat_group_timeout_ms", 180000);
        long tran = (ctStartAt > 0 ? ctStartAt : now)
                  + getSettingInt("cam_thuat_member_han_toi_da_ms", 900000);
        if (moi > tran) moi = tran;
        if (moi <= ctDeadline) return;
        long them = (moi - ctDeadline) / 1000;
        ctDeadline = moi;
        log("Cam thuat: gia han them " + them + "s (" + viSao + ")");
    }

    /**
     * Manager đã xác nhận cả nhóm đứng cùng map cùng khu → trưởng nhóm được phép đi mở cấm thuật.
     * Chỉ nhận khi đang ở đúng bước chờ, để một lệnh gửi nhầm không kéo nhân vật đi bấm NPC
     * giữa lúc đang làm việc khác — cú bấm đó tiêu một lượt trong ngày.
     */
    public String openCamThuat() {
        if (ctRole != 1 || ctStep != CT_L_READY) {
            return "LOI: nick nay khong o buoc cho mo cam thuat (step " + ctStep + ")";
        }
        ctStep = CT_L_OPEN_NPC;
        ctNextTime = 0;
        ctDeadline = System.currentTimeMillis() + getSettingInt("cam_thuat_enter_timeout_ms", 120000);
        log("Cam thuat: Manager da chot ca nhom tap ket du -> di mo cam thuat");
        return "da bat dau mo cam thuat";
    }

    /**
     * Manager chuyển tiếp tiếng báo của member: "tôi không chen được vào khu của trưởng nhóm".
     * Chỉ TRƯỞNG NHÓM đang ở bước chờ member mới nhận — mọi vai/bước khác bỏ qua, vì dời khu ở
     * bước khác là kéo nhân vật ra khỏi việc đang làm.
     */
    public String notifyCamThuatZoneFull(String who, int khuBiTuChoi) {
        if (ctRole != 1 || ctStep != CT_L_WAIT) {
            return "bo qua: nick nay khong o buoc cho member (vai " + ctRole + " step " + ctStep + ")";
        }
        // CHỈ TÍNH TIẾNG BÁO CHO KHU MÌNH ĐANG ĐỨNG.
        //
        // Đây là bản sửa lỗi "trưởng nhóm đổi khu liên tục" (người dùng đo được 31/07).
        // Nhịp hai bên LỆCH NHAU rất xa: trưởng nhóm dời sau crowd_wait (5s), còn member bị game
        // khoá 15s mới được thử khu mới. Nên sau mỗi lần dời, những tiếng báo còn lại về KHU CŨ
        // — đang trên đường, hoặc do member khác chưa kịp biết khu mới — lại đặt cờ lần nữa và
        // trưởng nhóm dời tiếp. Nó chạy nhanh hơn khả năng bám theo của chính member mình.
        //
        // Lọc bằng HẾT GIỜ (kiểu "vừa dời xong thì bỏ qua báo trong N giây") thì lại phải đoán N.
        // Lọc bằng SỐ KHU thì chính xác: báo về khu đã rời là tin cũ, bỏ; báo về khu đang đứng là
        // tin mới, tính. Cùng nguyên tắc "điều kiện là bằng chứng, không phải hết giờ".
        int khuHienTai = getCurrentZoneId();
        if (khuBiTuChoi > 0 && khuBiTuChoi != khuHienTai) {
            return "bo qua: bao ve khu " + khuBiTuChoi + " nhung minh da sang khu " + khuHienTai;
        }
        if (ctZoneFullAt == 0) {
            ctZoneFullAt = System.currentTimeMillis();
            ctNextTime = 0;   // xét lại ngay, khỏi chờ hết nhịp soi nhóm
            log("Cam thuat: member '" + who + "' bao khong chen duoc vao khu "
                    + khuHienTai + " -> se doi khu");
        }
        return "da nhan bao khu day nguoi";
    }

    /** Dừng gom nhóm giữa chừng (Manager huỷ, hoặc nhóm khác đã hỏng). */
    public String stopCamThuat() {
        if (ctStep == 0) return "khong co phien gom nhom nao dang chay";
        camThuatLeaveGroup();   // Manager bảo dừng thì cũng dọn nhóm luôn
        camThuatHandoffAfk();   // và đừng đứng chôn chân ở làng
        resetCamThuat();
        log("Cam thuat: da dung theo yeu cau");
        return "da dung gom nhom";
    }

    private void resetCamThuat() {
        ctStep = 0;
        ctRole = 0;
        ctNextTime = 0;
        ctDeadline = 0;
        ctStartAt = 0;
        ctLeaderName = "";
        ctWantMap = 0;
        ctWantZone = -1;
        ctWantX = -1;
        ctWantY = -1;
        ctMembers = new java.util.ArrayList<String>();
        ctGroupSentZone = -1;
        ctZoneFullAt = 0;
        ctZoneCursor = -1;
        ctZoneHops = 0;
        ctZonePending = false;
        ctZoneWaits = 0;
        ctJoinTries = 0;
        ctNextInvite = 0;
        ctLastRoster = "";
        ctLockSent = false;
        ctFullSince = 0;
        ctNextJoinSend = 0;
        ctSlot = 0;
        ctExpected = 0;
        ctAutoAcceptChanged = false;
        ctPrevAutoAccept = false;
        ctNpcId = -1;
        ctWalkTries = 0;
        ctMapBefore = -1;
        ctVerifyWaits = 0;
        ctDungeonMap = -1;
        ctParentMenu = null;
        ctTurnsDone = 0;
        ctMaxTurns = 0;
        ctNpcXySrc = "?";
        camThuatResetWalk();
    }

    /** Xoá trạng thái theo dõi đi lại. Gọi ở mọi chỗ bắt đầu một pha đi mới. */
    private void camThuatResetWalk() {
        ctNoXyTries = 0;
        ctNextDiag = 0;
        ctLastX = -99999;
        ctLastY = -99999;
        ctStuckTries = 0;
        ctAfterDungeon = false;
        ctFixTries = 0;
        ctMapWaits = 0;
    }

    /** Nối danh sách tên bằng ';' — Manager tách lại bằng đúng ký tự này. */
    private static String joinNames(java.util.List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(";");
            sb.append(n.trim());
        }
        return sb.toString();
    }

    private void camThuatProgress(String detail) {
        log("Cam thuat: " + detail);
        pushCamThuat("cam_thuat_progress", false, detail, -1, -1, "");
    }

    private void finishCamThuat(boolean ok, String detail) {
        int role = ctRole;

        // Gỡ tuyến bám theo TRƯỚC MỌI ĐƯỜNG KẾT THÚC. Đây là cửa ra duy nhất của cả hoạt động
        // (xong, hỏng, hết giờ trong hầm, người dùng bấm tắt) nên đặt ở đây là không bỏ sót
        // đường nào — kể cả những đường không đi qua chỗ "ra khỏi hầm" bên tickCamThuat.
        if (ctStep == CT_IN_DUNGEON) pushCamThuatDungeonMark(false, ctDungeonMap);

        // BÁO TRƯỚC, DỌN SAU.
        // Manager nhận cam_thuat_end là phát ngay cam_thuat_stop cho member, nên member bắt đầu
        // rời nhóm và đi treo CÙNG LÚC với trưởng nhóm chứ không phải sau khi trưởng nhóm đã
        // dọn xong. Trước đây dòng này nằm cuối hàm.
        //
        // KIỂU RIÊNG cho "chốt phiên", không dùng chung cam_thuat_group: kiểu đó được đẩy ở CẢ
        // HAI lúc — gom nhóm xong và kết thúc phiên — nên Manager không phân biệt được, và nó
        // chỉ báo member khi ok = false. Mà đường kết thúc hay gặp nhất ("có người hết lượt")
        // lại là kết thúc BÌNH THƯỜNG (ok = true) ⇒ member không ai báo, đứng ở chỗ NPC chờ hết
        // 120s mới tự bỏ, lại còn bị ghi thành LỖI.
        log("Cam thuat: " + (ok ? "XONG - " : "HONG - ") + detail);
        pushCamThuat("cam_thuat_end", ok, detail, -1, -1, role == 1 ? "leader" : "member");

        // Trả ô "tự cho vào nhóm" về đúng như trước khi tool đụng vào. Đây là công tắc của
        // người chơi trên giao diện, không phải của tool — để nguyên trạng thái lạ là sau này
        // nhóm nào cũng tự nhận người vào mà chủ nick không biết vì sao.
        if (ctAutoAcceptChanged) {
            try {
                setAutoAcceptGroup(ctPrevAutoAccept);
                log("Cam thuat: tra 'tu cho vao nhom' ve " + ctPrevAutoAccept);
            } catch (Exception e) {
                log("Cam thuat: khong tra duoc z.ah: " + e.getMessage());
            }
        }
        // Kết thúc kiểu gì cũng KHÔNG để nhân vật đứng lại giữa làng — kể cả khi kết thúc vì
        // hỏng. Bấm không vào được hầm là đường hay gặp nhất, mà bỏ mặc thì cả nhóm đứng chôn
        // chân ở chỗ NPC không làm gì cho tới khi có người để ý.
        camThuatLeaveGroup();
        camThuatHandoffAfk();
        resetCamThuat();
    }

    /**
     * Bỏ dấu tiếng Việt + hạ chữ thường, để so khớp thông báo của server không phụ thuộc cách
     * đặt dấu. Đây là cái bẫy đã làm hỏng bước nhận chìa Địa cung một lần ("khoá" vs "khóa"):
     * cùng một chữ, hai cách bỏ dấu, so trực tiếp là trượt.
     */
    private static String noAccent(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.replace('đ', 'd');   // đ không tách dấu được bằng NFD
    }

    /**
     * Bàn giao cho treo map AFK. Không làm gì nếu đã ở sẵn trạng thái đó (đường thành công đã
     * tự chuyển trước khi gọi finish) hoặc chưa cấu hình map treo.
     */
    /**
     * Giải tán nhóm khi phiên Cấm thuật kết thúc — mỗi nick tự gửi CMD 44 rời nhóm.
     *
     * Chỉ gọi ở ĐIỂM KẾT THÚC THẬT (finish/stop), KHÔNG gọi giữa các lượt: nhóm phải giữ nguyên
     * để đi tiếp lượt sau.
     *
     * Vì sao mỗi nick tự rời thay vì để trưởng nhóm đá: không phụ thuộc trưởng nhóm còn sống hay
     * còn ở đó. Trưởng nhóm rời thì quyền chuyển sang người khác, người đó cũng đang chạy hàm này
     * nên cũng rời — cuối cùng không ai còn nhóm.
     */
    private void camThuatLeaveGroup() {
        try {
            if (getSettingInt("cam_thuat_leave_group_after", 1) != 1) return;
            Object g = getGroupObj();
            if (hasNoGroup(g)) return;
            sendLeaveGroup();
            log("Cam thuat: ket thuc -> gui CMD 44 roi nhom cho sach");
        } catch (Exception e) {
            log("camThuatLeaveGroup error: " + e.getMessage());
        }
    }

    private void camThuatHandoffAfk() {
        try {
            if (getSettingInt("cam_thuat_after_afk", 1) != 1) return;
            if (afkMapId <= 0) return;
            if (state == TaskState.AFK_FARM) return;
            setAutoCombat(false);
            afkZoneChanged = false;
            autoCombatRequested = false;
            setEnabled(true);
            setState(TaskState.AFK_FARM);
            log("Cam thuat: ket thuc -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
        } catch (Exception e) {
            log("camThuatHandoffAfk error: " + e.getMessage());
        }
    }

    /**
     * Trưởng nhóm báo đội hình thật: ai đang ở trong nhóm, ai còn thiếu.
     * Tên đi bằng danh sách ngăn ';'. Manager gộp lại thành bản tổng kết theo nhóm và dùng
     * chính số liệu này làm cửa chặn trước khi cho vào cấm thuật.
     */
    private void pushCamThuatRoster(java.util.List<String> have, java.util.List<String> missing,
                                    java.util.List<String> strangers) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"cam_thuat_roster\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + missing.isEmpty()
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"have\":\"" + escapeJson(joinNames(have)) + "\""
                    + ",\"missing\":\"" + escapeJson(joinNames(missing)) + "\""
                    + ",\"strangers\":\"" + escapeJson(joinNames(strangers)) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushCamThuatRoster error: " + e.getMessage());
        }
    }

    /**
     * Báo Manager "tôi ĐÃ VÀO hầm" / "tôi ĐÃ RA khỏi hầm" để nó bật/tắt BÁM THEO cho đúng nhóm.
     *
     * Mục đích giống hệt bên Ải gia tộc: dồn hoả lực. Quái trong hầm HP cao, mỗi nick đánh một
     * con ở một góc thì tổng sát thương vẫn thế nhưng mất thời gian ĐI, và cuối vòng con nào
     * cũng dở dang một ít. Dồn cả nhóm vào một con thì không ai phải đi, quái chết sớm nên số
     * con đánh trả lại mình cũng giảm nhanh hơn.
     *
     * Vì sao báo Ở ĐÂY chứ không nối tuyến sẵn từ lúc lập nhóm: nick chưa vào hầm mà đã bám theo
     * thì máy bám thấy khác map — bên trong hoạt động thì nó không tự đi (cờ move=0), nhưng nối
     * sớm cũng chẳng để làm gì, còn nối theo lúc VÀO THẬT thì tuyến luôn khớp với thực tế.
     *
     * Tắt bằng `cam_thuat_follow,0` trong cfg: không báo thì Manager không dựng tuyến nào, phần
     * còn lại của Cấm thuật chạy y như cũ.
     */
    private void pushCamThuatDungeonMark(boolean vao, int mapId) {
        if (getSettingInt("cam_thuat_follow", 1) != 1) return;
        pushCamThuat(vao ? "cam_thuat_in" : "cam_thuat_out", true,
                (vao ? "da vao ham map " : "da ra khoi ham, tung o map ") + mapId,
                mapId, getCurrentZoneId(), ctRole == 1 ? "leader" : "member");
    }

    private void pushCamThuat(String type, boolean ok, String detail, int mapId, int zoneId, String extra) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"map\":" + mapId
                    + ",\"zone\":" + zoneId
                    + ",\"extra\":\"" + escapeJson(extra) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushCamThuat error: " + e.getMessage());
        }
    }

    /**
     * Toạ độ NPC mở cấm thuật trên map hiện tại: ưu tiên đọc SỐNG từ đối tượng NPC trong map,
     * không thấy mới lấy số ghi trong config. Trả null nếu không có nguồn nào.
     */
    private int[] camThuatNpcXY(int mapId) {
        // CONFIG TRƯỚC, đọc sống chỉ là dự phòng — ngược với trước đây.
        // Lý do: toạ độ đọc sống từ đối tượng NPC trong map trả về ~(80,108) trong khi dòng
        // "npc,cam_thuat,68,418,514" ghi (418,514). Cả nhóm kéo nhau tới (80,108) rồi tool báo
        // "tất cả sát NPC" — sát một điểm SAI. Vào hầm vẫn được vì mở NPC là gửi gói theo id,
        // server không xét khoảng cách; nhưng điểm tập kết thì hỏng, và đó là chỗ cần đúng.
        int[] cfg = npcConfig.get("npc_cam_thuat_" + mapId);
        if (cfg != null) { ctNpcXySrc = "config"; return new int[]{cfg[1], cfg[2]}; }
        int[] npc = findNpc(getSetting("cam_thuat_npc", "Onoki"),
                getSettingInt("cam_thuat_npc_id", 32));
        if (npc != null) { ctNpcXySrc = "doc song"; return new int[]{npc[1], npc[2]}; }
        ctNpcXySrc = "KHONG TRA DUOC";
        return null;
    }

    /**
     * Một dòng nói ĐỦ mọi con số cần để biết vì sao cổng chặn không mở: đang đứng đâu, điểm tập
     * kết ở đâu, lấy từ nguồn nào, lệch bao nhiêu so với ngưỡng, và config có dòng NPC cho map
     * này không.
     *
     * Vì sao phải có: lượt chạy 28/07 treo ở bước chờ tới hết giờ mà log chỉ in được TÊN người
     * chưa tới, không in con số nào — không đủ dữ kiện để kết luận nên phải đoán, và đoán sai
     * hai lần. Nhánh nào quyết định "đã tới NPC" thì nhánh đó phải nói được vì sao.
     */
    private String camThuatWhereAmI() {
        try {
            int map = getCurrentMapId();
            int px = getPlayerX();
            int py = getPlayerY();
            int[] xy = camThuatNpcXY(map);
            int rx = getSettingInt("cam_thuat_npc_range", 80);
            int ry = getSettingInt("cam_thuat_npc_range_y", 0);
            if (ry <= 0) ry = rx;
            return "map " + map + " khu " + getCurrentZoneId() + " dung tai (" + px + "," + py + ")"
                    + " | diem tap ket " + (xy == null ? "KHONG CO" : "(" + xy[0] + "," + xy[1] + ")")
                    + " nguon=" + ctNpcXySrc
                    + (xy == null ? "" : " lech dx=" + Math.abs(px - xy[0]) + " dy=" + Math.abs(py - xy[1]))
                    + " nguong x=" + rx + " y=" + ry
                    + " | config co npc_cam_thuat_" + map + ": "
                    + (npcConfig.get("npc_cam_thuat_" + map) != null)
                    + " | npcId=" + ctNpcId;
        } catch (Exception e) {
            return "khong doc duoc vi tri: " + e;
        }
    }

    /**
     * In dòng chẩn đoán về Manager theo nhịp thưa. Dùng ở những bước ĐỨNG CHỜ: đứng chờ mà im
     * lặng thì lúc hết giờ không còn gì để đọc.
     */
    /**
     * Đi tới map tập kết — NHẮM THẲNG ĐIỂM TẬP KẾT, không dùng navigateToMap(map) trần.
     *
     * navigateToMap(map) đặt đích là (0,0). Khi nhân vật ĐÃ Ở SẴN trên map đó thì lệnh "đi xuyên
     * map" thoái hoá thành "đi tới (0,0) của map đang đứng" ⇒ bị kéo về góc map. Đây là lệnh DUY
     * NHẤT trong luồng Cấm thuật có thể sinh ra một toạ độ không có trong config.
     *
     * Nó kích hoạt được vì phép chặn `curMap != wantMap` đọc map NGAY khoảnh khắc đổi map: đọc
     * trúng map cũ là lệnh bay đi, trong khi nhân vật đã đứng ở map mới rồi. Người dùng xác nhận
     * game thả ra ĐÚNG CHỖ NPC và nhân vật đứng im — nên cú đi về góc map là do tool phát.
     */
    private void camThuatGotoMap(int wantMap, int curMap, String why) {
        int[] cfg = npcConfig.get("npc_cam_thuat_" + wantMap);
        if (cfg != null) {
            navigateToMapXY(wantMap, cfg[1], cfg[2]);
            camThuatProgress(why + ": map " + curMap + " -> map " + wantMap
                    + ", nham thang diem tap ket (" + cfg[1] + "," + cfg[2] + ")");
            return;
        }
        navigateToMap(wantMap);
        camThuatProgress(why + ": map " + curMap + " -> map " + wantMap
                + " NHUNG config khong co npc_cam_thuat_" + wantMap
                + " -> dich la (0,0), nhan vat se bi keo ve goc map");
    }

    /**
     * Trưởng nhóm báo ĐIỂM TẬP KẾT — kèm TOẠ ĐỘ THẬT nó đang đứng, không chỉ map/khu.
     *
     * Trước đây đường truyền chỉ mang map + khu + tên trưởng nhóm; member đi tới NPC bằng cách
     * TỰ ĐỌC quest_anchors.cfg của chính nó. Nghĩa là "cả nhóm đứng cùng một chỗ" phụ thuộc vào
     * việc 12 tiến trình đọc ra cùng một con số — một giả định chưa ai kiểm, và nếu có nick nào
     * nạp hụt config thì nó nhắm một điểm khác mà không ai biết, cổng chặn không bao giờ đủ.
     *
     * Trưởng nhóm đã là nguồn sự thật cho KHU rồi; cho nó làm nguồn cho cả TOẠ ĐỘ thì "cùng một
     * chỗ" thành đúng do cấu trúc, không còn do trùng hợp cấu hình.
     */
    private void pushCamThuatZone(String detail, String leaderName) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"cam_thuat_zone\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":true"
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + getPlayerX() + ",\"y\":" + getPlayerY()
                    + ",\"extra\":\"" + escapeJson(leaderName) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushCamThuatZone error: " + e.getMessage());
        }
    }

    private void camThuatDiag(long now, String what) {
        if (now < ctNextDiag) return;
        ctNextDiag = now + getSettingInt("cam_thuat_diag_ms", 15000);
        camThuatProgress(what + " - " + camThuatWhereAmI());
    }

    /**
     * Một nhịp đi tới điểm tập kết — DÙNG CHUNG cho cả trưởng nhóm lẫn member.
     *
     * Client có HAI cơ chế di chuyển và chúng khác hẳn nhau:
     *   - fp.c(map,x,y)       : đi trong map, KHÔNG đụng tới z.ap
     *   - bi_0 + z.ap = true  : auto-nav gốc của game, thứ thật sự kéo nhân vật đi xa
     *
     * Ra khỏi hầm, member đi vòng qua CT_M_GOTO_MAP → navigateToMap() nên z.ap ĐANG BẬT; còn
     * trưởng nhóm gọi clearNavTarget() (z.ap = false) rồi chỉ còn fp.c trần. Trưởng nhóm tự tắt
     * đúng cái cơ chế đang chở member đi — và đó là chỗ hai vai KHÁC nhau, khớp với việc member
     * về được chỗ NPC còn trưởng nhóm thì không.
     *
     * Cách xử lý: vẫn phát fp.c trước (đang chạy được cho member, không đập đi làm lại), nhưng
     * theo dõi toạ độ; mấy nhịp liền không nhúc nhích thì chuyển sang auto-nav gốc NHẮM ĐÚNG
     * điểm tập kết. Và in toạ độ ra: "đứng im" với "đi mà không tới" là hai lỗi khác nhau, không
     * có số thì không phân biệt được — đúng thứ đã thiếu ở lượt chạy 28/07.
     */
    private void camThuatWalkStep(long now, int mapId, int[] xy, String who) throws Exception {
        int px = getPlayerX();
        int py = getPlayerY();
        int eps = getSettingInt("cam_thuat_moved_px", 8);
        if (Math.abs(px - ctLastX) > eps || Math.abs(py - ctLastY) > eps) {
            ctStuckTries = 0;
        } else {
            ctStuckTries++;
        }
        ctLastX = px;
        ctLastY = py;

        if (ctStuckTries >= getSettingInt("cam_thuat_stuck_tries", 3)) {
            navigateToMapXY(mapId, xy[0], xy[1]);
            camThuatProgress(who + ": khong nhuc nhich sau " + ctStuckTries
                    + " nhip -> chuyen sang auto-nav goc (z.ap). " + camThuatWhereAmI());
            ctStuckTries = 0;
            return;
        }

        // ĐI XA THÌ DÙNG CƠ CHẾ ĐI XA.
        //
        // Đây KHÔNG phải kết luận về nguyên nhân — nguyên nhân "vì sao nhóm xong trước thì lành
        // mà nhóm xong sau thì trưởng nhóm đơ tại chỗ" vẫn chưa chứng minh được. Đây là chính
        // sách đi lại cho đúng công cụ:
        //   - quãng dài  → auto-nav gốc (bi_0 + z.ap), thứ game dùng để đi xuyên map
        //   - quãng ngắn → fp.c, đủ cho bước áp sát cuối
        // Nó cũng san bằng chênh lệch giữa hai vai: member ra khỏi hầm đi qua bước về map nên
        // auto-nav gốc ĐANG chạy sẵn, còn trưởng nhóm gọi clearNavTarget() rồi chỉ còn fp.c trần.
        // Quan sát lặp lại nhiều lượt: cùng một điểm xuất phát, member đi được, trưởng nhóm đơ.
        int far = getSettingInt("cam_thuat_far_px", 200);
        if (Math.abs(px - xy[0]) > far || Math.abs(py - xy[1]) > far) {
            navigateToMapXY(mapId, xy[0], xy[1]);
            camThuatDiag(now, who + ": con xa diem tap ket -> di bang auto-nav goc");
            return;
        }
        navigateTo(mapId, xy[0], xy[1]);
        camThuatDiag(now, who + ": ap sat diem tap ket");
    }

    /**
     * Báo vị trí ở bước chờ vào hầm. Gửi kèm toạ độ và cờ ĐÃ ĐỨNG SÁT NPC chưa — cùng map cùng
     * khu vẫn chưa đủ: đứng xa NPC là không vào được, nên đây phải là một điều kiện của cổng chặn.
     * Không tìm được NPC ⇒ báo atNpc = false, để cổng chặn giữ lại thay vì cho bấm liều.
     */
    /**
     * ĐIỂM PHẢI ĐI TỚI **VÀ** ĐIỂM DÙNG ĐỂ CHẤM "ĐÃ TỚI CHƯA" — BẮT BUỘC LÀ MỘT.
     *
     * Đây là bản sửa cái làm hỏng lượt 3 của CT-1 lúc 09:52 ngày 01/08. Trước đó hai chỗ đo bằng
     * HAI THƯỚC KHÁC NHAU:
     *   · bước đi của member  → nhắm TOẠ ĐỘ THẬT của trưởng nhóm (ctWantX/Y)
     *   · cờ atNpc gửi Manager → đo theo ĐIỂM TRONG CONFIG
     * Bình thường hai điểm đó trùng nhau nên không lộ. Lượt đó trưởng nhóm ra hầm và dừng ở
     * (455,514) thay vì (418,514), thế là:
     *     member đứng (500,514) → cách trưởng nhóm 45px  ⇒ TỰ CHO LÀ ĐÃ TỚI, đứng im
     *                            → cách điểm config 82px ⇒ atNpc = false, cổng không bao giờ mở
     * Thế bí hoàn hảo: không ai sai lệnh nào, không ai đi thêm bước nào, và cả nhóm đứng đó
     * 116 giây tới lúc trưởng nhóm hết hạn ("het gio o buoc 6"). Ba member im lặng suốt vì
     * chúng không còn ở nhánh đi nữa.
     *
     * Chọn CONFIG làm thước chính chứ không chọn vị trí trưởng nhóm: cổng chặn và cơ chế kéo vào
     * hầm đều neo vào chỗ NPC, và 82px-so-với-NPC là khoảng CHƯA có bằng chứng là kéo được.
     * Vị trí trưởng nhóm chỉ còn là đường lui khi tra hụt config.
     */
    /**
     * NGƯỠNG NGANG VÀ NGƯỠNG DỌC LÀ HAI CHUYỆN KHÁC NHAU — đây là lý do phải tách.
     *
     * Map trong game này có TẦNG NỀN. Lệch theo X là đứng cách nhau trên cùng một mặt đất; lệch
     * theo Y là đứng trên MỘT BỆ KHÁC. Hai cái đó không thể chung một con số:
     *
     *   · X phải RỘNG. Chín member dồn về một điểm thì bộ tìm đường không cho chồng ô, chúng xếp
     *     thành hàng ngang — đo được 349..355 quanh điểm 418 (lệch 63-69). Ngưỡng chật là cả
     *     hàng đứng ngoài cổng.
     *   · Y phải CHẶT. Không có lực nào đẩy nhân vật lên cao cả — nếu nó ở trên đầu NPC thì đó
     *     là một bệ khác, và từ đó nó KHÔNG đi ngang sang chỗ NPC được. Cho 80px dọc là tool tự
     *     nhận "đã tới" rồi thôi không đi nữa, trong khi nhân vật đứng trên nóc.
     *     (Chuyện lệch tầng có thật trong game này: lúc dò cơ chế chuyển map đo được nhân vật ở
     *     (38,286) còn tấm biển ở (1728,531) — chênh hơn 200px chiều cao trên cùng một map.)
     *
     * Mọi lượt Cấm thuật đã chạy đều cho dy=0 (cả 12 nick đều ở y=514), nên nhánh này chưa từng
     * bị chạm. Đó là lý do phải chốt TRƯỚC khi nó xảy ra, chứ không phải lý do để bỏ qua.
     *
     * `cam_thuat_npc_range_y <= 0` = dùng chung ngưỡng ngang (giữ nguyên hành vi cũ).
     */
    private boolean camThuatAtPoint(int px, int py, int[] xy) {
        if (xy == null) return false;
        int rx = getSettingInt("cam_thuat_npc_range", 80);
        int ry = getSettingInt("cam_thuat_npc_range_y", 0);
        if (ry <= 0) ry = rx;
        return Math.abs(px - xy[0]) <= rx && Math.abs(py - xy[1]) <= ry;
    }

    private int[] camThuatTargetXY(int map) {
        int[] xy = camThuatNpcXY(map);
        if (xy != null) return xy;
        if (ctWantX >= 0 && ctWantY >= 0 && map == ctWantMap) {
            return new int[]{ctWantX, ctWantY};
        }
        return null;
    }

    private void pushCamThuatReady(String role, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            int map = getCurrentMapId();
            int px = getPlayerX();
            int py = getPlayerY();
            int[] xy = camThuatTargetXY(map);
            boolean atNpc = camThuatAtPoint(px, py, xy);
            w.print("{\"type\":\"cam_thuat_ready\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + atNpc
                    + ",\"map\":" + map
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + px + ",\"y\":" + py
                    + ",\"atNpc\":" + atNpc
                    + ",\"extra\":\"" + escapeJson(role) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushCamThuatReady error: " + e.getMessage());
        }
    }

    /**
     * Hạn giờ khi ĐANG Ở TRONG HẦM. Mặc định KHÔNG giới hạn, và đó là chủ ý:
     * mỗi vòng chỉ có 5 phút để hoàn thành, quá giờ là game tự đẩy ra ngoài — nên không có
     * cách nào kẹt lại trong hầm. Đặt một mốc tổng lại thành hại: 18 vòng có thể kéo dài tới
     * gần 90 phút, cắt ngang là giết một lượt đang chạy bình thường.
     * Chỉ bật (giá trị > 0) nếu có lúc nào đó thấy nhân vật thật sự đứng lì trong hầm.
     */
    private long dungeonDeadline(long now) {
        int ms = getSettingInt("cam_thuat_run_timeout_ms", 0);
        return ms > 0 ? now + ms : Long.MAX_VALUE;
    }

    /** Map tập kết: lấy cam_thuat_map, không khai thì dùng luôn map của config "village". */
    private int camThuatMap() {
        int wantMap = ctWantMap > 0 ? ctWantMap : getSettingInt("cam_thuat_map", 0);
        if (wantMap <= 0) {
            loadAnchorConfig();
            if (villageConfig != null) wantMap = villageConfig[0];
        }
        return wantMap;
    }

    /**
     * Trưởng nhóm đang chờ ở làng mà BỖNG DƯNG ở map khác ⇒ đã bị kéo vào hầm.
     *
     * Xảy ra thật: server giữ `infoChar.idZoneCustom` của lượt trước, nên chỉ cần đổi khu hay
     * đổi map trong làng là nhân vật bị ném ngược vào map hầm — không qua cú bấm nào. Trước đây
     * bước CT_L_GOTO_NPC / CT_L_READY không hề soi map, nên trưởng nhóm cứ tưởng mình còn ở làng
     * và ĐEM MAP HẦM RA LÀM ĐIỂM TẬP KẾT, kéo cả nhóm đi tìm NPC trong hầm cho tới khi hết giờ.
     *
     * @return true nếu đã chuyển sang trạng thái "đang trong hầm" và caller phải return ngay
     */
    private boolean camThuatDetectPulledIn(long now) throws Exception {
        int wantMap = camThuatMap();
        int nowMap = getCurrentMapId();
        if (wantMap <= 0 || nowMap == wantMap) return false;

        // NHẬN BIẾT THEO MAP HẦM ĐÃ BIẾT, không phải theo "khác map làng".
        //
        // Phép cũ là phép PHỦ ĐỊNH: bất kỳ map nào không phải làng đều bị coi là hầm. Chỉ cần
        // getCurrentMapId() trả một giá trị lạ trong khoảnh khắc chuyển màn là trưởng nhóm bị
        // ném ngược vào CT_IN_DUNGEON; nhịp sau thấy map đổi lại ⇒ ĐẾM THÊM MỘT LƯỢT MA
        // (ctTurnsDone++) và chạy lại toàn bộ pha gom nhóm.
        //
        // Map hầm đã biết chắc và đã ghi trong config (89 = Vòng Lặp Ảo Tưởng), log lượt chạy thật
        // in đúng "DA VAO HAM (map 89)" mọi lần. Dùng số đó thay vì suy ra bằng phủ định.
        //
        // Hàm này CHỈ lo đường bất thường (bị kéo vào mà không bấm, hoặc người dùng tự bấm tay).
        // Đường vào bình thường đi qua CT_L_VERIFY và ở đó ctDungeonMap lấy theo map THẬT, nên
        // dù server có đổi map hầm thì đường chính vẫn không hỏng.
        int dungeonMap = getSettingInt("cam_thuat_dungeon_map", 0);
        if (dungeonMap > 0 && nowMap != dungeonMap) {
            camThuatDiag(now, "CANH BAO: dang o map " + nowMap + " - khong phai lang " + wantMap
                    + " cung khong phai map cam thuat " + dungeonMap
                    + " -> KHONG coi la trong ham. Kiem lai cam_thuat_dungeon_map trong cfg");
            return false;
        }

        ctDungeonMap = nowMap;
        ctStep = CT_IN_DUNGEON;
        ctDeadline = dungeonDeadline(now);
        if (getSettingInt("cam_thuat_combat", 1) == 1) {
            clearNavTarget();
            setAutoCombat(true);
            autoCombatRequested = true;
        }
        camThuatProgress("dang cho o lang thi bi keo vao map " + nowMap + " -> coi nhu da vao ham");
        pushCamThuatDungeonMark(true, nowMap);
        ctNextTime = now + getSettingInt("cam_thuat_poll_ms", 3000);
        return true;
    }

    private void tickCamThuat(long now) {
        try {
            if (now > ctDeadline) {
                // TRƯỚC KHI kết luận hỏng: nhân vật có đang ở trong hầm thật không?
                // Đây là lỗi đã gây hậu quả nặng nhất: trưởng nhóm kẹt ở bước chờ, người dùng tự
                // bấm vào hầm bằng tay, rồi hạn 120s của bước chờ nổ ⇒ tool coi là hỏng, TẮT ĐÁNH
                // của cả 4 nick đang đánh trong hầm và kéo họ đi treo map, phá luôn lượt đang chạy.
                // Ở map khác map làng nghĩa là đang trong hầm — chuyển sang theo dõi trong hầm,
                // không phải báo hỏng.
                if (ctStep != CT_IN_DUNGEON && camThuatDetectPulledIn(now)) return;

                // Hết giờ TRONG HẦM không phải lỗi: vẫn phải tắt đánh và bàn giao cho treo map,
                // đừng để nhân vật đứng lại trong hầm đã đóng.
                if (ctStep == CT_IN_DUNGEON) {
                    setAutoCombat(false);
                    autoCombatRequested = false;
                    if (afkMapId > 0 && getSettingInt("cam_thuat_after_afk", 1) == 1) {
                        afkZoneChanged = false;
                        setEnabled(true);
                        setState(TaskState.AFK_FARM);
                        finishCamThuat(true, "het gio trong ham -> chuyen sang treo map " + afkMapId);
                    } else {
                        finishCamThuat(true, "het gio trong ham (chua cau hinh map treo nen dung yen)");
                    }
                    return;
                }
                // Hết giờ thì đây là dòng DUY NHẤT còn lại để đọc — nên nó phải nói đủ số liệu,
                // đừng bắt người đọc suy ra từ mỗi con số bước.
                finishCamThuat(false, "het gio o buoc " + ctStep + " - " + camThuatWhereAmI());
                return;
            }
            if (now < ctNextTime) return;

            final int stepMs     = getSettingInt("cam_thuat_step_ms", 200);
            final int mapWait    = getSettingInt("cam_thuat_map_wait_ms", 2500);
            final int zoneWait   = getSettingInt("cam_thuat_zone_wait_ms", 2500);
            final int groupWait  = getSettingInt("cam_thuat_group_wait_ms", 2500);
            final int joinWait   = getSettingInt("cam_thuat_join_wait_ms", 2000);

            // ── LƯỚI ĐỠ: GOM NHÓM THÌ KHÔNG ĐƯỢC ĐÁNH ──────────────────────────────────────
            // Mọi bước của Cấm thuật TRỪ trong hầm đều là lúc nhân vật cần được ĐI: về map, sang
            // khu, vào nhóm, đi tới NPC, đứng đúng chỗ chờ chốt. Đánh bật lên ở bất kỳ bước nào
            // trong số đó đều kéo nhân vật đi khỏi chỗ nó vừa tới — mà điểm tập kết chỉ có dung
            // sai 60px.
            // Nguồn bật lại đã biết là bám theo, và đã chặn tận gốc (followOwnerAlive giờ chỉ
            // đúng khi ctStep == CT_IN_DUNGEON). Giữ thêm chốt này vì cái giá của nó bằng không
            // — một phép so mỗi nhịp — còn cái giá của việc sót một nguồn khác là mất trọn lượt
            // của cả nhóm, và phải có người ngồi nhìn mới phát hiện ra.
            if (ctStep != CT_IN_DUNGEON && isAutoCombatOn()) {
                setAutoCombat(false);
                // PHẢI XOÁ CẢ MỤC TIÊU, KHÔNG CHỈ TẮT CỜ.
                // Tắt auto đánh KHÔNG đụng tới z.a — nhân vật vẫn còn khoá vào con quái cũ và
                // tự đi ngược về chỗ nó, giằng với lệnh đi tới điểm tập kết. Đo được 08:21-08:23
                // ngày 01/08: cùng quãng ~360px, ba trưởng nhóm đi hết 16 giây còn chín member
                // hết 97 giây — chênh 6 lần, và suốt 97 giây đó member không sinh nổi một dòng
                // log nào vì cứ bị kéo qua kéo lại.
                clearCombatTarget();
                autoCombatRequested = false;
                camThuatProgress("dang gom nhom ma auto danh bat len -> TAT + xoa muc tieu (buoc "
                        + ctStep + ")");
            }

            // ── Bước chung: về map tập kết (mặc định = map của nút Về làng) ──
            if (ctStep == CT_L_GOTO_MAP || ctStep == CT_M_GOTO_MAP) {
                int wantMap = camThuatMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    // VỪA RA KHỎI HẦM THÌ KHÔNG PHÁT LỆNH ĐI ĐÂU CẢ — CHỜ ĐỌC RA MAP ĐÚNG.
                    //
                    // Bấm nút lần đầu thì nhân vật thật sự ở map khác, đi xuyên map là đúng việc.
                    // Nhưng ra khỏi hầm thì nó ĐÃ đứng trên map tập kết rồi; đọc ra số khác chỉ
                    // có nghĩa là đọc trúng lúc chuyển màn chưa xong. Phát lệnh đi xuyên map lúc
                    // đó là lệnh "tới map N" gửi cho một nhân vật đang đứng sẵn ở map N ⇒ thoái
                    // hoá thành đi tới điểm mặc định của lệnh, tức bị kéo về góc map.
                    //
                    // Chờ vài nhịp cho map đọc ra ổn định. Chờ mãi cũng không được (lỡ nhân vật ở
                    // map khác thật), nên quá trần thì mới đi — và nói rõ là đã phải đi.
                    if (ctAfterDungeon
                            && ctMapWaits < getSettingInt("cam_thuat_map_settle_tries", 6)) {
                        ctMapWaits++;
                        camThuatProgress("vua ra khoi ham, doc duoc map " + curMap + " chu khong phai "
                                + wantMap + " -> CHO doc lai (lan " + ctMapWaits
                                + "), KHONG phat lenh di");
                        ctNextTime = now + getSettingInt("cam_thuat_map_settle_ms", 1000);
                        return;
                    }
                    if (ctAfterDungeon) {
                        camThuatProgress("cho " + ctMapWaits + " nhip van doc ra map " + curMap
                                + " -> danh phai di xuyen map that su");
                    }
                    camThuatGotoMap(wantMap, curMap, "ve map tap ket");
                    ctNextTime = now + mapWait;
                    return;
                }
                ctMapWaits = 0;
                ctAfterDungeon = false;   // đã xác định được đang đứng ở đâu
                ctStep = (ctRole == 1) ? CT_L_GROUP : CT_M_WAIT_ZONE;
                ctNextTime = now + stepMs;
                return;
            }

            // ─────────────── TRƯỞNG NHÓM ───────────────

            // Bảo đảm mình là TRƯỞNG của một nhóm. Đang ở nhóm người khác thì rời ra trước.
            if (ctStep == CT_L_GROUP) {
                Object g = getGroupObj();

                if (!hasNoGroup(g) && isGroupLeader(g)) {
                    ctStep = CT_L_UNLOCK;
                    ctNextTime = now + stepMs;
                    return;
                }

                if (!hasNoGroup(g)) {
                    java.util.List<String> names = getGroupMemberNames(g);
                    sendLeaveGroup();
                    log("Cam thuat: dang o nhom cua " + (names.isEmpty() ? "?" : names.get(0))
                            + " -> gui CMD 44 roi nhom");
                    ctNextTime = now + groupWait;
                    return;
                }

                int zone = getCurrentZoneId();

                if (ctZonePending) {
                    long khoa = zoneCooldownLeft(now);
                    if (zone == ctZoneCursor) {
                        ctZonePending = false;
                        ctZoneWaits = 0;
                    } else if (khoa > 0) {
                        // Xem chú thích ở zoneCooldownLeft: còn khoá thì chưa được kết luận gì.
                        ctNextTime = now + khoa + 250;
                        return;
                    } else if (++ctZoneWaits > getSettingInt("cam_thuat_zone_wait_tries", 3)) {
                        ctZoneCursor = nextZoneToTry(ctZoneCursor, zone,
                                getSettingInt("cam_thuat_max_zone", 20));
                        sendChangeZone(ctZoneCursor);
                        ctZoneWaits = 0;
                        log("Cam thuat: doi khu khong an (van o khu " + zone + ") -> thu khu " + ctZoneCursor);
                        ctNextTime = now + zoneWait;
                        return;
                    } else {
                        ctNextTime = now + zoneWait;
                        return;
                    }
                }

                // Đã gửi CMD 41 ngay tại khu này mà vẫn chưa có nhóm ⇒ khu đã đủ số nhóm.
                // Server chỉ hiện banner, không trả mã lỗi đọc được, nên chỉ suy ra bằng hành vi.
                if (ctGroupSentZone >= 0 && ctGroupSentZone == zone) {
                    int maxHop = getSettingInt("cam_thuat_max_zone_hop", 8);
                    if (ctZoneHops >= maxHop) {
                        finishCamThuat(false, "da thu " + ctZoneHops + " khu, khu nao cung day nhom");
                        return;
                    }
                    int maxZone = getSettingInt("cam_thuat_max_zone", 20);
                    ctZoneCursor = nextZoneToTry(ctZoneCursor, zone, maxZone);
                    sendChangeZone(ctZoneCursor);
                    ctZoneHops++;
                    ctGroupSentZone = -1;
                    ctZonePending = true;
                    ctZoneWaits = 0;
                    camThuatProgress("khu " + zone + " day nhom -> doi sang khu " + ctZoneCursor);
                    ctNextTime = now + zoneWait;
                    return;
                }

                sendCreateGroup();
                ctGroupSentZone = zone;
                log("Cam thuat: chua co nhom -> gui CMD 41 lap nhom (khu " + zone + ")");
                ctNextTime = now + groupWait;
                return;
            }

            // Mở khoá nhóm: nhóm khoá thì CMD 39 của member biến thành lời XIN, phải bấm duyệt
            // tay. Mở khoá xong member tự vào thẳng — đây là mấu chốt để gom nhóm tự động.
            if (ctStep == CT_L_UNLOCK) {
                Object g = getGroupObj();
                if (isGroupLocked(g)) {
                    if (ctJoinTries >= getSettingInt("cam_thuat_unlock_tries", 3)) {
                        camThuatProgress("khong mo duoc khoa nhom - member se phai duoc moi thu cong");
                        ctJoinTries = 0;
                        ctStep = CT_L_ANNOUNCE;
                        ctNextTime = now + stepMs;
                        return;
                    }
                    ctJoinTries++;
                    sendToggleGroupLock();
                    log("Cam thuat: nhom dang khoa -> gui CMD 42 mo khoa (lan " + ctJoinTries + ")");
                    ctNextTime = now + groupWait;
                    return;
                }
                // BẬT "tự cho vào nhóm" trước khi gọi member tới. Mở khoá (CMD 42) chỉ quyết định
                // lời xin có được gửi hay không; còn DUYỆT lời xin lại là việc của cờ này. Thiếu
                // nó thì member gửi CMD 39 xong phải nằm chờ tới lúc trưởng nhóm mời — đúng cái
                // đã thấy trên log: member chỉ vào được đúng lúc lượt mời đầu tiên bắn ra.
                if (getSettingInt("cam_thuat_auto_accept", 1) == 1 && zFieldAh != null) {
                    if (!isAutoAcceptGroup()) {
                        ctPrevAutoAccept = setAutoAcceptGroup(true);
                        ctAutoAcceptChanged = true;
                        log("Cam thuat: bat 'tu cho vao nhom' (z.ah) de member tu vao duoc");
                    }
                }

                ctJoinTries = 0;
                ctStep = CT_L_ANNOUNCE;
                ctNextTime = now + stepMs;
                return;
            }

            // Báo điểm tập kết về Manager để Manager chuyển tiếp cho member.
            if (ctStep == CT_L_ANNOUNCE) {
                Object g = getGroupObj();
                java.util.List<String> names = getGroupMemberNames(g);
                String myName = names.isEmpty() ? "" : names.get(0);  // phần tử 0 LUÔN là trưởng nhóm
                int zone = getCurrentZoneId();
                int map = getCurrentMapId();
                pushCamThuatZone("san sang nhan member tai map " + map + " khu " + zone, myName);
                log("Cam thuat: da bao diem tap ket map " + map + " khu " + zone + " (truong '" + myName + "')");
                ctStep = CT_L_WAIT;
                ctZoneFullAt = 0;     // khu mới thì tiếng báo của khu cũ hết giá trị
                // KHÔNG mời ngay. Member tự vào bằng CMD 39 chỉ mất vài giây; mời sớm chỉ tổ
                // đẩy popup "mời gia nhập nhóm" lên màn member rồi nằm lại đó sau khi họ đã vào
                // nhóm bằng đường khác. Chỉ mời khi hết khoảng chờ này mà vẫn thiếu người.
                ctNextInvite = now + getSettingInt("cam_thuat_invite_delay_ms", 15000);
                ctNextTime = now + stepMs;
                return;
            }

            // Chờ member vào nhóm. Định kỳ gửi CMD 41 mời những người còn thiếu — đường lui
            // phòng khi server thật đòi lời mời chứ không cho tự vào bằng CMD 39.
            if (ctStep == CT_L_WAIT) {
                Object g = getGroupObj();
                if (hasNoGroup(g) || !isGroupLeader(g)) {
                    finishCamThuat(false, "mat quyen truong nhom giua chung");
                    return;
                }

                java.util.List<String> have = getGroupMemberNames(g);
                java.util.List<String> missing = new java.util.ArrayList<String>();
                for (String want : ctMembers) {
                    if (want == null || want.trim().isEmpty()) continue;
                    if (!groupHasMember(g, want)) missing.add(want.trim());
                }

                // Người LẠ = đang ở trong nhóm nhưng không có trong danh sách setup. Nhóm phải
                // mở khoá để member tự vào được, mà mở khoá thì ai cũng vào được — nên đây là
                // trạng thái sẽ xảy ra thật, không phải giả định. Bỏ qua phần tử 0 (chính mình).
                java.util.List<String> strangers = new java.util.ArrayList<String>();
                for (int i = 1; i < have.size(); i++) {
                    String n = have.get(i);
                    if (n == null || n.trim().isEmpty()) continue;
                    boolean wanted = false;
                    for (String want : ctMembers) {
                        if (want != null && want.trim().equals(n.trim())) { wanted = true; break; }
                    }
                    if (!wanted) strangers.add(n.trim());
                }

                // Báo danh sách đội hình mỗi khi nó ĐỔI. Đây là số liệu Manager dùng để tổng kết
                // nhóm nào đủ / nhóm nào thiếu — và về sau là cửa chặn trước khi cho vào cấm thuật:
                // nguồn duy nhất đáng tin là danh sách server trả về (CMD 43), không phải file setup.
                String roster = joinNames(have) + "|" + joinNames(missing) + "|" + joinNames(strangers);
                if (!roster.equals(ctLastRoster)) {
                    ctLastRoster = roster;
                    pushCamThuatRoster(have, missing, strangers);
                    // CÓ TIẾN TRIỂN → xoá tiếng báo "khu đầy người" còn treo. Người vẫn đang lục
                    // tục vào được thì khu chưa đầy; dời khu lúc này là tự phá, cả nhóm phải đổi
                    // khu lại, mỗi nick thêm 15s.
                    ctZoneFullAt = 0;
                }

                if (!strangers.isEmpty()) {
                    if (getSettingInt("cam_thuat_kick_stranger", 1) == 1) {
                        for (String n : strangers) sendKickByName(n);
                        camThuatProgress("co nguoi la trong nhom " + strangers + " -> gui CMD 47 duoi");
                        // Chờ CMD 43 mới rồi mới xét tiếp, đừng đuổi chồng lên nhau.
                        ctNextTime = now + groupWait;
                        return;
                    }
                    camThuatProgress("co nguoi la trong nhom " + strangers + " (khong duoi vi cau hinh tat)");
                }

                // ĐỦ QUÂN = không thiếu ai trong danh sách được giao VÀ đạt sĩ số đích của file.
                // Hai điều kiện khác nhau: nick chưa vào game không nằm trong danh sách được giao
                // nên không bao giờ bị tính là "thiếu", chỉ đếm đầu người mới lộ ra.
                boolean complete = missing.isEmpty() && have.size() >= ctExpected;
                if (!complete) ctFullSince = 0;   // rớt mất người thì tính lại từ đầu

                // ── KHU ĐẦY NGƯỜI: TRƯỞNG NHÓM DỜI, KHÔNG PHẢI MEMBER BỎ CUỘC ───────────────
                // Khu có HAI hạn mức khác nhau: số NHÓM trong khu, và số NGƯỜI trong khu (15).
                // Lập được nhóm chỉ vượt qua hạn mức thứ nhất. Trường hợp thật (người dùng 30/07):
                // trưởng lập nhóm xong, member đổi khu thì server không cho vào vì khu đã đủ người
                // ⇒ nhóm không bao giờ đầy, mà chỉ TRƯỞNG NHÓM mới sửa được bằng cách dời chỗ.
                //
                // ĐIỀU KIỆN LÀ BẰNG CHỨNG, KHÔNG PHẢI HẾT GIỜ.
                // Bản nháp đầu tiên của chốt này lấy "đội hình đứng im 45s" làm dấu hiệu — SAI, và
                // sai theo hướng phá thứ đang chạy được: lượt gom bình thường cũng có thể im hơn
                // 45s nếu member còn đang đi bộ về map hoặc còn đang bị khoá đổi khu. Dời khu lúc
                // đó là bắt CẢ NHÓM đổi khu lại, mỗi nick thêm 15s, và cắt ngang đúng những người
                // sắp vào tới.
                // Nay chỉ dời khi CÓ MEMBER BÁO ĐÍCH DANH là không chen vào được (cam_thuat_zone_full
                // → Manager → notifyCamThuatZoneFull). Không có báo thì không bao giờ dời — tức lượt
                // chạy bình thường không đụng tới nhánh này.
                if (!complete && ctZoneFullAt > 0) {
                    int cho    = getSettingInt("cam_thuat_crowd_wait_ms", 5000);
                    int maxHop = getSettingInt("cam_thuat_max_zone_hop", 8);
                    // Chờ một nhịp ngắn sau tiếng báo đầu: nhiều member cùng kẹt sẽ báo lệch nhau
                    // vài giây, gom lại rồi dời MỘT LẦN thay vì dời liên tiếp mấy lần.
                    if (now - ctZoneFullAt < cho) {
                        ctNextTime = now + getSettingInt("cam_thuat_wait_poll_ms", 1500);
                        return;
                    }
                    if (ctZoneHops >= maxHop) {
                        camThuatProgress("member bao khong chen duoc vao khu nhung da nhay "
                                + ctZoneHops + "/" + maxHop + " khu -> khong doi nua, cho het han");
                        ctZoneFullAt = 0;
                    } else {
                        long khoaL = zoneCooldownLeft(now);
                        if (khoaL > 0) {   // còn khoá thì chờ, đừng gửi vào chỗ chắc chắn bị bỏ
                            ctNextTime = now + khoaL + 250;
                            return;
                        }
                        int zoneCu = getCurrentZoneId();
                        ctZoneCursor = nextZoneToTry(ctZoneCursor, zoneCu,
                                getSettingInt("cam_thuat_max_zone", 20));
                        sendChangeZone(ctZoneCursor);
                        ctZoneHops++;
                        ctZonePending = true;
                        ctZoneWaits = 0;
                        ctZoneFullAt = 0;
                        ctFullSince = 0;
                        ctLockSent = false;
                        // Về ANNOUNCE để báo LẠI khu mới cho Manager → Manager phát cho member →
                        // setCamThuatZone đưa từng member về bước đi tới khu. Không tự xử ở đây
                        // được: member không thấy trưởng nhóm, chúng chỉ biết khu qua Manager.
                        ctStep = CT_L_ANNOUNCE;
                        camThuatProgress("member bao KHU " + zoneCu + " DAY NGUOI (con thieu "
                                + missing.size() + " nguoi " + missing + ") -> doi sang khu "
                                + ctZoneCursor + " roi bao lai (lan nhay " + ctZoneHops
                                + "/" + maxHop + ")");
                        ctNextTime = now + zoneWait;
                        return;
                    }
                }

                if (complete) {
                    // CHỜ LẮNG trước khi khoá. Member vừa vào nhóm vẫn còn gói CMD 39 lặp đang bay
                    // tới (nó gửi lại theo nhịp cho tới khi thấy CMD 43 xác nhận). Nhóm còn mở thì
                    // server chỉ đáp "Bạn đã ở trong nhóm"; nhưng khoá rồi thì server kiểm groupLock
                    // TRƯỚC nhánh "đã ở trong nhóm", nên đúng gói đó biến thành LỜI XIN và đẩy popup
                    // lên màn trưởng nhóm dù người ấy đã ở trong nhóm.
                    if (ctFullSince == 0) {
                        ctFullSince = now;
                        ctNextTime = now + getSettingInt("cam_thuat_lock_delay_ms", 4000);
                        log("Cam thuat: du quan -> cho lang truoc khi khoa nhom");
                        return;
                    }

                    // Đủ quân thì KHOÁ nhóm lại: từ lúc này không ai chen vào được nữa, khỏi phải
                    // canh đuổi người lạ suốt phần còn lại của hoạt động.
                    // CMD 42 là lệnh ĐẢO trạng thái: gửi lặp sẽ mở khoá lại. Nên gửi ĐÚNG MỘT LẦN
                    // rồi chờ CMD 43 về; lần sau vào đây thì chốt luôn, báo đúng trạng thái đọc được.
                    if (getSettingInt("cam_thuat_lock_when_full", 1) == 1
                            && !isGroupLocked(g) && !ctLockSent) {
                        ctLockSent = true;
                        sendToggleGroupLock();
                        log("Cam thuat: du quan -> gui CMD 42 khoa nhom lai");
                        ctNextTime = now + groupWait;
                        return;
                    }

                    // Dọn popup "xin vào nhóm" còn sót. Sau khi đã khoá và đủ quân thì mọi lời xin
                    // đều thừa — kể cả lời xin do chính member của mình gửi lặp trước đó.
                    closeConfirmPopup();

                    // Nhóm đã khoá nên "tự cho vào nhóm" hết tác dụng — trả công tắc về nguyên
                    // trạng ngay, đừng để nó bật suốt cả lúc đánh trong hầm.
                    if (ctAutoAcceptChanged) {
                        setAutoAcceptGroup(ctPrevAutoAccept);
                        ctAutoAcceptChanged = false;
                        log("Cam thuat: tra 'tu cho vao nhom' ve " + ctPrevAutoAccept);
                    }

                    String groupDetail = "du " + have.size() + " nguoi " + have
                            + " tai khu " + getCurrentZoneId()
                            + (isGroupLocked(g) ? " [da khoa nhom]" : " [chua khoa duoc nhom]")
                            + (strangers.isEmpty() ? "" : " NHUNG con nguoi la " + strangers);
                    log("Cam thuat: GOM NHOM XONG - " + groupDetail);
                    pushCamThuat("cam_thuat_group", strangers.isEmpty(), groupDetail,
                            getCurrentMapId(), getCurrentZoneId(), "leader");

                    // Sang pha vào hầm. KHÔNG tự đi bấm NPC ngay: phải chờ Manager xác nhận mọi
                    // member đều đang đứng cùng map cùng khu, vì server chỉ kéo người CÙNG KHU
                    // mà lượt thì vẫn bị trừ dù có kéo được ai hay không.
                    // Đi tới NPC TRƯỚC rồi mới báo sẵn sàng: cả nhóm tập kết ngay chỗ NPC thì
                    // vừa chắc cùng khu, vừa khỏi phải chạy lại từ xa ở các lượt sau.
                    ctStep = CT_L_GOTO_NPC;
                    ctWalkTries = 0;
                    camThuatResetWalk();
                    ctMaxTurns = getSettingInt("cam_thuat_turns", 4);
                    ctDeadline = now + getSettingInt("cam_thuat_enter_timeout_ms", 120000);
                    ctNextTime = now + stepMs;
                    return;
                }

                if (now >= ctNextInvite) {
                    for (String name : missing) sendInviteByName(name);

                    // Báo LẠI điểm tập kết cùng nhịp. Member nào lúc trưởng nhóm báo lần đầu
                    // còn chưa kịp nhận lệnh vào vai (đang đi xuyên map, hoặc lệnh tới sau)
                    // thì đã bỏ lỡ khu và sẽ đứng chờ tới hết giờ. Phát lại là vô hại: member
                    // đã đứng đúng khu thấy khu không đổi nên không làm gì thêm.
                    int zone = getCurrentZoneId();
                    int map = getCurrentMapId();
                    String myName = have.isEmpty() ? "" : have.get(0);
                    pushCamThuatZone("van dang cho " + missing.size() + " member", myName);

                    ctNextInvite = now + getSettingInt("cam_thuat_invite_ms", 5000);
                    if (missing.isEmpty()) {
                        // Không thiếu ai trong danh sách được giao mà vẫn chưa đủ đầu người:
                        // phần còn lại là nick chưa vào game, chờ cũng không tự xuất hiện.
                        log("Cam thuat: moi " + have.size() + "/" + ctExpected
                                + " nguoi - so con lai chua vao game, van cho");
                    } else {
                        log("Cam thuat: con thieu " + missing + " -> gui CMD 41 moi lai + bao lai khu " + zone);
                    }
                }
                ctNextTime = now + getSettingInt("cam_thuat_wait_poll_ms", 1500);
                return;
            }

            // ─── TRƯỞNG NHÓM: chờ Manager chốt cả nhóm đã tập kết đủ rồi mới đi bấm NPC ───
            if (ctStep == CT_L_READY) {
                if (camThuatDetectPulledIn(now)) return;
                Object g = getGroupObj();
                if (hasNoGroup(g) || !isGroupLeader(g)) {
                    finishCamThuat(false, "mat quyen truong nhom truoc khi vao ham");
                    return;
                }
                // Báo LẠI điểm tập kết theo nhịp. Bắt buộc phải có ở đây chứ không chỉ ở bước
                // gom nhóm: từ lượt 2 trở đi, trưởng nhóm đi thẳng CT_IN_DUNGEON → CT_L_GOTO_NPC
                // → CT_L_READY, KHÔNG quay lại CT_L_ANNOUNCE/CT_L_WAIT nữa. Thiếu dòng này thì
                // member ra khỏi hầm xong sẽ ngồi chờ một khu không bao giờ được báo, tới khi
                // hết hạn 120s. Ra khỏi hầm server có thể xếp mỗi người một khu khác nên khu cũ
                // không dùng lại được.
                // SOI VỊ TRÍ TRƯỚC, BÁO KHU SAU — thứ tự này quan trọng.
                //
                // Trước đây báo khu ngay đầu bước, rồi mới soi lại vị trí. Nên trưởng nhóm vào
                // được bước này bằng một phép kiểm sai là nó BÁO KHU NGAY, member kéo nhau về
                // đúng khu đó — đúng lệnh, nhưng lệnh sai. Log 14:13 cho thấy nguyên văn:
                // "xong luot 1" (52s) → "đã báo 3 member" (53s) → "chưa tới chỗ NPC — lead" (53s).
                // Báo xong mới lộ ra là chưa tới nơi.
                int[] rxy = camThuatNpcXY(getCurrentMapId());
                if (!camThuatAtPoint(getPlayerX(), getPlayerY(), rxy)) {
                    camThuatProgress("o buoc san sang nhung KHONG con sat NPC -> di lai, CHUA bao khu. "
                            + camThuatWhereAmI());
                    ctStep = CT_L_GOTO_NPC;
                    ctNextTime = now + stepMs;
                    return;
                }

                // Đã đứng đúng chỗ → giờ mới báo điểm tập kết.
                // Bắt buộc phát lại theo nhịp chứ không chỉ ở bước gom nhóm: từ lượt 2 trở đi,
                // trưởng nhóm đi thẳng CT_IN_DUNGEON → CT_L_GOTO_NPC → CT_L_READY, không quay lại
                // CT_L_ANNOUNCE/CT_L_WAIT nữa. Thiếu dòng này thì member ra khỏi hầm sẽ ngồi chờ
                // một khu không bao giờ được báo. Ra khỏi hầm server có thể xếp mỗi người một khu
                // khác nên khu cũ không dùng lại được.
                java.util.List<String> myNames = getGroupMemberNames(g);
                pushCamThuatZone("diem tap ket cho luot ke tiep",
                        myNames.isEmpty() ? "" : myNames.get(0));

                camThuatDiag(now, "dung sat NPC, cho Manager chot ca nhom");

                // Báo vị trí theo nhịp; Manager gom vị trí của cả nhóm rồi mới gửi lệnh mở.
                pushCamThuatReady("leader", "san sang vao ham");
                ctNextTime = now + getSettingInt("cam_thuat_ready_poll_ms", 2000);
                return;
            }

            // Tìm NPC rồi đi tới. Toàn bộ đoạn này KHÔNG tốn lượt nào.
            if (ctStep == CT_L_GOTO_NPC) {
                if (camThuatDetectPulledIn(now)) return;
                String npcName = getSetting("cam_thuat_npc", "Onoki");
                int curMap = getCurrentMapId();
                int[] npc = findNpc(npcName, getSettingInt("cam_thuat_npc_id", 32));

                // NHỚ LẠI id đã tra được. Log lượt 2 chứng minh hàm tra NPC sống lúc được lúc
                // không: cùng lúc, cùng khu, member tra ra (80,108) còn trưởng nhóm CT-2 tra hụt
                // nên rơi về toạ độ config và bị coi là "chưa tới NPC" dù đang đứng ngay đó.
                // Có id rồi thì lần tra hụt sau không được phép làm hỏng cả lượt — mở NPC chỉ cần
                // id, không cần thấy đối tượng.
                if (npc != null) ctNpcId = npc[0];
                if (npc == null && ctNpcId > 0) {
                    log("Cam thuat: tra NPC hut nhung da co id " + ctNpcId + " -> dung lai id cu");
                    npc = new int[]{ctNpcId, -1, -1};
                }

                if (npc == null) {
                    int[] cfg = npcConfig.get("npc_cam_thuat_" + curMap);
                    if (cfg != null && ctWalkTries < getSettingInt("cam_thuat_walk_tries", 3)) {
                        ctWalkTries++;
                        navigateTo(curMap, cfg[1], cfg[2]);
                        log("Cam thuat: chua thay NPC '" + npcName + "' -> di toi toa do config ("
                                + cfg[1] + "," + cfg[2] + ") lan " + ctWalkTries);
                        ctNextTime = now + getSettingInt("cam_thuat_walk_wait_ms", 1500);
                        return;
                    }
                    dumpAllNpcsOnMap();
                    finishCamThuat(false, "khong thay NPC '" + npcName + "' tren map " + curMap);
                    return;
                }

                // Tách bạch hai thứ vốn bị gộp làm một:
                //   - ID lấy từ NPC đọc sống  → để gửi lệnh mở NPC
                //   - TOẠ ĐỘ lấy từ config    → để đứng tập kết, CÙNG điểm với member
                // Gộp chung thì trưởng nhóm và member nhắm hai điểm khác nhau, mà cổng chặn lại
                // đòi "tất cả sát NPC" ⇒ chờ mãi không bao giờ đủ.
                int[] xy = camThuatNpcXY(curMap);

                // DUNG SAI CHẶT KHI VỪA RA KHỎI HẦM.
                //
                // 60px là ngưỡng của CỔNG CHẶN 4 người (đứng quanh NPC là đủ để được kéo vào).
                // Nhưng ngay sau khi ra hầm, trưởng nhóm phải đứng ĐÚNG chỗ trước khi báo khu cho
                // member — 60px thì lệch nửa màn hình vẫn tính là "đã tới", và cái sai đó được
                // phát đi cho cả nhóm. Vì vậy pha chỉnh vị trí sau hầm dùng ngưỡng riêng, chặt hơn.
                //
                // Có TRẦN SỐ LẦN CHỈNH: đường đi của game không đảm bảo dừng đúng vào một ô cụ
                // thể, nên bám ngưỡng chặt vô hạn là tự khoá mình tới lúc hết hạn 120s. Quá trần
                // thì chấp nhận ngưỡng thường và NÓI RA, chứ không im lặng.
                // NGƯỠNG VỊ TRÍ LÀ TƯƠNG ĐỐI — lệch nhỏ không sao, không cần khớp tuyệt đối.
                //
                // `cam_thuat_lead_fix_px <= 0` = KHÔNG siết ngưỡng sau khi ra hầm, dùng luôn
                // ngưỡng thường. Đo được 19:35-19:36 ngày 31/07: pha siết 5px thất bại 8/8 lần,
                // tốn 12 giây, rồi chấp nhận đúng cái vị trí nó đã có từ nhịp đầu (lech dx=24
                // dy=28 — vốn đã nằm trong 60px). Bộ tìm đường của game không đảm bảo dừng vào
                // một ô cụ thể, nên 5px là ngưỡng không thể đạt: 12 giây đó là mất trắng, và mất
                // ở đúng chặng đang chạy đua với hạn giờ gom nhóm.
                int fixPx = getSettingInt("cam_thuat_lead_fix_px", 0);
                boolean fixing = ctAfterDungeon && fixPx > 0;
                int range = getSettingInt("cam_thuat_npc_range", 60);
                if (fixing) {
                    if (ctFixTries < getSettingInt("cam_thuat_lead_fix_tries", 8)) {
                        range = fixPx;
                    } else {
                        ctAfterDungeon = false;
                        camThuatProgress("chinh vi tri sau ham " + ctFixTries
                                + " lan van chua vao duoc " + fixPx
                                + "px -> chap nhan nguong thuong " + range + "px. " + camThuatWhereAmI());
                    }
                }

                // TRA HỤT TOẠ ĐỘ KHÔNG PHẢI LÀ "ĐÃ TỚI NƠI".
                // Chỗ này trước viết `if (xy != null && ngoài tầm) đi;` rồi rơi thẳng xuống
                // CT_L_READY — tức tra hụt thì trưởng nhóm TỰ CHO LÀ mình đứng đúng chỗ, trong
                // khi cổng chặn bên Manager dùng ĐÚNG hàm này và kết luận ngược lại
                // (atNpc = false vì xy == null). Hai nhánh chốt trái nhau trên cùng một dữ kiện
                // ⇒ đứng im báo cáo tới lúc hết hạn 120s rồi chết ở bước 6. "het gio o buoc 6"
                // chính là chữ ký của lỗi này: bước 6 là bước ĐÃ TƯỞNG tới nơi.
                if (xy == null) {
                    if (++ctNoXyTries <= getSettingInt("cam_thuat_npc_xy_tries", 5)) {
                        camThuatProgress("chua tra duoc toa do diem tap ket (lan " + ctNoXyTries
                                + ") - " + camThuatWhereAmI());
                        ctNextTime = now + getSettingInt("cam_thuat_walk_wait_ms", 1500);
                        return;
                    }
                    dumpAllNpcsOnMap();
                    finishCamThuat(false, "khong tra duoc toa do diem tap ket -> " + camThuatWhereAmI());
                    return;
                }
                ctNoXyTries = 0;

                // Pha siết (fixing) dùng ngưỡng vuông `range`; bình thường dùng ngưỡng tách
                // ngang/dọc của camThuatAtPoint.
                boolean toiNoi = fixing
                        ? (Math.abs(getPlayerX() - xy[0]) <= range
                           && Math.abs(getPlayerY() - xy[1]) <= range)
                        : camThuatAtPoint(getPlayerX(), getPlayerY(), xy);
                if (!toiNoi) {
                    if (fixing) ctFixTries++;
                    camThuatWalkStep(now, curMap, xy,
                            fixing ? "truong nhom (chinh vi tri sau ham, nguong " + range + "px)"
                                   : "truong nhom");
                    ctNextTime = now + getSettingInt("cam_thuat_walk_wait_ms", 1500);
                    return;
                }
                if (ctAfterDungeon) {
                    ctAfterDungeon = false;
                    camThuatProgress("da chinh khop vi tri sau ham (trong " + range
                            + "px) -> bay gio moi bao khu cho member. " + camThuatWhereAmI());
                }
                log("Cam thuat: da toi diem tap ket - " + camThuatWhereAmI());
                ctStep = CT_L_READY;
                ctNextTime = now + stepMs;
                return;
            }

            if (ctStep == CT_L_OPEN_NPC) {
                closeAnyDialog();
                sendOpenNpc(ctNpcId);
                ctParentMenu = null;
                log("Cam thuat: mo NPC id " + ctNpcId);
                ctStep = CT_L_MENU;
                ctNextTime = now + getSettingInt("cam_thuat_npc_wait_ms", 600);
                return;
            }

            // Đọc danh sách hành động server trả về rồi chọn mục cấm thuật.
            // Ở đây CHỮ mới là chuẩn chính, index chỉ là dự phòng — ngược với Địa cung. Lý do:
            // bấm nhầm ở đây MẤT MỘT LƯỢT trong ngày, mà thứ tự menu của server thật đã lệch so
            // với bản mẫu (bản mẫu để "Cấm thuật Izanami" ở vị trí 3, server thật ở vị trí 2).
            // Từ khoá dùng "izanami": không dấu nên không dính bẫy đặt dấu như "khoá"/"khóa".
            if (ctStep == CT_L_MENU) {
                if (detectDialog() == null) {
                    ctNextTime = now + getSettingInt("cam_thuat_dialog_poll_ms", 300);
                    return;
                }
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) {
                    ctNextTime = now + getSettingInt("cam_thuat_dialog_poll_ms", 300);
                    return;
                }
                ctParentMenu = menu;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < menu.length; i++) {
                    sb.append("  [").append(i).append("] ").append(menu[i]).append("\n");
                }
                log("Cam thuat: menu NPC:\n" + sb);
                camThuatProgress("menu NPC: " + java.util.Arrays.toString(menu));

                // Tìm mục cấm thuật. CHỮ là chuẩn chính, index chỉ là lưới đỡ — menu server thật
                // để mục này ở vị trí 2 còn bản mẫu ghi 3, mà bấm nhầm ở đây là mất một lượt.
                // Từ khoá "izanami" không dấu nên không dính bẫy đặt dấu kiểu "khoá"/"khóa".
                String kw = getSetting("cam_thuat_npc_keyword", "izanami");
                int idx = findMenuIndexByKeyword(menu, kw);
                int cfgIdx = getSettingInt("cam_thuat_npc_index", -1);
                if (idx < 0) {
                    if (cfgIdx >= 0 && cfgIdx < menu.length) {
                        idx = cfgIdx;
                        log("Cam thuat: khong thay tu khoa '" + kw + "' -> dung index cau hinh " + idx);
                    } else {
                        finishCamThuat(false, "khong tim thay muc cam thuat trong menu NPC (tu khoa '"
                                + kw + "', index cau hinh " + cfgIdx + ") | menu: "
                                + java.util.Arrays.toString(menu));
                        return;
                    }
                } else if (cfgIdx >= 0 && cfgIdx != idx) {
                    log("Cam thuat: index cau hinh " + cfgIdx + " lech voi vi tri thuc " + idx
                            + " -> lay theo chu server tra ve");
                }

                // ══ ĐÂY LÀ CÚ BẤM TỐN LƯỢT ══
                // Bấm mục này là VÀO THẲNG map cấm thuật, KHÔNG có bước xác nhận nào nữa. Nên mọi
                // điều kiện phải xong trước dòng này. Manager đã chặn ở cổng (đủ quân, sạch người
                // lạ, cùng map/khu, đứng sát NPC); ở đây kiểm lại lần cuối những gì đọc được tại
                // chỗ, phòng có người rời nhóm trong lúc đi tới NPC.
                Object gNow = getGroupObj();
                if (hasNoGroup(gNow) || !isGroupLeader(gNow)) {
                    finishCamThuat(false, "sap bam thi mat quyen truong nhom -> khong bam, giu lai luot");
                    return;
                }
                java.util.List<String> nowHave = getGroupMemberNames(gNow);
                if (ctExpected > 0 && nowHave.size() < ctExpected) {
                    finishCamThuat(false, "sap bam thi nhom tut con " + nowHave.size() + "/" + ctExpected
                            + " " + nowHave + " -> khong bam, giu lai luot");
                    return;
                }

                if (getSettingInt("cam_thuat_dry_run", 1) == 1) {
                    // CHẠY NHÁP: dừng ngay trước cú bấm. Không tốn lượt nào, chạy bao nhiêu lần cũng được.
                    closeCurrentDialog();
                    pushCamThuat("cam_thuat_dry", true,
                            "se bam [" + idx + "] " + menu[idx] + " | menu NPC "
                                    + java.util.Arrays.toString(menu)
                                    + " | nhom " + nowHave,
                            getCurrentMapId(), getCurrentZoneId(), "leader");
                    finishCamThuat(true, "CHAY NHAP - dung truoc cu bam, khong ton luot. Se bam ["
                            + idx + "] " + menu[idx]
                            + ". Doi cam_thuat_dry_run thanh 0 de bam that.");
                    return;
                }

                ctMapBefore = getCurrentMapId();
                sendSelectMenu(ctNpcId, idx);
                camThuatProgress("da bam [" + idx + "] " + menu[idx] + " (nhom " + nowHave.size()
                        + " nguoi) - dang cho vao ham");
                ctStep = CT_L_VERIFY;
                ctVerifyWaits = 0;
                ctNextTime = now + getSettingInt("cam_thuat_enter_wait_ms", 1500);
                return;
            }

            // Bằng chứng vào được hầm là MAP ĐỔI. Không tin thông báo của server: server báo lỗi
            // sau khi đã trừ lượt, còn map đổi thì chỉ xảy ra khi thật sự vào.
            if (ctStep == CT_L_VERIFY) {
                int nowMap = getCurrentMapId();
                if (nowMap != ctMapBefore) {
                    // Map đổi là bằng chứng đã vào. Đối chiếu thêm với map cấm thuật đã biết
                    // (89 = Vòng Lặp Ảo Tưởng) — KHÔNG bắt buộc phải khớp, chỉ để lộ ra ngay nếu
                    // bị đưa tới chỗ khác; lượt đã trừ rồi nên dừng lại lúc này cũng chẳng cứu được gì.
                    int wantMap = getSettingInt("cam_thuat_dungeon_map", 89);
                    if (wantMap > 0 && nowMap != wantMap) {
                        camThuatProgress("CANH BAO: vao map " + nowMap + " chu khong phai map cam thuat "
                                + wantMap + " - kiem lai cam_thuat_dungeon_map trong cfg");
                    }
                    ctDungeonMap = nowMap;
                    ctStep = CT_IN_DUNGEON;
                    ctDeadline = dungeonDeadline(now);
                    if (getSettingInt("cam_thuat_combat", 1) == 1) {
                        clearNavTarget();      // bỏ đích di chuyển cũ, không thì đi bộ thay vì đánh
                        setAutoCombat(true);
                        autoCombatRequested = true;
                    }
                    camThuatProgress("DA VAO HAM (map " + nowMap + ")");
                    pushCamThuatDungeonMark(true, nowMap);
                    ctNextTime = now + getSettingInt("cam_thuat_poll_ms", 3000);
                    return;
                }
                // Server thật vào thẳng, không có bước xác nhận. Nhưng nếu gặp bản có menu con
                // thì bấm nốt cho xong: lượt đã bị trừ từ cú bấm trước rồi, bấm thêm không tốn
                // gì mà cứu được cả lượt — bỏ dở mới là mất trắng.
                String[] extraMenu = readDialogMenuItems();
                if (extraMenu != null && extraMenu.length > 0
                        && !java.util.Arrays.equals(extraMenu, ctParentMenu)) {
                    int j = findMenuIndexByKeyword(extraMenu,
                            getSetting("cam_thuat_join_keyword", "tham gia"));
                    if (j < 0) {
                        int cfgJ = getSettingInt("cam_thuat_join_index", -1);
                        if (cfgJ >= 0 && cfgJ < extraMenu.length) j = cfgJ;
                    }
                    if (j >= 0) {
                        sendSelectMenu(ctNpcId, j);
                        camThuatProgress("hien ra menu xac nhan -> bam not [" + j + "] " + extraMenu[j]);
                        ctVerifyWaits = 0;
                        ctNextTime = now + getSettingInt("cam_thuat_enter_wait_ms", 1500);
                        return;
                    }
                }

                // TÍN HIỆU DỪNG LÀ CÂU TRẢ LỜI CỦA SERVER, KHÔNG PHẢI SỐ LƯỢT ĐÃ ĐI.
                // Đọc popup ngay mỗi nhịp: thấy đúng câu "không đủ lượt" là chốt luôn, khỏi phải
                // ngồi hết 6 nhịp chờ. Nhận ra rồi thì báo member TRƯỚC, rồi mới rời nhóm và đi
                // treo — để member bắt đầu dọn cùng lúc chứ không phải sau.
                String dlg = readAnyDialogText();
                if (dlg != null && !dlg.isEmpty()
                        && noAccent(dlg).contains(noAccent(
                                getSetting("cam_thuat_het_luot_keyword", "khong du luot")))) {
                    closeAnyDialog();
                    finishCamThuat(true, "server bao HET LUOT -> dung, giai tan nhom va di treo."
                            + " Server bao: " + dlg);
                    return;
                }

                if (++ctVerifyWaits > getSettingInt("cam_thuat_verify_tries", 6)) {
                    // Bấm mà không vào được: server đẩy popup nói lý do (thường là có người
                    // trong nhóm hết lượt). Đọc nguyên văn đưa về Manager thay vì đoán.
                    String msg = readAnyDialogText();
                    closeAnyDialog();
                    // TUYỆT ĐỐI KHÔNG thử lại: server trừ lượt trước khi kiểm, mỗi lần bấm hỏng
                    // là mất thêm một lượt của những người còn lượt.
                    //
                    // KHÔNG DÙNG SỐ LƯỢT ĐÃ ĐI để phân loại. Trước đây chỗ này xét
                    // `ctTurnsDone > 0` — đi rồi mới trượt thì coi là hết lượt, trượt ngay lượt
                    // đầu thì coi là hỏng. Nhưng số lượt của tool không nói lên gì: một ngày
                    // thường chỉ có 1 lượt, dùng vật phẩm mới thêm được, và tool không có cách
                    // nào biết nick còn mấy lượt. Chỉ SERVER biết, và nó đã trả lời bằng popup —
                    // câu đó bắt ở nhánh trên rồi. Xuống tới đây nghĩa là KHÔNG đọc được câu nào,
                    // nên phân loại theo "có đọc được chữ gì không", không theo số đếm.
                    if (msg != null && !msg.isEmpty()) {
                        finishCamThuat(true, "bam roi nhung khong vao duoc -> dung, giai tan nhom"
                                + " va di treo. Server bao: " + msg);
                    } else {
                        finishCamThuat(false, "bam roi nhung map van la " + ctMapBefore
                                + " va KHONG doc duoc thong bao nao cua server - dung han,"
                                + " KHONG thu lai. " + camThuatWhereAmI());
                    }
                    return;
                }
                ctNextTime = now + getSettingInt("cam_thuat_verify_ms", 1000);
                return;
            }

            // ─── THÀNH VIÊN: đứng yên chờ trưởng nhóm kéo vào ───
            if (ctStep == CT_M_STANDBY) {
                // Member cũng có thể bị ném vào map lạ (không phải hầm) vì cùng lý do
                // idZoneCustom còn sót — dùng chung một cách nhận biết với trưởng nhóm.
                if (camThuatDetectPulledIn(now)) return;
                int nowMap = getCurrentMapId();

                // Đứng SÁT NPC cùng trưởng nhóm — đứng xa là không được kéo vào.
                // Vẫn báo về Manager ở MỌI vòng (kèm cờ atNpc) chứ không im lặng khi còn đang đi:
                // im lặng thì nick kẹt đường sẽ biến mất khỏi log, không ai biết vì sao nhóm treo.
                if (nowMap == ctMapBefore) {
                    // MỘT THƯỚC DUY NHẤT — xem chú thích dài ở camThuatTargetXY.
                    // Trước đây chỗ này ưu tiên toạ độ thật của trưởng nhóm, còn cờ atNpc lại đo
                    // theo config. Hai thước lệch nhau là member đứng im mà cổng không mở, và
                    // không có dòng log nào nói vì sao — đúng cái đã giết lượt 3 của CT-1.
                    int[] xy = camThuatTargetXY(nowMap);
                    if (xy == null) {
                        // Cùng cái bẫy như bên trưởng nhóm: tra hụt toạ độ thì cổng chặn nhận
                        // atNpc = false, còn member đứng im vì không biết đi đâu — im lặng cho
                        // tới hết giờ, log chỉ còn mỗi cái tên. Phải nói ra con số.
                        camThuatDiag(now, "KHONG tra duoc toa do diem tap ket, dung im");
                        pushCamThuatReady("member", "chua biet diem tap ket o dau");
                        ctNextTime = now + getSettingInt("cam_thuat_walk_wait_ms", 1500);
                        return;
                    }
                    if (!camThuatAtPoint(getPlayerX(), getPlayerY(), xy)) {
                        camThuatWalkStep(now, nowMap, xy, "member");
                        pushCamThuatReady("member", "dang di toi cho NPC");
                        ctNextTime = now + getSettingInt("cam_thuat_walk_wait_ms", 1500);
                        return;
                    }
                }

                // NHỊP BÁO LÚC ĐỨNG CHỜ — đừng để member im lặng.
                // Lượt CT-1 lúc 09:52 ngày 01/08: ba member đứng im 116 giây tới lúc trưởng nhóm
                // hết hạn mà KHÔNG sinh nổi một dòng nào, vì chúng đã ra khỏi nhánh đi (chỗ duy
                // nhất có log). Manager thì lọc trùng nên cũng im. Cả hai bên im cùng lúc là mất
                // sạch dấu vết của một lượt hỏng — phải đọc ngược từ toạ độ mới lần ra được.
                camThuatDiag(now, "member: da toi noi, dang cho duoc keo vao ham");
                pushCamThuatReady("member", "dang cho duoc keo vao ham");
                if (nowMap != ctMapBefore) {
                    ctDungeonMap = nowMap;
                    ctStep = CT_IN_DUNGEON;
                    ctDeadline = dungeonDeadline(now);
                    if (getSettingInt("cam_thuat_combat", 1) == 1) {
                        clearNavTarget();
                        setAutoCombat(true);
                        autoCombatRequested = true;
                    }
                    camThuatProgress("DA DUOC KEO VAO HAM (map " + nowMap + ")");
                    pushCamThuatDungeonMark(true, nowMap);
                    ctNextTime = now + getSettingInt("cam_thuat_poll_ms", 3000);
                    return;
                }
                ctNextTime = now + getSettingInt("cam_thuat_ready_poll_ms", 2000);
                return;
            }

            // ─── Cả hai vai: đang trong hầm ───
            if (ctStep == CT_IN_DUNGEON) {
                int nowMap = getCurrentMapId();
                if (nowMap == ctDungeonMap) {
                    if (getSettingInt("cam_thuat_combat", 1) == 1 && !isAutoCombatOn()) {
                        clearNavTarget();
                        setAutoCombat(true);
                        log("Cam thuat: auto combat bi tat -> bat lai");
                    }
                    ctNextTime = now + getSettingInt("cam_thuat_poll_ms", 3000);
                    return;
                }

                // RA KHỎI HẦM = xong một lượt. Đây là tín hiệu kết thúc DUY NHẤT đáng tin: một
                // lượt chạy tới khi thắng 18 vòng nên thời gian không cố định, phụ thuộc sát
                // thương của cả nhóm. Hạn giờ bên dưới chỉ là lưới đỡ phòng treo, không phải mốc.
                //
                // Rời map hầm mà KHÔNG về làng thì vẫn tính là xong lượt (bỏ lỡ thì kẹt vĩnh viễn
                // vì trong hầm không đặt hạn giờ), nhưng phải kêu lên — đó hoặc là map lạ, hoặc là
                // một lần đọc map hụt đang bị tính thành một lượt.
                int villageMap = camThuatMap();
                if (villageMap > 0 && nowMap != villageMap) {
                    camThuatProgress("CANH BAO: roi map ham sang map " + nowMap
                            + " chu khong phai map lang " + villageMap + " - van tinh la xong luot");
                }
                // ĐÓNG PHIÊN BÁM THEO NGAY TẠI ĐÂY, TRƯỚC KHI TẮT ĐÁNH.
                // Không chờ follow_stop từ Manager: đó là một vòng đi-về qua mạng, còn tickFollow
                // chạy mỗi 250-1500ms ngay tại máy — trong khoảng đó nó thấy đánh vừa bị tắt và
                // BẬT LẠI, rồi nhân vật đứng đánh quái ở làng thay vì đi về điểm tập kết.
                // Đo được 19:36 ngày 31/07: 2/4 nick bị kéo ra (500,514)/(455,514) trong khi điểm
                // tập kết là (418,514) ⇒ cả nhóm lỡ lượt kế tiếp.
                if (flStep > 0) {
                    resetFollow();
                    log("Cam thuat: ra khoi ham -> dong phien bam theo tai cho (khong cho Manager)");
                }
                setAutoCombat(false);
                clearCombatTarget();   // tắt cờ thôi chưa đủ: z.a còn giữ con quái trong hầm
                autoCombatRequested = false;
                // Vẫn báo Manager để nó gỡ BẢNG TUYẾN của nhóm — phần trên chỉ dọn phía nick này.
                pushCamThuatDungeonMark(false, ctDungeonMap);
                ctTurnsDone++;
                // KHÔNG lấy số đếm của tool làm chuẩn dừng. Bình thường mỗi ngày chỉ 1 lượt,
                // dùng vật phẩm mới thêm được — tool không có cách nào biết nick còn mấy lượt.
                // Chỉ SERVER biết, và nó trả lời bằng cách cho vào hầm hay không. Nên cứ gom
                // nhóm bấm tiếp; hết lượt thì cú bấm sau sẽ trượt và đó mới là tín hiệu dừng.
                // cam_thuat_turns chỉ còn là TRẦN AN TOÀN phòng vòng lặp chạy hoang (0 = không trần).
                int turnCap = getSettingInt("cam_thuat_turns", 0);
                ctMaxTurns = turnCap;
                String where = "xong luot " + ctTurnsDone
                        + (turnCap > 0 ? "/" + turnCap : "") + ", dang o map " + nowMap;
                camThuatProgress(where);

                if (turnCap <= 0 || ctTurnsDone < turnCap) {
                    ctDungeonMap = -1;
                    ctVerifyWaits = 0;
                    ctParentMenu = null;
                    ctDeadline = now + getSettingInt("cam_thuat_enter_timeout_ms", 120000);
                    if (ctRole == 1) {
                        // Trưởng nhóm gom lại: về chỗ NPC, báo khu mới rồi chờ Manager chốt lại.
                        // Sau khi ra hầm server có thể xếp mỗi người một khu khác nên phải đồng
                        // bộ lại từ đầu, không dùng lại khu của lượt trước.
                        // CHỈ LẠI ĐÍCH, KHÔNG TẮT ĐỘNG CƠ.
                        //
                        // Chỗ này trước gọi clearNavTarget() — đặt z.ap = false, tức TẮT auto-nav
                        // gốc ngay trước bước phải đi xa. Đây là hành động CHỈ TRƯỞNG NHÓM mới
                        // làm: member ra khỏi hầm đi qua CT_M_GOTO_MAP/GOTO_ZONE nên động cơ vẫn
                        // chạy. Và triệu chứng quan sát được khớp với việc tắt động cơ: cùng một
                        // điểm xuất phát, cùng một lệnh đi, member tới nơi còn trưởng nhóm ĐƠ.
                        //
                        // Mục đích ban đầu là bỏ đích còn sót từ trong hầm — nhưng chỉ thẳng sang
                        // đích mới cũng bỏ được đích cũ, mà không phải tắt gì. Chưa chứng minh
                        // được đây là nguyên nhân; nhưng giữa "tắt rồi mong nó tự đi" và "chỉ
                        // thẳng chỗ cần tới" thì cái sau không có mặt trái nào.
                        int[] gather = npcConfig.get("npc_cam_thuat_" + nowMap);
                        if (gather != null) {
                            navigateToMapXY(nowMap, gather[1], gather[2]);
                        } else {
                            clearNavTarget();
                        }
                        ctStep = CT_L_GOTO_NPC;
                        ctWalkTries = 0;
                        camThuatResetWalk();
                        // CHỈ pha "vừa ra khỏi hầm" mới bật cờ này. Lượt đầu (bấm nút) không cần:
                        // lúc đó nhân vật đi từ map khác về, không có chuyện bị thả ra ở một chỗ
                        // rồi tưởng mình đã tới nơi.
                        ctAfterDungeon = true;
                        ctFixTries = 0;
                        pushCamThuat("cam_thuat_turn", true, "xong luot " + ctTurnsDone,
                                nowMap, getCurrentZoneId(), "leader");
                        // CHỜ VỊ TRÍ ỔN ĐỊNH RỒI MỚI ĐỌC.
                        // Trưởng nhóm là nick DUY NHẤT đọc toạ độ ngay sát khoảnh khắc đổi map:
                        // member còn phải đi GOTO_MAP → WAIT_ZONE → GOTO_ZONE (đổi khu) → JOIN
                        // mất vài giây, còn trưởng nhóm nhảy thẳng vào bước đi tới NPC sau đúng
                        // stepMs (200ms). Ở khoảnh khắc đó getPlayerX/Y còn có thể trả TOẠ ĐỘ CŨ
                        // TRONG HẦM; nếu số cũ đó tình cờ nằm trong bán kính cho phép thì trưởng
                        // nhóm "qua" phép kiểm ngay nhịp đầu và nhảy sang bước chờ — rồi đứng đó
                        // báo atNpc=false tới lúc hết hạn. Đúng triệu chứng CT-1/CT-2 lượt 2.
                        ctNextTime = now + getSettingInt("cam_thuat_settle_ms", 3000);
                        return;
                    } else {
                        // Member: quên khu cũ, chờ trưởng nhóm báo khu mới rồi bám theo.
                        ctStep = CT_M_GOTO_MAP;
                        ctWantZone = -1;
                        // Quên luôn toạ độ lượt trước: ra khỏi hầm trưởng nhóm đứng chỗ khác, bám
                        // số cũ là kéo nhau tới một điểm không còn ai ở đó.
                        ctWantX = -1;
                        ctWantY = -1;
                        ctZonePending = false;
                        ctZoneWaits = 0;
                        ctJoinTries = 0;
                        ctNextJoinSend = 0;
                        camThuatResetWalk();
                        // Member CŨNG đang ở pha "vừa ra khỏi hầm": bước kế tiếp là bước dễ phát
                        // nhầm lệnh đi xuyên map nhất, phải chờ đọc ra map thay vì đi liền.
                        ctAfterDungeon = true;
                        ctMapWaits = 0;
                    }
                    // Member cũng phải CHỜ VỊ TRÍ + MAP ỔN ĐỊNH như trưởng nhóm, và ở đây lý do
                    // còn nặng hơn: bước kế tiếp (CT_M_GOTO_MAP) so `curMap != wantMap` rồi phát
                    // lệnh đi xuyên map. Đọc trúng map cũ ở đúng khoảnh khắc đổi map là lệnh đó
                    // bay đi trong khi nhân vật đã ở map mới ⇒ bị kéo về góc map.
                    ctNextTime = now + getSettingInt("cam_thuat_settle_ms", 3000);
                    return;
                }

                if (afkMapId > 0 && getSettingInt("cam_thuat_after_afk", 1) == 1) {
                    afkZoneChanged = false;
                    autoCombatRequested = false;
                    setEnabled(true);
                    setState(TaskState.AFK_FARM);
                    finishCamThuat(true, where + " -> cham tran " + turnCap
                            + " luot, chuyen sang treo map " + afkMapId + " khu " + afkZone);
                } else {
                    finishCamThuat(true, where + " -> cham tran " + turnCap
                            + " luot (chua cau hinh map treo nen dung yen)");
                }
                return;
            }

            // ─────────────── THÀNH VIÊN ───────────────

            // Chờ Manager báo khu của trưởng nhóm. Không tự đoán khu: trưởng nhóm có thể
            // phải nhảy khu vì khu đầy nhóm, đoán sai là đứng nhầm chỗ cả phiên.
            if (ctStep == CT_M_WAIT_ZONE) {
                if (ctWantZone >= 0) {
                    ctStep = CT_M_GOTO_ZONE;
                    ctNextTime = now + stepMs;
                    return;
                }
                ctNextTime = now + getSettingInt("cam_thuat_wait_poll_ms", 1500);
                return;
            }

            if (ctStep == CT_M_GOTO_ZONE) {
                int wantMap = camThuatMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    // Trưởng nhóm có thể đứng ở map khác làng — bám theo map đó luôn.
                    camThuatGotoMap(wantMap, curMap, "bam theo truong nhom");
                    ctNextTime = now + mapWait;
                    return;
                }

                int zone = getCurrentZoneId();
                if (zone == ctWantZone) {
                    ctZonePending = false;
                    ctZoneWaits = 0;
                    ctStep = CT_M_JOIN;
                    // GIÃN LƯỢT XIN: cả nhóm nhận lệnh cùng lúc nên nếu ai cũng bắn CMD 39 ngay
                    // thì server nhận cả chùm trong cùng một khoảnh khắc. Mỗi member lệch nhau
                    // theo số thứ tự Manager gán (0,1,2...) — lệch cố định, không dùng ngẫu nhiên
                    // để log còn đọc được và tái hiện được khi cần soi lỗi.
                    int stagger = getSettingInt("cam_thuat_join_stagger_ms", 1500);
                    ctNextJoinSend = now + (long) ctSlot * stagger;
                    if (ctSlot > 0) {
                        log("Cam thuat: cho " + (ctSlot * stagger) + "ms cho lech luot xin (slot "
                                + ctSlot + ")");
                    }
                    ctNextTime = now + stepMs;
                    return;
                }

                // CÒN KHOÁ 15s THÌ CHƯA ĐƯỢC KẾT LUẬN GÌ — xem chú thích ở zoneCooldownLeft.
                // Không có chốt này, member đếm 3 nhịp × 2500ms rồi bỏ cuộc ở giây thứ 7.5, tức
                // BỎ CUỘC TRONG LÚC GAME CÒN KHOÁ, chưa từng có cơ hội chen vào khu.
                long khoaM = zoneCooldownLeft(now);
                if (ctZonePending && khoaM > 0) {
                    ctNextTime = now + khoaM + 250;
                    return;
                }
                if (ctZonePending && ++ctZoneWaits <= getSettingInt("cam_thuat_zone_wait_tries", 3)) {
                    ctNextTime = now + zoneWait;   // lệnh đổi khu chưa ăn, chờ thêm
                    return;
                }
                if (ctZoneWaits > getSettingInt("cam_thuat_zone_wait_tries", 3)) {
                    // KHU CỦA TRƯỞNG NHÓM ĐẦY NGƯỜI (15 người/khu) — member không chen vào được.
                    //
                    // ĐÂY KHÔNG PHẢI LỖI CỦA MEMBER, nên không được để member bỏ cuộc: bỏ cuộc ở
                    // đây là mất lượt của CẢ NHÓM, mà cái cần đổi là chỗ đứng của TRƯỞNG NHÓM.
                    // Member chỉ báo về Manager rồi tiếp tục thử; trưởng nhóm thấy nhóm không đầy
                    // sau `cam_thuat_crowd_wait_ms` thì tự dời sang khu khác và báo lại khu mới
                    // (xem nhánh CT_L_WAIT). Khu mới tới thì `setCamThuatZone` đưa member về đúng
                    // bước này để đi theo.
                    ctZoneWaits = 0;
                    ctZonePending = false;
                    pushZoneFull("cam_thuat_zone_full", ctWantZone,
                            "khong chen duoc vao khu " + ctWantZone + " cua truong nhom (dang o khu "
                            + zone + ") - khu do dang day nguoi");
                    // Thử lại theo NHỊP KHOÁ của game, không theo nhịp soi 1.5s: khu có thể trống ra
                    // khi có người rời đi, nên vẫn phải thử; nhưng thử dày hơn khoá thì mỗi lần chỉ
                    // sinh thêm một dòng "còn Xs mới gửi được" chứ không sớm hơn được một giây nào.
                    long lai = zoneCooldownLeft(now);
                    ctNextTime = now + (lai > 0 ? lai + 250
                                                : getSettingInt("cam_thuat_wait_poll_ms", 1500));
                    return;
                }
                sendChangeZone(ctWantZone);
                ctZonePending = true;
                ctZoneWaits = 0;
                log("Cam thuat: dang o khu " + zone + " -> doi sang khu " + ctWantZone + " cua truong nhom");
                ctNextTime = now + zoneWait;
                return;
            }

            // Gửi CMD 39 kèm TÊN TRƯỞNG NHÓM. Nhóm đã mở khoá thì server cho vào thẳng.
            if (ctStep == CT_M_JOIN) {
                Object g = getGroupObj();

                if (groupHasMember(g, ctLeaderName)) {
                    java.util.List<String> names = getGroupMemberNames(g);
                    // Lời mời của trưởng nhóm (CMD 41) đẩy lên một popup hỏi đồng ý. Vào nhóm
                    // bằng CMD 39 thì popup đó KHÔNG tự tắt, nằm lại chặn thao tác về sau.
                    closeConfirmPopup();
                    String d = "da vao nhom cua '" + ctLeaderName + "' (" + names.size()
                            + " nguoi) tai khu " + getCurrentZoneId();
                    log("Cam thuat: " + d);
                    pushCamThuat("cam_thuat_group", true, d, getCurrentMapId(), getCurrentZoneId(), "member");
                    // Vào được nhóm rồi thì phần còn lại chỉ là chờ trưởng nhóm bấm NPC. Nới hạn
                    // ở đây vì bước chờ đó dài ngắn tuỳ trưởng nhóm, không tuỳ member: nó có thể
                    // còn phải nhảy khu thêm mấy lần để đón nốt người cuối.
                    giaHanCamThuat("da vao nhom, cho truong nhom mo ham");

                    // ĐỨNG YÊN chờ được kéo vào hầm. Member TUYỆT ĐỐI không bấm NPC: cú bấm của
                    // trưởng nhóm đã kéo cả nhóm cùng khu vào, member bấm thêm chỉ tốn lượt
                    // riêng của mình (soLanCamThuat trừ theo từng người bấm).
                    ctStep = CT_M_STANDBY;
                    ctMapBefore = getCurrentMapId();
                    ctDeadline = now + getSettingInt("cam_thuat_enter_timeout_ms", 120000);
                    ctNextTime = now + stepMs;
                    return;
                }

                // Đang ở nhóm khác (nhóm cũ, hoặc bị người lạ kéo vào) → rời ra rồi mới xin vào.
                if (!hasNoGroup(g)) {
                    java.util.List<String> names = getGroupMemberNames(g);
                    sendLeaveGroup();
                    log("Cam thuat: dang o nhom cua " + (names.isEmpty() ? "?" : names.get(0))
                            + " (khong phai '" + ctLeaderName + "') -> roi nhom");
                    ctNextTime = now + groupWait;
                    return;
                }

                // Tách hai nhịp: SOI danh sách nhóm là đọc bộ nhớ tại chỗ nên soi dày được;
                // GỬI LẠI CMD 39 là gói lên server nên phải thưa. Trước đây dùng chung một nhịp
                // nên muốn phát hiện nhanh thì buộc phải bắn gói dày — thành ra spam server.
                if (now >= ctNextJoinSend) {
                    if (ctJoinTries >= getSettingInt("cam_thuat_join_tries", 8)) {
                        finishCamThuat(false, "gui CMD 39 " + ctJoinTries + " lan van chua vao duoc nhom cua '"
                                + ctLeaderName + "' - nhom co the dang khoa hoac da du 10 nguoi");
                        return;
                    }
                    ctJoinTries++;
                    sendJoinByName(ctLeaderName);
                    ctNextJoinSend = now + joinWait;
                    // Đẩy về Manager kèm map/khu: phải gửi lại nhiều lần là dấu hiệu có gì đó
                    // chặn, mà log client nằm rải ở từng cửa sổ game nên rất khó đối chiếu thời
                    // điểm. Từ lần thứ 2 mới đẩy, để lần vào ngay không làm bẩn log.
                    String where = "map " + getCurrentMapId() + " khu " + getCurrentZoneId();
                    log("Cam thuat: gui CMD 39 xin vao nhom '" + ctLeaderName + "' (lan " + ctJoinTries
                            + ", " + where + ")");
                    if (ctJoinTries >= 2) {
                        camThuatProgress("xin vao nhom '" + ctLeaderName + "' lan " + ctJoinTries
                                + " (" + where + ")");
                    }
                }
                ctNextTime = now + getSettingInt("cam_thuat_join_poll_ms", 500);
                return;
            }
        } catch (Exception e) {
            finishCamThuat(false, "loi: " + e.getMessage());
        }
    }


    /**
     * Kiểm tra có quái đặc biệt (tinh anh/thủ lĩnh) còn sống trong map không.
     * Quái đặc biệt: aY > 10 (damage multiplier) hoặc isBoss flag.
     * Quái sống: v != 4/5/6 (không phải trạng thái chết).
     */
    @SuppressWarnings("unchecked")
    private boolean hasSpecialMobAlive() {
        try {
            if (zFieldF == null) return false;
            Object zInstance = getZ();
            java.util.Vector<Object> npcs = (java.util.Vector<Object>) zFieldF.get(zInstance);
            if (npcs == null || npcs.isEmpty()) return false;

            // Field v (byte, trạng thái) và aY (int, level/damage multiplier) trên class x
            Field vField = null;
            Field ayField = null;
            Class<?> xClass = Class.forName("a.x");
            for (Field f : xClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getName().equals("v") && f.getType() == byte.class) vField = f;
                if (f.getName().equals("aY") && f.getType() == int.class) ayField = f;
            }
            if (vField == null) return false;

            for (Object npc : npcs) {
                if (npc == null) continue;
                byte v = vField.getByte(npc);
                // v == 4/5/6 → chết, bỏ qua
                if (v == 4 || v == 5 || v == 6) continue;
                // Quái đặc biệt: aY > 10
                if (ayField != null) {
                    int ay = ayField.getInt(npc);
                    if (ay > 10) return true;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // DIALOG DETECTION (Reflection-based)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Phát hiện dialog NPC đang hiển thị trên màn hình.
     * 
     * @return int[] {dialogType, stackSize} hoặc null nếu không có dialog
     *   dialogType: >=0 → dialog mở NPC (giá trị = npcEntityId)
     *              -2  → dialog OpenMenu (xác nhận/sub-menu)
     *              -3/-4 → dialog đặc biệt khác
     *   stackSize: kích thước stack dialog
     */
    private int[] detectDialog() {
        if (fkFieldAn == null || auClass == null || auFieldAs == null) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            
            java.util.Vector<?> dialogStack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (dialogStack == null || dialogStack.isEmpty()) return null;
            
            int stackSize = dialogStack.size();
            
            // Duyệt từ TOP xuống tìm au dialog (NPC dialog có thể không ở top khi có quest panel)
            for (int i = stackSize - 1; i >= 0; i--) {
                Object panel = dialogStack.get(i);
                if (panel != null && auClass.isInstance(panel)) {
                    int dialogType = auFieldAs.getInt(panel);
                    return new int[]{dialogType, stackSize};
                }
            }
            
            // Không tìm thấy au trong stack
            return new int[]{-999, stackSize};
        } catch (Exception e) {
            log("detectDialog error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Đóng dialog NPC đang hiển thị (gọi au.aw()).
     */
    /**
     * Đóng popup xác nhận kiểu "Đồng ý / Từ chối" (lời mời vào nhóm, lời xin gia nhập...).
     * Khác với dialog NPC: dialog NPC là nhánh a.cg, popup xác nhận là nhánh a.cd còn lại —
     * lọc theo đúng như vậy để không lỡ tay đóng dialog NPC đang dùng dở.
     * Tên class runtime của popup bị làm rối (a.at / a.aT) nên bám vào lớp cha a.cd cho chắc.
     */
    /**
     * Dọn SẠCH màn hình trước khi mở NPC — hai loại dialog nằm ở hai nhánh class khác nhau nên
     * phải gọi cả hai hàm, gọi một cái là sót:
     *   - a.au (nhánh a.cg): dialog NPC còn sót của bước trước
     *   - a.aT (nhánh a.cd): popup thông báo/xác nhận, ví dụ "Nhắc nhó - Chăm chỉ tương tác
     *     Fanpage nhận Code FC" mà server đẩy (gói -110) mỗi lần vào game và nằm lì trên màn.
     * Không dọn thì menu NPC bị đọc nhầm hoặc không đọc được, rồi cả bước treo tới khi hết giờ.
     */
    private boolean loginPopupDone = false;
    private int loginPopupTries = 0;
    private long loginPopupNext = 0;

    private void closeAnyDialog() {
        closeCurrentDialog();
        closeConfirmPopup();
    }

    /**
     * Đọc nguyên văn chữ trong popup thông báo kiểu "Nhắc nhở" (nhánh a.cd, class at/aT).
     *
     * Cần hàm RIÊNG vì readDialogQuestionText() chỉ đọc được dialog NPC (a.au, nhánh a.cg) —
     * hai nhánh class khác hẳn nhau. Chính loại popup này là thứ server dùng để báo "có người
     * trong nhóm hết lượt", nên thiếu nó thì lúc vào hầm hụt tool không đọc được lý do.
     *
     * Bám vào tên FIELD ("w" kiểu String, chỗ constructor at_0 cất chuỗi gốc) chứ không bám tên
     * class: jar có cả a.at lẫn a.aT, không phân biệt được bằng tên.
     */
    private String readConfirmPopupText() {
        try {
            if (fkFieldAn == null) return null;
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null || stack.isEmpty()) return null;

            Class<?> cdClass = Class.forName("a.cd");
            Class<?> cgClass = Class.forName("a.cg");
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object panel = stack.get(i);
                if (panel == null) continue;
                if (!cdClass.isInstance(panel) || cgClass.isInstance(panel)) continue;
                for (Class<?> c = panel.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField("w");
                        if (f.getType() != String.class) continue;
                        f.setAccessible(true);
                        Object v = f.get(panel);
                        if (v != null && !v.toString().trim().isEmpty()) return v.toString();
                    } catch (NoSuchFieldException ignore) {
                        // lớp này không có, thử lớp cha
                    }
                }
            }
        } catch (Exception e) {
            log("readConfirmPopupText error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Nguyên văn thông báo server đang hiện, thử lần lượt CẢ HAI nhánh dialog rồi mới chịu thua.
     */
    private String readAnyDialogText() {
        String t = readConfirmPopupText();
        if (t != null && !t.trim().isEmpty()) return t;
        t = readDialogQuestionText();
        if (t != null && !t.trim().isEmpty()) return t;
        return describeDialog();
    }

    private boolean closeConfirmPopup() {
        try {
            if (fkFieldAn == null) return false;
            Object zInst = getZ();
            if (zInst == null) return false;
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null || stack.isEmpty()) return false;

            Class<?> cdClass = Class.forName("a.cd");
            Class<?> cgClass = Class.forName("a.cg");
            Method aw = cdClass.getMethod("aw");
            aw.setAccessible(true);

            for (int i = stack.size() - 1; i >= 0; i--) {
                Object panel = stack.get(i);
                if (panel == null) continue;
                if (!cdClass.isInstance(panel) || cgClass.isInstance(panel)) continue;
                aw.invoke(panel);
                log("Da dong popup xac nhan (" + panel.getClass().getName() + ")");
                return true;
            }
        } catch (Exception e) {
            log("closeConfirmPopup error: " + e.getMessage());
        }
        return false;
    }

    /**
     * Dọn popup server đẩy lên ngay sau khi vào game — hiện tại là "Nhắc nhở: Chăm chỉ tương tác
     * Fanpage nhận Code FC" (gói -110). Nó nằm lì trên chồng dialog và làm mọi bước đọc menu NPC
     * sau đó không đọc được gì.
     *
     * CÓ HẠN SỐ LẦN THỬ, cố ý: chỉ dọn trong quãng đầu sau khi mod chạy rồi thôi hẳn. Nếu để nó
     * đóng popup vô thời hạn thì về sau người dùng tự mở hộp thoại nào cũng bị tool đóng mất.
     */
    private void tickClosePopupAfterLogin(long now) {
        if (loginPopupDone) return;
        if (getSettingInt("popup_close_login", 1) != 1) { loginPopupDone = true; return; }
        if (now < loginPopupNext) return;
        loginPopupNext = now + getSettingInt("popup_close_login_ms", 5000);

        // Tự lo phần reflection, và chỉ thử theo nhịp 5s chứ không gọi mỗi khung hình.
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return;   // chưa vào game xong; chưa tính là một lần thử

        if (closeConfirmPopup()) {
            loginPopupDone = true;
            log("Da dong popup thong bao sau khi vao game");
            return;
        }

        // Không đóng được cái nào. Vài lần đầu in ra chồng dialog đang có để còn biết popup đó
        // thuộc class nào — nếu nó không nằm ở nhánh a.cd thì bộ lọc hiện tại không với tới.
        if (loginPopupTries < 3) dumpDialogStack();

        if (++loginPopupTries >= getSettingInt("popup_close_login_tries", 12)) {
            loginPopupDone = true;   // hết hạn dọn, từ giờ không đụng vào hộp thoại nào nữa
        }
    }

    /** In tên class của mọi panel đang nằm trên chồng dialog — dùng để soi popup lạ. */
    private void dumpDialogStack() {
        try {
            if (fkFieldAn == null) { log("dumpDialogStack: chua map duoc field an"); return; }
            Object zInst = getZ();
            if (zInst == null) return;
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null || stack.isEmpty()) { log("dumpDialogStack: chong dialog rong"); return; }
            StringBuilder sb = new StringBuilder("dumpDialogStack (" + stack.size() + "):");
            for (int i = 0; i < stack.size(); i++) {
                Object pnl = stack.get(i);
                sb.append("\n  [").append(i).append("] ")
                  .append(pnl == null ? "null" : pnl.getClass().getName());
            }
            log(sb.toString());
        } catch (Exception e) {
            log("dumpDialogStack error: " + e.getMessage());
        }
    }

    private void closeCurrentDialog() {
        if (fkFieldAn == null || auClass == null || auMethodAw == null) return;
        try {
            Object zInst = getZ();
            if (zInst == null) return;
            
            java.util.Vector<?> dialogStack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (dialogStack == null || dialogStack.isEmpty()) return;
            
            // Tìm au dialog trong stack (có thể không ở top)
            for (int i = dialogStack.size() - 1; i >= 0; i--) {
                Object panel = dialogStack.get(i);
                if (panel != null && auClass.isInstance(panel)) {
                    auMethodAw.invoke(panel);
                    log("Dialog au closed via aw()");
                    return;
                }
            }
        } catch (Exception e) {
            log("closeCurrentDialog error: " + e.getMessage());
        }
    }

    /**
     * Log chi tiết trạng thái dialog hiện tại.
     */
    private String describeDialog() {
        int[] info = detectDialog();
        if (info == null) return "NO_STACK";
        int dialogType = info[0];
        int stackSize = info[1];
        if (dialogType == -999) return "NOT_AU(stack=" + stackSize + ")";
        if (dialogType >= 0) return "NPC_DIALOG(entityId=" + dialogType + ",stack=" + stackSize + ")";
        if (dialogType == -2) return "OPEN_MENU(stack=" + stackSize + ")";
        return "SPECIAL(" + dialogType + ",stack=" + stackSize + ")";
    }

    /**
     * Đọc danh sách menu items từ dialog NPC hiện tại.
     * @return String[] các menu items, hoặc null nếu không có dialog
     */
    private String[] readDialogMenuItems() {
        if (fkFieldAn == null || auClass == null || auFieldMenuItems == null) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> dialogStack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (dialogStack == null || dialogStack.isEmpty()) return null;
            Object topPanel = dialogStack.lastElement();
            if (topPanel == null || !auClass.isInstance(topPanel)) return null;
            return (String[]) auFieldMenuItems.get(topPanel);
        } catch (Exception e) {
            log("readDialogMenuItems error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tìm index chính xác của menu item chứa keyword trong dialog hiện tại.
     * Menu items có thể có prefix như ":-chat", ":-?", ":-!" cần strip trước khi so sánh.
     * @return index của menu item tìm thấy, hoặc -1 nếu không tìm thấy
     */
    private int findMenuIndexByKeyword(String[] menuItems, String keyword) {
        if (menuItems == null) return -1;
        String lowerKeyword = boDau(keyword);
        for (int i = 0; i < menuItems.length; i++) {
            String item = menuItems[i];
            if (item == null) continue;
            // Strip known prefixes
            String cleaned = item;
            if (cleaned.startsWith(":-chat")) cleaned = cleaned.substring(6);
            else if (cleaned.startsWith(":-?")) cleaned = cleaned.substring(3);
            else if (cleaned.startsWith(":-!")) cleaned = cleaned.substring(3);
            // Also strip comma sub-menu parts
            int commaIdx = cleaned.indexOf(',');
            if (commaIdx > 0) cleaned = cleaned.substring(0, commaIdx);
            if (boDau(cleaned).contains(lowerKeyword)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Bỏ dấu tiếng Việt + hạ chữ thường, để so khớp menu không phụ thuộc cách đặt dấu.
     *
     * ĐÂY LÀ BẢN VÁ CHO MỘT LỖI THẬT, lượt chạy Sơn cáp 17:14 ngày 29/07:
     *   menu server trả về `:-chatSơn Cáp Myoboku`, từ khoá trong config là `son cap`
     *   ⇒ hạ chữ thường xong vẫn là "sơn cáp" vs "son cap" ⇒ TRƯỢT ⇒ hoạt động dừng.
     * Chú thích ngay cạnh đó còn ghi "từ khoá để KHÔNG DẤU cho khỏi dính bẫy đặt dấu" — đúng
     * chẩn đoán, sai cách chữa: bỏ dấu ở MỘT VẾ thì hai vế càng không bao giờ gặp nhau.
     *
     * Chữa ở đây thay vì đi sửa từng từ khoá trong config: sửa config chỉ vá đúng một dòng, mà
     * mọi hoạt động đều tra menu bằng hàm này và menu game thì toàn tiếng Việt có dấu. Sau bản
     * này, từ khoá viết có dấu hay không dấu đều khớp.
     *
     * Không dùng java.text.Normalizer: bản Java của client không chắc có, mà bảng dưới đây đủ
     * cho toàn bộ chữ tiếng Việt.
     */
    private static String boDau(String s) {
        if (s == null) return "";
        String t = s.toLowerCase();
        final String[][] b = {
            {"àáảãạăằắẳẵặâầấẩẫậ", "a"},
            {"èéẻẽẹêềếểễệ",       "e"},
            {"ìíỉĩị",             "i"},
            {"òóỏõọôồốổỗộơờớởỡợ", "o"},
            {"ùúủũụưừứửữự",       "u"},
            {"ỳýỷỹỵ",             "y"},
            {"đ",                 "d"},
        };
        StringBuilder sb = new StringBuilder(t.length());
        outer:
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            for (String[] pair : b) {
                if (pair[0].indexOf(c) >= 0) { sb.append(pair[1]); continue outer; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Kiểm tra menu item có chứa sub-menu (dấu ',') không.
     * Nếu có, trả về số sub-options. Nếu không, trả về 0.
     */
    private int getSubMenuCount(String[] menuItems, int index) {
        if (menuItems == null || index < 0 || index >= menuItems.length) return 0;
        String item = menuItems[index];
        if (item == null) return 0;
        String[] parts = item.split(",");
        return parts.length > 1 ? parts.length - 1 : 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /** Đã báo "hết nhiệm vụ ngày" cho lượt bật Auto NV hiện tại chưa — chốt để chỉ bắn một lần. */
    private boolean autoNvDaBaoXong = false;

    /** Báo tình trạng nhiệm vụ ngày về Manager. Manager đếm theo tin này. */
    private void pushAutoNv(boolean ok, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"auto_nv_end\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushAutoNv error: " + e.getMessage());
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            enabledTime = System.currentTimeMillis();
            // Bật lại Auto NV = một lượt mới, phải báo lại được. Không xoá thì lượt sau chạy xong
            // mà Manager không hay, bên kia đứng chờ mãi.
            autoNvDaBaoXong = false;
        } else {
            setState(TaskState.IDLE);
            setAutoCombat(false);
        }
        log("TaskManager " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public boolean isEnabled() { return enabled; }
    public void setTuanHoanEnabled(boolean e) { tuanHoanEnabled = e; }
    public void setLinhThuEnabled(boolean e) { linhThuEnabled = e; }
    public void setAfkConfig(int mapId, int zone) {
        this.afkMapId = mapId;
        this.afkZone = zone;
        this.afkZoneChanged = false; // Reset để đổi khu lại khi config mới
        log("AFK config: map=" + mapId + " zone=" + zone);
    }

    public TaskState getState() { return state; }
    public TaskType getCurrentTaskType() { return currentTaskType; }

    public String getStatusText() {
        if (!enabled) return "Auto NV: Tat";
        switch (state) {
            case IDLE:            return "Auto NV: Cho nhiem vu...";
            case MOVE_TO_NPC:     return "Auto NV: Den NPC " + getNpcName();
            case INTERACT_NPC:    return "Auto NV: Tuong tac NPC";
            case WAIT_TASK_DATA:  return "Auto NV: Cho du lieu NV...";
            case MOVE_TO_MAP:     return "Auto NV: Di chuyen map NV";
            case DO_TASK:         return "Auto NV: Dang lam " + getTaskTypeName();
            case MOVE_TO_TURN_IN: return "Auto NV: Ve NPC tra NV";
            case TURN_IN:         return "Auto NV: Tra nhiem vu";
            case COOLDOWN:        return "Auto NV: Doi...";
            case AFK_FARM:        return "Auto NV: AFK Farm map " + afkMapId;
            default:              return "Auto NV: ???";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN TICK
    // ═══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    // SƠN CÁP — gom 2 nhóm 6 người rồi tập kết về một toạ độ
    // ══════════════════════════════════════════════════════════════
    // Máy trạng thái RIÊNG, KHÔNG dùng chung với Cấm thuật. Cấm thuật đang trong giai đoạn dò
    // lỗi; tham số hoá nó để dùng chung là đem rủi ro sang một tính năng đang chạy được. Sơn cáp
    // cũng thật sự đơn giản hơn: không mở NPC, không vào hầm, không vòng lượt.
    //
    // Ba bài học đã trả giá bên Cấm thuật được xây vào đây NGAY TỪ ĐẦU:
    //   1. Toạ độ lấy từ config; tra hụt KHÔNG được coi là "đã tới nơi".
    //   2. Báo điểm tập kết cho member SAU khi trưởng nhóm đã đứng đúng chỗ, không phải trước.
    //   3. Đang ở sẵn map đích thì KHÔNG phát lệnh đi xuyên map (lệnh đó thoái hoá thành đi tới
    //      điểm mặc định và kéo nhân vật về góc map).
    private static final int SC_LEAVE_OLD   = 8;   // CẢ HAI VAI, BƯỚC ĐẦU TIÊN: thoát nhóm cũ
    private static final int SC_L_GOTO_MAP  = 1;   // trưởng: về map tập kết
    private static final int SC_L_GROUP     = 2;   // trưởng: bảo đảm là trưởng của một nhóm
    private static final int SC_L_UNLOCK    = 3;   // trưởng: mở khoá + bật "tự cho vào nhóm"
    private static final int SC_L_ANNOUNCE  = 4;   // trưởng: báo map/khu/toạ độ
    private static final int SC_L_WAIT      = 5;   // trưởng: mời + chờ đủ quân
    private static final int SC_L_GOTO_PT   = 6;   // trưởng: đi tới điểm tập kết
    private static final int SC_L_READY     = 7;   // trưởng: đứng đúng chỗ, báo theo nhịp
    private static final int SC_M_GOTO_MAP  = 11;
    private static final int SC_M_WAIT_ZONE = 12;
    private static final int SC_M_GOTO_ZONE = 13;
    private static final int SC_M_JOIN      = 14;
    private static final int SC_M_STANDBY   = 15;
    private static final int SC_IN_FLOOR    = 20;  // cả hai vai: đang ở trong một tầng sơn cáp
    private static final int SC_OPEN_NPC    = 21;  // cả hai vai: mở NPC Fukasaku
    private static final int SC_MENU        = 22;  // cả hai vai: đọc menu, bấm mục Sơn Cáp
    private static final int SC_VERIFY      = 23;  // cả hai vai: đã bấm, chờ map đổi

    private int scStep = 0;               // 0 = tắt; chính nó là công tắc của máy này
    private int scRole = 0;               // 1 = trưởng nhóm, 2 = thành viên
    private long scNextTime = 0;
    private long scDeadline = 0;
    private String scLeaderName = "";
    private int scWantMap = 0;
    private int scWantZone = -1;
    private int scWantX = -1;             // member: toạ độ THẬT trưởng nhóm đang đứng
    private int scWantY = -1;
    private java.util.List<String> scMembers = new java.util.ArrayList<String>();
    private int scExpected = 0;
    private int scSlot = 0;
    private int scZoneCursor = -1;
    private int scZoneHops = 0;
    private boolean scZonePending = false;
    private int scZoneWaits = 0;
    private int scJoinTries = 0;
    private long scNextJoinSend = 0;
    private long scNextInvite = 0;
    private boolean scLockSent = false;
    private long scFullSince = 0;
    // Lúc member ĐẦU TIÊN báo không chen được vào khu (khu đầy người). 0 = chưa ai báo.
    private long scZoneFullAt = 0;
    private boolean scAutoAcceptChanged = false;
    private boolean scPrevAutoAccept = false;
    private String scLastRoster = "";
    private int scLastX = -99999;
    private int scLastY = -99999;
    private int scStuckTries = 0;
    private long scNextDiag = 0;
    private int scMapWaits = 0;
    private int scLeaveTries = 0;

    // ── PHA ĐÁNH TRONG SƠN CÁP ───────────────────────────────────────────────────────────────
    // Luật của hoạt động (người dùng mô tả 29/07):
    //     vào tầng → đánh hết quái thường → BOSS xuất hiện → giết boss → 5s sau CỬA MỞ
    //     → tự đi sang map tầng kế → lặp lại, tổng 5 tầng → hết tầng cuối GAME TỰ ĐẨY VỀ LÀNG
    //
    // ĐIỀU KIỆN QUA CỬA KHÔNG PHẢI "SỐ QUÁI SỐNG = 0". Số đó chạm 0 rồi NHẢY LẠI LÊN khi boss
    // ra — mà server tính boss là MỘT CON QUÁI như mọi con khác, nên nhìn con số thì không phân
    // biệt được. Lấy 0 làm mốc là qua cửa đúng lúc boss vừa xuất hiện, bỏ luôn con phải giết.
    //
    // Điều kiện đúng: HẾT QUÁI VÀ MỘT LÚC SAU VẪN HẾT. Hai trạng thái, không cần nhận dạng boss,
    // không phụ thuộc nhịp soi rơi trúng khoảnh khắc nào:
    private static final int SCF_CLEARING  = 0;  // còn quái (thường HAY boss) -> đánh
    private static final int SCF_WAIT_BOSS = 1;  // hết quái -> đếm giờ xem còn con nào ra không
    private static final int SCF_DOOR      = 3;  // chốt là xong -> chờ cửa rồi sang tầng sau
    private int scNpcId = -1;             // id thật của NPC Fukasaku trên map hiện tại
    private int scWalkTries = 0;          // số lần chưa thấy NPC
    private int scMenuWaits = 0;          // số nhịp chờ menu hiện ra
    private int scEnterWaits = 0;         // số nhịp chờ map đổi sau khi bấm
    private int scFloorPhase = SCF_CLEARING;
    private int scFloorNo = 0;            // đang ở tầng thứ mấy (1..son_cap_floors)
    private int scFloorMap = -1;          // map của tầng đang đứng
    private int scFloorAlive = -1;        // số quái sống lần đọc trước, chỉ để log khi đổi
    private long scBossDeadAt = 0;        // lúc phát hiện boss chết, để đếm 5s cửa mở
    private long scNextFloorTry = 0;      // nhịp thử lại lệnh đi sang tầng sau
    private String scMapTrail = "";       // dãy map đã đi qua — dữ liệu để điền son_cap_floor_maps
    private String scEntryTrail = "";     // dãy toạ độ ĐIỂM VÀO từng tầng (chỉ để tham khảo)
    private String scExitTrail = "";      // dãy toạ độ CỬA RA từng tầng — để điền son_cap_exit_xy
    private int scPrevX = -1, scPrevY = -1;  // vị trí nhịp trước; lúc map đổi chính là chỗ ra cửa
    private int scGuessTries = 0;         // số lần đã thử qua tầng bằng toạ độ đang đứng
    private String scLastNav = "";        // ảnh chụp tay lái lần trước, chỉ in khi có thay đổi
    private boolean scPacketLogOn = false; // đã bật bắt gói tin cho pha chờ này chưa

    /**
     * Ảnh chụp TAY LÁI của game: cờ auto-nav, đích bi_0, vị trí, map.
     *
     * Dùng để tìm ra CƠ CHẾ CHUYỂN MAP bằng quan sát. Người dùng xác nhận qua map bằng cách bấm
     * nút mũi tên ở mép màn hình; đọc mã giải ngược không tìm ra gói tương ứng (các gói nghi ngờ
     * -18/-22/-24/-25 đều là việc khác). Nhưng cú bấm đó bắt buộc phải để lại dấu vết trong bộ
     * nhớ — nếu nó dùng auto-nav thì `z.ap` bật lên và bi_0 mang map đích, và tool sẽ ĐỌC ĐƯỢC
     * ĐÚNG SỐ MAP + TOẠ ĐỘ mà game tự đặt. Có số đó thì chép lại y hệt là xong.
     * Nếu bấm mũi tên mà chẳng có gì ở đây đổi ⇒ cơ chế là gói tin thuần, phải đi hướng khác.
     */
    private String dumpNavState() {
        StringBuilder sb = new StringBuilder();
        try {
            Object z = getZ();
            sb.append("map=").append(getCurrentMapId())
              .append(" vitri=(").append(getPlayerX()).append(",").append(getPlayerY()).append(")");
            if (zFieldAp != null) sb.append(" z.ap=").append(zFieldAp.getBoolean(z));
            if (zFieldNavTarget != null) {
                Object t = zFieldNavTarget.get(z);
                if (t == null) sb.append(" bi_0=null");
                else {
                    sb.append(" bi_0{");
                    for (Field f : t.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (!f.getType().isPrimitive()) continue;
                        f.setAccessible(true);
                        sb.append(f.getName()).append("=").append(f.get(t)).append(" ");
                    }
                    sb.append("}");
                }
            }
        } catch (Exception e) {
            sb.append(" (loi doc: ").append(e.getMessage()).append(")");
        }
        return sb.toString();
    }
    private long scPhaseAt = 0;           // lúc vào pha hiện tại, để biết pha "chờ boss" đã chờ bao lâu

    private void resetSonCap() {
        scStep = 0; scRole = 0; scNextTime = 0; scDeadline = 0;
        scLeaderName = ""; scWantMap = 0; scWantZone = -1; scWantX = -1; scWantY = -1;
        scMembers = new java.util.ArrayList<String>();
        scExpected = 0; scSlot = 0;
        scZoneCursor = -1; scZoneHops = 0; scZonePending = false; scZoneWaits = 0;
        scJoinTries = 0; scNextJoinSend = 0; scNextInvite = 0;
        scLockSent = false; scFullSince = 0;
        scAutoAcceptChanged = false; scPrevAutoAccept = false;
        scLastRoster = "";
        scLastX = -99999; scLastY = -99999; scStuckTries = 0; scNextDiag = 0; scMapWaits = 0;
        scLeaveTries = 0;
        scFloorPhase = SCF_CLEARING; scFloorNo = 0; scFloorMap = -1; scFloorAlive = -1;
        scBossDeadAt = 0; scNextFloorTry = 0; scMapTrail = ""; scEntryTrail = ""; scGuessTries = 0;
        scExitTrail = ""; scPrevX = -1; scPrevY = -1; scPhaseAt = 0; scLastNav = "";
        if (scPacketLogOn) { scPacketLogOn = false; try { Auto.setPacketLog(false); } catch (Throwable ignore) {} }
        scNpcId = -1; scWalkTries = 0; scMenuWaits = 0; scEnterWaits = 0;
        scZoneFullAt = 0;
    }

    /**
     * Manager báo: có member không chen được vào khu của mình vì khu đã đủ người.
     * Chỉ GHI DẤU — việc dời khu do nhánh SC_L_WAIT làm, sau khi gom các tiếng báo lệch nhau
     * vài giây lại. Xử ngay tại đây thì 5 member báo là dời 5 lần liên tiếp.
     */
    public String notifySonCapZoneFull(String who, int khuBiTuChoi) {
        if (scRole != 1 || scStep != SC_L_WAIT) {
            return "bo qua: nick nay khong o buoc cho member (vai " + scRole + " step " + scStep + ")";
        }
        // Chỉ tính tiếng báo cho KHU MÌNH ĐANG ĐỨNG — xem chú thích dài ở notifyCamThuatZoneFull.
        int khuHienTai = getCurrentZoneId();
        if (khuBiTuChoi > 0 && khuBiTuChoi != khuHienTai) {
            return "bo qua: bao ve khu " + khuBiTuChoi + " nhung minh da sang khu " + khuHienTai;
        }
        if (scZoneFullAt == 0) {
            scZoneFullAt = System.currentTimeMillis();
            scNextTime = 0;
            log("Son cap: member '" + who + "' bao khong chen duoc vao khu " + khuHienTai
                    + " (day nguoi) -> se doi khu");
        }
        return "da ghi nhan";
    }

    
    /**
     * Manager báo cả nhóm đã tập kết đủ → TRƯỞNG NHÓM đi bấm NPC, cả nhóm được game đưa vào.
     *
     * CHỈ TRƯỞNG NHÓM BẤM — đúng khuôn Cấm thuật. Member không bấm gì cả, chúng đứng ở bước chờ
     * và nhận ra mình đã vào bằng việc MAP ĐỔI.
     *
     * Chuyện "vừa vào map là nhóm bị giải tán" (quan sát 28/07) xảy ra SAU khi cả đội đã vào,
     * không phải trước — game huỷ tổ đội một khi mọi người đã ở trong. Nó KHÔNG có nghĩa là nhóm
     * vô dụng: nhóm chính là thứ đưa người vào. Hệ quả duy nhất của nó là mọi phép kiểm nhóm phải
     * đứng SAU phép soi map, xem chú thích ở SC_L_READY.
     */
    public String enterSonCap() {
        if (scStep == 0) return "LOI: nick nay khong o phien son cap nao";
        if (scRole != 1) return "bo qua: nick nay khong phai truong nhom, cho duoc keo vao";
        if (scStep == SC_IN_FLOOR) return "da o trong son cap roi";
        if (scStep == SC_OPEN_NPC || scStep == SC_MENU || scStep == SC_VERIFY)
            return "dang vao son cap roi";
        scWalkTries = 0; scMenuWaits = 0; scEnterWaits = 0;
        scStep = SC_OPEN_NPC;
        scNextTime = 0;
        sonCapProgress("nhan lenh VAO SON CAP -> di bam NPC");
        return "da nhan lenh vao son cap";
    }

    /**
     * Map của tầng thứ n theo `son_cap_floor_maps` (danh sách ngăn bằng ';'), -1 = chưa khai.
     *
     * Chưa khai là trạng thái BÌNH THƯỜNG lúc đầu, không phải lỗi cấu hình: người dùng chưa vào
     * sơn cáp bao giờ nên chưa ai biết số map. Tool chạy được cả khi thiếu — xem CHẾ ĐỘ HỌC MAP
     * ở tickSonCapFloor.
     */
    private int sonCapFloorMap(int n) {
        String s = getSetting("son_cap_floor_maps", "");
        if (s == null || s.trim().isEmpty() || n < 1) return -1;
        String[] p = s.split(";");
        if (n > p.length) return -1;
        try { return Integer.parseInt(p[n - 1].trim()); } catch (Exception e) { return -1; }
    }

    private Field zFieldW, zFieldH;   // z.c / z.d → short: KÍCH THƯỚC map, nạp lười
    private Field zFieldExits;        // z.K  → Vector<a.fO>: DANH SÁCH LỐI RA của map
    private Field foExitDst;          // a.fO.ap → short: map ĐÍCH của lối ra đó

    /**
     * TÌM LỐI RA dẫn sang một map cụ thể. Đây là lời giải cho toàn bộ bài toán chuyển map —
     * dùng được cho cả Sơn cáp lẫn Ải gia tộc.
     *
     * ĐỌC ĐƯỢC TỪ ĐÂU: bản soi Làng Cỏ 19:26 ngày 29/07 in ra `z.K` có 2 phần tử lớp `a.fO`:
     *     ao=68 ap=74 ... ar=1831 as=236      (Làng Cỏ -> map 74)
     *     ao=68 ap=81 ... ar=106  as=456      (Làng Cỏ -> map 81)
     * `ao` = map hiện tại, `ap` = MAP ĐÍCH, `ar/as` = chỗ đứng của tấm biển. Nhân vật chuyển map
     * đúng tại (1840,235) — sát (1831,236).
     *
     * CƠ CHẾ, đọc từ z.java chỗ xử lý bấm vào biển (`x2 instanceof fo_0`) rồi `z.e(x,y)`:
     *     a.i.a().b(x, y);      // đi bộ tới chỗ tấm biển
     *     fe_0.a().b(false);    // TẮT auto đánh
     *     this.ap = false;      // TẮT auto-nav
     * KHÔNG gửi gói nào lên server lúc bấm — khớp đúng số đo `z.ap=false bi_0=null`. Chuyển map
     * xảy ra khi nhân vật ĐI TỚI NƠI, không phải khi bấm.
     *
     * Vì vậy mọi cách nhắm sang map khác (navigateToMap / navigateToMapXY, tức cơ chế nút "về
     * làng") đều sai loại — chúng dành cho đường đi xuyên nhiều map trong lưới thế giới, còn ở
     * đây chỉ là đi bộ vài trăm px trong chính map hiện tại.
     *
     * @return {x, y} chỗ đứng của tấm biển, hoặc null nếu map này không có lối sang map đó
     */
    private int[] findMapExit(int targetMap) {
        try {
            Object z = getZ();
            if (zFieldExits == null) {
                for (Field f : z.getClass().getDeclaredFields()) {
                    if (f.getName().equals("K") && java.util.Vector.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true); zFieldExits = f; break;
                    }
                }
            }
            if (zFieldExits == null) return null;
            Object listObj = zFieldExits.get(z);
            if (!(listObj instanceof java.util.Vector)) return null;
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            if (v.isEmpty()) return null;

            if (foExitDst == null) {
                Class<?> foCls = Class.forName("a.fO");
                for (Field f : foCls.getDeclaredFields()) {
                    if (f.getName().equals("ap") && f.getType() == short.class) {
                        f.setAccessible(true); foExitDst = f; break;
                    }
                }
            }
            if (foExitDst == null || mobFieldAr == null || mobFieldAs == null) return null;

            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null) continue;
                if (foExitDst.getShort(e) != targetMap) continue;
                return new int[]{ mobFieldAr.getShort(e), mobFieldAs.getShort(e) };
            }
        } catch (Exception e) {
            log("findMapExit error: " + e.getMessage());
        }
        return null;
    }

    /**
     * IN BẢNG NỐI MAP của client — trả lời "map X có lối sang map Y không, ở toạ độ nào" mà
     * KHÔNG CẦN VÀO MAP ĐÓ.
     *
     * Vì sao làm được: `z.K` (lối ra của map đang đứng) không phải server gửi theo phiên — mỗi
     * lần vào map, game dựng lại nó từ một BẢNG NỐI nằm sẵn trong client, `a.n.a().a` kiểu
     * short[][] (z.java, đoạn dựng z.K):
     *     if (bang[i][0] == map hien tai)  -> loi sang bang[i][5], o chu nhat cot 1..4
     *     if (bang[i][5] == map hien tai)  -> loi sang bang[i][0], o chu nhat cot 6..9
     * Mỗi dòng là MỘT mối nối hai chiều; client chỉ chọn đầu nào tuỳ mình đang đứng đâu.
     *
     * Bảng đó nạp lúc khởi động client, nên đọc được ngay ở làng. Đây là cách biết trước map ải
     * gia tộc 46 có nối sang 47 không mà không phải đốt lượt AGT của ngày — thứ đang là chỗ chưa
     * chắc chắn nhất của tool đó.
     *
     * ⚠️ `a.n` có BỐN trường cùng tên `a` (short[][], int[][], byte[][], String[][]) — đúng cái
     * bẫy trùng tên đã dính ba lần trong ngày. Lọc theo KIỂU, không theo tên.
     */
    private String dumpMapLinks(String dsMap) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> nCls = Class.forName("a.n");
            Method get = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == nCls
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true); get = m; break;
                }
            }
            if (get == null) return " (khong tra duoc a.n.a())";
            Object inst = get.invoke(null);

            Field bangF = null;
            for (Field f : nCls.getDeclaredFields()) {
                if (f.getType() == short[][].class) { f.setAccessible(true); bangF = f; break; }
            }
            if (bangF == null) return " (khong tra duoc bang noi map)";
            short[][] bang = (short[][]) bangF.get(inst);
            if (bang == null) return " (bang noi map rong)";

            java.util.Set<Integer> loc = new java.util.HashSet<Integer>();
            if (dsMap != null && !dsMap.trim().isEmpty()) {
                for (String s : dsMap.split("[;,]")) {
                    try { loc.add(Integer.parseInt(s.trim())); } catch (Exception ignore) {}
                }
            }

            int n = 0;
            for (int i = 0; i < bang.length; i++) {
                short[] r = bang[i];
                if (r == null || r.length < 10) continue;
                int a = r[0], b = r[5];
                if (!loc.isEmpty() && !loc.contains(a) && !loc.contains(b)) continue;
                // Chỗ đứng của tấm biển = tâm đáy ô chữ nhật, đúng cách fo_0.a() tính:
                //   j(x1 + (x2-x1)/2, y2)
                int ax = r[1] + (r[3] - r[1]) / 2, ay = r[4];
                int bx = r[6] + (r[8] - r[6]) / 2, by = r[9];
                sb.append("\n      map ").append(a).append(" <-> map ").append(b)
                  .append("  | bien ben ").append(a).append(" tai (").append(ax).append(",").append(ay).append(")")
                  .append("  | bien ben ").append(b).append(" tai (").append(bx).append(",").append(by).append(")");
                if (++n >= getSettingInt("scan_link_max", 200)) {
                    sb.append("\n      ... con nua, tang scan_link_max de xem het");
                    break;
                }
            }
            if (n == 0) sb.append("\n      (khong co moi noi nao lien quan toi ").append(dsMap).append(")");
        } catch (Exception e) {
            return " (loi: " + e + ")";
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════
    // THỬ ĐI QUA MAP — phép thử độc lập cho cơ chế chuyển map
    // ══════════════════════════════════════════════════════════════
    // Chuyển map là mắt xích còn lại của cả Sơn cáp lẫn Ải gia tộc, mà hai hoạt động đó mỗi ngày
    // chỉ chạy được một lượt. Nút này tách phép thử ra khỏi chúng: bấm ở BẤT KỲ map thường nào
    // (Làng Cỏ có sẵn hai lối ra), không tốn gì, thử bao nhiêu lần cũng được.
    //
    // Và nó TỰ CHẤM: đi xong mà map đổi thì báo qua được, hết giờ mà không đổi thì báo không —
    // kèm chỗ đang đứng so với chỗ tấm biển, đủ để biết là đi hụt hay đi tới nơi mà không kích
    // hoạt được.
    private int exStep = 0;          // 0 = tắt
    private long exNextTime = 0;
    private long exDeadline = 0;
    private int exMapBefore = -1;
    private int exTarget = -1;
    private int exX = -1, exY = -1;

    /**
     * @param targetMap map muốn sang; <= 0 = tự chọn lối XA NHẤT BÊN PHẢI (theo quan sát của
     *                  người dùng: lối sang map tiếp theo thường nằm bên phải).
     */
    public String goMapExit(int targetMap) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        try {
            int[] xy = null;
            int chon = targetMap;
            if (targetMap > 0) {
                xy = findMapExit(targetMap);
            } else {
                // Không chỉ định thì lấy lối có x lớn nhất — "map tiếp theo ở bên phải".
                if (zFieldExits == null) findMapExit(-1);
                Object listObj = (zFieldExits == null) ? null : zFieldExits.get(getZ());
                if (listObj instanceof java.util.Vector) {
                    java.util.Vector<?> v = (java.util.Vector<?>) listObj;
                    int bestX = Integer.MIN_VALUE;
                    for (int i = 0; i < v.size(); i++) {
                        Object e = v.elementAt(i);
                        if (e == null) continue;
                        int ex = mobFieldAr.getShort(e);
                        if (ex > bestX) {
                            bestX = ex;
                            xy = new int[]{ ex, mobFieldAs.getShort(e) };
                            chon = foExitDst.getShort(e);
                        }
                    }
                }
            }
            if (xy == null) {
                // ── MAP KHÔNG CÓ BIỂN: VẪN QUA ĐƯỢC, chỉ là qua MÙ ─────────────────────────
                // Việc chuyển map không cần tấm biển — nó chỉ cần đích nằm ngoài mép map. Tấm
                // biển chỉ cho biết HAI THỨ: mép nào dẫn tới map nào, và tầng nền của lối ra.
                // Không có biển thì mất cả hai: phải tự chọn hướng, và đi ngang ở tầng nền hiện
                // tại (có thể hụt nếu lối ra ở tầng khác).
                //
                // Vẫn đáng thử: ở ải gia tộc, đứng im là mất trọn lượt của ngày, còn thử mà
                // trượt thì chỉ mất mấy chục giây và có log nói rõ.
                if (getSettingInt("exit_blind", 1) != 1) {
                    String s = "map " + getCurrentMapId() + " khong co loi sang map "
                            + (targetMap > 0 ? String.valueOf(targetMap) : "nao ca")
                            + " va exit_blind=0. Loi ra co that:" + listMapExits();
                    pushExit(false, s);
                    return s;
                }
                boolean phai = getSettingInt("exit_blind_right", 1) == 1;
                exMapBefore = getCurrentMapId();
                exTarget = targetMap;
                exX = -1; exY = -1;                 // không có biển để đi tới
                setAutoCombat(false);
                autoCombatRequested = false;
                clearNavTarget();
                if (!crossMapEdge(phai)) {
                    pushExit(false, "khong dat duoc dich ngoai map");
                    return "LOI: khong dat duoc dich";
                }
                exStep = 1;
                exNextTime = 0;
                exDeadline = System.currentTimeMillis() + getSettingInt("exit_timeout_ms", 30000);
                String s = "map " + exMapBefore + " KHONG co bien chi duong"
                        + (targetMap > 0 ? " sang map " + targetMap : "")
                        + " -> QUA MU: bang thang qua mep " + (phai ? "PHAI" : "TRAI")
                        + " (khong biet dan toi dau). Loi ra co that:" + listMapExits();
                pushExit(true, s);
                return s;
            }

            exMapBefore = getCurrentMapId();
            exTarget = chon;
            exX = xy[0]; exY = xy[1];
            setAutoCombat(false);          // z.e() tắt auto đánh
            autoCombatRequested = false;
            clearNavTarget();              // z.e() đặt z.ap = false
            navigateTo(exMapBefore, exX, exY);
            exStep = 1;
            exNextTime = 0;
            exDeadline = System.currentTimeMillis() + getSettingInt("exit_timeout_ms", 30000);
            String s = "map " + exMapBefore + " -> map " + chon + ": di toi bien tai ("
                    + exX + "," + exY + "), dang o (" + getPlayerX() + "," + getPlayerY() + ")";
            pushExit(true, s);
            return s;
        } catch (Exception e) {
            pushExit(false, "loi: " + e);
            return "LOI: " + e;
        }
    }

    private void pushExit(boolean ok, String detail) {
        log("Di qua map: " + detail);
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"go_exit\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok + ",\"map\":" + getCurrentMapId()
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushExit error: " + e.getMessage());
        }
    }

    private void tickGoExit(long now) {
        try {
            int nowMap = getCurrentMapId();
            if (nowMap != exMapBefore) {
                exStep = 0;
                pushExit(true, "QUA DUOC: map " + exMapBefore + " -> " + nowMap
                        + (exTarget > 0 && nowMap != exTarget ? " (khac map dinh sang la " + exTarget + ")" : "")
                        + ", roi xuong (" + getPlayerX() + "," + getPlayerY() + ")");
                return;
            }
            if (now > exDeadline) {
                exStep = 0;
                // TRẢ CỜ z.ak VỀ FALSE. Nó là cổng chặn `if (!this.ak)` của chính hàm chuyển
                // map trong game — để bật mà không qua được thì lần sau bấm cũng không ăn.
                try { if (zFieldAk != null) zFieldAk.setBoolean(getZ(), false); } catch (Exception ignore) {}
                int rong = mapWidth();
                pushExit(false, "KHONG qua duoc sau " + (getSettingInt("exit_timeout_ms", 30000) / 1000)
                        + "s. Map rong " + rong + ", minh o (" + getPlayerX() + "," + getPlayerY() + ")"
                        + " -> da tra z.ak ve false");
                return;
            }
            if (now < exNextTime) return;
            exNextTime = now + getSettingInt("exit_poll_ms", 1500);

            // BƯỚC 1 — ĐI TỚI CHỖ TẤM BIỂN.
            //
            // Không phải để "bấm vào biển" (bấm chẳng gửi gì cả), mà để LÊN ĐÚNG TẦNG NỀN.
            // Bằng chứng từ lượt 19:50: ở map 74 nhân vật đứng (38,286), biển ở (1728,531) —
            // chênh hơn 200px chiều cao. Bước đi này dùng bộ tìm đường của game nên nó tụt
            // xuống đúng tầng có lối ra. Bỏ bước này thì đích sẽ là (rộng+20, y=286), tức đi
            // ngang ở tầng nền CŨ, và nhiều khả năng không bao giờ tới được mép.
            // (Đã thử bỏ và phải khôi phục — ghi lại để khỏi bỏ lần nữa.)
            if (exX < 0) return;   // qua mù: đích đã đặt ngoài map từ đầu, không có biển để đi tới
            int dx = Math.abs(getPlayerX() - exX), dy = Math.abs(getPlayerY() - exY);
            if (dx + dy > getSettingInt("exit_near_px", 80)) {
                navigateTo(exMapBefore, exX, exY);
                return;
            }
            // BƯỚC 2 — BĂNG QUA MÉP. Đây mới là thứ đổi map: đích nằm NGOÀI map, gán thẳng vào
            // đích di chuyển của nhân vật, không qua bộ tìm đường (bộ đó kẹp đích vào trong map).
            int rong = mapWidth();
            boolean sangPhai = (rong <= 0) || (exX > rong / 2);
            if (crossMapEdge(sangPhai)) {
                pushExit(true, "da sat bien (lech dx=" + dx + " dy=" + dy + ") -> BANG QUA MEP "
                        + (sangPhai ? "PHAI" : "TRAI") + " (map rong " + rong + ")");
            }
        } catch (Exception e) {
            exStep = 0;
            pushExit(false, "loi: " + e);
        }
    }

    /** Liệt kê mọi lối ra của map hiện tại — để log khi không tìm thấy lối cần đi. */
    private String listMapExits() {
        StringBuilder sb = new StringBuilder();
        try {
            if (zFieldExits == null) findMapExit(-1);   // nạp field
            if (zFieldExits == null) return "(khong doc duoc z.K)";
            Object listObj = zFieldExits.get(getZ());
            if (!(listObj instanceof java.util.Vector)) return "(z.K khong phai Vector)";
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null) continue;
                sb.append(" -> map ").append(foExitDst == null ? "?" : foExitDst.getShort(e))
                  .append(" tai (").append(mobFieldAr.getShort(e)).append(",")
                  .append(mobFieldAs.getShort(e)).append(")");
            }
        } catch (Exception e) {
            return "(loi: " + e.getMessage() + ")";
        }
        return sb.length() == 0 ? "(map nay khong co loi ra nao)" : sb.toString();
    }

    // ĐÃ BỎ walkToMapExit(). Sơn cáp và Ải gia tộc nay dùng CHUNG máy goMapExit() với nút 🚪 —
    // cái đã chạy được thật (74→88→73→102 lúc 19:50 ngày 29/07). Có hai bản cùng làm một việc là
    // sớm muộn một bản được sửa còn bản kia thì không; mà bản không được sửa lại đúng là bản
    // chạy trong hoạt động mỗi ngày một lượt.

    private Field iFieldMoveTarget;   // a.i.a → a.ft: ĐÍCH DI CHUYỂN của nhân vật
    private Field iFieldE;            // a.i.e → short: tham số thứ ba của ft(...)
    private Field zFieldAk, zFieldY;  // z.ak → boolean, z.y → byte: cờ "đang băng qua mép"

    /**
     * BƯỚC 2 — BĂNG QUA MÉP MAP. Đây mới là thứ thật sự đổi map.
     *
     * Đọc từ `z.a(int,int)` trong z.java, đúng nhánh mà cú bấm vào tấm biển gọi tới khi nhân vật
     * đã ở gần biển (trong 80px):
     *     if (bam gan mep PHAI man hinh) { z.y = 2; z.ak = true;
     *                                      i.a().a = new ft((short)(z.c + 20), i.as, i.e); }
     *     else if (bam gan mep TRAI)     { z.y = 3; z.ak = true;
     *                                      i.a().a = new ft(-20, i.as, i.e); }
     *
     * Điểm mấu chốt: đích là MỘT TOẠ ĐỘ NGOÀI MAP (`chiều rộng + 20`, hoặc `-20`), và nó được
     * GÁN THẲNG vào đích di chuyển của nhân vật — không đi qua bộ tìm đường. Vì vậy mọi cách
     * dùng `fp.c` hay auto-nav đều không thể làm được: bộ tìm đường kẹp đích vào trong map, mà
     * đúng cái nằm ngoài map mới là thứ kích hoạt chuyển màn.
     *
     * Ba lần đoán sai trước đều vì thiếu đúng chi tiết này:
     *   17:35 navigateToMap(map đích)            — sai loại lệnh
     *   17:57 navigateToMapXY(map đích, chỗ đứng) — sai loại lệnh
     *   18:37 đi bộ tới mép, đích bị kẹp vào map — đúng ý, sai đường đi
     *
     * @param sangPhai true = mép phải, false = mép trái
     */
    private boolean crossMapEdge(boolean sangPhai) {
        try {
            Object z = getZ();
            Object me = getI();
            Class<?> zc = z.getClass();
            Class<?> ic = me.getClass();

            if (zFieldAk == null || zFieldY == null) {
                for (Field f : zc.getDeclaredFields()) {
                    if (f.getName().equals("ak") && f.getType() == boolean.class) { f.setAccessible(true); zFieldAk = f; }
                    if (f.getName().equals("y")  && f.getType() == byte.class)    { f.setAccessible(true); zFieldY = f; }
                }
            }
            if (iFieldMoveTarget == null || iFieldE == null) {
                Class<?> ftCls = Class.forName("a.ft");
                for (Field f : ic.getDeclaredFields()) {
                    // BẪY TRÙNG TÊN: a.i có hai trường kiểu ft (a và b), và nhiều trường tên 'e'
                    // khác kiểu. Lọc theo TÊN **và** KIỂU, đúng luật đã đặt sau ba lần dính.
                    if (f.getName().equals("a") && f.getType() == ftCls) { f.setAccessible(true); iFieldMoveTarget = f; }
                    if (f.getName().equals("e") && f.getType() == short.class) { f.setAccessible(true); iFieldE = f; }
                }
            }
            if (zFieldAk == null || zFieldY == null || iFieldMoveTarget == null || iFieldE == null) {
                log("crossMapEdge: thieu field (ak=" + (zFieldAk != null) + " y=" + (zFieldY != null)
                        + " target=" + (iFieldMoveTarget != null) + " e=" + (iFieldE != null) + ")");
                return false;
            }

            int rong = mapWidth();
            short dichX = (short) (sangPhai ? (rong + 20) : -20);
            short dichY = mobFieldAs.getShort(me);
            int e = iFieldE.getShort(me);

            Object ft = Class.forName("a.ft")
                    .getConstructor(short.class, short.class, int.class)
                    .newInstance(dichX, dichY, e);

            zFieldY.setByte(z, (byte) (sangPhai ? 2 : 3));
            zFieldAk.setBoolean(z, true);
            iFieldMoveTarget.set(me, ft);
            log("crossMapEdge: dich (" + dichX + "," + dichY + ") z.y=" + (sangPhai ? 2 : 3) + " z.ak=true");
            return true;
        } catch (Exception ex) {
            log("crossMapEdge error: " + ex);
            return false;
        }
    }

    /**
     * Chiều rộng / chiều cao map hiện tại, đọc từ chính đối tượng map (z.c và z.d).
     *
     * Bản mổ 18:30 ngày 29/07 ở map 94: c=2300, d=610 — khớp với đàn quái trải tới x=2200.
     * Cần để KẸP mọi đích di chuyển vào trong map: đích nằm ngoài thì auto-nav bỏ ngay và nhân
     * vật đứng im, đúng cái đã xảy ra khi tool nhắm (-248,518) và (2648,518).
     * @return 0 nếu không đọc được (khi đó đừng kẹp)
     */
    private int mapWidth()  { return readMapSize(true); }
    private int mapHeight() { return readMapSize(false); }

    private int readMapSize(boolean rong) {
        try {
            if (zFieldW == null || zFieldH == null) {
                Class<?> zc = getZ().getClass();
                for (Field f : zc.getDeclaredFields()) {
                    if (f.getType() != short.class) continue;
                    if (f.getName().equals("c")) { f.setAccessible(true); zFieldW = f; }
                    if (f.getName().equals("d")) { f.setAccessible(true); zFieldH = f; }
                }
            }
            Field f = rong ? zFieldW : zFieldH;
            return (f == null) ? 0 : f.getShort(getZ());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Map tập kết sơn cáp: lấy son_cap_map, không khai thì dùng map của config "village". */
    private int sonCapMap() {
        int wantMap = scWantMap > 0 ? scWantMap : getSettingInt("son_cap_map", 0);
        if (wantMap <= 0) {
            loadAnchorConfig();
            if (villageConfig != null) wantMap = villageConfig[0];
        }
        return wantMap;
    }

    /** Toạ độ điểm tập kết trên map: chỉ đọc config, không đoán. Null = không khai. */
    private int[] sonCapPoint(int mapId) {
        int[] cfg = npcConfig.get("npc_son_cap_" + mapId);
        return (cfg == null) ? null : new int[]{cfg[1], cfg[2]};
    }

    private String sonCapWhereAmI() {
        try {
            int map = getCurrentMapId();
            int px = getPlayerX();
            int py = getPlayerY();
            int[] xy = sonCapPoint(map);
            int range = getSettingInt("son_cap_range", 60);
            return "map " + map + " khu " + getCurrentZoneId() + " dung tai (" + px + "," + py + ")"
                    + " | diem tap ket " + (xy == null ? "KHONG CO" : "(" + xy[0] + "," + xy[1] + ")")
                    + (xy == null ? "" : " lech dx=" + Math.abs(px - xy[0]) + " dy=" + Math.abs(py - xy[1]))
                    + " nguong=" + range
                    + " | config co npc_son_cap_" + map + ": " + (npcConfig.get("npc_son_cap_" + map) != null);
        } catch (Exception e) {
            return "khong doc duoc vi tri: " + e;
        }
    }

    /**
     * Member báo: không chen được vào khu của trưởng nhóm vì khu đã đủ 15 người.
     * Dùng chung cho Cấm thuật và Sơn cáp — cùng một chuyện, chỉ khác tên loại tin.
     *
     * Có RIÊNG một trường `want_zone` = KHU BỊ TỪ CHỐI, tách hẳn khỏi `zone` (= khu member đang
     * đứng). KHÔNG nhồi nó vào `zone`: trong repo này việc một trường mang nghĩa khác nhau tuỳ
     * loại tin đã gây lỗi nhiều lần, và ở đây hai số đó khác nhau ở đúng lúc quan trọng nhất.
     *
     * Trưởng nhóm cần `want_zone` để BỎ những tiếng báo về khu nó đã rời. Không có nó thì nó dời
     * khu liên tục: nó dời sau 5s, còn member bị game khoá 15s mới thử được khu mới, nên mấy
     * tiếng báo về khu cũ luôn tới sau lúc đã dời và lại đẩy nó dời tiếp.
     */
    private void pushZoneFull(String type, int khuBiTuChoi, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":false"
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"want_zone\":" + khuBiTuChoi
                    + ",\"extra\":\"member\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushZoneFull error: " + e.getMessage());
        }
    }

    private void pushSonCap(String type, boolean ok, String detail, String extra) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + getPlayerX() + ",\"y\":" + getPlayerY()
                    + ",\"extra\":\"" + escapeJson(extra) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushSonCap error: " + e.getMessage());
        }
    }

    private void sonCapProgress(String detail) {
        log("Son cap: " + detail);
        pushSonCap("son_cap_progress", false, detail, "");
    }

    private void sonCapDiag(long now, String what) {
        if (now < scNextDiag) return;
        scNextDiag = now + getSettingInt("son_cap_diag_ms", 15000);
        sonCapProgress(what + " - " + sonCapWhereAmI());
    }

    /** Quãng dài dùng auto-nav gốc, quãng ngắn dùng fp.c — cùng chính sách với Cấm thuật. */
    private void sonCapWalkStep(long now, int mapId, int[] xy, String who) throws Exception {
        int px = getPlayerX();
        int py = getPlayerY();
        int eps = getSettingInt("son_cap_moved_px", 8);
        if (Math.abs(px - scLastX) > eps || Math.abs(py - scLastY) > eps) scStuckTries = 0;
        else scStuckTries++;
        scLastX = px;
        scLastY = py;

        if (scStuckTries >= getSettingInt("son_cap_stuck_tries", 3)) {
            navigateToMapXY(mapId, xy[0], xy[1]);
            sonCapProgress(who + ": khong nhuc nhich sau " + scStuckTries
                    + " nhip -> chuyen sang auto-nav goc. " + sonCapWhereAmI());
            scStuckTries = 0;
            return;
        }
        int far = getSettingInt("son_cap_far_px", 200);
        if (Math.abs(px - xy[0]) > far || Math.abs(py - xy[1]) > far) {
            navigateToMapXY(mapId, xy[0], xy[1]);
            sonCapDiag(now, who + ": con xa diem tap ket -> di bang auto-nav goc");
            return;
        }
        navigateTo(mapId, xy[0], xy[1]);
        sonCapDiag(now, who + ": ap sat diem tap ket");
    }

    private void finishSonCap(boolean ok, String detail) {
        int role = scRole;
        // Gỡ tuyến dồn hoả lực TRƯỚC MỌI ĐƯỜNG KẾT THÚC — cùng lý do như bên Cấm thuật: đây là
        // cửa ra duy nhất, đặt ở đây thì không sót đường nào.
        if (scStep == SC_IN_FLOOR && getSettingInt("son_cap_follow", 1) == 1) {
            pushSonCap("son_cap_out", true, "ket thuc son cap", role == 1 ? "leader" : "member");
        }
        // Báo TRƯỚC, dọn SAU: Manager nhận son_cap_end là phát son_cap_stop cho member ngay, nên
        // cả nhóm dọn cùng lúc chứ không phải chờ trưởng nhóm xong mới tới lượt.
        log("Son cap: " + (ok ? "XONG - " : "HONG - ") + detail);
        pushSonCap("son_cap_end", ok, detail, role == 1 ? "leader" : "member");
        if (scAutoAcceptChanged) {
            try { setAutoAcceptGroup(scPrevAutoAccept); } catch (Exception ignore) {}
        }
        // GIẢI TÁN NHÓM khi tắt sơn cáp. Mỗi nick tự gửi CMD 44 nên không phụ thuộc trưởng nhóm
        // còn sống hay không: trưởng rời thì quyền chuyển sang người khác, người đó cũng đang
        // chạy hàm này nên cũng rời — cuối cùng không ai còn nhóm. Đây là thứ làm cho lần bấm
        // thứ hai của nút thật sự trả máy về trạng thái sạch cho hoạt động kế tiếp.
        if (getSettingInt("son_cap_leave_group_after", 1) == 1) {
            try {
                Object g = getGroupObj();
                if (!hasNoGroup(g)) {
                    sendLeaveGroup();
                    log("Son cap: ket thuc -> gui CMD 44 roi nhom cho sach");
                }
            } catch (Exception e) {
                log("Son cap: khong roi duoc nhom: " + e.getMessage());
            }
        }
        // ── BÀN GIAO CHO TREO MAP ───────────────────────────────────────────────────────────
        // Thiếu đoạn này thì xong sơn cáp là nhân vật ĐỨNG KHÔNG ở làng tới hết ngày: nhánh
        // "về làng" đã tắt đánh, mà `startSonCap*` lúc bắt đầu có gọi stopCurrentActivity() nên
        // máy treo map cũ cũng đã bị dập. Không ai bật lại.
        // Ba hoạt động cùng loại đều đã có (`dia_cung_after_afk`, `cam_thuat_after_afk`,
        // `agt_after_afk`) — sơn cáp là cái duy nhất bị bỏ sót.
        //
        // Đặt ở ĐÂY chứ không ở hai nhánh kết thúc: hàm này là cửa ra duy nhất của máy sơn cáp
        // (xem chú thích đầu hàm), nên không sót đường nào — kể cả đường hết giờ hay chạy nháp,
        // hai đường mà để nguyên cũng là nhân vật đứng không.
        if (afkMapId > 0 && getSettingInt("son_cap_after_afk", 1) == 1) {
            afkZoneChanged = false;        // để AFK_FARM chịu đổi khu cho lượt mới
            autoCombatRequested = false;   // để nó bật lại đánh
            setEnabled(true);
            setState(TaskState.AFK_FARM);
            log("Son cap: xong -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
        }
        resetSonCap();
    }

    public String startSonCapLeader(java.util.List<String> memberNames, int expected) {
        return startSonCapLeader(memberNames, expected, 0, 1);
    }

    public String startSonCapLeader(java.util.List<String> memberNames, int expected,
                                    int zoneSlot, int zoneSlots) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        if (zFieldGroup == null || emMethodQ == null || emMethodP == null) {
            return "LOI: chua map duoc doi tuong nhom (a.em)";
        }
        stopCurrentActivity();
        resetSonCap();
        scRole = 1;
        scStep = SC_LEAVE_OLD;
        scMembers = (memberNames != null) ? memberNames : new java.util.ArrayList<String>();
        scExpected = (expected > 0) ? expected : (1 + scMembers.size());
        // Cùng lý do với Cấm thuật: hai nhóm sơn cáp cũng dùng chung nextZoneToTry nên cùng xuất
        // phát ở zone_min. Xem chú thích ở khuXuatPhatNhom.
        int khuDau = khuXuatPhatNhom(zoneSlot, zoneSlots, getSettingInt("son_cap_max_zone", 20));
        scZoneCursor = khuDau - 1;
        scDeadline = System.currentTimeMillis() + getSettingInt("son_cap_timeout_ms", 300000);
        log("Son cap: vai TRUONG NHOM, si so dich " + scExpected + ", cho " + scMembers
                + " | nhom " + (zoneSlot + 1) + "/" + Math.max(1, zoneSlots)
                + " -> phai nhay khu thi bat dau tu khu " + khuDau);
        return "da bat dau gom nhom son cap (truong nhom)";
    }

    public String startSonCapMember(String leaderName, int slot) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        if (zFieldGroup == null || emMethodQ == null) return "LOI: chua map duoc doi tuong nhom (a.em)";
        if (leaderName == null || leaderName.trim().isEmpty()) return "LOI: thieu ten truong nhom";
        stopCurrentActivity();
        resetSonCap();
        scRole = 2;
        scStep = SC_LEAVE_OLD;
        scLeaderName = leaderName.trim();
        scSlot = slot < 0 ? 0 : slot;
        scDeadline = System.currentTimeMillis() + getSettingInt("son_cap_timeout_ms", 300000);
        log("Son cap: vai THANH VIEN, bam theo '" + scLeaderName + "' (slot " + scSlot + ")");
        return "da bat dau gom nhom son cap (thanh vien)";
    }

    public String setSonCapTarget(int mapId, int zoneId, String leaderName, int lx, int ly) {
        if (scRole != 2 || scStep == 0) return "LOI: nick nay khong o vai thanh vien son cap";
        if (leaderName != null && !leaderName.trim().isEmpty()) scLeaderName = leaderName.trim();
        if (mapId > 0) scWantMap = mapId;
        if (lx >= 0 && ly >= 0) { scWantX = lx; scWantY = ly; }
        boolean moved = (zoneId != scWantZone);
        scWantZone = zoneId;
        if (moved && scStep >= SC_M_WAIT_ZONE) {
            scStep = SC_M_GOTO_ZONE;
            scZonePending = false;
            scZoneWaits = 0;
            scJoinTries = 0;
            scNextTime = 0;
        }
        log("Son cap: nhan diem tap ket map " + scWantMap + " khu " + scWantZone
                + " (" + scWantX + "," + scWantY + ") cua '" + scLeaderName + "'");
        return "da nhan khu " + zoneId;
    }

    public String stopSonCap() {
        if (scStep == 0) return "khong co phien son cap nao dang chay";
        finishSonCap(true, "da dung theo yeu cau");
        return "da dung son cap";
    }

    private void tickSonCap(long now) {
        try {
            if (now > scDeadline) {
                finishSonCap(false, "het gio o buoc " + scStep + " - " + sonCapWhereAmI());
                return;
            }
            if (now < scNextTime) return;

            final int stepMs    = getSettingInt("son_cap_step_ms", 200);
            final int mapWait   = getSettingInt("son_cap_map_wait_ms", 2500);
            final int zoneWait  = getSettingInt("son_cap_zone_wait_ms", 2500);
            final int groupWait = getSettingInt("son_cap_group_wait_ms", 2500);
            final int joinWait  = getSettingInt("son_cap_join_wait_ms", 2000);

            // ── BƯỚC 1, CẢ HAI VAI: THOÁT NHÓM CŨ TRƯỚC MỌI THỨ KHÁC ──
            //
            // Dọn sạch trước rồi mới đi, thay vì vừa đi vừa dọn rải rác ở ba chỗ khác nhau như
            // trước. Lý do thực tế: đội hình sơn cáp (2×6) chia khác cấm thuật (3×4), nên gần như
            // nick nào cũng đang đứng nhầm nhóm, và có nick còn đang làm TRƯỞNG một nhóm cũ.
            // Trưởng nhóm cũ rời ra thì quyền chuyển sang người còn lại — người đó cũng đang chạy
            // bước này nên cũng rời; cuối cùng mọi nhóm cũ đều tan.
            if (scStep == SC_LEAVE_OLD) {
                Object g = getGroupObj();
                if (!hasNoGroup(g)) {
                    if (++scLeaveTries > getSettingInt("son_cap_leave_tries", 5)) {
                        sonCapProgress("CANH BAO: gui CMD 44 " + scLeaveTries
                                + " lan van con trong nhom -> di tiep, se don not o buoc sau");
                        scStep = (scRole == 1) ? SC_L_GOTO_MAP : SC_M_GOTO_MAP;
                        scNextTime = now + stepMs;
                        return;
                    }
                    java.util.List<String> cur = getGroupMemberNames(g);
                    sendLeaveGroup();
                    sonCapProgress("dang o nhom cua " + (cur.isEmpty() ? "?" : cur.get(0))
                            + " (" + cur.size() + " nguoi) -> CMD 44 thoat nhom (lan " + scLeaveTries + ")");
                    scNextTime = now + groupWait;
                    return;
                }
                scLeaveTries = 0;
                scStep = (scRole == 1) ? SC_L_GOTO_MAP : SC_M_GOTO_MAP;
                scNextTime = now + stepMs;
                return;
            }

            // ── Bước 2: về map tập kết ──
            if (scStep == SC_L_GOTO_MAP || scStep == SC_M_GOTO_MAP) {
                int wantMap = sonCapMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    int[] pt = sonCapPoint(wantMap);
                    if (pt != null) navigateToMapXY(wantMap, pt[0], pt[1]);
                    else navigateToMap(wantMap);
                    sonCapProgress("dang o map " + curMap + " -> ve map " + wantMap
                            + (pt != null ? " nham thang (" + pt[0] + "," + pt[1] + ")"
                                          : " NHUNG config khong co npc_son_cap_" + wantMap));
                    scNextTime = now + mapWait;
                    return;
                }
                scStep = (scRole == 1) ? SC_L_GROUP : SC_M_WAIT_ZONE;
                scNextTime = now + stepMs;
                return;
            }

            // ─────────────── TRƯỞNG NHÓM ───────────────
            if (scStep == SC_L_GROUP) {
                Object g = getGroupObj();
                if (!hasNoGroup(g) && isGroupLeader(g)) {
                    scStep = SC_L_UNLOCK;
                    scNextTime = now + stepMs;
                    return;
                }
                if (!hasNoGroup(g)) {
                    sendLeaveGroup();
                    log("Son cap: dang o nhom nguoi khac -> CMD 44 roi nhom");
                    scNextTime = now + groupWait;
                    return;
                }
                int zone = getCurrentZoneId();
                if (scZonePending) {
                    long khoa = zoneCooldownLeft(now);
                    if (zone == scZoneCursor) { scZonePending = false; scZoneWaits = 0; }
                    else if (khoa > 0) {
                        // Xem chú thích ở zoneCooldownLeft: còn khoá thì chưa được kết luận gì.
                        scNextTime = now + khoa + 250;
                        return;
                    }
                    else if (++scZoneWaits > getSettingInt("son_cap_zone_wait_tries", 3)) {
                        scZoneCursor = nextZoneToTry(scZoneCursor, zone,
                                getSettingInt("son_cap_max_zone", 20));
                        sendChangeZone(scZoneCursor);
                        scZoneWaits = 0;
                        scNextTime = now + zoneWait;
                        return;
                    } else {
                        scNextTime = now + zoneWait;
                        return;
                    }
                }
                sendCreateGroup();
                scNextTime = now + groupWait;
                if (++scZoneHops > getSettingInt("son_cap_max_hops", 20)) {
                    finishSonCap(false, "khu nao cung khong lap duoc nhom (da thu " + scZoneHops + " khu)");
                }
                return;
            }

            if (scStep == SC_L_UNLOCK) {
                Object g = getGroupObj();
                if (hasNoGroup(g)) { scStep = SC_L_GROUP; scNextTime = now + stepMs; return; }
                if (isGroupLocked(g)) {
                    sendToggleGroupLock();
                    log("Son cap: nhom dang khoa -> gui CMD 42 mo khoa");
                    scNextTime = now + groupWait;
                    return;
                }
                if (getSettingInt("son_cap_auto_accept", 1) == 1 && !isAutoAcceptGroup()) {
                    scPrevAutoAccept = setAutoAcceptGroup(true);
                    scAutoAcceptChanged = true;
                    log("Son cap: bat 'tu cho vao nhom' (cu = " + scPrevAutoAccept + ")");
                }
                scStep = SC_L_ANNOUNCE;
                scNextTime = now + stepMs;
                return;
            }

            if (scStep == SC_L_ANNOUNCE) {
                java.util.List<String> names = getGroupMemberNames(getGroupObj());
                pushSonCap("son_cap_zone", true, "san sang nhan member",
                        names.isEmpty() ? "" : names.get(0));
                scStep = SC_L_WAIT;
                scNextInvite = 0;
                scNextTime = now + stepMs;
                return;
            }

            if (scStep == SC_L_WAIT) {
                Object g = getGroupObj();
                if (hasNoGroup(g) || !isGroupLeader(g)) {
                    scStep = SC_L_GROUP;
                    scNextTime = now + stepMs;
                    return;
                }
                java.util.List<String> have = getGroupMemberNames(g);
                java.util.List<String> missing = new java.util.ArrayList<String>();
                for (String want : scMembers) {
                    boolean found = false;
                    for (String h : have) if (h.equalsIgnoreCase(want)) { found = true; break; }
                    if (!found) missing.add(want);
                }
                // NGƯỜI LẠ = đang ở trong nhóm mà không có trong danh sách sơn cáp.
                // Đây là trạng thái xảy ra THẬT, không phải giả định: chạy Sơn cáp ngay sau Cấm
                // thuật thì trưởng nhóm vẫn đang là trưởng của nhóm CŨ với đội hình cũ, và đội
                // hình sơn cáp chia khác hẳn. Không đuổi thì nhóm phình quá 6 người, người cũ
                // chiếm chỗ của người cần vào. Bỏ qua phần tử 0 vì đó là chính mình.
                java.util.List<String> strangers = new java.util.ArrayList<String>();
                for (int i = 1; i < have.size(); i++) {
                    String n = have.get(i);
                    if (n == null || n.trim().isEmpty()) continue;
                    boolean wanted = false;
                    for (String want : scMembers) {
                        if (want != null && want.trim().equals(n.trim())) { wanted = true; break; }
                    }
                    if (!wanted) strangers.add(n.trim());
                }

                String rosterNow = have.toString() + "|" + strangers;
                if (!rosterNow.equals(scLastRoster)) {
                    scLastRoster = rosterNow;
                    pushSonCap("son_cap_roster", missing.isEmpty() && strangers.isEmpty(),
                            "trong nhom " + have.size() + "/" + scExpected + " " + have
                            + (strangers.isEmpty() ? "" : " NHUNG con nguoi la " + strangers), "leader");
                }

                if (!strangers.isEmpty() && getSettingInt("son_cap_kick_stranger", 1) == 1) {
                    for (String n : strangers) sendKickByName(n);
                    sonCapProgress("co nguoi la trong nhom " + strangers + " -> gui CMD 47 duoi");
                    // Chờ CMD 43 mới rồi mới xét tiếp, đừng đuổi chồng lên nhau.
                    scNextTime = now + groupWait;
                    return;
                }

                if (missing.isEmpty() && have.size() >= scExpected) {
                    if (scFullSince == 0) scFullSince = now;
                    if (now - scFullSince < getSettingInt("son_cap_lock_delay_ms", 2000)) {
                        scNextTime = now + stepMs;
                        return;
                    }
                    if (getSettingInt("son_cap_lock_group", 1) == 1 && !scLockSent && !isGroupLocked(g)) {
                        sendToggleGroupLock();
                        scLockSent = true;
                        scNextTime = now + groupWait;
                        return;
                    }
                    closeConfirmPopup();
                    if (scAutoAcceptChanged) {
                        setAutoAcceptGroup(scPrevAutoAccept);
                        scAutoAcceptChanged = false;
                    }
                    pushSonCap("son_cap_group", true, "du " + have.size() + " nguoi " + have
                            + " tai khu " + getCurrentZoneId()
                            + (isGroupLocked(g) ? " [da khoa nhom]" : " [chua khoa duoc nhom]"), "leader");
                    scStep = SC_L_GOTO_PT;
                    scNextTime = now + stepMs;
                    return;
                }

                if (!missing.isEmpty()) scFullSince = 0;   // rớt mất người thì tính lại từ đầu

                // ── KHU ĐẦY NGƯỜI: TRƯỞNG NHÓM DỜI, KHÔNG PHẢI MEMBER BỎ CUỘC ───────────────
                // ĐIỀU KIỆN LÀ BẰNG CHỨNG, KHÔNG PHẢI HẾT GIỜ: chỉ dời khi CÓ member báo đích
                // danh là không chen vào được. Không có báo thì nhánh này không bao giờ chạy,
                // tức lượt gom bình thường không đụng tới. (Cùng nguyên tắc với agt_opened, và
                // là bản sửa của một bản nháp lấy "đội hình đứng im 45s" làm dấu hiệu — cái đó
                // sai vì lượt gom bình thường cũng im hơn 45s khi member còn đang đi bộ.)
                if (!missing.isEmpty() && scZoneFullAt > 0) {
                    int cho    = getSettingInt("son_cap_crowd_wait_ms", 5000);
                    int maxHop = getSettingInt("son_cap_max_hops", 20);
                    // Gom các tiếng báo lệch nhau vài giây lại rồi dời MỘT LẦN.
                    if (now - scZoneFullAt < cho) {
                        scNextTime = now + getSettingInt("son_cap_wait_poll_ms", 1500);
                        return;
                    }
                    if (scZoneHops >= maxHop) {
                        sonCapProgress("member bao khong chen duoc vao khu nhung da nhay "
                                + scZoneHops + "/" + maxHop + " khu -> khong doi nua, cho het han");
                        scZoneFullAt = 0;
                    } else {
                        long khoaL = zoneCooldownLeft(now);
                        if (khoaL > 0) {   // còn khoá thì chờ, đừng gửi vào chỗ chắc chắn bị bỏ
                            scNextTime = now + khoaL + 250;
                            return;
                        }
                        int zoneCu = getCurrentZoneId();
                        scZoneCursor = nextZoneToTry(scZoneCursor, zoneCu,
                                getSettingInt("son_cap_max_zone", 20));
                        sendChangeZone(scZoneCursor);
                        scZoneHops++;
                        scZonePending = true;
                        scZoneWaits = 0;
                        scZoneFullAt = 0;
                        scFullSince = 0;
                        scLockSent = false;
                        // Về ANNOUNCE để báo LẠI khu mới: member không thấy trưởng nhóm, chúng
                        // chỉ biết khu qua Manager.
                        scStep = SC_L_ANNOUNCE;
                        sonCapProgress("member khong chen duoc vao khu " + zoneCu
                                + " (day nguoi) -> DOI sang khu " + scZoneCursor + " roi bao lai");
                        scNextTime = now + getSettingInt("son_cap_zone_wait_ms", 2500);
                        return;
                    }
                }

                if (now >= scNextInvite) {
                    for (String name : missing) sendInviteByName(name);
                    java.util.List<String> nm = getGroupMemberNames(g);
                    pushSonCap("son_cap_zone", true, "van dang cho " + missing.size() + " member",
                            nm.isEmpty() ? "" : nm.get(0));
                    scNextInvite = now + getSettingInt("son_cap_invite_ms", 5000);
                }
                scNextTime = now + getSettingInt("son_cap_wait_poll_ms", 1500);
                return;
            }

            if (scStep == SC_L_GOTO_PT) {
                int curMap = getCurrentMapId();
                int[] xy = sonCapPoint(curMap);
                if (xy == null) {
                    finishSonCap(false, "config khong khai npc_son_cap_" + curMap
                            + " -> khong biet di dau. " + sonCapWhereAmI());
                    return;
                }
                int range = getSettingInt("son_cap_range", 60);
                if (Math.abs(getPlayerX() - xy[0]) > range || Math.abs(getPlayerY() - xy[1]) > range) {
                    sonCapWalkStep(now, curMap, xy, "truong nhom");
                    scNextTime = now + getSettingInt("son_cap_walk_wait_ms", 1500);
                    return;
                }
                sonCapProgress("da toi diem tap ket - " + sonCapWhereAmI());
                scStep = SC_L_READY;
                scNextTime = now + stepMs;
                return;
            }

            if (scStep == SC_L_READY) {
                // SOI MAP TRƯỚC PHÉP KIỂM NHÓM — thứ tự này là bắt buộc, không phải sở thích.
                // Người dùng quan sát 28/07: VỪA VÀO map sơn cáp là NHÓM BỊ GIẢI TÁN. Nếu kiểm
                // nhóm trước, nhịp đầu tiên sau khi vào sẽ thấy "mất quyền trưởng nhóm" và giết
                // luôn phiên — đúng vào khoảnh khắc hoạt động vừa bắt đầu, và báo là HỎNG.
                if (sonCapDetectEntered(now)) return;
                Object g = getGroupObj();
                if (hasNoGroup(g) || !isGroupLeader(g)) {
                    finishSonCap(false, "mat quyen truong nhom");
                    return;
                }
                // SOI VỊ TRÍ TRƯỚC, BÁO ĐIỂM TẬP KẾT SAU — đúng thứ tự đã trả giá bên Cấm thuật.
                int curMap = getCurrentMapId();
                int[] xy = sonCapPoint(curMap);
                int range = getSettingInt("son_cap_range", 60);
                if (xy == null || Math.abs(getPlayerX() - xy[0]) > range
                               || Math.abs(getPlayerY() - xy[1]) > range) {
                    sonCapProgress("o buoc san sang nhung KHONG con dung diem tap ket -> di lai, CHUA bao. "
                            + sonCapWhereAmI());
                    scStep = SC_L_GOTO_PT;
                    scNextTime = now + stepMs;
                    return;
                }
                java.util.List<String> nm = getGroupMemberNames(g);
                pushSonCap("son_cap_zone", true, "diem tap ket", nm.isEmpty() ? "" : nm.get(0));
                pushSonCap("son_cap_ready", true, "dung dung diem tap ket", "leader");
                sonCapDiag(now, "dung dung diem tap ket, cho ca nhom");
                scNextTime = now + getSettingInt("son_cap_ready_poll_ms", 2000);
                return;
            }

            // ─────────────── THÀNH VIÊN ───────────────
            if (scStep == SC_M_WAIT_ZONE) {
                // DỌN NHÓM CŨ NGAY LÚC ĐANG RẢNH CHỜ KHU, đừng đợi tới bước xin vào.
                //
                // Trước đây member chỉ rời nhóm sai ở SC_M_JOIN — tức mãi sau khi đã về map, chờ
                // khu và đổi khu xong. Suốt quãng đó nó vẫn dính nhóm cũ, trong khi trưởng nhóm
                // đã bắn CMD 41 mời rồi: lời mời rơi vào hư không vì người nhận đang có nhóm.
                // Với cách chia sơn cáp (2×6) khác cấm thuật (3×4) thì gần như nick nào cũng
                // rơi vào tình huống này, và có nick còn đang làm TRƯỞNG một nhóm cũ.
                //
                // Đã ở đúng nhóm của trưởng nhóm rồi thì KHÔNG rời — chạy lại nút không được
                // phép phá đội hình vừa gom xong.
                Object gw = getGroupObj();
                if (!hasNoGroup(gw) && !scLeaderName.isEmpty() && !groupHasMember(gw, scLeaderName)) {
                    java.util.List<String> cur = getGroupMemberNames(gw);
                    sendLeaveGroup();
                    log("Son cap: dang dinh nhom cu cua " + (cur.isEmpty() ? "?" : cur.get(0))
                            + " -> CMD 44 roi truoc cho san sang");
                    scNextTime = now + groupWait;
                    return;
                }
                if (scWantZone >= 0) { scStep = SC_M_GOTO_ZONE; scNextTime = now + stepMs; return; }
                scNextTime = now + getSettingInt("son_cap_wait_poll_ms", 1500);
                return;
            }

            if (scStep == SC_M_GOTO_ZONE) {
                int wantMap = sonCapMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    scStep = SC_M_GOTO_MAP;
                    scNextTime = now + stepMs;
                    return;
                }
                int zone = getCurrentZoneId();
                if (zone == scWantZone) {
                    scZonePending = false;
                    scZoneWaits = 0;
                    scStep = SC_M_JOIN;
                    scNextJoinSend = now + (long) scSlot * getSettingInt("son_cap_join_stagger_ms", 1500);
                    scNextTime = now + stepMs;
                    return;
                }
                // Còn khoá 15s thì "vẫn ở khu cũ" là ĐÚNG — chưa được đếm là thất bại.
                // Sót chốt này thì member bỏ cuộc ở giây 7.5, tức bỏ cuộc trước cả lúc game cho
                // phép thử lại lần đầu.
                if (scZonePending && zoneCooldownLeft(now) > 0) {
                    scNextTime = now + zoneCooldownLeft(now) + 250;
                    return;
                }
                if (scZonePending && ++scZoneWaits <= getSettingInt("son_cap_zone_wait_tries", 3)) {
                    scNextTime = now + zoneWait;
                    return;
                }
                if (scZoneWaits > getSettingInt("son_cap_zone_wait_tries", 3)) {
                    // KHU CỦA TRƯỞNG NHÓM ĐẦY NGƯỜI — MEMBER KHÔNG ĐƯỢC BỎ CUỘC.
                    //
                    // Log thật 14:58 ngày 31/07: 5 member báo "khong vao duoc khu 4" rồi
                    // finishSonCap(false) → mà `son_cap_after_afk` lại đưa chúng đi treo map,
                    // nên nhìn vào thì tưởng mọi thứ bình thường trong khi nhóm chưa hề lập xong.
                    // Khu có HAI hạn mức: số NHÓM và số NGƯỜI (15). Trưởng lập được nhóm chỉ
                    // vượt hạn mức thứ nhất; member vẫn có thể bị chặn bởi hạn mức thứ hai — và
                    // chỉ TRƯỞNG NHÓM dời chỗ mới sửa được. Bỏ cuộc ở đây là mất lượt CẢ NHÓM
                    // vì một chuyện member không có quyền sửa.
                    // Y hệt cơ chế đã dựng cho Cấm thuật (cam_thuat_zone_full).
                    scZoneWaits = 0;
                    scZonePending = false;
                    pushZoneFull("son_cap_zone_full", scWantZone,
                            "khong chen duoc vao khu " + scWantZone + " cua truong nhom (dang o khu "
                            + zone + ") - khu do dang day nguoi");
                    // Thử lại theo NHỊP KHOÁ của game: khu có thể trống ra khi có người rời đi,
                    // nhưng thử dày hơn khoá thì chỉ sinh thêm dòng "còn Xs" chứ không sớm hơn.
                    long lai = zoneCooldownLeft(now);
                    scNextTime = now + (lai > 0 ? lai + 250
                                                : getSettingInt("son_cap_wait_poll_ms", 1500));
                    return;
                }
                sendChangeZone(scWantZone);
                scZonePending = true;
                scZoneWaits = 0;
                scNextTime = now + zoneWait;
                return;
            }

            if (scStep == SC_M_JOIN) {
                Object g = getGroupObj();
                if (!hasNoGroup(g) && groupHasMember(g, scLeaderName)) {
                    closeConfirmPopup();
                    pushSonCap("son_cap_group", true, "da vao nhom cua '" + scLeaderName + "' ("
                            + getGroupMemberNames(g).size() + " nguoi) tai khu " + getCurrentZoneId(), "member");
                    scStep = SC_M_STANDBY;
                    scNextTime = now + stepMs;
                    return;
                }
                // ĐANG Ở NHÓM KHÁC thì phải RỜI RA TRƯỚC. Thiếu bước này là member cứ bắn CMD 39
                // mãi mà không bao giờ vào được — server không cho gia nhập khi đang có nhóm.
                // Xảy ra thật khi chạy Sơn cáp ngay sau Cấm thuật: đội hình hai bên chia khác nhau
                // nên gần như nick nào cũng đang dính nhóm cũ.
                if (!hasNoGroup(g)) {
                    java.util.List<String> cur = getGroupMemberNames(g);
                    sendLeaveGroup();
                    log("Son cap: dang o nhom cua " + (cur.isEmpty() ? "?" : cur.get(0))
                            + " (khong phai '" + scLeaderName + "') -> CMD 44 roi nhom");
                    scNextTime = now + groupWait;
                    return;
                }

                if (now >= scNextJoinSend) {
                    sendJoinByName(scLeaderName);
                    scNextJoinSend = now + joinWait;
                    if (++scJoinTries > getSettingInt("son_cap_join_tries", 30)) {
                        finishSonCap(false, "gui CMD 39 " + scJoinTries + " lan van chua vao duoc nhom");
                        return;
                    }
                }
                scNextTime = now + getSettingInt("son_cap_join_poll_ms", 500);
                return;
            }

            if (scStep == SC_M_STANDBY) {
                if (sonCapDetectEntered(now)) return;   // map đổi = đã bị kéo vào sơn cáp
                int curMap = getCurrentMapId();
                // Toạ độ trưởng nhóm báo sang được ưu tiên hơn config của chính mình.
                int[] xy = (scWantX >= 0 && scWantY >= 0 && curMap == scWantMap)
                        ? new int[]{scWantX, scWantY}
                        : sonCapPoint(curMap);
                if (xy == null) {
                    sonCapDiag(now, "KHONG biet diem tap ket o dau, dung im");
                    pushSonCap("son_cap_ready", false, "chua biet diem tap ket", "member");
                    scNextTime = now + getSettingInt("son_cap_walk_wait_ms", 1500);
                    return;
                }
                int range = getSettingInt("son_cap_range", 60);
                if (Math.abs(getPlayerX() - xy[0]) > range || Math.abs(getPlayerY() - xy[1]) > range) {
                    sonCapWalkStep(now, curMap, xy, "member");
                    pushSonCap("son_cap_ready", false, "dang di toi diem tap ket", "member");
                    scNextTime = now + getSettingInt("son_cap_walk_wait_ms", 1500);
                    return;
                }
                pushSonCap("son_cap_ready", true, "da dung diem tap ket", "member");
                sonCapDiag(now, "da dung diem tap ket");
                scNextTime = now + getSettingInt("son_cap_ready_poll_ms", 2000);
                return;
            }

            if (scStep == SC_IN_FLOOR) {
                tickSonCapFloor(now);
                return;
            }

            // ─── VÀO SƠN CÁP: TRƯỞNG NHÓM mở NPC rồi bấm "Sơn Cáp Myoboku" ───
            // Đúng khuôn Cấm thuật: một mình trưởng nhóm bấm, game kéo CẢ NHÓM vào. Member không
            // bấm gì, chúng đứng ở SC_M_STANDBY và nhận ra đã vào bằng MAP ĐỔI.
            //
            // RÀNG BUỘC GIỐNG HỆT CẤM THUẬT: sơn cáp MỖI NGÀY MỘT LƯỢT, bấm hụt là mất trắng ngày
            // hôm đó. Nên có chạy nháp (son_cap_dry_run) và sau cú bấm thì KHÔNG BAO GIỜ bấm lại.
            // Bản đầu của bước này viết vòng thử lại vì tưởng sơn cáp miễn phí — sai, đã bỏ.
            if (scStep == SC_OPEN_NPC) {
                int curMap = getCurrentMapId();
                String npcName = getSetting("son_cap_npc", "Fukasaku");
                int tplId = getSettingInt("son_cap_npc_id", 105);
                int[] npc = findNpc(npcName, tplId);
                if (npc == null) {
                    if (++scWalkTries > getSettingInt("son_cap_npc_tries", 5)) {
                        dumpAllNpcsOnMap();
                        finishSonCap(false, "khong thay NPC '" + npcName + "' (ma ban mau " + tplId
                                + ") tren map " + curMap + " - " + sonCapWhereAmI());
                        return;
                    }
                    sonCapProgress("chua thay NPC '" + npcName + "' / ma ban mau " + tplId
                            + " (lan " + scWalkTries + ")");
                    scNextTime = now + getSettingInt("son_cap_walk_wait_ms", 1500);
                    return;
                }
                scNpcId = npc[0];
                closeAnyDialog();
                sendOpenNpc(scNpcId);
                sonCapProgress("mo NPC " + npcName + " (id " + scNpcId + ")");
                scStep = SC_MENU;
                scMenuWaits = 0;
                scNextTime = now + getSettingInt("son_cap_npc_wait_ms", 600);
                return;
            }

            if (scStep == SC_MENU) {
                // Map đổi trước khi kịp đọc menu = đã vào rồi (server đưa thẳng vào map).
                if (sonCapDetectEntered(now)) return;

                String[] menu = (detectDialog() == null) ? null : readDialogMenuItems();
                if (menu == null || menu.length == 0) {
                    if (++scMenuWaits > getSettingInt("son_cap_menu_tries", 12)) {
                        finishSonCap(false, "mo NPC roi nhung khong doc duoc menu - " + sonCapWhereAmI());
                        return;
                    }
                    scNextTime = now + getSettingInt("son_cap_dialog_poll_ms", 300);
                    return;
                }
                sonCapProgress("menu NPC: " + java.util.Arrays.toString(menu));

                // CHỮ là chuẩn chính, index chỉ là lưới đỡ — cùng bài học với Cấm thuật: thứ tự
                // menu của server thật có thể lệch so với bản mẫu. Từ khoá để KHÔNG DẤU
                // ("son cap") vì chuỗi server trả về có thể đặt dấu khác cách mình gõ.
                String kw = getSetting("son_cap_npc_keyword", "son cap");
                int idx = findMenuIndexByKeyword(menu, kw);
                int cfgIdx = getSettingInt("son_cap_npc_index", -1);
                if (idx < 0) {
                    if (cfgIdx >= 0 && cfgIdx < menu.length) {
                        idx = cfgIdx;
                        sonCapProgress("khong thay tu khoa '" + kw + "' -> dung index cau hinh " + idx);
                    } else {
                        finishSonCap(false, "khong tim thay muc son cap trong menu (tu khoa '" + kw
                                + "', index cau hinh " + cfgIdx + ") | menu: "
                                + java.util.Arrays.toString(menu));
                        return;
                    }
                } else if (cfgIdx >= 0 && cfgIdx != idx) {
                    sonCapProgress("index cau hinh " + cfgIdx + " lech voi vi tri thuc " + idx
                            + " -> lay theo chu server tra ve");
                }

                // ══ ĐÂY LÀ CÚ BẤM TỐN LƯỢT ══
                // SƠN CÁP MỖI NGÀY CHỈ MỘT LƯỢT. Bấm hụt là mất trắng ngày hôm đó — không kiểm
                // lại được gì nữa cho tới hôm sau. Ràng buộc y hệt Cấm thuật, nên xử y hệt:
                // mọi điều kiện phải xong TRƯỚC dòng này, và sau dòng này KHÔNG BẤM LẠI.
                //
                // Cú bấm kéo CẢ NHÓM vào, nên nhóm tụt người là số người bị bỏ lại ngoài — mà họ
                // cũng mất lượt của ngày. Manager đã chặn ở cổng (đủ quân, cùng map/khu, đứng
                // đúng điểm); ở đây kiểm lại lần cuối tại chỗ, phòng có người rời nhóm trong lúc
                // đi tới NPC.
                Object gNow = getGroupObj();
                if (hasNoGroup(gNow) || !isGroupLeader(gNow)) {
                    closeAnyDialog();
                    sonCapProgress("sap bam thi mat quyen truong nhom -> khong bam, cho lai");
                    scStep = SC_L_READY;
                    scNextTime = now + getSettingInt("son_cap_ready_poll_ms", 2000);
                    return;
                }
                java.util.List<String> nowHave = getGroupMemberNames(gNow);
                if (scExpected > 0 && nowHave.size() < scExpected) {
                    closeAnyDialog();
                    sonCapProgress("sap bam thi nhom tut con " + nowHave.size() + "/" + scExpected
                            + " " + nowHave + " -> khong bam, cho du roi bam lai");
                    scStep = SC_L_READY;
                    scNextTime = now + getSettingInt("son_cap_ready_poll_ms", 2000);
                    return;
                }

                if (getSettingInt("son_cap_dry_run", 1) == 1) {
                    // CHẠY NHÁP: dừng ngay TRƯỚC cú bấm. Không tốn lượt, chạy bao nhiêu lần cũng
                    // được — dùng để kiểm toàn bộ pha gom nhóm và tra menu mà không đốt lượt của
                    // ngày. Đọc xong dòng "se bam [...]" thấy đúng mục thì đổi thành 0.
                    closeAnyDialog();
                    pushSonCap("son_cap_dry", true,
                            "se bam [" + idx + "] " + menu[idx] + " | menu NPC "
                                    + java.util.Arrays.toString(menu) + " | nhom " + nowHave,
                            scRole == 1 ? "leader" : "member");
                    finishSonCap(true, "CHAY NHAP - dung truoc cu bam, khong ton luot. Se bam ["
                            + idx + "] " + menu[idx]
                            + ". Doi son_cap_dry_run thanh 0 de bam that.");
                    return;
                }

                sendSelectMenu(scNpcId, idx);
                sonCapProgress("da bam [" + idx + "] " + menu[idx] + " (nhom " + nowHave.size()
                        + " nguoi) -> cho ca nhom vao map son cap");
                scEnterWaits = 0;
                scNextTime = now + getSettingInt("son_cap_enter_wait_ms", 1500);
                scStep = SC_VERIFY;
                return;
            }

            if (scStep == SC_VERIFY) {
                if (sonCapDetectEntered(now)) return;
                if (++scEnterWaits > getSettingInt("son_cap_verify_tries", 8)) {
                    // TUYỆT ĐỐI KHÔNG BẤM LẠI.
                    // Sơn cáp mỗi ngày một lượt, và server trừ lượt TRƯỚC khi kiểm — cú bấm thứ
                    // hai không cứu được gì, nó chỉ đốt nốt lượt của những người còn lượt. Đây
                    // đúng bài học đã ghi bên Cấm thuật; bản đầu của Sơn cáp viết vòng thử lại vì
                    // tưởng hoạt động này miễn phí.
                    // Đọc NGUYÊN VĂN câu server trả về thay vì đoán lý do.
                    String why = readAnyDialogText();
                    closeAnyDialog();
                    if (why != null && !why.isEmpty()) {
                        finishSonCap(true, "bam roi nhung khong vao duoc -> DUNG, KHONG bam lai."
                                + " Server bao: " + why);
                    } else {
                        finishSonCap(false, "bam roi nhung map van la " + getCurrentMapId()
                                + " va KHONG doc duoc thong bao nao cua server -> DUNG han,"
                                + " KHONG bam lai. " + sonCapWhereAmI());
                    }
                    return;
                }
                scNextTime = now + getSettingInt("son_cap_verify_ms", 1000);
                return;
            }
        } catch (Exception e) {
            finishSonCap(false, "loi: " + e);
        }
    }

    /**
     * Đang chờ tập kết mà MAP ĐỔI ⇒ đã vào sơn cáp. Cùng cách nhận biết với Cấm thuật: map đổi
     * là bằng chứng duy nhất đáng tin, nó bao trùm mọi đường vào (tự bấm, bị kéo, NPC đẩy).
     * @return true nếu vừa chuyển sang pha đánh
     */
    private boolean sonCapDetectEntered(long now) throws Exception {
        int gather = sonCapMap();
        int nowMap = getCurrentMapId();
        if (gather <= 0 || nowMap == gather || nowMap <= 0) return false;

        scStep = SC_IN_FLOOR;
        scDeadline = Long.MAX_VALUE;   // trong hoạt động: không đặt hạn của tool, xem chú thích dưới
        sonCapEnterFloor(now, nowMap, 1);
        return true;
    }

    /** Vào một tầng: đặt lại toàn bộ pha đếm, bật đánh, ghi map vào dãy đã đi. */
    private void sonCapEnterFloor(long now, int mapId, int floorNo) throws Exception {
        scFloorMap = mapId;
        scFloorNo = floorNo;
        scFloorPhase = SCF_CLEARING;
        scFloorAlive = -1;
        scBossDeadAt = 0;
        scNextFloorTry = 0;
        scGuessTries = 0;   // mỗi tầng được thử lại từ đầu
        scPhaseAt = now;
        scMapTrail += (scMapTrail.isEmpty() ? "" : ";") + mapId;

        if (getSettingInt("son_cap_combat", 1) == 1) {
            clearNavTarget();          // bỏ đích cũ, không thì đi bộ thay vì đánh
            setAutoCombat(true);
            autoCombatRequested = true;
        }
        sonCapProgress("VAO TANG " + floorNo + "/" + getSettingInt("son_cap_floors", 5)
                + " (map " + mapId + ") -> bat danh."
                + " Day map da di: " + scMapTrail);
        // Báo Manager để nó dựng tuyến DỒN HOẢ LỰC cho nhóm này.
        if (getSettingInt("son_cap_follow", 1) == 1) {
            pushSonCap("son_cap_in", true, "da vao tang " + floorNo + " map " + mapId,
                    scRole == 1 ? "leader" : "member");
        }
        scNextTime = now + getSettingInt("son_cap_floor_poll_ms", 2000);
    }

    /**
     * Máy đánh trong sơn cáp — ba pha, CHỈ TIẾN KHÔNG LÙI.
     *
     * Vì sao phải có pha chứ không chỉ đọc số quái: số quái sống chạm 0 rồi NHẢY LẠI LÊN khi
     * boss ra. Một phép so đơn `alive == 0` sẽ đúng hai lần — lần đầu là lúc boss sắp hiện, và
     * hành động ở lần đó (qua cửa) là sai hoàn toàn. Ghi lại pha thì "0 lần đầu" và "0 lần sau"
     * thành hai chuyện khác nhau.
     *
     * NHẬN RA BOSS BẰNG HÀNH VI, KHÔNG BẰNG CỜ: cờ `a.fn.aZ` mới chỉ xác nhận trên boss map
     * train ngoài, chưa ai đo boss trong sơn cáp. Nên điều kiện là "đã sạch rồi mà quái xuất
     * hiện lại" — thứ chắc chắn đúng theo mô tả của hoạt động. Cờ vẫn được ĐỌC và in ra log để
     * lượt chạy này trả lời luôn câu "boss trong hoạt động có mang cờ đó không".
     */
    private void tickSonCapFloor(long now) throws Exception {
        int nowMap = getCurrentMapId();
        int villageMap = sonCapMap();
        // Nhớ vị trí nhịp TRƯỚC: lúc map đổi, số đó chính là chỗ bước qua được = CỬA RA.
        int truocX = scPrevX, truocY = scPrevY;
        scPrevX = getPlayerX();
        scPrevY = getPlayerY();

        // VỀ LÀNG = XONG HẲN. Hết tầng cuối thì game tự đẩy ra, không cần tool làm gì.
        if (villageMap > 0 && nowMap == villageMap) {
            setAutoCombat(false);
            autoCombatRequested = false;
            if (getSettingInt("son_cap_follow", 1) == 1) {
                pushSonCap("son_cap_out", true, "da ra khoi son cap", scRole == 1 ? "leader" : "member");
            }
            finishSonCap(true, "game da day ve lang sau tang " + scFloorNo
                    + " -> XONG. Day map cac tang: " + scMapTrail
                    + " | CUA RA tung tang: " + scExitTrail
                    + " | diem vao: " + scEntryTrail
                    + "  (chep dai CUA RA vao son_cap_exit_xy de lan sau tu di)");
            return;
        }

        // QUÁ SỐ TẦNG = ĐÃ RA NGOÀI, chỉ có điều không ra đúng map đang khai là làng.
        //
        // Đường ra bình thường là nhánh "về làng" ở trên. Nhưng map làng ở đây lấy từ điểm tập
        // kết (son_cap_map), mà game có thể đẩy ra một map khác. Không có chốt này thì tool coi
        // đó là "tầng 6", đứng dọn quái ở một map không có quái, rồi chờ boss vĩnh viễn.
        int soTang = getSettingInt("son_cap_floors", 5);
        if (soTang > 0 && scFloorNo > soTang) {
            setAutoCombat(false);
            autoCombatRequested = false;
            if (getSettingInt("son_cap_follow", 1) == 1) {
                pushSonCap("son_cap_out", true, "da ra ngoai", scRole == 1 ? "leader" : "member");
            }
            finishSonCap(true, "da qua het " + soTang + " tang va dang o map " + nowMap
                    + " (khong phai map tap ket " + villageMap + ") -> coi nhu XONG."
                    + " Day map cac tang: " + scMapTrail);
            return;
        }

        // ĐỔI SANG MAP KHÁC = ĐÃ QUA TẦNG. Nhận cả hai đường: tool tự đi được, hoặc người dùng
        // đi tay (lúc chưa khai son_cap_floor_maps).
        if (nowMap != scFloorMap) {
            // HỌC CẢ HAI ĐẦU:
            //   · CỬA RA của tầng vừa rời = chỗ đứng ở nhịp TRƯỚC (bây giờ đã sang map mới rồi)
            //     — đây mới là số dùng để điều khiển, điền vào son_cap_exit_xy.
            //   · ĐIỂM VÀO của tầng mới = chỗ đang đứng, chỉ để tham khảo/đối chiếu.
            // Sang tầng rồi thì thôi bắt gói — đã có câu trả lời, để tiếp là ngập log.
            if (scPacketLogOn) { scPacketLogOn = false; Auto.setPacketLog(false); }
            if (truocX >= 0) {
                scExitTrail += (scExitTrail.isEmpty() ? "" : ";") + truocX + "," + truocY;
                sonCapProgress("CUA RA tang " + scFloorNo + " la (" + truocX + "," + truocY
                        + ") -> chep vao son_cap_exit_xy. Da hoc: " + scExitTrail);
            }
            scEntryTrail += (scEntryTrail.isEmpty() ? "" : ";") + getPlayerX() + "," + getPlayerY();
            sonCapProgress("DIEM VAO tang " + (scFloorNo + 1) + " la (" + getPlayerX() + ","
                    + getPlayerY() + ")");
            // ĐỐI CHIẾU VỚI CONFIG NHƯNG KHÔNG CHẶN THEO NÓ.
            // son_cap_floor_maps điền từ CSDL của server chứ chưa phải số đo trong một lượt chạy,
            // nên nó có thể sai thứ tự. Sai thì phải KÊU LÊN — im lặng là lượt sau vẫn sai y thế.
            // Nhưng vẫn chạy tiếp theo map THẬT: game mới là nguồn sự thật về mình đang đứng đâu,
            // cãi lại nó chỉ tạo ra một máy trạng thái tin vào chỗ mình không hề ở.
            int wantNext = sonCapFloorMap(scFloorNo + 1);
            if (wantNext > 0 && nowMap != wantNext) {
                sonCapProgress("CANH BAO: toi map " + nowMap + " nhung son_cap_floor_maps ghi tang "
                        + (scFloorNo + 1) + " la map " + wantNext
                        + " -> chay tiep theo map that, sua lai thu tu trong cfg");
            }
            if (getSettingInt("son_cap_follow", 1) == 1) {
                pushSonCap("son_cap_out", true, "roi tang " + scFloorNo, scRole == 1 ? "leader" : "member");
            }
            sonCapEnterFloor(now, nowMap, scFloorNo + 1);
            return;
        }

        if (getSettingInt("son_cap_combat", 1) == 1 && !isAutoCombatOn()
                && scFloorPhase != SCF_DOOR) {
            clearNavTarget();
            setAutoCombat(true);
            log("Son cap: auto combat bi tat -> bat lai");
        }

        int alive = countAliveMobs();
        if (alive != scFloorAlive) {
            scFloorAlive = alive;
            sonCapProgress("tang " + scFloorNo + " (map " + nowMap + "): con "
                    + (alive < 0 ? "?" : String.valueOf(alive)) + " quai song"
                    + " [pha " + scFloorPhase + "]");
        }

        // ── CÒN QUÁI THÌ ĐÁNH ──────────────────────────────────────────────────────────────
        // KHÔNG phân biệt quái thường với boss ở đây, và đó là chủ ý.
        //
        // BẢN TRƯỚC SAI VÀ SAI NẶNG. Nó chia ba pha: "dọn quái" (đợi alive==0) → "chờ boss"
        // (đợi alive>0) → "đánh boss" (đợi alive==0). Cách đó buộc tool phải BẮT ĐƯỢC khoảnh
        // khắc alive==0 nằm giữa con quái thường cuối cùng và lúc boss hiện. Nhịp soi 2 giây,
        // boss lại được server tính là MỘT CON QUÁI như mọi con khác — ra nhanh hơn một nhịp là
        // tool không bao giờ thấy số 0 đó. Hậu quả:
        //     đánh nốt boss → alive về 0 → tool tưởng vừa "dọn xong quái thường"
        //     → nhảy sang pha CHỜ BOSS → chờ một con đã chết → TREO
        // Người dùng chỉ ra đúng chỗ này trước khi nó kịp xảy ra trong lượt chạy.
        //
        // Cách đúng đơn giản hơn hẳn, và không cần nhận dạng boss: TẦNG XONG KHI KHÔNG CÒN CON
        // NÀO VÀ MỘT LÚC SAU VẪN KHÔNG CÓ CON NÀO. Chỉ hai trạng thái, không phụ thuộc nhịp soi
        // rơi vào đâu, và đúng luôn cho tầng đã sạch sẵn từ lượt vào trước.
        if (scFloorPhase == SCF_CLEARING) {
            if (alive == 0) {
                scFloorPhase = SCF_WAIT_BOSS;
                scPhaseAt = now;
                sonCapProgress("tang " + scFloorNo + ": het quai -> cho "
                        + (getSettingInt("son_cap_boss_wait_ms", 20000) / 1000)
                        + "s xem con nao ra nua khong (boss)");
            }
            scNextTime = now + getSettingInt("son_cap_floor_poll_ms", 2000);
            return;
        }

        // ── HẾT QUÁI: XÁC NHẬN CÓ THẬT SỰ XONG KHÔNG ───────────────────────────────────────
        if (scFloorPhase == SCF_WAIT_BOSS) {
            if (alive > 0) {
                // Có con mới ra — gần như chắc là boss. Quay lại đánh; không cần biết nó là gì.
                // Vẫn IN thông số ra: đây là cách đo câu còn treo "boss trong hoạt động có mang
                // cờ a.fn.aZ không", mà không hề dùng cờ đó để điều khiển.
                scFloorPhase = SCF_CLEARING;
                scPhaseAt = now;
                if (getSettingInt("son_cap_combat", 1) == 1) {
                    clearNavTarget();
                    setAutoCombat(true);
                    autoCombatRequested = true;
                }
                sonCapProgress("tang " + scFloorNo + ": co con moi ra (" + alive
                        + ") -> danh tiep. " + sonCapFindBoss() + " | " + sonCapDescribeBoss());
                scNextTime = now + getSettingInt("son_cap_floor_poll_ms", 2000);
                return;
            }
            int hanCho = getSettingInt("son_cap_boss_wait_ms", 20000);
            if (hanCho > 0 && now - scPhaseAt >= hanCho) {
                scFloorPhase = SCF_DOOR;
                scBossDeadAt = now;
                setAutoCombat(false);   // hết việc đánh; giữ bật là nó đứng đánh thay vì đi
                autoCombatRequested = false;
                sonCapProgress("tang " + scFloorNo + ": " + (hanCho / 1000)
                        + "s khong con nao ra nua -> TANG XONG, di sang tang sau");
            }
            scNextTime = now + getSettingInt("son_cap_floor_poll_ms", 2000);
            return;
        }

        // SCF_DOOR — boss chết rồi, chờ cửa mở rồi sang tầng sau.
        if (now - scBossDeadAt < getSettingInt("son_cap_door_ms", 5000)) {
            scNextTime = now + 500;
            return;
        }

        // QUA TẦNG — GIAO CHO MÁY CHUYỂN MAP DÙNG CHUNG (goMapExit).
        //
        // Đúng cái nút 🚪 đã chạy được thật lúc 19:50 ngày 29/07 (74→88→73→102): đi tới tấm biển
        // cho đúng TẦNG NỀN, rồi băng qua mép map bằng cách gán đích NGOÀI map.
        //
        // Không viết lại ở đây. Có hai bản cùng làm một việc thì sớm muộn một bản được sửa còn
        // bản kia thì không — mà bản không được sửa lại đúng là bản chạy trong hoạt động mỗi
        // ngày một lượt. Máy đó chạy riêng trong tick() và TỰ CHẤM, nên ở đây chỉ gọi rồi đứng
        // ngoài; gọi lại khi nó đã kết thúc mà map vẫn chưa đổi (tức lần trước trượt).
        int next = sonCapFloorMap(scFloorNo + 1);
        if (exStep == 0 && now >= scNextFloorTry) {
            scNextFloorTry = now + getSettingInt("son_cap_floor_retry_ms", 5000);
            sonCapProgress("tang " + scFloorNo + " -> tang " + (scFloorNo + 1) + ": " + goMapExit(next));
        }
        scNextTime = now + getSettingInt("son_cap_floor_poll_ms", 2000);
    }

    /**
     * TÌM BOSS trong map — thực thể có `a.fn.V != 0`.
     *
     * ĐÃ ĐỌC NHẦM MỘT LẦN, ghi lại để khỏi lặp: thấy ảnh chụp game có chữ "Hang Gamatatsu" ở XY
     * 2010,264 trùng chỗ con `loai=267` (2012,264) nên kết luận nó là CỬA. SAI — chữ đó nằm ở
     * góc phải trên, đúng chỗ TÊN MAP (giống "Dòng Sông Kusagakure" ở map train). Map 94 tên là
     * "Hang Gamatatsu", đặt theo tên con boss ở đó; nhân vật lúc chụp chỉ tình cờ đứng cạnh xác
     * con boss. Người dùng xác nhận: Gamatatsu HP 500 triệu chính là boss, hiện ra sau khi con
     * quái thường cuối cùng bị giết.
     *
     * Bài học: đọc một dòng chữ trên ảnh thì phải xét CHỖ NÓ NẰM, không chỉ nội dung.
     *
     * Dấu hiệu boss (đo ở map 94):
     *     quái thường  V=0  loai=263  HP 2.630.000    ban mau l='Bọ rùa'     v=''
     *     boss         V=3  loai=267  HP 500.000.000  ban mau l='Gamatatsu'  v='Linh thú'
     * Cờ `a.fn.aZ` KHÔNG dùng được: nó = false trên chính con boss này (chỉ đúng với boss map
     * train ngoài). `V != 0` và bản mẫu CÓ DANH HIỆU mới là dấu hiệu đúng trong hoạt động.
     *
     * Chỉ dùng để MÔ TẢ trong log — máy đánh không cần biết con nào là boss, nó chỉ hỏi "còn
     * con nào không".
     *
     * @return chuỗi mô tả, hoặc "" nếu map không có con nào như vậy
     */
    private String sonCapFindBoss() {
        try {
            if (zFieldE == null || mobFieldV == null) return "";
            Object listObj = zFieldE.get(getZ());
            if (!(listObj instanceof java.util.Vector)) return "";
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null || mobFieldV.getByte(e) == 0) continue;
                String[] ten = mobTemplateName(e);
                StringBuilder sb = new StringBuilder("BOSS: ");
                if (ten != null) {
                    sb.append("'").append(ten[0]).append("'");
                    if (!ten[1].isEmpty()) sb.append(" (").append(ten[1]).append(")");
                }
                if (mobFieldType != null) sb.append(" loai=").append(mobFieldType.getShort(e));
                sb.append(" V=").append(mobFieldV.getByte(e));
                if (mobFieldHp != null && mobFieldHpMax != null)
                    sb.append(" hp=").append(mobFieldHp.getInt(e)).append("/").append(mobFieldHpMax.getInt(e));
                if (mobFieldAr != null && mobFieldAs != null)
                    sb.append(" tai (").append(mobFieldAr.getShort(e)).append(",")
                      .append(mobFieldAs.getShort(e)).append(")");
                sb.append(isEntityDead(e) ? " CHET" : " SONG");
                return sb.toString();
            }
        } catch (Exception e) {
            log("sonCapFindBoss error: " + e.getMessage());
        }
        return "";
    }

    
    /**
     * Mô tả con quái còn sống có HP tối đa lớn nhất — tức boss vừa ra.
     * Mục đích là ĐO chứ không phải điều khiển: in ra cờ `aZ` để lượt chạy này trả lời câu
     * "boss trong hoạt động có mang cùng cờ với boss map train ngoài không".
     */
    private String sonCapDescribeBoss() {
        try {
            if (zFieldE == null) return "";
            Object listObj = zFieldE.get(getZ());
            if (!(listObj instanceof java.util.Vector)) return "";
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            Object best = null;
            int bestHp = -1;
            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null || isEntityDead(e) || !isKillableMob(e)) continue;
                int hp = (mobFieldHpMax == null) ? 0 : mobFieldHpMax.getInt(e);
                if (hp > bestHp) { bestHp = hp; best = e; }
            }
            if (best == null) return "";
            StringBuilder sb = new StringBuilder("Con to nhat:");
            if (mobFieldType != null) sb.append(" loai=").append(mobFieldType.getShort(best));
            if (mobFieldHp != null && mobFieldHpMax != null)
                sb.append(" hp=").append(mobFieldHp.getInt(best)).append("/").append(bestHp);
            if (mobFieldLevel != null) sb.append(" cap=").append(mobFieldLevel.getInt(best));
            if (mobFieldElite != null)
                sb.append(" co-thu-linh=").append(mobFieldElite.getBoolean(best) ? "CO" : "KHONG");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ẢI GIA TỘC (AGT) — một nick mở cửa ải, phần còn lại vào
    // ══════════════════════════════════════════════════════════════
    // KHÔNG lập nhóm: hoạt động này chạy theo GIA TỘC, không theo tổ đội. Mọi nick chỉ cần
    // đứng cạnh NPC rồi tự bấm menu của mình.
    //
    // Menu NPC có HAI TẦNG, nhưng chỉ tốn MỘT gói tin:
    //   :-chatGia tộc,Thành lập,Xin vào gia tộc,Mở cửa ải gia tộc,Vào ải gia tộc
    //    └ mục cha ┘ └────────────── các mục con nằm ngay trong chuỗi ─────────────┘
    // Cú bấm đầu ("Gia tộc") chỉ mở bảng con ở phía client, KHÔNG gửi gì lên server. Cú thứ hai
    // gửi CMD 53 ba byte: npcId + chỉ số mục cha + chỉ số mục con (sendSelectMenuWithSub).
    // Nghĩa là đọc menu một lần là biết đủ cả hai tầng — không phải bấm rồi chờ server trả bảng mới.
    private static final int AGT_GOTO_MAP    = 1;
    private static final int AGT_GOTO_NPC    = 2;
    private static final int AGT_WAIT_SIGNAL = 3;  // member: chờ trưởng mở xong cửa ải
    private static final int AGT_OPEN_NPC    = 4;
    private static final int AGT_MENU        = 5;
    private static final int AGT_VERIFY      = 6;
    private static final int AGT_IN_GATE     = 7;  // dang o trong ai, chi viec danh va cho

    private int agtStep = 0;              // 0 = tắt; chính nó là công tắc của máy này
    private int agtRole = 0;              // 1 = người mở cửa ải, 2 = người vào
    private long agtNextTime = 0;
    private long agtDeadline = 0;
    private int agtNpcId = -1;
    private int agtMapBefore = -1;
    private int agtVerifyWaits = 0;
    private boolean agtSignal = false;    // member: đã nhận tín hiệu "cửa ải mở rồi"
    private int agtLastX = -99999;
    private int agtLastY = -99999;
    private int agtStuckTries = 0;
    private long agtNextDiag = 0;
    private int agtGateMap = -1;          // cổng đang đứng (46 hoặc 47)
    private long agtClearAt = 0;          // mốc map sạch quái (0 = chưa sạch)
    private long agtNextMoveTry = 0;      // mốc được phép thử đi sang cổng kế tiếp
    private int agtLastAlive = -2;        // số quái sống lần đếm trước, để chỉ log khi đổi
    private boolean agtDidOpen = false;   // nick mở cửa: đã bấm 'Mở cửa ải' xong chưa
    private boolean agtSawMobs = false;   // đã THẤY quái trong cổng này ít nhất một lần chưa
    private long agtInGateAt = 0;         // lúc vào cổng hiện tại, để đếm hạn chờ sinh quái
    private boolean agtCanTryOpen = false; // cờ thử Mở cửa ải nếu bấm Vào ải mà map không đổi
    private int agtAttempts = 0;          // số lần thử mở/vào ải thất bại

    private void resetAgt() {
        agtStep = 0; agtRole = 0; agtNextTime = 0; agtDeadline = 0;
        agtNpcId = -1; agtMapBefore = -1; agtVerifyWaits = 0; agtSignal = false;
        agtLastX = -99999; agtLastY = -99999; agtStuckTries = 0; agtNextDiag = 0;
        agtGateMap = -1; agtDidOpen = false;
        agtClearAt = 0; agtNextMoveTry = 0; agtLastAlive = -2;
        agtSawMobs = false; agtInGateAt = 0; agtCanTryOpen = false; agtAttempts = 0;
    }

    private int agtMap() {
        int m = getSettingInt("agt_map", 0);
        if (m <= 0) { loadAnchorConfig(); if (villageConfig != null) m = villageConfig[0]; }
        return m;
    }

    /** Toạ độ đứng bấm NPC. Chỉ đọc config — tra hụt là dừng, không đoán. */
    private int[] agtPoint(int mapId) {
        int[] cfg = npcConfig.get("npc_agt_" + mapId);
        return (cfg == null) ? null : new int[]{cfg[1], cfg[2]};
    }

    /**
     * CHỈ CHẶN DÒNG LOG, KHÔNG CHẶN LỆNH ĐIỀU PHỐI.
     *
     * Khác hẳn `follow_log_manager` (chặn được cả cửa vì bám theo không có lệnh nào đi đường đó).
     * Ở AGT, ba loại tin là XƯƠNG SỐNG của hoạt động, chặn là hỏng cả lượt:
     *   · agt_opened  → Manager phát tín hiệu cho 11 nick còn lại bấm "Vào ải gia tộc"
     *   · agt_in_gate → Manager dựng tuyến dồn hoả lực
     *   · agt_end     → Manager gỡ tuyến (AgtFollowStop)
     * `agt_dry` cũng để nguyên: nó chỉ bắn khi chạy nháp, mà lúc đó nó LÀ thứ cần đọc.
     *
     * Còn lại `agt_progress` mới là chỗ ngập — nó đi qua agtProgress() gọi từ khắp nơi theo nhịp
     * soi. Tắt bằng `agt_log_manager,0`. Log tại máy (`log()`) vẫn ghi đủ, cần mổ xẻ thì mở file
     * log của client chứ không phải bật lại rồi chờ lượt sau.
     */
    private void pushAgt(String type, boolean ok, String detail) {
        if ("agt_progress".equals(type) && getSettingInt("agt_log_manager", 1) == 0) return;
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"extra\":\"" + (agtRole == 1 ? "leader" : "member") + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushAgt error: " + e.getMessage());
        }
    }

    private void agtProgress(String detail) {
        log("AGT: " + detail);
        pushAgt("agt_progress", false, detail);
    }

    private String agtWhereAmI() {
        try {
            int map = getCurrentMapId();
            int[] xy = agtPoint(map);
            return "map " + map + " khu " + getCurrentZoneId()
                    + " dung tai (" + getPlayerX() + "," + getPlayerY() + ")"
                    + " | diem dung " + (xy == null ? "KHONG CO" : "(" + xy[0] + "," + xy[1] + ")")
                    + " | npcId=" + agtNpcId;
        } catch (Exception e) {
            return "khong doc duoc vi tri: " + e;
        }
    }

    /**
     * Tìm mục menu theo CHỮ, so sau khi bỏ dấu. Dùng chung cho mọi hoạt động bấm NPC.
     *
     * Chọn theo chữ chứ không theo số thứ tự: bản mẫu server đã sai thứ tự menu 4 lần ở phần
     * cấm thuật. Bỏ dấu trước khi so vì đây là bẫy đã làm hỏng bước nhận chìa Địa cung
     * ("khoá" vs "khóa" — cùng chữ, hai cách đặt dấu, so trực tiếp là trượt).
     *
     * Hai chế độ, chọn theo việc parentKw có rỗng hay không:
     *   · parentKw CÓ chữ  → chỉ tìm trong bảng con của mục cha khớp; trả {cha, con} với con ≥ 0
     *                        ⇒ bấm bằng sendSelectMenuWithSub (CMD 53 ba byte)
     *   · parentKw RỖNG    → tìm thẳng ở menu gốc; trả {mục, -1}
     *                        ⇒ bấm bằng sendSelectMenu (CMD 53 hai byte)
     * Trả null = không thấy.
     */
    private int[] findMenuByKeyword(String[] menu, String parentKw, String subKw) {
        String sk = noAccent(subKw);
        if (parentKw == null || parentKw.trim().isEmpty()) {
            for (int i = 0; i < menu.length; i++) {
                if (menu[i] == null) continue;
                // So trên phần TRƯỚC dấu phẩy: mục gốc có bảng con thì cả chuỗi bị nối vào
                // nhau, so cả chuỗi là khớp trúng một mục con rồi bấm hai byte — sai đường.
                String head = menu[i].split(",")[0];
                if (noAccent(head).contains(sk)) return new int[]{i, -1};
            }
            return null;
        }
        String p = noAccent(parentKw);
        for (int i = 0; i < menu.length; i++) {
            if (menu[i] == null) continue;
            String[] parts = menu[i].split(",");
            if (parts.length < 2) continue;                  // mục này không có bảng con
            if (!noAccent(parts[0]).contains(p)) continue;    // không phải mục cha cần tìm
            for (int j = 1; j < parts.length; j++) {
                if (noAccent(parts[j]).contains(sk)) return new int[]{i, j - 1};
            }
        }
        return null;
    }

    /**
     * Bản của AGT — giữ nguyên tên và nguyên hành vi (AGT đã build xong nhưng chưa chạy lượt nào,
     * không đụng vào logic của nó). agt_parent_keyword mặc định là "gia toc" nên nhánh rỗng ở
     * hàm dùng chung không bao giờ chạm tới từ đường này.
     */
    private int[] agtFindMenu(String[] menu, String parentKw, String subKw) {
        return findMenuByKeyword(menu, parentKw, subKw);
    }

    public String startAgt(int role) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        stopCurrentActivity();
        resetAgt();
        agtRole = 2;
        agtStep = AGT_GOTO_MAP;
        agtDeadline = System.currentTimeMillis() + getSettingInt("agt_timeout_ms", 300000);
        log("AGT: bat dau chay Ải gia tộc");
        return "da bat dau AGT";
    }

    /** Manager báo cửa ải đã mở → member được phép bấm mục "Vào ải gia tộc". */
    public String setAgtSignal() {
        if (agtStep == 0) return "LOI: AGT chua chay";
        agtSignal = true;
        log("AGT: nhan tin hieu cua ai da mo");
        return "da nhan tin hieu";
    }

    public String stopAgt() {
        if (agtStep == 0) return "khong co phien AGT nao dang chay";
        resetAgt();
        log("AGT: da dung theo yeu cau");
        return "da dung AGT";
    }

    // ══════════════════════════════════════════════════════════════
    // SOI MENU NPC — trả lời câu "bấm mục cha thì chuyện gì xảy ra"
    // ══════════════════════════════════════════════════════════════
    // Cả AGT lẫn Cấm thuật đều dựa vào một giả định CHƯA ĐƯỢC KIỂM ở NPC gia tộc: mục con nằm
    // sẵn trong chuỗi của mục cha (ngăn bằng dấu phẩy), nên bấm được thẳng bằng CMD 53 ba byte.
    // Khả năng còn lại: bấm mục cha là gửi gói lên server, server trả về DANH SÁCH MỚI, tức
    // phải bấm hai bước.
    //
    // Máy này bấm thử mục cha bằng CMD 53 HAI BYTE rồi đọc lại menu, và tự kết luận bằng cách
    // so hai danh sách. Không đoán, không phải hy sinh một lượt hoạt động thật để thử.
    private int pbStep = 0;          // 0 = tắt, 1 = chờ menu gốc, 2 = chờ menu sau khi bấm
    private long pbNextTime = 0;
    private int pbNpcId = -1;
    private int pbTries = 0;
    private String pbMenu1 = "";

    private void pushProbe(String detail) {
        log("Soi menu: " + detail);
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"npc_probe\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":true,\"map\":" + getCurrentMapId()
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushProbe error: " + e.getMessage());
        }
    }

    public String probeNpcMenu() {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        try {
            // NPC cần soi lấy từ `probe_npc` TRƯỚC, rồi mới tụt về NPC của AGT/Cấm thuật.
            // Trước đây đóng cứng vào agt_npc, nên muốn soi menu của một NPC khác (Đại hội chẳng
            // hạn) là phải sửa tạm agt_npc — sửa tạm thì hay quên trả lại, và lượt AGT sau đó đi
            // tìm sai NPC mà chẳng có dòng nào nói vì sao.
            String probeName = getSetting("probe_npc", "");
            int[] npc = probeName.trim().isEmpty()
                    ? findNpc(getSetting("agt_npc", getSetting("cam_thuat_npc", "Onoki")),
                            getSettingInt("agt_npc_id", getSettingInt("cam_thuat_npc_id", 32)))
                    : findNpc(probeName, getSettingInt("probe_npc_id", -1));
            if (npc == null) {
                dumpAllNpcsOnMap();
                return "LOI: khong tra duoc id NPC - da in danh sach NPC tren map ra log";
            }
            pbNpcId = npc[0];
            pbMenu1 = "";
            pbTries = 0;
            closeAnyDialog();
            sendOpenNpc(pbNpcId);
            pbStep = 1;
            pbNextTime = System.currentTimeMillis() + getSettingInt("agt_npc_wait_ms", 600);
            return "dang soi menu NPC id=" + pbNpcId;
        } catch (Exception e) {
            pbStep = 0;
            return "LOI: " + e;
        }
    }

    private void tickProbe(long now) {
        if (pbStep == 0 || now < pbNextTime) return;
        try {
            String[] menu = readDialogMenuItems();
            int maxTries = getSettingInt("probe_tries", 10);
            if (menu == null || menu.length == 0) {
                if (++pbTries <= maxTries) {
                    pbNextTime = now + getSettingInt("agt_dialog_poll_ms", 300);
                    return;
                }
                pushProbe(pbStep == 1
                        ? "KHONG doc duoc menu goc sau " + maxTries + " lan thu"
                        : "sau khi bam muc cha: KHONG con dialog nao -> cu bam do DA THUC HIEN LUON"
                          + " chu khong mo ra menu con");
                pbStep = 0;
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < menu.length; i++) {
                sb.append("[").append(i).append("] ").append(menu[i]).append(" · ");
            }

            if (pbStep == 1) {
                pbMenu1 = sb.toString();
                pushProbe("MENU GOC: " + pbMenu1);
                String kw = getSetting("agt_parent_keyword", "gia toc");
                int idx = -1;
                for (int i = 0; i < menu.length; i++) {
                    if (menu[i] == null) continue;
                    if (noAccent(menu[i].split(",")[0]).contains(noAccent(kw))) { idx = i; break; }
                }
                if (idx < 0) {
                    pushProbe("khong thay muc cha '" + kw + "' trong menu goc -> dung");
                    closeAnyDialog();
                    pbStep = 0;
                    return;
                }
                pushProbe("bam muc cha [" + idx + "] '" + menu[idx].split(",")[0].trim()
                        + "' bang CMD 53 HAI BYTE, roi doc lai menu...");
                sendSelectMenu(pbNpcId, idx);
                pbTries = 0;
                pbStep = 2;
                pbNextTime = now + getSettingInt("probe_after_click_ms", 1200);
                return;
            }

            String m2 = sb.toString();
            pushProbe("MENU SAU KHI BAM: " + m2);
            pushProbe(m2.equals(pbMenu1)
                    ? "KET LUAN: menu KHONG DOI -> muc con nam san trong chuoi cua muc cha."
                      + " Duong CMD 53 BA BYTE dang dung la DUNG."
                    : "KET LUAN: menu DA DOI -> server tra ve danh sach moi."
                      + " Phai bam HAI BUOC: CMD 53 hai byte cho muc cha, doc lai menu, roi bam muc con.");
            closeAnyDialog();
            pbStep = 0;
        } catch (Exception e) {
            pushProbe("loi: " + e);
            pbStep = 0;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SOI MAP — đếm và phân loại entity, tách NGƯỜI CHƠI khỏi QUÁI
    // ══════════════════════════════════════════════════════════════
    // Số này dùng ở nhiều chỗ: AGT lấy "còn quái không" để quyết định qua cổng, Sơn cáp sẽ cần
    // tương tự, và bám mục tiêu cần chắc chắn không nhắm vào người.
    //
    // Ba vector khác nhau, mục đích khác nhau — in cả ba để so trực tiếp thay vì tin lời:
    //   z.E  kỳ vọng CHỈ quái   (hai hàm tra quái của game duyệt nó rồi ép kiểu a.fn không kiểm)
    //   z.O  danh sách nhắm được (hàm chọn mục tiêu của game duyệt nó, ép kiểu a.x)
    //   z.F  danh sách chung     (bản đếm CŨ dùng nó và đếm nhầm cả người chơi)
    // Chạy thuần ĐỌC bộ nhớ, không gửi gói nào, nên bấm lúc nào và bao nhiêu lần cũng được.
    /**
     * Ghi lại một mã lệnh gói tin vừa gửi lên server. Gọi từ Auto.pk (hook trong a.fm.aG).
     * Kèm vị trí + map để đối chiếu: gói phát ra đúng lúc bấm nút mũi tên là gói cần tìm.
     */
    public void logPacket(byte cmd) {
        String where;
        try {
            where = "map " + getCurrentMapId() + " (" + getPlayerX() + "," + getPlayerY() + ")";
        } catch (Exception e) {
            where = "?";
        }
        pushScan("GOI TIN MOI: fm(" + cmd + ")  [" + where + "]");
    }

    /** Lần soi hiện tại do máy tự chạy (true) hay do người bấm nút (false). */
    private boolean scanAuto = false;
    /** Đã mổ xẻ trường bao nhiêu lần rồi — phần này chỉ cần vài lần đầu, sau đó là lặp lại. */
    private int scanDeepDone = 0;

    // ── MÁY SOI TỰ ĐỘNG (nút 🧲) ──────────────────────────────────────────────────────────
    // Chạy ĐỘC LẬP với mọi hoạt động: không cần bám theo, không cần đang ở trong hầm, không cần
    // bật Auto. Bật trước khi vào hoạt động rồi để đó, tắt khi xong.
    //
    // Vì sao phải là máy chạy đều chứ không phải bấm từng phát: mỗi hoạt động một ngày chỉ chạy
    // được MỘT lượt. Ngồi canh bấm 🗺️ đúng khoảnh khắc boss ra là chuyện lỡ thì mất tới hôm sau.
    // Thà thu dư cả lượt rồi lọc sau.
    private boolean scanAutoOn = false;
    private long scanAutoNext = 0;

    public String setScanAuto(boolean on) {
        if (on && !reflectionReady) initReflection();
        scanAutoOn = on;
        scanAutoNext = 0;              // bật là soi ngay một phát làm mốc gốc
        scanNavNext = 0; scanNavLast = "";
        log("Soi map tu dong: " + (on ? "BAT (nhip " + getSettingInt("scan_auto_ms", 15000) + "ms)" : "tat"));
        return on ? "da bat soi map tu dong" : "da tat soi map tu dong";
    }

    private long scanNavNext = 0;
    private String scanNavLast = "";

    private void tickScanAuto(long now) {
        // ── SOI TAY LÁI: nhịp dày, tách khỏi nhịp soi map ───────────────────────────────────
        // Mục đích: tìm ra CƠ CHẾ CHUYỂN MAP. Người dùng cho biết biển chỉ đường (mũi tên có
        // chữ, ví dụ "Dòng Sông Kusagakure" ở Làng Cỏ) là MỘT VẬT THỂ ĐỨNG TRONG MAP, bấm vào là
        // nhân vật tự đi sang map kế — và Làng Cỏ cũng có một cái. Nghĩa là thử được ở ngoài,
        // không phải đốt lượt hoạt động nào.
        //
        // Cách đo: bật 🧲, đứng cạnh biển, bấm vào nó, rồi đọc dòng ngay sau.
        //   · bi_0 mang số map + toạ độ  ⇒ biển dùng chính auto-nav, và ta ĐỌC ĐƯỢC tham số thật
        //     game tự đặt. Chép lại y hệt là giải quyết xong chuyển map cho cả AGT lẫn Sơn cáp.
        //   · không gì đổi                ⇒ là gói tin thuần, lúc đó mới cần bàn tới việc vá
        //     a.fm.aG() (đường đó rủi ro vì nằm trên mọi gói tin, nên chỉ làm khi hết cách).
        int navMs = getSettingInt("scan_nav_ms", 1000);
        if (navMs > 0 && now >= scanNavNext) {
            scanNavNext = now + navMs;
            String s = dumpNavState();
            if (!s.equals(scanNavLast)) {
                scanNavLast = s;
                pushScan("TAY LAI DOI: " + s);
            }
        }

        int ms = getSettingInt("scan_auto_ms", 15000);
        if (ms <= 0) return;
        if (scanAutoNext > 0 && now < scanAutoNext) return;
        scanAutoNext = now + ms;
        scanMapEntities(true);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    //  ĐỔI ĐỒ LẤY TINH THẠCH tại NPC Kinkaku
    // ══════════════════════════════════════════════════════════════════════════════════════
    //
    // Toàn bộ số liệu dưới đây ĐO ĐƯỢC hoặc đọc thẳng từ mã game, không có mục nào là suy đoán:
    //
    // · MÓN NÀO ĐỔI ĐƯỢC — `mon.w() > 0`. Chính bảng thông tin món trong game dùng đúng hàm đó:
    //     if (d_02.w() > 0) object = fm_0.c(com.c.a.a.gv, fm_0.f(d_02.w()));
    //   với `gv = 'Có thể đổi # Tinh thạch'` (đo lúc chạy 16:41 ngày 03/08). Nên KHÔNG cần khai
    //   danh sách mã, cũng không dò chữ — hỏi thẳng chính con số game hiển thị.
    //
    // · CỬA SỔ — `a.dQ`, chụp được trong chồng bảng: `a.dQ  mang do .h dai=16 co do=0  t=78`.
    //   `byte t` là loại NPC, 78 là Kinkaku. 16 ô, đúng lưới 4×4 trên ảnh.
    //
    // · LỆNH GỬI — nút Đồng ý của cửa sổ (mã 5002) làm đúng thế này:
    //     if (t == 78) fm(-20);  s(so_mon);  moi mon: s(mon.cr), t(mon.ch)
    //
    // · `cr` LÀ TÚI NÀO, `ch` LÀ Ô THỨ MẤY. Đường huỷ của cửa sổ khai ra:
    //     i.a().a(mon.cr)[mon.ch] = mon;        // dq_0.M()
    //   `i.a().a(int)` trả về mảng của đúng kho đó. Đây là dạng tổng quát của thứ mà bên gom đồ
    //   em đã giả định hẹp hơn ("luôn là túi chính").
    private static final int TS_TAT      = 0;
    private static final int TS_DI       = 1;   // đi tới chỗ NPC
    private static final int TS_MO_NPC   = 2;   // gửi lệnh mở NPC
    private static final int TS_CHON_MUC = 3;   // đọc menu, chọn "Đổi tinh thạch"
    private static final int TS_XEP      = 4;   // cửa sổ mở → xếp đồ vào 16 ô
    private static final int TS_CHOT     = 5;   // gửi lệnh đổi, chờ cửa sổ trống lại

    private int tsStep = TS_TAT;
    private long tsNextTime = 0, tsHanChot = 0;
    private int tsNpcId = -1;
    private int tsLuot = 0, tsTongMon = 0, tsTongDa = 0;
    private int tsBoQua = 0;   // đã ghi log bao nhiêu món bị luật chặn, để không ngập log
    private int tsLogDiChuyen = -1;  // map đã ghi log lúc đang đi, để không ghi lại mỗi nhịp

    public String startTinhThach() {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        stopCurrentActivity();
        tsStep = TS_DI;
        tsNpcId = -1;
        tsLuot = 0; tsTongMon = 0; tsTongDa = 0; tsBoQua = 0; tsLogDiChuyen = -1;
        tsNextTime = 0;
        tsHanChot = System.currentTimeMillis() + getSettingInt("tinh_thach_timeout_ms", 180000);
        log("Tinh thach: bat dau, di toi NPC");
        return "tinh thach: dang di toi NPC";
    }

    /**
     * CỬA RA DUY NHẤT của máy đổi tinh thạch — mọi đường kết thúc đều đi qua đây.
     *
     * Hai việc phải làm, cả hai đều đã sót ở lượt chạy 17:56 ngày 03/08 (6 nick đổi xong 99 món
     * rồi đứng nguyên với cửa sổ mở):
     *
     * 1. ĐÓNG CỬA SỔ. `closeAnyDialog()` không đụng tới được: nó chỉ đóng bảng `a.au` và popup
     *    xác nhận, còn cửa sổ đổi là `a.dQ` — nhánh lớp khác hẳn. Đóng bằng cách game dọn: gọi
     *    `M()` để trả món còn sót về đúng kho (`i.a().a(cr)[ch]`), rồi gỡ bảng khỏi chồng `z.an`.
     *
     * 2. BÀN GIAO ĐI TREO. Đúng bài học của Sơn cáp và gom đồ: đặt ở cửa ra chung, không rải ra
     *    từng nhánh — rải thì luôn sót một đường, mà sót là nick đứng không cả buổi.
     */
    public String stopTinhThach() {
        dongCuaSoTinhThach();
        closeAnyDialog();          // dọn nốt bảng NPC còn sót nếu có
        tsStep = TS_TAT;

        if (afkMapId > 0 && getSettingInt("tinh_thach_after_afk", 1) == 1) {
            afkZoneChanged = false;
            autoCombatRequested = false;
            setEnabled(true);
            setState(TaskState.AFK_FARM);
            log("Tinh thach: xong -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
            return "tinh thach: da dung -> di treo map " + afkMapId;
        }
        log("Tinh thach: da dung (khong co map treo de ban giao)");
        return "tinh thach: da dung";
    }

    /** Đóng cửa sổ đổi tinh thạch: trả món còn sót về kho rồi gỡ bảng khỏi chồng `z.an`. */
    private void dongCuaSoTinhThach() {
        try {
            Object cs = timCuaSoTinhThach();
            if (cs == null) return;
            // `M()` là đường dọn của chính cửa sổ — nó trả mọi món trong 16 ô về đúng kho theo
            // cặp (cr, ch). Không gọi mà gỡ thẳng là mất món đang nằm trong ô.
            try {
                Method m = cs.getClass().getDeclaredMethod("M");
                m.setAccessible(true);
                m.invoke(cs);
            } catch (Throwable e) {
                log("Tinh thach: goi M() hong: " + e);
            }
            Object zInst = getZ();
            if (zInst != null && fkFieldAn != null) {
                java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
                if (stack != null) stack.remove(cs);
            }
            log("Tinh thach: da dong cua so doi");
        } catch (Throwable e) {
            log("Tinh thach: dong cua so hong: " + e);
        }
    }

    /**
     * MÓN NÀY CÓ ĐƯỢC PHÉP ĐỔI KHÔNG — luật khai ở `tinh_thach_luat`, dạng `capMin-capMax:soTT`.
     *
     * `w() > 0` mới chỉ nói món ĐỔI ĐƯỢC, chưa nói NÊN đổi. Trang bị đặc biệt cũng đổi được,
     * đổi nhầm là mất đồ thật và không lấy lại. Nên thêm một tầng khoá: chỉ đổi đúng những
     * hạng đã khai — trang bị cấp 4x ăn 8 tinh thạch, cấp 5x ăn 9.
     *
     * Cặp (cấp yêu cầu, số tinh thạch) chứ không phải một mình số tinh thạch: trang bị đặc biệt
     * có thể trùng số tinh thạch nhưng khác cấp, khoá bằng một chiều là vẫn lọt.
     *
     * Để trống `tinh_thach_luat` là bỏ tầng khoá này, quay về luật rộng `w() > 0`. Chỉ nên làm
     * thế khi đã nhận diện được trang bị đặc biệt bằng dấu hiệu riêng của nó.
     */
    private boolean tinhThachDuocDoi(Object mon, int soTT) {
        String luat = getSetting("tinh_thach_luat", "");
        if (luat.trim().isEmpty()) return true;          // không khai luật ⇒ nhận mọi món w()>0
        int cap = tinhThachCapYeuCau(mon);
        for (String phan : luat.split(",")) {
            phan = phan.trim();
            if (phan.isEmpty()) continue;
            try {
                int haiCham = phan.indexOf(':');
                if (haiCham < 0) continue;
                int can = Integer.parseInt(phan.substring(haiCham + 1).trim());
                if (can != soTT) continue;
                String khoang = phan.substring(0, haiCham).trim();
                int gach = khoang.indexOf('-');
                int min = (gach < 0) ? Integer.parseInt(khoang)
                                     : Integer.parseInt(khoang.substring(0, gach).trim());
                int max = (gach < 0) ? min
                                     : Integer.parseInt(khoang.substring(gach + 1).trim());
                if (cap >= min && cap <= max) return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    /**
     * Mã vật phẩm có nằm trong danh sách CẤM không — dạng `a-b` hoặc số lẻ, cách nhau dấu phẩy.
     *
     * Sinh ra vì `d_0.w()` mang HAI nghĩa: với mã 125–133 và 535 nó rẽ sang nhánh riêng trả về
     * một đại lượng khác (120, 240, …), không phải số tinh thạch. Không chặn thì tool nhìn
     * 'Chim đại bàng' thấy "1200 tinh thạch" rồi đem thú cưỡi đi đổi.
     */
    private boolean maBiCam(int ma) {
        for (String phan : getSetting("tinh_thach_ma_cam", "").split(",")) {
            phan = phan.trim();
            if (phan.isEmpty()) continue;
            try {
                int gach = phan.indexOf('-');
                if (gach < 0) {
                    if (Integer.parseInt(phan) == ma) return true;
                } else {
                    int a = Integer.parseInt(phan.substring(0, gach).trim());
                    int b = Integer.parseInt(phan.substring(gach + 1).trim());
                    if (ma >= a && ma <= b) return true;
                }
            } catch (Exception ignore) {}
        }
        return false;
    }

    /** Cấp yêu cầu của món — `E` trên bản mẫu (chính game gọi nó là levelNeed trong toString). */
    private int tinhThachCapYeuCau(Object mon) {
        try {
            Object bm = banMauMon(mon);
            if (bm == null) return -1;
            Field f = bm.getClass().getDeclaredField("E");
            f.setAccessible(true);
            Object v = f.get(bm);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignore) {}
        return -1;
    }

    /**
     * TRANG BỊ ĐẶC BIỆT — nhận bằng MÃ TUỲ CHỌN, thứ mà chính game dùng để tô màu dòng chữ.
     *
     * Dòng "Trang bị Hokage" màu hồng trong bảng thông tin món không phải một hằng chữ — nó là
     * chữ của một TUỲ CHỌN gắn trên món. Bảng thông tin dựng nó thế này:
     *
     *     if (tuyChon.i() == 380) q.addElement(new cw(tuyChon.e(), -13313, ...));   // -13313 = hồng
     *     ... 389, 390, 391 cũng hồng; 379 dùng màu -18377
     *
     * Nên dấu hiệu là MÃ TUỲ CHỌN, không phải chữ. Chắc hơn hẳn so tên hay dò chữ: chữ đổi theo
     * bản dịch, mã thì không.
     *
     * Có dấu này rồi thì không cần bảng cấp/số tinh thạch nữa — nhưng hai tầng khoá không loại
     * trừ nhau, giữ cả hai thì thừa an toàn chứ không thiếu.
     */
    private boolean laTrangBiDacBiet(Object mon) {
        String ds = getSetting("tinh_thach_bo_qua_opt", "");
        if (ds.trim().isEmpty()) return false;
        java.util.Set<Integer> cam = new java.util.HashSet<Integer>();
        for (String s : ds.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try { cam.add(Integer.valueOf(Integer.parseInt(s))); } catch (Exception ignore) {}
        }
        for (int ma : dsMaTuyChon(mon)) if (cam.contains(Integer.valueOf(ma))) return true;
        return false;
    }

    /** Mã của mọi tuỳ chọn gắn trên món — `d_0.a()` bản trả về mảng, rồi `.i()` từng phần tử. */
    private int[] dsMaTuyChon(Object mon) {
        try {
            for (Method m : mon.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                if (!rt.isArray() || rt.getComponentType().isPrimitive()) continue;
                // Lớp tuỳ chọn phải có hàm `i()` trả số — đó là mã mà bảng thông tin rẽ nhánh theo.
                Class<?> tc = rt.getComponentType();
                Method mi = null;
                try { mi = tc.getDeclaredMethod("i"); } catch (Exception ignore) { continue; }
                if (mi.getReturnType() != int.class && mi.getReturnType() != short.class) continue;
                m.setAccessible(true);
                Object arr = m.invoke(mon);
                if (arr == null) continue;
                int n = java.lang.reflect.Array.getLength(arr);
                int[] ra = new int[n];
                int co = 0;
                mi.setAccessible(true);
                for (int i = 0; i < n; i++) {
                    Object tc2 = java.lang.reflect.Array.get(arr, i);
                    if (tc2 == null) continue;
                    Object v = mi.invoke(tc2);
                    if (v instanceof Number) ra[co++] = ((Number) v).intValue();
                }
                int[] gon = new int[co];
                System.arraycopy(ra, 0, gon, 0, co);
                return gon;
            }
        } catch (Throwable ignore) {}
        return new int[0];
    }

    /** Số tinh thạch đổi được của một món — `d_0.w()`. 0 nghĩa là món này không đổi được. */
    private int soTinhThach(Object mon) {
        try {
            Method m = mon.getClass().getDeclaredMethod("w");
            m.setAccessible(true);
            Object v = m.invoke(mon);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignore) {}
        return 0;
    }

    /** Mảng kho theo `cr` — `i.a().a(cr)`, đúng hàm mà đường huỷ của cửa sổ dùng để trả món về. */
    private Object khoTheoCr(int cr) {
        try {
            Object me = getI();
            Method m = me.getClass().getDeclaredMethod("a", int.class);
            m.setAccessible(true);
            return m.invoke(me, Integer.valueOf(cr));
        } catch (Throwable ignore) { return null; }
    }

    /** Cửa sổ đổi tinh thạch đang mở hay không — lớp có `d_0[16]` tên `h` và `byte t`. */
    private Object timCuaSoTinhThach() {
        try {
            if (fkFieldAn == null) return null;
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null) return null;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object p = stack.get(i);
                if (p == null) continue;
                Class<?> c = p.getClass();
                boolean coH = false, coT = false;
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if ("h".equals(f.getName()) && f.getType().isArray()
                            && laLopVatPham(f.getType().getComponentType())) coH = true;
                    if ("t".equals(f.getName()) && f.getType() == byte.class) coT = true;
                }
                if (coH && coT) return p;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * Xếp đồ đổi được vào 16 ô của cửa sổ. Trả về số món đã xếp.
     *
     * Quét MỌI kho mà cửa sổ biết trả về (`cr` 0..3), không chỉ túi chính: cửa sổ có hai thẻ
     * "Trang bị" và "Túi", và đường huỷ của nó dùng `i.a().a(cr)` chứ không phải một mảng cố
     * định — nghĩa là đồ đưa vào có thể đến từ nhiều kho khác nhau.
     *
     * Lấy món ra khỏi kho y như game làm, vì đường huỷ trả về đúng `[cr][ch]`; không lấy ra thì
     * huỷ giữa chừng sẽ nhân đôi món trong bộ nhớ client.
     */
    private int tinhThachXepDo(Object cuaSo) {
        int daXep = 0;
        try {
            Object oCuaSo = null;
            Field fh = cuaSo.getClass().getDeclaredField("h");
            fh.setAccessible(true);
            oCuaSo = fh.get(cuaSo);
            if (oCuaSo == null) return 0;
            int soO = java.lang.reflect.Array.getLength(oCuaSo);
            int crMax = getSettingInt("tinh_thach_kho_max", 3);

            for (int o = 0; o < soO; o++) {
                if (java.lang.reflect.Array.get(oCuaSo, o) != null) continue;
                Object chon = null;
                for (int cr = 0; cr <= crMax && chon == null; cr++) {
                    Object kho = khoTheoCr(cr);
                    if (kho == null || !kho.getClass().isArray()) continue;
                    int n = java.lang.reflect.Array.getLength(kho);
                    for (int b = 0; b < n; b++) {
                        Object mon = java.lang.reflect.Array.get(kho, b);
                        if (mon == null) continue;
                        if (khoaKhongTraoDoi(mon)) continue;          // món khoá thì bỏ
                        int stt = soTinhThach(mon);
                        if (stt <= 0) continue;                       // không đổi được thì bỏ
                        if (maBiCam(truongSo(mon, "e"))) {
                            // `w()` KHÔNG phải lúc nào cũng là số tinh thạch. Với mấy mã vật phẩm
                            // này nó rẽ sang một nhánh khác hẳn và trả về đại lượng khác — đo được
                            // 'Chim đại bàng' ra 1200, 'Áo Choàng Hokage Làng Lá' ra 534. Tin
                            // `w() > 0` là đem thú cưỡi đi đổi lấy tinh thạch.
                            if (tsBoQua < getSettingInt("tinh_thach_log_bo_qua", 20)) {
                                tsBoQua++;
                                log("Tinh thach: BO QUA '" + tenMon(mon) + "' ma="
                                        + truongSo(mon, "e") + " — ma nam trong tinh_thach_ma_cam,"
                                        + " w()=" + stt + " KHONG phai so tinh thach");
                            }
                            continue;
                        }
                        if (laTrangBiDacBiet(mon)) {
                            if (tsBoQua < getSettingInt("tinh_thach_log_bo_qua", 20)) {
                                tsBoQua++;
                                log("Tinh thach: BO QUA '" + tenMon(mon)
                                        + "' — TRANG BI DAC BIET (ma tuy chon "
                                        + java.util.Arrays.toString(dsMaTuyChon(mon)) + ")");
                            }
                            continue;
                        }
                        if (!tinhThachDuocDoi(mon, stt)) {
                            // Ghi ra chứ không bỏ im: món đổi được mà bị luật chặn thì phải nhìn
                            // thấy, không thì lúc thiếu đồ chẳng biết vì luật hẹp hay vì hết đồ.
                            if (tsBoQua < getSettingInt("tinh_thach_log_bo_qua", 20)) {
                                tsBoQua++;
                                log("Tinh thach: bo qua '" + tenMon(mon) + "' — cap "
                                        + tinhThachCapYeuCau(mon) + ", " + stt
                                        + " tinh thach, khong khop tinh_thach_luat");
                            }
                            continue;
                        }
                        // `cr`/`ch` là thứ ĐI TRONG GÓI TIN, lệch với vị trí thật là server xử
                        // nhầm món. Đối chiếu trước khi lấy, lệch thì bỏ qua và ghi lại.
                        if (truongSo(mon, "cr") != cr || truongSo(mon, "ch") != b) {
                            log("Tinh thach: BO QUA '" + tenMon(mon) + "' — cr/ch ghi la ("
                                    + truongSo(mon, "cr") + "," + truongSo(mon, "ch")
                                    + ") nhung dang nam o (" + cr + "," + b + ")");
                            continue;
                        }
                        java.lang.reflect.Array.set(kho, b, null);
                        chon = mon;
                        break;
                    }
                }
                if (chon == null) break;                              // hết đồ đổi được
                java.lang.reflect.Array.set(oCuaSo, o, chon);
                daXep++;
                tsTongDa += soTinhThach(chon);
                log("Tinh thach: xep '" + tenMon(chon) + "' (" + soTinhThach(chon)
                        + " tinh thach) vao o " + o);
            }
        } catch (Throwable e) {
            log("Tinh thach: xep do hong: " + e);
        }
        return daXep;
    }

    /** Gửi lệnh đổi — đúng thứ tự nút Đồng ý của cửa sổ làm: s(số món), rồi s(cr) + t(ch) từng món. */
    private boolean tinhThachGuiDoi(Object cuaSo) {
        try {
            Field fh = cuaSo.getClass().getDeclaredField("h");
            fh.setAccessible(true);
            Object o = fh.get(cuaSo);
            int n = java.lang.reflect.Array.getLength(o);
            int co = 0;
            for (int i = 0; i < n; i++) if (java.lang.reflect.Array.get(o, i) != null) co++;
            if (co == 0) return false;

            Object p = fmClass.getConstructor(byte.class).newInstance((byte) -20);
            Method s = fmClass.getDeclaredMethod("s", int.class);
            Method t = fmClass.getDeclaredMethod("t", int.class);
            s.setAccessible(true); t.setAccessible(true);
            s.invoke(p, Integer.valueOf(co));
            for (int i = 0; i < n; i++) {
                Object mon = java.lang.reflect.Array.get(o, i);
                if (mon == null) continue;
                s.invoke(p, Integer.valueOf(truongSo(mon, "cr")));
                t.invoke(p, Integer.valueOf(truongSo(mon, "ch")));
            }
            fmClass.getDeclaredMethod("aG").invoke(p);
            tsTongMon += co;
            log("Tinh thach: da gui doi " + co + " mon");
            return true;
        } catch (Throwable e) {
            log("Tinh thach: gui doi hong: " + e);
            return false;
        }
    }

    /** Id thực thể của NPC gần điểm đã khai nhất — khỏi phải khai cứng id trong config. */
    private int timNpcGan(int x, int y, int banKinh) {
        int tot = -1, ganNhat = Integer.MAX_VALUE;
        try {
            if (zFieldF == null || frFieldAr == null || frFieldAs == null || frFieldAZ == null) return -1;
            java.util.Vector<?> v = (java.util.Vector<?>) zFieldF.get(getZ());
            if (v == null) return -1;
            for (int i = 0; i < v.size(); i++) {
                Object npc = v.get(i);
                if (npc == null) continue;
                int dx = Math.abs(frFieldAr.getShort(npc) - x);
                int dy = Math.abs(frFieldAs.getShort(npc) - y);
                if (dx > banKinh || dy > banKinh) continue;
                int d = dx + dy;
                if (d < ganNhat) { ganNhat = d; tot = frFieldAZ.getInt(npc); }
            }
        } catch (Throwable ignore) {}
        return tot;
    }

    private void tickTinhThach(long now) {
        if (tsStep == TS_TAT) return;
        if (now < tsNextTime) return;
        tsNextTime = now + getSettingInt("tinh_thach_step_ms", 700);
        try {
            if (tsHanChot > 0 && now > tsHanChot) {
                pushTinhThach("tinh_thach_end", false,
                        "het gio o buoc " + tsStep + " (da doi " + tsTongMon + " mon)");
                stopTinhThach();
                return;
            }

            int map = getSettingInt("tinh_thach_map", 68);
            int x = getSettingInt("tinh_thach_x", 694);
            int y = getSettingInt("tinh_thach_y", 362);

            if (tsStep == TS_DI) {
                if (getCurrentMapId() != map) {
                    // Đích là LÀNG thì đi bằng đúng đường của nút 🏠 Về làng — `navigateToVillage()`
                    // là lối duy nhất trong repo đã chạy thật từ mọi nơi (map treo, hầm, ải), nên
                    // dùng nó thay vì `navigateToMap` trần. Trên map khác thì mới dùng đường chung.
                    int[] lang = gomDiemLang();
                    if (map == lang[0]) navigateToVillage();
                    else navigateToMap(map);
                    if (tsLogDiChuyen != getCurrentMapId()) {
                        tsLogDiChuyen = getCurrentMapId();
                        log("Tinh thach: dang o map " + getCurrentMapId() + " -> di ve map " + map);
                    }
                    return;
                }
                int dx = Math.abs(getPlayerX() - x), dy = Math.abs(getPlayerY() - y);
                int gan = getSettingInt("tinh_thach_toi_noi_px", 60);
                if (dx > gan || dy > gan) {
                    if (dx > 200 || dy > 200) navigateToMapXY(map, x, y);
                    else navigateTo(map, x, y);
                    return;
                }
                closeAnyDialog();   // dọn bảng còn sót, không thì bước sau đọc nhầm menu cũ
                tsStep = TS_MO_NPC;
                return;
            }

            if (tsStep == TS_MO_NPC) {
                int id = getSettingInt("tinh_thach_npc_id", -1);
                if (id < 0) id = timNpcGan(x, y, getSettingInt("tinh_thach_npc_range", 120));
                if (id < 0) {
                    pushTinhThach("tinh_thach_end", false,
                            "khong thay NPC nao quanh (" + x + "," + y + ")");
                    stopTinhThach();
                    return;
                }
                tsNpcId = id;
                sendOpenNpc(tsNpcId);
                log("Tinh thach: mo NPC id=" + tsNpcId);
                tsStep = TS_CHON_MUC;
                return;
            }

            if (tsStep == TS_CHON_MUC) {
                if (timCuaSoTinhThach() != null) {   // menu bỏ qua, cửa sổ đã mở sẵn
                    tsStep = TS_XEP;
                    return;
                }
                if (detectDialog() == null) return;
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) return;
                String kw = getSetting("tinh_thach_menu", "tinh thạch");
                int idx = findMenuIndexByKeyword(menu, kw);
                if (idx < 0) {
                    pushTinhThach("tinh_thach_end", false, "khong thay muc '" + kw
                            + "' trong menu NPC: " + java.util.Arrays.toString(menu));
                    stopTinhThach();
                    return;
                }
                log("Tinh thach: chon muc [" + idx + "] '" + menu[idx] + "'");
                sendSelectMenu(tsNpcId, idx);
                tsStep = TS_XEP;
                tsNextTime = now + getSettingInt("tinh_thach_cho_cua_so_ms", 1200);
                return;
            }

            if (tsStep == TS_XEP) {
                Object cs = timCuaSoTinhThach();
                if (cs == null) return;              // chờ server dựng cửa sổ
                int n = tinhThachXepDo(cs);
                if (n == 0) {
                    pushTinhThach("tinh_thach_end", true, "xong: " + tsLuot + " luot, "
                            + tsTongMon + " mon, uoc " + tsTongDa + " tinh thach");
                    stopTinhThach();   // qua cua ra chung: dong cua so + ban giao di treo
                    return;
                }
                if (!tinhThachGuiDoi(cs)) { stopTinhThach(); return; }
                tsLuot++;
                tsStep = TS_CHOT;
                tsNextTime = now + getSettingInt("tinh_thach_cho_server_ms", 1500);
                return;
            }

            if (tsStep == TS_CHOT) {
                // Chờ server dọn 16 ô rồi mới xếp lượt kế. Đo bằng BẰNG CHỨNG (ô đã trống) chứ
                // không bằng đồng hồ: xếp tiếp khi ô còn đồ là gửi lại chính mấy món vừa gửi.
                Object cs = timCuaSoTinhThach();
                if (cs == null) {
                    // SERVER ĐÓNG CỬA SỔ SAU KHI ĐỔI — mở lại NPC rồi chạy tiếp, KHÔNG dừng.
                    //
                    // Chưa đo được server dọn 16 ô rồi giữ cửa sổ hay đóng hẳn. Trước đây nhánh
                    // này dừng luôn, tức đổi đúng một lượt 16 món rồi thôi dù túi còn đồ — mà
                    // nhìn log lại thấy "xong", không có dấu hiệu gì là còn sót.
                    if (tsLuot >= getSettingInt("tinh_thach_max_luot", 30)) {
                        pushTinhThach("tinh_thach_end", true, "dung o " + tsLuot
                                + " luot (cham tran tinh_thach_max_luot), da doi " + tsTongMon + " mon");
                        stopTinhThach();
                        return;
                    }
                    log("Tinh thach: cua so dong sau luot " + tsLuot + " -> mo lai NPC chay tiep");
                    tsStep = TS_MO_NPC;
                    tsNextTime = now + getSettingInt("tinh_thach_mo_lai_ms", 1200);
                    return;
                }
                Field fh = cs.getClass().getDeclaredField("h");
                fh.setAccessible(true);
                Object o = fh.get(cs);
                int n = java.lang.reflect.Array.getLength(o);
                for (int i = 0; i < n; i++)
                    if (java.lang.reflect.Array.get(o, i) != null) return;   // chưa dọn xong
                tsStep = TS_XEP;
                return;
            }
        } catch (Exception e) {
            log("Tinh thach: loi o buoc " + tsStep + ": " + e.getMessage());
        }
    }

    private void pushTinhThach(String type, boolean ok, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushTinhThach error: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    //  GOM ĐỒ VỀ LEAD
    // ══════════════════════════════════════════════════════════════════════════════════════
    //
    // Mem giao dịch đồ trong danh sách sang cho lead. Ô giao dịch chỉ có 12 nên phải nhiều lượt,
    // giữa hai lượt soi lại túi xem còn món nào trong danh sách không.
    //
    // MỘT MEM MỘT LÚC. Manager giữ hàng đợi và chỉ phát điểm hẹn cho một mem, xong mới sang mem
    // kế. Phát cho cả 12 mem cùng lúc thì 12 nick cùng chạy tới rồi cùng đòi giao dịch với một
    // lead — mà cửa sổ giao dịch chỉ nhận được một đối phương, 11 nick còn lại đứng chờ vô hạn.
    //
    // Tóm tắt phần giao thức dùng ở đây:
    //   lệnh 86 (kèm tên) mời · 85 nhận · 82 khoá (tiền, số món, rồi từng SỐ Ô) · 81 đồng ý.
    //   Đưa món vào ô KHÔNG gửi gói nào — cả danh sách chỉ đi lúc gửi lệnh 82.
    private static final int GOM_TAT     = 0;
    private static final int GOM_M_DI    = 1;   // mem: đang đi tới điểm hẹn
    private static final int GOM_M_CHO   = 2;   // mem: đã tới, chờ lời mời
    private static final int GOM_M_XEP   = 3;   // mem: cửa sổ mở → xếp món vào ô rồi khoá
    private static final int GOM_M_DONGY = 4;   // mem: đã khoá, chờ đủ điều kiện rồi đồng ý
    private static final int GOM_M_XONG  = 5;   // mem: cửa sổ đóng → soi lại túi
    private static final int GOM_L_VE_LANG = 10; // lead: tự đi về làng rồi mới báo vị trí
    private static final int GOM_L_CHO   = 11;  // lead: chờ Manager bảo mời ai
    private static final int GOM_L_HEN   = 15;  // lead: đã có người cần mời, chờ hết khoá 30s
    private static final int GOM_L_MOI   = 12;  // lead: đã gửi lời mời, chờ cửa sổ mở
    private static final int GOM_L_KHOA  = 13;  // lead: chờ đối phương khoá rồi khoá theo (0 món)
    private static final int GOM_L_DONGY = 14;  // lead: đã khoá, chờ đủ điều kiện rồi đồng ý

    private int gomStep = GOM_TAT;
    private String gomLeadName = "";     // tên NHÂN VẬT của lead (mem dùng để nhận đúng lời mời)
    private String gomMoiAi = "";        // lead: đang mời nhân vật nào
    private int gomMap = -1, gomZone = -1, gomX = -1, gomY = -1;
    private long gomNextTime = 0;
    private long gomHanChot = 0;         // hạn chót của MỘT BƯỚC
    private long gomTongHan = 0;         // hạn chót của CẢ LƯỢT GOM — chỉ cái này mới được dừng máy
    private long gomKhoaLuc = 0;         // lúc gửi lệnh khoá, để chờ đủ 5 giây
    private int gomLuot = 0;             // đã xong bao nhiêu lượt giao dịch
    private int gomZoneWaits = 0;        // mem: đã thử chen vào khu của lead bao nhiêu lần

    /** Danh sách mã vật phẩm cần gom, đọc từ `gom_item_ids` trong quest_anchors.cfg. */
    private java.util.Set<Integer> gomDanhSachMa() {
        java.util.Set<Integer> ds = new java.util.HashSet<Integer>();
        for (String s : getSetting("gom_item_ids", "").split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            try { ds.add(Integer.valueOf(Integer.parseInt(s))); } catch (Exception ignore) {}
        }
        return ds;
    }

    /**
     * LEAD TỰ ĐI VỀ LÀNG, không dựa vào lệnh `go_village` mà Manager gửi trước đó.
     *
     * Hai lý do, cả hai đều đã cắn thật:
     *
     * 1. `stopCurrentActivity()` ngay dưới đây kết thúc bằng `clearNavTarget()` — nó XOÁ SẠCH
     *    đích mà `go_village` vừa đặt vài mili giây trước. Manager gửi hai lệnh liền nhau nên
     *    lệnh sau luôn thắng: lead đứng chết tại map treo. Đây đúng là chuyện đo được ngày 03/08.
     *
     * 2. Kể cả không bị xoá thì `go_village` cũng chỉ phát MỘT LẦN. Mem không dính vì bước
     *    GOM_M_DI phát lại lệnh đi mỗi nhịp; lead thì trước đây không có bước đi nào, nên chỉ
     *    cần một lần lỡ là hỏng vĩnh viễn.
     *
     * Nên lead có bước đi của riêng nó, và CHỈ báo vị trí khi đã thật sự tới nơi — báo sớm là
     * Manager phát cho mem một điểm hẹn giữa đường, mem tới đó thì lead đã đi chỗ khác.
     */
    public String startGomLead() {
        stopCurrentActivity();
        gomStep = GOM_L_VE_LANG;
        gomLuot = 0;
        gomMoiAi = "";
        gomNextTime = 0;
        gomZoneCursor = -1;
        gomTradeDoneAt = 0;
        gomHanChot = System.currentTimeMillis() + getSettingInt("gom_den_timeout_ms", 180000);
        gomTongHan = System.currentTimeMillis() + getSettingInt("gom_tong_timeout_ms", 1800000);
        log("Gom do: lam LEAD, dang tu di ve lang");
        return "gom do: lead dang ve lang";
    }

    /** Toạ độ làng lấy từ dòng `village` trong quest_anchors.cfg. */
    private int[] gomDiemLang() {
        loadAnchorConfig();
        if (villageConfig != null) return new int[]{villageConfig[0], villageConfig[1], villageConfig[2]};
        return new int[]{68, 819, 514};
    }

    public String startGomMem(int map, int zone, int x, int y, String leadName) {
        stopCurrentActivity();
        gomStep = GOM_M_DI;
        gomMap = map; gomZone = zone; gomX = x; gomY = y;
        gomLeadName = (leadName == null) ? "" : leadName.trim();
        gomLuot = 0;
        gomNextTime = 0;
        gomMonLuotNay = "";        // khỏi báo nhầm món của lượt gom TRƯỚC sang lượt này
        gomHanChot = System.currentTimeMillis() + getSettingInt("gom_den_timeout_ms", 180000);
        gomTongHan = System.currentTimeMillis() + getSettingInt("gom_tong_timeout_ms", 1800000);
        log("Gom do: lam MEM, di toi map " + map + " khu " + zone + " (" + x + "," + y + ")"
                + " gap lead '" + gomLeadName + "'");
        return "gom do: mem dang di toi diem hen";
    }

    /**
     * CỬA RA DUY NHẤT của máy gom đồ — mọi đường kết thúc đều đi qua đây.
     *
     * Bàn giao sang treo map đặt ở ĐÂY chứ không ở từng nhánh kết thúc, đúng bài học của Sơn cáp:
     * để rải ở các nhánh thì luôn sót một đường (hết giờ, hỏng giữa chừng, hàng đợi cạn sớm), mà
     * sót đường nào là nhân vật đứng không ở làng cả buổi.
     *
     * KHÔNG thoát sớm khi `gomStep` đã là GOM_TAT. Mem xong việc tự đặt bước về 0 trước khi
     * Manager kịp gửi lệnh dừng; thoát sớm là đúng những nick làm xong việc lại không được đi
     * treo. Nick chưa tới lượt cũng vậy — chúng đã bị kéo về làng lúc bấm nút nên vẫn phải đưa đi.
     */
    public String stopGom() { return stopGom(true); }

    /**
     * @param xongCaLuot true = đã chạy hết hàng đợi (hoặc người dùng bấm dừng) ⇒ ĐƯỢC đi treo.
     *                   false = dừng giữa chừng vì lỗi ⇒ chỉ dọn dẹp, KHÔNG đi đâu.
     *
     * Phân biệt vì LEAD không được rời chỗ hẹn khi còn mem chưa gom xong. Nó là điểm hẹn của cả
     * hàng đợi — lead đi treo giữa chừng thì những mem còn lại chạy tới một chỗ trống, hoặc tệ
     * hơn là chạy theo lead sang tận map treo (đúng chuyện lúc 14:16 ngày 03/08).
     */
    public String stopGom(boolean xongCaLuot) {
        if (!xongCaLuot && gomStep >= GOM_L_CHO) {
            // LEAD dừng giữa chừng: dọn cửa sổ, về bước chờ, ĐỨNG NGUYÊN tại điểm hẹn.
            Object csL = timCuaSoGiaoDich();
            if (csL != null) guiLenhGiaoDich(83, 0, null);
            gomMoiAi = "";
            gomStep = GOM_L_CHO;
            gomHanChot = 0;
            log("Gom do: lead dung giua chung -> giu nguyen cho hen, KHONG di treo");
            return "gom do: lead giu nguyen cho hen";
        }
        Object cs = timCuaSoGiaoDich();
        if (cs != null) guiLenhGiaoDich(83, 0, null);   // huỷ cửa sổ còn mở, trả món về túi
        gomStep = GOM_TAT;
        gomMoiAi = "";
        gomHanChot = 0;
        gomTongHan = 0;

        if (afkMapId > 0 && getSettingInt("gom_after_afk", 1) == 1) {
            afkZoneChanged = false;        // để AFK_FARM chịu đổi khu cho lượt mới
            autoCombatRequested = false;   // để nó bật lại đánh
            setEnabled(true);
            setState(TaskState.AFK_FARM);
            log("Gom do: xong -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
            return "gom do: da dung -> di treo map " + afkMapId;
        }
        log("Gom do: da dung (khong co map treo de ban giao)");
        return "gom do: da dung";
    }

    /**
     * KHOÁ GIỮA HAI LƯỢT GIAO DỊCH — luật của game, không phải của tính năng này.
     *
     * Giao dịch xong thì phải chờ `gom_trade_cooldown_ms` mới giao dịch tiếp được. Đo thực tế:
     * 30 giây. Cùng họ với khoá 15 giây khi đổi khu, và xử cùng một kiểu: CHỜ chứ không bỏ lượt.
     * Gửi lời mời vào lúc còn khoá là gói rơi vào hư không, mà log vẫn ghi "đã mời" — đúng kiểu
     * hỏng đã trả giá ở chỗ đổi khu.
     */
    private long gomTradeDoneAt = 0;

    private long gomTradeCooldownLeft(long now) {
        int cd = getSettingInt("gom_trade_cooldown_ms", 30000);
        if (cd <= 0 || gomTradeDoneAt == 0) return 0;
        long troi = now - gomTradeDoneAt;
        return (troi >= cd) ? 0 : (cd - troi);
    }

    /**
     * Lead: nhận yêu cầu mời một nhân vật. Manager gọi, mỗi lúc một người.
     *
     * KHÔNG gửi gói ngay — chỉ ghi nhận rồi để `tickGom` gửi khi hết khoá. Manager gửi lệnh này
     * ngay lúc mem báo sẵn sàng, mà lúc đó khoá 30 giây thường còn chưa hết; từ chối luôn thì
     * Manager không có đường thử lại, hàng đợi đứng im.
     */
    public String gomMoi(String tenNhanVat) {
        if (gomStep != GOM_L_CHO && gomStep != GOM_L_HEN && gomStep != GOM_L_MOI)
            return "gom do: chua o buoc cho moi (buoc " + gomStep + ")";
        if (tenNhanVat == null || tenNhanVat.trim().isEmpty()) return "gom do: thieu ten nguoi nhan";
        gomMoiAi = tenNhanVat.trim();
        gomStep = GOM_L_HEN;
        gomNextTime = 0;
        long con = gomTradeCooldownLeft(System.currentTimeMillis());
        gomHanChot = System.currentTimeMillis() + con
                + getSettingInt("gom_moi_timeout_ms", 20000);
        if (con > 0) {
            log("Gom do: hen moi '" + gomMoiAi + "' — game con khoa giao dich " + con + "ms");
            return "gom do: hen moi " + gomMoiAi + " sau " + con + "ms";
        }
        return "gom do: se moi " + gomMoiAi;
    }

    /** Gửi thật lời mời — chỉ gọi khi đã hết khoá. */
    private boolean gomGuiLoiMoi() {
        try {
            Object p = fmClass.getConstructor(byte.class).newInstance((byte) 86);
            fmWriteUTF.invoke(p, gomMoiAi);
            fmClass.getDeclaredMethod("aG").invoke(p);
            log("Gom do: da moi '" + gomMoiAi + "' giao dich");
            return true;
        } catch (Exception e) {
            log("Gom do: gui loi moi hong: " + e.getMessage());
            return false;
        }
    }

    /**
     * CỬA SỔ GIAO DỊCH đang mở hay không — tìm trên chồng bảng của game (`z.an`).
     *
     * Nhận theo HÌNH DẠNG: lớp nào có HAI mảng vật phẩm cùng độ dài (ô của mình và ô của đối
     * phương) cùng với `aY`/`ar` kiểu int thì đó là cửa sổ giao dịch. Không dò theo tên vì tên
     * thật là `a.S` còn bản dịch ngược gọi là `s_0` — đúng cái bẫy đã sập ở `d_0`.
     */
    private Object timCuaSoGiaoDich() {
        try {
            if (fkFieldAn == null) return null;
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null) return null;
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object p = stack.get(i);
                if (p != null && laCuaSoGiaoDich(p.getClass())) return p;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private boolean laCuaSoGiaoDich(Class<?> c) {
        int soMang = 0;
        boolean coAY = false, coAR = false;
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType().isArray() && laLopVatPham(f.getType().getComponentType())) soMang++;
            if ("aY".equals(f.getName()) && f.getType() == int.class) coAY = true;
            if ("ar".equals(f.getName()) && f.getType() == int.class) coAR = true;
        }
        return soMang >= 2 && coAY && coAR;
    }

    private int gomDocInt(Object o, String ten) {
        try {
            Field f = o.getClass().getDeclaredField(ten);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Exception ignore) { return -1; }
    }

    /** Mảng ô CỦA MÌNH trong cửa sổ giao dịch (`j`), không phải ô của đối phương (`k`). */
    private Object gomOCuaMinh(Object cuaSo) {
        try {
            Field f = cuaSo.getClass().getDeclaredField("j");
            f.setAccessible(true);
            return f.get(cuaSo);
        } catch (Exception ignore) { return null; }
    }

    /**
     * XẾP MÓN TRONG DANH SÁCH TỪ TÚI VÀO Ô GIAO DỊCH. Trả về số món đã xếp.
     *
     * Đúng cách game làm khi người chơi bấm "đưa vào ô giao dịch": lấy món ra khỏi mảng túi rồi
     * đặt vào `j[]`. Đường huỷ giao dịch của game trả ngược lại bằng `i.a().a[mon.ch] = mon`, nên
     * bỏ trống ô túi là bắt buộc — không bỏ thì huỷ giao dịch sẽ nhân đôi món.
     *
     * KHÔNG gửi gói nào ở đây. Cả danh sách chỉ đi lúc gửi lệnh khoá (82).
     *
     * Lọc `ax` theo TỪNG Ô chứ không theo mã: đo ngày 03/08 cho thấy cùng một mã có ô khoá lẫn ô
     * không khoá (mã 277 ở ô 13 khoá 564 cái, ô 59 không khoá 713 cái). Khoá là tính chất của
     * từng chồng đồ.
     */
    /** Tên + số lượng các món đã xếp vào ô giao dịch của LƯỢT đang chạy. Chỉ để báo về
     *  Manager cho người xem biết chính xác món gì đã sang tay — dò lại bằng log của từng
     *  client là phải mở 18 cửa sổ console, không ai làm nổi. */
    private String gomMonLuotNay = "";

    private int gomXepMonVaoO(Object cuaSo) {
        int daXep = 0;
        StringBuilder daGhi = new StringBuilder();
        try {
            java.util.Set<Integer> ds = gomDanhSachMa();
            if (ds.isEmpty()) { log("Gom do: gom_item_ids rong -> khong xep mon nao"); return 0; }
            Object oMinh = gomOCuaMinh(cuaSo);
            Object tui = gomLayTui();
            if (oMinh == null || tui == null) return 0;

            int soO = java.lang.reflect.Array.getLength(oMinh);
            int soTui = java.lang.reflect.Array.getLength(tui);
            for (int o = 0; o < soO; o++) {
                if (java.lang.reflect.Array.get(oMinh, o) != null) continue;   // ô đã có đồ
                int lay = -1;
                for (int b = 0; b < soTui; b++) {
                    Object mon = java.lang.reflect.Array.get(tui, b);
                    if (mon == null) continue;
                    if (khoaKhongTraoDoi(mon)) continue;
                    if (!ds.contains(Integer.valueOf(truongSo(mon, "e")))) continue;
                    // ĐỐI CHIẾU `ch` VỚI VỊ TRÍ THẬT trước khi lấy.
                    //
                    // Món nằm ô nào cũng được — vòng lặp này quét lại cả túi mỗi lượt nên không
                    // hề giả định món đứng yên một chỗ. Nhưng GÓI TIN thì buộc phải mang số ô:
                    // giao thức của game chỉ định danh món bằng `ch` (`fm.t(mon.ch)`), không có
                    // đường nào khác. Nên nếu `ch` lệch với vị trí thật thì server sẽ chuyển
                    // NHẦM MÓN — đúng thứ không được phép xảy ra khi đang gom đồ thật.
                    // Lệch thì BỎ QUA món đó và ghi lại, chứ không đoán bên nào đúng.
                    int ch = truongSo(mon, "ch");
                    if (ch != b) {
                        log("Gom do: BO QUA '" + tenMon(mon) + "' ma=" + truongSo(mon, "e")
                                + " — ch=" + ch + " nhung dang nam o o " + b
                                + " (lech nhau, gui di se chuyen nham mon)");
                        continue;
                    }
                    lay = b; break;
                }
                if (lay < 0) break;                                            // hết món để xếp
                Object mon = java.lang.reflect.Array.get(tui, lay);
                java.lang.reflect.Array.set(oMinh, o, mon);
                java.lang.reflect.Array.set(tui, lay, null);
                daXep++;
                int sl = truongSo(mon, "cf");
                if (daGhi.length() > 0) daGhi.append(", ");
                daGhi.append(tenMon(mon));
                if (sl > 1) daGhi.append(" x").append(sl);
                log("Gom do: xep '" + tenMon(mon) + "' ma=" + truongSo(mon, "e")
                        + " sl=" + sl + " (o tui " + truongSo(mon, "ch") + ") vao o " + o);
            }
        } catch (Exception e) {
            log("Gom do: xep mon hong: " + e.getMessage());
        }
        gomMonLuotNay = daGhi.toString();
        return daXep;
    }

    /** Mảng túi `i.a().a` — nhận theo hình dạng, mảng vật phẩm DÀI NHẤT trên nhân vật. */
    private Object gomLayTui() {
        try {
            Object me = getI();
            if (me == null) return null;
            Object tot = null;
            int dai = -1;
            for (Field f : me.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().isArray()) continue;
                if (!laLopVatPham(f.getType().getComponentType())) continue;
                f.setAccessible(true);
                Object arr = f.get(me);
                if (arr == null) continue;
                int n = java.lang.reflect.Array.getLength(arr);
                if (n > dai) { dai = n; tot = arr; }
            }
            return tot;
        } catch (Exception ignore) { return null; }
    }

    /** Còn bao nhiêu món trong danh sách nằm trong túi và giao dịch được. */
    private int gomDemMonConLai() {
        int co = 0;
        try {
            java.util.Set<Integer> ds = gomDanhSachMa();
            Object tui = gomLayTui();
            if (tui == null || ds.isEmpty()) return 0;
            int n = java.lang.reflect.Array.getLength(tui);
            for (int i = 0; i < n; i++) {
                Object mon = java.lang.reflect.Array.get(tui, i);
                if (mon == null || khoaKhongTraoDoi(mon)) continue;
                if (ds.contains(Integer.valueOf(truongSo(mon, "e")))) co++;
            }
        } catch (Exception ignore) {}
        return co;
    }

    /**
     * Gửi một lệnh của cửa sổ giao dịch.
     *   82 = khoá — kèm tiền và danh sách SỐ Ô của từng món · 81 = đồng ý · 83 = huỷ.
     * `soO` null nghĩa là khoá với 0 món (lead không đưa gì sang).
     */
    private boolean guiLenhGiaoDich(int lenh, int tien, int[] soO) {
        try {
            Object p = fmClass.getConstructor(byte.class).newInstance((byte) lenh);
            if (lenh == 82) {
                Method u = fmClass.getDeclaredMethod("u", int.class);
                Method s = fmClass.getDeclaredMethod("s", int.class);
                Method t = fmClass.getDeclaredMethod("t", int.class);
                u.invoke(p, Integer.valueOf(tien));
                s.invoke(p, Integer.valueOf(soO == null ? 0 : soO.length));
                if (soO != null) for (int o : soO) t.invoke(p, Integer.valueOf(o));
            }
            fmClass.getDeclaredMethod("aG").invoke(p);
            return true;
        } catch (Exception e) {
            log("Gom do: gui lenh " + lenh + " hong: " + e.getMessage());
            return false;
        }
    }

    /** Khoá ô giao dịch — gửi 82 kèm số ô của mọi món đang nằm trong `j[]`. */
    private boolean gomKhoa(Object cuaSo) {
        try {
            Object oMinh = gomOCuaMinh(cuaSo);
            java.util.List<Integer> ds = new java.util.ArrayList<Integer>();
            if (oMinh != null) {
                int n = java.lang.reflect.Array.getLength(oMinh);
                for (int i = 0; i < n; i++) {
                    Object mon = java.lang.reflect.Array.get(oMinh, i);
                    if (mon != null) ds.add(Integer.valueOf(truongSo(mon, "ch")));
                }
            }
            int[] mang = new int[ds.size()];
            for (int i = 0; i < mang.length; i++) mang[i] = ds.get(i).intValue();
            int tien = gomDocInt(cuaSo, "as");
            if (tien < 0) tien = 0;
            if (!guiLenhGiaoDich(82, tien, mang)) return false;

            // Đặt trạng thái tại máy y như game làm khi người chơi bấm Khoá: gán aY=1 TRƯỚC khi
            // gửi, và nếu đối phương đã khoá rồi thì lên đồng hồ 5 giây. Không gán thì logic của
            // chính game đọc cửa sổ này sẽ thấy trạng thái cũ.
            Field fAY = cuaSo.getClass().getDeclaredField("aY");
            fAY.setAccessible(true);
            fAY.setInt(cuaSo, 1);
            int ar = gomDocInt(cuaSo, "ar");
            if (ar == 1) gomDatDongHo(cuaSo);
            gomKhoaLuc = System.currentTimeMillis();
            log("Gom do: da khoa " + mang.length + " mon");
            return true;
        } catch (Exception e) {
            log("Gom do: khoa hong: " + e.getMessage());
            return false;
        }
    }

    /** Lên đồng hồ 5 giây của cửa sổ, y như game làm khi cả hai bên cùng khoá. */
    private void gomDatDongHo(Object cuaSo) {
        try {
            Field fp = cuaSo.getClass().getDeclaredField("p");
            fp.setAccessible(true);
            fp.setLong(cuaSo, System.currentTimeMillis() + 5999L);
        } catch (Exception ignore) {}
    }

    /** Đủ điều kiện bấm Đồng ý chưa — đúng ba điều kiện game đặt ra, không nới một cái nào. */
    private boolean gomDuocDongY(Object cuaSo) {
        int aY = gomDocInt(cuaSo, "aY");
        int ar = gomDocInt(cuaSo, "ar");
        if (aY != 1 || ar <= 0) return false;
        try {
            Field fp = cuaSo.getClass().getDeclaredField("p");
            fp.setAccessible(true);
            return fp.getLong(cuaSo) < System.currentTimeMillis() + 999L;
        } catch (Exception ignore) {
            // Không đọc được đồng hồ thì lùi về đo bằng lúc mình khoá — kém chắc hơn nhưng vẫn
            // tôn trọng 5 giây, còn hơn bấm sớm rồi server chặn.
            return gomKhoaLuc > 0 && System.currentTimeMillis() - gomKhoaLuc >= 5200;
        }
    }

    private boolean gomDongY(Object cuaSo) {
        if (!guiLenhGiaoDich(81, 0, null)) return false;
        try {
            Field fAY = cuaSo.getClass().getDeclaredField("aY");
            fAY.setAccessible(true);
            fAY.setInt(cuaSo, 2);
        } catch (Exception ignore) {}
        log("Gom do: da bam dong y");
        return true;
    }

    private void tickGom(long now) {
        if (gomStep == GOM_TAT) return;
        if (now < gomNextTime) return;
        gomNextTime = now + getSettingInt("gom_step_ms", 700);

        try {
            // HAI HẠN CHÓT KHÁC NHAU, đừng gộp làm một.
            //
            // `gomTongHan` là hạn của CẢ LƯỢT GOM; chạm nó mới thật sự dừng và bàn giao đi treo.
            // `gomHanChot` chỉ là hạn của MỘT BƯỚC. Trước đây hết hạn bước cũng gọi `stopGom()`,
            // mà `stopGom()` giờ đưa nhân vật đi treo map — nên lead chờ mời quá 20 giây là bỏ
            // chỗ hẹn, chạy thẳng về map treo, rồi vẫn báo vị trí mới cho Manager và kéo luôn mem
            // chạy theo. Đúng chuyện xảy ra lúc 14:16 ngày 03/08: lead nhảy sang map 74.
            //
            // Hết hạn một bước của LEAD thì chỉ bỏ lượt mời đó và quay về chờ, KHÔNG dừng máy:
            // thứ hỏng là một lời mời, không phải cả lượt gom.
            if (gomTongHan > 0 && now > gomTongHan) {
                pushGom("gom_loi", "het gio ca luot gom (buoc " + gomStep + ")");
                log("Gom do: het gio ca luot -> dung");
                // Hết giờ KHÔNG phải "xong cả lượt" — lead đứng nguyên tại chỗ hẹn, để Manager
                // còn kịp bảo dừng hẳn. Mem thì đi treo bình thường.
                stopGom(false);
                return;
            }
            if (gomHanChot > 0 && now > gomHanChot) {
                if (gomStep >= GOM_L_CHO) {          // mọi bước của LEAD
                    log("Gom do: het gio o buoc " + gomStep + " voi '" + gomMoiAi
                            + "' -> bo luot moi nay, quay ve cho (KHONG dung may)");
                    pushGom("gom_lead_loi", "het gio o buoc " + gomStep + " voi '" + gomMoiAi + "'");
                    Object csCu = timCuaSoGiaoDich();
                    if (csCu != null) guiLenhGiaoDich(83, 0, null);   // đóng cửa sổ dở dang
                    gomMoiAi = "";
                    gomStep = GOM_L_CHO;
                    gomHanChot = 0;                  // chờ Manager bảo mời người kế
                    return;
                }
                pushGom("gom_loi", "het gio o buoc " + gomStep);
                log("Gom do: het gio o buoc " + gomStep + " -> dung");
                stopGom();
                return;
            }

            Object cuaSo = timCuaSoGiaoDich();

            // ── MEM ───────────────────────────────────────────────────────────────────────
            if (gomStep == GOM_M_DI) {
                if (getCurrentMapId() != gomMap) { navigateToMap(gomMap); return; }
                if (gomZone >= 0 && getCurrentZoneId() != gomZone) {
                    long con = sendChangeZone(gomZone);
                    if (con > 0) { gomNextTime = now + con + 250; return; }  // game khoá 15s
                    // KHU CỦA LEAD ĐẦY NGƯỜI — mem không chen vào được.
                    // Không phải lỗi của mem nên mem KHÔNG bỏ cuộc: chỉ báo về Manager rồi thử
                    // tiếp, còn thứ phải đổi là chỗ đứng của LEAD. Manager bảo lead nhảy khu,
                    // lead báo lại khu mới, Manager phát lại điểm hẹn cho chính mem này.
                    gomZoneWaits++;
                    if (gomZoneWaits > getSettingInt("gom_zone_wait_tries", 3)) {
                        gomZoneWaits = 0;
                        pushGom("gom_zone_full", "khong chen duoc vao khu " + gomZone
                                + " cua lead (dang o khu " + getCurrentZoneId() + ") - khu do day nguoi");
                    }
                    gomNextTime = now + getSettingInt("gom_zone_poll_ms", 2000);
                    return;
                }
                gomZoneWaits = 0;
                int dx = Math.abs(getPlayerX() - gomX), dy = Math.abs(getPlayerY() - gomY);
                int nguong = getSettingInt("gom_toi_noi_px", 80);
                if (dx > nguong || dy > nguong) {
                    // Cùng chính sách đi lại với Cấm thuật: quãng dài dùng auto-nav gốc của game
                    // (thứ đi xuyên map được), quãng ngắn mới áp sát. Trộn ngược lại thì nhân vật
                    // đơ tại chỗ ở quãng dài — đã trả giá cho chuyện này ở Cấm thuật.
                    int xa = getSettingInt("gom_far_px", 200);
                    if (dx > xa || dy > xa) navigateToMapXY(gomMap, gomX, gomY);
                    else navigateTo(gomMap, gomX, gomY);
                    return;
                }
                // KHÔNG CÓ MÓN NÀO THÌ KHỎI GIAO DỊCH. Xin một lượt rỗng vẫn ăn trọn khoá 30
                // giây của game rồi mới báo hết — đo được lúc 14:14 ngày 03/08, mem báo
                // "con 0 mon can gom" mà vẫn chạy một lượt. Nhân với 11 mem là mất vài phút
                // vào việc không làm gì.
                int conLai = gomDemMonConLai();
                if (conLai == 0) {
                    pushGom("gom_mem_done", "khong co mon nao trong danh sach -> bo qua, khong giao dich");
                    log("Gom do: khong co mon nao can gom -> bao xong luon");
                    stopGom();
                    return;
                }
                gomStep = GOM_M_CHO;
                gomHanChot = now + getSettingInt("gom_cho_moi_timeout_ms", 120000);
                pushGom("gom_mem_ready", "da toi diem hen, con " + conLai + " mon can gom");
                log("Gom do: da toi diem hen, cho loi moi (" + conLai + " mon)");
                return;
            }

            if (gomStep == GOM_M_CHO) {
                // Lời mời tới thì game dựng hộp thoại; ta chỉ cần gửi lệnh NHẬN rồi dọn hộp
                // thoại đó đi. Không dò hộp thoại mà cứ gửi bừa: Manager chỉ bảo lead mời đúng
                // một mem một lúc, nên lúc này chỉ có thể là lời mời của lead.
                if (cuaSo != null) {                        // đối phương mở được cửa sổ rồi
                    gomStep = GOM_M_XEP;
                    gomHanChot = now + getSettingInt("gom_giao_dich_timeout_ms", 90000);
                    return;
                }
                if (coHopThoaiXacNhan()) {
                    guiLenhGiaoDich(85, 0, null);
                    closeConfirmPopup();
                    log("Gom do: da nhan loi moi giao dich");
                }
                return;
            }

            if (gomStep == GOM_M_XEP) {
                if (cuaSo == null) { gomStep = GOM_M_XONG; return; }
                if (gomDocInt(cuaSo, "aY") == 0) {
                    int n = gomXepMonVaoO(cuaSo);
                    if (n == 0) {
                        // Không còn món nào để đưa -> đóng cửa sổ, báo xong.
                        guiLenhGiaoDich(83, 0, null);
                        gomStep = GOM_M_XONG;
                        return;
                    }
                    gomKhoa(cuaSo);
                }
                gomStep = GOM_M_DONGY;
                return;
            }

            if (gomStep == GOM_M_DONGY) {
                if (cuaSo == null) { gomStep = GOM_M_XONG; return; }
                if (gomDuocDongY(cuaSo)) gomDongY(cuaSo);
                return;
            }

            if (gomStep == GOM_M_XONG) {
                if (cuaSo != null) return;                  // cửa sổ chưa đóng hẳn, chờ
                gomLuot++;
                gomTradeDoneAt = now;                       // mem cũng dính khoá 30s như lead
                int con = gomDemMonConLai();
                if (con > 0) {
                    gomStep = GOM_M_CHO;
                    gomHanChot = now + getSettingInt("gom_cho_moi_timeout_ms", 120000);
                    pushGom("gom_mem_con", "xong luot " + gomLuot + ", con " + con + " mon", gomMonLuotNay);
                    log("Gom do: xong luot " + gomLuot + ", con " + con + " mon -> cho moi tiep");
                } else {
                    pushGom("gom_mem_done", "het mon trong danh sach sau " + gomLuot + " luot", gomMonLuotNay);
                    log("Gom do: het mon trong danh sach -> bao xong");
                    // Qua cửa ra chung để được bàn giao đi treo, thay vì tự đặt bước về 0.
                    // Mem xong việc mà đứng không ở làng là mất cả buổi farm của nick đó.
                    stopGom();
                }
                return;
            }

            // ── LEAD ──────────────────────────────────────────────────────────────────────
            if (gomStep == GOM_L_VE_LANG) {
                int[] lang = gomDiemLang();
                if (getCurrentMapId() != lang[0]) { navigateToVillage(); return; }
                int dx = Math.abs(getPlayerX() - lang[1]), dy = Math.abs(getPlayerY() - lang[2]);
                if (dx > getSettingInt("gom_toi_noi_px", 80) || dy > getSettingInt("gom_toi_noi_px", 80)) {
                    int xa = getSettingInt("gom_far_px", 200);
                    if (dx > xa || dy > xa) navigateToMapXY(lang[0], lang[1], lang[2]);
                    else navigateTo(lang[0], lang[1], lang[2]);
                    return;
                }
                gomStep = GOM_L_CHO;
                gomHanChot = now + getSettingInt("gom_tong_timeout_ms", 1800000);
                log("Gom do: lead da ve toi lang (" + getPlayerX() + "," + getPlayerY() + ")");
                gomBaoViTri();     // tới nơi rồi mới báo — báo sớm là mem chạy tới chỗ trống
                return;
            }

            if (gomStep == GOM_L_CHO) {
                if (cuaSo != null) {                        // mem chủ động mở trước
                    gomStep = GOM_L_KHOA;
                    gomHanChot = now + getSettingInt("gom_giao_dich_timeout_ms", 90000);
                }
                return;
            }

            if (gomStep == GOM_L_HEN) {
                long con = gomTradeCooldownLeft(now);
                if (con > 0) { gomNextTime = now + Math.min(con + 250, 5000); return; }
                if (!gomGuiLoiMoi()) { gomStep = GOM_L_CHO; return; }
                gomStep = GOM_L_MOI;
                gomHanChot = now + getSettingInt("gom_moi_timeout_ms", 20000);
                return;
            }

            if (gomStep == GOM_L_MOI) {
                if (cuaSo == null) return;                  // chờ đối phương bấm đồng ý
                gomStep = GOM_L_KHOA;
                gomHanChot = now + getSettingInt("gom_giao_dich_timeout_ms", 90000);
                return;
            }

            if (gomStep == GOM_L_KHOA) {
                if (cuaSo == null) { gomLeadXongLuot(); return; }
                // Lead không đưa gì sang. Chỉ khoá SAU KHI đối phương đã khoá — khoá trước thì
                // mem chưa kịp xếp món, hai bên cùng khoá là chốt một lượt rỗng.
                if (gomDocInt(cuaSo, "aY") == 0 && gomDocInt(cuaSo, "ar") > 0) gomKhoa(cuaSo);
                if (gomDocInt(cuaSo, "aY") == 1) gomStep = GOM_L_DONGY;
                return;
            }

            if (gomStep == GOM_L_DONGY) {
                if (cuaSo == null) { gomLeadXongLuot(); return; }
                if (gomDuocDongY(cuaSo)) gomDongY(cuaSo);
                return;
            }
        } catch (Exception e) {
            log("Gom do: loi o buoc " + gomStep + ": " + e.getMessage());
        }
    }

    private void gomLeadXongLuot() {
        gomLuot++;
        gomTradeDoneAt = System.currentTimeMillis();   // bắt đầu tính khoá 30s của game
        gomStep = GOM_L_CHO;
        gomHanChot = System.currentTimeMillis() + getSettingInt("gom_tong_timeout_ms", 1800000);
        pushGom("gom_lead_luot", "xong luot " + gomLuot + " voi '" + gomMoiAi + "'");
        log("Gom do: lead xong luot " + gomLuot + " voi '" + gomMoiAi + "'");
    }

    /** Có hộp thoại xác nhận (lời mời) đang hiện không — dùng lại bộ đọc popup sẵn có. */
    private boolean coHopThoaiXacNhan() {
        String t = readConfirmPopupText();
        return t != null && !t.trim().isEmpty();
    }

    /** Lead báo về Manager: đang đứng ở đâu để Manager phát cho mem. */
    public String gomBaoViTri() {
        try {
            // CHƯA TỚI LÀNG THÌ CHƯA BÁO. Manager hỏi lại mỗi 5 giây, mà lead đi xuyên map mất
            // lâu hơn thế nhiều — báo vị trí giữa đường là Manager phát cho mem một điểm hẹn mà
            // lead sẽ rời khỏi ngay sau đó, mem tới nơi thì đứng không.
            if (gomStep == GOM_L_VE_LANG) {
                return "gom do: lead con dang ve lang (" + getCurrentMapId()
                        + " " + getPlayerX() + "," + getPlayerY() + ") - chua bao vi tri";
            }
            // KHÔNG CÒN LÀM LEAD THÌ TUYỆT ĐỐI KHÔNG BÁO VỊ TRÍ.
            //
            // Manager hỏi lại mỗi 5 giây suốt lượt gom. Nếu máy gom đã tắt (hết giờ, bị dừng,
            // hoặc đã bàn giao đi treo) mà vẫn trả lời thì Manager phát cho mem đúng toạ độ
            // MAP TREO của lead — mem bỏ điểm hẹn ở làng chạy theo sang map treo. Đo được lúc
            // 14:16 ngày 03/08: lead báo map 74 khu 9, mem lập tức được phát điểm hẹn đó.
            if (gomStep < GOM_L_CHO) {
                return "gom do: khong con o che do lead (buoc " + gomStep + ") - khong bao vi tri";
            }
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return "gom do: chua noi duoc Manager";
            String tenNV = "";
            try {
                Object me = getI();
                Field f = me.getClass().getDeclaredField("l");
                f.setAccessible(true);
                Object v = f.get(me);
                if (v != null) tenNV = String.valueOf(v);
            } catch (Exception ignore) {}
            w.print("{\"type\":\"gom_lead_at\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"map\":" + getCurrentMapId() + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + getPlayerX() + ",\"y\":" + getPlayerY()
                    + ",\"lead\":\"" + escapeJson(tenNV) + "\"}\n");
            w.flush();
            return "gom do: da bao vi tri map " + getCurrentMapId() + " khu " + getCurrentZoneId();
        } catch (Exception e) {
            return "gom do: bao vi tri hong: " + e.getMessage();
        }
    }

    /**
     * Lead nhảy sang khu khác khi mem báo khu đầy người.
     *
     * GIỮ CON TRỎ giữa các lần gọi, y như Cấm thuật và Sơn cáp. Trước đây truyền thẳng `-1` nên
     * mỗi lần gọi là dò lại từ đầu dãy: đang ở khu 30 thì nhảy 29, đang ở 29 thì nhảy lại 30 —
     * quẩn giữa hai khu mãi mà không bao giờ thử tới khu thứ ba, trong khi log vẫn báo "đã đổi
     * sang khu N" nên nhìn vào tưởng đang quét.
     */
    private int gomZoneCursor = -1;

    public String gomDoiKhu() {
        try {
            int nay = getCurrentZoneId();
            int max = getSettingInt("gom_max_zone", getSettingInt("dia_cung_max_zone", 30));
            long khoa = zoneCooldownLeft(System.currentTimeMillis());
            if (khoa > 0) return "gom do: game con khoa doi khu " + khoa + "ms";
            gomZoneCursor = nextZoneToTry(gomZoneCursor, nay, max);
            long con = sendChangeZone(gomZoneCursor);
            if (con > 0) return "gom do: game con khoa doi khu " + con + "ms";
            return "gom do: da doi sang khu " + gomZoneCursor;
        } catch (Exception e) {
            return "gom do: doi khu hong: " + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BÙA UẾ THỔ — người chơi khác khoá nick mình lại bằng một bảng captcha
    // ══════════════════════════════════════════════════════════════════════
    //
    // Vật phẩm mã 617 "Bùa uế thổ": yểm lên đối phương, đối phương KHÔNG được tự hồi sinh hay
    // về thành cho tới khi có người nhập đúng mã captcha. Mỗi lá chỉ tác dụng lên một người.
    //
    // KHÔNG CÓ ĐỒNG HỒ ĐẾM NGƯỢC. Con số "5 phút" là hạn dùng của lá bùa bên phía kẻ yểm, không
    // phải hạn chịu đựng của nạn nhân: đã dính rồi thì nick nằm đó tới khi có người nhập đúng
    // mã, hoặc tới khi tắt hẳn client. Nhầm chỗ này là nhầm cả mức nghiêm trọng của mọi đường
    // hỏng bên dưới — "chờ 5 phút là xong" với "nằm chết tới sáng mai" là hai chuyện khác hẳn.
    //
    // VÌ SAO TOOL PHẢI BIẾT — và đây mới là phần đắt, không phải bốn ký tự kia:
    // nick nằm chết không sinh ra gói sự kiện nào, nó im hệt nick đang đánh ngon. Trong khi đó
    // máy trạng thái của hoạt động vẫn đếm hạn rồi bắn ra "het gio o buoc N" ⇒ một dòng log đổ
    // lỗi sai chỗ, và lượt đang chạy bị bỏ dở vì tool tự kết luận hỏng. Đúng kiểu đã mất cả
    // buổi để truy với câu "khong thay NPC Raikage" hôm 08/08: câu log đổ lỗi cho NPC trong khi
    // lỗi thật là đứng nhầm map.
    //
    // NHẬN BIẾT ĐƯỢC LÀ VÌ CHUỖI NẰM Ở CLIENT: câu "đã yểm bùa uế thổ, nhập mã đã giải trừ bùa
    // chú" nằm trong bảng hằng chữ com/c/a/a.class — client tự dựng chứ không phải server đẩy
    // xuống, nên readAnyDialogText() đọc ra được. (Đối chiếu: "Khoảng cách quá xa" của giao
    // dịch KHÔNG có trong bất kỳ lớp nào ⇒ câu đó do server gửi, không dò kiểu này được.)
    //
    // Câu của game có kèm TÊN KẺ YỂM, nên báo về là biết luôn ai làm.
    private long buaKiemKe = 0;
    private boolean buaDangDinh = false;
    private long buaBangMoTu = 0;      // bảng lạ hiện từ lúc nào (để dump khi nó nằm lì)
    private long buaDumpKe = 0;        // chặn dump liên tục
    private long buaGuiLuc = 0;        // lúc gửi mã lên server; >0 = đang chờ xem có qua không
    private int  buaLanThu = 0;        // lần gửi ảnh thứ mấy trong cùng một lượt bị yểm

    /**
     * Chụp ảnh captcha đang hiện rồi đẩy về Manager. Dùng cho CẢ lần đầu lẫn các lần gõ sai.
     *
     * Gộp làm một chỗ vì hai đường đó phải giống hệt nhau: lần gõ sai mà gửi thiếu ảnh thì người
     * dùng không có gì để đọc, còn gửi ảnh cũ thì càng tệ — server đã đổi mã, nhìn ảnh cũ gõ lại
     * là sai tiếp.
     */
    private void guiAnhCaptcha(String noiDung, int lanThu) {
        Object bang = timBangCaptcha();
        byte[] png = anhCaptchaPng(bang);
        String kem = (lanThu > 1) ? (" (lan " + lanThu + ")") : "";
        if (png != null && png.length > 0) {
            // java.util.Base64 chứ không javax.xml.bind.DatatypeConverter: cái sau nằm trong JAXB,
            // có ở JRE 8 nhưng bị gỡ khỏi Java 11 — dùng nó là gài sẵn một quả mìn cho ngày đổi
            // JRE, mà lúc đó lỗi hiện ra ở đây chứ không ở chỗ đổi.
            pushBuaAnh(noiDung + kem, java.util.Base64.getEncoder().encodeToString(png));
            log("Bua ue tho: da gui anh captcha " + png.length + " byte len Manager" + kem);
        } else {
            pushBua("bua_ue_tho", noiDung + kem);
        }
    }

    /**
     * GOM MỌI CHỮ ĐANG HIỆN TRÊN CHỒNG BẢNG — không lọc theo lớp bảng, không lọc theo tên trường.
     *
     * Vì sao phải có, và đây là bài học trả giá ngay trong lần chạy thật đầu tiên:
     * `readConfirmPopupText()` chỉ nhận bảng thuộc lớp `a.cd` và chỉ đọc đúng MỘT trường tên `w`.
     * Bảng captcha của bùa uế thổ là lớp khác ⇒ phép dò trượt hoàn toàn, không có lấy một dòng
     * log, trong khi popup đang sờ sờ trên màn hình.
     *
     * Bài học chung: dò theo HÌNH DẠNG (có trường String nào chứa cụm chữ này không) bền hơn dò
     * theo TÊN LỚP và TÊN TRƯỜNG. Tên là thứ trình làm rối đổi được và cũng là thứ mình đoán;
     * còn "trên màn hình đang có chữ này" là thứ quan sát được.
     *
     * Quét cả String[] chứ không chỉ String: chữ trong bảng thường bị cắt sẵn thành từng dòng.
     * Quét cả lớp CHA: trường chứa chữ hay nằm ở lớp bảng gốc chứ không ở lớp con.
     */
    private String docMoiChuTrenBang(int gioiHan) {
        StringBuilder sb = new StringBuilder();
        try {
            if (fkFieldAn == null) return "";
            Object zInst = getZ();
            if (zInst == null) return "";
            Object o = fkFieldAn.get(zInst);
            if (!(o instanceof java.util.Vector)) return "";
            java.util.Vector<?> stack = (java.util.Vector<?>) o;
            java.util.IdentityHashMap<Object, Boolean> daXem =
                    new java.util.IdentityHashMap<Object, Boolean>();
            int[] ngan = new int[]{ getSettingInt("bua_ue_tho_so_node", 400) };
            int sau = getSettingInt("bua_ue_tho_sau", 3);
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object p = stack.get(i);
                if (p == null) continue;
                // BỎ QUA CHÍNH a.z. Nó nằm trong chồng bảng (nó cũng là một bảng), nhưng nó là cả
                // thế giới trò chơi — Vector 90 quái, 425 phần tử, hàng chục đối tượng con. Đi vào
                // đó là duyệt gần hết bộ nhớ game mỗi 3 giây để tìm một câu nằm ở bảng trên cùng.
                if (p == zInst) continue;
                gomChuTrongCay(p, daXem, sau, sb, gioiHan, ngan);
                if (sb.length() > gioiHan) break;
            }
        } catch (Exception ignore) {}
        return sb.toString();
    }

    /**
     * ĐI VÀO CÂY WIDGET, không chỉ đọc node trên cùng.
     *
     * Bài học từ bản mổ 16:30 ngày 09/08: toàn bộ trường String của bảng captcha chỉ có ba cái
     * NHÃN — 'Mã xác nhận:', 'Nhắc nhở', 'Xác nhận'. Câu thông báo thật ("… đã yểm bùa uế thổ …")
     * không nằm ở đó mà nằm trong `a.bd.ae` — Vector chứa các widget CON của bảng. Bản đọc trước
     * chỉ quét trường String của node trên cùng nên không thể thấy, và sẽ không bao giờ thấy dù
     * chờ bao lâu.
     *
     * Bảng trong game là một CÂY, nên muốn đọc chữ trên bảng thì phải duyệt cây. Ba cái chặn để
     * việc đó không thành duyệt cả bộ nhớ: giới hạn độ SÂU, giới hạn số NODE, và bỏ qua chính
     * a.z. Thiếu một trong ba là mỗi 3 giây lại quét gần hết game.
     *
     * Dùng IdentityHashMap chứ không HashSet: các lớp của game có thể tự định nghĩa equals/hashCode
     * theo cách bất kỳ, mà thứ cần chống ở đây là đi lại ĐÚNG MỘT đối tượng (cây widget có con
     * trỏ ngược về cha: `a.bb.c = bd`), tức so theo danh tính chứ không theo giá trị.
     */
    private void gomChuTrongCay(Object o, java.util.IdentityHashMap<Object, Boolean> daXem,
                                int sau, StringBuilder sb, int gioiHan, int[] ngan) {
        if (o == null || sau < 0 || sb.length() > gioiHan || ngan[0] <= 0) return;
        if (daXem.containsKey(o)) return;
        daXem.put(o, Boolean.TRUE);
        ngan[0]--;
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Field[] fs;
                try { fs = c.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fs) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (sb.length() > gioiHan || ngan[0] <= 0) return;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        if (v instanceof String) {
                            String s = v.toString().trim();
                            if (!s.isEmpty()) sb.append(s).append(" | ");
                        } else if (v instanceof String[]) {
                            for (String s : (String[]) v)
                                if (s != null && !s.trim().isEmpty())
                                    sb.append(s.trim()).append(" | ");
                        } else if (v instanceof java.util.Vector) {
                            java.util.Vector<?> con = (java.util.Vector<?>) v;
                            for (int k = 0; k < con.size(); k++)
                                gomChuTrongCay(con.get(k), daXem, sau - 1, sb, gioiHan, ngan);
                        } else if (v.getClass().getName().startsWith("a.")
                                && !v.getClass().isArray()) {
                            gomChuTrongCay(v, daXem, sau - 1, sb, gioiHan, ngan);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}
    }

    /**
     * Số CỬA SỔ đang mở — KHÔNG tính chính `a.z`.
     *
     * `z.an` luôn chứa sẵn `a.z` (bản thân màn chơi cũng là một "bảng"), nên đếm thô thì lúc nào
     * cũng ≥ 1 và "có bảng nằm lì" thành LUÔN ĐÚNG. Hậu quả đo được lúc 18:04: hai nick không hề
     * bị yểm vẫn mổ xẻ toàn bộ `a.z` — hàng nghìn dòng mỗi nick — đổ vào log và file soi.
     *
     * Bằng chứng nằm ngay trong bản dump đó: `an:Vector(1)` khi không mở gì, `an:Vector(2)` khi
     * bảng captcha đang hiện. Trừ đi chính nó là ra đúng số cửa sổ thật.
     */
    private int soBangDangMo() {
        try {
            if (fkFieldAn == null) return 0;
            Object zInst = getZ();
            if (zInst == null) return 0;
            Object o = fkFieldAn.get(zInst);
            if (!(o instanceof java.util.Vector)) return 0;
            java.util.Vector<?> v = (java.util.Vector<?>) o;
            int n = 0;
            for (int i = 0; i < v.size(); i++) {
                Object p = v.get(i);
                if (p != null && p != zInst) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private void tickBuaUeTho(long now) {
        try {
            if (getSettingInt("bua_ue_tho_bao", 1) != 1) return;
            if (now < buaKiemKe) return;
            buaKiemKe = now + getSettingInt("bua_ue_tho_soi_ms", 3000);

            // KHÔNG chặn theo reflectionReady ở ngoài như các máy khác: cờ đó chỉ bật khi có ai
            // gọi initReflection, mà nick chỉ đăng nhập rồi để đó thì không ai gọi — đúng lỗi đã
            // gặp ở tickClosePopupAfterLogin. Thử ngay tại đây, và đã bị nhịp soi 3s ghì lại nên
            // không tốn gì.
            if (!reflectionReady) { initReflection(); if (!reflectionReady) return; }

            // ── NHẬN BIẾT BẰNG CHÍNH CÁI BẢNG, KHÔNG BẰNG CHỮ ─────────────────────────────
            //
            // Ba lần dính bùa liên tiếp (16:23, 16:30, 16:44 ngày 09/08) đều dựng bảng `a.ew` với
            // `M = -44`, và bản mổ toàn cây widget KHÔNG chứa một chữ "bùa"/"yểm"/"uế thổ" nào —
            // game lấy câu từ bảng hằng chữ lúc VẼ, không cất vào bảng. Nghĩa là khớp chữ không
            // bao giờ ăn, dù duyệt cây sâu tới đâu. Hai vòng sửa trước đều đâm vào bức tường đó.
            //
            // Bảng có mặt LÀ bằng chứng, và nó còn chắc hơn chữ: chữ do người dịch đặt và đổi được
            // giữa hai câu (đã cắn đúng chuyện đó lúc 16:23), còn lớp bảng + mã loại là thứ client
            // dùng để dựng đúng cái hộp này.
            //
            // `S` KHÔNG phải ô nhập — nó giữ TÊN KẺ YỂM ('xinhghe' lúc 16:44, rỗng khi game dùng
            // câu không kèm tên). Suýt cho ghi mã đè lên đó.
            Object bangCt = timBangCaptcha();
            boolean dinhBang = false;
            String keYem = "";
            if (bangCt != null) {
                int maCan = getSettingInt("bua_ue_tho_ma_bang", -44);
                int maCo = docSoTruong(bangCt, getSetting("bua_ue_tho_truong_ma", "M"), Integer.MIN_VALUE);
                dinhBang = (maCan == Integer.MIN_VALUE) || (maCo == maCan) || (maCo == Integer.MIN_VALUE);
                keYem = docChuoiTruong(bangCt, getSetting("bua_ue_tho_truong_ke_yem", "S"));
            }

            String chu = noAccent(getSetting("bua_ue_tho_chu", "bua ue tho")).toLowerCase();

            // HAI PHÉP ĐỌC, HẸP TRƯỚC RỘNG SAU.
            // Hẹp cho ra chữ SẠCH để bắn lên Telegram (đúng một câu). Rộng thì chắc chắn thấy
            // nhưng trộn mọi chữ trên bảng lại. Thử hẹp trước, trượt mới dùng rộng — và lần chạy
            // thật đầu tiên cho thấy phép hẹp trượt, nên nhánh rộng KHÔNG phải để cho vui.
            String t = readAnyDialogText();
            boolean dinh = dinhBang;
            if (dinh) {
                t = (keYem.isEmpty() ? "" : keYem + " ") + "da yem bua ue tho - can nhap captcha";
            }
            if (!dinh) dinh = (t != null) && noAccent(t).toLowerCase().contains(chu);
            if (!dinh) {
                String rong = docMoiChuTrenBang(getSettingInt("bua_ue_tho_chu_toi_da", 4000));
                if (noAccent(rong).toLowerCase().contains(chu)) {
                    dinh = true;
                    // LẤY ĐÚNG MẢNH CHỨA CỤM CHỮ, không lấy cả cục.
                    // Phép đọc rộng nối mọi trường String của mọi bảng lại bằng " | " — bắn nguyên
                    // cục lên Telegram là một đống chữ lẫn nhãn nút, tiêu đề, chữ nền. Cắt ra đúng
                    // mảnh có cụm chữ thì được nguyên văn câu game nói, kèm luôn tên kẻ yểm.
                    t = rong;
                    for (String manh : rong.split("\\|")) {
                        if (noAccent(manh).toLowerCase().contains(chu)) { t = manh.trim(); break; }
                    }
                }
            }

            // LƯỚI ĐỠ: BẢNG NẰM LÌ THÌ MỔ, DÙ KHÔNG KHỚP CHỮ NÀO.
            //
            // Nếu chữ trên bảng captcha không nằm trong bất kỳ trường String nào (game vẽ thẳng
            // ra màn hình chẳng hạn) thì mọi phép khớp chữ đều trượt, và cái dump — thứ DUY NHẤT
            // gỡ được bí — lại treo vào chính phép khớp đó. Vòng luẩn quẩn: không nhận ra được
            // thì không bao giờ có dữ liệu để học cách nhận ra.
            //
            // Cắt vòng đó bằng một quan sát khác hẳn: bảng nào của tool cũng được đóng trong vài
            // giây, nên MỘT BẢNG MỞ LIÊN TỤC QUÁ LÂU tự nó đã là chuyện bất thường đáng chộp.
            if (!dinh && getSettingInt("bua_ue_tho_dump_bang_li", 1) == 1) {
                if (soBangDangMo() > 0) {
                    if (buaBangMoTu == 0) buaBangMoTu = now;
                    long li = getSettingInt("bua_ue_tho_bang_li_ms", 60000);
                    if (now - buaBangMoTu >= li && now >= buaDumpKe) {
                        buaDumpKe = now + getSettingInt("bua_ue_tho_dump_cach_ms", 600000);
                        log("Bua ue tho: co bang mo li " + (li / 1000) + "s ma khong khop chu nao"
                                + " -> mo xe de xem no la cai gi");
                        pushScan("=== BANG NAM LI (khong khop chu) ===" + dumpBangCaptcha());
                    }
                } else {
                    buaBangMoTu = 0;
                }
            }

            if (!dinh) {
                // BÁO CẢ LÚC HẾT. Không có tin này thì bảng theo dõi treo nick ở trạng thái "đang
                // bị yểm" vĩnh viễn sau lần đầu — im lặng khi hết cũng sai hệt im lặng khi dính.
                if (buaDangDinh) {
                    buaDangDinh = false;
                    buaGuiLuc = 0;
                    pushBua("bua_ue_tho_het", "bang captcha da dong - da giai bua"
                            + (buaLanThu > 1 ? " (sau " + buaLanThu + " lan gui anh)" : ""));
                    log("Bua ue tho: bang da dong -> het bi yem");
                    buaLanThu = 0;
                }
                return;
            }

            buaBangMoTu = 0;           // đã nhận ra rồi, khỏi cần lưới đỡ "bảng nằm lì"

            // ── GÕ SAI MÃ THÌ GỬI LẠI ẢNH MỚI ────────────────────────────────────────────
            //
            // Captcha cố tình viết khó đọc: I hoa với l thường gần như y hệt nhau, 0 với O, 1 với
            // l. Gõ sai là chuyện bình thường, không phải ngoại lệ hiếm.
            //
            // BẢNG CÒN Ở ĐÓ SAU KHI ĐÃ GỬI MÃ = MÃ SAI. Đây là quan sát trực tiếp, không phải suy
            // đoán: gửi đúng thì server đóng bảng, còn bảng vẫn nằm nghĩa là nó không nhận.
            //
            // KHÔNG gửi lại ảnh: người dùng đã đo và cho biết game GIỮ NGUYÊN MÃ CŨ sau khi gõ
            // sai, chỉ hiện "Mã captcha không chính xác". Gửi lại một tấm ảnh y hệt vừa vô ích
            // vừa làm rác nhóm, và còn đẩy tấm cũ trôi lên xa.
            //
            // Chỗ thật sự kẹt nằm bên Manager: nó xoá mục chờ ngay khi nhận mã, nên reply lần hai
            // vào đúng tin ảnh đó rơi vào hư không. Đã sửa để GIỮ mục chờ tới khi bùa được giải.
            // Ở đây chỉ cần báo cho người dùng biết là trượt, để họ đọc lại tấm ảnh CŨ và reply
            // tiếp — không cần gửi gì thêm.
            //
            // TRẦN `thu_lai_max` LÀ NGƯỠNG NHẮC, KHÔNG PHẢI DẤU CHẤM HẾT.
            //
            // Bản đầu quá trần thì `return` luôn và Manager xoá mục chờ ⇒ reply tiếp vô tác dụng.
            // Hồi đó tưởng bùa tự tan sau 5 phút nên bỏ cuộc chỉ mất nốt vài phút. THẬT RA bùa
            // nằm đó tới khi có người nhập đúng mã — chỉ tắt hẳn client mới thoát. Nên dừng thử
            // lại chính là tự tay khoá nick vĩnh viễn, đúng lúc người dùng đang cố cứu nó.
            //
            // Trần này cũng không đổi lấy được gì: mỗi báo "sai" gắn với ĐÚNG MỘT mã người dùng
            // vừa gửi (buaGuiLuc đặt lúc gửi, xoá lúc báo), không có nhịp tự lặp nào để mà spam.
            if (buaDangDinh) {
                if (buaGuiLuc > 0
                        && now - buaGuiLuc >= getSettingInt("bua_ue_tho_cho_ket_ms", 4000)) {
                    buaGuiLuc = 0;
                    buaLanThu++;
                    int tran = getSettingInt("bua_ue_tho_thu_lai_max", 4);
                    log("Bua ue tho: gui ma xong ma bang van con -> ma SAI (lan " + buaLanThu + ")");
                    if (tran > 0 && buaLanThu > tran) {
                        pushBua("bua_ue_tho_bo", "da thu " + buaLanThu
                                + " lan van chua qua captcha - reply tiep van an, hoac vao game nhap tay");
                    } else {
                        pushBua("bua_ue_tho_sai", "ma sai - doc lai anh o tren roi reply lai (lan "
                                + buaLanThu + ")");
                    }
                }
                return;                // đã báo rồi; nhánh này chạy mỗi 3 giây tới khi bùa được giải
            }
            buaDangDinh = true;
            buaLanThu = 1;
            buaGuiLuc = 0;
            String noiDung = t.trim();
            if (noiDung.length() > 300) noiDung = noiDung.substring(0, 300);
            log("Bua ue tho: DANG BI YEM -> " + noiDung);

            guiAnhCaptcha(noiDung, 1);

            // In danh sách HÀM một lần — giữ lại làm công cụ chẩn đoán cho bản cập nhật sau.
            Object bangD = timBangCaptcha();
            if (bangD != null && getSettingInt("bua_ue_tho_dump_ham", 1) == 1) {
                pushScan("=== HAM BANG CAPTCHA ===" + dumpHamBangCaptcha(bangD));
            }

            // MỔ XẺ BẢNG NGAY LẦN ĐẦU GẶP.
            //
            // Chưa biết trường nào giữ ảnh captcha, cũng chưa biết ô nhập mã nằm đâu. Bảng này
            // chỉ hiện 5 phút và không gọi ra theo ý muốn được — đợi có người ngồi sẵn ở máy để
            // bấm nút soi là đợi một điều kiện không kiểm soát được. Tự dump ngay lúc gặp thì
            // lần dính bùa ĐẦU TIÊN đã cho đủ dữ liệu để nối phần còn lại.
            if (getSettingInt("bua_ue_tho_dump", 1) == 1) {
                pushScan("=== BANG CAPTCHA BUA UE THO ===" + dumpBangCaptcha());
            }
        } catch (Exception e) {
            log("tickBuaUeTho error: " + e.getMessage());
        }
    }

    /**
     * Mổ xẻ SÂU THÊM MỘT TẦNG so với dumpAllFields.
     *
     * Bản mổ 16:23 ngày 09/08 chỉ ra đúng hai chỗ còn bí, và cả hai đều bị `dumpAllFields` in
     * gọn thành một dòng vô nghĩa:
     *   · `b:String[]=String[1]`  — nội dung câu thông báo nằm trong đó, chỉ thấy độ dài
     *   · `d:fV=<a.fV>` / `h:fY=<a.fY>` — một trong hai gần như chắc là chỗ giữ ẢNH captcha,
     *     chỉ thấy tên lớp
     * Không có hai thứ đó thì không nối được bước gửi ảnh. Nên bung String[] ra và đi thêm ĐÚNG
     * một tầng vào các đối tượng con — một tầng là đủ thấy mặt, mà không nổ thành hàng nghìn dòng
     * như đi đệ quy không đáy.
     */
    private String dumpSauMotTang(Object o) {
        if (o == null) return " (null)";
        StringBuilder sb = new StringBuilder(dumpAllFields(o));
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        if (v.getClass().isArray()
                                && v.getClass().getComponentType() == String.class) {
                            int n = java.lang.reflect.Array.getLength(v);
                            for (int k = 0; k < n; k++) {
                                Object s = java.lang.reflect.Array.get(v, k);
                                sb.append("\n        ").append(c.getSimpleName()).append(".")
                                  .append(f.getName()).append("[").append(k).append("]='")
                                  .append(s).append("'");
                            }
                        } else if (!(v instanceof String) && !(v instanceof Number)
                                && !(v instanceof Boolean) && !(v instanceof Character)
                                && !(v instanceof java.util.Collection)
                                && !v.getClass().isArray()
                                && v.getClass().getName().startsWith("a.")) {
                            sb.append("\n        >> ").append(c.getSimpleName()).append(".")
                              .append(f.getName()).append(" = ").append(v.getClass().getName())
                              .append(dumpAllFields(v));
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}
        return sb.toString();
    }

    /** Mổ xẻ chồng bảng lúc captcha đang hiện — để tìm chỗ giữ ảnh và ô nhập mã. */
    private String dumpBangCaptcha() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(dumpPanelStack());
            if (fkFieldAn == null) return sb.toString();
            Object zInst = getZ();
            if (zInst == null) return sb.toString();
            Object o = fkFieldAn.get(zInst);
            if (!(o instanceof java.util.Vector)) return sb.toString();
            java.util.Vector<?> stack = (java.util.Vector<?>) o;
            // Chỉ mổ vài bảng trên cùng: captcha là bảng vừa mở nên nó nằm ở đỉnh chồng, còn mổ
            // cả chồng thì ra hàng nghìn dòng và thứ cần tìm chìm nghỉm trong đó.
            int sau = getSettingInt("bua_ue_tho_dump_sau", 3);
            for (int i = stack.size() - 1; i >= 0 && i > stack.size() - 1 - sau; i--) {
                Object p = stack.get(i);
                if (p == null) continue;
                sb.append("\n--- MO XE BANG [").append(i).append("] ")
                  .append(p.getClass().getName()).append(dumpSauMotTang(p));
            }
        } catch (Exception e) {
            sb.append("\n  loi mo xe: ").append(e);
        }
        return sb.toString();
    }

    // ── CHUYỂN TIẾP CAPTCHA ────────────────────────────────────────────────────────────────
    //
    // Tool KHÔNG giải mã. Nó chuyển tấm ảnh cho người thật xem, rồi gõ hộ câu trả lời của người
    // đó vào ô — làm cái bàn phím nối dài, không phải làm cái đầu. Đó là ranh đã thống nhất.
    //
    // Chỗ đặt mọi thứ đọc được từ bản mổ 16:30 ngày 09/08:
    //   bảng    a.ew  (nằm trong chồng z.an, luôn ở đỉnh vì nó vừa mở)
    //   ảnh     a.ew → aT.d → a.fV  có sẵn Pixmap 80×50 — đọc thẳng, không phải chụp màn hình
    //   ô nhập  a.ew.h → a.fY       (aY=4 = đúng độ dài mã, dấu hiệu mạnh đây là ô nhập)
    //   nút     a.ew → aT.F → a.by  có j='Xác nhận'

    /** Đọc một trường số của bảng theo tên, đi cả lớp cha. Không có thì trả `neuThieu`. */
    private int docSoTruong(Object o, String ten, int neuThieu) {
        if (o == null || ten == null || ten.trim().isEmpty()) return neuThieu;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten.trim());
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (NoSuchFieldException ignore) {
            } catch (Throwable ignore) { return neuThieu; }
        }
        return neuThieu;
    }

    /** Đọc một trường String của bảng theo tên, đi cả lớp cha. Không có thì trả rỗng. */
    private String docChuoiTruong(Object o, String ten) {
        if (o == null || ten == null || ten.trim().isEmpty()) return "";
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten.trim());
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return v.toString().trim();
            } catch (NoSuchFieldException ignore) {
            } catch (Throwable ignore) { return ""; }
        }
        return "";
    }

    /** Bảng captcha đang mở, hoặc null. Nhận theo LỚP khai trong cfg. */
    private Object timBangCaptcha() {
        try {
            if (fkFieldAn == null) return null;
            Object zInst = getZ();
            if (zInst == null) return null;
            Object o = fkFieldAn.get(zInst);
            if (!(o instanceof java.util.Vector)) return null;
            java.util.Vector<?> stack = (java.util.Vector<?>) o;
            String lop = getSetting("bua_ue_tho_lop_bang", "a.ew");
            for (int i = stack.size() - 1; i >= 0; i--) {
                Object p = stack.get(i);
                if (p != null && p.getClass().getName().equals(lop)) return p;
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * Ảnh captcha dạng PNG.
     *
     * TÌM THEO KIỂU, KHÔNG THEO TÊN TRƯỜNG: duyệt các trường của bảng, trường nào trỏ tới một đối
     * tượng CÓ CHỨA `com.badlogic.gdx.graphics.Pixmap` thì đó là chỗ giữ ảnh. Tên trường (`d`) là
     * thứ trình làm rối đổi được sau một bản cập nhật, còn "đối tượng này có một Pixmap" là tính
     * chất quan sát được — cùng lý do đã chọn lọc-theo-kiểu ở z.D.
     *
     * Chạy ngay trên luồng render (tick() được nhét vào a.a.render()) nên đọc Pixmap là an toàn:
     * Pixmap nằm ở bộ nhớ thường, không phải texture trên GPU, không cần đụng GL.
     */
    private byte[] anhCaptchaPng(Object bang) {
        if (bang == null) return null;
        try {
            Class<?> pixCls = Class.forName("com.badlogic.gdx.graphics.Pixmap");
            Object pix = null;
            for (Class<?> c = bang.getClass(); c != null && c != Object.class && pix == null;
                 c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(bang);
                        if (v == null || !v.getClass().getName().startsWith("a.")) continue;
                        for (Field g : v.getClass().getDeclaredFields()) {
                            if (g.getType() != pixCls) continue;
                            g.setAccessible(true);
                            Object p = g.get(v);
                            if (p != null) { pix = p; break; }
                        }
                    } catch (Throwable ignore) {}
                    if (pix != null) break;
                }
            }
            if (pix == null) { log("Bua ue tho: khong tim thay Pixmap trong bang captcha"); return null; }

            Object raGiay = sualAnh(pix, pixCls);   // lật lại + phóng to; hỏng thì trả nguyên bản

            Class<?> pngCls = Class.forName("com.badlogic.gdx.graphics.PixmapIO$PNG");
            Object png = pngCls.getConstructor().newInstance();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            pngCls.getMethod("write", java.io.OutputStream.class, pixCls).invoke(png, bos, raGiay);
            try { pngCls.getMethod("dispose").invoke(png); } catch (Throwable ignore) {}
            if (raGiay != pix) {
                try { pixCls.getMethod("dispose").invoke(raGiay); } catch (Throwable ignore) {}
            }
            return bos.toByteArray();
        } catch (Throwable e) {
            log("Bua ue tho: lay anh captcha hong: " + e);
            return null;
        }
    }

    /**
     * LẬT LẠI TRỤC Y VÀ PHÓNG TO ảnh captcha trước khi gửi đi.
     *
     * Vì sao lật: `Pixmap` của libgdx xếp hàng điểm ảnh từ TRÊN xuống, còn giao diện game vẽ theo
     * hệ toạ độ y-hướng-xuống nên nó lật lúc vẽ. Đọc thẳng bộ nhớ ra rồi ghi PNG thì được đúng
     * tấm ảnh game giữ, nhưng LỘN NGƯỢC so với cái hiện trên màn hình — người nhìn phải tự lật
     * trong đầu, mà đây là captcha, vốn đã cố tình làm khó đọc.
     *
     * Vì sao phóng to: ảnh gốc 80×50. Trên điện thoại thì Telegram co lại còn bằng con tem.
     * Phóng bằng NearestNeighbour chứ không nội suy — captcha là nét mảnh, làm mượt là nhoè mất
     * đúng thứ cần đọc.
     *
     * Hỏng ở bất kỳ bước nào thì TRẢ VỀ NGUYÊN BẢN. Ảnh lộn ngược vẫn đọc được nếu cố; không có
     * ảnh thì mất trắng lượt. Đây là chỗ làm đẹp, không được phép làm hỏng đường chính.
     */
    private Object sualAnh(Object src, Class<?> pixCls) {
        try {
            int lat = getSettingInt("bua_ue_tho_lat_anh", 1);
            int k = getSettingInt("bua_ue_tho_phong_to", 4);
            if (k < 1) k = 1;
            if (lat != 1 && k == 1) return src;

            int w = ((Number) pixCls.getMethod("getWidth").invoke(src)).intValue();
            int h = ((Number) pixCls.getMethod("getHeight").invoke(src)).intValue();
            if (w <= 0 || h <= 0 || (long) w * k > 4000 || (long) h * k > 4000) return src;

            Class<?> fmtCls = Class.forName("com.badlogic.gdx.graphics.Pixmap$Format");
            Object fmt = pixCls.getMethod("getFormat").invoke(src);
            Object dst = pixCls.getConstructor(int.class, int.class, fmtCls)
                               .newInstance(w * k, h * k, fmt);

            // Cả hai là hàm TĨNH trong libgdx. Không tắt trộn màu thì lúc chép, phần trong suốt
            // của nguồn bị hoà với nền chứ không chép thẳng.
            try {
                Class<?> blCls = Class.forName("com.badlogic.gdx.graphics.Pixmap$Blending");
                pixCls.getMethod("setBlending", blCls)
                      .invoke(null, layHangSo(blCls, "None"));
            } catch (Throwable ignore) {}
            try {
                Class<?> flCls = Class.forName("com.badlogic.gdx.graphics.Pixmap$Filter");
                pixCls.getMethod("setFilter", flCls)
                      .invoke(null, layHangSo(flCls, "NearestNeighbour"));
            } catch (Throwable ignore) {}

            java.lang.reflect.Method ve = pixCls.getMethod("drawPixmap", pixCls,
                    int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class);
            // Chép TỪNG HÀNG, hàng nguồn y rơi xuống hàng đích (h-1-y) ⇒ lật dọc. Làm luôn trong
            // một lượt với phóng to, khỏi phải dựng thêm một ảnh trung gian.
            for (int y = 0; y < h; y++) {
                int dy = (lat == 1) ? (h - 1 - y) : y;
                ve.invoke(dst, src, 0, y, w, 1, 0, dy * k, w * k, k);
            }
            return dst;
        } catch (Throwable e) {
            log("Bua ue tho: lat/phong anh hong (" + e + ") -> gui nguyen ban");
            return src;
        }
    }

    /** Lấy một hằng số enum theo tên, không cần ép kiểu tổng quát. */
    private Object layHangSo(Class<?> enumCls, String ten) throws Exception {
        for (Object o : enumCls.getEnumConstants())
            if (String.valueOf(o).equals(ten)) return o;
        throw new NoSuchFieldException(ten + " khong co trong " + enumCls.getName());
    }

    /**
     * Gõ mã của NGƯỜI DÙNG vào ô rồi bấm Xác nhận.
     *
     * Chưa biết chắc trường nào của ô nhập giữ chữ — bản mổ cho thấy `a.fY` có nhiều trường String
     * và lúc trống thì cái nào cũng rỗng, nên không phân biệt được bằng cách nhìn. Thay vì đoán
     * một cái rồi im lặng hỏng, ghi vào CẢ DANH SÁCH khai trong cfg và IN RA đã ghi được những
     * cái nào. Chạy thật một lần là biết cái nào ăn, rồi rút danh sách lại — sửa cfg, không phải
     * dựng lại jar.
     *
     * Tương tự với nút: tên hàm bấm để trong cfg. Chưa khai thì chỉ ghi mã rồi thôi, và người dùng
     * bấm nút bằng tay — vẫn hơn hẳn phải tự đọc ảnh rồi tự gõ.
     */
    public String nhapMaCaptcha(String ma) {
        if (ma == null || ma.trim().isEmpty()) return "LOI: ma rong";
        ma = ma.trim();
        Object bang = timBangCaptcha();
        if (bang == null) return "LOI: khong con bang captcha nao dang mo";
        StringBuilder daGhi = new StringBuilder();
        try {
            Object oNhap = null;
            String tenO = getSetting("bua_ue_tho_truong_o_nhap", "h");
            for (Class<?> c = bang.getClass(); c != null && c != Object.class && oNhap == null;
                 c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(tenO);
                    f.setAccessible(true);
                    oNhap = f.get(bang);
                } catch (NoSuchFieldException ignore) {}
            }
            if (oNhap == null) return "LOI: khong tim thay o nhap (truong '" + tenO + "')";

            for (String ten : getSetting("bua_ue_tho_o_nhap_truong", "ap,n,Q,R,aq").split(",")) {
                ten = ten.trim();
                if (ten.isEmpty()) continue;
                for (Class<?> c = oNhap.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(ten);
                        if (f.getType() != String.class) break;
                        f.setAccessible(true);
                        f.set(oNhap, ma);
                        daGhi.append(ten).append(" ");
                        break;
                    } catch (NoSuchFieldException ignore) {}
                }
            }
            // Bảng cũng có thể tự giữ một bản sao của chữ đã gõ.
            //
            // MẶC ĐỊNH RỖNG, và đó là một bản vá chứ không phải bỏ trống cho vui: trước đây mặc
            // định là "S", mà `a.ew.S` giữ TÊN KẺ YỂM chứ không phải ô nhập — ghi mã vào đó là
            // xoá mất tên. Đặt rỗng trong cfg KHÔNG đủ để chữa: `String.split(",")` của Java cắt
            // bỏ phần tử rỗng ở cuối, nên dòng `set,khoa,` chỉ tách ra 2 phần và giá trị rỗng
            // không bao giờ được ghi nhận ⇒ getSetting rơi về mặc định. Phải sửa chính mặc định.
            for (String ten : getSetting("bua_ue_tho_bang_truong", "").split(",")) {
                ten = ten.trim();
                if (ten.isEmpty()) continue;
                for (Class<?> c = bang.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(ten);
                        if (f.getType() != String.class) break;
                        f.setAccessible(true);
                        f.set(bang, ma);
                        daGhi.append("bang.").append(ten).append(" ");
                        break;
                    } catch (NoSuchFieldException ignore) {}
                }
            }
        } catch (Throwable e) {
            return "LOI: ghi ma hong: " + e;
        }

        String ketQua = "da ghi ma '" + ma + "' vao: " + (daGhi.length() == 0 ? "(khong cho nao)" : daGhi);

        // ── BẤM XÁC NHẬN ──────────────────────────────────────────────────────────────────
        //
        // BẤM VÀO ĐỐI TƯỢNG NÚT, không phải vào bảng. Đọc bảng phương thức thật (không phải
        // constant pool — chỗ đó lẫn cả hàm mà lớp GỌI SANG lớp khác, và em đã đoán sai đúng vì
        // nhầm hai thứ đó):
        //     a.ew :  b(La/A;)V         ⇒ hàm VẼ, a.A là lớp đồ hoạ
        //             a(La/dI;II)V      ⇒ xử lý sự kiện
        //     a.by :  b()V              ⇒ KHÔNG tham số, trên chính đối tượng NÚT
        //
        // Nên `b` trên bảng là vẽ, còn `b` trên nút mới là bấm. Cùng một tên, hai nghĩa — và đó
        // là lý do lần trước gọi không ra gì: phép tra chỉ nhận bản không-tham-số hoặc 1 String,
        // mà `ew.b` cần một `a.A`.
        //
        // Cả CHỖ BẤM lẫn TÊN HÀM đều nằm trong cfg: chưa chạy thật thì chưa chắc, mà sai thì đổi
        // một dòng cfg còn hơn dựng lại jar.
        // ĐƯỜNG CHÍNH: LÀM ĐÚNG THỨ NÚT XÁC NHẬN LÀM, không đi mò cách giả lập cú bấm.
        //
        // Giải mã `a.ew.a(La/dI;II)V` — hàm xử lý sự kiện của bảng — ra đúng chuỗi việc:
        //     a.ew.a()      -> a.fY      lấy ô nhập
        //     a.fY.a()      -> String    ĐỌC chữ trong ô
        //     a.ew.M        : byte       mã lệnh
        //     a.fm.c(byte)  -> a.fm      dựng gói tin
        //     a.fm.m(String)             ghi mã vào gói
        //     a.fm.aG()                  gửi
        //
        // Tức "bấm Xác nhận" rốt cuộc chỉ là DỰNG MỘT GÓI RỒI GỬI. Làm thẳng như vậy thì không
        // phải chế ra một sự kiện chuột giả với toạ độ và mã sự kiện phải đoán — hai vòng trước
        // đã đoán sai đúng vì cố tìm "cái nút" thay vì tìm "việc nó làm".
        //
        // Và `M` KHÔNG phải mã loại bảng như đã tưởng lúc đầu — nó là MÃ LỆNH của gói. Đọc sống
        // từ bảng chứ không nhúng số -44 vào code: server đổi mã lệnh thì bảng vẫn mang số đúng.
        if (getSettingInt("bua_ue_tho_gui_goi", 1) == 1 && fmClass != null) {
            try {
                int cmd = docSoTruong(bang, getSetting("bua_ue_tho_truong_ma", "M"), Integer.MIN_VALUE);
                if (cmd == Integer.MIN_VALUE) {
                    ketQua += " | khong doc duoc ma lenh tu bang";
                } else {
                    // getDeclaredMethod + setAccessible, KHÔNG getMethod: lớp game bị làm rối nên
                    // hàm có thể không public, mà getMethod chỉ thấy public rồi ném lỗi câm.
                    java.lang.reflect.Method tao =
                            fmClass.getDeclaredMethod(getSetting("bua_ue_tho_fm_tao", "c"), byte.class);
                    tao.setAccessible(true);
                    Object goi = tao.invoke(null, Byte.valueOf((byte) cmd));

                    // Dùng lại fmWriteUTF đã map sẵn: 'm' bị nạp chồng (m(boolean) = writeBoolean),
                    // quét theo tên là vớ nhầm bản boolean — chú thích ở initReflection đã cảnh báo.
                    fmWriteUTF.setAccessible(true);
                    fmWriteUTF.invoke(goi, ma);

                    java.lang.reflect.Method gui =
                            fmClass.getDeclaredMethod(getSetting("bua_ue_tho_fm_gui", "aG"));
                    gui.setAccessible(true);
                    gui.invoke(goi);
                    // ĐÁNH DẤU LÚC GỬI. Nhịp soi sẽ nhìn lại sau vài giây: bảng còn đó nghĩa là
                    // mã sai ⇒ tự chụp ảnh mới gửi lại. Không đánh dấu thì gõ sai là kẹt luôn.
                    buaGuiLuc = System.currentTimeMillis();
                    ketQua += " | DA GUI GOI xac nhan (cmd=" + cmd + ")";
                    log("Bua ue tho: " + ketQua);
                    pushBua("bua_ue_tho_nhap", ketQua);
                    return ketQua;
                }
            } catch (Throwable e) {
                ketQua += " | gui goi hong: " + e + " -> thu goi ham";
            }
        }

        // ĐƯỜNG LUI: gọi một hàm trên nút hoặc trên bảng. Giữ lại vì nó chỉnh được hoàn toàn bằng
        // cfg — nếu bản cập nhật nào đó đổi cách gửi gói thì vẫn còn một lối để thử mà không phải
        // dựng lại jar.
        String hamBam = getSetting("bua_ue_tho_ham_xac_nhan", "").trim();
        String bamO = getSetting("bua_ue_tho_bam_o", "nut").trim();
        if (hamBam.isEmpty()) {
            ketQua += " | bua_ue_tho_ham_xac_nhan de trong -> bam nut bang tay";
        } else {
            Object dich = bang;
            if (bamO.equalsIgnoreCase("nut")) {
                Object nut = null;
                String tenNut = getSetting("bua_ue_tho_truong_nut", "F");
                for (Class<?> c = bang.getClass(); c != null && c != Object.class && nut == null;
                     c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(tenNut);
                        f.setAccessible(true);
                        nut = f.get(bang);
                    } catch (NoSuchFieldException ignore) {
                    } catch (IllegalAccessException ignore) {}
                }
                if (nut == null) ketQua += " | KHONG thay nut (truong '" + tenNut + "')";
                else dich = nut;
            }
            java.lang.reflect.Method mTrong = null, mChuoi = null;
            for (Class<?> c = dich.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(hamBam)) continue;
                    Class<?>[] ts = m.getParameterTypes();
                    if (ts.length == 0 && mTrong == null) mTrong = m;
                    if (ts.length == 1 && ts[0] == String.class && mChuoi == null) mChuoi = m;
                }
                if (mTrong != null) break;
            }
            java.lang.reflect.Method m = (mTrong != null) ? mTrong : mChuoi;
            if (m == null) {
                ketQua += " | KHONG thay ham '" + hamBam + "' tren "
                        + dich.getClass().getName() + " (khong tham so hoac 1 String)";
            } else {
                try {
                    m.setAccessible(true);
                    if (m.getParameterTypes().length == 1) m.invoke(dich, ma);
                    else m.invoke(dich);
                    ketQua += " | da goi " + dich.getClass().getName() + "." + hamBam
                            + (m.getParameterTypes().length == 1 ? "(ma)" : "()");
                } catch (Throwable e) {
                    ketQua += " | goi " + hamBam + " hong: " + e;
                }
            }
        }
        log("Bua ue tho: " + ketQua);
        pushBua("bua_ue_tho_nhap", ketQua);
        return ketQua;
    }

    /**
     * IN RA DANH SÁCH HÀM của bảng, ô nhập và nút — để biết phải gọi cái gì mới là "bấm Xác nhận".
     * Mổ trường thì đã thấy đủ chỗ chứa, nhưng chỗ chứa không cho biết cách KÍCH HOẠT. Đây là mảnh
     * cuối, và in một lần lúc gặp bảng là đủ.
     */
    private String dumpHamBangCaptcha(Object bang) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("\n  HAM cua ").append(bang.getClass().getName()).append(":");
            for (Class<?> c = bang.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    // IN KIỂU THAM SỐ, không chỉ in SỐ LƯỢNG. Bản trước chỉ in số nên nhìn thấy
                    // "b(1 tham so)" mà vẫn không gọi được — phải mở file lớp ra đọc chữ ký mới
                    // biết tham số là String. Một cột thiếu làm cả bản in thành vô dụng.
                    sb.append("\n    ").append(c.getSimpleName()).append(".").append(m.getName()).append("(");
                    Class<?>[] ts = m.getParameterTypes();
                    for (int k = 0; k < ts.length; k++) {
                        if (k > 0) sb.append(", ");
                        sb.append(ts[k].getSimpleName());
                    }
                    sb.append(") -> ").append(m.getReturnType().getSimpleName());
                }
            }
        } catch (Throwable e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    private void pushBua(String type, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"map\":" + getMapAnToan()
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushBua error: " + e.getMessage());
        }
    }

    /** Gói mang cả ẢNH captcha (PNG mã hoá base64) — Manager sẽ đẩy thẳng lên Telegram. */
    private void pushBuaAnh(String detail, String anhB64) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"bua_ue_tho\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"map\":" + getMapAnToan()
                    + ",\"anh\":\"" + anhB64 + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushBuaAnh error: " + e.getMessage());
        }
    }

    private void pushGom(String type, String detail) {
        pushGom(type, detail, null);
    }

    /** `mon` = tên các món vừa sang tay ở lượt này (null/rỗng thì không kèm). */
    private void pushGom(String type, String detail, String mon) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"zone\":" + getCurrentZoneId()
                    + (mon == null || mon.isEmpty() ? "" : ",\"mon\":\"" + escapeJson(mon) + "\"")
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushGom error: " + e.getMessage());
        }
    }

    /** Đẩy một dòng danh sách vật phẩm về Manager — ghi ra file riêng, không lẫn vào file soi map. */
    private void pushItemList(String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"item_list\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushItemList error: " + e.getMessage());
        }
    }

    /**
     * XUẤT TOÀN BỘ BẢNG MẪU VẬT PHẨM — `n.a().a[]`, chỉ số mảng chính là MÃ VẬT PHẨM.
     *
     * Để sau này thêm món mới vào danh sách gom mà không phải đợi món đó rơi vào túi ai: mở bảng
     * ra, tra tên, lấy chỉ số. Thuần đọc bộ nhớ, không gửi gói nào.
     *
     * Lớp bản mẫu (`a.H`, bản dịch ngược gọi là `h_0`) còn nguyên `toString()` của người viết
     * game nên biết chắc nghĩa từng trường, không phải suy:
     *   `D` = mã · `l` = tên · `v` = mô tả · `aB` = CÓ XẾP CHỒNG KHÔNG · `r` = loại · `E` = cấp cần.
     *
     * `aB` đáng giá riêng: món KHÔNG xếp chồng thì mỗi cái ăn một ô giao dịch, nên lúc xếp lượt
     * phải đếm theo số ô chứ không theo số món.
     */
    public String dumpItemCatalog() {
        // ĐẨY DÒNG ĐẦU TRƯỚC MỌI THAO TÁC CÓ THỂ NÉM LỖI.
        //
        // Manager chỉ tạo file khi nhận được dòng đầu tiên. Trước đây mọi thứ nằm trong một khối
        // try, nên hỏng ở bước dò bảng là không đẩy nổi dòng nào — file không hề được tạo, còn
        // Manager thì đã ghi "đang xuất..." nên nhìn vào tưởng chạy bình thường. Hỏng mà im lặng
        // là kiểu hỏng tốn thời gian nhất; giờ luôn có file, và lỗi nằm ngay trong đó.
        pushItemList("=== BAT DAU XUAT BANG MAU VAT PHAM ===");
        try {
            Object bang = bangMauVatPham();
            if (bang == null) {
                pushItemList("LOI: khong tim duoc bang mau vat pham tren a.n");
                return "khong tim duoc bang mau vat pham";
            }
            int len = java.lang.reflect.Array.getLength(bang);
            Class<?> tc = bang.getClass().getComponentType();
            String loc = getSetting("catalog_find", "");
            String locThuong = loc.toLowerCase();

            Field fTen  = tc.getDeclaredField("l");
            Field fMa   = tc.getDeclaredField("D");
            fTen.setAccessible(true); fMa.setAccessible(true);
            // Hai trường phụ: thiếu thì bỏ cột đó chứ KHÔNG bỏ cả bảng. Tên và mã mới là thứ
            // không có không xong; loại và cờ xếp chồng chỉ là thông tin kèm.
            Field fLoai = layTruongNeuCo(tc, "r");
            Field fChong = layTruongNeuCo(tc, "aB");

            pushItemList("bang = " + tc.getName() + "[" + len + "]"
                    + (loc.isEmpty() ? "  (in het)" : "  (chi in ten chua '" + loc + "')")
                    + (fLoai == null ? "  [khong co cot loai]" : "")
                    + (fChong == null ? "  [khong co cot xepchong]" : ""));

            int inRa = 0;
            for (int i = 0; i < len; i++) {
                Object bm = java.lang.reflect.Array.get(bang, i);
                if (bm == null) continue;
                Object ten = fTen.get(bm);
                String t = (ten == null) ? "" : String.valueOf(ten);
                if (t.isEmpty()) continue;
                if (!locThuong.isEmpty() && !t.toLowerCase().contains(locThuong)) continue;
                inRa++;
                // IN HẾT MỌI TRƯỜNG của bản mẫu, không chỉ ba trường mình nghĩ là đủ.
                //
                // Bản in đầu chỉ có tên/loại/xếp chồng nên thiếu đúng thứ cần: dòng mô tả
                // ("Tăng 5 điểm Hokage Móc Sắt", "Có thể đổi 8 Tinh thạch ở Kinkaku") và cấp yêu
                // cầu. Lớp bản mẫu `a.H` giữ tới 12 trường; game còn để nguyên toString() khai
                // tên từng cái: D=mã, l=tên, v=mô tả, aB=xếp chồng, i=giới tính, r=loại,
                // j=lớp nhân vật, q=mã ảnh, E=cấp cần, cv=tài phú cần, t=mã quái, F=mã nhân vật.
                pushItemList("ma=" + i + " ten='" + t + "'"
                        + (fLoai == null ? "" : "  loai=" + fLoai.getByte(bm))
                        + (fChong == null ? "" : (fChong.getBoolean(bm) ? " xepchong" : " KHONG-xepchong"))
                        + (fMa.getShort(bm) != i ? "  [!] truong D=" + fMa.getShort(bm)
                                                    + " lech voi chi so" : "")
                        + moTaBanMau(bm, fTen, fMa, fLoai, fChong));
            }
            pushItemList("=== HET: in ra " + inRa + " mon ===");
            return "da xuat " + inRa + "/" + len + " muc bang mau vat pham";
        } catch (Throwable e) {
            pushItemList("LOI: " + e);
            return "loi xuat bang mau: " + e;
        }
    }

    /** Mọi trường CÒN LẠI của bản mẫu — bỏ những trường đã in riêng ở đầu dòng và giá trị rỗng. */
    private String moTaBanMau(Object bm, Field... daIn) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : bm.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                boolean trung = false;
                for (Field g : daIn) if (g != null && g.getName().equals(f.getName())) trung = true;
                if (trung) continue;
                f.setAccessible(true);
                Object v = f.get(bm);
                if (v == null) continue;
                if (v instanceof String) {
                    if (((String) v).isEmpty()) continue;
                    sb.append("  ").append(f.getName()).append("='").append(v).append("'");
                } else if (v instanceof Number) {
                    long n = ((Number) v).longValue();
                    if (n == 0 || n == -1) continue;
                    sb.append("  ").append(f.getName()).append("=").append(n);
                } else if (v instanceof Boolean && ((Boolean) v)) {
                    sb.append("  ").append(f.getName()).append("=T");
                }
            }
        } catch (Throwable ignore) {}
        return sb.toString();
    }

    private Field layTruongNeuCo(Class<?> c, String ten) {
        try {
            Field f = c.getDeclaredField(ten);
            f.setAccessible(true);
            return f;
        } catch (Exception ignore) { return null; }
    }

    /**
     * Mảng bản mẫu vật phẩm `n.a().a[]`.
     *
     * Nhận mảng theo HÌNH DẠNG (lớp phần tử có `l` kiểu String và `D` kiểu short) chứ không theo
     * tên — lớp `n` giữ tới ba mảng khác nhau cùng tên trường `a`, phân biệt được chỉ nhờ kiểu.
     * Đúng cái bẫy đã sập ở `d_0` và ở `z.D`.
     */
    private Object bangMauVatPham() {
        try {
            Class<?> nCls = Class.forName("a.n");
            Object inst = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0 || m.getReturnType() != nCls) continue;
                m.setAccessible(true);
                inst = m.invoke(null);
                break;
            }
            if (inst == null) return null;

            // Đường 1 — HỎI NGƯỢC TỪ CHÍNH VẬT PHẨM. Lấy một món bất kỳ trong túi rồi hỏi nó
            // bản mẫu, ra đúng lớp bản mẫu vật phẩm; mảng nào trên `n` có phần tử ĐÚNG lớp đó
            // thì là bảng cần tìm. Không đoán một chữ nào.
            Class<?> lopBanMau = null;
            Object tui = gomLayTui();
            if (tui != null) {
                int n = java.lang.reflect.Array.getLength(tui);
                for (int i = 0; i < n && lopBanMau == null; i++) {
                    Object mon = java.lang.reflect.Array.get(tui, i);
                    if (mon == null) continue;
                    Object bm = banMauMon(mon);
                    if (bm != null) lopBanMau = bm.getClass();
                }
            }
            if (lopBanMau != null) {
                Object arr = timMangTheoLop(nCls, inst, lopBanMau);
                if (arr != null) return arr;
            }

            // Đường 2 — CHỮ KÝ BỐN TRƯỜNG, dùng khi túi rỗng nên không mượn được món nào.
            //
            // PHẢI đủ bốn trường chứ không phải hai. Đo trên jar 03/08: lớp `n` giữ BẢY mảng cùng
            // có `l:String` + `D:short` (bản mẫu NPC a.fs, bản mẫu quái a.fo, a.fl, a.G, a.fH...),
            // nên chữ ký hai trường trả về nhầm bảng NPC — rồi `getDeclaredField("aB")` ném lỗi và
            // cả hàm xuất chết lặng, không đẩy nổi một dòng. Thêm `r:byte` và `aB:boolean` thì
            // đúng MỘT mảng khớp: `n.a : a.H[]`.
            for (Field f : nCls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().isArray()) continue;
                Class<?> tc = f.getType().getComponentType();
                if (tc.isPrimitive()) continue;
                if (!laLopBanMauVatPham(tc)) continue;
                f.setAccessible(true);
                Object arr = f.get(inst);
                if (arr != null) return arr;
            }
        } catch (Throwable e) {
            log("Gom do: tim bang mau hong: " + e);
        }
        return null;
    }

    private Object timMangTheoLop(Class<?> nCls, Object inst, Class<?> lop) {
        for (Field f : nCls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getType().isArray()) continue;
            if (f.getType().getComponentType() != lop) continue;
            try {
                f.setAccessible(true);
                Object arr = f.get(inst);
                if (arr != null) return arr;
            } catch (Exception ignore) {}
        }
        return null;
    }

    /** Lớp bản mẫu VẬT PHẨM — bốn trường, đủ để tách khỏi bản mẫu NPC và bản mẫu quái. */
    private boolean laLopBanMauVatPham(Class<?> c) {
        boolean l = false, D = false, r = false, aB = false;
        for (Field g : c.getDeclaredFields()) {
            if ("l".equals(g.getName())  && g.getType() == String.class)  l = true;
            if ("D".equals(g.getName())  && g.getType() == short.class)   D = true;
            if ("r".equals(g.getName())  && g.getType() == byte.class)    r = true;
            if ("aB".equals(g.getName()) && g.getType() == boolean.class) aB = true;
        }
        return l && D && r && aB;
    }

    private void pushScan(String detail) {
        log("Soi map: " + detail);
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"map_scan\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":true,\"map\":" + getCurrentMapId()
                    + ",\"auto\":" + scanAuto
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushScan error: " + e.getMessage());
        }
    }

    public String scanMapEntities() {
        return scanMapEntities(false);
    }

    /**
     * Soi map, LẤY DƯ CÒN HƠN THIẾU.
     *
     * Chạy trong một hoạt động thật (Cấm thuật) thì mỗi lượt chỉ có một lần, sai một trường là
     * phải chờ tới hôm sau. Nên chỗ này không lọc theo giả thuyết đang có: nó in ra
     *   - toàn bộ danh sách thực thể của cả ba vector, kèm mọi trường đã biết mặt;
     *   - và MỔ XẺ hết sạch trường của một đại diện cho mỗi loại thực thể khác nhau.
     * Mổ xẻ mới là phần đáng giá: nó lôi ra cả những trường chưa ai đặt tên — HP, cấp, cờ tinh
     * anh/thủ lĩnh — mà không cần đoán trước tên trường nào là gì. Nhìn số thật rồi đặt tên sau.
     *
     * Phần mổ xẻ chỉ chạy `scan_deep_times` lần đầu: cùng một loại thực thể thì danh sách trường
     * y hệt nhau, in lại chỉ tổ ngập log. Số thay đổi theo thời gian (HP tụt dần) vẫn thấy được
     * qua phần danh sách chạy mọi lần.
     *
     * @param auto true = máy tự soi theo nhịp (Manager chỉ ghi ra file), false = người bấm nút.
     */
    public String scanMapEntities(boolean auto) {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        boolean deep;
        try {
            scanAuto = auto;
            deep = scanDeepDone < getSettingInt("scan_deep_times", 2);
            Class<?> nguoiCls = Class.forName("a.i");
            Class<?> quaiCls  = Class.forName("a.fn");
            // CỜ in ở dòng tiêu đề, MỌI lần soi. Rẻ (một byte) và là cách kiểm trường đọc đúng
            // mà không tốn lượt hoạt động nào: đứng ở làng tự đổi cờ tay trắng→xanh→trắng, số
            // ở đây phải đổi theo. Không đổi theo là đọc sai trường.
            int coNow = readPlayerFlag();
            pushScan("=== map " + getCurrentMapId() + " khu " + getCurrentZoneId()
                    + ", nhan vat tai (" + getPlayerX() + "," + getPlayerY() + ")"
                    + " | danh=" + (isAutoCombatOn() ? "BAT" : "tat")
                    + " | co=" + coNow + " (" + flagName(coNow) + ")"
                    + " | MT z.a: " + describeTarget()
                    + (deep ? " | co mo xe truong" : "") + " ===");

            // SOI CHỈ TÚI — cắt sạch phần còn lại.
            //
            // Một lượt soi đầy đủ in ra quái, NPC, người chơi, đối tượng map, bảng nối map, chồng
            // bảng, hằng chữ… hơn 1300 dòng cho bốn lượt, và phần túi nằm lọt thỏm giữa đó. Khi
            // câu hỏi chỉ là "trong túi đang có gì" thì tất cả những thứ kia là nhiễu thuần tuý.
            if (getSettingInt("scan_chi_tui", 0) == 1) {
                pushScan("HANG TRANG:" + dumpBag());
                if (deep) scanDeepDone++;
                return "da soi xong (chi tui) - xem log";
            }

            // Mục tiêu đang đánh: mổ xẻ RIÊNG. Đây là con quái mà cả nhóm dồn vào, nên mọi trường
            // của nó (HP, cấp, cờ loại) đều là thứ cần cho các bước sau.
            if (deep && zFieldTarget != null) {
                try {
                    Object t = zFieldTarget.get(getZ());
                    if (t != null) pushScan("MO XE MUC TIEU z.a:" + dumpAllFields(t) + dumpTemplate(t));
                } catch (Exception ignore) {}
            }
            // Bản thân: để đối chiếu — trường nào của quái trùng tên với trường nào của mình.
            if (deep) {
                try { pushScan("MO XE BAN THAN (a.i):" + dumpAllFields(getI())); }
                catch (Exception ignore) {}
            }
            // CHÍNH ĐỐI TƯỢNG MAP. Chưa bao giờ mổ — mới chỉ mổ quái, NPC và nhân vật.
            //
            // Đang tìm: DANH SÁCH CỬA / ĐIỂM CHUYỂN MÀN. Client bắt buộc phải biết chúng ở đâu —
            // nó vẽ được mũi tên và biết lúc nào chạm cửa thì đổi map — nên dữ liệu đó nằm sẵn
            // trong bộ nhớ. Có nó thì Sơn cáp khỏi phải đoán mép map, và Ải gia tộc cũng dùng
            // được cho việc chuyển cổng.
            // Tìm mảng/Vector nhỏ vài phần tử, hoặc trường tên kiểu "cua/link/exit" giữ toạ độ.
            if (deep) {
                try { pushScan("MO XE DOI TUONG MAP (z):" + dumpAllFields(getZ())); }
                catch (Exception ignore) {}
                try { pushScan("CAC DANH SACH NHO CUA MAP:" + dumpSmallLists(getZ())); }
                catch (Exception ignore) {}
            }
            // BẢNG NỐI MAP — in ở MỌI lần soi, không chỉ lần mổ xẻ. Ngắn, và là thứ trả lời
            // "map nào nối map nào, biển ở đâu" mà không cần vào map đó.
            {
                String ds = getSetting("scan_link_maps", "");
                pushScan("BANG NOI MAP" + (ds.isEmpty() ? " (map dang dung)" : " [loc: " + ds + "]")
                        + ":" + dumpMapLinks(ds.isEmpty()
                            ? String.valueOf(getCurrentMapId()) : ds));
            }

            scanOneVector("z.E", zFieldE, nguoiCls, quaiCls, deep);
            scanOneVector("z.O", zFieldO, nguoiCls, quaiCls, deep);
            scanOneVector("z.F", zFieldF, nguoiCls, quaiCls, deep);
            scanOneVector("z.D", zFieldD, nguoiCls, quaiCls, deep);
            pushScan("DAN SO KHU:" + dumpZonePeople());
            if (getSettingInt("scan_bag", 1) == 1) pushScan("HANG TRANG:" + dumpBag());
            String timChu = getSetting("scan_text_find", "giao d");
            if (timChu != null && !timChu.isEmpty())
                pushScan("HANG CHU chua '" + timChu + "':" + dumpTextConstants(timChu));
            if (getSettingInt("scan_dialog", 1) == 1) pushScan("CHONG BANG:" + dumpPanelStack());
            String chuTheoTen = getSetting("scan_text_fields", "");
            if (chuTheoTen != null && !chuTheoTen.isEmpty())
                pushScan("HANG CHU theo ten:" + dumpTextByName(chuTheoTen));
            pushScan("countAliveMobs() dang tra ve: " + countAliveMobs()
                    + "  (= so 'song' cua z.E TRU di nhung con <KHONG DANH DUOC>)");
            if (deep) scanDeepDone++;
            return "da soi xong - xem log";
        } catch (Exception e) {
            return "LOI: " + e;
        } finally {
            scanAuto = false;
        }
    }

    /**
     * ĐO dân số khu: `z.D` có đúng bằng số người trong khu không, và tên người chơi nằm ở
     * trường nào của `a.i`.
     *
     * CHỈ ĐỌC VÀ IN — không một máy hoạt động nào gọi hàm này. Nó nằm ở nút 🧲 (scanner thuần)
     * vì chưa được phép tin: cần đối chiếu số in ra với số người thật thấy trên màn hình / trong
     * bảng khu của game. Có hai điều còn phải xác nhận:
     *   1. Server gửi TOÀN BỘ người trong khu ngay lúc vào, hay chỉ gửi dần khi tới gần?
     *   2. `a.i` có hai trường String (`k` và `l`) — cái nào là tên nhân vật?
     * Trả lời được hai câu đó thì trưởng nhóm mới dùng được nó để biết "khu còn mấy chỗ" NGAY
     * lúc vừa tới, thay vì phải tốn trọn một vòng lập nhóm → báo khu → member kẹt → dời khu
     * (≥30s và hai lượt khoá 15s cho một khu vốn đã biết là đầy).
     */
    private String dumpZonePeople() {
        StringBuilder sb = new StringBuilder();
        try {
            if (zFieldD == null) return " (khong doc duoc z.D)";
            Object listObj = zFieldD.get(getZ());
            if (!(listObj instanceof java.util.Vector)) return " (z.D khong phai Vector)";
            java.util.Vector<?> v = (java.util.Vector<?>) listObj;
            sb.append("\n  z.D.size() = ").append(v.size())
              .append("  <- so nguoi trong khu ").append(getCurrentZoneId())
              .append(" (map ").append(getCurrentMapId()).append(")");
            // In cả hai trường String của a.i cho MỖI người: cái nào ra tên nhân vật thì cái đó
            // là trường tên. Đoán một cái rồi tin luôn chính là bẫy đã sập nhiều lần.
            Class<?> cls = null;
            try { cls = Class.forName("a.i"); } catch (Exception ignore) {}
            Field fk = null, fl = null;
            if (cls != null) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    if (f.getName().equals("k")) fk = f;
                    if (f.getName().equals("l")) fl = f;
                }
            }
            sb.append("\n  (a.i.k / a.i.l — cai nao ra ten nhan vat thi cai do la truong ten)");
            int in = Math.min(v.size(), getSettingInt("scan_people_max", 20));
            for (int i = 0; i < in; i++) {
                Object e = v.elementAt(i);
                if (e == null) { sb.append("\n   [").append(i).append("] null"); continue; }
                sb.append("\n   [").append(i).append("] ").append(e.getClass().getName());
                if (fk != null) sb.append("  k='").append(fk.get(e)).append("'");
                if (fl != null) sb.append("  l='").append(fl.get(e)).append("'");
                try {
                    if (mobFieldAr != null && mobFieldAs != null) {
                        sb.append("  (").append(mobFieldAr.getShort(e)).append(",")
                          .append(mobFieldAs.getShort(e)).append(")");
                    }
                } catch (Exception ignore) {}
            }
            if (v.size() > in) sb.append("\n   ... con ").append(v.size() - in).append(" nguoi nua");
        } catch (Exception e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    /**
     * Mổ luôn BẢN MẪU của thực thể — `a.fn.a()` trả về `a.fo`.
     *
     * Vì sao cần: bản thân thực thể chỉ mang số liệu của MỘT cá thể (toạ độ, HP còn lại). Cái
     * quyết định "nó LÀ gì" nằm ở bản mẫu dùng chung cho cả loại — và bản mẫu thường giữ TÊN.
     * Trường `ad` đọc từ gói mạng đang rỗng, nên tên (nếu có) chỉ còn ở đây.
     *
     * Đây là cách trả lời câu "13 thứ ở Làng Cỏ là gì" bằng dữ liệu thay vì bằng suy luận từ
     * tên lớp. Trước đó mới chỉ đọc mỗi `a.fo.r` — một byte trong cả bản mẫu.
     */
    private String dumpTemplate(Object e) {
        // NPC (a.fr) đi với bản mẫu a.fs, quái (a.fn) đi với a.fo — hai họ khác nhau.
        String npc = npcNameOf(e);
        if (npc != null) {
            try {
                Object fs = frMethodTemplate.invoke(e);
                return "\n      --- BAN MAU NPC " + fs.getClass().getName() + " ---" + dumpAllFields(fs);
            } catch (Exception ignore) { return ""; }
        }
        if (mobMethodFo == null) return "";
        try {
            Object fo = mobMethodFo.invoke(e);
            if (fo == null) return "\n      (ban mau a.fo = null)";
            return "\n      --- BAN MAU " + fo.getClass().getName() + " ---" + dumpAllFields(fo);
        } catch (Exception ex) {
            return "";
        }
    }

    private Method frMethodTemplate;   // a.fr.a() → a.fs, nạp lười
    private Field foFieldName;         // a.fo.l → String, TÊN quái
    private Field foFieldTitle;        // a.fo.v → String, DANH HIỆU ('Linh thú' cho boss)

    /**
     * {tên, danh hiệu} của quái, đọc từ BẢN MẪU a.fo. null nếu không phải quái hoặc không đọc được.
     *
     * Tên thật KHÔNG nằm ở `a.fn.ad` — trường đó rỗng với mọi con đã gặp. Nó nằm ở bản mẫu dùng
     * chung cho cả loại. Bản soi map 94 (17:57 ngày 29/07):
     *     quái thường  l='Bọ rùa'     v=''
     *     boss         l='Gamatatsu'  v='Linh thú'
     * Danh hiệu vì vậy là dấu hiệu tách boss khỏi quái thường — chắc hơn cờ `a.fn.aZ`, vì cờ đó
     * ĐO ĐƯỢC LÀ FALSE trên chính con boss này (nó chỉ đúng với boss map train ngoài).
     */
    private String[] mobTemplateName(Object e) {
        if (mobMethodFo == null) return null;
        try {
            Object fo = mobMethodFo.invoke(e);
            if (fo == null) return null;
            if (foFieldName == null || foFieldTitle == null) {
                for (Field f : fo.getClass().getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    if (f.getName().equals("l")) { f.setAccessible(true); foFieldName = f; }
                    if (f.getName().equals("v")) { f.setAccessible(true); foFieldTitle = f; }
                }
            }
            if (foFieldName == null) return null;
            Object ten = foFieldName.get(fo);
            Object dh = (foFieldTitle == null) ? null : foFieldTitle.get(fo);
            return new String[]{ ten == null ? "" : ten.toString(), dh == null ? "" : dh.toString() };
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * TÊN NPC, đọc đúng đường mà findNpcByName đã dùng: a.fr.a() → a.fs, rồi lấy trường tên.
     * @return tên NPC, hoặc null nếu đây không phải NPC
     */
    private String npcNameOf(Object e) {
        try {
            if (frClass == null || fsClass == null || fsFieldL == null) return null;
            if (!frClass.isInstance(e)) return null;
            if (frMethodTemplate == null) {
                for (Method m : frClass.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 0
                            && m.getReturnType() == fsClass) {
                        m.setAccessible(true); frMethodTemplate = m; break;
                    }
                }
            }
            if (frMethodTemplate == null) return null;
            Object tpl = frMethodTemplate.invoke(e);
            if (tpl == null) return null;
            Object nm = fsFieldL.get(tpl);
            return (nm instanceof String) ? (String) nm : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Mở TỪNG PHẦN TỬ của các danh sách nhỏ trong đối tượng map.
     *
     * Đang tìm CỬA SANG MAP KHÁC. Bản mổ 18:30 ngày 29/07 cho biết đối tượng map giữ rất nhiều
     * Vector: E(61) đúng là quái, L(48), y(32), Q(29), p(27), u(16), w(10)... và một loạt danh
     * sách nhỏ D(5) I(5) g(5) J(4) z(3) A(2) H(1) K(1). Một trong số đó gần như chắc chắn là
     * danh sách điểm chuyển màn — số lượng vài phần tử đúng tầm "một map có vài lối ra".
     *
     * Vì sao phải mở tận phần tử: `dumpAllFields` chỉ in được KÍCH THƯỚC của Vector, mà kích
     * thước thì không phân biệt được "5 cái cửa" với "5 hiệu ứng hình ảnh". Phải nhìn phần tử
     * mới biết cái nào mang TOẠ ĐỘ.
     *
     * Chỉ mở danh sách có ít hơn `scan_list_max` phần tử: mấy cái to (quái, ô đất) đã biết là gì
     * rồi, in ra chỉ tổ ngập log.
     */
    private String dumpSmallLists(Object o) {
        if (o == null) return " (null)";
        StringBuilder sb = new StringBuilder();
        int max = getSettingInt("scan_list_max", 10);
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try { fs = c.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(o);
                    if (val == null) continue;

                    java.util.List<Object> items = new java.util.ArrayList<Object>();
                    if (val instanceof java.util.Vector) {
                        java.util.Vector<?> v = (java.util.Vector<?>) val;
                        if (v.isEmpty() || v.size() > max) continue;
                        for (int i = 0; i < v.size(); i++) items.add(v.elementAt(i));
                    } else if (val.getClass().isArray()
                            && !val.getClass().getComponentType().isPrimitive()) {
                        int n = java.lang.reflect.Array.getLength(val);
                        if (n == 0 || n > max) continue;
                        for (int i = 0; i < n; i++) items.add(java.lang.reflect.Array.get(val, i));
                    } else {
                        continue;
                    }

                    sb.append("\n      [").append(c.getSimpleName()).append(".").append(f.getName())
                      .append("] x").append(items.size());
                    for (int i = 0; i < items.size(); i++) {
                        Object it = items.get(i);
                        sb.append("\n        #").append(i).append(" ");
                        if (it == null) { sb.append("null"); continue; }
                        sb.append(it.getClass().getName());
                        // In HẾT trường số của phần tử — toạ độ cửa (nếu có) chắc chắn nằm trong đó.
                        try {
                            for (Class<?> ec = it.getClass(); ec != null && ec != Object.class; ec = ec.getSuperclass()) {
                                for (Field ef : ec.getDeclaredFields()) {
                                    if (java.lang.reflect.Modifier.isStatic(ef.getModifiers())) continue;
                                    Class<?> t = ef.getType();
                                    if (!t.isPrimitive() && t != String.class) continue;
                                    ef.setAccessible(true);
                                    Object ev = ef.get(it);
                                    sb.append(" ").append(ef.getName()).append("=")
                                      .append(ev instanceof String ? "'" + ev + "'" : String.valueOf(ev));
                                }
                            }
                        } catch (Throwable ignore) {}
                    }
                } catch (Throwable ignore) {}
            }
        }
        return sb.length() == 0 ? " (khong co danh sach nho nao)" : sb.toString();
    }

    /**
     * In HẾT trường của một đối tượng, đi ngược lên cả cây thừa kế.
     *
     * Không lọc theo tên trường: cả bài toán ở đây là chưa biết trường nào mang nghĩa gì, lọc
     * theo hiểu biết hiện tại thì đúng cái mình đang thiếu sẽ bị bỏ đi. Đối tượng con thì chỉ in
     * TÊN LỚP chứ không gọi toString(): mã đã bị làm rối, toString() của nó có thể in ra cả cây
     * hoặc ném lỗi.
     */
    /**
     * DÒ HÀNG TRANG — chỉ đọc và in, không đụng vào món nào.
     *
     * Lớp người chơi `a.i` giữ BẢY mảng `d_0[]` (a,b,c,d,e,f,g) và chưa biết mảng nào là túi,
     * mảng nào là đồ đang mặc / rương / hòm. Code của chính game dùng `i.a().a` và `i.a().c` ở
     * hai chỗ khác nhau, nên không suy ra được bằng đọc tĩnh.
     *
     * ĐỐI CHIẾU BẰNG SỐ NGƯỜI DÙNG NHÌN THẤY. Ảnh chụp túi ngày 01/08 cho "132/189" ⇒ mảng nào
     * dài 189 và có đúng 132 ô khác null thì mảng đó là túi. Không cần đoán một chữ nào.
     * Số đo 03/08 cho `a.i.a` dài đúng 189 — chốt.
     *
     * BA TRƯỜNG ĐÃ CHỐT (03/08), không còn phải mò:
     *   `e`  = MÃ VẬT PHẨM · `cf` = SỐ LƯỢNG · `ch` = SỐ Ô · `ax` = khoá, không giao dịch được.
     * Cách chốt: dựng cột `cf` của các ô đầu (26, 218, 465, 505, 1187, 1568, 906) rồi so với số
     * hiện trên ô đồ trong ảnh — khớp trọn bộ, đúng thứ tự. Mã game xác nhận lại hai lần:
     * hàm dựng `d_0(int ma, boolean khoa, int soLuong)` gán `this.e = ma; this.cf = soLuong`,
     * và hàm lấy số lượng trả `cf <= 0 ? 1 : cf`.
     *
     * `byte H` mà em từng đoán là số lượng thì SAI — ảnh có số 1568, vượt tầm byte. Đoán trường
     * rồi tin luôn chính là cái bẫy đã sập nhiều lần ở dự án này.
     *
     * KHÔNG DÒ THEO TÊN LỚP. Bản chạy 03/08 không thấy mảng nào vì em so tên với "a.d_0" —
     * mà "d_0" là tên do CFR ĐẶT RA lúc dịch ngược, không phải tên thật. Windows không phân
     * biệt hoa thường trong tên file nên `a/d.class` và `a/D.class` không thể cùng nằm một thư
     * mục; CFR giữ `d` và đổi `D` thành `d_0`. Lớp vật phẩm thật là `a.D`, cửa sổ giao dịch
     * thật là `a.S` (`s_0`). Cùng luật đó: `ff_0`=fF, `bn_0`=bN, `df_0`=dF, `ch_0`=cH, `bi_0`=bI.
     * Nên ở đây nhận diện lớp vật phẩm BẰNG HÌNH DẠNG — lớp nào có cả `ch`(int) lẫn `ax`(boolean)
     * thì đó là vật phẩm — đổi tên kiểu gì cũng không gãy.
     */
    private String dumpBag() {
        StringBuilder sb = new StringBuilder();
        try {
            Object me = getI();
            if (me == null) return " (khong lay duoc nhan vat)";
            Class<?> cls = me.getClass();
            int sample = getSettingInt("scan_bag_sample", 3);
            int listMax = getSettingInt("scan_bag_max", 40);
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().isArray()) continue;
                if (!laLopVatPham(f.getType().getComponentType())) continue;
                f.setAccessible(true);
                Object arr = f.get(me);
                if (arr == null) { sb.append("\n  a.i.").append(f.getName()).append(" = null"); continue; }
                int len = java.lang.reflect.Array.getLength(arr);
                int co = 0;
                for (int i = 0; i < len; i++) if (java.lang.reflect.Array.get(arr, i) != null) co++;
                // `a.i.a` LÀ TÚI — không phải suy đoán. Đường huỷ giao dịch của chính game
                // (a.s_0.M) trả món về bằng `i.a().a[ mon.ch ] = mon`, tức mảng `a` là túi và
                // `d_0.ch` là SỐ Ô trong túi.
                boolean laTui = "a".equals(f.getName());
                int traoDoiDuoc = 0;
                for (int i = 0; i < len; i++) {
                    Object it = java.lang.reflect.Array.get(arr, i);
                    if (it != null && !khoaKhongTraoDoi(it)) traoDoiDuoc++;
                }
                sb.append("\n  a.i.").append(f.getName())
                  .append("  dai=").append(len).append("  co do=").append(co)
                  .append("  giao dich duoc=").append(traoDoiDuoc)
                  .append(laTui ? "   <<< TUI (a.s_0.M tra do ve mang nay)" : "");
                int in = 0, mo = 0;
                for (int i = 0; i < len && in < listMax; i++) {
                    Object it = java.lang.reflect.Array.get(arr, i);
                    if (it == null) continue;
                    in++;
                    sb.append("\n   ").append(moTaMon(it));
                    if (mo < sample) {
                        mo++;
                        sb.append("\n      MO XE:").append(dumpAllFields(it));
                    }
                }
                if (co > in) sb.append("\n   ... con ").append(co - in).append(" mon nua"
                        + " (nang scan_bag_max neu muon xem het)");
            }
            if (sb.length() == 0) sb.append(" (khong thay mang vat pham nao tren " + cls.getName() + ")");
        } catch (Exception e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    /**
     * IN CÁC HẰNG CHỮ của `com.c.a.a` có chứa một cụm từ.
     *
     * Bảng chữ tiếng Việt của game nằm ở `com.c.a.a`, nhưng KHÔNG đọc tĩnh được: các trường
     * String không mang ConstantValue và `<clinit>` cũng không có cặp `ldc → putstatic` nào —
     * nghĩa là chữ được nạp lúc chạy từ dữ liệu ngoài. Chỉ còn cách hỏi lúc game đang chạy.
     *
     * Dùng để trả lời câu hỏi cụ thể: trong dây chuyền mời giao dịch, ô thoại xác nhận in ra
     * `com.c.a.a.hJ`. Biết chữ của `hJ` là biết chắc lệnh 86 có phải lời mời GIAO DỊCH hay
     * không — thay vì suy từ hình dạng dây chuyền rồi tin.
     */
    private String dumpTextConstants(String tim) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> cls = Class.forName("com.c.a.a");
            // Nhận NHIỀU cụm ngăn bằng '|'. Mỗi lần đổi cụm tìm là phải khởi động lại client
            // (config chỉ đọc một lần lúc khởi động), nên hỏi được nhiều thứ trong một lần bấm
            // là tiết kiệm hẳn một vòng đóng-mở 12 client.
            String[] cumTim = tim.toLowerCase().split("\\|");
            int co = 0, tran = getSettingInt("scan_text_max", 60);
            for (Field f : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(null); } catch (Throwable ignore) { continue; }
                if (v instanceof String) {
                    String s = (String) v;
                    if (khopCum(s, cumTim) && co++ < tran)
                        sb.append("\n  ").append(f.getName()).append(" = '").append(s).append("'");
                } else if (v instanceof String[]) {
                    String[] mang = (String[]) v;
                    for (int i = 0; i < mang.length; i++) {
                        if (mang[i] == null) continue;
                        if (khopCum(mang[i], cumTim) && co++ < tran)
                            sb.append("\n  ").append(f.getName()).append("[").append(i)
                              .append("] = '").append(mang[i]).append("'");
                    }
                }
            }
            if (co == 0) sb.append(" (khong co chuoi nao chua cum nay)");
            else if (co > tran) sb.append("\n  ... con ").append(co - tran).append(" chuoi nua");
        } catch (Throwable e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    private boolean khopCum(String s, String[] cum) {
        String t = s.toLowerCase();
        for (String c : cum) {
            c = c.trim();
            if (!c.isEmpty() && t.contains(c)) return true;
        }
        return false;
    }

    /**
     * ẢNH CHỤP CHỒNG BẢNG ĐANG MỞ — mỗi bảng một dòng: tên lớp thật, cùng các mảng vật phẩm và
     * trường số của nó.
     *
     * Dùng để nhận ra cửa sổ do server dựng mà chưa biết là lớp nào. Bản dịch ngược có tới mười
     * lớp cùng giữ `d_0[16]`, đoán bằng mắt là hoà; mở đúng cửa sổ đó trong game rồi bấm 🧲 thì
     * nó tự khai tên. Đây cũng là cách đã dùng để tìm ra cửa sổ giao dịch `a.S`.
     */
    private String dumpPanelStack() {
        StringBuilder sb = new StringBuilder();
        try {
            if (fkFieldAn == null) return " (chua map duoc chong bang z.an)";
            Object zInst = getZ();
            if (zInst == null) return " (chua lay duoc z)";
            java.util.Vector<?> stack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (stack == null || stack.isEmpty()) return " (chong bang rong - chua mo cua so nao)";
            for (int i = 0; i < stack.size(); i++) {
                Object p = stack.get(i);
                if (p == null) { sb.append("\n  [").append(i).append("] null"); continue; }
                Class<?> c = p.getClass();
                sb.append("\n  [").append(i).append("] ").append(c.getName());
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        if (f.getType().isArray() && laLopVatPham(f.getType().getComponentType())) {
                            Object arr = f.get(p);
                            int n = (arr == null) ? -1 : java.lang.reflect.Array.getLength(arr);
                            int co = 0;
                            for (int k = 0; k < n; k++)
                                if (java.lang.reflect.Array.get(arr, k) != null) co++;
                            sb.append("\n      mang do .").append(f.getName())
                              .append(" dai=").append(n).append(" co do=").append(co);
                            for (int k = 0; k < n; k++) {
                                Object mon = java.lang.reflect.Array.get(arr, k);
                                if (mon != null) sb.append("\n        ").append(moTaMon(mon));
                            }
                        } else if (f.getType() == int.class || f.getType() == byte.class
                                || f.getType() == short.class || f.getType() == boolean.class
                                || f.getType() == String.class) {
                            Object v = f.get(p);
                            if (v == null) continue;
                            String s = String.valueOf(v);
                            if (s.equals("0") || s.equals("false") || s.isEmpty()) continue;
                            sb.append("  ").append(f.getName()).append("=").append(s);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    /**
     * IN CHỮ CỦA MẤY TRƯỜNG ĐƯỢC GỌI ĐÍCH DANH trong `com.c.a.a` — danh sách tên cách nhau dấu phẩy.
     *
     * Dùng khi đã biết cần hỏi trường nào. Ví dụ đang cần: hộp thoại mời giao dịch gắn nút
     * nhãn `bP` với mã 2998 và nút nhãn `e` với mã 2999; 2998 gửi lệnh 85, 2999 gửi lệnh 84.
     * Đọc chữ của `bP` và `e` là biết chắc lệnh nào ĐỒNG Ý — thay vì đoán theo thứ tự nút.
     */
    private String dumpTextByName(String danhSach) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> cls = Class.forName("com.c.a.a");
            for (String ten : danhSach.split(",")) {
                ten = ten.trim();
                if (ten.isEmpty()) continue;
                try {
                    Field f = cls.getDeclaredField(ten);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof String[]) {
                        String[] m = (String[]) v;
                        sb.append("\n  ").append(ten).append(" = mang ").append(m.length).append(" phan tu");
                        for (int i = 0; i < m.length && i < 12; i++)
                            sb.append("\n    [").append(i).append("] '").append(m[i]).append("'");
                    } else {
                        sb.append("\n  ").append(ten).append(" = '").append(v).append("'");
                    }
                } catch (NoSuchFieldException e) {
                    sb.append("\n  ").append(ten).append(" = (khong co truong nay)");
                }
            }
        } catch (Throwable e) {
            sb.append(" loi: ").append(e);
        }
        return sb.toString();
    }

    /**
     * Lớp này có phải LỚP VẬT PHẨM không — nhận theo hình dạng, không theo tên.
     *
     * Hai trường đã có bằng chứng chắc từ mã game: `ch`(int) là số ô trong túi (đường huỷ giao
     * dịch trả món về bằng `i.a().a[mon.ch] = mon`), `ax`(boolean) là cờ không giao dịch được
     * (mục "đưa vào ô giao dịch" chỉ hiện khi `!mon.ax`). Không lớp nào khác có đủ cặp này.
     */
    private boolean laLopVatPham(Class<?> c) {
        if (c == null || c.isPrimitive()) return false;
        boolean coCh = false, coAx = false;
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if ("ch".equals(f.getName()) && f.getType() == int.class) coCh = true;
            if ("ax".equals(f.getName()) && f.getType() == boolean.class) coAx = true;
        }
        return coCh && coAx;
    }

    /**
     * BẢN MẪU của một món — `n.a().a[ mon.e ]`, nơi giữ TÊN vật phẩm ở trường `l`.
     *
     * Không gọi thẳng `a.n` mà hỏi ngược từ chính món: lớp vật phẩm có ba hàm cùng tên `a()`
     * (bytecode cho phép trùng tên khác kiểu trả về, mã nguồn thì không) — một trả String, một
     * trả mảng, và một trả BẢN MẪU. Lọc theo kiểu trả về nên không phụ thuộc tên lớp nào cả,
     * đúng bài học `d_0` ↔ `a.D`.
     */
    private Object banMauMon(Object it) {
        try {
            for (Method m : it.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive() || rt.isArray()) continue;
                if (rt == String.class || rt == it.getClass()) continue;
                m.setAccessible(true);
                Object bm = m.invoke(it);
                if (bm == null) continue;
                // Bản mẫu là lớp có trường tên `l` kiểu String — đó là tên hiển thị.
                for (Field f : bm.getClass().getDeclaredFields()) {
                    if ("l".equals(f.getName()) && f.getType() == String.class) return bm;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** Tên vật phẩm đọc từ bản mẫu; rỗng nếu không tra được. */
    private String tenMon(Object it) {
        try {
            Object bm = banMauMon(it);
            if (bm == null) return "";
            Field f = bm.getClass().getDeclaredField("l");
            f.setAccessible(true);
            Object v = f.get(bm);
            return (v == null) ? "" : String.valueOf(v);
        } catch (Throwable ignore) {}
        return "";
    }

    /** Đọc một trường số của món theo tên (đã có bằng chứng: e=mã, cf=số lượng, ch=số ô). */
    private int truongSo(Object it, String ten) {
        try {
            Field f = it.getClass().getDeclaredField(ten);
            f.setAccessible(true);
            Object v = f.get(it);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignore) {}
        return -1;
    }

    /** `ax` = TRUE ⇒ món bị khoá, KHÔNG đưa vào ô giao dịch được. */
    private boolean khoaKhongTraoDoi(Object it) {
        try {
            Field f = it.getClass().getDeclaredField("ax");
            f.setAccessible(true);
            return f.getBoolean(it);
        } catch (Throwable ignore) {}
        return true;   // không đọc được thì coi như khoá — thà bỏ sót còn hơn gửi nhầm
    }

    /**
     * MỘT DÒNG CHO MỘT MÓN — đủ để chọn ra danh sách mã cần gom.
     *
     * Ba trường đã chốt bằng số đo ngày 03/08, không còn đoán:
     *   `e`  = MÃ VẬT PHẨM  — chỉ số vào bảng mẫu `n.a().a[]`, tên nằm ở `.l`
     *   `cf` = SỐ LƯỢNG     — khớp trọn bộ số trên ảnh chụp túi (26, 218, 465, 505, 1187, 1568,
     *                         906); hàm lấy số lượng của game trả `cf <= 0 ? 1 : cf`
     *   `ch` = SỐ Ô         — số mà giao thức giao dịch dùng để chỉ món
     */
    private String moTaMon(Object it) {
        int ma = truongSo(it, "e");
        int soLuong = truongSo(it, "cf");
        int o = truongSo(it, "ch");
        String ten = tenMon(it);
        StringBuilder sb = new StringBuilder();
        sb.append("o[").append(o).append("] ma=").append(ma)
          .append(" sl=").append(soLuong <= 0 ? 1 : soLuong)
          .append(khoaKhongTraoDoi(it) ? " [KHOA]" : " [giao dich duoc]");
        if (!ten.isEmpty()) sb.append(" ten='").append(ten).append("'");
        int stt = soTinhThach(it);
        if (stt > 0) sb.append("  tinhthach=").append(stt)
                       .append(" cap=").append(tinhThachCapYeuCau(it));
        int[] opt = dsMaTuyChon(it);
        if (opt.length > 0) sb.append("  opt=").append(java.util.Arrays.toString(opt));
        return sb.toString();
    }

    private String dumpAllFields(Object o) {
        if (o == null) return " (null)";
        StringBuilder sb = new StringBuilder();
        int maxStr = getSettingInt("scan_str_len", 48);
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try { fs = c.getDeclaredFields(); } catch (Throwable t) { continue; }
            StringBuilder line = new StringBuilder();
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(o);
                    String s;
                    if (val == null) s = "null";
                    else if (val instanceof String) {
                        s = (String) val;
                        if (s.length() > maxStr) s = s.substring(0, maxStr) + "..";
                        s = "'" + s + "'";
                    } else if (val.getClass().isArray()) {
                        s = val.getClass().getComponentType().getSimpleName()
                          + "[" + java.lang.reflect.Array.getLength(val) + "]";
                    } else if (val instanceof java.util.Collection) {
                        s = val.getClass().getSimpleName() + "(" + ((java.util.Collection<?>) val).size() + ")";
                    } else if (val instanceof Number || val instanceof Boolean || val instanceof Character) {
                        s = String.valueOf(val);
                    } else {
                        s = "<" + val.getClass().getName() + ">";
                    }
                    line.append(" ").append(f.getName())
                        .append(":").append(f.getType().getSimpleName())
                        .append("=").append(s);
                } catch (Throwable ignore) {}
            }
            if (line.length() > 0) sb.append("\n      [").append(c.getName()).append("]").append(line);
        }
        return sb.toString();
    }

    /** Hậu tố phân loại "/r=..,V=..,loai=.." cho biểu đồ; rỗng nếu không đọc được. */
    private String kindOf(Object e) {
        StringBuilder sb = new StringBuilder();
        try {
            if (mobMethodFo != null && foFieldR != null) {
                Object fo = mobMethodFo.invoke(e);
                if (fo != null) sb.append("/r=").append(foFieldR.getByte(fo));
            }
        } catch (Exception ignore) {}
        try {
            if (mobFieldV != null) sb.append(",V=").append(mobFieldV.getByte(e));
        } catch (Exception ignore) {}
        try {
            if (mobFieldType != null) sb.append(",loai=").append(mobFieldType.getShort(e));
        } catch (Exception ignore) {}
        return sb.toString();
    }

    private void scanOneVector(String ten, Field f, Class<?> nguoiCls, Class<?> quaiCls, boolean deep) {
        if (f == null) { pushScan(ten + ": KHONG tra duoc field"); return; }
        try {
            Object o = f.get(getZ());
            if (!(o instanceof java.util.Vector)) { pushScan(ten + ": khong phai Vector"); return; }
            java.util.Vector<?> v = (java.util.Vector<?>) o;

            java.util.LinkedHashMap<String, int[]> hist = new java.util.LinkedHashMap<String, int[]>();
            java.util.LinkedHashMap<String, Object> daiDien = new java.util.LinkedHashMap<String, Object>();
            int nguoi = 0, quai = 0, khac = 0, song = 0, chet = 0;
            int nList = 0, maxList = getSettingInt("scan_max_list", 60);
            StringBuilder ds = new StringBuilder();

            for (int i = 0; i < v.size(); i++) {
                Object e = v.elementAt(i);
                if (e == null) continue;
                // Gộp theo LỚP + r + V chứ không chỉ theo lớp: một lớp `a.fn` có thể chứa
                // nhiều loại thực thể khác hẳn nhau, và r/V mới là thứ hàm chọn mục tiêu của
                // game dùng để phân biệt. Gộp theo lớp thôi thì che mất đúng cái cần biết.
                String cn = e.getClass().getName() + kindOf(e);
                int[] c = hist.get(cn);
                if (c == null) { c = new int[]{0}; hist.put(cn, c); daiDien.put(cn, e); }
                c[0]++;

                boolean laNguoi = nguoiCls.isInstance(e);
                boolean laQuai  = quaiCls.isInstance(e);
                if (laNguoi) nguoi++; else if (laQuai) quai++; else khac++;
                boolean daChet = isEntityDead(e);
                if (daChet) chet++; else song++;

                // LIỆT KÊ TỪNG CON, không lấy mẫu vài con nữa. Trong hầm Cấm thuật cần biết CON
                // NÀO còn sống ở đâu để đối chiếu với mục tiêu cả nhóm đang dồn vào; lấy mẫu 4
                // con đầu thì đúng con đang đánh lại hay rơi ra ngoài. Trần scan_max_list chỉ để
                // chặn map đông bất thường, và khi cắt thì NÓI RA đã cắt bao nhiêu.
                if (nList < maxList) {
                    nList++;
                    ds.append("\n      #").append(i).append(" ").append(cn)
                      .append(laNguoi ? " [NGUOI CHOI]" : (laQuai ? " [quai]" : " [?]"));
                    try {
                        if (mobFieldId != null) ds.append(" id=").append(mobFieldId.getInt(e));
                        if (mobFieldAr != null && mobFieldAs != null)
                            ds.append(" (").append(mobFieldAr.getShort(e)).append(",")
                              .append(mobFieldAs.getShort(e)).append(")");
                        // TÊN và LOẠI — thứ duy nhất phân biệt được "quái thật" với thứ khác.
                        // a.fn.ad là String đọc thẳng từ gói mô tả thực thể; a.fn.D là mã loại
                        // (chính là thứ nhiệm vụ đem so với dq.ci để biết cần săn con nào).
                        if (mobFieldName != null) {
                            Object nm = mobFieldName.get(e);
                            ds.append(" ten='").append(nm == null ? "" : nm.toString()).append("'");
                        }
                        // NPC có tên nằm ở bản mẫu riêng (a.fs), không phải ở trường ad. In ra để
                        // đối chiếu thẳng: danh sách NPC thật của map trông như thế nào.
                        String npcTen = npcNameOf(e);
                        if (npcTen != null) {
                            ds.append(" [NPC] Tên='").append(npcTen).append("'");
                            try {
                                if (frFieldAZ != null) ds.append(" EntityID=").append(frFieldAZ.getInt(e));
                                if (frFieldAh != null) ds.append(" TemplateID=").append(frFieldAh.getShort(e));
                            } catch (Exception ignore) {}
                        }
                        // TÊN THẬT của quái nằm ở BẢN MẪU (a.fo.l), không phải ở a.fn.ad — trường
                        // ad rỗng với mọi con đã gặp. Danh hiệu (a.fo.v) là thứ tách boss khỏi
                        // quái thường: 'Bọ rùa' để trống, còn 'Gamatatsu' mang 'Linh thú'.
                        String[] tenMau = mobTemplateName(e);
                        if (tenMau != null) {
                            ds.append(" mau='").append(tenMau[0]).append("'");
                            if (!tenMau[1].isEmpty()) ds.append(" danh-hieu='").append(tenMau[1]).append("'");
                        }
                        if (mobFieldType != null) ds.append(" loai=").append(mobFieldType.getShort(e));
                        // HP / cấp / cờ đặc biệt — in thẳng ở đây để lần đọc sau khỏi phải lần
                        // vào phần mổ xẻ. HP tối đa cũng chính là thứ phân biệt quái thật với
                        // vật thể trang trí (vật thể = 1).
                        if (mobFieldHp != null && mobFieldHpMax != null)
                            ds.append(" hp=").append(mobFieldHp.getInt(e))
                              .append("/").append(mobFieldHpMax.getInt(e));
                        if (mobFieldLevel != null) ds.append(" cap=").append(mobFieldLevel.getInt(e));
                        if (mobFieldExp != null) ds.append(" exp=").append(mobFieldExp.getInt(e));
                        if (mobFieldElite != null && mobFieldElite.getBoolean(e)) ds.append(" [THU LINH]");
                    } catch (Exception ignore) {
                        ds.append(" (khong doc duoc mot so truong)");
                    }
                    ds.append(daChet ? " CHET" : " song");
                    if (!isKillableMob(e)) ds.append(" <KHONG DANH DUOC>");
                }
            }
            if (v.size() > nList) ds.append("\n      ... con ").append(v.size() - nList)
                                   .append(" con nua khong in (scan_max_list=").append(maxList).append(")");

            StringBuilder h = new StringBuilder();
            for (java.util.Map.Entry<String, int[]> en : hist.entrySet()) {
                h.append(en.getKey()).append("x").append(en.getValue()[0]).append("  ");
            }
            pushScan(ten + ": tong " + v.size()
                    + " | NGUOI CHOI=" + nguoi + " quai=" + quai + " khac=" + khac
                    + " | song=" + song + " chet=" + chet
                    + "\n    lop: " + h + ds);

            // MỘT ĐẠI DIỆN CHO MỖI LOẠI, mổ ra hết trường. Mỗi loại một con là đủ: cùng loại thì
            // danh sách trường giống nhau, khác nhau chỉ ở giá trị — mà giá trị thì phần liệt kê
            // ở trên đã in theo từng con rồi.
            if (deep) {
                for (java.util.Map.Entry<String, Object> en : daiDien.entrySet()) {
                    pushScan("MO XE " + ten + " / " + en.getKey() + ":"
                            + dumpAllFields(en.getValue()) + dumpTemplate(en.getValue()));
                }
            }
        } catch (Exception ex) {
            pushScan(ten + ": loi " + ex);
        }
    }

    private void tickAgt(long now) {
        try {
            if (now > agtDeadline) {
                pushAgt("agt_end", false, "het gio o buoc " + agtStep + " - " + agtWhereAmI());
                resetAgt();
                return;
            }
            if (now < agtNextTime) return;

            final int stepMs  = getSettingInt("agt_step_ms", 200);
            final int mapWait = getSettingInt("agt_map_wait_ms", 2500);

            if (agtStep == AGT_GOTO_MAP) {
                int wantMap = agtMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    int[] pt = agtPoint(wantMap);
                    if (pt != null) navigateToMapXY(wantMap, pt[0], pt[1]);
                    else navigateToMap(wantMap);
                    agtProgress("dang o map " + curMap + " -> ve map " + wantMap);
                    agtNextTime = now + mapWait;
                    return;
                }
                agtStep = AGT_GOTO_NPC;
                agtNextTime = now + stepMs;
                return;
            }

            if (agtStep == AGT_GOTO_NPC) {
                int curMap = getCurrentMapId();
                int[] xy = agtPoint(curMap);
                if (xy == null) {
                    pushAgt("agt_end", false, "config khong khai npc_agt_" + curMap
                            + " -> khong biet dung dau. " + agtWhereAmI());
                    resetAgt();
                    return;
                }
                // id NPC lấy từ đối tượng đọc sống; nhớ lại để lần tra hụt sau không làm hỏng lượt
                // (mở NPC chỉ cần id, không cần nhìn thấy đối tượng).
                int[] npc = findNpc(getSetting("agt_npc", getSetting("cam_thuat_npc", "Onoki")),
                    getSettingInt("agt_npc_id", getSettingInt("cam_thuat_npc_id", 32)));
                if (npc != null) agtNpcId = npc[0];

                int range = getSettingInt("agt_range", 60);
                if (Math.abs(getPlayerX() - xy[0]) > range || Math.abs(getPlayerY() - xy[1]) > range) {
                    int px = getPlayerX(), py = getPlayerY();
                    int eps = getSettingInt("agt_moved_px", 8);
                    if (Math.abs(px - agtLastX) > eps || Math.abs(py - agtLastY) > eps) agtStuckTries = 0;
                    else agtStuckTries++;
                    agtLastX = px; agtLastY = py;
                    int far = getSettingInt("agt_far_px", 200);
                    if (agtStuckTries >= getSettingInt("agt_stuck_tries", 3)
                            || Math.abs(px - xy[0]) > far || Math.abs(py - xy[1]) > far) {
                        navigateToMapXY(curMap, xy[0], xy[1]);
                        agtStuckTries = 0;
                    } else {
                        navigateTo(curMap, xy[0], xy[1]);
                    }
                    if (now >= agtNextDiag) {
                        agtNextDiag = now + getSettingInt("agt_diag_ms", 15000);
                        agtProgress("dang di toi cho NPC - " + agtWhereAmI());
                    }
                    agtNextTime = now + getSettingInt("agt_walk_wait_ms", 1500);
                    return;
                }
                if (agtNpcId <= 0) {
                    dumpAllNpcsOnMap();
                    pushAgt("agt_end", false, "khong tra duoc id NPC '"
                            + getSetting("agt_npc", "Onoki") + "'. " + agtWhereAmI());
                    resetAgt();
                    return;
                }
                agtProgress("da toi cho NPC - " + agtWhereAmI());
                agtStep = AGT_OPEN_NPC;
                agtNextTime = now + stepMs;
                return;
            }

            if (agtStep == AGT_WAIT_SIGNAL) {
                agtStep = AGT_OPEN_NPC;
                agtNextTime = now + stepMs;
                return;
            }

            if (agtStep == AGT_OPEN_NPC) {
                closeAnyDialog();
                sendOpenNpc(agtNpcId);
                agtMapBefore = getCurrentMapId();
                agtStep = AGT_MENU;
                agtNextTime = now + getSettingInt("agt_npc_wait_ms", 600);
                return;
            }

            if (agtStep == AGT_MENU) {
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) {
                    agtNextTime = now + getSettingInt("agt_dialog_poll_ms", 300);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < menu.length; i++) sb.append("[").append(i).append("] ").append(menu[i]).append(" · ");
                agtProgress("menu NPC: " + sb);

                String parentKw = getSetting("agt_parent_keyword", "gia toc");
                // Ưu tiên bấm "Vào ải gia tộc". Nếu lần trước map không đổi thì thử bấm "Mở cửa ải" (Tộc trưởng)
                String subKw = agtCanTryOpen
                        ? getSetting("agt_open_keyword", "mo cua ai")
                        : getSetting("agt_enter_keyword", "vao ai gia toc");

                int[] hit = agtFindMenu(menu, parentKw, subKw);
                if (hit == null && agtCanTryOpen) {
                    // Nếu không có mục "Mở cửa ải" trong menu thì lùi về mục "Vào ải gia tộc"
                    subKw = getSetting("agt_enter_keyword", "vao ai gia toc");
                    hit = agtFindMenu(menu, parentKw, subKw);
                }

                if (hit == null) {
                    int iCha = -1;
                    boolean chaCoDauPhay = false;
                    String pNo = noAccent(parentKw);
                    for (int i = 0; i < menu.length; i++) {
                        if (menu[i] == null) continue;
                        String[] parts = menu[i].split(",");
                        if (!noAccent(parts[0]).contains(pNo)) continue;
                        iCha = i;
                        chaCoDauPhay = parts.length >= 2;
                        break;
                    }
                    String vi;
                    if (iCha < 0) {
                        vi = "KHONG thay muc cha '" + parentKw + "' -> sua agt_parent_keyword";
                    } else if (!chaCoDauPhay) {
                        vi = "thay muc cha '" + parentKw + "' o [" + iCha + "] nhung chuoi KHONG co dau phay";
                    } else {
                        vi = "co muc cha va co muc con, nhung khong muc con nao khop '" + subKw + "'";
                    }
                    closeAnyDialog();
                    pushAgt("agt_end", false, vi + " | Menu: " + sb);
                    resetAgt();
                    return;
                }

                if (getSettingInt("agt_dry_run", 0) == 1) {
                    closeAnyDialog();
                    pushAgt("agt_dry", true, "CHAY NHAP - se bam [" + hit[0] + "]["
                            + hit[1] + "] khop '" + subKw + "'. Doi agt_dry_run,0 de chay that.");
                    resetAgt();
                    return;
                }

                sendSelectMenuWithSub(agtNpcId, hit[0], hit[1]);
                agtProgress("da bam muc cha [" + hit[0] + "] muc con [" + hit[1] + "] khop '" + subKw + "'");
                agtVerifyWaits = 0;
                agtStep = AGT_VERIFY;
                agtNextTime = now + getSettingInt("agt_verify_ms", 1000);
                return;
            }

            if (agtStep == AGT_VERIFY) {
                int nowMap = getCurrentMapId();
                if (nowMap != agtMapBefore) {
                    int g1 = getSettingInt("agt_gate1_map", 46);
                    int g2 = getSettingInt("agt_gate2_map", 47);

                    if (g1 > 0 && nowMap == g1) {
                        pushAgt("agt_end", true, "DA VAO AI GIA TOC - CONG 1 (map " + nowMap + ")");
                    } else if (g2 > 0 && nowMap == g2) {
                        pushAgt("agt_end", true, "DA VAO AI GIA TOC - CONG 2 (map " + nowMap + ")");
                    } else {
                        pushAgt("agt_progress", true, "da doi map sang " + nowMap);
                    }

                    pushAgt("agt_in_gate", true, "da vao map " + nowMap);
                    agtGateMap = nowMap;
                    agtStep = AGT_IN_GATE;
                    agtSawMobs = false;
                    agtInGateAt = now;
                    agtDeadline = agtGateDeadline(now);
                    if (getSettingInt("agt_combat", 1) == 1) {
                        clearNavTarget();
                        setAutoCombat(true);
                        autoCombatRequested = true;
                    }
                    if (getSettingInt("agt_dump_map", 1) == 1) dumpMapEntities("vua vao map " + nowMap);
                    agtNextTime = now + getSettingInt("agt_poll_ms", 3000);
                    return;
                }

                if (++agtVerifyWaits > getSettingInt("agt_verify_tries", 6)) {
                    String msg = readAnyDialogText();
                    closeAnyDialog();
                    agtAttempts++;
                    int maxAttempts = getSettingInt("agt_max_attempts", 5);
                    if (agtAttempts >= maxAttempts) {
                        pushAgt("agt_end", false, "Da thu " + agtAttempts + " lan khong vao duoc ai (server bao: "
                                + (msg == null || msg.isEmpty() ? "khong ro" : msg) + ") -> chuyen sang AFK farm");
                        if (afkMapId > 0 && getSettingInt("agt_after_afk", 1) == 1) {
                            afkZoneChanged = false;
                            setEnabled(true);
                            setState(TaskState.AFK_FARM);
                            log("AGT: thu " + agtAttempts + " lan khong thanh cong -> quay ve treo map " + afkMapId + " khu " + afkZone);
                        }
                        resetAgt();
                        return;
                    }
                    if (!agtCanTryOpen) {
                        // Lần thử vào ải chưa đổi map -> đặt cờ để lần sau thử bấm "Mở cửa ải" (nếu là Tộc trưởng)
                        agtCanTryOpen = true;
                        agtStep = AGT_OPEN_NPC;
                        agtVerifyWaits = 0;
                        agtNextTime = now + getSettingInt("agt_npc_wait_ms", 1000);
                        agtProgress("map chua doi (lan " + agtAttempts + "/" + maxAttempts + ") -> thu mo/vao lai...");
                        return;
                    } else {
                        // Đã thử cả mục mở ải nhưng map vẫn chưa đổi -> chờ 3s thử lại
                        agtCanTryOpen = false;
                        agtStep = AGT_OPEN_NPC;
                        agtVerifyWaits = 0;
                        agtNextTime = now + getSettingInt("agt_retry_ms", 3000);
                        agtProgress("chua vao duoc ai (lan " + agtAttempts + "/" + maxAttempts + ") -> cho 3s thu lai...");
                        return;
                    }
                }
                agtNextTime = now + getSettingInt("agt_verify_ms", 1000);
                return;
            }

            // ─── ĐANG Ở TRONG ẢI: chỉ việc đánh và chờ ───
            //
            // Toàn bộ pha này KHÔNG phải làm gì ngoài giữ cho auto đánh luôn bật:
            //   · 3 phút đầu chưa có quái — cứ bật sẵn để đó
            //   · đánh hết quái thì game báo một câu rồi chờ 30s mới cho qua cổng 2
            //   · cổng 2 cũng vậy, tới lúc xong thì game TỰ ĐẨY RA LÀNG
            // Nên tín hiệu kết thúc là RỜI KHỎI HAI MAP CỔNG, giống hệt cách nhận biết bên
            // Cấm thuật. Và cũng vì game tự đẩy ra nên KHÔNG đặt hạn giờ mặc định.
            if (agtStep == AGT_IN_GATE) {
                int nowMap = getCurrentMapId();
                int g1 = getSettingInt("agt_gate1_map", 0);
                int g2 = getSettingInt("agt_gate2_map", 0);
                boolean inGate = (g1 > 0 && nowMap == g1) || (g2 > 0 && nowMap == g2);

                if (inGate) {
                    if (nowMap != agtGateMap) {
                        agtProgress("qua cong ke tiep: map " + agtGateMap + " -> map " + nowMap);
                        agtGateMap = nowMap;
                        agtClearAt = 0;
                        agtNextMoveTry = 0;
                        agtLastAlive = -2;
                        agtSawMobs = false;   // cổng 2 cũng phải chờ sinh quái từ đầu
                        agtInGateAt = now;
                        // XOÁ ĐÍCH CỦA LỆNH ĐI XUYÊN MAP NGAY KHI ĐÃ TỚI NƠI.
                        // navigateToMap() đặt đích là (0,0); tới map mới rồi mà để nguyên thì
                        // auto-nav gốc còn kéo nhân vật về góc map thay vì đứng lại đánh. Đây
                        // đúng là cái đã mất cả ngày truy ở phần cấm thuật — không lặp lại.
                        clearNavTarget();
                        if (getSettingInt("agt_combat", 1) == 1) {
                            setAutoCombat(true);
                            autoCombatRequested = true;
                        }
                        if (getSettingInt("agt_dump_map", 1) == 1) dumpMapEntities("map " + nowMap);
                        // IN NGAY CÁC LỐI RA của map cổng. Lượt AGT mỗi ngày một lần — dòng này
                        // trả lời luôn câu "map ải có biển chỉ đường không" ngay lúc vừa vào,
                        // chứ không phải đợi tới lúc sạch quái mới biết.
                        agtProgress("loi ra cua map " + nowMap + ":" + listMapExits());

                        // Báo Manager "tôi đã vào map cổng này" để nó bật BÁM THEO cho cả đám.
                        // Mục đích: tập trung hoả lực. 12 nick mỗi đứa đánh một con ở một góc
                        // thì tổng sát thương vẫn thế nhưng mất thời gian ĐI; dồn vào một con
                        // thì không ai phải đi, quái chết sớm nên số quái đánh lại mình cũng
                        // giảm nhanh hơn, và không còn cảnh cuối map mỗi con dở dang một ít.
                        //
                        // Gửi ở ĐÂY, ngay khi vừa vào một map cổng, và gửi lại mỗi lần đổi cổng
                        // (46 -> 47). Manager dựa vào trường map để dựng lại tuyến cho đúng
                        // cổng hiện tại — nhờ vậy không bao giờ có chuyện lead ở cổng 2 mà mem
                        // còn ở cổng 1 rồi máy bám theo phát lệnh đi xuyên map, giành tay lái
                        // với chính lệnh chuyển cổng của AGT.
                        if (getSettingInt("agt_follow", 1) == 1) {
                            pushAgt("agt_in_gate", true, "da vao map " + nowMap);
                        }
                    }
                    // KHÔNG bật lại đánh trong lúc đang băng qua map. Máy chuyển map vừa tắt đánh
                    // vừa đặt đích ra ngoài mép; bật lại ở đây là nhân vật đứng đánh tại chỗ thay
                    // vì đi — đúng cái mâu thuẫn "một vô lăng", và ở ải thì nó ăn trọn lượt của
                    // ngày vì cả nhóm kẹt lại cổng 1.
                    if (exStep == 0 && getSettingInt("agt_combat", 1) == 1 && !isAutoCombatOn()) {
                        clearNavTarget();
                        setAutoCombat(true);
                        log("AGT: auto combat bi tat -> bat lai");
                    }

                    // ĐẾM QUÁI CÒN SỐNG thay vì bắt câu thông báo.
                    // Game chỉ báo đúng một dòng khi map sạch quái, mà một dòng chữ thì phụ thuộc
                    // kênh hiển thị và cách đặt dấu — trong khi số quái sống là dữ kiện có sẵn
                    // trong bộ nhớ client (z.E, trạng thái 4/5/6 = chết). Đọc số bao giờ cũng
                    // chắc hơn đọc chữ.
                    //
                    // Trong ải gia tộc, quái ĐÃ KILL KHÔNG HỒI SINH (khác map train ngoài), nên
                    // ở CỔNG 1 số này giảm đơn điệu và chạm 0 là sạch thật.
                    //
                    // Ải CÓ boss, nhưng người dùng xác nhận (29/07) nó chỉ ra ở CỔNG 2 — tức cổng
                    // cuối, mà nhánh "sạch thì qua cổng sau" bên dưới bỏ qua cổng cuối. Nên boss
                    // của ải không đụng gì tới phép đếm này.
                    // Chỗ đặt lại đồng hồ bên dưới vì vậy là LƯỚI ĐỠ, không phải bản vá cho một
                    // lỗi đã biết: nó chỉ nói ra khi có quái xuất hiện lại ngoài dự kiến.
                    // (Cơ chế "hết quái ⇒ ra boss ⇒ giết boss mới qua được" là của SƠN CÁP.)
                    int alive = countAliveMobs();
                    if (alive != agtLastAlive) {
                        agtLastAlive = alive;
                        agtProgress("map " + nowMap + ": con " + (alive < 0 ? "?" : String.valueOf(alive))
                                + " quai song");
                    }

                    // Ngưỡng "coi như sạch". ĐỂ 0 và đừng nâng.
                    //
                    // Lời khuyên cũ ở đây là "thấy số chững ở đâu thì nâng ngưỡng lên đúng đó" —
                    // SAI, và nay đã bỏ. Lý do số chững là countAliveMobs() duyệt z.F, mà z.F
                    // chứa cả NGƯỜI CHƠI (a.i và a.fn cùng thừa kế a.x), nên 12 nick cùng gia
                    // tộc đứng trong ải bị đếm là "còn sống". Nâng ngưỡng chỉ là che lỗi: đúng
                    // bằng số nick đang đứng đó, hụt một nick chết là sai ngay.
                    // Đã sửa gốc — countAliveMobs() duyệt z.E, vector chỉ chứa quái. Nếu số vẫn
                    // không về 0 thì đó là DẤU HIỆU CÒN LỖI KHÁC, phải tra chứ không phải nâng.
                    int clearAt = getSettingInt("agt_clear_threshold", 0);
                    boolean isLastGate = (g2 <= 0) || (nowMap == g2);

                    // ⚠️ SỐ 0 LÚC VỪA VÀO KHÔNG PHẢI LÀ "SẠCH QUÁI".
                    //
                    // Người dùng cho biết: vào ải phải CHỜ ~4 PHÚT server mới sinh quái. Lượt
                    // chạy 21:05 ngày 29/07 lộ đúng lỗi này — vào map 46 lúc 21:05:36, ba giây
                    // sau tool đã báo "SACH QUAI" rồi chờ 30s và đi qua cổng 2, trong khi trận
                    // đánh còn chưa bắt đầu. Game trả lời "Cửa ải chưa mở".
                    //
                    // Đây là LẦN THỨ HAI trong ngày cùng một hình dạng lỗi: số 0 mang HAI NGHĨA
                    // — "chưa bắt đầu" và "đã xong" (lần trước là pha chờ boss của Sơn cáp).
                    // Cách chữa cũng như lần đó: bắt buộc phải THẤY QUÁI ÍT NHẤT MỘT LẦN rồi mới
                    // được coi số 0 là đã sạch.
                    if (alive > 0) agtSawMobs = true;
                    if (!agtSawMobs) {
                        int cho = getSettingInt("agt_spawn_wait_ms", 360000);
                        if (cho > 0 && now - agtInGateAt < cho) {
                            // Chưa từng thấy con nào và chưa hết hạn chờ sinh quái -> đứng đánh,
                            // tuyệt đối không tính chuyện qua cổng.
                            agtNextTime = now + getSettingInt("agt_poll_ms", 3000);
                            return;
                        }
                        // Hết hạn mà vẫn chưa thấy con nào: có thể cổng này thật sự không có
                        // quái. Nói ra rồi mới cho đi tiếp — im lặng thì lần sau lại tưởng đúng.
                        agtProgress("cho " + (cho / 1000) + "s ma chua thay con quai nao o map "
                                + nowMap + " -> coi nhu cong nay khong co quai, di tiep");
                        agtSawMobs = true;
                    }

                    // LƯỚI ĐỠ: quái xuất hiện lại sau khi đã sạch ⇒ đếm giờ chờ lại từ đầu.
                    // Không phải vá cho một lỗi đã biết — boss của ải ra ở cổng 2 (cổng cuối),
                    // mà nhánh dưới bỏ qua cổng cuối. Để đây vì nếu điều bất ngờ đó xảy ra thật
                    // thì hậu quả nặng: đồng hồ chạy hết trong lúc đang đánh, và giây con cuối
                    // chết là lệnh qua cổng bắn đi ngay. Rẻ, và có in ra một dòng để biết.
                    if (agtClearAt != 0 && alive > clearAt) {
                        agtClearAt = 0;
                        agtProgress("map " + nowMap + " co quai xuat hien lai (" + alive
                                + " con) -> dem gio cho lai tu dau");
                    }
                    if (!isLastGate && alive >= 0 && alive <= clearAt
                            && getSettingInt("agt_auto_go_next", 1) == 1) {
                        // Sạch quái ở cổng 1 → chờ đủ agt_gate_wait_ms rồi mới thử đi sang cổng 2.
                        //
                        // Vì sao phải CHỜ rồi mới THỬ, và thử lại theo nhịp: chưa biết chắc game
                        // TỰ ĐẨY sang cổng 2 hay bắt mình tự đi. Cách này đúng cho cả hai —
                        // game tự đẩy thì map đổi trước khi tới lượt thử; bắt tự đi thì lệnh
                        // dưới đây làm việc đó. Chỉ phát lệnh khi ĐÃ HẾT QUÁI nên không bao giờ
                        // giành tay lái với auto đánh.
                        if (agtClearAt == 0) {
                            agtClearAt = now;
                            agtProgress("map " + nowMap + " SACH QUAI -> cho "
                                    + getSettingInt("agt_gate_wait_ms", 30000) + "ms roi thu qua cong 2");
                        } else if (now - agtClearAt >= getSettingInt("agt_gate_wait_ms", 30000)
                                && now >= agtNextMoveTry) {
                            agtNextMoveTry = now + getSettingInt("agt_gate_retry_ms", 5000);
                            // GIAO CHO MÁY CHUYỂN MAP DÙNG CHUNG — cùng đường với Sơn cáp và nút
                            // 🚪: đi tới tấm biển cho đúng TẦNG NỀN, rồi băng qua mép map.
                            // Map không có biển thì máy đó tự chuyển sang "qua mù" (exit_blind).
                            // Chỉ gọi khi nó rảnh; nó tự chấm và tự tắt.
                            if (exStep == 0) {
                                agtProgress("qua cong 2 (map " + g2 + "): " + goMapExit(g2));
                            }
                        }
                    }
                    // ĐANG TRÊN ĐƯỜNG SANG CỔNG 2 thì soi dày hơn hẳn.
                    //
                    // navigateToMap đặt đích là góc (0,0) của map đích — không tránh được, vì
                    // không biết toạ độ nào là hợp lý ở cổng 2. Tới nơi rồi mà chưa kịp phát
                    // hiện thì cờ auto-nav còn bật và nhân vật cứ cắm đầu về góc map thay vì
                    // đứng đánh; đúng cái mâu thuẫn "một vô lăng". Nhánh đổi map ở trên gọi
                    // clearNavTarget() để chặn, nhưng nó chỉ chạy khi TỚI LƯỢT SOI —
                    // để nhịp 3 giây thì có thể đi lạc tới ~500px trước khi bị chặn.
                    // Chỉ soi dày trong lúc đang đi, tới nơi là trả về nhịp thường.
                    agtNextTime = now + (agtNextMoveTry > 0
                            ? getSettingInt("agt_moving_poll_ms", 500)
                            : getSettingInt("agt_poll_ms", 3000));
                    return;
                }

                // Rời khỏi cả hai cổng = xong ải.
                setAutoCombat(false);
                autoCombatRequested = false;
                pushAgt("agt_end", true, "XONG AI GIA TOC - game da day ra map " + nowMap);
                if (afkMapId > 0 && getSettingInt("agt_after_afk", 1) == 1) {
                    afkZoneChanged = false;
                    setEnabled(true);
                    setState(TaskState.AFK_FARM);
                    log("AGT: xong -> chuyen sang treo map " + afkMapId + " khu " + afkZone);
                }
                resetAgt();
                return;
            }
        } catch (Exception e) {
            pushAgt("agt_end", false, "loi: " + e);
            resetAgt();
        }
    }

    /**
     * Hạn giờ khi ĐANG Ở TRONG ẢI. Mặc định KHÔNG giới hạn, và đó là chủ ý — cùng lý do với
     * Cấm thuật và Địa cung: xong hoặc hết giờ là game tự đẩy ra làng, không có cách nào kẹt
     * lại. Đặt thêm một mốc của tool chỉ tạo ra rủi ro cắt oan một lượt đang chạy bình thường.
     */
    private long agtGateDeadline(long now) {
        int ms = getSettingInt("agt_run_timeout_ms", 0);
        return ms > 0 ? now + ms : Long.MAX_VALUE;
    }

    // ══════════════════════════════════════════════════════════════
    // ĐẠI HỘI NHẪN GIẢ — hoạt động ĐƠN, mỗi nick tự chạy trọn vòng
    // ══════════════════════════════════════════════════════════════
    //
    //   bấm nút → tới NPC → chọn mục "đại hội" → MAP ĐỔI = đã vào → bật auto đánh
    //     → soi 30s/lần cho tới khi RA KHỎI map đại hội (game đẩy về TRƯỜNG hoặc LÀNG)
    //     → ra rồi: cờ đang XANH thì đổi về TRẮNG
    //     → bàn giao cho treo map (AFK farm)
    //
    // Khuôn lấy từ ĐỊA CUNG chứ không phải từ Cấm thuật/AGT: cả hai đều là hoạt động đơn, cùng
    // hình dạng NPC → menu → map đổi → soi → ra → bàn giao. KHÔNG lập nhóm, và KHÔNG gắn bám
    // theo lead: phạm vi bám theo là ĐÓNG ở ba hoạt động nhóm (Cấm thuật · Sơn cáp · Ải gia tộc),
    // gắn thêm vào một hoạt động đơn là phá đúng cái chốt đó.
    private static final int DH_GOTO_MAP  = 1;
    private static final int DH_GOTO_NPC  = 2;
    private static final int DH_OPEN_NPC  = 3;
    private static final int DH_MENU      = 4;
    private static final int DH_VERIFY    = 5;  // chờ map đổi = bằng chứng đã vào
    private static final int DH_IN_ARENA  = 6;  // đang trong đại hội, soi 30s/lần
    private static final int DH_FLAG      = 7;  // đã ra: chỉnh cờ về trắng rồi bàn giao AFK

    private int dhStep = 0;               // 0 = tắt; chính nó là công tắc của máy này
    private long dhNextTime = 0;
    private long dhDeadline = 0;
    private int dhNpcId = -1;
    private int dhMapBefore = -1;
    private int dhVerifyWaits = 0;
    private int dhRetries = 0;            // số lần đã bấm lại vì map không đổi
    private int dhLastX = -99999;
    private int dhLastY = -99999;
    private int dhStuckTries = 0;
    private long dhNextDiag = 0;
    private int dhArenaMap = -1;          // map đại hội đang đứng
    private int dhLastMap = -1;           // để chỉ log khi ĐỔI, không in đều tay mỗi 30s
    private int dhLastFlag = -2;
    private int dhFlagTries = 0;
    private long dhFlagNext = 0;
    private String dhPicked = "";         // mục menu đã bấm, để câu báo lỗi nói được bấm cái gì
    /** Map đại hội HỌC ĐƯỢC trong lượt này — cộng thêm vào danh sách cfg, không ghi đè. */
    private final java.util.LinkedHashSet<Integer> dhLearnedMaps = new java.util.LinkedHashSet<Integer>();

    private void resetDaiHoi() {
        dhStep = 0; dhNextTime = 0; dhDeadline = 0;
        dhNpcId = -1; dhMapBefore = -1; dhVerifyWaits = 0; dhRetries = 0;
        dhLastX = -99999; dhLastY = -99999; dhStuckTries = 0; dhNextDiag = 0;
        dhArenaMap = -1; dhLastMap = -1; dhLastFlag = -2;
        dhFlagTries = 0; dhFlagNext = 0; dhPicked = "";
        dhLearnedMaps.clear();
    }

    private int daiHoiMap() {
        int m = getSettingInt("dai_hoi_map", 0);
        if (m <= 0) { loadAnchorConfig(); if (villageConfig != null) m = villageConfig[0]; }
        return m;
    }

    /** Toạ độ đứng bấm NPC. Chỉ đọc config — tra hụt là dừng, không đoán. */
    private int[] daiHoiPoint(int mapId) {
        int[] cfg = npcConfig.get("npc_dai_hoi_" + mapId);
        return (cfg == null) ? null : new int[]{cfg[1], cfg[2]};
    }

    private void pushDaiHoi(String type, boolean ok, String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushDaiHoi error: " + e.getMessage());
        }
    }

    private void dhProgress(String detail) {
        log("Dai hoi: " + detail);
        pushDaiHoi("dai_hoi_progress", false, detail);
    }

    /** Kết thúc và dọn máy. Mọi đường ra đều phải đi qua đây, không tự hạ dhStep ở nơi khác. */
    private void dhFinish(boolean ok, String detail) {
        String learned = dhLearnedMaps.isEmpty() ? "" : (" | map dai hoi da hoc: " + dhMapsCsv());
        log("Dai hoi: " + (ok ? "XONG" : "DUNG") + " - " + detail + learned);
        pushDaiHoi("dai_hoi_end", ok, detail + learned);
        resetDaiHoi();
    }

    private String dhMapsCsv() {
        StringBuilder sb = new StringBuilder();
        for (Integer m : dhLearnedMaps) { if (sb.length() > 0) sb.append(";"); sb.append(m); }
        return sb.toString();
    }

    private String dhWhereAmI() {
        try {
            int map = getCurrentMapId();
            int[] xy = daiHoiPoint(map);
            int co = readPlayerFlag();
            return "map " + map + " khu " + getCurrentZoneId()
                    + " dung tai (" + getPlayerX() + "," + getPlayerY() + ")"
                    + " | diem dung " + (xy == null ? "KHONG CO" : "(" + xy[0] + "," + xy[1] + ")")
                    + " | npcId=" + dhNpcId
                    + " | co=" + co + " (" + flagName(co) + ")";
        } catch (Exception e) {
            return "khong doc duoc vi tri: " + e;
        }
    }

    /** Map nào đó có nằm trong một dãy cfg kiểu "46;47" / "46,47" không. */
    private boolean mapInList(String csv, int mapId) {
        if (csv == null) return false;
        for (String part : csv.split("[;,]")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try { if (Integer.parseInt(s) == mapId) return true; } catch (NumberFormatException ignore) {}
        }
        return false;
    }

    public String startDaiHoi() {
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        stopCurrentActivity();
        resetDaiHoi();
        dhStep = DH_GOTO_MAP;
        dhDeadline = System.currentTimeMillis() + getSettingInt("dai_hoi_timeout_ms", 300000);
        // NÓI RA NGAY LÚC BẤM, không để người dùng tự phát hiện sau hai tiếng: thiếu dãy map
        // làng/trường thì nhánh "đã ra" bên dưới không bao giờ đúng, và máy sẽ ngồi trong
        // DH_IN_ARENA cho tới hết dai_hoi_run_timeout_ms (mặc định: mãi mãi).
        if (getSetting("dai_hoi_out_maps", "").trim().isEmpty()) {
            log("Dai hoi: CANH BAO dai_hoi_out_maps con RONG -> se khong tu nhan ra luc bi day ra."
                    + " Dien map lang + map truong vao quest_anchors.cfg.");
        }
        if (iFieldFlag == null) {
            log("Dai hoi: CANH BAO khong nap duoc a.i.f -> khong doc/chinh duoc co, phan con lai van chay.");
        }
        log("Dai hoi: bat dau (" + dhWhereAmI() + ")");
        return "da bat dau Dai hoi nhan gia";
    }

    public String stopDaiHoi() {
        if (dhStep == 0) return "khong co phien Dai hoi nao dang chay";
        setAutoCombat(false);
        autoCombatRequested = false;
        dhFinish(false, "da dung theo yeu cau");
        return "da dung Dai hoi";
    }

    private void tickDaiHoi(long now) {
        try {
            if (now > dhDeadline) {
                dhFinish(false, "het gio o buoc " + dhStep + " - " + dhWhereAmI());
                return;
            }
            if (now < dhNextTime) return;

            final int stepMs  = getSettingInt("dai_hoi_step_ms", 200);
            final int mapWait = getSettingInt("dai_hoi_map_wait_ms", 2500);

            // ─── Bước 1: về map có NPC ───
            if (dhStep == DH_GOTO_MAP) {
                int wantMap = daiHoiMap();
                int curMap = getCurrentMapId();
                if (wantMap > 0 && curMap != wantMap) {
                    int[] pt = daiHoiPoint(wantMap);
                    if (pt != null) navigateToMapXY(wantMap, pt[0], pt[1]);
                    else navigateToMap(wantMap);
                    dhProgress("dang o map " + curMap + " -> ve map " + wantMap);
                    dhNextTime = now + mapWait;
                    return;
                }
                dhStep = DH_GOTO_NPC;
                dhNextTime = now + stepMs;
                return;
            }

            // ─── Bước 2: đi tới chỗ NPC ───
            // Chống kẹt chép nguyên nhánh của AGT: nhánh đó đã gánh sẵn ba bài học của Cấm thuật
            // (toạ độ chỉ đọc config · quãng xa dùng auto-nav gốc · ba nhịp không nhúc nhích thì
            // đổi cách đi). Viết lại từ đầu là mất cả ba mà không ai nhớ vì sao.
            if (dhStep == DH_GOTO_NPC) {
                int curMap = getCurrentMapId();
                int[] xy = daiHoiPoint(curMap);
                if (xy == null) {
                    dhFinish(false, "config khong khai npc_dai_hoi_" + curMap
                            + " -> khong biet dung dau. " + dhWhereAmI());
                    return;
                }
                int[] npc = findNpc(getSetting("dai_hoi_npc", ""),
                        getSettingInt("dai_hoi_npc_id", -1));
                if (npc != null) dhNpcId = npc[0];

                int range = getSettingInt("dai_hoi_range", 60);
                if (Math.abs(getPlayerX() - xy[0]) > range || Math.abs(getPlayerY() - xy[1]) > range) {
                    int px = getPlayerX(), py = getPlayerY();
                    int eps = getSettingInt("dai_hoi_moved_px", 8);
                    if (Math.abs(px - dhLastX) > eps || Math.abs(py - dhLastY) > eps) dhStuckTries = 0;
                    else dhStuckTries++;
                    dhLastX = px; dhLastY = py;
                    int far = getSettingInt("dai_hoi_far_px", 200);
                    if (dhStuckTries >= getSettingInt("dai_hoi_stuck_tries", 3)
                            || Math.abs(px - xy[0]) > far || Math.abs(py - xy[1]) > far) {
                        navigateToMapXY(curMap, xy[0], xy[1]);
                        dhStuckTries = 0;
                    } else {
                        navigateTo(curMap, xy[0], xy[1]);
                    }
                    if (now >= dhNextDiag) {
                        dhNextDiag = now + getSettingInt("dai_hoi_diag_ms", 15000);
                        dhProgress("dang di toi cho NPC - " + dhWhereAmI());
                    }
                    dhNextTime = now + getSettingInt("dai_hoi_walk_wait_ms", 1500);
                    return;
                }
                if (dhNpcId <= 0) {
                    dumpAllNpcsOnMap();
                    dhFinish(false, "khong tra duoc id NPC '" + getSetting("dai_hoi_npc", "(chua khai)")
                            + "' -> da in danh sach NPC tren map ra log. " + dhWhereAmI());
                    return;
                }
                dhProgress("da toi cho NPC - " + dhWhereAmI());
                dhStep = DH_OPEN_NPC;
                dhNextTime = now + stepMs;
                return;
            }

            // ─── Bước 3: mở NPC ───
            if (dhStep == DH_OPEN_NPC) {
                closeAnyDialog();
                sendOpenNpc(dhNpcId);
                dhMapBefore = getCurrentMapId();
                dhStep = DH_MENU;
                dhNextTime = now + getSettingInt("dai_hoi_npc_wait_ms", 600);
                return;
            }

            // ─── Bước 4: chọn mục theo CHỮ ───
            if (dhStep == DH_MENU) {
                String[] menu = readDialogMenuItems();
                if (menu == null || menu.length == 0) {
                    dhNextTime = now + getSettingInt("dai_hoi_dialog_poll_ms", 300);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < menu.length; i++) sb.append("[").append(i).append("]").append(menu[i]).append(" ");
                dhProgress("menu NPC: " + sb);

                String parentKw = getSetting("dai_hoi_parent_keyword", "");
                String subKw    = getSetting("dai_hoi_keyword", "dai hoi");
                int[] hit = findMenuByKeyword(menu, parentKw, subKw);
                if (hit == null) {
                    // Chẩn đoán tách ba nhánh, y như AGT: nói ra PHẢI SỬA KHOÁ NÀO, không chỉ
                    // báo "không thấy" rồi để người dùng tự mò giữa hai từ khoá.
                    String vi;
                    if (parentKw.trim().isEmpty()) {
                        vi = "KHONG thay muc nao khop '" + subKw + "' o menu goc"
                                + " -> sua dai_hoi_keyword, hoac khai dai_hoi_parent_keyword neu no nam trong bang con";
                    } else {
                        boolean seenParent = false;
                        for (String m : menu) {
                            if (m != null && noAccent(m.split(",")[0]).contains(noAccent(parentKw))) { seenParent = true; break; }
                        }
                        vi = seenParent
                                ? ("thay muc cha '" + parentKw + "' nhung trong bang con khong co '"
                                        + subKw + "' -> sua dai_hoi_keyword")
                                : ("KHONG thay muc cha '" + parentKw + "' -> sua dai_hoi_parent_keyword");
                    }
                    closeAnyDialog();
                    dhFinish(false, vi + " | Menu: " + sb);
                    return;
                }

                String label = menu[hit[0]];
                dhPicked = (hit[1] < 0)
                        ? ("[" + hit[0] + "] " + label)
                        : ("[" + hit[0] + "][" + hit[1] + "] " + label);
                if (getSettingInt("dai_hoi_dry_run", 0) == 1) {
                    closeAnyDialog();
                    pushDaiHoi("dai_hoi_dry", true, "CHAY NHAP - se bam " + dhPicked
                            + " khop '" + subKw + "'. Doi dai_hoi_dry_run,0 de chay that.");
                    log("Dai hoi: CHAY NHAP - se bam " + dhPicked);
                    resetDaiHoi();
                    return;
                }

                dhMapBefore = getCurrentMapId();
                if (hit[1] < 0) sendSelectMenu(dhNpcId, hit[0]);
                else            sendSelectMenuWithSub(dhNpcId, hit[0], hit[1]);
                dhProgress("da bam " + dhPicked + " (dang o map " + dhMapBefore + ")");
                dhVerifyWaits = 0;
                dhStep = DH_VERIFY;
                dhNextTime = now + getSettingInt("dai_hoi_verify_ms", 1000);
                return;
            }

            // ─── Bước 5: MAP ĐỔI là bằng chứng duy nhất đáng tin đã vào ───
            // Nó bao trùm mọi thất bại phía trên: hết lượt hôm nay, chưa tới giờ đại hội, không
            // đủ điều kiện. Đọc chữ server báo cũng chỉ để IN RA, không dùng làm điều kiện.
            if (dhStep == DH_VERIFY) {
                int nowMap = getCurrentMapId();
                if (nowMap != dhMapBefore) {
                    dhArenaMap = nowMap;
                    dhLearnedMaps.add(nowMap);
                    dhLastMap = nowMap;
                    dhLastFlag = -2;
                    dhProgress("da vao dai hoi - map " + dhMapBefore + " -> " + nowMap);
                    if (getSettingInt("dai_hoi_combat", 1) == 1) {
                        // XOÁ ĐÍCH TRƯỚC KHI BẬT ĐÁNH. Vừa gọi navigateToMap(làng) +
                        // navigateTo(NPC) nên auto-nav gốc còn bật; để nguyên thì nhân vật lo đi
                        // về làng chứ không đánh. Đúng chỗ đã mất cả ngày để truy ở Cấm thuật.
                        clearNavTarget();
                        setAutoCombat(true);
                        autoCombatRequested = true;
                    }
                    dhDeadline = daiHoiRunDeadline(now);
                    dhStep = DH_IN_ARENA;
                    dhNextTime = now + getSettingInt("dai_hoi_poll_ms", 30000);
                    return;
                }
                if (++dhVerifyWaits > getSettingInt("dai_hoi_verify_tries", 6)) {
                    String why = readAnyDialogText();
                    closeAnyDialog();
                    // Vào lại được nên CHO thử lại — nhưng CÓ TRẦN. Không trần thì một lỗi kiểu
                    // "chưa tới giờ đại hội" biến thành vòng lặp bấm NPC cả buổi.
                    int max = getSettingInt("dai_hoi_retry_max", 1);
                    if (dhRetries < max) {
                        dhRetries++;
                        dhVerifyWaits = 0;
                        dhProgress("da bam " + dhPicked + " nhung map van la " + dhMapBefore
                                + " -> thu lai lan " + dhRetries + "/" + max
                                + ". Server bao: " + why);
                        dhStep = DH_OPEN_NPC;
                        dhNextTime = now + getSettingInt("dai_hoi_retry_wait_ms", 3000);
                        return;
                    }
                    dhFinish(false, "da bam " + dhPicked + " nhung map van la " + dhMapBefore
                            + " sau " + (max + 1) + " lan -> DUNG. Server bao: " + why);
                    return;
                }
                dhNextTime = now + getSettingInt("dai_hoi_verify_ms", 1000);
                return;
            }

            // ─── Bước 6: ĐANG TRONG ĐẠI HỘI — soi 30s/lần, ba nhánh ───
            if (dhStep == DH_IN_ARENA) {
                int nowMap = getCurrentMapId();
                int co = readPlayerFlag();

                // Chỉ in khi map HOẶC cờ đổi. Nhịp 30s × cả buổi mà in đều tay thì log ⚔️ của
                // các hoạt động khác không còn nhìn được trong lúc chạy.
                if (nowMap != dhLastMap || co != dhLastFlag) {
                    dhLastMap = nowMap; dhLastFlag = co;
                    dhProgress("map " + nowMap + " | co=" + co + " (" + flagName(co) + ")");
                }

                boolean isOut = mapInList(getSetting("dai_hoi_out_maps", ""), nowMap);
                boolean isArena = (nowMap == dhArenaMap)
                        || dhLearnedMaps.contains(nowMap)
                        || mapInList(getSetting("dai_hoi_maps", ""), nowMap);

                if (isOut) {
                    setAutoCombat(false);
                    autoCombatRequested = false;
                    dhProgress("RA KHOI DAI HOI - dang o map " + nowMap);
                    dhFlagTries = 0;
                    dhFlagNext = 0;
                    dhStep = DH_FLAG;
                    dhNextTime = now + getSettingInt("dai_hoi_step_ms", 200);
                    return;
                }

                if (!isArena) {
                    // NHÁNH THỨ BA — chỗ dễ sai nhất, và nghiêng về "CÒN TRONG hoạt động".
                    //
                    // Vì sao nghiêng về phía đó: hai chỗ bị đẩy về là BIẾT TRƯỚC (làng, trường) và
                    // nằm trong cfg. Map lạ khả năng cao là một vòng/màn khác của đại hội. Đoán
                    // ngược lại là bỏ dở hoạt động ngay giữa lúc đang đánh — mà mỗi ngày chỉ có
                    // vài lượt. Lưới đỡ cho trường hợp đoán sai là dai_hoi_run_timeout_ms.
                    dhLearnedMaps.add(nowMap);
                    dhProgress("CANH BAO: map la " + nowMap
                            + " - chua khai trong dai_hoi_maps hay dai_hoi_out_maps"
                            + " -> tam coi nhu CON TRONG dai hoi. Doc dong nay roi dien cfg cho luot sau.");
                }

                // Còn trong hoạt động: giữ cho auto đánh luôn bật. Chết/hồi sinh hay đổi trạng
                // thái map đều tắt nó, và tickAfkFarm cũng bật lại theo chu kỳ đúng kiểu này.
                if (getSettingInt("dai_hoi_combat", 1) == 1 && !isAutoCombatOn()) {
                    clearNavTarget();
                    setAutoCombat(true);
                    log("Dai hoi: auto combat bi tat -> bat lai");
                }
                dhNextTime = now + getSettingInt("dai_hoi_poll_ms", 30000);
                return;
            }

            // ─── Bước 7: chỉnh cờ về TRẮNG rồi bàn giao AFK ───
            //
            // Người dùng cho biết: bị đẩy ra thì nhân vật ở cờ XANH hoặc TRẮNG. Xanh mà để nguyên
            // là treo map cả buổi trong trạng thái người khác đánh được mình.
            //
            // KHÔNG chặn việc bàn giao AFK vì cái cờ, và đó là chủ ý: cờ sai thì bị đánh, còn
            // không train thì mất trắng cả buổi. Hết số lần thử là in cảnh báo rồi vẫn đi tiếp.
            if (dhStep == DH_FLAG) {
                if (now < dhFlagNext) return;
                int want = getSettingInt("dai_hoi_flag_want", 0);
                int co = readPlayerFlag();

                if (co == want) {
                    if (dhFlagTries > 0) dhProgress("co da ve " + flagName(co) + " (" + co + ")");
                    dhHandOffToAfk();
                    return;
                }
                if (co < 0 || iFieldFlag == null) {
                    dhProgress("CANH BAO khong doc duoc co -> bo qua buoc chinh co, van di train");
                    dhHandOffToAfk();
                    return;
                }
                int max = getSettingInt("dai_hoi_flag_tries", 3);
                if (dhFlagTries >= max) {
                    dhProgress("CANH BAO: da gui doi co " + max + " lan ma van la "
                            + flagName(co) + " (" + co + ") -> van di train");
                    dhHandOffToAfk();
                    return;
                }
                dhFlagTries++;
                sendSetFlag(want);
                dhProgress("co dang la " + flagName(co) + " (" + co + ") -> gui doi ve "
                        + flagName(want) + " (lan " + dhFlagTries + "/" + max + ")");
                dhFlagNext = now + getSettingInt("dai_hoi_flag_verify_ms", 1000);
                return;
            }
        } catch (Exception e) {
            dhFinish(false, "loi: " + e);
        }
    }

    /**
     * Bàn giao cho treo map AFK — chép đúng đường của Địa cung/AGT, kể cả hai cờ phải hạ.
     *
     * afkZoneChanged=false để AFK_FARM chịu đổi khu lại, autoCombatRequested=false để nó chịu
     * bật đánh lại cho lượt mới. Thiếu một trong hai là nhân vật tới map treo rồi đứng im.
     */
    private void dhHandOffToAfk() {
        try {
            int nowMap = getCurrentMapId();
            if (afkMapId > 0 && getSettingInt("dai_hoi_after_afk", 1) == 1) {
                afkZoneChanged = false;
                autoCombatRequested = false;
                setEnabled(true);
                setState(TaskState.AFK_FARM);
                dhFinish(true, "dang o map " + nowMap + " -> chuyen sang treo map "
                        + afkMapId + " khu " + afkZone);
            } else {
                dhFinish(true, "dang o map " + nowMap
                        + " nhung chua cau hinh map treo (afkMapId=" + afkMapId
                        + ", dai_hoi_after_afk=" + getSettingInt("dai_hoi_after_afk", 1) + ")");
            }
        } catch (Exception e) {
            dhFinish(true, "ban giao AFK loi: " + e);
        }
    }

    /**
     * Hạn giờ khi ĐANG TRONG ĐẠI HỘI. Mặc định KHÔNG giới hạn, cùng chủ ý với Địa cung và AGT:
     * xong hoặc hết giờ là game tự đẩy ra, không có cách nào kẹt lại. Đặt thêm một mốc của tool
     * chỉ tạo ra rủi ro cắt oan một lượt đang chạy bình thường.
     *
     * Nhưng ở đại hội nó CÒN là lưới đỡ cho nhánh "map lạ ⇒ coi như còn trong hoạt động": đoán
     * sai chiều đó thì máy sẽ chờ mãi, và đây là thứ duy nhất cắt được. Đặt một số dương khi
     * chưa tin dãy dai_hoi_out_maps của mình.
     */
    private long daiHoiRunDeadline(long now) {
        int ms = getSettingInt("dai_hoi_run_timeout_ms", 0);
        return ms > 0 ? now + ms : Long.MAX_VALUE;
    }

    /**
     * Đếm thực thể CÒN SỐNG trong map hiện tại. Trả -1 nếu không đọc được.
     *
     * Dùng đúng nguồn mà hasSpecialMobAlive() đã dùng: vector z.F, trường trạng thái `v` (byte)
     * trên class a.x — giá trị 4/5/6 là đã chết. Đây là dữ kiện SỐ có sẵn trong bộ nhớ client,
     * chắc chắn hơn việc bắt một dòng thông báo (phụ thuộc kênh hiển thị và cách đặt dấu).
     *
     * Lưu ý: vector này chứa cả NPC lẫn quái. Trong map ải gia tộc gần như chỉ có quái, nên số
     * này dùng làm "còn quái không" là đủ — và số đếm được IN RA LOG mỗi khi đổi, nên nếu có NPC
     * lẫn vào làm số không bao giờ về 0 thì nhìn log là thấy ngay.
     */
    /**
     * IN RA THÀNH PHẦN THẬT của vector z.F: mỗi lớp bao nhiêu đối tượng, và vài mẫu kèm trạng thái.
     *
     * Vì sao cần: đếm "quái còn sống" chỉ đúng nếu biết chắc z.F chứa những gì. findNpcByName lọc
     * theo lớp NPC, hasSpecialMobAlive đọc trường của lớp a.x — tức vector này chứa nhiều loại.
     * Nếu NHÂN VẬT NGƯỜI CHƠI cũng nằm trong đó thì 11 nick cùng gia tộc đứng cạnh sẽ bị đếm là
     * "còn sống" và số không bao giờ về 0.
     *
     * Không đoán. Cho tool in ra một lần mỗi khi vào cổng, rồi nhìn log mà lọc cho đúng.
     */
    private void dumpMapEntities(String where) {
        try {
            if (zFieldF == null) { log("AGT dump: zFieldF null"); return; }
            Object zInstance = getZ();
            if (zInstance == null) { log("AGT dump: z null"); return; }
            java.util.Vector<Object> list = (java.util.Vector<Object>) zFieldF.get(zInstance);
            if (list == null) { log("AGT dump: z.F null"); return; }

            java.util.LinkedHashMap<String, Integer> hist = new java.util.LinkedHashMap<String, Integer>();
            StringBuilder mau = new StringBuilder();
            int shown = 0;
            for (Object o : list) {
                if (o == null) continue;
                String cn = o.getClass().getName();
                Integer c = hist.get(cn);
                hist.put(cn, c == null ? 1 : c + 1);

                if (shown < getSettingInt("agt_dump_samples", 6)) {
                    shown++;
                    mau.append("\n    ").append(cn).append(" {");
                    for (Field f : o.getClass().getDeclaredFields()) {
                        String fn = f.getName();
                        // Chỉ in mấy trường nhỏ có khả năng phân biệt: trạng thái, id, tên.
                        if (!fn.equals("v") && !fn.equals("aY") && !fn.equals("aZ")
                                && !fn.equals("ar") && !fn.equals("as") && !fn.equals("j")) continue;
                        try {
                            f.setAccessible(true);
                            Object val = f.get(o);
                            mau.append(fn).append("=").append(val).append(" ");
                        } catch (Exception ignore) { }
                    }
                    mau.append("}");
                }
            }
            StringBuilder sb = new StringBuilder("z.F co " + list.size() + " doi tuong: ");
            for (java.util.Map.Entry<String, Integer> e : hist.entrySet()) {
                sb.append(e.getKey()).append("x").append(e.getValue()).append("  ");
            }
            agtProgress("THANH PHAN MAP (" + where + ") " + sb + mau);
        } catch (Exception e) {
            log("AGT dump loi: " + e);
        }
    }

    /**
     * Đếm quái CÒN SỐNG trên map.
     *
     * Duyệt z.E chứ KHÔNG phải z.F. Đây là bản sửa một lỗi thật, xác định bằng bytecode:
     * z.F/z.O chứa lẫn NGƯỜI CHƠI (lớp a.i) và quái (lớp a.fn) — cả hai đều thừa kế a.x —
     * nên bản cũ đếm "mọi a.x còn sống" là đếm cả 12 nick cùng gia tộc đứng trong ải, và
     * số đếm KHÔNG BAO GIỜ về 0. Đúng cái rủi ro đã nêu khi làm AGT mà lúc đó chưa tra ra.
     *
     * Bằng chứng z.E chỉ chứa quái: hai hàm tra quái của chính game — a.z.a(I)La/fn; và
     * a.z.b(I)La/fn; — đều duyệt z.E rồi ép kiểu thẳng sang a.fn, KHÔNG hề kiểm instanceof.
     * Ép kiểu mù như vậy chỉ an toàn nếu vector đó thuần một loại.
     */
    private int countAliveMobs() {
        try {
            if (zFieldE == null) return -1;
            Object zInstance = getZ();
            if (zInstance == null) return -1;
            Object listObj = zFieldE.get(zInstance);
            if (!(listObj instanceof java.util.Vector)) return -1;
            java.util.Vector<?> list = (java.util.Vector<?>) listObj;

            int n = 0;
            for (int i = 0; i < list.size(); i++) {
                Object o = list.elementAt(i);
                if (o == null || isEntityDead(o)) continue;
                // Bỏ vật thể không đánh được (HP tối đa = 1). Ở Làng Cỏ có 13 cái như vậy; giữ
                // lại là "map đã sạch quái" không bao giờ đúng.
                if (!isKillableMob(o)) continue;
                n++;
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BÁM THEO — member train cùng chỗ với trưởng nhóm
    // ══════════════════════════════════════════════════════════════
    // KHÔNG đi đâu cả, không lập nhóm: bấm ở ĐÚNG CHỖ đang đứng. Dùng để kiểm thử ngoài map
    // train bình thường trước, chạy được rồi mới gắn vào các hoạt động khác.
    //
    // MÂU THUẪN CỐT LÕI phải xử lý: đánh và đi giành nhau MỘT tay lái. Phát lệnh đi trong lúc
    // auto đánh đang bật thì nhân vật đi bộ chứ không đánh (chú thích ở tickAfkFarm/tickDoTask
    // đã ghi đúng điều này). Nên không thể bám liên tục — phải bám theo NGƯỠNG:
    //
    //     lệch > far_px   ⇒ TẮT đánh, chạy tới chỗ lead
    //     về trong near_px ⇒ xoá đích, BẬT đánh lại
    //
    // Hai ngưỡng khác nhau (trễ) là cố ý: một ngưỡng thì nhân vật dao động bật/tắt liên tục ngay
    // tại mép. Đây chính là cách kiểm trôi của farm anchor đang chạy được, chỉ khác điểm neo:
    // lấy vị trí SỐNG của lead thay vì một toạ độ cố định trong config.
    private static final int FL_LEAD = 1;
    private static final int FL_MEM  = 2;

    private int flStep = 0;               // 0 = tắt
    private long flNextTime = 0;
    private String flLeaderName = "";
    private int flWantMap = -1;
    private int flWantZone = -1;
    private int flWantX = -1;
    private int flWantY = -1;
    private boolean flChasing = false;    // đang đuổi theo (đã tắt đánh)
    private long flChaseStart = 0;        // lúc bắt đầu đuổi lượt này
    private long flChaseBlockUntil = 0;   // bỏ cuộc rồi thì nghỉ, không đuổi lại ngay
    private long flNextReport = 0;        // nhịp BÁO CÁO, tách khỏi nhịp QUYẾT ĐỊNH
    private long flNextNav = 0;           // nhịp phát lại lệnh đi
    private long flNextTargetChk = 0;     // nhịp kiểm "mục tiêu có lạc quá xa không"
    private int flWantTX = -1;            // toạ độ MỤC TIÊU của lead (chế độ 2), -1 = lead không có
    private int flWantTY = -1;
    private int flWantTID = -1;           // MÃ cá thể của mục tiêu lead — tra bằng mã thì khớp đúng con
    private String flMode2Last = "";      // dòng log gần nhất của chế độ 2, để khỏi lặp
    private int flLeadTgtX = -1;          // mục tiêu lead báo lần gần nhất + từ lúc nào,
    private int flLeadTgtY = -1;          // dùng để biết lead đã ở yên con đó đủ lâu chưa
    private long flLeadTgtSince = 0;
    /**
     * Phiên này có được phép RA LỆNH DI CHUYỂN không.
     *
     * Nút 🧲 kiểm thử thì có: nó phải tự kéo member tới chỗ lead. Nhưng khi bám theo chạy BÊN
     * TRONG một hoạt động (Cấm thuật, sau này là Sơn cáp) thì KHÔNG: hoạt động đó có máy trạng
     * thái riêng và cũng đang lái nhân vật. Hai bên cùng phát lệnh đi là giành tay lái — cụ thể
     * ở Cấm thuật: bám theo tắt đánh để đuổi, 3 giây sau máy Cấm thuật thấy "auto combat bị tắt"
     * lại bật lên, nhân vật đứng giữa hai lệnh mà không làm gì ra hồn.
     *
     * Tắt cờ này thì bám theo rút về đúng MỘT việc: gán mục tiêu của lead vào z.a. Việc đó không
     * đụng tay lái nên chạy chung với bất kỳ hoạt động nào cũng được.
     */
    private boolean flAllowMove = true;

    // ── CHỦ SỞ HỮU PHIÊN BÁM THEO ────────────────────────────────────────────────────────────
    // Bám mục tiêu CHỈ được dùng trong ba hoạt động theo nhóm: Cấm thuật · Sơn cáp · Ải gia tộc.
    // Không dùng ở bất kỳ đâu khác — treo map, Địa cung, Auto nhiệm vụ đều KHÔNG.
    //
    // Chỗ này cưỡng chế điều đó bằng CẤU TRÚC chứ không bằng thói quen gọi đúng chỗ:
    //   · Không khai chủ hợp lệ ⇒ startFollow TỪ CHỐI, không có phiên nào được mở.
    //   · Máy của chủ tắt ⇒ phiên tự đóng ngay nhịp sau.
    // Lý do phải chặt: bám theo có quyền ghi z.a và bật/tắt auto đánh. Một phiên sống sót sau khi
    // hoạt động đã xong sẽ ngồi ghi mục tiêu đè lên lúc nhân vật đang treo map — hỏng đúng cái
    // chạy nhiều giờ nhất trong ngày, mà lại là kiểu hỏng khó lần ra vì nó không báo gì.
    private static final int FL_OWNER_NONE = 0;
    private static final int FL_OWNER_CT   = 1;   // Cấm thuật
    private static final int FL_OWNER_AGT  = 2;   // Ải gia tộc
    private static final int FL_OWNER_SC   = 3;   // Sơn cáp
    private int flOwner = FL_OWNER_NONE;

    private static int followOwnerCode(String s) {
        if (s == null) return FL_OWNER_NONE;
        String t = s.trim().toLowerCase();
        if (t.equals("cam_thuat")) return FL_OWNER_CT;
        if (t.equals("agt"))       return FL_OWNER_AGT;
        if (t.equals("son_cap"))   return FL_OWNER_SC;
        return FL_OWNER_NONE;
    }

    private String followOwnerName() {
        switch (flOwner) {
            case FL_OWNER_CT:  return "Cam thuat";
            case FL_OWNER_AGT: return "Ai gia toc";
            case FL_OWNER_SC:  return "Son cap";
            default:           return "?";
        }
    }

    /**
     * Chủ phiên có đang ở ĐÚNG PHA ĐÁNH không — không phải "máy còn chạy".
     *
     * Đây là bản sửa một lỗi đo được 19:36 ngày 31/07: ra khỏi hầm cấm thuật rồi mà 2/4 nick vẫn
     * bật đánh, đứng đánh quái ở làng nên bị kéo khỏi điểm tập kết (đo được (500,514) và
     * (455,514) trong khi điểm tập kết là (418,514)) ⇒ không vào được lượt kế tiếp.
     *
     * Đường ra khỏi hầm CÓ gọi setAutoCombat(false). Nhưng ngay sau đó `tickFollow` chạy, thấy
     * `follow_combat=1` và đánh đang tắt nên BẬT LẠI. Điều kiện cũ `ctStep > 0` vẫn đúng suốt
     * quãng đi bộ về NPC, nên bám theo không hề biết là mình đã hết việc.
     *
     * Gỡ tuyến bên Manager không cứu được: nó là một vòng đi-về qua mạng (mod báo ra hầm →
     * Manager → follow_stop), còn `tickFollow` chạy mỗi 250-1500ms ngay tại máy. Nó luôn thắng
     * trong khoảng đó. Vì thế chốt phải nằm ở ĐÂY, dựa trên trạng thái ngay trong cùng tiến trình.
     *
     * Đúng pha = đúng lúc dồn hoả lực có nghĩa: trong hầm, trong tầng, trong ải. Mọi bước khác
     * (đi bộ, gom nhóm, chuyển map) là lúc nhân vật cần được ĐI, không phải được ĐÁNH.
     */
    private boolean followOwnerAlive() {
        switch (flOwner) {
            case FL_OWNER_CT:  return ctStep  == CT_IN_DUNGEON;
            case FL_OWNER_AGT: return agtStep == AGT_IN_GATE;
            case FL_OWNER_SC:  return scStep  == SC_IN_FLOOR;
            default:           return false;
        }
    }

    private void resetFollow() {
        flStep = 0; flNextTime = 0; flLeaderName = "";
        flWantMap = -1; flWantZone = -1; flWantX = -1; flWantY = -1;
        flChasing = false; flChaseStart = 0; flChaseBlockUntil = 0;
        flNextReport = 0; flNextNav = 0; flNextTargetChk = 0;
        flWantTX = -1; flWantTY = -1; flWantTID = -1; flMode2Last = "";
        flLeadTgtX = -1; flLeadTgtY = -1; flLeadTgtSince = 0;
        flAllowMove = true;
        flOwner = FL_OWNER_NONE;
    }

    /**
     * Log bám theo gửi lên Manager. TẮT ĐƯỢC bằng `follow_log_manager,0` trong config.
     *
     * Chặn ở ĐÂY chứ không chặn ở Manager, vì ba nguồn ồn đều đi qua đúng cửa này:
     * dòng "ung vien TAM DANH" lúc mở phiên, mọi followProgress(), và dòng "cach lead dx/dy"
     * mà mỗi member bắn 1.5s một lần. Với 11 member thì riêng dòng cuối đã là ~7 gói/giây
     * trên đúng cái socket đang phải chuyển follow_goto — tắt ở Manager thì màn hình sạch
     * nhưng đường truyền vẫn nghẽn y như cũ.
     *
     * KHÔNG chặn pushFollowLead(): đó là kênh SỐ LIỆU (vị trí + mục tiêu của lead), không
     * phải log. Cũng KHÔNG chặn log() tại máy: file log của client vẫn ghi đủ, cần mổ xẻ
     * thì mở file, khỏi phải bật lại rồi chạy lại một lượt hoạt động.
     */
    private void pushFollow(String type, boolean ok, String role, String detail) {
        if (getSettingInt("follow_log_manager", 1) == 0) return;
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"" + type + "\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":" + ok
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + getPlayerX() + ",\"y\":" + getPlayerY()
                    + ",\"extra\":\"" + escapeJson(role) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushFollow error: " + e.getMessage());
        }
    }

    /**
     * Lead báo vị trí CỦA MÌNH kèm toạ độ MỤC TIÊU đang đánh (tx,ty).
     * Member chế độ 1 dùng vị trí lead; chế độ 2 dùng tx/ty. Gửi cả hai trong một gói để
     * đổi chế độ không phải đổi giao thức, và để rơi về chế độ 1 lúc nào cũng được.
     */
    private void pushFollowLead() {
        int tx = -1, ty = -1, tid = -1;
        if (zFieldTarget != null && mobFieldAr != null && mobFieldAs != null) {
            try {
                Object t = zFieldTarget.get(getZ());
                if (t != null) {
                    tx = mobFieldAr.getShort(t);
                    ty = mobFieldAs.getShort(t);
                    // Mã cá thể: member tra bằng mã thì khớp CHÍNH XÁC con lead đang đánh,
                    // khỏi dung sai toạ độ và không thể nhắm nhầm sang người chơi.
                    if (mobFieldId != null) tid = mobFieldId.getInt(t);
                }
            } catch (Exception ignore) {}
        }
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"follow_pos\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"ok\":true"
                    + ",\"map\":" + getCurrentMapId()
                    + ",\"zone\":" + getCurrentZoneId()
                    + ",\"x\":" + getPlayerX() + ",\"y\":" + getPlayerY()
                    + ",\"tx\":" + tx + ",\"ty\":" + ty + ",\"tid\":" + tid
                    + ",\"extra\":\"lead\",\"detail\":\"\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushFollowLead error: " + e.getMessage());
        }
    }

    private void followProgress(String detail) {
        log("Bam theo: " + detail);
        pushFollow("follow_progress", false, flStep == FL_LEAD ? "lead" : "member", detail);
    }

    /**
     * @param allowMove 1 = được phép tự đi tới chỗ lead; 0 = CHỈ gán mục tiêu, không ra lệnh đi;
     *                  -1 = lấy theo `follow_move` trong config.
     * @param owner     hoạt động sở hữu phiên này: "cam_thuat" | "agt" | "son_cap".
     *                  Đây là danh sách ĐÓNG. Bám mục tiêu không được dùng ở bất kỳ đâu khác —
     *                  treo map, Địa cung, Auto nhiệm vụ đều không. Khai sai hoặc bỏ trống là
     *                  TỪ CHỐI mở phiên, chứ không phải mở rồi hy vọng không ai gọi nhầm.
     */
    public String startFollow(int role, String leaderName, int allowMove, String owner) {
        int oc = followOwnerCode(owner);
        if (oc == FL_OWNER_NONE) {
            log("Bam theo: TU CHOI - chu phien khong hop le ('" + owner + "')."
                    + " Chi Cam thuat / Ai gia toc / Son cap moi duoc dung bam muc tieu.");
            return "LOI: chu phien khong hop le ('" + owner + "')";
        }
        if (!reflectionReady) initReflection();
        if (!reflectionReady) return "LOI: reflection chua san sang";
        resetFollow();
        flOwner = oc;
        // Máy của chủ phải ĐANG chạy. Lệnh tới muộn hơn lúc hoạt động đã kết thúc là chuyện có
        // thật (gói đi đường, hoặc Manager gửi lại), và mở phiên lúc đó là ghi mục tiêu đè lên
        // một nhân vật đang làm việc khác.
        if (!followOwnerAlive()) {
            String ten = followOwnerName();
            resetFollow();
            log("Bam theo: TU CHOI - " + ten + " khong con chay");
            return "LOI: " + ten + " khong con chay";
        }
        flStep = (role == 1) ? FL_LEAD : FL_MEM;
        flAllowMove = (allowMove < 0) ? getSettingInt("follow_move", 1) == 1 : (allowMove == 1);
        flLeaderName = (leaderName == null) ? "" : leaderName.trim();
        // KHÔNG gọi stopCurrentActivity(): bấm ở đúng chỗ đang đứng, không kéo nhân vật đi đâu.
        if (getSettingInt("follow_combat", 1) == 1) {
            // Đổi mục tiêu ngay từ đầu: lúc bấm nút nhân vật có thể còn khoá vào con quái
            // của phiên trước, để nguyên là nó đi ngược lại đó ngay khi bật đánh.
            try { setAutoCombat(false); combatOnFresh(getSettingInt("follow_retarget", 1) == 1); }
            catch (Exception ignore) {}
        }
        log("Bam theo: vai " + (flStep == FL_LEAD ? "LEAD" : "MEMBER '" + flLeaderName + "'")
                + " cho " + followOwnerName()
                + (flAllowMove ? " (duoc tu di toi lead)" : " (CHI gan muc tieu, khong tu di)"));
        // In ứng viên tầm đánh của CHÍNH nick này. Mục đích: đối chiếu giữa nick cận chiến và
        // nick đánh xa. Ngưỡng follow_near_px đang là MỘT SỐ CỨNG dùng chung cho mọi nick, trong
        // khi nick đánh xa lẽ ra được dừng sớm hơn nhiều — đây là bước lấy số thật để sửa việc đó.
        pushFollow("follow_progress", true, flStep == FL_LEAD ? "lead" : "member",
                "ung vien TAM DANH = " + getAttackRangeGuess()
                + " (chua xac nhan, dang doi chieu giua cac lop)");
        return "da bat bam theo (" + (flStep == FL_LEAD ? "lead" : "member") + ")";
    }

    public String setFollowTarget(int mapId, int zoneId, int x, int y) {
        return setFollowTarget(mapId, zoneId, x, y, -1, -1);
    }

    public String setFollowTarget(int mapId, int zoneId, int x, int y, int tx, int ty) {
        return setFollowTarget(mapId, zoneId, x, y, tx, ty, -1);
    }

    public String setFollowTarget(int mapId, int zoneId, int x, int y, int tx, int ty, int tid) {
        if (flStep != FL_MEM) return "LOI: nick nay khong o vai member bam theo";
        boolean moved = (flWantX != x || flWantY != y || flWantMap != mapId);
        flWantMap = mapId; flWantZone = zoneId; flWantX = x; flWantY = y;
        flWantTX = tx; flWantTY = ty; flWantTID = tid;
        if (moved) { flNextNav = 0; flNextTime = 0; }   // lead đổi chỗ: nhắm lại NGAY, đừng đợi hết nhịp
        return "da nhan vi tri lead";
    }

    public String stopFollow() {
        if (flStep == 0) return "khong co phien bam theo nao dang chay";
        // CHỈ TẮT ĐÁNH KHI PHIÊN NÀY LÀ CHỦ CỦA CÁI CÔNG TẮC ĐÓ.
        // Ở chế độ được phép đi (nút 🧲) thì đúng: bám theo tự bật đánh nên tắt cũng phải nó.
        // Ở chế độ chỉ-gán-mục-tiêu thì KHÔNG: lúc đó auto đánh là của hoạt động chủ (Cấm thuật)
        // bật và canh giữ. Tuyến bám theo được gỡ ngay khi nick ĐẦU TIÊN ra khỏi hầm, mà nick
        // khác có thể còn chậm vài giây — tắt đánh của người đang đánh dở là gây hại, rồi 3 giây
        // sau máy Cấm thuật lại bật lên, chỉ được mỗi việc mất 3 giây.
        if (flAllowMove) {
            try { setAutoCombat(false); autoCombatRequested = false; } catch (Exception ignore) {}
        }
        resetFollow();
        log("Bam theo: da dung");
        return "da dung bam theo";
    }

    private void tickFollow(long now) {
        try {
            // ĐANG BĂNG QUA MAP THÌ BÁM THEO ĐỨNG YÊN.
            //
            // Máy chuyển map tắt đánh, xoá đích, rồi đặt đích ra ngoài mép map. Còn bám theo thì
            // mỗi nhịp lại BẬT ĐÁNH và XOÁ ĐÍCH: bên lead ở nhánh FL_LEAD, bên member ở chỗ gán
            // mục tiêu. Hai bên giành nhau đúng một vô lăng — kết quả là nhân vật đứng đánh tại
            // chỗ thay vì đi, và không bao giờ tới được mép.
            //
            // Cùng loại với chốt đã đặt bên AGT (không bật lại combat khi exStep > 0). Bám theo
            // là phần PHỤ: lúc hoạt động cần lái nhân vật thì nó nhường.
            if (exStep > 0) {
                flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                return;
            }
            if (now < flNextTime) return;

            if (flStep == FL_LEAD) {
                if (getSettingInt("follow_combat", 1) == 1 && !isAutoCombatOn()) {
                    clearNavTarget();
                    setAutoCombat(true);
                }
                pushFollowLead();
                flNextTime = now + getSettingInt("follow_report_ms", 2000);
                return;
            }

            if (flStep == FL_MEM) {
                if (flWantMap < 0) {   // chưa nhận được vị trí lead lần nào
                    flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                    return;
                }

                int curMap = getCurrentMapId();
                // Đưa nhau về cùng map/khu là việc CÓ DI CHUYỂN. Chạy bên trong một hoạt động thì
                // không được làm: hoạt động đó tự lo việc đưa cả nhóm vào, và khác map ở đây chỉ
                // là khoảnh khắc người này đã vào người kia chưa. Xen vào là kéo nhau ra ngoài.
                if (flAllowMove && curMap != flWantMap) {
                    setAutoCombat(false);
                    navigateToMapXY(flWantMap, flWantX, flWantY);   // nhắm thẳng chỗ lead, không phải (0,0)
                    followProgress("khac map lead (" + curMap + " -> " + flWantMap + ") -> di sang");
                    flChasing = true; flChaseStart = now;   // đổi map là tiến triển: đếm lại từ đầu
                    flNextTime = now + getSettingInt("follow_map_wait_ms", 2500);
                    return;
                }
                if (flAllowMove && getSettingInt("follow_match_zone", 1) == 1
                        && flWantZone >= 0 && getCurrentZoneId() != flWantZone) {
                    setAutoCombat(false);
                    sendChangeZone(flWantZone);
                    followProgress("khac khu lead -> doi sang khu " + flWantZone);
                    flChasing = true; flChaseStart = now;
                    flNextTime = now + getSettingInt("follow_zone_wait_ms", 2500);
                    return;
                }
                // Không cùng MAP với lead mà lại không được phép đi: đứng yên chờ hoạt động đưa
                // vào, tuyệt đối không gán mục tiêu — quái của map bên kia không có ở đây.
                int curZone = getCurrentZoneId();
                if (!flAllowMove && curMap != flWantMap) {
                    if (!"khacmap".equals(flMode2Last)) {
                        flMode2Last = "khacmap";
                        followProgress("chua cung map voi lead (" + curMap + " vs " + flWantMap
                                + ") -> cho hoat dong dua vao, khong tu di");
                    }
                    flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                    return;
                }

                // KHÁC KHU: mặc định CHỈ CẢNH BÁO, KHÔNG CHẶN.
                //
                // Vì sao đáng để ý: mã cá thể quái là CHỈ SỐ THEO TỪNG BẢN MAP (0,1,2,...) chứ
                // không phải mã toàn cục. Ở một map CÓ chia khu, hai khu là hai đàn quái đánh số
                // lại từ đầu ⇒ `tid=5` của lead ở khu 0 tra ra một con khác hẳn ở khu 1, mà mọi
                // phép kiểm đều "hợp lệ": đúng một con quái sống, cùng mã, cùng map.
                //
                // Vì sao KHÔNG chặn: hai chỗ đang dùng đều là map KHÔNG chia khu (ải gia tộc — do
                // người dùng xác nhận; hầm cấm thuật — vào theo nhóm nên server ném cả nhóm vào
                // một bản). Ở map không chia khu, `z.v` không có gì bảo đảm được đặt lại — nó có
                // thể còn mang số khu của map TRƯỚC, mà 12 nick thì trước đó mỗi đứa một khu ở
                // Làng Cỏ. Chặn theo một con số như vậy là dồn hoả lực chết lặng không rõ vì sao.
                //
                // Chọn phía nói ra thay vì phía chặn: vẫn đánh, nhưng in một dòng để lượt chạy
                // thật trả lời được câu "12 nick có cùng một bản map không". Thấy dòng này lặp
                // trong ải ⇒ map đó CÓ chia khu thật ⇒ bật follow_match_zone,1 và thêm bước đồng
                // bộ khu. Bật khoá đó lên thì chỗ này chuyển thành chặn.
                if (!flAllowMove && flWantZone >= 0 && curZone >= 0 && curZone != flWantZone) {
                    boolean chan = getSettingInt("follow_match_zone", 0) == 1;
                    String s = "khac khu voi lead (minh " + curMap + "/" + curZone
                             + " vs lead " + flWantMap + "/" + flWantZone + ")"
                             + (chan ? " -> CHO, khong gan muc tieu"
                                     : " -> van gan (map nay coi nhu khong chia khu)");
                    if (!s.equals(flMode2Last)) { flMode2Last = s; followProgress(s); }
                    if (chan) {
                        flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                        return;
                    }
                }

                // ── CHẾ ĐỘ 2: BÁM MỤC TIÊU ──────────────────────────────────────────────
                // Chỉ làm một việc: gán mục tiêu của lead vào z.a. KHÔNG ra lệnh đi, KHÔNG tắt
                // đánh, không ngưỡng nào cả — engine của game biết tầm đánh của chính nhân vật
                // này nên nó tự đưa tới đúng cự ly. Đứng xa hay gần lead trở nên vô nghĩa:
                // cứ đánh đúng con của lead thì vị trí tự khớp trong tầm của cả hai bên.
                if (getSettingInt("follow_mode", 1) == 2 && zFieldTarget != null) {
                    // ── LỌC RUNG (MẶC ĐỊNH TẮT) ─────────────────────────────────────────
                    // Bám theo cú đổi mục tiêu của lead là ĐÚNG và phải TỨC THÌ: quái trong
                    // các hoạt động HP rất cao, không tập trung hoả lực thì chẳng con nào chết.
                    //
                    // Chỗ này từng bị bật mặc định vì đọc nhầm log 09:35 ngày 29/07: thấy lead
                    // xoay vòng ba con mà không con nào chết nên kết luận "engine của lead tự
                    // xoay". SAI — người dùng đang BẤM TAY đổi mục tiêu để thử phản ứng của
                    // member. Không có rung nào để lọc, và lọc thì làm chậm đúng cái lệnh cố ý.
                    // Bằng chứng tool chạy đúng nằm ngay trong log đó: ngừng bấm lúc 09:36:04
                    // thì 09:36:07 member đã từ 345px về 8px.
                    //
                    // Giữ lại làm KHOÁ dự phòng: nếu sau này auto-combat của lead thật sự đổi
                    // mục tiêu liên tục thì bật lên, khỏi phải sửa code. Mục tiêu của mình chết
                    // thì luôn đổi ngay, không chờ.
                    if (flWantTX >= 0 && flWantTY >= 0) {
                        int tolMove = getSettingInt("follow_target_match_px", 60);
                        if (Math.abs(flWantTX - flLeadTgtX) + Math.abs(flWantTY - flLeadTgtY) > tolMove) {
                            flLeadTgtX = flWantTX; flLeadTgtY = flWantTY; flLeadTgtSince = now;
                        }
                        int need = getSettingInt("follow_switch_after_ms", 0);
                        boolean leadOnDaiLau = (flLeadTgtSince > 0) && (now - flLeadTgtSince >= need);
                        if (need > 0 && !leadOnDaiLau && keepCurrentTarget(flWantX, flWantY)) {
                            if (getSettingInt("follow_combat", 1) == 1 && !isAutoCombatOn()) {
                                setAutoCombat(true);
                                autoCombatRequested = true;
                            }
                            String s = "che do 2: lead vua doi, cho " + need + "ms xem co that khong"
                                     + " -> giu MT dang danh (cach minh " + targetDistance() + ")";
                            if (!s.equals(flMode2Last)) { flMode2Last = s; followProgress(s); }
                            flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                            return;
                        }
                    }

                    if (flWantTX >= 0 && flWantTY >= 0) {
                        // Tra bằng MÃ trước: khớp đúng con lead đang đánh, không dung sai,
                        // không thể nhắm nhầm sang người chơi (z.E không chứa người chơi).
                        // Toạ độ chỉ còn là đường lui khi lead chạy bản cũ chưa gửi mã.
                        Object mob = findMobById(flWantTID);
                        String cachLay = (mob != null) ? " [theo ma]" : "";
                        if (mob == null) {
                            mob = findEntityNear(flWantTX, flWantTY,
                                    getSettingInt("follow_target_match_px", 60));
                            if (mob != null) cachLay = " [theo toa do]";
                        }
                        if (mob == null) {
                            // Không thấy con của lead — với quái HP THẤP thì đây là chuyện
                            // thường: nó chết mất giữa lúc lead báo và lúc mình tra (map 105
                            // ngày 29/07, ba hàng quái chết/hồi sinh liên tục).
                            // Đừng vì thế mà rơi về bám vị trí — kéo theo cả máy đuổi và lệnh
                            // đi. Cứ đánh con GẦN CHỖ ĐÓ NHẤT: vẫn là khu của lead, vẫn không
                            // phải ra lệnh di chuyển nào.
                            int area = getSettingInt("follow_target_area_px", 250);
                            if (area > 0) {
                                mob = findEntityNear(flWantTX, flWantTY, area);
                                cachLay = " (con cua lead da chet, lay con gan do nhat)";
                            }
                        }
                        if (mob != null) {
                            // Dọn tàn dư của chế độ 1. Lúc bấm nút lead thường chưa có mục tiêu
                            // nên chế độ 2 rơi tạm về chế độ 1, và chế độ 1 kịp phát MỘT LỆNH ĐI.
                            // Không xoá thì nhân vật cứ đi tới đích cũ — mà đang đi thì không
                            // đánh được, đúng cái mâu thuẫn "một vô lăng" mà chế độ 2 sinh ra để
                            // tránh. Log 09:29 ngày 29/07: nhân vật đứng cách mục tiêu 336px
                            // suốt 11 giây rồi mới nhúc nhích.
                            if (flChasing) {
                                flChasing = false;
                                clearNavTarget();
                                followProgress("che do 2 tiep quan -> xoa lenh di con sot cua che do 1");
                            }
                            try { zFieldTarget.set(getZ(), mob); } catch (Exception ignore) {}
                            if (getSettingInt("follow_combat", 1) == 1 && !isAutoCombatOn()) {
                                setAutoCombat(true);
                                autoCombatRequested = true;
                            }
                            // Đọc LẠI z.a sau khi ghi — đây là phép thử quyết định của chế độ này:
                            // game có giữ mục tiêu mình gán khi auto-combat đang bật hay không.
                            int after = targetDistance();
                            String s = "che do 2: gan MT cua lead (" + flWantTX + "," + flWantTY
                                     + ")" + cachLay + " -> "
                                     + (after >= 0 ? ("z.a cach minh " + after) : "z.a TRONG (bi bo)");
                            if (!s.equals(flMode2Last)) {
                                flMode2Last = s;
                                followProgress(s);
                            }
                            flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                            return;
                        }
                        // Không thấy con quái đó trong danh sách của mình → rơi về bám vị trí.
                        if (!"khongthay".equals(flMode2Last)) {
                            flMode2Last = "khongthay";
                            followProgress("che do 2: khong thay quai cua lead o (" + flWantTX + ","
                                    + flWantTY + ") -> tam bam VI TRI");
                        }
                    } else if (!"leadkhongco".equals(flMode2Last)) {
                        flMode2Last = "leadkhongco";
                        followProgress("che do 2: lead dang khong co muc tieu -> tam bam VI TRI");
                    }
                }

                // KHÔNG ĐƯỢC PHÉP DI CHUYỂN ⇒ bám theo dừng lại ở đây, không rơi xuống phần bám
                // vị trí bên dưới (phần đó tắt đánh và phát lệnh đi).
                // Chưa gán được mục tiêu thì cứ để auto-đánh bật: engine tự chọn con gần nhất,
                // vẫn hơn đứng im, và nhịp sau lead có mục tiêu lại thì dồn về đúng con đó.
                if (!flAllowMove) {
                    if (getSettingInt("follow_combat", 1) == 1 && !isAutoCombatOn()) {
                        clearNavTarget();
                        setAutoCombat(true);
                        autoCombatRequested = true;
                    }
                    flNextTime = now + getSettingInt("follow_poll_ms", 1500);
                    return;
                }

                int px = getPlayerX();
                int py = getPlayerY();
                int dx = Math.abs(px - flWantX);
                int dy = Math.abs(py - flWantY);
                int far  = getSettingInt("follow_far_px", 350);
                int near = getSettingInt("follow_near_px", 120);

                // Chỉ khởi động đuổi khi đã hết khoảng nghỉ của lần bỏ cuộc trước.
                if (!flChasing && now >= flChaseBlockUntil && (dx > far || dy > far)) {
                    flChasing = true;
                    flChaseStart = now;
                    flNextNav = 0;            // đi ngay trong chính tick này
                    setAutoCombat(false);     // BẮT BUỘC: còn bật đánh thì nó đánh tại chỗ, không đi
                    clearNavTarget();
                    followProgress("cach lead dx=" + dx + " dy=" + dy + " (>" + far
                            + ") -> TAT danh, duoi theo");
                }

                if (flChasing) {
                    // Bỏ cuộc khi đuổi quá lâu mà chưa vào tầm. Lead hay đứng khác TẦNG NỀN
                    // (log 28/07: dx=0 dy≈103 suốt 40s) — đi bộ không bao giờ khớp được y,
                    // nên nếu chỉ chờ "đủ gần" thì nó đứng nhìn mãi. Thà đánh tại chỗ còn hơn.
                    int maxMs = getSettingInt("follow_chase_max_ms", 8000);
                    boolean inRange = (dx <= near && dy <= near);
                    boolean giveUp  = !inRange && maxMs > 0 && flChaseStart > 0
                                      && (now - flChaseStart) >= maxMs;

                    if (inRange || giveUp) {
                        flChasing = false;
                        // Phải ĐỔI MỤC TIÊU ở đây. Bật/tắt đánh không đụng tới z.a, nên nếu chỉ
                        // bật lại thì nó vẫn khoá vào con quái xa tít lúc trước rồi tự đi ngược
                        // về đó — chạy tới lead xong lại chạy đi, lặp tới khi con quái đó chết.
                        // Chọn mục tiêu BẮT BUỘC làm lúc combat còn TẮT (a.z.a(Z)Z tự từ chối
                        // khi fE.bo == true), tức đúng ngay đây trước setAutoCombat(true).
                        String note = "";
                        if (getSettingInt("follow_combat", 1) == 1) {
                            note = combatOnFresh(getSettingInt("follow_retarget", 1) == 1);
                        } else {
                            clearNavTarget();   // bỏ đích, không thì đi tiếp thay vì đứng đánh
                        }
                        if (giveUp) {
                            int rest = getSettingInt("follow_chase_cooldown_ms", 15000);
                            flChaseBlockUntil = now + rest;
                            followProgress("duoi " + ((now - flChaseStart) / 1000) + "s van chua toi (dx="
                                    + dx + " dy=" + dy + ") -> BAT danh tai cho, nghi duoi "
                                    + (rest / 1000) + "s" + note);
                        } else {
                            followProgress("da toi cho lead (dx=" + dx + " dy=" + dy
                                    + ") -> BAT danh lai" + note);
                        }
                    } else if (now >= flNextNav) {
                        // Soi nhanh nhưng KHÔNG phát lệnh đi liên tục: đích không đổi thì
                        // phát lại chỉ tốn gói mạng chứ không đi nhanh hơn.
                        navigateTo(curMap, flWantX, flWantY);
                        flNextNav = now + getSettingInt("follow_nav_repeat_ms", 1000);
                    }
                } else if (getSettingInt("follow_combat", 1) == 1 && !isAutoCombatOn()) {
                    // Đánh bị tắt bởi thứ khác (chết/hồi sinh, máy khác...) — bật lại kèm
                    // đổi mục tiêu, vì mục tiêu cũ lúc này gần như chắc chắn đã lạc.
                    combatOnFresh(getSettingInt("follow_retarget", 1) == 1);
                } else if (getSettingInt("follow_retarget", 1) == 1 && now >= flNextTargetChk) {
                    // CHẶN SỚM: đổi mục tiêu ngay khi nó lạc, đừng đợi nhân vật chạy theo nó
                    // rồi mới kéo về. Lượt chạy 08:25 ngày 29/07 đo được mục tiêu lạc tới
                    // 495px trước khi ngưỡng đuổi (250px vị trí NHÂN VẬT) kịp phản ứng —
                    // tức mỗi lần như vậy là một chuyến chạy đi rồi chạy về, mất toi 2-3 giây.
                    int tmax = getSettingInt("follow_target_max_px", 250);
                    int td = targetDistance();
                    if (tmax > 0 && td > tmax) {
                        setAutoCombat(false);        // BẮT BUỘC: game từ chối đổi khi đang bật
                        String note = combatOnFresh(true);
                        followProgress("muc tieu cach " + td + " (>" + tmax + ") -> doi ngay" + note);
                        // Chặn thử lại dồn dập: quanh đây có thể chỉ toàn quái xa, đổi mấy
                        // cũng ra con xa, mà mỗi lần đổi là một nhịp tắt/bật đánh.
                        flNextTargetChk = now + getSettingInt("follow_target_recheck_ms", 3000);
                    }
                }

                String tt = flChasing ? " [dang duoi]"
                          : (now < flChaseBlockUntil ? " [danh tai cho, nghi duoi]" : " [dang danh]");
                if (now >= flNextReport) {
                    pushFollow("follow_pos", !flChasing, "member",
                            "cach lead dx=" + dx + " dy=" + dy + tt);
                    // Nhịp GHI LOG riêng, KHÔNG dùng chung report_ms. Report_ms là nhịp lead
                    // báo vị trí — hạ nó xuống thì member bám sát hơn; còn log thì hạ theo chỉ
                    // làm ngập màn hình. Hai thứ khác mục đích, phải khác khoá.
                    flNextReport = now + getSettingInt("follow_log_ms", 1500);
                }

                // ĐANG ĐUỔI thì soi dày hơn hẳn. Trước đây dùng chung một nhịp 1500ms nên
                // tới nơi rồi vẫn phải đứng chờ hết nhịp mới nhận ra "đã vào tầm" — chính là
                // độ trễ cảm nhận được. Soi chỉ đọc toạ độ tại chỗ, không tốn gói mạng.
                flNextTime = now + (flChasing ? getSettingInt("follow_chase_poll_ms", 250)
                                              : getSettingInt("follow_poll_ms", 1500));
                return;
            }
        } catch (Exception e) {
            log("Bam theo loi: " + e);
            resetFollow();
        }
    }

    public void tick() {

        if (reflectionReady) checkNpcClickProbe();

        // Nhận chìa Địa cung — chạy độc lập, không cần bật Auto NV
        if (dcStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickDiaCung(System.currentTimeMillis());
        }

        // Dọn popup thông báo sau khi vào game — chạy độc lập, không cần bật hoạt động nào.
        // KHÔNG chặn theo reflectionReady ở đây: cờ đó chỉ bật khi có ai đó gọi initReflection(),
        // mà các hook khác lại chỉ gọi khi hoạt động của chúng đang chạy. Chỉ đăng nhập rồi để đó
        // thì cờ không bao giờ bật và hàm này không bao giờ chạy — đúng lỗi đã gặp.
        if (!loginPopupDone) tickClosePopupAfterLogin(System.currentTimeMillis());

        // Gom nhóm Cấm thuật — cũng chạy độc lập với cờ Auto NV
        if (ctStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickCamThuat(System.currentTimeMillis());
        }

        // Gom nhóm Sơn cáp — máy riêng, cũng chạy độc lập với cờ Auto NV
        if (scStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickSonCap(System.currentTimeMillis());
        }

        // Ải gia tộc — máy riêng, không lập nhóm, cũng chạy độc lập với cờ Auto NV
        if (agtStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickAgt(System.currentTimeMillis());
        }

        // Đổi đồ lấy tinh thạch — máy riêng, chạy độc lập với cờ Auto NV
        if (tsStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickTinhThach(System.currentTimeMillis());
        }

        // Gom đồ về lead — máy riêng, chạy độc lập với cờ Auto NV
        if (gomStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickGom(System.currentTimeMillis());
        }

        // Đại hội nhẫn giả — máy riêng, hoạt động ĐƠN, cũng chạy độc lập với cờ Auto NV.
        // Phải nằm TRƯỚC cổng `enabled` bên dưới: cổng đó chỉ quản Auto nhiệm vụ, còn hoạt động
        // thì bấm nút là chạy dù Auto NV đang tắt.
        if (dhStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickDaiHoi(System.currentTimeMillis());
        }

        // Auto Quiz NPC — máy riêng, hoạt động ĐƠN, chạy độc lập với cờ Auto NV.
        if (quizStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickQuiz(System.currentTimeMillis());
        }

        // Bám theo lead — KHÔNG phải máy độc lập: nó là phần phụ của Cấm thuật / Ải gia tộc /
        // Sơn cáp và không được sống lâu hơn chủ của mình.
        //
        // Chốt chặn ở đây là chốt CUỐI CÙNG, cố ý đặt ngay tại cổng vào của vòng lặp: dù đường
        // kết thúc nào bị bỏ sót không gọi stopFollow (rớt mạng, hết giờ, người dùng bấm tắt,
        // gói follow_stop không tới nơi), nhịp sau máy chủ đã về 0 là phiên đóng ngay.
        // Không có chốt này thì một phiên sót lại sẽ ngồi ghi z.a đè lên lúc nhân vật đang treo
        // map — hỏng đúng thứ chạy nhiều giờ nhất trong ngày, và hỏng lặng lẽ.
        if (flStep > 0) {
            if (!followOwnerAlive()) {
                log("Bam theo: " + followOwnerName() + " da dung -> tu dong dong phien bam theo");
                resetFollow();
            } else {
                if (!reflectionReady) initReflection();
                if (reflectionReady) tickFollow(System.currentTimeMillis());
            }
        }

        // Bị người khác yểm bùa uế thổ — chạy KHÔNG cần hoạt động nào và KHÔNG chặn theo
        // reflectionReady (nó tự lo bên trong). Bị yểm là lúc nick đang chết nằm im, tức đúng
        // lúc mọi máy trạng thái khác đều không có gì để nói. Xem tickBuaUeTho.
        tickBuaUeTho(System.currentTimeMillis());

        // Thử đi qua map — máy riêng, tự tắt khi có kết quả
        if (exStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickGoExit(System.currentTimeMillis());
        }

        // Soi map tự động — thuần ĐỌC bộ nhớ, không gửi gói nào lên server nên chạy song song
        // với bất kỳ hoạt động nào cũng vô hại.
        if (scanAutoOn) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickScanAuto(System.currentTimeMillis());
        }

        // Soi menu NPC — công cụ chẩn đoán, chỉ chạy khi bấm nút, tự tắt sau khi xong
        if (pbStep > 0) {
            if (!reflectionReady) initReflection();
            if (reflectionReady) tickProbe(System.currentTimeMillis());
        }

        if (!enabled) return;

        // Init reflection nếu chưa
        if (!reflectionReady) {
            initReflection();
            if (!reflectionReady) return;
        }

        long now = System.currentTimeMillis();

        try {
            switch (state) {
                case IDLE:            tickIdle(now); break;
                case MOVE_TO_NPC:     tickMoveToNpc(now); break;
                case INTERACT_NPC:    tickInteractNpc(now); break;
                case WAIT_TASK_DATA:  tickWaitTaskData(now); break;
                case MOVE_TO_MAP:     tickMoveToMap(now); break;
                case DO_TASK:         tickDoTask(now); break;
                case MOVE_TO_TURN_IN: tickMoveToTurnIn(now); break;
                case TURN_IN:         tickTurnIn(now); break;
                case COOLDOWN:        tickCooldown(now); break;
                case AFK_FARM:        tickAfkFarm(now); break;
            }
        } catch (Exception e) {
            log("ERROR in tick: " + e.getMessage());
            setState(TaskState.COOLDOWN);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private void tickIdle(long now) throws Exception {
        if (now - lastActionTime < 2000) return;
        lastActionTime = now;

        // === Kiểm tra Tuần hoàn ===
        if (tuanHoanEnabled) {
            Object tuanHoanTask = getTuanHoanTask();

            if (tuanHoanTask != null && isTaskCompleted(tuanHoanTask)) {
                log("Tuan hoan da hoan thanh! Di tra NV...");
                currentTaskType = TaskType.TUAN_HOAN;
                setState(TaskState.MOVE_TO_TURN_IN);
                return;
            }

            if (tuanHoanTask != null && !isTaskCompleted(tuanHoanTask)) {
                int mapK = getTaskField(tuanHoanTask, dqFieldK);
                int mapAu = getTaskField(tuanHoanTask, dqFieldAu);
                int mobId = getTaskField(tuanHoanTask, dqFieldCi);
                int progress = getTaskField(tuanHoanTask, dqFieldAs);
                int required = getTaskField(tuanHoanTask, dqFieldAx);
                log("Co NV tuan hoan. MapK=" + mapK + " MapAu=" + mapAu
                        + " Mob=" + mobId + " " + progress + "/" + required);
                currentTaskType = TaskType.TUAN_HOAN;
                setState(TaskState.MOVE_TO_MAP);
                return;
            }

            int thRemaining = getTuanHoanRemaining();
            if (tuanHoanTask == null && thRemaining > 0) {
                log("Con " + thRemaining + " luot tuan hoan. Di nhan NV...");
                currentTaskType = TaskType.TUAN_HOAN;
                setState(TaskState.MOVE_TO_NPC);
                return;
            }
        }

        // === Kiểm tra Linh thú ===
        if (linhThuEnabled) {
            Object linhThuTask = getLinhThuTask();

            if (linhThuTask != null && isTaskCompleted(linhThuTask)) {
                log("Linh thu da hoan thanh! Di tra NV...");
                currentTaskType = TaskType.LINH_THU;
                setState(TaskState.MOVE_TO_TURN_IN);
                return;
            }

            if (linhThuTask != null && !isTaskCompleted(linhThuTask)) {
                int mobId = getTaskField(linhThuTask, dqFieldCi);
                int progress = getTaskField(linhThuTask, dqFieldAs);
                int required = getTaskField(linhThuTask, dqFieldAx);
                log("Co NV linh thu. Mob=" + mobId + " " + progress + "/" + required);
                currentTaskType = TaskType.LINH_THU;
                setState(TaskState.MOVE_TO_MAP);
                return;
            }

            int ltRemaining = getLinhThuRemaining();
            if (linhThuTask == null && ltRemaining > 0) {
                log("Con " + ltRemaining + " luot linh thu. Di nhan NV...");
                currentTaskType = TaskType.LINH_THU;
                setState(TaskState.MOVE_TO_NPC);
                return;
            }
        }

        // Hết NV → AFK farm nếu có config
        // Nhưng chờ ít nhất 5s sau khi enable để game data load xong
        long timeSinceEnabled = System.currentTimeMillis() - enabledTime;
        if (timeSinceEnabled < 5000) {
            logOnce("Cho game data load... (" + (5000 - timeSinceEnabled) / 1000 + "s)");
            return;
        }
        // BÁO XONG NHIỆM VỤ NGÀY — mắt xích để Manager biết nick nào đã xong.
        //
        // Chỗ quyết định vốn đã có sẵn: tới được đây nghĩa là cả tuần hoàn lẫn linh thú đều hết
        // lượt. Thiếu mỗi việc nói cho Manager biết.
        //
        // BẮN ĐÚNG MỘT LẦN cho mỗi lượt bật Auto NV. Nhánh này nằm trong vòng tick nên không chốt
        // là nó bắn mỗi nhịp, ngập log và làm Manager đếm nhầm số nick đã xong.
        // Cờ được xoá ở setEnabled(true) nên bật lại Auto NV là báo lại được.
        if (!autoNvDaBaoXong) {
            autoNvDaBaoXong = true;
            pushAutoNv(true, "het nhiem vu ngay (tuan hoan=" + getTuanHoanRemaining()
                    + " linh thu=" + getLinhThuRemaining() + ")"
                    + (afkMapId > 0 ? " -> treo map " + afkMapId : " - chua set map treo"));
        }
        if (afkMapId > 0) {
            log("Het NV! Di chuyen den map " + afkMapId + " de AFK farm...");
            setState(TaskState.AFK_FARM);
        } else {
            logOnce("Khong con NV nao (TH=" + getTuanHoanRemaining() + " LT=" + getLinhThuRemaining() + "). Chua set AFK map.");
        }
    }

    private void tickMoveToNpc(long now) throws Exception {
        if (now - lastActionTime < 2000) return; // Throttle 2 giây
        lastActionTime = now;

        int npcId = getCurrentNpcId(); // Đây là template ID (102 hoặc 8)
        int npcMapId = getNpcMapId(npcId);
        short currentMap = getCurrentMapId();
        int playerX = getPlayerX();
        int playerY = getPlayerY();

        log("DEBUG MOVE: currentMap=" + currentMap + " targetMap=" + npcMapId
                + " playerPos=(" + playerX + "," + playerY + ")");

        if (currentMap != npcMapId) {
            setAutoCombat(false);
            log("Di chuyen den map " + npcMapId + " (dang o map " + currentMap + ") de gap NPC " + npcId);
            navigateToMap(npcMapId);
            return;
        }

        // Đã đúng map, tìm NPC động
        int[] npcInfo = findNpcOnMap(npcId);
        int[] npcPos;
        if (npcInfo != null) {
            currentNpcRealId = npcInfo[0];
            npcPos = new int[]{npcInfo[1], npcInfo[2]};
            log("Tim thay NPC " + npcId + " tren map! ID thuc=" + currentNpcRealId + ", Pos=(" + npcPos[0] + "," + npcPos[1] + ")");
        } else {
            currentNpcRealId = -1;
            npcPos = getNpcPosition(npcId); // Fallback
            // Dump tất cả NPC trên map 1 lần để debug
            if (!lastLogMessage.contains("NPC dump")) {
                dumpAllNpcsOnMap();
            }
            log("Khong thay NPC " + npcId + " tren map, dung toado fallback: (" + npcPos[0] + "," + npcPos[1] + ")");
        }

        double distance = Math.sqrt(Math.pow(playerX - npcPos[0], 2) + Math.pow(playerY - npcPos[1], 2));

        if (distance <= NPC_INTERACT_RANGE) {
            if (currentNpcRealId == -1) {
                log("Da den toado fallback nhung khong thay NPC thuc te! Cho load...");
                return;
            }
            log("Da den gan NPC " + npcId + " (dist=" + (int) distance + ")");
            setState(TaskState.INTERACT_NPC);
        } else {
            setAutoCombat(false);
            log("Di chuyen den NPC " + npcId + " tai (" + npcPos[0] + "," + npcPos[1]
                    + ") dist=" + (int) distance);
            navigateTo(npcMapId, npcPos[0], npcPos[1]);
        }
    }

    /**
     * Event-driven NPC interaction:
     * Step 0: Gửi CMD 54 (open NPC) → chờ dialog NPC xuất hiện
     * Step 1: Phát hiện NPC_DIALOG → gửi CMD 53 (chọn menu) → chờ dialog thay đổi
     * Step 2: Phát hiện OPEN_MENU (-2) → gửi CMD 5 (xác nhận) → chờ task data hoặc dialog tiếp
     * Step 3+: Nếu có thêm dialog OPEN_MENU → tiếp tục gửi CMD 5
     */
    private void tickInteractNpc(long now) throws Exception {
        // Debug log mỗi giây
        if (now - lastInteractDebugTime > 1000) {
            lastInteractDebugTime = now;
            String dlgInfo = describeDialog();
            Object taskObj = getCurrentTaskObject();
            log("DEBUG INTERACT: step=" + interactStep
                    + " timeDiff=" + (now - lastInteractStepTime)
                    + " stateDiff=" + (now - stateEnteredTime)
                    + " realId=" + currentNpcRealId
                    + " dialog=" + dlgInfo
                    + " taskObj=" + (taskObj != null ? "EXISTS" : "NULL"));
        }

        // Cooldown ban đầu
        if (now - stateEnteredTime < COOLDOWN_MS) return;

        // Validate NPC ID
        if (currentNpcRealId == -1) {
            log("currentNpcRealId is invalid (-1)! Chuyen ve COOLDOWN de check lai NPC...");
            setState(TaskState.COOLDOWN);
            return;
        }

        // Timeout tổng cho interact (15 giây)
        if (now - stateEnteredTime > 15000) {
            log("TIMEOUT interact NPC sau 15s! Dong dialog va retry...");
            closeCurrentDialog();
            setState(TaskState.COOLDOWN);
            return;
        }

        int[] dlgInfo = detectDialog();
        int dialogType = (dlgInfo != null) ? dlgInfo[0] : -999;

        switch (interactStep) {
            case 0: // ── Gửi CMD 54 (mở NPC) ──
                log("[Step 0] Gui packet mo NPC thuc ID=" + currentNpcRealId
                        + " (template " + getCurrentNpcId() + ") (CMD 54)...");
                sendOpenNpc(currentNpcRealId);
                interactStep = 1;
                lastInteractStepTime = now;
                break;

            case 1: // ── Chờ NPC_DIALOG xuất hiện, đọc menu items, tìm đúng index, gửi CMD 53 ──
                // Cần đợi ít nhất 500ms cho server phản hồi
                if (now - lastInteractStepTime < 500) break;

                if (dialogType >= 0) {
                    // NPC dialog đã xuất hiện! Đọc menu items
                    String[] menuItems = readDialogMenuItems();
                    if (menuItems != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int mi = 0; mi < menuItems.length; mi++) {
                            sb.append("  [" + mi + "] " + menuItems[mi] + "\n");
                        }
                        log("[Step 1] Menu items (" + menuItems.length + " items):\n" + sb.toString());

                        // Tìm index chính xác theo task type
                        String keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tu\u1ea7n ho\u00e0n" : "linh th\u00fa";
                        int foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        // Fallback keywords
                        if (foundIndex == -1) {
                            keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tuan hoan" : "thu ph\u1ee5c";
                            foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        }
                        if (foundIndex == -1) {
                            keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tuan hoan" : "m\u00e3nh th\u00fa";
                            foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        }
                        // Nếu vẫn không tìm thấy, dùng configured index
                        int menuIndex;
                        if (foundIndex >= 0) {
                            menuIndex = foundIndex;
                            log("[Step 1] Tim thay keyword '" + keyword + "' tai index=" + menuIndex);
                        } else {
                            menuIndex = getCurrentMenuIndex();
                            log("[Step 1] Khong tim thay keyword! Dung configured index=" + menuIndex);
                        }

                        // Kiểm tra menu item có sub-menu (dấu ',') không
                        int subCount = getSubMenuCount(menuItems, menuIndex);

                        // Đóng dialog trước khi gửi CMD 53 (giống client thật)
                        closeCurrentDialog();

                        if (subCount > 0) {
                            // Menu có sub-options → gửi 3 bytes: entityId, parentIndex, subIndex=0 (accept)
                            log("[Step 1] Menu co " + subCount + " sub-options! Gui CMD 53 voi parentIdx=" + menuIndex + " subIdx=0");
                            sendSelectMenuWithSub(currentNpcRealId, menuIndex, 0);
                        } else {
                            // Menu không có sub-options → gửi 2 bytes
                            log("[Step 1] Gui CMD 53 menu index=" + menuIndex + " (no sub-menu)");
                            sendSelectMenu(currentNpcRealId, menuIndex);
                        }
                    } else {
                        // Không đọc được menu items, dùng index cấu hình
                        int menuIndex = getCurrentMenuIndex();
                        log("[Step 1] NPC_DIALOG detected nhung khong doc duoc menu! Gui CMD 53 index=" + menuIndex);
                        closeCurrentDialog();
                        sendSelectMenu(currentNpcRealId, menuIndex);
                    }
                    interactStep = 2;
                    lastInteractStepTime = now;
                } else if (now - lastInteractStepTime > 3000) {
                    // Timeout chờ dialog, gửi lại CMD 54
                    log("[Step 1] Timeout cho NPC dialog! Dialog=" + describeDialog() + ". Retry CMD 54...");
                    sendOpenNpc(currentNpcRealId);
                    lastInteractStepTime = now;
                }
                break;

            case 2: // ── Chờ OPEN_MENU (-2) hoặc task data, rồi gửi CMD 5 ──
                if (now - lastInteractStepTime < 500) break;

                // Kiểm tra task data đã đến chưa (có thể server gửi thẳng không qua dialog)
                Object taskCheck = getCurrentTaskObject();
                if (taskCheck != null) {
                    log("[Step 2] Task data da den ngay! Chuyen sang WAIT_TASK_DATA");
                    closeCurrentDialog();
                    setState(TaskState.WAIT_TASK_DATA);
                    break;
                }

                if (dialogType == -2) {
                    // OpenMenu dialog xuất hiện → gửi CMD 5 xác nhận
                    log("[Step 2] OPEN_MENU (-2) detected! Gui CMD 5 index=0 (xac nhan)...");
                    sendSelectSubMenu(0);
                    interactStep = 3;
                    lastInteractStepTime = now;
                } else if (dialogType >= 0) {
                    // Vẫn còn NPC dialog cũ → có thể CMD 53 chưa xử lý xong, chờ thêm
                    if (now - lastInteractStepTime > 3000) {
                        log("[Step 2] Van con NPC_DIALOG cu (entityId=" + dialogType
                                + ")! Gui lai CMD 53...");
                        sendSelectMenu(currentNpcRealId, getCurrentMenuIndex());
                        lastInteractStepTime = now;
                    }
                } else if (now - lastInteractStepTime > 5000) {
                    log("[Step 2] Timeout cho OPEN_MENU! Dialog=" + describeDialog() + ". Retry tu dau...");
                    interactStep = 0;
                    lastInteractStepTime = now;
                }
                break;

            case 3: // ── Chờ kết quả sau CMD 5: task data hoặc dialog tiếp ──
                if (now - lastInteractStepTime < 500) break;

                // Kiểm tra task data
                Object taskAfterCmd5 = getCurrentTaskObject();
                if (taskAfterCmd5 != null) {
                    log("[Step 3] Task data da nhan sau CMD 5! Thanh cong!");
                    closeCurrentDialog();
                    setState(TaskState.WAIT_TASK_DATA);
                    break;
                }

                if (dialogType == -2) {
                    // Có thêm 1 dialog OPEN_MENU nữa → gửi CMD 5 lần nữa
                    log("[Step 3] Them 1 OPEN_MENU (-2)! Gui CMD 5 index=0 lan nua...");
                    sendSelectSubMenu(0);
                    interactStep = 4; // tăng step
                    lastInteractStepTime = now;
                } else if (dialogType == -999) {
                    // Dialog đã đóng nhưng chưa có task → chờ thêm
                    if (now - lastInteractStepTime > 3000) {
                        log("[Step 3] Dialog dong nhung chua co task data. Chuyen WAIT_TASK_DATA...");
                        setState(TaskState.WAIT_TASK_DATA);
                    }
                } else if (now - lastInteractStepTime > 5000) {
                    log("[Step 3] Timeout! Dialog=" + describeDialog() + ". Retry...");
                    closeCurrentDialog();
                    setState(TaskState.COOLDOWN);
                }
                break;

            default: // ── Step 4+: Xử lý thêm dialog nếu cần ──
                if (now - lastInteractStepTime < 500) break;

                Object taskFinal = getCurrentTaskObject();
                if (taskFinal != null) {
                    log("[Step " + interactStep + "] Task data nhan duoc! Thanh cong!");
                    closeCurrentDialog();
                    setState(TaskState.WAIT_TASK_DATA);
                    break;
                }

                if (dialogType == -2) {
                    log("[Step " + interactStep + "] Con dialog OPEN_MENU! Gui CMD 5 index=0...");
                    sendSelectSubMenu(0);
                    interactStep++;
                    lastInteractStepTime = now;
                } else if (now - lastInteractStepTime > 3000) {
                    log("[Step " + interactStep + "] Khong co dialog/task. Chuyen WAIT_TASK_DATA...");
                    closeCurrentDialog();
                    setState(TaskState.WAIT_TASK_DATA);
                }
                break;
        }
    }

    private void tickWaitTaskData(long now) throws Exception {
        Object task = getCurrentTaskObject();

        if (task != null) {
            // Dump ALL fields for debugging
            String taskS = getTaskString(task, dqFieldS);
            String taskX = getTaskString(task, dqFieldX);
            String taskC = getTaskString(task, dqFieldC);
            int aY = getTaskField(task, dqFieldAY);
            int ar = getTaskField(task, dqFieldAr);
            int as = getTaskField(task, dqFieldAs);
            int au = getTaskField(task, dqFieldAu);
            int av = getTaskField(task, dqFieldAv);
            int ci = getTaskField(task, dqFieldCi);
            int k  = getTaskField(task, dqFieldK);
            int a  = getTaskField(task, dqFieldA);
            int aw = getTaskField(task, dqFieldAw);
            int ax = getTaskField(task, dqFieldAx);
            boolean completed = isTaskCompleted(task);

            log("=== DQ TASK DUMP ===");
            log("  aY=" + aY + " ar=" + ar + " as=" + as + " au=" + au);
            log("  av=" + av + " ci=" + ci + " k=" + k + " a=" + a);
            log("  aw=" + aw + " ax=" + ax + " completed=" + completed);
            log("  S=[" + taskS + "] X=[" + taskX + "] c=[" + taskC + "]");
            log("=== END DQ DUMP ===");

            // Xác định loại NV: nếu ci > 0 (mob ID) và ax > 1 → kill task
            boolean isKillTask = (ci > 0 && ax > 1);
            boolean isTalkTask = (ci <= 0 || ax <= 1) && av > 0;

            log("Da nhan NV " + getTaskTypeName() + ": " + taskS
                    + " | isKill=" + isKillTask + " isTalk=" + isTalkTask
                    + " | ci=" + ci + " av=" + av + " ax=" + ax + " au=" + au);

            if (completed) {
                setState(TaskState.MOVE_TO_TURN_IN);
            } else {
                setState(TaskState.MOVE_TO_MAP);
            }
            return;
        }

        if (now - stateEnteredTime > WAIT_TASK_DATA_TIMEOUT) {
            log("Timeout cho du lieu NV! Retry...");
            setState(TaskState.COOLDOWN);
        }
    }

    private void tickMoveToMap(long now) throws Exception {
        Object task = getCurrentTaskObject();
        if (task == null) {
            log("Task is null, quay lai IDLE");
            setState(TaskState.IDLE);
            return;
        }

        if (isTaskCompleted(task)) {
            log("NV da hoan thanh trong luc di chuyen!");
            setState(TaskState.MOVE_TO_TURN_IN);
            return;
        }

        // Thử nhiều field để tìm target map: `k` trước, fallback `au`
        int mapK = getTaskField(task, dqFieldK);
        int mapAu = getTaskField(task, dqFieldAu);
        int targetMap = (mapK > 0) ? mapK : mapAu;
        short currentMap = getCurrentMapId();

        if (targetMap > 0 && currentMap != targetMap) {
            if (now - lastMoveCheckTime > MOVE_CHECK_INTERVAL) {
                lastMoveCheckTime = now;
                setAutoCombat(false);
                autoCombatRequested = false;
                // Dùng cross-map navigation giống game thật (bi_0 target)
                navigateToQuest(task);
                logOnce("Di chuyen den map " + targetMap + " de lam NV (cross-map via bi_0)");
            }
        } else {
            log("Da o dung map " + currentMap + " (targetMap=" + targetMap + "). Bat dau lam NV!");
            if (currentTaskType == TaskType.TUAN_HOAN) {
                clearNavTarget();  // Tuần hoàn: xóa bi_0, chỉ cần đúng map
            } else {
                // Linh thú: vẫn navigate đến boss spawn dù đã đúng map
                navigateToQuest(task);
                log("Linh thu: navigate den boss spawn via bi_0...");
            }
            setState(TaskState.DO_TASK);
        }
    }

    private void tickDoTask(long now) throws Exception {
        Object task = getCurrentTaskObject();
        if (task == null) {
            log("Task null trong DO_TASK, quay lai IDLE");
            setAutoCombat(false);
            autoCombatRequested = false;
            restorePriorityTargeting();
            setState(TaskState.IDLE);
            return;
        }

        if (isTaskCompleted(task)) {
            int progress = getTaskField(task, dqFieldAs);
            int required = getTaskField(task, dqFieldAx);
            log("NV hoan thanh! " + progress + "/" + required);
            setAutoCombat(false);
            autoCombatRequested = false;
            restorePriorityTargeting();
            setState(TaskState.MOVE_TO_TURN_IN);
            return;
        }

        int ci = getTaskField(task, dqFieldCi);    // mob ID
        int av = getTaskField(task, dqFieldAv);    // NPC target
        int ax = getTaskField(task, dqFieldAx);    // required count
        int progress = getTaskField(task, dqFieldAs);

        // Kiểm tra đúng map chưa (nếu chưa đúng map thì quay về MOVE_TO_MAP)
        int mapK = getTaskField(task, dqFieldK);
        short currentMap = getCurrentMapId();
        if (mapK > 0 && currentMap != mapK) {
            log("Chua dung map! Dang o " + currentMap + " can den " + mapK + ". Quay lai MOVE_TO_MAP...");
            setAutoCombat(false);
            autoCombatRequested = false;
            restorePriorityTargeting();
            setState(TaskState.MOVE_TO_MAP);
            return;
        }

        // Xác định loại task: nếu có mob ID → kill task (bao gồm boss ax=1)
        if (ci > 0 && ax >= 1) {
            boolean isBossQuest = (ax == 1); // Boss: chỉ cần giết 1 con
            long timeSinceDoTask = now - stateEnteredTime;

            // ═══ Boss quest (linh thú): cùng cơ chế farm ═══
            if (isBossQuest) {
                // Bước 1: Lưu anchor + navigate đến
                if (bossAnchorX == 0) {
                    short[] cfg = findAnchor("boss", currentMap, 0);
                    if (cfg != null) {
                        bossAnchorX = cfg[0];
                        bossAnchorY = cfg[1];
                        // Có config → TẮT z.ap + TẮT combat + navigate đến anchor
                        disableAutoNav();
                        setAutoCombat(false);
                        navigateTo(currentMap, bossAnchorX, bossAnchorY);
                        log("Boss: Config anchor=(" + bossAnchorX + "," + bossAnchorY 
                                + ") Mob=" + ci + " | Navigate + Combat OFF");
                    } else {
                        bossAnchorX = (short)(getPlayerX() + 10);
                        bossAnchorY = getPlayerY();
                        setAutoCombat(true);
                        log("Boss: Fallback anchor=(" + bossAnchorX + "," + bossAnchorY 
                                + ") Mob=" + ci + " | Combat ON");
                    }
                    autoCombatRequested = true;
                }

                // Bước 2: Drift check mỗi 1s
                if (now - lastBossAnchorCheck >= 1000) {
                    lastBossAnchorCheck = now;
                    boolean specialAlive = hasSpecialMobAlive();
                    
                    if (specialAlive) {
                        // Có quái đặc biệt → giữ combat, tạm dừng drift check
                        if (!isAutoCombatOn()) {
                            setAutoCombat(true);
                            log("Boss: Quai dac biet! Bat combat de danh.");
                        }
                    } else {
                        // Không có quái đặc biệt → check drift
                        short cx = getPlayerX();
                        short cy = getPlayerY();
                        if (Math.abs(cx - bossAnchorX) > 5 || Math.abs(cy - bossAnchorY) > 5) {
                            // Lệch → TẮT combat → quay về anchor
                            setAutoCombat(false);
                            navigateTo(currentMap, bossAnchorX, bossAnchorY);
                            log("Boss DRIFT: (" + cx + "," + cy + ") → TAT combat + quay ve (" 
                                    + bossAnchorX + "," + bossAnchorY + ")");
                        } else if (!isAutoCombatOn()) {
                            // Đã về gần anchor → BẬT lại combat
                            setAutoCombat(true);
                            log("Boss: Da ve anchor. Bat lai combat.");
                        }
                    }
                }
            }

            // Farm task thường
            if (!isBossQuest) {
                // ═══ Bước 1: Lưu anchor + navigate đến ═══
                if (!farmAnchorSet) {
                    farmMobId = ci;
                    
                    // Tìm anchor từ config, fallback dùng vị trí z.ap
                    short[] cfg = findAnchor("farm", currentMap, ci);
                    if (cfg != null) {
                        farmAnchorX = cfg[0];
                        farmAnchorY = cfg[1];
                        farmHasConfigAnchor = true;
                        // Có config → TẮT combat + navigate đến anchor trước
                        setAutoCombat(false);
                        navigateTo(currentMap, farmAnchorX, farmAnchorY);
                        log("Farm: Config anchor=(" + farmAnchorX + "," + farmAnchorY 
                                + ") Mob=" + ci + " (" + getMobNameById(ci) + ") | Map=" + currentMap
                                + " | Navigate + Drift check ON");
                    } else {
                        // Không config (map 1 quái) → dùng vị trí hiện tại + bật combat ngay, KHÔNG drift check
                        farmAnchorX = getPlayerX();
                        farmAnchorY = getPlayerY();
                        farmHasConfigAnchor = false;
                        setAutoCombat(true);
                        log("Farm: z.ap anchor=(" + farmAnchorX + "," + farmAnchorY 
                                + ") Mob=" + ci + " (" + getMobNameById(ci) + ") | Map=" + currentMap
                                + " | Combat ON | Drift check OFF");
                    }
                    farmAnchorSet = true;
                    autoCombatRequested = true;
                }

                // ═══ Bước 2: Drift check mỗi 3s (CHỈ khi có config anchor = map nhiều quái) ═══
                if (farmHasConfigAnchor && now - lastBossAnchorCheck >= 1000) {
                    lastBossAnchorCheck = now;
                    boolean specialAlive = hasSpecialMobAlive();
                    
                    if (specialAlive) {
                        // Có quái đặc biệt sống → tạm dừng drift check, để combat xử lý
                        if (!isAutoCombatOn()) {
                            setAutoCombat(true);
                            log("Farm: Quai dac biet xuat hien! Bat combat de danh.");
                        }
                    } else {
                        // Không có quái đặc biệt → check drift
                        short curX = getPlayerX();
                        short curY = getPlayerY();
                        int driftX = Math.abs(curX - farmAnchorX);
                        int driftY = Math.abs(curY - farmAnchorY);
                        
                        if (driftX > 200 || driftY > 200) {
                            // Lệch → TẮT combat → quay về anchor
                            setAutoCombat(false);
                            navigateTo(currentMap, farmAnchorX, farmAnchorY);
                            log("Farm DRIFT: (" + curX + "," + curY + ") → TAT combat + quay ve (" 
                                    + farmAnchorX + "," + farmAnchorY + ")");
                        } else if (!isAutoCombatOn()) {
                            // Đã về gần anchor → BẬT lại combat
                            setAutoCombat(true);
                            log("Farm: Da ve anchor. Bat lai combat.");
                        }
                    }
                }
            } else if (timeSinceDoTask >= 9000 && now - lastActionTime > 5000) {
                // Boss quest sau delay: re-check auto combat
                if (!isAutoCombatOn()) {
                    setAutoCombat(true);
                    log("Re-enable auto combat cho boss quest");
                }
            }

            if (now - lastActionTime > 10000) {
                lastActionTime = now;
                int currentProgress = getTaskField(task, dqFieldAs);
                short playerX = getPlayerX();
                short playerY = getPlayerY();
                // Lấy tên mob từ ci (mob template ID)
                String mobName = getMobNameById(ci);
                logOnce("Dang farm: " + currentProgress + "/" + ax + " | Map=" + currentMap 
                        + " | Mob=" + ci + " (" + mobName + ")"
                        + " | PlayerXY=(" + playerX + "," + playerY + ")");
            }
        } else if (av > 0) {
            // Talk task - có NPC target
            tickDoTalkTask(now, task);
        } else {
            // Không rõ loại task - vẫn bật auto combat
            if (!autoCombatRequested) {
                autoCombatRequested = true;
                setAutoCombat(true);
                log("NV khong ro loai (ci=" + ci + " av=" + av + " ax=" + ax
                        + "). Bat auto danh de thu!");
            }
            if (now - lastActionTime > 10000) {
                lastActionTime = now;
                int currentProgress = getTaskField(task, dqFieldAs);
                logOnce("Farm (unknown type): " + currentProgress + "/" + ax);
            }
        }
    }

    private void tickDoTalkTask(long now, Object task) throws Exception {
        if (now - lastActionTime < COOLDOWN_MS) return;
        lastActionTime = now;

        int targetNpc = getTaskField(task, dqFieldAv);
        if (targetNpc <= 0) {
            log("NV noi chuyen nhung khong co NPC target! Bat auto farm...");
            // Fallback: bật auto combat thay vì skip
            if (!isAutoCombatOn()) {
                setAutoCombat(true);
            }
            return;
        }

        log("NV noi chuyen: tuong tac NPC " + targetNpc);
        sendOpenNpc(targetNpc);
        Thread.sleep(500);
        sendSelectMenu(targetNpc, 0);
    }

    private void tickMoveToTurnIn(long now) throws Exception {
        int npcId = getCurrentNpcId();
        int npcMapId = getNpcMapId(npcId);
        short currentMap = getCurrentMapId();

        setAutoCombat(false);
        autoCombatRequested = false;

        if (currentMap != npcMapId) {
            if (now - lastMoveCheckTime > MOVE_CHECK_INTERVAL) {
                lastMoveCheckTime = now;
                // Dùng cross-map navigation giống khi đi làm NV
                navigateToMap(npcMapId);
                logOnce("Di chuyen ve map " + npcMapId + " tra NV cho NPC " + npcId + " (cross-map)");
            }
            return;
        }

        // Đã đúng map, clear nav target và tìm NPC động
        clearNavTarget();
        int[] npcInfo = findNpcOnMap(npcId);
        int[] npcPos;
        if (npcInfo != null) {
            currentNpcRealId = npcInfo[0];
            npcPos = new int[]{npcInfo[1], npcInfo[2]};
        } else {
            currentNpcRealId = -1;
            npcPos = getNpcPosition(npcId); // Fallback
        }

        int playerX = getPlayerX();
        int playerY = getPlayerY();
        double distance = Math.sqrt(Math.pow(playerX - npcPos[0], 2) + Math.pow(playerY - npcPos[1], 2));

        if (distance <= NPC_INTERACT_RANGE) {
            if (currentNpcRealId == -1) {
                log("Da den toado fallback tra NV nhung khong thay NPC thuc te! Cho load...");
                return;
            }
            log("Da den gan NPC " + npcId + " (real " + currentNpcRealId + ") de tra NV");
            setState(TaskState.TURN_IN);
        } else {
            if (now - lastMoveCheckTime > MOVE_CHECK_INTERVAL) {
                lastMoveCheckTime = now;
                navigateTo(npcMapId, npcPos[0], npcPos[1]);
                logOnce("Dang di den NPC " + npcId + " tra NV...");
            }
        }
    }

    /**
     * Event-driven Turn-in NPC interaction (tương tự tickInteractNpc).
     */
    private void tickTurnIn(long now) throws Exception {
        // Debug log mỗi giây
        if (now - lastInteractDebugTime > 1000) {
            lastInteractDebugTime = now;
            String dlgInfo = describeDialog();
            log("DEBUG TURN_IN: step=" + interactStep
                    + " timeDiff=" + (now - lastInteractStepTime)
                    + " dialog=" + dlgInfo);
        }

        if (now - stateEnteredTime < COOLDOWN_MS) return;

        if (currentNpcRealId == -1) {
            log("currentNpcRealId is invalid (-1) khi tra NV! Chuyen ve COOLDOWN...");
            setState(TaskState.COOLDOWN);
            return;
        }

        // Timeout tổng 15s
        if (now - stateEnteredTime > 15000) {
            log("TIMEOUT tra NV sau 15s!");
            closeCurrentDialog();
            setState(TaskState.COOLDOWN);
            return;
        }

        int[] dlgInfo = detectDialog();
        int dialogType = (dlgInfo != null) ? dlgInfo[0] : -999;

        switch (interactStep) {
            case 0: // ── Gửi CMD 54 ──
                log("[TurnIn Step 0] Mo NPC thuc ID=" + currentNpcRealId
                        + " (template " + getCurrentNpcId() + ") (CMD 54)");
                sendOpenNpc(currentNpcRealId);
                interactStep = 1;
                lastInteractStepTime = now;
                break;

            case 1: // ── Chờ NPC_DIALOG → đọc menu, tìm index, gửi CMD 53 ──
                if (now - lastInteractStepTime < 500) break;

                if (dialogType >= 0) {
                    String[] menuItems = readDialogMenuItems();
                    if (menuItems != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int mi = 0; mi < menuItems.length; mi++) {
                            sb.append("  [" + mi + "] " + menuItems[mi] + "\n");
                        }
                        log("[TurnIn Step 1] Menu items (" + menuItems.length + "):\n" + sb.toString());

                        String keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tu\u1ea7n ho\u00e0n" : "linh th\u00fa";
                        int foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        if (foundIndex == -1) {
                            keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tuan hoan" : "thu ph\u1ee5c";
                            foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        }
                        if (foundIndex == -1) {
                            keyword = (currentTaskType == TaskType.TUAN_HOAN) ? "tuan hoan" : "m\u00e3nh th\u00fa";
                            foundIndex = findMenuIndexByKeyword(menuItems, keyword);
                        }
                        int menuIndex = (foundIndex >= 0) ? foundIndex : getCurrentMenuIndex();
                        log("[TurnIn Step 1] Using menuIndex=" + menuIndex
                                + (foundIndex >= 0 ? " (found by keyword)" : " (configured)"));

                        int subCount = getSubMenuCount(menuItems, menuIndex);
                        closeCurrentDialog();

                        if (subCount > 0) {
                            // Sub-options: 0=Nhận, 1=Huỷ, 2=Hoàn Thành
                            int turnInSubIdx = subCount - 1; // "Hoàn Thành" luôn ở cuối
                            log("[TurnIn Step 1] Menu co sub-options! Gui CMD 53 parentIdx=" + menuIndex + " subIdx=" + turnInSubIdx + " (Hoan Thanh)");
                            sendSelectMenuWithSub(currentNpcRealId, menuIndex, turnInSubIdx);
                        } else {
                            log("[TurnIn Step 1] Gui CMD 53 index=" + menuIndex);
                            sendSelectMenu(currentNpcRealId, menuIndex);
                        }
                    } else {
                        int menuIndex = getCurrentMenuIndex();
                        log("[TurnIn Step 1] Khong doc duoc menu! Gui CMD 53 index=" + menuIndex);
                        closeCurrentDialog();
                        sendSelectMenu(currentNpcRealId, menuIndex);
                    }
                    interactStep = 2;
                    lastInteractStepTime = now;
                } else if (now - lastInteractStepTime > 3000) {
                    log("[TurnIn Step 1] Timeout! Retry CMD 54...");
                    sendOpenNpc(currentNpcRealId);
                    lastInteractStepTime = now;
                }
                break;

            case 2: // ── Chờ OPEN_MENU → gửi CMD 5 ──
                if (now - lastInteractStepTime < 500) break;

                // Kiểm tra task đã biến mất (server đã xử lý tra NV)
                Object taskCheck = getCurrentTaskObject();
                if (taskCheck == null) {
                    log("[TurnIn Step 2] Task da null (tra thanh cong!) → COOLDOWN");
                    closeCurrentDialog();
                    setState(TaskState.COOLDOWN);
                    break;
                }

                if (dialogType == -2) {
                    log("[TurnIn Step 2] OPEN_MENU detected! Gui CMD 5 index=0...");
                    sendSelectSubMenu(0);
                    interactStep = 3;
                    lastInteractStepTime = now;
                } else if (dialogType >= 0) {
                    if (now - lastInteractStepTime > 3000) {
                        log("[TurnIn Step 2] Van con NPC dialog cu! Gui lai CMD 53...");
                        sendSelectMenu(currentNpcRealId, getCurrentMenuIndex());
                        lastInteractStepTime = now;
                    }
                } else if (now - lastInteractStepTime > 5000) {
                    log("[TurnIn Step 2] Timeout! Retry tu dau...");
                    interactStep = 0;
                    lastInteractStepTime = now;
                }
                break;

            default: // ── Step 3+: Chờ kết quả hoặc thêm dialog ──
                if (now - lastInteractStepTime < 500) break;

                Object taskFinal = getCurrentTaskObject();
                if (taskFinal == null) {
                    log("[TurnIn Step " + interactStep + "] Task null → tra thanh cong!");
                    closeCurrentDialog();
                    setState(TaskState.COOLDOWN);
                    break;
                }

                if (dialogType == -2) {
                    log("[TurnIn Step " + interactStep + "] Con OPEN_MENU! Gui CMD 5...");
                    sendSelectSubMenu(0);
                    interactStep++;
                    lastInteractStepTime = now;
                } else if (now - lastInteractStepTime > 3000) {
                    log("[TurnIn Step " + interactStep + "] Timeout, tra NV xong.");
                    closeCurrentDialog();
                    setState(TaskState.COOLDOWN);
                }
                break;
        }
    }

    private void tickCooldown(long now) {
        if (now - stateEnteredTime > COOLDOWN_MS) {
            setState(TaskState.IDLE);
        }
    }

    private void tickAfkFarm(long now) throws Exception {
        short currentMap = getCurrentMapId();

        // Nếu có NV mới (server reset hàng ngày) → quay về IDLE làm NV
        int thRemaining = getTuanHoanRemaining();
        int ltRemaining = getLinhThuRemaining();
        if ((tuanHoanEnabled && thRemaining > 0) || (linhThuEnabled && ltRemaining > 0)) {
            log("Co NV moi! TH=" + thRemaining + " LT=" + ltRemaining + ". Quay lai lam NV...");
            setAutoCombat(false);
            autoCombatRequested = false;
            setState(TaskState.IDLE);
            return;
        }

        // Di chuyển đến AFK map nếu chưa đúng
        if (currentMap != afkMapId) {
            if (now - lastMoveCheckTime > 5000) {
                lastMoveCheckTime = now;
                // Game engine check a.i.a() != null
                try {
                    Object iInst = (iGetInstance != null) ? iGetInstance.invoke(null) : null;
                    if (iInst == null) {
                        log("AFK navigate: a.i instance chua ready, cho...");
                        return;
                    }
                } catch (Exception e) {
                    log("AFK navigate: check a.i error: " + e.getMessage());
                    return;
                }
                navigateToMap(afkMapId);
                log("AFK navigate: dang o map " + currentMap + " -> map " + afkMapId);
            }
            return;
        }

        // Đã đúng map → check zone + bật auto combat
        clearNavTarget();

        // Đổi khu nếu cần
        if (afkZone > 0 && !afkZoneChanged) {
            try {
                // CHỈ ĐÁNH DẤU ĐÃ ĐỔI KHI THẬT SỰ GỬI ĐƯỢC.
                // Đây là đường đi hằng ngày và là chỗ chốt khoá 15s dễ làm hỏng nhất: mọi hoạt
                // động đều bàn giao sang treo map ngay sau khi vừa nhảy khu, nên lệnh đổi khu ở
                // đây gần như luôn rơi vào giữa khoá. Đặt cờ vô điều kiện thì nick treo map ở
                // KHU SAI suốt ngày mà không có dòng nào báo — vì cờ đã bật, nhánh này không
                // bao giờ chạy lại.
                long khoa = sendChangeZone(afkZone);
                if (khoa > 0) {
                    lastActionTime = now;   // thử lại ở nhịp sau, cờ vẫn để nguyên false
                    return;
                }
                log("AFK: Doi khu " + afkZone + " tren map " + afkMapId);
                afkZoneChanged = true;
                lastActionTime = now; // chờ 3s cho map reload
            } catch (Exception e) {
                log("AFK: Doi khu error: " + e.getMessage());
            }
            return;
        }

        // Chờ 3s sau khi đổi khu
        if (now - lastActionTime < 3000) return;

        if (!autoCombatRequested) {
            autoCombatRequested = true;
            setAutoCombat(true);
            log("Da den AFK map " + afkMapId + " khu " + afkZone + ". Bat auto farm!");
        } else if (now - lastActionTime > 10000) {
            lastActionTime = now;
            if (!isAutoCombatOn()) {
                setAutoCombat(true);
                log("Re-enable auto combat (AFK farm)");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Tìm NPC theo template ID trong map hiện tại.
     * Trả về mảng 3 phần tử: {realNpcId (index thực), x, y}.
     * Nếu không tìm thấy, trả về null.
     */
    private int[] findNpcOnMap(int npcTemplateId) {
        if (!reflectionReady || zFieldF == null) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> npcVector = (java.util.Vector<?>) zFieldF.get(zInst);
            if (npcVector == null) return null;
            
            for (int i = 0; i < npcVector.size(); i++) {
                Object npcObj = npcVector.get(i);
                if (npcObj == null) continue;
                
                short templateId = frFieldAh.getShort(npcObj);
                if (templateId == npcTemplateId) {
                    int realId = frFieldAZ.getInt(npcObj);
                    short x = frFieldAr.getShort(npcObj);
                    short y = frFieldAs.getShort(npcObj);
                    return new int[]{realId, x, y};
                }
            }
        } catch (Exception e) {
            log("Error in findNpcOnMap: " + e.getMessage());
        }
        return null;
    }

    /** Debug: Dump tất cả NPC trên map hiện tại */
    private void dumpAllNpcsOnMap() {
        try {
            Object zInst = getZ();
            if (zInst == null) return;
            java.util.Vector<?> npcVector = (java.util.Vector<?>) zFieldF.get(zInst);
            if (npcVector == null || npcVector.size() == 0) {
                log("NPC dump: no NPCs on map");
                return;
            }
            log("NPC dump: " + npcVector.size() + " NPCs on map:");
            for (int i = 0; i < npcVector.size(); i++) {
                Object npcObj = npcVector.get(i);
                if (npcObj == null) continue;
                short templateId = frFieldAh.getShort(npcObj);
                int realId = frFieldAZ.getInt(npcObj);
                short x = frFieldAr.getShort(npcObj);
                short y = frFieldAs.getShort(npcObj);
                log("  [" + i + "] template=" + templateId + " realId=" + realId + " pos=(" + x + "," + y + ")");
            }
        } catch (Exception e) {
            log("NPC dump error: " + e.getMessage());
        }
    }

    private Object getCurrentTaskObject() throws Exception {
        if (currentTaskType == TaskType.TUAN_HOAN) {
            return getTuanHoanTask();
        } else {
            return getLinhThuTask();
        }
    }

    private int getCurrentNpcId() {
        return currentTaskType == TaskType.TUAN_HOAN ? NPC_TUAN_HOAN : NPC_LINH_THU;
    }

    private int getCurrentMenuIndex() {
        return currentTaskType == TaskType.TUAN_HOAN ? menuIndexTuanHoan : menuIndexLinhThu;
    }

    private String getNpcName() {
        return currentTaskType == TaskType.TUAN_HOAN ? "MeiTerumi(102)" : "Rasa(98)";
    }

    private String getTaskTypeName() {
        return currentTaskType == TaskType.TUAN_HOAN ? "Tuan hoan" : "Linh thu";
    }

    /**
     * Lấy map ID của NPC — ưu tiên map hiện tại nếu có NPC config.
     * Nếu đang ở map có NPC → trả NV ngay, không cần chạy về làng mặc định.
     */
    private int getNpcMapId(int npcId) {
        loadAnchorConfig();
        String npcType = (npcId == NPC_TUAN_HOAN) ? "npc_tuan_hoan" : "npc_linh_thu";
        // Check: map hiện tại có NPC này không?
        try {
            short currentMap = getCurrentMapId();
            String localKey = npcType + "_" + currentMap;
            if (npcConfig.containsKey(localKey)) {
                log("NPC " + npcType + " co o map hien tai (" + currentMap + "), tra NV tai day!");
                return currentMap;
            }
        } catch (Exception e) { /* ignore, use default */ }
        // Fallback: map mặc định (entry đầu tiên trong config)
        Integer defaultMap = npcDefaultMap.get(npcType);
        if (defaultMap != null) return defaultMap;
        return 68; // ultimate fallback
    }

    /** Tọa độ NPC — ưu tiên map hiện tại, fallback map mặc định */
    private int[] getNpcPosition(int npcId) {
        loadAnchorConfig();
        String npcType = (npcId == NPC_TUAN_HOAN) ? "npc_tuan_hoan" : "npc_linh_thu";
        // Check: map hiện tại có NPC này không?
        try {
            short currentMap = getCurrentMapId();
            String localKey = npcType + "_" + currentMap;
            int[] cfg = npcConfig.get(localKey);
            if (cfg != null) return new int[]{cfg[1], cfg[2]};
        } catch (Exception e) { /* ignore, use default */ }
        // Fallback: map mặc định
        Integer defaultMap = npcDefaultMap.get(npcType);
        if (defaultMap != null) {
            int[] cfg = npcConfig.get(npcType + "_" + defaultMap);
            if (cfg != null) return new int[]{cfg[1], cfg[2]};
        }
        return new int[]{DEFAULT_X, DEFAULT_Y}; // ultimate fallback
    }

    private void setState(TaskState newState) {
        if (state != newState) {
            log("State: " + state + " -> " + newState);
            state = newState;
            stateEnteredTime = System.currentTimeMillis();
            lastMoveCheckTime = 0;
            lastLogMessage = "";
            interactStep = 0;
            lastInteractStepTime = 0;
            // Reset farm anchor khi đổi state
            farmAnchorSet = false;
            farmHasConfigAnchor = false;
            farmMobId = 0;
            bossAnchorX = 0;
            bossAnchorY = 0;
            lastBossAnchorCheck = 0;
        }
    }

    private void log(String message) {
        System.out.println("[TaskMgr] " + message);
        lastLogMessage = message;
    }

    private void logOnce(String message) {
        if (!message.equals(lastLogMessage)) {
            log(message);
        }
    }

    /**
     * Scan ALL NPCs on current map. Return JSON array string.
     * Each NPC: {"id":realId, "templateId":tplId, "x":x, "y":y, "name":"...", "hp":hp}
     */
    @SuppressWarnings("unchecked")
    public String scanAllNpcsJson() {
        if (!reflectionReady) {
            initReflection();
            if (!reflectionReady) return "[]";
        }
        try {
            Object zInst = getZ();
            if (zInst == null) return "[]";
            java.util.Vector<Object> npcVector = (java.util.Vector<Object>) zFieldF.get(zInst);
            if (npcVector == null || npcVector.isEmpty()) return "[]";

            // Method fr.a() returns fs
            Method frGetTemplate = null;
            if (frClass != null && fsClass != null) {
                for (Method m : frClass.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 0
                            && m.getReturnType() == fsClass) {
                        frGetTemplate = m;
                        frGetTemplate.setAccessible(true);
                        break;
                    }
                }
            }

            StringBuilder sb = new StringBuilder("{\"npcs\":[");
            boolean first = true;
            for (Object npc : npcVector) {
                if (npc == null || !frClass.isInstance(npc)) continue;

                short templateId = frFieldAh.getShort(npc);
                int realId = frFieldAZ.getInt(npc);
                short x = frFieldAr.getShort(npc);
                short y = frFieldAs.getShort(npc);

                String npcName = "";
                int npcHp = 0;
                if (frGetTemplate != null) {
                    try {
                        Object template = frGetTemplate.invoke(npc);
                        if (template != null) {
                            if (fsFieldL != null) {
                                Object nameObj = fsFieldL.get(template);
                                if (nameObj instanceof String) npcName = (String) nameObj;
                            }
                            if (fsFieldY != null) {
                                npcHp = fsFieldY.getInt(template);
                            }
                        }
                    } catch (Exception ignore) {}
                }

                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(realId)
                  .append(",\"templateId\":").append(templateId)
                  .append(",\"x\":").append(x)
                  .append(",\"y\":").append(y)
                  .append(",\"name\":\"").append(escapeJson(npcName)).append("\"")
                  .append(",\"hp\":").append(npcHp)
                  .append("}");
            }
            sb.append("]");

            // ── Scan MOBs from all Vector<eq> fields in z ──
            sb.append(",\"mobs\":[");
            try {
                Class<?> eqClass = Class.forName("a.eq");
                // Find eq.z field (template ID) and eq.ct field (HP)
                Field eqFieldZ = null, eqFieldCt = null;
                for (Field f : eqClass.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().equals("z") && f.getType() == short.class) eqFieldZ = f;
                    if (f.getName().equals("ct") && f.getType() == int.class) eqFieldCt = f;
                }
                // eq.a() method returns mob template (class m)
                Method eqGetTemplate = null;
                Class<?> mClass = Class.forName("a.m");
                for (Method m : eqClass.getDeclaredMethods()) {
                    if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == mClass) {
                        eqGetTemplate = m;
                        eqGetTemplate.setAccessible(true);
                        break;
                    }
                }

                boolean firstMob = true;
                // Scan ALL vector fields looking for eq instances
                for (Field zf : zClass.getDeclaredFields()) {
                    zf.setAccessible(true);
                    if (java.lang.reflect.Modifier.isStatic(zf.getModifiers())) continue;
                    if (zf.getType() != java.util.Vector.class) continue;
                    Object vecObj = zf.get(zInst);
                    if (vecObj == null) continue;
                    java.util.Vector<?> vec = (java.util.Vector<?>) vecObj;
                    if (vec.isEmpty()) continue;
                    // Check if first element is eq class
                    if (!eqClass.isInstance(vec.get(0))) continue;

                    for (Object mob : vec) {
                        if (mob == null || !eqClass.isInstance(mob)) continue;
                        short mobTplId = eqFieldZ != null ? eqFieldZ.getShort(mob) : -1;
                        int mobHp = eqFieldCt != null ? eqFieldCt.getInt(mob) : 0;
                        // Read x,y from parent class fr_0 (fields ar, as)
                        short mx = frFieldAr != null ? frFieldAr.getShort(mob) : 0;
                        short my = frFieldAs != null ? frFieldAs.getShort(mob) : 0;
                        int mobRealId = 0;
                        try { mobRealId = mob.getClass().getField("aZ").getInt(mob); } catch (Exception ig) {}
                        // Try read name from eq string fields
                        String mobName = "";
                        try {
                            for (Field mf : mob.getClass().getDeclaredFields()) {
                                mf.setAccessible(true);
                                if (mf.getType() == String.class) {
                                    Object sv = mf.get(mob);
                                    if (sv != null && !sv.toString().isEmpty()) {
                                        mobName = sv.toString();
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ig) {}

                        if (!firstMob) sb.append(",");
                        firstMob = false;
                        sb.append("{\"id\":").append(mobRealId)
                          .append(",\"tplId\":").append(mobTplId)
                          .append(",\"hp\":").append(mobHp)
                          .append(",\"x\":").append(mx)
                          .append(",\"y\":").append(my)
                          .append(",\"vec\":\"").append(zf.getName()).append("\"")
                          .append(",\"name\":\"").append(escapeJson(mobName)).append("\"")
                          .append("}");
                    }
                }
            } catch (Exception mobEx) {
                log("mob scan error: " + mobEx.getMessage());
            }
            sb.append("]}");

            return sb.toString();
        } catch (Exception e) {
            log("scanAllNpcsJson error: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * Deep scan: quét TẤT CẢ Vector fields trong class z để tìm event NPC.
     * Trả về JSON string chứa thông tin từng vector field.
     */
    @SuppressWarnings("unchecked")
    public String scanAllEntitiesJson() {
        if (!reflectionReady) {
            initReflection();
            if (!reflectionReady) return "{\"error\":\"reflection not ready\"}";
        }
        try {
            Object zInst = getZ();
            if (zInst == null) return "{\"error\":\"z instance null\"}";

            StringBuilder sb = new StringBuilder("{\"fields\":[");
            boolean firstField = true;

            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;

                Object val = null;
                try { val = f.get(zInst); } catch (Exception ignore) {}
                if (val == null) continue;

                // Chỉ quan tâm Vector fields
                if (val instanceof java.util.Vector) {
                    java.util.Vector<?> vec = (java.util.Vector<?>) val;
                    if (vec.isEmpty()) continue;

                    if (!firstField) sb.append(",");
                    firstField = false;

                    // Lấy class name của phần tử đầu
                    String elementClass = vec.get(0).getClass().getName();
                    sb.append("{\"field\":\"").append(f.getName())
                      .append("\",\"type\":\"").append(f.getType().getName())
                      .append("\",\"elementClass\":\"").append(elementClass)
                      .append("\",\"size\":").append(vec.size())
                      .append(",\"items\":[");

                    boolean firstItem = true;
                    for (Object item : vec) {
                        if (item == null) continue;
                        if (!firstItem) sb.append(",");
                        firstItem = false;

                        sb.append("{\"class\":\"").append(item.getClass().getName()).append("\"");

                        // Thử đọc tất cả fields quan trọng
                        try {
                            // Đọc các short/int fields phổ biến
                            for (Field itemF : item.getClass().getDeclaredFields()) {
                                itemF.setAccessible(true);
                                if (java.lang.reflect.Modifier.isStatic(itemF.getModifiers())) continue;
                                Class<?> ft = itemF.getType();
                                if (ft == short.class || ft == int.class || ft == long.class) {
                                    sb.append(",\"").append(itemF.getName()).append("\":").append(itemF.get(item));
                                } else if (ft == String.class) {
                                    Object sv = itemF.get(item);
                                    if (sv != null) {
                                        String s = sv.toString();
                                        if (s.length() <= 50) {
                                            sb.append(",\"").append(itemF.getName()).append("\":\"").append(escapeJson(s)).append("\"");
                                        }
                                    }
                                }
                            }
                            // Thử đọc template (method a() trả về fs)
                            if (frClass != null && frClass.isInstance(item) && fsClass != null) {
                                for (Method m : frClass.getDeclaredMethods()) {
                                    if (m.getName().equals("a") && m.getParameterCount() == 0
                                            && m.getReturnType() == fsClass) {
                                        m.setAccessible(true);
                                        Object tmpl = m.invoke(item);
                                        if (tmpl != null) {
                                            if (fsFieldL != null) {
                                                Object n = fsFieldL.get(tmpl);
                                                if (n != null) sb.append(",\"tplName\":\"").append(escapeJson(n.toString())).append("\"");
                                            }
                                            if (fsFieldY != null) {
                                                sb.append(",\"tplHp\":").append(fsFieldY.getInt(tmpl));
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ignore) {}

                        sb.append("}");
                    }
                    sb.append("]}");
                }
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /** Search ALL fields in z for any entity with a String field containing keyword. */
    public String searchEntityByName(String keyword) {
        if (!reflectionReady) { initReflection(); if (!reflectionReady) return "{\"error\":\"no reflection\"}"; }
        try {
            Object zInst = getZ();
            if (zInst == null) return "{\"error\":\"z null\"}";
            StringBuilder sb = new StringBuilder("{\"keyword\":\"" + escapeJson(keyword) + "\",\"results\":[");
            boolean first = true;
            String kl = keyword.toLowerCase();
            for (Field f : zClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Object val = null;
                try { val = f.get(zInst); } catch (Exception ig) {}
                if (val == null) continue;
                if (val instanceof java.util.Vector) {
                    java.util.Vector<?> vec = (java.util.Vector<?>) val;
                    int idx = 0;
                    for (Object item : vec) {
                        if (item != null) {
                            String match = findStrField(item, kl);
                            if (match != null) {
                                if (!first) sb.append(","); first = false;
                                sb.append("{\"f\":\"").append(f.getName()).append("\",\"i\":").append(idx)
                                  .append(",\"c\":\"").append(item.getClass().getName())
                                  .append("\",\"m\":\"").append(escapeJson(match))
                                  .append("\",\"d\":").append(dumpObj(item)).append("}");
                            }
                        }
                        idx++;
                    }
                } else if (val instanceof java.util.Map) {
                    for (java.util.Map.Entry<?,?> e2 : ((java.util.Map<?,?>) val).entrySet()) {
                        Object item = e2.getValue();
                        if (item != null) {
                            String match = findStrField(item, kl);
                            if (match != null) {
                                if (!first) sb.append(","); first = false;
                                sb.append("{\"f\":\"").append(f.getName()).append("\",\"k\":\"")
                                  .append(escapeJson(String.valueOf(e2.getKey())))
                                  .append("\",\"c\":\"").append(item.getClass().getName())
                                  .append("\",\"m\":\"").append(escapeJson(match))
                                  .append("\",\"d\":").append(dumpObj(item)).append("}");
                            }
                        }
                    }
                } else if (val.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(val);
                    for (int i = 0; i < len; i++) {
                        Object item = java.lang.reflect.Array.get(val, i);
                        if (item != null) {
                            String match = findStrField(item, kl);
                            if (match != null) {
                                if (!first) sb.append(","); first = false;
                                sb.append("{\"f\":\"").append(f.getName()).append("\",\"i\":").append(i)
                                  .append(",\"c\":\"").append(item.getClass().getName())
                                  .append("\",\"m\":\"").append(escapeJson(match))
                                  .append("\",\"d\":").append(dumpObj(item)).append("}");
                            }
                        }
                    }
                }
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) { return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"; }
    }

    private String findStrField(Object obj, String kl) {
        if (obj == null) return null;
        if (obj instanceof String && ((String)obj).toLowerCase().contains(kl)) return "self=" + obj;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == String.class) {
                    Object sv = f.get(obj);
                    if (sv != null && sv.toString().toLowerCase().contains(kl)) return f.getName() + "=" + sv;
                }
                // 1 level deep
                if (!f.getType().isPrimitive() && f.getType() != String.class
                        && !f.getType().isArray() && !java.util.Collection.class.isAssignableFrom(f.getType())) {
                    Object inner = f.get(obj);
                    if (inner != null) {
                        for (Field f2 : inner.getClass().getDeclaredFields()) {
                            f2.setAccessible(true);
                            if (java.lang.reflect.Modifier.isStatic(f2.getModifiers())) continue;
                            if (f2.getType() == String.class) {
                                Object sv2 = f2.get(inner);
                                if (sv2 != null && sv2.toString().toLowerCase().contains(kl)) return f.getName() + "." + f2.getName() + "=" + sv2;
                            }
                        }
                    }
                }
            }
        } catch (Exception ig) {}
        return null;
    }

    private String dumpObj(Object obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft == short.class || ft == int.class || ft == long.class || ft == byte.class
                        || ft == float.class || ft == double.class || ft == boolean.class) {
                    if (!first) sb.append(","); first = false;
                    sb.append("\"").append(f.getName()).append("\":").append(f.get(obj));
                } else if (ft == String.class) {
                    Object sv = f.get(obj);
                    if (sv != null && sv.toString().length() <= 100) {
                        if (!first) sb.append(","); first = false;
                        sb.append("\"").append(f.getName()).append("\":\"").append(escapeJson(sv.toString())).append("\"");
                    }
                }
            }
        } catch (Exception ig) {}
        sb.append("}");
        return sb.toString();
    }

    /** Brute-force search: tìm bất kỳ entity/object nào có int field >= targetHp. */
    public String searchByHp(int targetHp) {
        if (!reflectionReady) { initReflection(); if (!reflectionReady) return "{\"error\":\"no reflection\"}"; }
        try {
            Object zInst = getZ();
            if (zInst == null) return "{\"error\":\"z null\"}";
            StringBuilder sb = new StringBuilder("{\"target\":" + targetHp + ",\"results\":[");
            boolean first = true;

            for (Field zf : zClass.getDeclaredFields()) {
                zf.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(zf.getModifiers())) continue;
                Object val = null;
                try { val = zf.get(zInst); } catch (Exception ig) {}
                if (val == null) continue;

                // Check Vectors
                if (val instanceof java.util.Vector) {
                    java.util.Vector<?> vec = (java.util.Vector<?>) val;
                    int idx = 0;
                    for (Object item : vec) {
                        if (item != null) {
                            String hpMatch = findIntFieldGte(item, targetHp, 2);
                            if (hpMatch != null) {
                                if (!first) sb.append(","); first = false;
                                sb.append("{\"f\":\"").append(zf.getName()).append("\",\"i\":").append(idx)
                                  .append(",\"c\":\"").append(item.getClass().getName())
                                  .append("\",\"hp\":\"").append(escapeJson(hpMatch))
                                  .append("\",\"d\":").append(dumpObj(item)).append("}");
                            }
                        }
                        idx++;
                    }
                }
                // Check arrays
                else if (val.getClass().isArray() && !val.getClass().getComponentType().isPrimitive()) {
                    int len = java.lang.reflect.Array.getLength(val);
                    for (int i = 0; i < len && i < 500; i++) {
                        Object item = java.lang.reflect.Array.get(val, i);
                        if (item != null) {
                            String hpMatch = findIntFieldGte(item, targetHp, 2);
                            if (hpMatch != null) {
                                if (!first) sb.append(","); first = false;
                                sb.append("{\"f\":\"").append(zf.getName()).append("\",\"i\":").append(i)
                                  .append(",\"c\":\"").append(item.getClass().getName())
                                  .append("\",\"hp\":\"").append(escapeJson(hpMatch))
                                  .append("\",\"d\":").append(dumpObj(item)).append("}");
                            }
                        }
                    }
                }
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) { return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"; }
    }

    /** Tìm int field >= target trong object, quét depth levels. Trả về "fieldName=value" hoặc null. */
    private String findIntFieldGte(Object obj, int target, int depth) {
        if (obj == null || depth <= 0) return null;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft == int.class) {
                    int v = f.getInt(obj);
                    if (v >= target) return f.getName() + "=" + v;
                } else if (ft == long.class) {
                    long v = f.getLong(obj);
                    if (v >= target) return f.getName() + "=" + v;
                } else if (ft == short.class) {
                    short v = f.getShort(obj);
                    if (v >= target) return f.getName() + "=" + v;
                } else if (!ft.isPrimitive() && ft != String.class && !ft.isArray()
                        && !java.util.Collection.class.isAssignableFrom(ft)) {
                    Object inner = f.get(obj);
                    if (inner != null) {
                        String match = findIntFieldGte(inner, target, depth - 1);
                        if (match != null) return f.getName() + "." + match;
                    }
                }
            }
        } catch (Exception ig) {}
        return null;
    }

    /** Read question text from au.v Vector (join all lines). */
    private String readDialogQuestionText() {
        if (fkFieldAn == null || auClass == null || auFieldV == null) return null;
        try {
            Object zInst = getZ();
            if (zInst == null) return null;
            java.util.Vector<?> dialogStack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (dialogStack == null || dialogStack.isEmpty()) return null;

            // Find au dialog in stack
            for (int i = dialogStack.size() - 1; i >= 0; i--) {
                Object panel = dialogStack.get(i);
                if (panel != null && auClass.isInstance(panel)) {
                    Object vObj = auFieldV.get(panel);
                    if (vObj instanceof java.util.Vector) {
                        java.util.Vector<?> lines = (java.util.Vector<?>) vObj;
                        StringBuilder result = new StringBuilder();
                        for (Object line : lines) {
                            if (line != null) {
                                if (result.length() > 0) result.append(" ");
                                result.append(line.toString());
                            }
                        }
                        return result.toString();
                    }
                }
            }
        } catch (Exception e) {
            log("readDialogQuestionText error: " + e.getMessage());
        }
        return null;
    }

    /** Read parent menu index from au.ar. */
    private int readDialogParentIndex() {
        if (fkFieldAn == null || auClass == null || auFieldAr == null) return -1;
        try {
            Object zInst = getZ();
            if (zInst == null) return -1;
            java.util.Vector<?> dialogStack = (java.util.Vector<?>) fkFieldAn.get(zInst);
            if (dialogStack == null || dialogStack.isEmpty()) return -1;

            for (int i = dialogStack.size() - 1; i >= 0; i--) {
                Object panel = dialogStack.get(i);
                if (panel != null && auClass.isInstance(panel)) {
                    return auFieldAr.getInt(panel);
                }
            }
        } catch (Exception e) {
            log("readDialogParentIndex error: " + e.getMessage());
        }
        return -1;
    }

    private int npcProbeLastId = -1;
    private String npcProbeLastQuestion = "";

    private void checkNpcClickProbe() {
        try {
            int[] dlg = detectDialog();
            if (dlg == null || dlg[0] < 0) {
                npcProbeLastId = -1;
                npcProbeLastQuestion = "";
                return;
            }

            int npcId = dlg[0];
            String question = readDialogQuestionText();
            if (question == null) question = "";

            if (npcId != npcProbeLastId || (!question.isEmpty() && !question.equalsIgnoreCase(npcProbeLastQuestion))) {
                npcProbeLastId = npcId;
                npcProbeLastQuestion = question;

                Object npcObj = findNpcOnMap(npcId);
                String npcName = npcNameOf(npcObj);
                if (npcName == null || npcName.isEmpty()) npcName = "NPC #" + npcId;

                int mapId = getCurrentMapId();
                int x = getPlayerX();
                int y = getPlayerY();
                if (npcObj != null && frFieldAr != null && frFieldAs != null) {
                    try {
                        x = frFieldAr.getShort(npcObj);
                        y = frFieldAs.getShort(npcObj);
                    } catch (Exception ignore) {}
                }

                String[] menuItems = readDialogMenuItems();
                StringBuilder menuSb = new StringBuilder();
                if (menuItems != null) {
                    for (String m : menuItems) {
                        if (menuSb.length() > 0) menuSb.append(" | ");
                        menuSb.append(m);
                    }
                }

                java.io.PrintWriter w = Auto.getWriter();
                if (w != null) {
                    w.print("{\"type\":\"npc_clicked_probe\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                            + ",\"npcId\":" + npcId
                            + ",\"name\":\"" + escapeJson(npcName) + "\""
                            + ",\"map\":" + mapId
                            + ",\"x\":" + x
                            + ",\"y\":" + y
                            + ",\"menu\":\"" + escapeJson(menuSb.toString()) + "\""
                            + ",\"question\":\"" + escapeJson(question) + "\"}\n");
                    w.flush();
                }
            }
        } catch (Exception ignore) {}
    }

    /** Escape special characters for JSON string. */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ------------------------------------------------------------------
    // AUTO QUIZ NPC EVENT SYSTEM
    // ------------------------------------------------------------------
    private static final int QUIZ_STEP_IDLE = 0;
    private static final int QUIZ_STEP_MOVE_NPC = 1;
    private static final int QUIZ_STEP_OPEN_NPC = 2;
    private static final int QUIZ_STEP_CLICK_START = 3;
    private static final int QUIZ_STEP_READ_QUESTION = 4;
    private static final int QUIZ_STEP_WAIT_QUERY_RES = 5;
    private static final int QUIZ_STEP_AUTO_ANSWER = 6;
    private static final int QUIZ_STEP_WAIT_HUMAN = 7;
    private static final int QUIZ_STEP_CHECK_TRANSITION = 8;
    private static final int QUIZ_STEP_ESC_POPUP = 9;
    private static final int QUIZ_STEP_COOLDOWN = 10;

    private int quizStep = QUIZ_STEP_IDLE;
    private long quizNextTime = 0;
    private int quizNpcId = -1;
    private String quizLastQuestion = "";
    private String quizQueryQuestion = "";
    private String quizQueryResAnswer = null;
    private boolean quizQueryPending = false;
    private String quizSelectedAnswer = "";
    private long quizAnswerTime = 0;
    private long quizCooldownUntil = 0;

    private java.util.Set<Integer> quizIgnoredNpcIds = new java.util.HashSet<Integer>();
    private int quizClickStartRetry = 0;

    public String startQuiz(int npcId) {
        if (!reflectionReady) initReflection();
        stopCurrentActivity();
        this.quizNpcId = npcId;
        this.quizStep = QUIZ_STEP_MOVE_NPC;
        this.quizNextTime = 0;
        this.quizLastQuestion = "";
        this.quizQueryQuestion = "";
        this.quizQueryResAnswer = null;
        this.quizQueryPending = false;
        this.quizSelectedAnswer = "";
        this.quizIgnoredNpcIds.clear();
        this.quizClickStartRetry = 0;
        log("Auto Quiz NPC: bat dau");
        pushQuizStatus("Bat dau Auto Quiz NPC...");
        return "da bat dau Auto Quiz NPC";
    }

    public void stopQuiz() {
        if (quizStep > 0) {
            log("Auto Quiz NPC: dung");
            pushQuizStatus("Da dung Auto Quiz NPC");
        }
        this.quizStep = QUIZ_STEP_IDLE;
    }

    public void onQuizQueryRes(String question, String correctAnswer) {
        if (quizStep == QUIZ_STEP_WAIT_QUERY_RES) {
            this.quizQueryResAnswer = correctAnswer;
            this.quizQueryPending = false;
        }
    }

    private void pushQuizQuery(String question) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"quiz_query\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"question\":\"" + escapeJson(question) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushQuizQuery error: " + e.getMessage());
        }
    }

    private void pushQuizRecordCorrect(String question, String answer) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"quiz_record_correct\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"question\":\"" + escapeJson(question) + "\""
                    + ",\"answer\":\"" + escapeJson(answer) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushQuizRecordCorrect error: " + e.getMessage());
        }
    }

    private void pushQuizStatus(String detail) {
        try {
            java.io.PrintWriter w = Auto.getWriter();
            if (w == null) return;
            w.print("{\"type\":\"quiz_status\",\"username\":\"" + escapeJson(Auto.getUsername()) + "\""
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}\n");
            w.flush();
        } catch (Exception e) {
            log("pushQuizStatus error: " + e.getMessage());
        }
    }

    private void tickQuiz(long now) {
        if (now < quizNextTime) return;

        switch (quizStep) {
            case QUIZ_STEP_MOVE_NPC: {
                int[] dlg = detectDialog();
                if (dlg != null && dlg[0] >= 0 && !quizIgnoredNpcIds.contains(Integer.valueOf(dlg[0]))) {
                    this.quizNpcId = dlg[0];
                    quizStep = QUIZ_STEP_CLICK_START;
                    quizNextTime = now + 300;
                    return;
                }
                int targetNpc = quizNpcId;
                int nx = -1, ny = -1;
                if (targetNpc <= 0 || quizIgnoredNpcIds.contains(Integer.valueOf(targetNpc))) {
                    int[] npcData = findNpcByName("Tsunade", quizIgnoredNpcIds);
                    if (npcData == null) npcData = findNpcByName("Câu hỏi", quizIgnoredNpcIds);
                    if (npcData == null) npcData = findNpcByName("Event", quizIgnoredNpcIds);
                    if (npcData == null) npcData = findNpcByName("Sự kiện", quizIgnoredNpcIds);
                    if (npcData == null) npcData = findNpcByName("Npc", quizIgnoredNpcIds);
                    if (npcData != null) {
                        targetNpc = npcData[0];
                        nx = npcData[1];
                        ny = npcData[2];
                    }
                } else {
                    Object npcObj = findNpcOnMap(targetNpc);
                    if (npcObj != null && frFieldAr != null && frFieldAs != null) {
                        try {
                            nx = frFieldAr.getShort(npcObj);
                            ny = frFieldAs.getShort(npcObj);
                        } catch (Exception ignore) {}
                    }
                }
                if (targetNpc > 0) {
                    this.quizNpcId = targetNpc;
                    if (nx > 0 && ny > 0) {
                        try {
                            int curX = getPlayerX();
                            int curY = getPlayerY();
                            double dist = Math.hypot(nx - curX, ny - curY);
                            if (dist > NPC_INTERACT_RANGE) {
                                navigateTo(getCurrentMapId(), nx, ny);
                                quizNextTime = now + 1000;
                                return;
                            }
                        } catch (Exception ignore) {}
                    }
                }
                quizStep = QUIZ_STEP_OPEN_NPC;
                quizNextTime = now + 400;
                break;
            }

            case QUIZ_STEP_OPEN_NPC: {
                if (quizNpcId > 0) {
                    try { sendOpenNpc(quizNpcId); } catch (Exception ignore) {}
                }
                quizStep = QUIZ_STEP_CLICK_START;
                quizNextTime = now + 800;
                break;
            }

            case QUIZ_STEP_CLICK_START: {
                String[] menu = readDialogMenuItems();
                if (menu != null && menu.length > 0) {
                    int startIdx = findMenuIndexByKeyword(menu, "bắt đầu");
                    if (startIdx < 0) startIdx = findMenuIndexByKeyword(menu, "tra loi");
                    if (startIdx < 0) startIdx = findMenuIndexByKeyword(menu, "bat dau");
                    if (startIdx < 0) startIdx = findMenuIndexByKeyword(menu, "câu hỏi");
                    if (startIdx >= 0) {
                        try { sendSelectMenu(quizNpcId, startIdx); } catch (Exception ignore) {}
                        quizStep = QUIZ_STEP_READ_QUESTION;
                        quizNextTime = now + 800;
                        quizClickStartRetry = 0;
                        return;
                    }
                }
                String qText = readDialogQuestionText();
                if (qText != null && !qText.trim().isEmpty()) {
                    quizStep = QUIZ_STEP_READ_QUESTION;
                    quizNextTime = now + 300;
                    quizClickStartRetry = 0;
                    return;
                }

                quizClickStartRetry++;
                if (quizClickStartRetry >= 3) {
                    log("Quiz: Open NPC " + quizNpcId + " doesn't have Quiz menu option! Ignoring this NPC and searching next...");
                    pushQuizStatus("Mở nhầm NPC Tsunade cũ " + quizNpcId + " (không có nút trả lời). Đang thử NPC Tsunade Sự kiện...");
                    quizIgnoredNpcIds.add(Integer.valueOf(quizNpcId));
                    quizNpcId = -1;
                    quizClickStartRetry = 0;
                    closeCurrentDialog();
                    closeAnyDialog();
                    quizStep = QUIZ_STEP_MOVE_NPC;
                    quizNextTime = now + 500;
                    return;
                }
                quizNextTime = now + 500;
                break;
            }

            case QUIZ_STEP_READ_QUESTION: {
                String qText = readDialogQuestionText();
                if (qText == null || qText.trim().isEmpty()) {
                    quizNextTime = now + 400;
                    return;
                }

                if (!quizLastQuestion.isEmpty() && !quizLastQuestion.equalsIgnoreCase(qText) && !quizSelectedAnswer.isEmpty()) {
                    log("Quiz: Question changed! Recording correct answer: " + quizLastQuestion + " -> " + quizSelectedAnswer);
                    pushQuizRecordCorrect(quizLastQuestion, quizSelectedAnswer);
                    quizSelectedAnswer = "";
                }

                quizQueryQuestion = qText;
                pushQuizQuery(qText);
                quizQueryPending = true;
                quizQueryResAnswer = null;
                quizAnswerTime = now;
                quizStep = QUIZ_STEP_WAIT_QUERY_RES;
                quizNextTime = now + 200;
                break;
            }

            case QUIZ_STEP_WAIT_QUERY_RES: {
                if (quizQueryPending && now < quizAnswerTime + 3000) {
                    return;
                }

                String[] options = readDialogMenuItems();
                if (options == null || options.length == 0) {
                    quizNextTime = now + 400;
                    return;
                }

                // CASE 1: DB Hit
                if (quizQueryResAnswer != null && !quizQueryResAnswer.trim().isEmpty()) {
                    int matchIdx = findMenuIndexByKeyword(options, quizQueryResAnswer);
                    if (matchIdx >= 0) {
                        log("Quiz: DB Hit! Selecting answer [" + matchIdx + "]: " + options[matchIdx]);
                        pushQuizStatus("Tự động chọn đáp án từ DB: " + options[matchIdx]);
                        quizLastQuestion = quizQueryQuestion;
                        quizSelectedAnswer = options[matchIdx];
                        quizAnswerTime = now;
                        try { sendSelectMenu(quizNpcId, matchIdx); } catch (Exception ignore) {}
                        quizStep = QUIZ_STEP_CHECK_TRANSITION;
                        quizNextTime = now + 800;
                        return;
                    }
                }

                // CASE 2: DB Miss -> Wait for human input
                log("Quiz: DB Miss! Waiting for user manual click...");
                pushQuizStatus("Chờ người dùng chọn đáp án cho: " + quizQueryQuestion);
                quizLastQuestion = quizQueryQuestion;
                quizSelectedAnswer = "";
                quizStep = QUIZ_STEP_WAIT_HUMAN;
                quizNextTime = now + 400;
                break;
            }

            case QUIZ_STEP_WAIT_HUMAN: {
                String confirmText = readConfirmPopupText();
                if (confirmText != null && (confirmText.contains("30") || confirmText.toLowerCase().contains("cho") || confirmText.toLowerCase().contains("phat"))) {
                    log("Quiz: Penalty popup detected: " + confirmText);
                    quizStep = QUIZ_STEP_ESC_POPUP;
                    quizNextTime = now + 200;
                    return;
                }

                String curQ = readDialogQuestionText();
                if (curQ != null && !curQ.trim().isEmpty() && !curQ.equalsIgnoreCase(quizLastQuestion)) {
                    quizStep = QUIZ_STEP_READ_QUESTION;
                    quizNextTime = now + 200;
                    return;
                }

                quizNextTime = now + 400;
                break;
            }

            case QUIZ_STEP_CHECK_TRANSITION: {
                String confirmText = readConfirmPopupText();
                if (confirmText != null && (confirmText.contains("30") || confirmText.toLowerCase().contains("cho") || confirmText.toLowerCase().contains("phat"))) {
                    log("Quiz: Wrong answer selected! Penalty popup: " + confirmText);
                    quizStep = QUIZ_STEP_ESC_POPUP;
                    quizNextTime = now + 200;
                    return;
                }

                String curQ = readDialogQuestionText();
                if (curQ != null && !curQ.trim().isEmpty() && !curQ.equalsIgnoreCase(quizLastQuestion)) {
                    quizStep = QUIZ_STEP_READ_QUESTION;
                    quizNextTime = now + 200;
                    return;
                }

                quizNextTime = now + 400;
                break;
            }

            case QUIZ_STEP_ESC_POPUP: {
                log("Quiz: Escaping popup & starting 30s cooldown...");
                closeConfirmPopup();
                closeAnyDialog();
                closeCurrentDialog();
                quizCooldownUntil = now + 30000;
                pushQuizStatus("Dính phạt 30s! Đang đếm lùi cooldown...");
                quizStep = QUIZ_STEP_COOLDOWN;
                quizNextTime = now + 1000;
                break;
            }

            case QUIZ_STEP_COOLDOWN: {
                if (now >= quizCooldownUntil) {
                    log("Quiz: 30s Cooldown done! Restarting NPC interaction...");
                    pushQuizStatus("Hết 30s cooldown! Thao tác lại từ đầu...");
                    quizStep = QUIZ_STEP_MOVE_NPC;
                    quizNextTime = now + 500;
                } else {
                    long remSec = (quizCooldownUntil - now) / 1000;
                    if (remSec % 5 == 0) {
                        pushQuizStatus("Cooldown còn " + remSec + "s...");
                    }
                    quizNextTime = now + 1000;
                }
                break;
            }
        }
    }
}
