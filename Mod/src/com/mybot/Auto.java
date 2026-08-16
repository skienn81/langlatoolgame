package com.mybot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * Auto Bot cho game Làng Lá - Inject vào a.a.create() và a.a.render()
 * 
 * Tính năng:
 * - Nhận username/password/server từ JVM System Properties
 * - Tự động đăng nhập (sử dụng Reflection truy cập class obfuscated)
 * - Kết nối TCP tới Manager Dashboard để báo cáo trạng thái
 * - Đọc dữ liệu game thật (HP, Level, Quest) qua Reflection
 */
public class Auto {
    private static Socket socket;
    private static PrintWriter writer;
    private static BufferedReader reader;
    /** Đã chạy init() chưa — xem chú thích trong init(). */
    private static boolean initialized = false;
    private static boolean isAutoEnabled = false;
    private static String currentQuest = "Đang chờ đăng nhập...";
    private static int hp = 0;
    private static int maxHp = 0;
    private static int level = 0;
    private static String charName = "";
    private static int xp = 0;
    private static String username = "Player_" + (int)(Math.random() * 9000 + 1000);
    private static String password = "";
    private static String serverName = "";
    private static String serverIp   = "";
    private static int    serverPort = -1;
    private static boolean isConnected = false;

    // ── Trạng thái auto login ──────────────────────────────────────────
    private static boolean loginAttempted = false;
    private static boolean loginSuccess = false;
    private static int loginRetryCount = 0;
    private static final int MAX_LOGIN_RETRIES = 5;

    // ── BƯỚC 1 (chẩn đoán popup login) — read-only, chỉ dump ra stdout ──
    private static int loginPopupDumpCount = 0;
    private static long lastPopupDumpTime = 0;
    private static long lastReflectTime = 0;   // throttle initReflection (khỏi spam mỗi frame)
    private static int popupDismissCount = 0;  // BƯỚC 2: số lần đã thử bấm Xác nhận
    private static long lastDismissTime = 0;

    // ── Cached Reflection references ───────────────────────────────────
    private static Object gameInstance = null;       // Instance của a.a (game main)
    private static Object loginManager = null;       // Instance của a.fj (login manager)
    private static Object gameData = null;           // Instance của a.n (game data)
    private static boolean reflectionInitialized = false;

    public static void init() {
        // Chặn khởi tạo lặp. init() spawn thread kết nối Manager nên gọi nhiều lần là
        // sinh thừa thread + đua nhau connect. Bản jar cũ có tới 57 lời gọi tích tụ trong
        // a.a.create() do injector cộng dồn mỗi lần build — cờ này làm chúng vô hại.
        if (initialized) return;
        initialized = true;

        System.out.println("[MyBot] ═══════════════════════════════════════");
        System.out.println("[MyBot] Initializing Auto Bot v2.0...");
        System.out.println("[MyBot] ═══════════════════════════════════════");

        // ── Đọc tham số JVM từ Manager ─────────────────────────────────
        String propUsername = System.getProperty("auto.username");
        String propPassword = System.getProperty("auto.password");
        String propServer   = System.getProperty("auto.server");

        if (propUsername != null && !propUsername.isEmpty()) {
            username = propUsername;
            System.out.println("[MyBot] Username from JVM: " + username);
        } else {
            System.out.println("[MyBot] No JVM username found, using random: " + username);
        }

        if (propPassword != null && !propPassword.isEmpty()) {
            password = propPassword;
            System.out.println("[MyBot] Password received from JVM (length=" + password.length() + ")");
        } else {
            System.out.println("[MyBot] No JVM password found.");
        }

        if (propServer != null && !propServer.isEmpty()) {
            serverName = propServer;
            System.out.println("[MyBot] Server from JVM: " + serverName);
        } else {
            System.out.println("[MyBot] No JVM server found.");
        }

        String propSvIp   = System.getProperty("auto.server.ip");
        String propSvPort = System.getProperty("auto.server.port");
        if (propSvIp != null && !propSvIp.trim().isEmpty()) serverIp = propSvIp.trim();
        try {
            if (propSvPort != null && !propSvPort.trim().isEmpty())
                serverPort = Integer.parseInt(propSvPort.trim());
        } catch (Exception e) { serverPort = -1; }
        System.out.println("[MyBot] Server dich tu JVM: "
                + (serverIp.isEmpty() ? "(khong khai ip)" : serverIp + ":" + serverPort));

        // Đọc AFK map/zone từ JVM properties
        String propAfkMap = System.getProperty("auto.afk.map");
        String propAfkZone = System.getProperty("auto.afk.zone");
        if (propAfkMap != null && !propAfkMap.isEmpty()) {
            try {
                int afkMap = Integer.parseInt(propAfkMap);
                int afkZone = 1;
                if (propAfkZone != null && !propAfkZone.isEmpty()) {
                    afkZone = Integer.parseInt(propAfkZone);
                }
                TaskManager.getInstance().setAfkConfig(afkMap, afkZone);
                System.out.println("[MyBot] AFK config from JVM: map=" + afkMap + " zone=" + afkZone);
            } catch (NumberFormatException e) {
                System.out.println("[MyBot] Invalid AFK config from JVM: " + propAfkMap);
            }
        }

        // Start TCP Client Thread (kết nối tới Manager Dashboard)
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectServer();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // ════════════════════════════════════════════════════════════════════
    // REFLECTION: Khám phá và truy cập các class game
    // ════════════════════════════════════════════════════════════════════

    /**
     * Khởi tạo Reflection - tìm các instance quan trọng trong game.
     * Gọi từ tick() sau khi game đã load xong (delay vài giây).
     */
    private static void initReflection() {
        if (reflectionInitialized) return;
        
        try {
            System.out.println("[MyBot] ── Initializing Reflection ──");

            // 1. Lấy game instance (a.a) - đây là class chính của LibGDX Game
            Class<?> gameClass = Class.forName("a.a");
            System.out.println("[MyBot] Found game class: a.a");

            // Tìm static instance hoặc singleton
            gameInstance = findStaticInstance(gameClass);
            if (gameInstance == null) {
                // Thử lấy từ Gdx.app
                try {
                    Class<?> gdxClass = Class.forName("com.badlogic.gdx.Gdx");
                    Field appField = gdxClass.getDeclaredField("app");
                    appField.setAccessible(true);
                    Object app = appField.get(null);
                    System.out.println("[MyBot] Got Gdx.app: " + (app != null ? app.getClass().getName() : "null"));
                    
                    if (app != null && app.getClass().getName().equals("com.beatdz.langlalau.DesktopLauncher")) {
                        // DesktopLauncher wraps the real game
                        // Try to get the ApplicationListener
                        Class<?> launcherClass = app.getClass();
                        dumpFields(launcherClass, app, "DesktopLauncher");
                    }
                    
                    // Nếu Gdx.app chính là game instance
                    if (app != null) {
                        gameInstance = app;
                    }
                } catch (Exception e) {
                    System.out.println("[MyBot] Cannot get Gdx.app: " + e.getMessage());
                }
            }

            // 2. Tìm login manager (a.fj)
            try {
                Class<?> fjClass = Class.forName("a.fj");
                System.out.println("[MyBot] Found login class: a.fj");
                
                // Tìm static instance
                loginManager = findStaticInstance(fjClass);
                if (loginManager == null) {
                    // Thử tìm trong các field của game instance
                    if (gameInstance != null) {
                        loginManager = findFieldOfType(gameInstance, fjClass);
                    }
                }
                
                if (loginManager != null) {
                    System.out.println("[MyBot] ✅ Got login manager instance!");
                    dumpFields(fjClass, loginManager, "LoginManager(a.fj)");
                    dumpMethods(fjClass, "LoginManager(a.fj)");
                } else {
                    System.out.println("[MyBot] ⚠ Login manager instance is null, will retry...");
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Cannot find a.fj: " + e.getMessage());
            }

            // 3. Tìm game data (a.n)
            try {
                Class<?> nClass = Class.forName("a.n");
                System.out.println("[MyBot] Found data class: a.n");
                gameData = findStaticInstance(nClass);
                if (gameData == null && gameInstance != null) {
                    gameData = findFieldOfType(gameInstance, nClass);
                }
                if (gameData != null) {
                    System.out.println("[MyBot] ✅ Got game data instance!");
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Cannot find a.n: " + e.getMessage());
            }

            reflectionInitialized = (loginManager != null);
            if (!reflectionInitialized) {
                System.out.println("[MyBot] Reflection NOT fully initialized, will retry next tick...");
            }

        } catch (Exception e) {
            System.out.println("[MyBot] Reflection init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tìm static field trả về instance của class đó (singleton pattern).
     */
    private static Object findStaticInstance(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    Object val = f.get(null);
                    if (val != null && clazz.isAssignableFrom(val.getClass())) {
                        System.out.println("[MyBot]   Found static instance in field: " + f.getName());
                        return val;
                    }
                }
            } catch (Exception e) { /* skip */ }
        }
        return null;
    }

    /**
     * Tìm field có type tương ứng trong một object.
     */
    private static Object findFieldOfType(Object obj, Class<?> targetType) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    if (targetType.isAssignableFrom(f.getType())) {
                        Object val = f.get(obj);
                        if (val != null) {
                            System.out.println("[MyBot]   Found field '" + f.getName() + "' of type " + targetType.getSimpleName());
                            return val;
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Dump tất cả fields của một object (để debug).
     */
    private static void dumpFields(Class<?> clazz, Object obj, String label) {
        System.out.println("[MyBot] ── Fields of " + label + " ──");
        for (Field f : clazz.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                String valStr = (val == null) ? "null" : 
                    (val instanceof String) ? "\"" + val + "\"" : 
                    val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val));
                System.out.println("[MyBot]   " + f.getType().getSimpleName() + " " + f.getName() + " = " + valStr);
            } catch (Exception e) {
                System.out.println("[MyBot]   " + f.getName() + " = <error: " + e.getMessage() + ">");
            }
        }
    }

    /**
     * BƯỚC 1 — Chẩn đoán popup chặn login. READ-ONLY: chỉ đọc + in, KHÔNG bấm/gọi gì.
     * Mục tiêu: lôi ra lớp popup + text "Cập nhật dữ liệu..." để Bước 2 khớp đúng chữ ký.
     */
    private static void dumpLoginPopupState() {
        System.out.println("[MyBot] ═══════ DUMP POPUP LOGIN (login dang treo) ═══════");
        // 1. Chủ input hiện tại — popup thường chiếm input khi hiện
        try {
            Object ip = com.badlogic.gdx.Gdx.input.getInputProcessor();
            if (ip != null) {
                System.out.println("[MyBot] InputProcessor = " + ip.getClass().getName());
                dumpFieldsDeep(ip, "InputProcessor", 0);
            } else {
                System.out.println("[MyBot] InputProcessor = null");
            }
        } catch (Throwable t) {
            System.out.println("[MyBot] InputProcessor err: " + t);
        }
        // 2/3/4. Text popup thường là 1 String nằm trong object dialog mà các gốc này
        //    trỏ tới (đệ quy 1 tầng). a.fj hay null ở giai đoạn này → in rõ để biết.
        try {
            if (gameInstance != null) dumpFieldsDeep(gameInstance, "a.a(game)", 0);
            else System.out.println("[MyBot] a.a(game) = null");
        } catch (Throwable t) { System.out.println("[MyBot] a.a dump err: " + t); }
        try {
            if (gameData != null) dumpFieldsDeep(gameData, "a.n(data)", 0);
            else System.out.println("[MyBot] a.n(data) = null");
        } catch (Throwable t) { System.out.println("[MyBot] a.n dump err: " + t); }
        try {
            if (loginManager != null) dumpFieldsDeep(loginManager, "a.fj(login)", 0);
            else System.out.println("[MyBot] a.fj(login) = null (chua tao)");
        } catch (Throwable t) { System.out.println("[MyBot] a.fj dump err: " + t); }
        // 5. Chồng panel fk.an (popup = 1 bd trên đó) + các nút bb (class + toạ độ)
        dumpPanelStack();
        System.out.println("[MyBot] ═══════ HET DUMP POPUP ═══════");
    }

    /** Dump chồng panel fk.an = a.n.a().a.an và các nút bb (ar,as,au,av) để chọn đúng
     *  nút "Xác nhận" cho Bước 2. READ-ONLY. */
    private static void dumpPanelStack() {
        try {
            Class<?> nCls = Class.forName("a.n");
            Object nInst = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == nCls) {
                    m.setAccessible(true); nInst = m.invoke(null); break;
                }
            }
            if (nInst == null) { System.out.println("[MyBot] PANEL: n.a() = null"); return; }
            // a.n có NHIỀU field tên "a" (int, fk_0[], fk...) → phải chọn cái ĐÚNG KIỂU a.fk
            Class<?> fkCls = Class.forName("a.fk");
            Object fk = fieldByNameType(nInst, "a", fkCls);
            if (fk == null) { System.out.println("[MyBot] PANEL: fk (n.a kieu fk) = null"); return; }
            System.out.println("[MyBot] PANEL: fk = " + fk.getClass().getName());
            java.util.Vector<?> an = (java.util.Vector<?>) getUp(fk, "an");   // an khai báo ở base a.fk
            System.out.println("[MyBot] PANEL: fk.an so panel = " + (an == null ? "null" : an.size()));
            if (an == null) return;
            for (int i = 0; i < an.size(); i++) {
                Object bd = an.elementAt(i);
                System.out.println("[MyBot]   panel[" + i + "] = " + bd.getClass().getName());
                java.util.Vector<?> ae = (java.util.Vector<?>) getUp(bd, "ae");  // ae ở base a.bd
                System.out.println("[MyBot]     ae (nut) = " + (ae == null ? "null" : ae.size()));
                if (ae == null) continue;
                for (int j = 0; j < ae.size(); j++) {
                    Object bb = ae.elementAt(j);
                    System.out.println("[MyBot]       bb[" + j + "] = " + bb.getClass().getName()
                            + " ar=" + ri(bb, "ar") + " as=" + ri(bb, "as")
                            + " au=" + ri(bb, "au") + " av=" + ri(bb, "av")
                            + " a=" + (rf(bb, "a") == null ? "null" : "OBJ"));
                }
            }
        } catch (Throwable t) {
            System.out.println("[MyBot] PANEL dump err: " + t);
        }
    }

    /** BƯỚC 2 — Tự bấm nút "Xác nhận" của popup cập nhật dữ liệu. CÓ ĐIỀU KIỆN:
     *  chỉ khi đang treo pre-login VÀ có panel với ĐÚNG 1 nút (popup xác nhận đơn).
     *  Không thấy popup → không làm gì (tự-lành nếu game bỏ lớp này). Bấm = gọi thẳng
     *  panel.b(nut.ar, nut.e) — đúng thứ by.l() làm khi nhấn, khỏi cần toạ độ. */
    private static void tryDismissUpdatePopup() {
        try {
            Class<?> nCls = Class.forName("a.n");
            Object nInst = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == nCls) {
                    m.setAccessible(true); nInst = m.invoke(null); break;
                }
            }
            if (nInst == null) return;
            Object fk = fieldByNameType(nInst, "a", Class.forName("a.fk"));
            if (fk == null) return;
            java.util.Vector<?> an = (java.util.Vector<?>) getUp(fk, "an");
            if (an == null || an.isEmpty()) return;
            Class<?> bdCls = Class.forName("a.bd");
            // Duyệt từ TRÊN xuống, panel đầu tiên CÓ nút = popup
            for (int i = an.size() - 1; i >= 0; i--) {
                Object panel = an.elementAt(i);
                java.util.Vector<?> ae = (java.util.Vector<?>) getUp(panel, "ae");
                if (ae == null || ae.isEmpty()) continue;
                // An toàn: chỉ tự bấm khi popup có ĐÚNG 1 nút. Nhiều nút → không đoán, bỏ qua.
                if (ae.size() != 1) {
                    System.out.println("[MyBot] BƯỚC2: popup " + panel.getClass().getName()
                            + " có " + ae.size() + " nút — bỏ qua cho an toàn.");
                    return;
                }
                Object bb = ae.elementAt(0);
                int cmd = getIntUp(bb, "ar", Integer.MIN_VALUE);   // by.ar = mã lệnh
                Object data = getUp(bb, "e");                      // by.e  = data lệnh
                Object parent = fieldByNameType(bb, "c", bdCls);   // nút.c = panel cha (bd)
                if (parent == null) parent = panel;
                if (cmd == Integer.MIN_VALUE) { System.out.println("[MyBot] BƯỚC2: khong doc duoc cmd nut"); return; }
                // PHẢI gọi bản 3 THAM SỐ b(int,Object,be) — bản 2 tham số rỗng (no-op)!
                // Đúng thứ by.a() làm: this.c.b(di_0.ar, this.e, this) với arg3 = chính nút.
                Class<?> beCls = Class.forName("a.be");
                Method mb = findMethod(parent.getClass(), "b", int.class, Object.class, beCls);
                if (mb == null) { System.out.println("[MyBot] BƯỚC2: khong thay bd.b(int,Object,be)"); return; }
                mb.invoke(parent, cmd, data, bb);
                System.out.println("[MyBot] ✅ BƯỚC2: da bam Xac nhan (panel=" + panel.getClass().getName()
                        + " nut=" + bb.getClass().getName() + " cmd=" + cmd + ")");
                return;
            }
        } catch (Throwable t) {
            System.out.println("[MyBot] BƯỚC2 dismiss err: " + t);
        }
    }

    /** tìm Method theo tên + tham số, đi ngược superclass */
    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException e) { /* lên lớp cha */ }
        }
        return null;
    }
    /** đọc int field (đi ngược superclass), trả dflt nếu lỗi */
    private static int getIntUp(Object o, String name, int dflt) {
        Field f = fieldUp(o.getClass(), name);
        if (f == null) return dflt;
        try { return f.getInt(o); } catch (Throwable t) { return dflt; }
    }

    /** tìm Field theo tên, đi ngược cả superclass (field có thể ở lớp cha) */
    private static Field fieldUp(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException e) { /* lên lớp cha */ }
        }
        return null;
    }
    /** lấy giá trị field theo tên (đi ngược superclass) */
    private static Object getUp(Object o, String name) {
        Field f = fieldUp(o.getClass(), name);
        if (f == null) return null;
        try { return f.get(o); } catch (Throwable t) { return null; }
    }
    /** chọn field theo tên VÀ kiểu — xử lý ca nhiều field trùng tên khác kiểu (obfuscation) */
    private static Object fieldByNameType(Object o, String name, Class<?> type) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name) && type.isAssignableFrom(f.getType())) {
                    try { f.setAccessible(true); return f.get(o); } catch (Throwable t) { /* thử tiếp */ }
                }
            }
        }
        return null;
    }
    /** đọc int field (đi ngược superclass; "?" nếu lỗi) */
    private static String ri(Object o, String name) {
        Field f = fieldUp(o.getClass(), name);
        if (f == null) return "?";
        try { return String.valueOf(f.getInt(o)); } catch (Throwable t) { return "?"; }
    }
    /** đọc object field (đi ngược superclass; null nếu lỗi) */
    private static Object rf(Object o, String name) {
        return getUp(o, name);
    }

    /** Dump String của obj; ở tầng 0 còn đệ quy 1 tầng vào các field-object lớp game
     *  (a.*) để lôi String bên trong — nơi text popup nhiều khả năng nằm. */
    private static void dumpFieldsDeep(Object obj, String label, int depth) {
        if (obj == null || depth > 1) return;
        Class<?> c = obj.getClass();
        System.out.println("[MyBot] -- " + label + " : " + c.getName() + " --");
        for (Field f : c.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.length() > 0)
                        System.out.println("[MyBot]   String " + f.getName() + " = \"" + s + "\"");
                } else if (depth == 0 && v.getClass().getName().startsWith("a.")
                           && !(v instanceof java.util.Vector) && !v.getClass().isArray()) {
                    System.out.println("[MyBot]   -> " + f.getName() + " : " + v.getClass().getName());
                    dumpFieldsDeep(v, "    " + f.getName(), depth + 1);
                }
            } catch (Throwable t) { /* bỏ qua field lỗi */ }
        }
    }

    /**
     * Dump tất cả methods của một class (để debug).
     */
    private static void dumpMethods(Class<?> clazz, String label) {
        System.out.println("[MyBot] ── Methods of " + label + " ──");
        for (Method m : clazz.getDeclaredMethods()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[MyBot]   ").append(m.getReturnType().getSimpleName());
            sb.append(" ").append(m.getName()).append("(");
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(")");
            System.out.println(sb.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // AUTO LOGIN
    // ════════════════════════════════════════════════════════════════════

    /**
     * Thực hiện đăng nhập tự động bằng Reflection.
     * Chiến lược:
     * 1. Tìm text field username/password trong login manager
     * 2. Gán giá trị
     * 3. Gọi method login
     */
    /** Bỏ chú thích trong ngoặc ở đuôi tên server: "S14(10/06 OPEN)" → "s14".
     *  Nhà phát hành gắn/gỡ chú thích ngày mở bất cứ lúc nào, so nguyên văn là trượt. */
    private static String khoaTenServer(String ten) {
        if (ten == null) return "";
        String s = ten.trim();
        int cuoiNhom = s.indexOf("] ");
        if (cuoiNhom >= 0) s = s.substring(cuoiNhom + 2);
        int moNgoac = s.indexOf('(');
        if (moNgoac >= 0) s = s.substring(0, moNgoac);
        return s.trim().toLowerCase();
    }

    /**
     * Chọn server ĐÍCH trước khi login, theo ip:port Manager truyền vào.
     *
     * Vì sao phải tự chọn: client không tự nhớ được server. Nó đọc file
     * animesoft/1/arr_server.beatdz ("ip:port") rồi dò trong danh sách server TỰ TẢI của
     * chính nó (a.n.a().a) — dò trượt thì im lặng giữ nguyên server mặc định, mà mặc định
     * lại là server mới mở nhất. Ngày 07/08 nhà phát hành mở S15(13/08 TEST) là cả 18 nick
     * bị đẩy sang đó, báo "Thông tin tài khoản hoặc mật khẩu không chính xác" vì nhân vật
     * không tồn tại bên server test. Ghi file trước khi mở client cũng không cứu được:
     * client chỉ đọc file đó MỘT LẦN, và dò trượt thì bỏ qua luôn.
     *
     * Nên ở đây gán thẳng a.n.a().b = đúng server rồi mới để fj.Q() lấy ip/port từ đó.
     * In cả danh sách server client đang thấy — trượt lần sau là có bằng chứng ngay,
     * khỏi đoán.
     *
     * @return true nếu đã chọn được đúng server.
     */
    private static boolean chonServerDich() {
        if (serverIp.isEmpty() || serverPort <= 0) {
            System.out.println("[MyBot] SERVER: Manager khong truyen ip/port -> giu server mac dinh cua client");
            return false;
        }
        try {
            Class<?> nCls = Class.forName("a.n");
            Object nInst = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == nCls) {
                    m.setAccessible(true); nInst = m.invoke(null); break;
                }
            }
            if (nInst == null) {
                System.out.println("[MyBot] SERVER: khong lay duoc a.n.a() -> giu server mac dinh");
                return false;
            }

            Class<?> fbCls = Class.forName("a.fB");   // một server: l=ten, ae=ip, ai=port

            // a.n có nhiều field tên "a"; lấy đúng cái là MẢNG NHÓM — nhận ra bằng việc
            // lớp phần tử của nó có một field mảng fB (danh sách server của nhóm).
            Object[] nhoms = null;
            for (Field f : nCls.getDeclaredFields()) {
                if (!f.getType().isArray()) continue;
                Class<?> comp = f.getType().getComponentType();
                if (comp.isPrimitive()) continue;
                boolean coDsServer = false;
                for (Field g : comp.getDeclaredFields()) {
                    if (g.getType().isArray() && g.getType().getComponentType() == fbCls) { coDsServer = true; break; }
                }
                if (!coDsServer) continue;
                f.setAccessible(true);
                Object v = f.get(nInst);
                if (v instanceof Object[]) { nhoms = (Object[]) v; break; }
            }
            if (nhoms == null || nhoms.length == 0) {
                System.out.println("[MyBot] SERVER: client chua tai xong danh sach server -> giu server mac dinh");
                return false;
            }

            Object dungIpPort = null, dungTen = null;
            String khoaMuon = khoaTenServer(serverName);
            StringBuilder ds = new StringBuilder();

            for (Object nhom : nhoms) {
                if (nhom == null) continue;
                Object dsSv = null;
                for (Field g : nhom.getClass().getDeclaredFields()) {
                    if (g.getType().isArray() && g.getType().getComponentType() == fbCls) {
                        g.setAccessible(true); dsSv = g.get(nhom); break;
                    }
                }
                if (!(dsSv instanceof Object[])) continue;
                for (Object sv : (Object[]) dsSv) {
                    if (sv == null) continue;
                    Object ip  = getUp(sv, "ae");
                    Object ten = getUp(sv, "l");
                    String cong = ri(sv, "ai");
                    ds.append("\n[MyBot]    ").append(ten).append("  ").append(ip).append(":").append(cong);
                    if (ip instanceof String && ((String) ip).equalsIgnoreCase(serverIp)
                            && String.valueOf(serverPort).equals(cong)) {
                        if (dungIpPort == null) dungIpPort = sv;
                    }
                    if (dungTen == null && khoaMuon.length() > 0 && ten instanceof String
                            && khoaTenServer((String) ten).equals(khoaMuon)) {
                        dungTen = sv;
                    }
                }
            }

            Object chon = dungIpPort != null ? dungIpPort : dungTen;
            if (chon == null) {
                System.out.println("[MyBot] ❌ SERVER: khong thay server " + serverIp + ":" + serverPort
                        + " (\"" + serverName + "\") trong danh sach client dang co:" + ds);
                return false;
            }

            // Setter a.n.a(fB): gán server đang chọn VÀ ghi lai arr_server cho lan sau.
            Method dat = null;
            for (Method m : nCls.getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == fbCls) { dat = m; break; }
            }
            if (dat == null) {
                System.out.println("[MyBot] ❌ SERVER: khong tim thay setter a.n.a(fB) -> giu server mac dinh");
                return false;
            }
            dat.setAccessible(true);
            dat.invoke(nInst, chon);

            System.out.println("[MyBot] ✅ SERVER: da chon \"" + getUp(chon, "l") + "\" "
                    + getUp(chon, "ae") + ":" + ri(chon, "ai")
                    + (dungIpPort == null ? " (khop theo TEN, ip/port khai trong Manager khong khop)" : ""));
            return true;
        } catch (Throwable t) {
            System.out.println("[MyBot] ❌ SERVER: loi khi chon server: " + t);
            return false;
        }
    }

    private static void attemptAutoLogin() {
        if (loginAttempted && loginRetryCount >= MAX_LOGIN_RETRIES) return;
        if (username.isEmpty() || password.isEmpty()) {
            System.out.println("[MyBot] Cannot auto-login: username or password is empty");
            loginAttempted = true;
            return;
        }

        loginAttempted = true;
        loginRetryCount++;
        System.out.println("[MyBot] ═══ Auto Login Attempt #" + loginRetryCount + " ═══");

        try {
            Class<?> fjClass = Class.forName("a.fj");

            if (loginManager != null) {
                // ═══════════════════════════════════════════════════════════
                // Flow login dựa trên decompiled source a.fj:
                // 1. fj.Q() → set server IP/port từ selected fB (server info)
                // 2. eH.c(username) → set username vào UI text field
                // 3. fY.c(password) → set password vào UI text field  
                // 4. fj.h() → trigger login thread (kiểm tra mạng + kết nối)
                // ═══════════════════════════════════════════════════════════

                Class<?> fjClass2 = loginManager.getClass();

                // Bước 0: chốt server đích TRƯỚC — fj.Q() chỉ chép ip/port ra từ
                // a.n.a().b, nên chọn sai ở đây là login thẳng vào server khác.
                chonServerDich();

                // Bước 1: Gọi fj.Q() để set server IP/port
                System.out.println("[MyBot] Step 1: Calling fj.Q() to set server...");
                try {
                    Method qMethod = fjClass2.getDeclaredMethod("Q");
                    qMethod.setAccessible(true);
                    qMethod.invoke(null); // Q() là static method
                    System.out.println("[MyBot] ✅ fj.Q() called - server IP/port set");
                } catch (Exception e) {
                    System.out.println("[MyBot] ❌ fj.Q() failed: " + e.getMessage());
                }

                // Bước 2: Set username/password dùng fj.c(String) và trực tiếp fY
                System.out.println("[MyBot] Step 2: Setting username/password...");
                try {
                    // Dùng fj.c(String) để set username (đây là wrapper chính thức)
                    // fj.c(String) sẽ: eH.c(username) + fM.b(1000) + fY.c("")
                    // Nhưng nó cũng clear password, nên ta set password SAU
                    Method setUsernameMethod = fjClass2.getDeclaredMethod("c", String.class);
                    setUsernameMethod.setAccessible(true);
                    setUsernameMethod.invoke(loginManager, username);
                    System.out.println("[MyBot] ✅ Username set via fj.c(): " + username);
                    
                    // Set password vào field fY g
                    Object passwordField = null;
                    for (Field f : fjClass2.getDeclaredFields()) {
                        f.setAccessible(true);
                        if (f.getType().getSimpleName().equals("fY") && passwordField == null) {
                            passwordField = f.get(loginManager);
                        }
                    }
                    if (passwordField != null) {
                        // fY.c(String) set text
                        Method cMethod = null;
                        Class<?> cls = passwordField.getClass();
                        while (cls != null && cls != Object.class) {
                            try {
                                cMethod = cls.getDeclaredMethod("c", String.class);
                                cMethod.setAccessible(true);
                                break;
                            } catch (NoSuchMethodException nsme) {
                                cls = cls.getSuperclass();
                            }
                        }
                        if (cMethod != null) {
                            cMethod.invoke(passwordField, password);
                            System.out.println("[MyBot] ✅ Password set (length=" + password.length() + ")");
                        }
                    }

                    // Verify username via eH.a() and fY.a()  
                    Object loginPanel = null;
                    for (Field f : fjClass2.getDeclaredFields()) {
                        f.setAccessible(true);
                        if (f.getType().getSimpleName().equals("eH")) {
                            loginPanel = f.get(loginManager);
                            break;
                        }
                    }
                    if (loginPanel != null) {
                        try {
                            // a() might be in parent class, use getMethod
                            Method getter = null;
                            Class<?> cls2 = loginPanel.getClass();
                            while (cls2 != null && cls2 != Object.class) {
                                for (Method m : cls2.getDeclaredMethods()) {
                                    if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                                        m.setAccessible(true);
                                        getter = m;
                                        break;
                                    }
                                }
                                if (getter != null) break;
                                cls2 = cls2.getSuperclass();
                            }
                            if (getter != null) {
                                String result = (String) getter.invoke(loginPanel);
                                System.out.println("[MyBot] Verify eH username: \"" + result + "\"");
                            }
                        } catch (Exception e) {
                            System.out.println("[MyBot] Verify username error: " + e.getMessage());
                        }
                    }
                    if (passwordField != null) {
                        try {
                            Method getter = null;
                            Class<?> cls2 = passwordField.getClass();
                            while (cls2 != null && cls2 != Object.class) {
                                for (Method m : cls2.getDeclaredMethods()) {
                                    if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                                        m.setAccessible(true);
                                        getter = m;
                                        break;
                                    }
                                }
                                if (getter != null) break;
                                cls2 = cls2.getSuperclass();
                            }
                            if (getter != null) {
                                String result = (String) getter.invoke(passwordField);
                                System.out.println("[MyBot] Verify fY password: \"" + (result != null ? "(len=" + result.length() + ")" : "null") + "\"");
                            }
                        } catch (Exception e) {
                            System.out.println("[MyBot] Verify password error: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[MyBot] Set username/password failed: " + e.getMessage());
                    e.printStackTrace();
                }

                // Bước 2b: Kiểm tra fM.w() (internet check)
                System.out.println("[MyBot] Step 2b: Checking fM.w() (internet)...");
                try {
                    Class<?> fmClass = Class.forName("a.fM");
                    Method wMethod = fmClass.getDeclaredMethod("w");
                    wMethod.setAccessible(true);
                    boolean hasInternet = (Boolean) wMethod.invoke(null);
                    System.out.println("[MyBot] fM.w() = " + hasInternet + (hasInternet ? " ✅" : " ❌ NO INTERNET!"));
                } catch (Exception e) {
                    System.out.println("[MyBot] fM.w() check failed: " + e.getMessage());
                }

                // Bước 3: Gọi fj.h() — trigger login thread
                // fm.aH() bên trong ef thread sẽ TỰ ĐỘNG gọi n.P() (connect TCP) nếu cần
                // KHÔNG gọi n.P() riêng vì sẽ set fC.bk=true → aH() skip connect!
                System.out.println("[MyBot] Step 3: Calling fj.h() to trigger login...");
                try {
                    Method hMethod = fjClass2.getDeclaredMethod("h");
                    hMethod.setAccessible(true);
                    hMethod.invoke(loginManager);
                    System.out.println("[MyBot] ✅ fj.h() called! ef thread → aH() → n.P() → connect → send login!");
                } catch (Exception e) {
                    System.out.println("[MyBot] ❌ fj.h() failed: " + e.getMessage());
                    e.printStackTrace();
                }
                
                // Chờ 3s cho login thread hoàn thành connect + gửi packet
                System.out.println("[MyBot] Waiting 3s for login thread to connect...");
                try { Thread.sleep(3000); } catch (InterruptedException ex) {}
                
                // Check fC state
                try {
                    Class<?> fcClass = Class.forName("a.fC");
                    Method fcGetter = null;
                    for (Method m : fcClass.getDeclaredMethods()) {
                        if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == fcClass) {
                            fcGetter = m;
                            break;
                        }
                    }
                    if (fcGetter != null) {
                        fcGetter.setAccessible(true);
                        Object fcInstance = fcGetter.invoke(null);
                        if (fcInstance != null) {
                            System.out.println("[MyBot] fC instance: EXISTS");
                            // Dump key fields: bk (connected), bm, bl, am()
                            for (Field f : fcClass.getDeclaredFields()) {
                                f.setAccessible(true);
                                String name = f.getName();
                                if (name.equals("bk") || name.equals("bl") || name.equals("bm") || name.equals("bj")) {
                                    System.out.println("[MyBot]   fC." + name + " = " + f.get(fcInstance));
                                }
                            }
                            // Check am() = isConnected/isBusy
                            for (Method m : fcClass.getDeclaredMethods()) {
                                if (m.getName().equals("am") && m.getParameterCount() == 0) {
                                    m.setAccessible(true);
                                    System.out.println("[MyBot]   fC.am() = " + m.invoke(fcInstance));
                                }
                            }
                        } else {
                            System.out.println("[MyBot] fC instance: null (singleton not created)");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[MyBot] fC check: " + e.getMessage());
                }

                loginSuccess = true;
                System.out.println("[MyBot] ✅ Auto login sequence completed!");

            } else {
                System.out.println("[MyBot] loginManager is null, cannot login.");
            }

        } catch (Exception e) {
            System.out.println("[MyBot] Auto login error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tìm và gán username/password vào các String fields của login manager.
     * Dựa trên phân tích: a.fj có các field liên quan đến taikhoan, matkhau.
     */
    private static void setStringFieldsForLogin(Class<?> clazz, Object obj) {
        System.out.println("[MyBot] Searching for String fields to set username/password...");
        int stringFieldCount = 0;
        Field firstStringField = null;
        Field secondStringField = null;

        for (Field f : clazz.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                if (f.getType() == String.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    stringFieldCount++;
                    Object val = f.get(obj);
                    System.out.println("[MyBot]   String field '" + f.getName() + "' = " + val);
                    
                    // Heuristic: field đầu tiên chứa username cũ, field thứ 2 chứa password
                    if (firstStringField == null) {
                        firstStringField = f;
                    } else if (secondStringField == null) {
                        secondStringField = f;
                    }
                    
                    // Nếu field hiện tại chứa username cũ → đây là field username
                    if (val != null && val.toString().contains("langla")) {
                        System.out.println("[MyBot]   >> Looks like username field! Setting to: " + username);
                        f.set(obj, username);
                        firstStringField = f; // Mark as found
                    }
                }
            } catch (Exception e) { /* skip */ }
        }
    }

    /**
     * Tìm và gọi method login trong login manager.
     * Tìm method phù hợp dựa trên signature:
     * - void method(String, String, boolean) - login(user, pass, remember)
     * - void method(String, String) - login(user, pass) 
     * - void method() - doLogin() nếu username/pass đã được set
     */
    private static void callLoginMethod(Class<?> clazz, Object obj) {
        System.out.println("[MyBot] Searching for login method...");
        
        for (Method m : clazz.getDeclaredMethods()) {
            try {
                m.setAccessible(true);
                Class<?>[] params = m.getParameterTypes();
                
                // Pattern 1: method(String, String, boolean) — login(user, pass, remember)
                if (params.length == 3 && params[0] == String.class && params[1] == String.class && params[2] == boolean.class) {
                    System.out.println("[MyBot] Found login method candidate: " + m.getName() + "(String, String, boolean)");
                    System.out.println("[MyBot] Calling with: (" + username + ", *****, true)");
                    m.invoke(obj, username, password, true);
                    System.out.println("[MyBot] ✅ Login method called successfully!");
                    loginSuccess = true;
                    return;
                }
                
                // Pattern 2: method(String, String) — login(user, pass)
                if (params.length == 2 && params[0] == String.class && params[1] == String.class) {
                    System.out.println("[MyBot] Found login method candidate: " + m.getName() + "(String, String)");
                    System.out.println("[MyBot] Calling with: (" + username + ", *****)");
                    m.invoke(obj, username, password);
                    System.out.println("[MyBot] ✅ Login method called successfully!");
                    loginSuccess = true;
                    return;
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Method " + m.getName() + " failed: " + e.getMessage());
            }
        }

        // Pattern 3: Tìm method void không tham số (doLogin/connect style)
        for (Method m : clazz.getDeclaredMethods()) {
            try {
                m.setAccessible(true);
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 0 && m.getReturnType() == void.class) {
                    String name = m.getName();
                    // Bỏ qua các method rõ ràng không phải login
                    if (name.equals("toString") || name.equals("hashCode") || name.equals("notify") || 
                        name.equals("notifyAll") || name.equals("wait") || name.equals("finalize")) continue;
                    
                    System.out.println("[MyBot] Trying void method: " + name + "()");
                    // Chỉ thử gọi nếu đã set username/password vào fields
                }
            } catch (Exception e) { /* skip */ }
        }

        System.out.println("[MyBot] ⚠ No matching login method found in a.fj");
        System.out.println("[MyBot] Will dump all info for manual analysis...");
        dumpMethods(clazz, "a.fj");
    }

    /**
     * Fallback: thử gọi static login methods.
     */
    private static void callStaticLoginMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            try {
                m.setAccessible(true);
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] params = m.getParameterTypes();
                
                if (params.length == 2 && params[0] == String.class && params[1] == String.class) {
                    System.out.println("[MyBot] Found static login method: " + m.getName() + "(String, String)");
                    m.invoke(null, username, password);
                    System.out.println("[MyBot] ✅ Static login called!");
                    loginSuccess = true;
                    return;
                }
            } catch (Exception e) { /* skip */ }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ĐỌC DỮ LIỆU GAME THẬT
    // ════════════════════════════════════════════════════════════════════

    private static boolean gameDataDumped = false;
    private static Field fieldHp = null;
    private static Field fieldMaxHp = null;
    private static Field fieldLevel = null;
    private static Field fieldCharName = null;
    private static Field fieldQuest = null;

    private static boolean gameDataLoggedOnce = false;

    private static Method gameDataGetInstance = null;
    private static boolean gameDataMethodScanned = false;

    private static void readGameData() {
        // Scan 1 lần tìm static method() trả về a.i (giống TaskManager)
        if (!gameDataMethodScanned) {
            gameDataMethodScanned = true;
            try {
                Class<?> iClass = Class.forName("a.i");
                for (Method m : iClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 0
                            && m.getReturnType() == iClass) {
                        gameDataGetInstance = m;
                        gameDataGetInstance.setAccessible(true);
                        System.out.println("[MyBot] readGameData: Found static getInstance: " + m.getName() + "() -> " + iClass.getName());
                        break;
                    }
                }
                if (gameDataGetInstance == null) {
                    System.out.println("[MyBot] readGameData: NO static method returning a.i found!");
                    // Dump all methods for debug
                    for (Method m : iClass.getDeclaredMethods()) {
                        System.out.println("[MyBot]   method: " + m.getName() + " static=" + java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                + " params=" + m.getParameterCount() + " returns=" + m.getReturnType().getName());
                    }
                    // Check superclasses too
                    Class<?> superCls = iClass.getSuperclass();
                    while (superCls != null && superCls != Object.class) {
                        for (Method m : superCls.getDeclaredMethods()) {
                            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                    && m.getParameterCount() == 0) {
                                System.out.println("[MyBot]   super." + superCls.getSimpleName() + "." + m.getName() + "() -> " + m.getReturnType().getName());
                            }
                        }
                        superCls = superCls.getSuperclass();
                    }
                }
            } catch (Exception e) {
                System.out.println("[MyBot] readGameData scan error: " + e.getMessage());
            }
        }

        // Lấy instance
        if (gameDataGetInstance != null) {
            try {
                gameData = gameDataGetInstance.invoke(null);
            } catch (Exception e) {
                gameData = null;
            }
        } else {
            gameData = null;
        }

        if (gameData == null) {
            if (!gameDataLoggedOnce) {
                System.out.println("[MyBot] readGameData: gameData=NULL (a.i.a() returned null)");
            }
            level = 0;
            hp = 0;
            maxHp = 0;
            return;
        }

        try {
            Class<?> iClass = gameData.getClass();

            if (!gameDataLoggedOnce) {
                System.out.println("[MyBot] == readGameData DEBUG ==");
                System.out.println("[MyBot] gameData class: " + iClass.getName());
                // Dump ALL String fields (class + superclasses)
                Class<?> dumpCls = iClass;
                while (dumpCls != null && dumpCls != Object.class) {
                    java.lang.reflect.Field[] fields = dumpCls.getDeclaredFields();
                    for (java.lang.reflect.Field f : fields) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(gameData);
                            if (val instanceof String && !((String) val).isEmpty()) {
                                System.out.println("[MyBot]   " + dumpCls.getSimpleName() + "." + f.getName() + " = \"" + val + "\"");
                            }
                        } catch (Exception ignored) {}
                    }
                    dumpCls = dumpCls.getSuperclass();
                }
            }

            // Đọc tên nhân vật: tìm field 'l' kiểu String (class i có duplicate 'l': boolean + String)
            if (fieldCharName == null) {
                Class<?> cls = iClass;
                while (cls != null && cls != Object.class) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        if (f.getName().equals("l") && f.getType() == String.class) {
                            fieldCharName = f;
                            fieldCharName.setAccessible(true);
                            if (!gameDataLoggedOnce) {
                                System.out.println("[MyBot] fieldCharName found: " + cls.getSimpleName() + ".l (String)");
                            }
                            break;
                        }
                    }
                    if (fieldCharName != null) break;
                    cls = cls.getSuperclass();
                }
            }
            if (fieldCharName != null) {
                Object rawName = fieldCharName.get(gameData);
                if (!gameDataLoggedOnce) {
                    System.out.println("[MyBot] fieldCharName.get() = " + (rawName != null ? "\"" + rawName + "\" type=" + rawName.getClass().getName() : "null"));
                }
                if (rawName instanceof String) {
                    String name = (String) rawName;
                    if (!name.isEmpty()) {
                        charName = name;
                    }
                }
            } else if (!gameDataLoggedOnce) {
                System.out.println("[MyBot] WARNING: field 'l' NOT FOUND!");
            }

            // Đọc Level — tìm method j() trả int (có 2 overload: boolean + int)
            try {
                Method jMethod = null;
                Class<?> cls = iClass;
                while (cls != null && cls != Object.class) {
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("j") && m.getParameterCount() == 0 
                            && m.getReturnType() == int.class) {
                            jMethod = m;
                            jMethod.setAccessible(true);
                            break;
                        }
                    }
                    if (jMethod != null) break;
                    cls = cls.getSuperclass();
                }
                if (jMethod != null) {
                    level = (Integer) jMethod.invoke(gameData);
                    if (!gameDataLoggedOnce) {
                        System.out.println("[MyBot] Level: j()->int = " + level + " from " + jMethod.getDeclaringClass().getName());
                    }
                }
                // Fallback: nếu j() trả 0, thử scan int fields tìm giá trị level hợp lý
                if (level == 0 && !gameDataLoggedOnce) {
                    System.out.println("[MyBot] Level=0, scanning all int methods/fields...");
                    // Dump tất cả int methods không param
                    Class<?> scanCls = iClass;
                    while (scanCls != null && scanCls != Object.class) {
                        for (Method m : scanCls.getDeclaredMethods()) {
                            if (m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                                try {
                                    m.setAccessible(true);
                                    int val = (Integer) m.invoke(gameData);
                                    if (val > 0 && val < 200) {  // Level hợp lý: 1-200
                                        System.out.println("[MyBot]   " + scanCls.getSimpleName() + "." + m.getName() + "() = " + val + " <- possible level?");
                                    }
                                } catch (Exception ignore) {}
                            }
                        }
                        scanCls = scanCls.getSuperclass();
                    }
                }
            } catch (Exception e) {
                if (!gameDataLoggedOnce) {
                    System.out.println("[MyBot] Level read ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("[MyBot] Level read CAUSE: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                    }
                }
                level = 0;
            }

            // Đọc HP
            if (fieldHp == null) {
                Class<?> cls = iClass;
                while (cls != null) {
                    try {
                        fieldHp = cls.getDeclaredField("y");
                        fieldHp.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
            }
            if (fieldHp != null) {
                hp = fieldHp.getInt(gameData);
            }

            // Đọc Max HP
            if (fieldMaxHp == null) {
                Class<?> cls = iClass;
                while (cls != null) {
                    try {
                        fieldMaxHp = cls.getDeclaredField("A");
                        fieldMaxHp.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        cls = cls.getSuperclass();
                    }
                }
            }
            if (fieldMaxHp != null) {
                maxHp = fieldMaxHp.getInt(gameData);
            }

            if (!gameDataLoggedOnce) {
                System.out.println("[MyBot] RESULT: charName=\"" + charName + "\" level=" + level + " hp=" + hp + "/" + maxHp);
                gameDataLoggedOnce = true;
            }

        } catch (Exception e) {
            System.out.println("[MyBot] readGameData error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // TCP CLIENT (KẾT NỐI MANAGER)
    // ════════════════════════════════════════════════════════════════════

    private static void connectServer() {
        int retries = 0;
        while (retries < 10) {
            try {
                System.out.println("[MyBot] Connecting to Manager Server at 127.0.0.1:9090...");
                socket = new Socket("127.0.0.1", 9090);
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                isConnected = true;
                System.out.println("[MyBot] Connected to Manager Server successfully!");
                // Không dùng listener thread nữa - đọc TCP trong tick() non-blocking
                break;
            } catch (Exception e) {
                System.out.println("[MyBot] Connect failed: " + e.getMessage() + ". Retrying in 3 seconds...");
                retries++;
                try { Thread.sleep(3000); } catch (InterruptedException ex) {}
            }
        }
    }

    private static void processCommand(String line) {
        if (line.contains("\"command\":\"start_auto\"")) {
            isAutoEnabled = true;
            TaskManager.getInstance().setEnabled(true);
            System.out.println("[MyBot] ✅ Auto + Auto NV Enabled!");
        } else if (line.contains("\"command\":\"stop_auto\"")) {
            isAutoEnabled = false;
            // Phải gọi stopAllActivities() chứ không chỉ setEnabled(false): máy trạng thái Địa
            // cung và Cấm thuật chạy TRƯỚC cổng `enabled` trong tick() nên tắt cờ đó không dừng
            // được chúng — bấm Tắt Auto giữa chừng mà nhân vật vẫn đi tiếp lượt sau.
            System.out.println("[MyBot] Auto + Auto NV Disabled! "
                    + TaskManager.getInstance().stopAllActivities());
        } else if (line.contains("\"command\":\"start_task\"")) {
            TaskManager.getInstance().setEnabled(true);
            System.out.println("[MyBot] Auto Task Enabled!");
        } else if (line.contains("\"command\":\"stop_task\"")) {
            TaskManager.getInstance().setEnabled(false);
            System.out.println("[MyBot] Auto Task Disabled!");
        } else if (line.contains("\"command\":\"set_afk_map\"")) {
            // Parse from JSON: {"command":"set_afk_map","map":40,"zone":1}
            try {
                int mapId = 0;
                int zone = 1;
                int mapIdx = line.indexOf("\"map\":");
                if (mapIdx >= 0) {
                    String after = line.substring(mapIdx + 6);
                    String num = "";
                    for (char c : after.toCharArray()) {
                        if (Character.isDigit(c)) num += c;
                        else if (num.length() > 0) break;
                    }
                    mapId = Integer.parseInt(num);
                }
                int zoneIdx = line.indexOf("\"zone\":");
                if (zoneIdx >= 0) {
                    String after = line.substring(zoneIdx + 7);
                    String num = "";
                    for (char c : after.toCharArray()) {
                        if (Character.isDigit(c)) num += c;
                        else if (num.length() > 0) break;
                    }
                    zone = Integer.parseInt(num);
                }
                if (mapId > 0) {
                    TaskManager.getInstance().setAfkConfig(mapId, zone);
                    System.out.println("[MyBot] AFK set: map=" + mapId + " zone=" + zone);
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Error parsing set_afk_map: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"change_zone_now\"")) {
            // {"command":"change_zone_now","zone":21}
            // Đổi khu NGAY, không chờ vào state AFK_FARM như set_afk_map.
            try {
                int zone = 0;
                int zoneIdx = line.indexOf("\"zone\":");
                if (zoneIdx >= 0) {
                    String after = line.substring(zoneIdx + 7);
                    String num = "";
                    for (char c : after.toCharArray()) {
                        if (Character.isDigit(c)) num += c;
                        else if (num.length() > 0) break;
                    }
                    if (num.length() > 0) zone = Integer.parseInt(num);
                }
                if (zone > 0) {
                    TaskManager.getInstance().changeZoneNow(zone);
                } else {
                    System.out.println("[MyBot] change_zone_now: zone khong hop le");
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Error parsing change_zone_now: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"get_pos\"")) {
            // Trả về tọa độ hiện tại cho Manager
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] get_pos: reflection chua san sang");
                    return;
                }
                short px = tm.getPlayerX();
                short py = tm.getPlayerY();
                int mapId = tm.getCurrentMapId();
                String posJson = "{\"type\":\"pos_info\",\"x\":" + px + ",\"y\":" + py 
                        + ",\"map\":" + mapId + ",\"username\":\"" + username + "\"}\n";
                writer.print(posJson);
                writer.flush();
                System.out.println("[MyBot] 📍 Pos=(" + px + "," + py + ") Map=" + mapId);
            } catch (Exception e) {
                System.out.println("[MyBot] get_pos error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"dia_cung_run\"")) {
            // Địa cung — chạy trọn hoạt động: lập nhóm (1 người) -> tới NPC -> nhận chìa.
            // Bất đồng bộ vì mọi bước đều phải chờ server; tiến trình + kết quả đẩy về Manager.
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] dia_cung_run: reflection chua san sang");
                    return;
                }
                // tier: 0 = lấy từ config; skipKey: Manager báo hôm nay đã nhận chìa rồi
                int tier = parseIntParam(line, "tier", 0);
                boolean skipKey = line.contains("\"skipKey\":true");
                System.out.println("[MyBot] Dia cung: " + tm.startDiaCung(tier, skipKey));
            } catch (Exception e) {
                System.out.println("[MyBot] dia_cung_run error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_leader\"")) {
            // Cấm thuật — vai TRƯỞNG NHÓM: lập nhóm, mở khoá, báo khu về Manager rồi gom member.
            // Danh sách member đi bằng TÊN NHÂN VẬT ngăn bởi ';' — giao thức nhóm của game
            // không có id nhân vật, mọi lệnh (mời/xin/đuổi/nhường) đều dùng tên.
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] cam_thuat_leader: reflection chua san sang");
                    return;
                }
                String raw = parseStringParam(line, "members", "");
                java.util.List<String> members = new java.util.ArrayList<String>();
                if (raw != null && !raw.trim().isEmpty()) {
                    for (String s : raw.split(";")) {
                        if (s != null && !s.trim().isEmpty()) members.add(s.trim());
                    }
                }
                // expected = sĩ số đích theo doi_hinh.cfg, KỂ CẢ nick chưa vào game. Khác với
                // members ở trên (Manager đã lọc bỏ nick chưa vào game) nên phải gửi riêng.
                int expected = parseIntParam(line, "expected", 0);
                // zone_slot/zone_slots: nhóm này là nhóm thứ mấy trong tổng số nhóm. Dùng để mỗi
                // nhóm xuất phát ở một bậc khác nhau trên dãy khu, khỏi ba trưởng nhóm cùng nhảy
                // vào đúng một khu rồi chen nhau. Manager đời cũ không gửi -> mặc định 0/1.
                int zoneSlot = parseIntParam(line, "zone_slot", 0);
                int zoneSlots = parseIntParam(line, "zone_slots", 1);
                System.out.println("[MyBot] Cam thuat (truong): "
                        + tm.startCamThuatLeader(members, expected, zoneSlot, zoneSlots));
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_leader error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_member\"")) {
            // Cấm thuật — vai THÀNH VIÊN: về làng rồi chờ Manager báo khu của trưởng nhóm.
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] cam_thuat_member: reflection chua san sang");
                    return;
                }
                String leader = parseStringParam(line, "leader", "");
                // slot = số thứ tự trong nhóm, để mỗi member lệch nhau một khoảng khi gửi
                // lời xin — tránh cả nhóm bắn CMD 39 vào cùng một khoảnh khắc.
                int slot = parseIntParam(line, "slot", 0);
                System.out.println("[MyBot] Cam thuat (member): " + tm.startCamThuatMember(leader, slot));
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_member error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_goto\"")) {
            // Manager chuyển tiếp map/khu trưởng nhóm đang đứng. Gửi lại được nhiều lần vì
            // trưởng nhóm có thể phải nhảy khu khi khu hiện tại đã đủ số nhóm.
            try {
                TaskManager tm = TaskManager.getInstance();
                int mapId = parseIntParam(line, "map", 0);
                int zoneId = parseIntParam(line, "zone", -1);
                String leader = parseStringParam(line, "leader", "");
                // Toạ độ THẬT của trưởng nhóm. Thiếu (-1) thì member tự tra config như trước.
                int lx = parseIntParam(line, "x", -1);
                int ly = parseIntParam(line, "y", -1);
                System.out.println("[MyBot] Cam thuat (diem tap ket): "
                        + tm.setCamThuatTarget(mapId, zoneId, leader, lx, ly));
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_goto error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"bua_ma\"")) {
            // Mã captcha do NGƯỜI DÙNG gõ trên Telegram, Manager chuyển xuống. Tool chỉ gõ hộ vào
            // ô — nó không đọc ảnh, không giải gì cả.
            try {
                String ma = parseStringParam(line, "ma", "");
                System.out.println("[MyBot] Bua ue tho (nhap ma): "
                        + TaskManager.getInstance().nhapMaCaptcha(ma));
            } catch (Exception e) {
                System.out.println("[MyBot] bua_ma error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_zone_full\"")) {
            // Manager chuyển tiếp: một member không chen được vào khu của trưởng nhóm (khu đầy
            // người). Trưởng nhóm dời sang khu khác rồi báo lại khu mới.
            try {
                String who = parseStringParam(line, "who", "");
                int khu = parseIntParam(line, "want_zone", -1);
                System.out.println("[MyBot] Cam thuat (khu day nguoi): "
                        + TaskManager.getInstance().notifyCamThuatZoneFull(who, khu));
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_zone_full error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_zone_full\"")) {
            // Y hệt cam_thuat_zone_full, cho Sơn cáp: member không chen được vào khu của trưởng
            // nhóm vì khu đã đủ 15 người ⇒ trưởng nhóm dời khu rồi báo lại.
            try {
                String who = parseStringParam(line, "who", "");
                int khu = parseIntParam(line, "want_zone", -1);
                System.out.println("[MyBot] Son cap (khu day nguoi): "
                        + TaskManager.getInstance().notifySonCapZoneFull(who, khu));
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_zone_full error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_open\"")) {
            // Manager đã xác nhận cả nhóm tập kết đủ và cùng khu → trưởng nhóm đi mở cấm thuật.
            try {
                System.out.println("[MyBot] Cam thuat (mo ham): "
                        + TaskManager.getInstance().openCamThuat());
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_open error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"follow_start\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] follow_start: reflection chua san sang");
                    return;
                }
                // role:  1 = lead (chỉ báo vị trí), 2 = member (bám theo)
                // move:  1 = được tự đi tới chỗ lead, 0 = CHỈ gán mục tiêu (chạy bên trong một
                //        hoạt động đang tự lái nhân vật), -1 = theo config.
                // owner: hoạt động sở hữu phiên — "cam_thuat" | "agt" | "son_cap". BẮT BUỘC.
                //        Bám mục tiêu chỉ dùng trong ba hoạt động theo nhóm đó, không dùng ở bất
                //        kỳ đâu khác. Thiếu hoặc sai là startFollow từ chối luôn.
                System.out.println("[MyBot] Bam theo: " + tm.startFollow(
                        parseIntParam(line, "role", 2), parseStringParam(line, "leader", ""),
                        parseIntParam(line, "move", -1), parseStringParam(line, "owner", "")));
            } catch (Exception e) {
                System.out.println("[MyBot] follow_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"follow_goto\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                // tx/ty = toạ độ MỤC TIÊU của lead, tid = mã cá thể của nó (a.x.aZ).
                // -1 = lead đang không có mục tiêu.
                System.out.println("[MyBot] Bam theo: " + tm.setFollowTarget(
                        parseIntParam(line, "map", -1), parseIntParam(line, "zone", -1),
                        parseIntParam(line, "x", -1), parseIntParam(line, "y", -1),
                        parseIntParam(line, "tx", -1), parseIntParam(line, "ty", -1),
                        parseIntParam(line, "tid", -1)));
            } catch (Exception e) {
                System.out.println("[MyBot] follow_goto error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"map_scan\"")) {
            // Soi map: đếm và phân loại entity trong z.E / z.O / z.F, tách người chơi khỏi quái.
            // Thuần ĐỌC bộ nhớ, không gửi gói nào lên server.
            try {
                System.out.println("[MyBot] Soi map: " + TaskManager.getInstance().scanMapEntities());
            } catch (Exception e) {
                System.out.println("[MyBot] map_scan error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_enter\"")) {
            // Manager báo cả nhóm đã tập kết đủ -> nick này tự bấm NPC vào sơn cáp.
            // Mỗi nick tự bấm cho mình: vào map là nhóm bị giải tán nên nhóm không phải phương
            // tiện đưa người vào.
            try {
                System.out.println("[MyBot] Son cap: " + TaskManager.getInstance().enterSonCap());
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_enter error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"go_exit\"")) {
            // Thử đi qua map: đọc bảng lối ra của map rồi đi tới tấm biển — y hệt cú bấm vào
            // biển trong game. map <= 0 = tự chọn lối bên phải nhất.
            try {
                System.out.println("[MyBot] Di qua map: " + TaskManager.getInstance()
                        .goMapExit(parseIntParam(line, "map", -1)));
            } catch (Exception e) {
                System.out.println("[MyBot] go_exit error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"scan_auto\"")) {
            // Bật/tắt máy soi map tự động (nút 🧲). Nhịp lấy từ scan_auto_ms trong cfg.
            // Thuần đọc bộ nhớ, chạy song song với bất kỳ hoạt động nào cũng vô hại.
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance()
                        .setScanAuto(parseIntParam(line, "on", 1) == 1));
            } catch (Exception e) {
                System.out.println("[MyBot] scan_auto error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"tinh_thach_start\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().startTinhThach());
            } catch (Exception e) {
                System.out.println("[MyBot] tinh_thach_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"tinh_thach_stop\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().stopTinhThach());
            } catch (Exception e) {
                System.out.println("[MyBot] tinh_thach_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"quiz_start\"")) {
            try {
                int npcId = parseIntParam(line, "npcId", -1);
                System.out.println("[MyBot] " + TaskManager.getInstance().startQuiz(npcId));
            } catch (Exception e) {
                System.out.println("[MyBot] quiz_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"quiz_stop\"")) {
            try {
                TaskManager.getInstance().stopQuiz();
                System.out.println("[MyBot] Quiz stopped");
            } catch (Exception e) {
                System.out.println("[MyBot] quiz_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"quiz_query_res\"")) {
            try {
                String qText = parseStringParam(line, "question", "");
                String cAns = parseStringParam(line, "correctAnswer", "");
                TaskManager.getInstance().onQuizQueryRes(qText, cAns);
            } catch (Exception e) {
                System.out.println("[MyBot] quiz_query_res error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_lead_start\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().startGomLead());
            } catch (Exception e) {
                System.out.println("[MyBot] gom_lead_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_lead_report\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().gomBaoViTri());
            } catch (Exception e) {
                System.out.println("[MyBot] gom_lead_report error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_mem_start\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().startGomMem(
                        parseIntParam(line, "map", -1),
                        parseIntParam(line, "zone", -1),
                        parseIntParam(line, "x", -1),
                        parseIntParam(line, "y", -1),
                        parseStringParam(line, "lead", "")));
            } catch (Exception e) {
                System.out.println("[MyBot] gom_mem_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_invite\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance()
                        .gomMoi(parseStringParam(line, "who", "")));
            } catch (Exception e) {
                System.out.println("[MyBot] gom_invite error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_zone_hop\"")) {
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().gomDoiKhu());
            } catch (Exception e) {
                System.out.println("[MyBot] gom_zone_hop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"gom_stop\"")) {
            // xong=1: đã chạy hết hàng đợi ⇒ lead được đi treo.
            // xong=0: dừng giữa chừng ⇒ lead đứng nguyên tại chỗ hẹn, không bỏ mem còn lại.
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance()
                        .stopGom(parseIntParam(line, "xong", 1) == 1));
            } catch (Exception e) {
                System.out.println("[MyBot] gom_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"item_list\"")) {
            // Xuất bảng mẫu vật phẩm ra file riêng, để tra mã món mới mà thêm vào danh sách gom.
            // Thuần đọc bộ nhớ client, không gửi gói nào lên server.
            try {
                System.out.println("[MyBot] " + TaskManager.getInstance().dumpItemCatalog());
            } catch (Exception e) {
                System.out.println("[MyBot] item_list error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"npc_probe\"")) {
            // Soi menu NPC: mở NPC, in menu gốc, bấm mục cha bằng CMD 53 hai byte, in menu sau
            // đó, rồi tự kết luận mục con có nằm sẵn trong chuỗi hay server trả về list mới.
            try {
                System.out.println("[MyBot] Soi menu: " + TaskManager.getInstance().probeNpcMenu());
            } catch (Exception e) {
                System.out.println("[MyBot] npc_probe error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"follow_stop\"")) {
            try {
                System.out.println("[MyBot] Bam theo: " + TaskManager.getInstance().stopFollow());
            } catch (Exception e) {
                System.out.println("[MyBot] follow_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"agt_start\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] agt_start: reflection chua san sang");
                    return;
                }
                // role: 1 = nick mở cửa ải, 2 = nick vào ải
                System.out.println("[MyBot] AGT: " + tm.startAgt(parseIntParam(line, "role", 2)));
            } catch (Exception e) {
                System.out.println("[MyBot] agt_start error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"agt_go\"")) {
            try {
                System.out.println("[MyBot] AGT: " + TaskManager.getInstance().setAgtSignal());
            } catch (Exception e) {
                System.out.println("[MyBot] agt_go error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"agt_stop\"")) {
            try {
                System.out.println("[MyBot] AGT: " + TaskManager.getInstance().stopAgt());
            } catch (Exception e) {
                System.out.println("[MyBot] agt_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"dai_hoi_run\"")) {
            // Đại hội nhẫn giả — hoạt động ĐƠN, không có vai nào để khai, không có tham số.
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] dai_hoi_run: reflection chua san sang");
                    return;
                }
                System.out.println("[MyBot] Dai hoi: " + tm.startDaiHoi());
            } catch (Exception e) {
                System.out.println("[MyBot] dai_hoi_run error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"dai_hoi_stop\"")) {
            try {
                System.out.println("[MyBot] Dai hoi: " + TaskManager.getInstance().stopDaiHoi());
            } catch (Exception e) {
                System.out.println("[MyBot] dai_hoi_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_leader\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] son_cap_leader: reflection chua san sang");
                    return;
                }
                String membersRaw = parseStringParam(line, "members", "");
                java.util.List<String> members = new java.util.ArrayList<String>();
                for (String s : membersRaw.split(";")) {
                    String t = s.trim();
                    if (!t.isEmpty()) members.add(t);
                }
                int expected = parseIntParam(line, "expected", 0);
                int zoneSlot = parseIntParam(line, "zone_slot", 0);
                int zoneSlots = parseIntParam(line, "zone_slots", 1);
                System.out.println("[MyBot] Son cap (leader): "
                        + tm.startSonCapLeader(members, expected, zoneSlot, zoneSlots));
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_leader error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_member\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] son_cap_member: reflection chua san sang");
                    return;
                }
                String leader = parseStringParam(line, "leader", "");
                int slot = parseIntParam(line, "slot", 0);
                System.out.println("[MyBot] Son cap (member): " + tm.startSonCapMember(leader, slot));
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_member error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_goto\"")) {
            try {
                TaskManager tm = TaskManager.getInstance();
                int mapId = parseIntParam(line, "map", 0);
                int zoneId = parseIntParam(line, "zone", -1);
                int lx = parseIntParam(line, "x", -1);
                int ly = parseIntParam(line, "y", -1);
                String leader = parseStringParam(line, "leader", "");
                System.out.println("[MyBot] Son cap (diem tap ket): "
                        + tm.setSonCapTarget(mapId, zoneId, leader, lx, ly));
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_goto error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"son_cap_stop\"")) {
            try {
                System.out.println("[MyBot] Son cap: " + TaskManager.getInstance().stopSonCap());
            } catch (Exception e) {
                System.out.println("[MyBot] son_cap_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"cam_thuat_stop\"")) {
            try {
                System.out.println("[MyBot] Cam thuat: " + TaskManager.getInstance().stopCamThuat());
            } catch (Exception e) {
                System.out.println("[MyBot] cam_thuat_stop error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"go_village\"")) {
            // Về làng: tắt auto + navigate về map 59
            try {
                isAutoEnabled = false;
                TaskManager.getInstance().setEnabled(false);
                TaskManager tm = TaskManager.getInstance();
                if (!tm.ensureReflection()) {
                    System.out.println("[MyBot] go_village: reflection chua san sang");
                    return;
                }
                tm.navigateToVillage();
                System.out.println("[MyBot] 🏠 Lenh ve lang da gui");
            } catch (Exception e) {
                System.out.println("[MyBot] go_village error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"scan_npc\"")) {
            try {
                String scanResult = TaskManager.getInstance().scanAllNpcsJson();
                String result = "{\"type\":\"scan_npc_result\"," + scanResult.substring(1) + "\n";
                // scanResult is {"npcs":[...],"mobs":[...]} -> merge into {type:..., npcs:..., mobs:...}
                // by removing leading { and prepending {type:...
                String merged = "{\"type\":\"scan_npc_result\",\"username\":\"" + username + "\"," + scanResult.substring(1) + "\n";
                writer.print(merged);
                writer.flush();
                System.out.println("[MyBot] scan_npc: sent " + merged.length() + " bytes");
            } catch (Exception e) {
                System.out.println("[MyBot] scan_npc error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"deep_scan\"")) {
            try {
                String result = TaskManager.getInstance().scanAllEntitiesJson();
                String msg = "{\"type\":\"deep_scan_result\",\"data\":" + result + ",\"username\":\"" + username + "\"}\n";
                writer.print(msg);
                writer.flush();
                System.out.println("[MyBot] deep_scan: sent " + result.length() + " bytes");
            } catch (Exception e) {
                System.out.println("[MyBot] deep_scan error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"search_npc\"")) {
            try {
                String keyword = parseStringParam(line, "keyword", "");
                String result = TaskManager.getInstance().searchEntityByName(keyword);
                String msg = "{\"type\":\"search_npc_result\",\"data\":" + result + ",\"username\":\"" + username + "\"}\n";
                writer.print(msg);
                writer.flush();
                System.out.println("[MyBot] search_npc '" + keyword + "': sent " + result.length() + " bytes");
            } catch (Exception e) {
                System.out.println("[MyBot] search_npc error: " + e.getMessage());
            }
        } else if (line.contains("\"command\":\"search_hp\"")) {
            try {
                int targetHp = parseIntParam(line, "hp", 100000);
                String result = TaskManager.getInstance().searchByHp(targetHp);
                String msg = "{\"type\":\"search_hp_result\",\"data\":" + result + ",\"username\":\"" + username + "\"}\n";
                writer.print(msg);
                writer.flush();
                System.out.println("[MyBot] search_hp " + targetHp + ": sent " + result.length() + " bytes");
            } catch (Exception e) {
                System.out.println("[MyBot] search_hp error: " + e.getMessage());
            }
        } else {
            System.out.println("[MyBot] TCP: unknown command: " + line);
        }
    }

    private static void listenCommands() {
        System.out.println("[MyBot] TCP Listener thread started.");
        // Listener thread giờ chỉ là backup - tick() đọc non-blocking
        try {
            String line;
            while (isConnected && (line = reader.readLine()) != null) {
                System.out.println("[MyBot] TCP RECV (thread): [" + line + "]");
                processCommand(line);
            }
        } catch (Exception e) {
            System.out.println("[MyBot] TCP Listener error: " + e.getMessage());
        } finally {
            System.out.println("[MyBot] TCP Listener thread exiting.");
        }
    }

    private static void closeConnection() {
        isConnected = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception e) {}
    }

    // ════════════════════════════════════════════════════════════════════
    // GAME LOOP TICK - Gọi mỗi frame từ a.a.render()
    // ════════════════════════════════════════════════════════════════════

    private static long lastStatusSendTime = 0;
    private static long lastAutoTickTime = 0;
    private static long initTime = System.currentTimeMillis();
    private static boolean reflectionDumped = false;
    private static boolean enterGameAttempted = false;

    // ── BẮT GÓI TIN GỬI ĐI ────────────────────────────────────────────────────────────────
    // Injector ghi đè thân a.fm.aG() để gọi hàm này với mã lệnh của mọi gói client gửi lên
    // server. Dùng để tìm ra thao tác nào ứng với gói nào mà không phải đọc mã giải ngược —
    // cụ thể đang cần: nút mũi tên chuyển map ở mép màn hình, đã lần hết các lệnh nghi ngờ
    // trong mã mà không ra.
    //
    // IN MỖI MÃ ĐÚNG MỘT LẦN kể từ lúc bật. Gói đi lại/đánh nhau bắn liên tục, in hết thì
    // chôn mất cái cần tìm; in một lần thì sau vài giây đứng yên là hết gói nền, rồi bấm nút
    // — dòng MỚI hiện ra chính là nó.
    //
    // Mảng 256 phần tử thay cho Set: aG() được gọi từ nhiều luồng và rất dày, không được phép
    // cấp phát hay khoá gì ở đây.
    private static volatile boolean packetLogOn = false;
    private static final boolean[] packetSeen = new boolean[256];

    public static void setPacketLog(boolean on) {
        if (on) java.util.Arrays.fill(packetSeen, false);
        packetLogOn = on;
        System.out.println("[MyBot] Bat goi tin: " + (on ? "BAT (in moi ma mot lan)" : "tat"));
    }

    /** Gọi từ a.fm.aG() sau khi Injector ghi đè. Phải RẺ và KHÔNG BAO GIỜ ném lỗi. */
    public static void pk(byte cmd) {
        if (!packetLogOn) return;
        int i = cmd & 0xFF;
        if (packetSeen[i]) return;
        packetSeen[i] = true;
        try {
            TaskManager.getInstance().logPacket(cmd);
        } catch (Throwable ignore) {}
    }

    public static void tick() {
        // render() là điểm vào DUY NHẤT sau khi bỏ inject vào create() (xem Injector/inject.py).
        // Gọi ở đây để mod vẫn khởi tạo được trên bản jar sạch, không còn lời gọi trong create().
        if (!initialized) init();

        long now = System.currentTimeMillis();
        long elapsed = now - initTime;

        // ── Bước 1: Sau 3 giây, khởi tạo Reflection (throttle 2s, khỏi spam mỗi frame) ─
        if (!reflectionInitialized && elapsed > 3000 && (now - lastReflectTime > 2000)) {
            lastReflectTime = now;
            initReflection();
        }

        // ── Bước 2: Sau 5 giây, thử auto login ───────────────────────
        if (reflectionInitialized && !loginSuccess && elapsed > 5000) {
            if (!loginAttempted || (loginRetryCount < MAX_LOGIN_RETRIES && (now - lastAutoTickTime > 3000))) {
                lastAutoTickTime = now;
                attemptAutoLogin();
            }
        }

        // ── Bước 2b: Nếu reflection chưa init, retry sau mỗi 5 giây ─
        if (!reflectionInitialized && elapsed > 5000 && (now - lastAutoTickTime > 5000)) {
            lastAutoTickTime = now;
            reflectionInitialized = false;
            initReflection();
        }

        // ── BƯỚC 2: tự bấm "Xác nhận" popup update khi treo pre-login ──
        // Sớm nhất có thể: thử từ 3s, mỗi 1s. CHỈ khi a.fj CHƯA tạo (đúng pha popup,
        // tránh đụng màn login sau này). Có điều kiện + tự-lành: không popup thì no-op.
        if (!loginSuccess && loginManager == null && elapsed > 3000
                && popupDismissCount < 20 && (now - lastDismissTime > 1000)) {
            lastDismissTime = now;
            popupDismissCount++;
            tryDismissUpdatePopup();
        }

        // ── BƯỚC 1 chẩn đoán: login treo quá lâu → dump popup (tối đa 3 lần) ──
        // KHÔNG đòi reflectionInitialized: popup hiện SỚM hơn màn login, lúc a.fj còn null
        // → nếu đòi init xong thì máy đo không bao giờ chạy. Chỉ cần đã qua 9s mà chưa login.
        if (!loginSuccess && elapsed > 9000
                && loginPopupDumpCount < 3 && (now - lastPopupDumpTime > 6000)) {
            lastPopupDumpTime = now;
            loginPopupDumpCount++;
            System.out.println("[MyBot] (dump popup lan " + loginPopupDumpCount + "/3)");
            dumpLoginPopupState();
        }

        // ── Bước 2c: Sau login, tự chọn nhân vật vào game ──────────
        if (loginSuccess && !enterGameAttempted && elapsed > 8000) {
            enterGameAttempted = true;
            System.out.println("[MyBot] ═══ Auto Enter Game ═══");
            try {
                // Gửi packet CMD -122, sub-command -127, charIndex 0
                // Server: case -122 → readByte(-127) → readByte(0) → sendChar() + addChar()
                Class<?> fmCls = Class.forName("a.fm");
                
                // fm.d(byte) tạo packet cmd -122 + ghi sub-command byte
                Method dMethod = fmCls.getDeclaredMethod("d", byte.class);
                dMethod.setAccessible(true);
                Object packet = dMethod.invoke(null, (byte) -127);
                
                // writeByte(0) = chọn nhân vật đầu tiên
                Method writeByte = fmCls.getDeclaredMethod("s", int.class);
                writeByte.setAccessible(true);
                writeByte.invoke(packet, 0);
                
                // aG() = gửi packet
                Method send = fmCls.getDeclaredMethod("aG");
                send.setAccessible(true);
                send.invoke(packet);
                
                System.out.println("[MyBot] ✅ Đã gửi packet chọn nhân vật vào game!");
            } catch (Exception e) {
                System.out.println("[MyBot] ❌ Enter game failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // ── Bước 3: Dump reflection info 1 lần để debug ──────────────
        if (!reflectionDumped && elapsed > 10000) {
            reflectionDumped = true;
            dumpGameState();
        }

        // ── Bước 4: Đọc game data thật (sau 10 giây để game load xong) ─
        if (elapsed > 10000) {
            readGameData();
        }

        // ── Bước 4b: Đọc TCP commands bằng raw InputStream ─────────
        if (isConnected && socket != null && elapsed > 5000) {
            try {
                java.io.InputStream is = socket.getInputStream();
                int avail = is.available();
                if (avail > 0) {
                    byte[] buf = new byte[avail];
                    int read = is.read(buf);
                    if (read > 0) {
                        String raw = new String(buf, 0, read, "UTF-8");
                        System.out.println("[MyBot] TCP RAW RECV (" + read + " bytes): [" + raw.trim() + "]");
                        // Tách dòng
                        String[] lines = raw.split("\n");
                        for (String line : lines) {
                            line = line.trim();
                            if (!line.isEmpty()) {
                                processCommand(line);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[MyBot] TCP raw read error: " + e.getMessage());
            }
        }

        // ── DEBUG: Log trạng thái mỗi 10 giây ──────────────────────
        if (elapsed > 12000 && (now - lastAutoTickTime > 10000)) {
            lastAutoTickTime = now;
            System.out.println("[MyBot] ═══ TICK DEBUG ═══");
            try {
                int avail = socket != null ? socket.getInputStream().available() : -1;
                System.out.println("[MyBot] elapsed=" + elapsed + "ms login=" + loginSuccess
                        + " autoEnabled=" + isAutoEnabled + " connected=" + isConnected
                        + " socketAvail=" + avail);
            } catch (Exception e) {
                System.out.println("[MyBot] elapsed=" + elapsed + "ms (socket err: " + e.getMessage() + ")");
            }
            System.out.println("[MyBot] TaskMgr.enabled=" + TaskManager.getInstance().isEnabled()
                    + " state=" + TaskManager.getInstance().getState());
        }

        // ── Bước 5: Chạy TaskManager tick (auto nhiệm vụ) ──────────
        // Chỉ chạy khi game đã fully loaded (player instance tồn tại)
        if (elapsed > 15000) {
            boolean playerReady = true;
            try {
                Class<?> iClass = Class.forName("a.i");
                java.lang.reflect.Method iGetInst = null;
                for (java.lang.reflect.Method m : iClass.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 0
                            && m.getReturnType() == iClass) {
                        iGetInst = m;
                        break;
                    }
                }
                if (iGetInst != null) {
                    Object playerInst = iGetInst.invoke(null);
                    if (playerInst == null) {
                        playerReady = false;
                    }
                }
            } catch (Exception ignore) {}
            
            if (playerReady) {
                TaskManager.getInstance().tick();
            }
        }

        // ── Bước 6: Gửi trạng thái về Manager (mỗi 1.5 giây) ────────
        if (isConnected && (now - lastStatusSendTime > 1500)) {
            lastStatusSendTime = now;
            sendStatusToManager();
        }
    }

    /**
     * Dump trạng thái game tại thời điểm hiện tại (debug).
     */
    private static void dumpGameState() {
        System.out.println("[MyBot] ═══════════════════════════════════════");
        System.out.println("[MyBot] GAME STATE DUMP");
        System.out.println("[MyBot] ═══════════════════════════════════════");
        System.out.println("[MyBot] loginManager: " + (loginManager != null ? "YES" : "NO"));
        System.out.println("[MyBot] gameData: " + (gameData != null ? "YES" : "NO"));
        System.out.println("[MyBot] gameInstance: " + (gameInstance != null ? gameInstance.getClass().getName() : "null"));
        System.out.println("[MyBot] loginSuccess: " + loginSuccess);
        
        // ── Dump current Screen ───────────────────────────────────────
        if (gameInstance != null) {
            try {
                // Tìm getScreen() hoặc field screen trong hierarchy
                Class<?> cls = gameInstance.getClass();
                while (cls != null && cls != Object.class) {
                    System.out.println("[MyBot] Class hierarchy: " + cls.getName());
                    for (Field f : cls.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(gameInstance);
                            if (val != null) {
                                String typeName = f.getType().getSimpleName();
                                // Tìm field có type chứa "Screen" hoặc là com.b.a 
                                if (typeName.contains("Screen") || typeName.equals("a") || typeName.equals("h") || typeName.equals("i")) {
                                    System.out.println("[MyBot]   SCREEN? " + typeName + " " + f.getName() + " = " + val.getClass().getName());
                                }
                            }
                        } catch (Exception e) { /* skip */ }
                    }
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().contains("Screen") || m.getName().contains("screen")) {
                            System.out.println("[MyBot]   SCREEN method: " + m.getReturnType().getSimpleName() + " " + m.getName() + "()");
                        }
                    }
                    cls = cls.getSuperclass();
                }
                
                // Thử gọi getScreen() nếu có
                try {
                    Method getScreen = gameInstance.getClass().getMethod("getScreen");
                    Object currentScreen = getScreen.invoke(gameInstance);
                    System.out.println("[MyBot] ★ Current Screen: " + (currentScreen != null ? currentScreen.getClass().getName() : "null"));
                    if (currentScreen != null) {
                        // Dump methods của screen hiện tại
                        System.out.println("[MyBot] ── Screen methods ──");
                        for (Method m : currentScreen.getClass().getDeclaredMethods()) {
                            StringBuilder sb = new StringBuilder("[MyBot]   ");
                            sb.append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
                            Class<?>[] params = m.getParameterTypes();
                            for (int p = 0; p < params.length; p++) {
                                if (p > 0) sb.append(", ");
                                sb.append(params[p].getSimpleName());
                            }
                            sb.append(")");
                            System.out.println(sb.toString());
                        }
                        // Dump fields
                        System.out.println("[MyBot] ── Screen fields ──");
                        for (Field f : currentScreen.getClass().getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                Object val = f.get(currentScreen);
                                if (val != null) {
                                    if (val instanceof String) {
                                        System.out.println("[MyBot]   " + f.getType().getSimpleName() + " " + f.getName() + " = \"" + val + "\"");
                                    } else {
                                        System.out.println("[MyBot]   " + f.getType().getSimpleName() + " " + f.getName() + " = " + val.getClass().getSimpleName());
                                    }
                                }
                            } catch (Exception e) { /* skip */ }
                        }
                    }
                } catch (NoSuchMethodException e) {
                    System.out.println("[MyBot] No getScreen() method found");
                }
            } catch (Exception e) {
                System.out.println("[MyBot] Screen dump error: " + e.getMessage());
            }
        }

        // ── Dump class instances ──────────────────────────────────────
        try {
            String[] classNames = {"a.a", "a.d", "a.n", "a.fj", "a.fM", "a.eZ", "a.eF", "a.fg"};
            for (String cn : classNames) {
                try {
                    Class<?> c = Class.forName(cn);
                    Object inst = findStaticInstance(c);
                    System.out.println("[MyBot] " + cn + ": " + (inst != null ? "HAS instance" : "no instance"));
                } catch (Exception e) {
                    System.out.println("[MyBot] " + cn + ": NOT FOUND");
                }
            }
        } catch (Exception e) { /* skip */ }
        
        System.out.println("[MyBot] ═══════════════════════════════════════");
    }

    private static void sendStatusToManager() {
        try {
            String status;
            if (level > 0) {
                if (isAutoEnabled) {
                    status = "Dang Auto";
                } else {
                    status = "Da login - Dung Auto";
                }
            } else {
                if (!loginSuccess && !loginAttempted) {
                    status = "Dang cho login...";
                } else if (!loginSuccess) {
                    status = "Login thu #" + loginRetryCount + "/" + MAX_LOGIN_RETRIES;
                } else {
                    status = "Dang login / Ket noi...";
                }
            }

            String serverInfo = (serverName != null && !serverName.isEmpty()) ? serverName : "N/A";
            String taskStatus = TaskManager.getInstance().getStatusText();

            String json = "{" +
                    "\"username\":\"" + username + "\"," +
                    "\"status\":\"" + status + " [" + serverInfo + "]\"," +
                    "\"level\":" + level + "," +
                    "\"charName\":\"" + charName + "\"," +
                    "\"hp\":\"" + hp + "/" + maxHp + "\"," +
                    "\"quest\":\"" + currentQuest + "\"," +
                    "\"task\":\"" + taskStatus + "\"" +
                    "}\n";
            writer.print(json);
            writer.flush();
        } catch (Exception e) {
            System.out.println("[MyBot] Failed to send status: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════

    /** Escape chuỗi trước khi nhét vào JSON gửi Manager (tên nhân vật có thể chứa ký tự lạ). */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Parse an int parameter from a simple JSON string. */
    private static int parseIntParam(String json, String key, int defaultValue) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        String after = json.substring(idx + search.length()).trim();
        StringBuilder num = new StringBuilder();
        boolean negative = false;
        for (int i = 0; i < after.length(); i++) {
            char c = after.charAt(i);
            if (c == '-' && num.length() == 0) { negative = true; continue; }
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        if (num.length() == 0) return defaultValue;
        int val = Integer.parseInt(num.toString());
        return negative ? -val : val;
    }

    /** Parse a string parameter from a simple JSON string. */
    private static String parseStringParam(String json, String key, String defaultValue) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        int start = idx + search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return defaultValue;
        return json.substring(start, end);
    }

    /** Get the TCP writer (for sending messages from TaskManager). */
    public static PrintWriter getWriter() {
        return writer;
    }

    /** Get the current username. */
    public static String getUsername() {
        return username;
    }
}
