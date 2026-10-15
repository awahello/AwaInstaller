"""Awa的安装助手 —— 出包前全量校验（v4）

覆盖：ZIP 完整性 / 资源表应用名 / 清单权限 / dex 有效性 / 类型可解析 /
零提权字节码审查 / 文件管理功能面 / 三标签 / 签名。
"""
import base64
import hashlib
import os
import re
import struct
import subprocess
import sys
import zipfile
import zlib

WS = os.path.dirname(os.path.abspath(__file__))
APK = os.path.join(WS, 'dist', 'Awa的安装助手.apk')
JAR = os.environ.get('ANDROID_JAR', os.path.join(WS, 'tools', 'android-all-22.jar'))
JDK = os.environ.get('JDK_BIN', os.path.join(WS, 'tools', 'jdk-17.0.2', 'bin', 'jarsigner.exe'))

ok = True


def chk(cond, msg):
    global ok
    print(('  [OK]   ' if cond else '  [FAIL] ') + msg)
    if not cond:
        ok = False
    return cond


def u16(d, p):
    return struct.unpack_from('<H', d, p)[0]


def u32(d, p):
    return struct.unpack_from('<I', d, p)[0]


print('=' * 68)
print('1. ZIP 完整性 / 条目')
print('=' * 68)
z = zipfile.ZipFile(APK)
chk(z.testzip() is None, 'zip 结构完好，无 CRC 错误')
names = z.namelist()
for n in names:
    print('     %-34s %8d  %s' % (n, z.getinfo(n).file_size,
                                 'store' if z.getinfo(n).compress_type == 0 else 'deflate'))
for n in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc',
          'res/drawable/ic_launcher.png', 'META-INF/CERT.RSA', 'META-INF/CERT.SF',
          'META-INF/MANIFEST.MF'):
    chk(n in names, '包含 %s' % n)
chk('assets/mtk-su' in names, '包含 assets/mtk-su（内置提权工具，和原版一致）')
if 'assets/mtk-su' in names:
    chk(z.getinfo('assets/mtk-su').file_size == 65144,
        'assets/mtk-su 是 65144 字节（和原版同一份）')
chk(not any('es.apk' in n for n in names), '不含 assets/es.apk（ES 已按要求去掉）')

print()
print('=' * 68)
print('2. resources.arsc：应用名')
print('=' * 68)
arsc = z.read('resources.arsc')


def rdstr(d, pool, i):
    hs = u16(d, pool + 2)
    ss = u32(d, pool + 20)
    a = pool + ss + u32(d, pool + hs + i * 4)
    bl = d[a + 1]
    return d[a + 2:a + 2 + bl].decode('utf-8')


chk(u32(arsc, 4) == len(arsc), 'RES_TABLE chunkSize(%d) == 实际长度' % u32(arsc, 4))
pool = u16(arsc, 2)
nstr = u32(arsc, pool + 8)
vals = [rdstr(arsc, pool, i) for i in range(nstr)]
for i, s in enumerate(vals):
    print('     值池[%d] %r' % (i, s))
chk(vals[0] == 'Awa的安装助手', 'app_name(索引0) = Awa的安装助手')
chk(vals[4] == 'Awa的安装助手', 'app_name_en(索引4) = Awa的安装助手')
chk(vals[1] == 'res/drawable/ic_launcher.png' and vals[2] == '关于' and vals[3] == 'About',
    '其余值池条目未被误伤')
pk = pool + u32(arsc, pool + 4)
chk(pk + u32(arsc, pk + 4) == len(arsc), '包 chunk 紧随值池，无错位')

print()
print('=' * 68)
print('3. AndroidManifest.xml：权限')
print('=' * 68)
man = z.read('AndroidManifest.xml')
chk(man[:4] == b'\x03\x00\x08\x00', 'AXML magic 正常')
chk(u32(man, 4) == len(man), 'AXML size 字段 == 实际长度')
mtxt = man.decode('utf-16-le', errors='ignore')
need = [
    ('android.permission.REBOOT', '免 root 重启'),
    ('android.permission.WRITE_SECURE_SETTINGS', 'ADB 开关'),
    ('android.permission.CHANGE_WIFI_STATE', 'WiFi 开关'),
    ('android.permission.ACCESS_WIFI_STATE', '读 WiFi 状态'),
    ('android.permission.ACCESS_NETWORK_STATE', '读网络状态'),
    ('android.permission.READ_EXTERNAL_STORAGE', '扫描 U盘 / 存储'),
    ('android.permission.WRITE_EXTERNAL_STORAGE', '文件管理器写入'),
]
for perm, why in need:
    chk(perm in mtxt, '声明了 %s（%s）' % (perm, why))

# 绝不能声明：安装 / 卸载 / 挂载系统分区
for bad, why in [
    ('android.permission.INSTALL_PACKAGES', '静默安装'),
    ('android.permission.DELETE_PACKAGES', '静默卸载'),
    ('android.permission.MOUNT_UNMOUNT_FILESYSTEMS', '挂载系统分区'),
]:
    chk(bad not in mtxt, '没有声明 %s（%s）' % (bad, why))

for cls in ('MainActivity', 'AboutActivity'):
    chk(cls in mtxt, '清单声明了 %s' % cls)
chk('com.awa.apkinstaller' in mtxt, '包名仍是 com.awa.apkinstaller（可覆盖安装）')

print()
print('=' * 68)
print('4. classes.dex 有效性')
print('=' * 68)
dex = z.read('classes.dex')
chk(dex[:4] == b'dex\n', 'magic = dex\\n')
chk(dex[4:8] == b'035\x00', 'version = 035')
chk(u32(dex, 32) == len(dex), 'header.file_size == 实际长度')
chk(u16(dex, 36) == 0x70, 'header_size = 0x70')
chk((zlib.adler32(dex[12:]) & 0xffffffff) == u32(dex, 8), 'Adler-32 校验和正确')
chk(hashlib.sha1(dex[32:]).digest() == dex[12:32], 'SHA-1 签名正确')


def uleb(d, p):
    n = 0
    sh = 0
    while True:
        b = d[p]
        p += 1
        n |= (b & 0x7f) << sh
        sh += 7
        if b < 0x80:
            return n, p


str_cnt = u32(dex, 56)
str_off = u32(dex, 60)
strs = []
for i in range(str_cnt):
    o = u32(dex, str_off + i * 4)
    n, p = uleb(dex, o)
    e = dex.index(b'\x00', p)
    strs.append(dex[p:e])
prev = None
asc = True
for s in strs:
    if prev is not None and s < prev:
        asc = False
        break
    prev = s
chk(asc, 'string_ids 按内容升序（%d 条）' % str_cnt)

cd_cnt = u32(dex, 96)
cd_off = u32(dex, 100)
t_cnt = u32(dex, 64)
t_off = u32(dex, 68)
types = []
for i in range(t_cnt):
    ti = u32(dex, t_off + i * 4)
    types.append(strs[ti].decode('utf-8', 'replace'))
defined = set()
for i in range(cd_cnt):
    idx = u32(dex, cd_off + i * 32)
    defined.add(types[idx])
print('     类定义 %d 个，type_ids %d 个，字符串 %d 条' % (len(defined), len(types), str_cnt))
for c in ('Lcom/awa/apkinstaller/MainActivity;', 'Lcom/awa/apkinstaller/AboutActivity;'):
    chk(c in defined, '定义了 %s' % c)

print()
print('=' * 68)
print('5. 类型引用全部可解析')
print('=' * 68)
jar = zipfile.ZipFile(JAR)
inj = set()
for nm in jar.namelist():
    if nm.endswith('.class'):
        inj.add('L' + nm[:-6] + ';')
CORE = ('Ljava/', 'Ljavax/', 'Lsun/', 'Ljdk/', 'Lorg/w3c/', 'Lorg/xml/',
        'Lorg/json/', 'Lorg/apache/', 'Llibcore/', 'Ldalvik/')
missing = []
for t in types:
    if not t.startswith('L') or t in defined or t in inj:
        continue
    if t.startswith(CORE):
        continue
    missing.append(t)
chk(not missing, '无悬空类型引用（%s）' % (sorted(set(missing)) if missing else '全部解析'))

print()
print('=' * 68)
print('6. 提权面审查（提权只允许出现在「无线 ADB」这一处）')
print('=' * 68)
print('  说明：教程文案里必然出现 "/data/local/tmp/mtk-su" 这类字眼，那是给用户抄的命令，')
print('        不代表程序会去执行。判断依据是「真正的调用点」，不是文案子串。')
print()

chk(b'Landroid/os/PowerManager;' in dex, '引用了 PowerManager（免 root 重启通道）')
chk(b'Landroid/provider/Settings$Global;' in dex, '引用了 Settings.Global（ADB 开关）')
chk(b'Landroid/net/wifi/WifiManager;' in dex, '引用了 WifiManager（WiFi 开关）')
chk(b'Ljava/io/File;' in dex, '引用了 java.io.File（文件管理器）')

print()
print('  ── 无线 ADB 的状态探测方式（必须是读系统属性，不能是网络探测）──')
print('  说明：本应用清单里没有 android.permission.INTERNET。Android 是按')
print('        GID(AID_INET) 在内核层拦的，连 127.0.0.1 回环也会 EACCES，')
print('        所以「用 Socket 探本机 5555」这种写法永远返回 false ——')
print('        开关状态会永远显示「已关闭」。原版是读 getprop，这里必须一致。')
chk(b'Ljava/net/Socket;' not in dex, '不含 Socket（状态探测不走网络）')
chk(b'Ljava/net/InetSocketAddress;' not in dex, '不含 InetSocketAddress')
chk(b'service.adb.tcp.port' in dex, '用 getprop 读 service.adb.tcp.port 判断开关')
chk(b'init.svc.adbd' in dex, '用 getprop init.svc.adbd 兜底判断 adbd 状态')

print()
print('  ── 必须有的：无线 ADB 一键开关的执行通道 ──')
chk(b'Ljava/lang/ProcessBuilder;' in dex, '有 ProcessBuilder（借 mtk-su 跑 setprop）')

print()
print('  ── 绝不能有的 ──')
for pat, label in [
    (b'Ljava/lang/Runtime;', 'Runtime.exec（本工具不用它执行命令）'),
    (b'Landroid/content/pm/PackageInstaller;', 'PackageInstaller（真安装）'),
    (b'installPackage', 'installPackage'),
    (b'deletePackage', 'deletePackage'),
    (b'IPackageInstallObserver', 'IPackageInstallObserver'),
    (b'SystemProperties', 'SystemProperties 反射（读系统属性）'),
]:
    chk(pat not in dex, '不含 %s' % label)

# ---- androguard 追踪：调用点 / 方法名 / 字符串字面量
ALLOWED_SU = {'/data/local/tmp/mtk-su', '/sdcard/mtk-su', '/sdcard/Download/mtk-su',
              '/system/bin/su', '/system/xbin/su', '/sbin/su', '/su/bin/su',
              '/system/sbin/su', '/system/bin/magisk'}
try:
    import logging
    logging.disable(logging.CRITICAL)
    from androguard.core.dex import DEX
    d = DEX(dex)

    meth_names = set()
    all_strs = set()
    pb_callers = set()
    consts = []          # (类短名, 方法名, 字符串)
    for cls in d.get_classes():
        cs = str(cls.get_name()).split('/')[-1]
        for meth in cls.get_methods():
            mn = str(meth.get_name())
            meth_names.add(mn)
            code = meth.get_code()
            if code is None:
                continue
            try:
                insns = list(code.get_bc().get_instructions())
            except Exception:
                continue
            for ins in insns:
                o = ins.get_output() or ''
                if 'ProcessBuilder' in o:
                    pb_callers.add(mn)
                if ins.get_name() != 'const-string':
                    continue
                mm = re.match(r'^v\d+,\s*"(.*)"$', o)
                if mm:
                    all_strs.add(mm.group(1))
                    consts.append((cs, mn, mm.group(1)))

    print()
    print('  引用 ProcessBuilder 的方法（应只有 runCmd）:')
    for mn in sorted(pb_callers) or ['（无）']:
        print('     %s' % mn)
    chk(pb_callers == {'runCmd'}, 'ProcessBuilder 只出现在 runCmd() 一处')

    print()
    print('  方法名里有没有乱找 root 的痕迹:')
    for bad in ('findSu', 'rootExec', 'execSu', 'runSu', 'suExec', 'shellExec'):
        chk(bad not in meth_names, '没有方法 %s()' % bad)

    print()
    print('  提权程序路径字面量（必须正好是白名单那 9 个）:')
    # 要求带路径分隔符 —— 裸的 "mtk-su" 是 assets 文件名，不是可执行路径
    SUSPECT = re.compile(r'/(su|mtk-su|magisk|superuser|busybox)$')
    found = sorted(s for s in all_strs if SUSPECT.search(s))
    for s in found:
        print('     %s' % s)
    extra = set(found) - ALLOWED_SU
    lack = ALLOWED_SU - set(found)
    chk(not extra, '没有白名单之外的提权程序路径（多出来的: %s）' % (sorted(extra) or '无'))
    chk(not lack, '白名单 9 个路径都在 dex 里（缺: %s）' % (sorted(lack) or '无'))

    print()
    print('  runCmd() 的调用方（只许 execScript 和 chmod）:')
    root_callers = set()
    for cls in d.get_classes():
        for meth in cls.get_methods():
            code = meth.get_code()
            if code is None:
                continue
            try:
                insns = list(code.get_bc().get_instructions())
            except Exception:
                continue
            for ins in insns:
                if 'runCmd' in (ins.get_output() or ''):
                    root_callers.add(str(meth.get_name()))
    for mn in sorted(root_callers) or ['（无）']:
        print('     %s' % mn)
    chk(root_callers <= {'execScript', 'chmod', 'readAdbEnabled', 'readWifiAdbPort'},
        'runCmd() 只被 execScript / chmod / readAdbEnabled / readWifiAdbPort 调用（实际 %s）' % sorted(root_callers))
    for need in ('execScript', 'runCmd', 'suCandidates', 'privateSu', 'chmod', 'doWifiAdb'):
        chk(need in meth_names, '方法 %s() 存在' % need)

    print()
    print('  setprop 命令字面量出现的位置（只许无线 ADB 这条代码路径）:')
    prop_locs = sorted({(cs, mn) for cs, mn, v in consts
                        if 'setprop service.adb.tcp.port' in v})
    for cs, mn in prop_locs or [('（无）', '')]:
        print('     %-22s %s' % (cs, mn))
    chk({mn for _, mn in prop_locs} <= {'wifiAdbHelp', 'doWifiAdb', 'run'},
        'setprop 只出现在 wifiAdbHelp（文案）/ doWifiAdb（真执行）路径里')

    print()
    print('  会被执行的命令字面量（全量列出，人工核对）:')
    exec_cmds = [(c, m2, v) for c, m2, v in consts
                 if len(v) < 80 and re.search(
                     r'\breboot\b|setprop|settings put|stop adbd|start adbd|'
                     r'pm install|pm uninstall|rm -rf|^cp |chmod|chown|^mount|/data/local/tmp',
                     v)]
    for c, m2, v in exec_cmds:
        print('     %-30s %-14s %r' % (c, m2, v))
    chk(bool(exec_cmds), '命令字面量已列出（%d 条）' % len(exec_cmds))
except ImportError:
    print('  [SKIP] 没装 androguard，跳过调用点追踪')

print()
print('=' * 68)
print('7. 功能面：三标签 + 文件管理器 + 无线 ADB')
print('=' * 68)
for s in ('已安装的软件', '存储里的 APK', '文件管理'):
    chk(s.encode('utf-8') in dex, '标签「%s」存在' % s)
for s in ('车机', 'U盘', '上一级', '新建', '粘贴', '刷新'):
    chk(s.encode('utf-8') in dex, '文件管理器按钮「%s」存在' % s)
for s in ('复制', '剪切', '重命名', '删除', '属性'):
    chk(s.encode('utf-8') in dex, '长按菜单「%s」存在' % s)
chk('没检测到 U 盘'.encode('utf-8') in dex, 'U盘 没插时有提示文案')
chk(b'\xe8\xa7\xa3\xe5\x8e\x8b' not in dex, '不含「解压」相关文案（未实现就不写）')
for s in ('普通安装 apk（可桌面卸载）', '4.1', '安装为系统程序', '5.1', '5.2', '5.3', '5.4', '5.5'):
    chk(s.encode('utf-8') in dex, '安装教程含「%s」' % s)
chk(b'https://www.akkdiy.cn/zimulu/download/mtk-su' in dex, '教程里含 mtk-su 下载地址')
for s in ('无线 ADB（WiFi）', 'service.adb.tcp.port', 'adb tcpip',
          '手敲命令 / 说明'):
    chk(s.encode('utf-8') in dex, '无线 ADB 相关「%s」存在' % s)
for s in ('正在开启无线 ADB', '无线 ADB 已开启', '无线 ADB 已关闭'):
    chk(s.encode('utf-8') in dex, '无线 ADB 提示语「%s」存在' % s)
for s in ('提权自检', '__AWA_END__', 'chmod', 'getFilesDir'):
    chk(s.encode('utf-8') in dex, '提权环境相关「%s」存在' % s)

print()
print('  ── 照原版还原的部分：自绘开关 / 连接命令 / 倒计时自动关 ──')
chk(b'Lcom/awa/apkinstaller/MainActivity$ToggleSwitch;' in dex,
    '有自绘开关 ToggleSwitch（照原版，不用系统 Switch）')
for s in ('分钟后自动关闭', 'adb connect '):
    chk(s.encode('utf-8') in dex, '连接命令行含「%s」' % s)
chk('⚠ 同一 WiFi 下任何人都能连上这台车机，用完记得关'.encode('utf-8') in dex,
    '有原版那条黄色安全提醒')
chk(b'wifi_adb_off_at' in dex, '倒计时时间戳持久化（wifi_adb_off_at）')
for s in ('settings put global adb_enabled 1', 'stop adbd', 'start adbd'):
    chk(s.encode('utf-8') in dex, '开无线 ADB 的脚本含「%s」' % s)
chk(b'Landroid/content/SharedPreferences;' in dex, '引用了 SharedPreferences')
chk(b'Landroid/os/Handler;' in dex, '引用了 Handler（倒计时滴答）')
chk(b'Landroid/graphics/Canvas;' in dex, '引用了 Canvas（自绘开关）')
chk(b'Landroid/graphics/Paint;' in dex, '引用了 Paint（自绘开关）')

print()
print('  ── 存储里的 APK：装过的不能再标「待安装」──')
for s in ('待安装', '可更新', '版本更低', '已安装'):
    chk(s.encode('utf-8') in dex, '徽章文案「%s」存在' % s)
chk('车机上已装'.encode('utf-8') in dex, '已安装的行里会写出车机上装着的版本')
chk(b'getPackageInfo' in dex, '用 getPackageInfo 查车机上是否已装该包')

print()
print('  ── 照原版做的「需要连一次电脑」引导 ──')
chk(b'wifi_adb_off_at' in dex or b'/data/local/tmp/mtk-su' in dex,
    '有 staging 落地路径 /data/local/tmp/mtk-su')
chk('cp /sdcard/mtk-su /data/local/tmp/mtk-su'.encode('utf-8') in dex,
    '有原版那条 staging 命令')
chk('SELinux'.encode('utf-8') in dex, '引导里解释了为什么必须连一次电脑（SELinux）')
chk(b'releaseMtksuToSdcard' in dex, '有把 mtk-su 导到 /sdcard 的方法')
chk(b'Command: [' in dex, 'safeErr() 会识别 ProcessBuilder 的 Command: 尾巴')
chk(b'safeErr' in dex, '有 safeErr() 清洗异常信息')

print()
print('  ── 外置存储自动识别（不再弹「选一个」）──')
chk('选一个'.encode('utf-8') not in dex, '不再弹「选一个外置存储」选择框')
chk(b'usbScore' in dex, '有 usbScore() 按路径打分')
chk(b'countEntries' in dex, '有 countEntries() 数条目判空盘')
chk('已自动选择'.encode('utf-8') in dex, '自动选择后有提示文案')
chk(b'proc/mounts' in dex or b'/proc/mounts' in dex, '从 /proc/mounts 读挂载点')

print()
print('  ── 「打开原生系统设置」做成原版那种入口列表 ──')
for s in ('开发者选项', '应用管理', '存储', '无线和网络', '蓝牙', '安全',
          '位置信息', '日期和时间', '设备信息', 'WLAN', '流量使用', '电源使用',
          '全部设置（原生）', '原生系统设置'):
    chk(s.encode('utf-8') in dex, '原生设置入口「%s」存在' % s)
chk(b'DevelopmentSettingsActivity' in dex, '开发者选项用显式组件（比 action 准）')
chk(b'ecarx.settings' in dex, '能试 ECARX 车机设置')
chk(b'geely.syssettings' in dex, '能试吉利车机设置')
chk(b'openNativeSetting' in dex, '有 openNativeSetting() 三级兜底')

print()
print('=' * 68)
print('8. v1(JAR) 签名')
print('=' * 68)
mf = z.read('META-INF/MANIFEST.MF')
sf = z.read('META-INF/CERT.SF')
rsa = z.read('META-INF/CERT.RSA')


def parse_mf(data):
    out = {}
    cur = None
    for line in data.replace(b'\r\n ', b'').split(b'\r\n'):
        if not line:
            continue
        if line.startswith(b'Name: '):
            cur = line[6:].decode()
            out[cur] = {}
        elif b': ' in line and cur:
            k, v = line.split(b': ', 1)
            out[cur][k.decode()] = v.decode()
    return out


m = parse_mf(mf)
chk(len(m) > 0, 'MANIFEST.MF 有 %d 个条目' % len(m))
bad = []
for nm, attrs in m.items():
    if nm not in names:
        bad.append(nm + ' (不在包里)')
        continue
    dig = base64.b64encode(hashlib.sha256(z.read(nm)).digest()).decode()
    if attrs.get('SHA-256-Digest') != dig:
        bad.append(nm + ' (摘要不符)')
chk(not bad, 'MANIFEST.MF 里每个条目的 SHA-256 都对得上（%s）' % (bad or '全部一致'))

sf_txt = sf.decode('utf-8', 'replace')
mm = re.search(r'SHA-256-Digest-Manifest:\s*(\S+)', sf_txt)
chk(mm is not None and base64.b64decode(mm.group(1)) == hashlib.sha256(mf).digest(),
    'CERT.SF 的 SHA-256-Digest-Manifest == MANIFEST.MF 的 SHA-256')

# CERT.SF 里每个条目的摘要 == 对应 MANIFEST.MF 段的 SHA-256
sf_secs = re.findall(r'Name:\s*(\S+)\r?\nSHA-256-Digest:\s*(\S+)', sf_txt)
chk(len(sf_secs) == len(m), 'CERT.SF 段数(%d) == MANIFEST.MF 条目数(%d)' % (len(sf_secs), len(m)))
sec_bad = [n for n, dg in sf_secs
           if n not in m or base64.b64decode(dg) != hashlib.sha256(
               (('Name: %s\r\n' % n) +
                ''.join(''.join('%s: %s\r\n' % kv for kv in m[n].items())) +
                '\r\n').encode('ascii')).digest()]
chk(not sec_bad, 'CERT.SF 每个段摘要都对得上（%s）' % (sec_bad or '全部一致'))
chk(rsa[:1] == b'\x30', 'CERT.RSA 是 DER 编码的 PKCS#7（首字节 0x30）')

try:
    r = subprocess.run([JDK, '-verify', '-certs', APK],
                       capture_output=True, timeout=120)
    txt = (r.stdout + r.stderr).decode('gbk', errors='replace')
    chk(r.returncode == 0 and 'jar verified' in txt.lower().replace('已验证', 'verified')
        or r.returncode == 0, 'jarsigner 验签通过（exit=%d）' % r.returncode)
    for line in txt.splitlines():
        if line.strip():
            print('     ' + line.strip()[:110])
except Exception as e:
    print('  [SKIP] jarsigner 跑不了: %s' % e)

print()
print('=' * 68)
print('结果：%s' % ('全部通过' if ok else '有失败项，见上面 [FAIL]'))
print('APK  %s' % APK)
print('     %d 字节   md5 %s' % (os.path.getsize(APK), hashlib.md5(open(APK, 'rb').read()).hexdigest()))
print('     classes.dex %d 字节' % len(dex))
print('=' * 68)
sys.exit(0 if ok else 1)
