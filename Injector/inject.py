import os
import sys
import urllib.request
import subprocess

# ── Đường dẫn ────────────────────────────────────────────────────────────────
# poc_dir suy ra từ vị trí file này (Injector/ nằm ngay dưới gốc dự án), nên chép dự án
# đi đâu cũng chạy — không phải sửa đường dẫn cứng.
poc_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# THƯ MỤC CÀI GAME — chỉnh dòng này cho khớp máy bạn, hoặc đặt biến môi trường LANGLA_GAME.
# Cần vì script mượn JRE của game làm trình biên dịch và mượn gdx.jar làm classpath.
game_dir = os.environ.get("LANGLA_GAME", r"C:\Games\LangLa")
if not os.path.isdir(game_dir):
    print("Khong thay thu muc game:", game_dir)
    print("Sua bien game_dir trong Injector/inject.py, hoac dat bien moi truong LANGLA_GAME.")
    exit(1)

# JAR NGUỒN — script này vừa đọc vừa ghi client_modded.jar (vá xong ghi đè lại chính nó),
# nên LẦN ĐẦU phải có sẵn một bản jar gốc của game đặt tên như vậy. Bản phát hành không kèm
# jar của game: đó là mã của nhà phát hành, không phải mã của dự án này.
seed_jar = os.path.join(poc_dir, "client_modded.jar")
if not os.path.exists(seed_jar):
    print("=" * 70)
    print("THIEU client_modded.jar — chua co jar nguon de va.")
    print()
    print("Lan dau chay, ban phai tu lay jar cua BAN GAME MINH DANG CAI:")
    print("  1. Mo thu muc game, tim file jar chua class 'com.beatdz.langlalau.DesktopLauncher'")
    print("     (mot so ban dong goi no ben trong file .exe khoi chay — giai nen ra de lay).")
    print("  2. Chep file jar do vao goc du an va DAT TEN client_modded.jar")
    print("  3. Chay lai script nay.")
    print()
    print("Tu lan thu hai tro di khong can lam gi: script tu doc va ghi de len chinh file do.")
    print("Goc du an:", poc_dir)
    print("=" * 70)
    exit(1)

lib_dir = os.path.join(poc_dir, "lib")
os.makedirs(lib_dir, exist_ok=True)

javassist_url = "https://repo1.maven.org/maven2/org/javassist/javassist/3.29.2-GA/javassist-3.29.2-GA.jar"
javassist_jar = os.path.join(lib_dir, "javassist.jar")

# Download Javassist
if not os.path.exists(javassist_jar):
    print("Downloading Javassist library...")
    try:
        urllib.request.urlretrieve(javassist_url, javassist_jar)
        print("Javassist downloaded successfully!")
    except Exception as e:
        print("Failed to download Javassist:", e)
        exit(1)

# Máy không có JDK — dùng JRE 1.8 của game + ecj (Eclipse Compiler for Java) làm javac.
# ecj 3.26.0 là bản cuối còn chạy được trên Java 8; bản mới hơn đòi Java 11.
java_path = os.path.join(game_dir, "jre", "bin", "java.exe")
ecj_jar = os.path.join(poc_dir, "tools", "ecj.jar")
ecj_url = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar"

if not os.path.exists(ecj_jar):
    print("Downloading ecj (Eclipse Compiler for Java)...")
    os.makedirs(os.path.dirname(ecj_jar), exist_ok=True)
    try:
        urllib.request.urlretrieve(ecj_url, ecj_jar)
        print("ecj downloaded successfully!")
    except Exception as e:
        print("Failed to download ecj:", e)
        exit(1)


def compile_java(classpath, out_dir, sources):
    """Biên dịch về bytecode Java 8 (client game chạy JRE 1.8)."""
    cmd = [java_path, "-jar", ecj_jar, "-8", "-nowarn", "-encoding", "utf8",
           "-cp", classpath, "-d", out_dir] + sources
    return subprocess.run(cmd, capture_output=True, text=True)

# 1. Compile mod Java files (Auto.java + TaskManager.java)
print("Compiling mod Java files...")
mod_classes_dir = os.path.join(poc_dir, "Mod", "classes")
os.makedirs(mod_classes_dir, exist_ok=True)

# Lấy tất cả .java files trong com/mybot/
mod_src_dir = os.path.join(poc_dir, "Mod", "src")
java_files = []
for root, dirs, files in os.walk(mod_src_dir):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))

# Classpath: cần original jar (chứa a.z, a.i, a.dq, v.v.)
original_jar_for_cp = os.path.join(poc_dir, "client_modded.jar")
gdx_jar_cp = os.path.join(game_dir, "lib", "gdx.jar")

print(f"Compiling {len(java_files)} Java files: {[os.path.basename(f) for f in java_files]}")
res = compile_java(f"{original_jar_for_cp};{gdx_jar_cp}", mod_classes_dir, java_files)
if res.returncode != 0:
    print("Failed to compile mod Java files:")
    print(res.stdout)
    print(res.stderr)
    exit(1)
print("All mod Java files compiled successfully.")

# 2. Write Injector.java
injector_java = """import javassist.*;
import java.io.File;

public class Injector {
    public static void main(String[] args) {
        try {
            String jarPath = args[0];
            String outJarPath = args[1];
            String modClassesPath = args[2];
            String gdxJarPath = args[3];

            ClassPool pool = ClassPool.getDefault();
            pool.insertClassPath(jarPath);
            pool.insertClassPath(gdxJarPath);
            pool.insertClassPath(modClassesPath);

            System.out.println("Modifying class a.a...");
            CtClass cc = pool.get("a.a");
            if (cc.isFrozen()) cc.defrost();

            // 1. KHÔNG inject vào create() nữa.
            //    insertAfter() không thể kiểm tra "đã chèn chưa", mà inject.py lại dùng
            //    client_modded.jar vừa làm nguồn vừa làm đích → mỗi lần build cộng thêm một
            //    lời gọi Auto.init(). Bản jar trước khi sửa đã tích tụ 57 lời gọi, tức 57
            //    thread kết nối Manager mỗi lần mở client.
            //    Điểm vào giờ là render() (mục 2) — hook đó được XOÁ RỒI THÊM LẠI mỗi lần
            //    build nên không bao giờ cộng dồn. Auto.tick() tự gọi init() lần đầu.
            //    Vẫn kiểm create() tồn tại để fail sớm nếu class đổi hình dạng.
            try {
                cc.getDeclaredMethod("create");
                System.out.println("Found a.a.create() (khong sua) - diem vao la render()");
            } catch (NotFoundException e) {
                System.out.println("create() not found! Class a.a da doi hinh dang.");
                System.exit(1);
            }

            // 2. Add/replace render() method
            try {
                // Xóa render cũ nếu đã tồn tại (re-injection)
                try {
                    CtMethod oldRender = cc.getDeclaredMethod("render");
                    cc.removeMethod(oldRender);
                    System.out.println("Removed existing render() for re-injection.");
                } catch (NotFoundException e) {
                    // Chưa có render() — OK, thêm mới
                }
                CtMethod mRender = CtNewMethod.make(
                    "public void render() { " +
                    "   com.mybot.Auto.tick(); " +
                    "   super.render(); " +
                    "}", cc);
                cc.addMethod(mRender);
                System.out.println("Injected Auto.tick() via a.a.render()");
            } catch (Exception e) {
                System.out.println("Failed to inject render: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            String tempOutDir = "./temp_patched";
            cc.writeFile(tempOutDir);
            System.out.println("Patched class written to " + tempOutDir);
            cc.detach();

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
"""

injector_src_path = os.path.join(poc_dir, "Injector", "Injector.java")
os.makedirs(os.path.dirname(injector_src_path), exist_ok=True)
with open(injector_src_path, "w", encoding="utf-8") as f:
    f.write(injector_java)

# 3. Compile Injector.java
print("Compiling Injector.java...")
injector_classes_dir = os.path.join(poc_dir, "Injector", "classes")
os.makedirs(injector_classes_dir, exist_ok=True)

res = compile_java(javassist_jar, injector_classes_dir, [injector_src_path])
if res.returncode != 0:
    print("Failed to compile Injector.java:")
    print(res.stdout)
    print(res.stderr)
    exit(1)
print("Injector.java compiled successfully.")

# 4. Run Injector.java to generate patched a/a.class
print("Running Injector to patch bytecode...")
original_jar = os.path.join(poc_dir, "client_modded.jar")
gdx_jar = os.path.join(game_dir, "lib", "gdx.jar")

cmd_run_injector = [
    java_path,
    "-cp", f"{injector_classes_dir};{javassist_jar}",
    "Injector",
    original_jar,
    "unused",
    mod_classes_dir,
    gdx_jar
]
res = subprocess.run(cmd_run_injector, cwd=poc_dir, capture_output=True, text=True)
if res.returncode != 0:
    print("Failed to execute Injector:")
    print(res.stderr)
    exit(1)
print(res.stdout)
print("Injector ran successfully.")

# 5. Package into new client_modded.jar
print("Packaging modified files into client_modded.jar...")
import zipfile
import shutil

patched_class_file = os.path.join(poc_dir, "temp_patched", "a", "a.class")
client_modded_jar = os.path.join(poc_dir, "client_modded.jar")

# Nếu original_jar != client_modded_jar thì copy base, nếu cùng thì dùng trực tiếp
if os.path.abspath(original_jar) != os.path.abspath(client_modded_jar):
    shutil.copyfile(original_jar, client_modded_jar)

# Tạo temp jar mới, copy entries cũ (bỏ qua entries sẽ bị thay thế), thêm entries mới
temp_jar = client_modded_jar + ".tmp"
entries_to_replace = {"a/a.class"}

# Thu thập mod class entries
mybot_classes_dir = os.path.join(mod_classes_dir, "com", "mybot")
mod_entries = {}
if os.path.exists(mybot_classes_dir):
    for root, dirs, files in os.walk(mybot_classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_file = os.path.join(root, f)
                rel_path = os.path.relpath(class_file, mod_classes_dir).replace("\\", "/")
                mod_entries[rel_path] = class_file
                entries_to_replace.add(rel_path)

with zipfile.ZipFile(client_modded_jar, 'r') as zin:
    with zipfile.ZipFile(temp_jar, 'w', zipfile.ZIP_DEFLATED) as zout:
        # Copy entries cũ (bỏ qua những cái sẽ thay thế)
        for item in zin.infolist():
            if item.filename not in entries_to_replace:
                zout.writestr(item, zin.read(item.filename))
        # Thêm patched a/a.class
        zout.write(patched_class_file, "a/a.class")
        print(f"  Replaced: a/a.class")
        # Thêm mod classes
        for rel_path, class_file in mod_entries.items():
            zout.write(class_file, rel_path)
            print(f"  Added: {rel_path}")

# Thay thế file gốc (Windows-safe, retry nếu bị lock)
import time, gc
gc.collect()
for attempt in range(5):
    try:
        time.sleep(0.5)
        if os.path.exists(client_modded_jar):
            os.remove(client_modded_jar)
        os.rename(temp_jar, client_modded_jar)
        break
    except PermissionError:
        print(f"  File locked, retry {attempt+1}/5...")
        time.sleep(1)
else:
    # Vẫn lock sau 5 lần ⇒ game/Manager đang mở và giữ file. Ghi ra tên khác để không mất
    # công build, nhưng phải BÁO HỎNG rõ ràng và thoát mã lỗi: lần trước chuyện này lọt qua,
    # 15 client chạy tiếp bằng jar cũ mà không ai biết, rồi mấy lượt đối chiếu sau đó đều
    # đọc nhầm jar cũ.
    alt_jar = client_modded_jar.replace(".jar", "_new.jar")
    # os.replace chứ không os.rename: trên Windows rename vào file đã tồn tại là ném
    # FileExistsError, nên lần chạy sau sẽ crash vì còn sót _new.jar của lần này.
    os.replace(temp_jar, alt_jar)
    print("")
    print("  ==========================================================")
    print("  KHONG GHI DUOC VAO client_modded.jar - FILE DANG BI KHOA")
    print("  Nguyen nhan: game client hoac Manager dang mo va giu file.")
    print("  => Dong het client roi chay lai inject.py.")
    print(f"  Ban build moi tam de tai: {alt_jar}")
    print("  ==========================================================")
    sys.exit(1)

print(f"Modded client jar created at: {client_modded_jar}")
print("Verification: Patched game client is fully ready!")
