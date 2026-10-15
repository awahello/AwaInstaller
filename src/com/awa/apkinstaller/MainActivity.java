package com.awa.apkinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Awa的安装助手
 *
 * 三块内容，顶部标签切换：
 *   1) 已安装的软件 —— 系统 + 用户应用，点条目 → 卸载教程
 *   2) 存储里的 APK —— U盘 / 内部存储，点条目 → 安装教程
 *   3) 文件管理     —— 自带文件管理器：浏览 /sdcard 和 U盘，复制 / 粘贴 / 删除 /
 *                       重命名 / 新建文件夹 / 看属性 / 调其他应用打开
 *
 * 点条目只弹对应的 ADB 教程命令，本工具不替你安装、不替你卸载。
 *
 * 顶栏两个按钮（和原版一致）：
 *   · 设置 —— 车机信息、ADB / 无线 ADB / WiFi 开关、原生设置入口
 *   · 重启 —— 只用系统 REBOOT 权限（装到 /system/priv-app 即免 root 生效）
 *
 * ⚠ 关于提权 —— 全工具只有「无线 ADB」这一处会用到 root。
 *   其余所有功能（安装教程 / 卸载教程 / 文件管理 / 重启 / ADB 开关 / WiFi 开关）
 *   一律不提权，不会去找 su，不会执行任何提权命令。
 *
 *   无线 ADB 为什么必须提权：
 *     它靠系统属性 service.adb.tcp.port 控制，而系统属性只有 root 能写。
 *     Android 5.1 的 Settings.Global 里没有 adb_tcp_port 这个设置项
 *     （那是 Android 6.0 才加的），系统压根不听，所以车机内部
 *     不存在「免 root 开无线 ADB」的接口 —— 想做成按钮就只能借 root。
 *
 *   提权做法照原版 AwaAPK：包里内置 assets/mtk-su，第一次用时自动释放到
 *   应用私有目录并 chmod；候选顺序是「上次跑通的 → /data/local/tmp/ →
 *   私有目录释放的 → 系统 su」，每个先试 `su -c`，不行再把脚本喂给 stdin，
 *   末尾追加 echo __AWA_END__ 判定是否真跑通。
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------ 配色
    private static final int C_BG = 0xFF14161A;
    private static final int C_BAR = 0xFF1D2026;
    private static final int C_CARD = 0xFF22262E;
    private static final int C_TX = 0xFFE9EDF3;
    private static final int C_TX2 = 0xFF9BA5B4;
    private static final int C_TX3 = 0xFF6C7787;
    private static final int C_ACC = 0xFF3B82F6;
    private static final int C_SYS = 0xFFC2761B;
    private static final int C_USR = 0xFF1F8A4C;
    private static final int C_WARN = 0xFFFFD166;

    private static final int TAB_INSTALLED = 0;
    private static final int TAB_STORAGE = 1;
    private static final int TAB_FILES = 2;
    private static final int MAX_APK = 400;

    private static final String APP_TITLE = "Awa的安装助手";

    /** mtk-su 官方下载地址。程序不会去下载它，只出现在教程文案里 */
    private static final String MTK_URL = "https://www.akkdiy.cn/zimulu/download/mtk-su";

    /** 无线 ADB 用的 TCP 端口 */
    private static final int ADB_TCP_PORT = 5555;

    // ---- 提权环境（全工具只有「无线 ADB」会用到）
    /** 脚本末尾追加的结束标记 —— 输出里出现它才算真跑通 */
    private static final String END_MARK = "__AWA_END__";
    /** 系统里本来就带的 su（机器已经 root 过的情况） */
    private static final String[] SYSTEM_SU = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/system/bin/magisk",
    };
    /** 上次跑通的提权程序，下次优先用它 */
    private String workingSu = null;
    /** 试过没跑通的，下次跳过 */
    private final Set<String> failedSu = new HashSet<String>();
    /** 从 assets 释放到私有目录的 mtk-su */
    private File privateSu = null;

    private int tab = TAB_INSTALLED;
    private int appFilter = 0;   // 0 全部  1 系统应用  2 用户应用
    private int storeMode = 0;   // 0 U盘   1 内部存储

    private volatile boolean busy = false;
    private volatile boolean pendingApps = false;
    private volatile boolean pendingApks = false;

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final List<ApkEntry> apks = new ArrayList<ApkEntry>();
    private final List<FileEntry> files = new ArrayList<FileEntry>();

    private Button tabBtn0, tabBtn1, tabBtn2;
    private LinearLayout toolbar;
    private ListView list;
    private TextView footerView;
    private BaseAdapter adapter;

    // ---- 文件管理
    private File curDir;
    private final List<File> clip = new ArrayList<File>();
    private boolean clipCut = false;
    private List<File> usbCache = null;      // usbRoots() 的短时缓存
    private long usbCacheAt = 0;

    // 设置面板
    private LinearLayout settingsBox;
    private ScrollView settingsScroll;
    private AlertDialog settingsDialog;

    // ---- 设置面板里的控件（照原版：面板只建一次，之后只刷状态）
    private ToggleSwitch adbToggle, wifiToggle, wlanToggle;
    private TextView ipValueText, adbStateText, wifiStateText, wlanStateText;
    private TextView wifiExtraText, wifiWarnText;
    private volatile boolean adbBusy = false;
    private volatile boolean wifiBusy = false;
    /** 无线 ADB 到点自动关的时间戳，0 = 没在倒计时。存 SharedPreferences 里，重启也不丢 */
    private long wifiOffAt = 0;
    /** 本机 IP（纯地址，不带网卡名），给 adb connect 用 */
    private String lastIp = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 无线 ADB 开多久自动关（照原版：30 分钟） */
    private static final long WIFI_ADB_KEEP_MS = 30 * 60 * 1000L;
    private static final String PREF_OFF_AT = "wifi_adb_off_at";
    /** 原版那套面板配色 */
    private static final int P_ON = 0xFF33B5E5;      // ADB 开着（蓝）
    private static final int P_OFF = 0xFF8A93A6;     // 关着（灰）
    private static final int P_OK = 0xFF7ED6A5;      // 好（绿）
    private static final int P_WARN = 0xFFFFD166;    // 提醒（黄）
    private static final int P_SEP = 0xFF4A4A4A;     // 分隔线

    // ------------------------------------------------------------ 生命周期
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        showTab(TAB_INSTALLED);
    }

    /**
     * 回到前台时把「无线 ADB 自动关闭」的倒计时接上。
     * 原版就是靠这个 —— 退到后台再回来，剩余时间还是接着算的，
     * 而且时间一到会自动把无线 ADB 关掉（开着的时候同一 WiFi 下谁都能连进来）。
     */
    @Override
    protected void onResume() {
        super.onResume();
        resumeWifiAdbTimer();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(wifiTick);
        super.onDestroy();
    }

    /**
     * 返回键。以前没写这个，在文件管理里一按返回就直接退出应用了。
     * 现在的顺序：设置面板开着 → 先关面板；文件管理里 → 先往上一级
     * （在 U 盘里就直接跳回车机目录）；其他标签 → 先回第一个标签；都没有才退出。
     */
    @Override
    public void onBackPressed() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
            return;
        }
        if (tab == TAB_FILES && curDir != null) {
            File home = innerHome();
            if (under(curDir, home)) {
                if (!curDir.getAbsolutePath().equals(home.getAbsolutePath())) {
                    File p = curDir.getParentFile();
                    if (p != null && !p.equals(curDir)) { openDir(p); return; }
                }
            } else {
                openDir(home);          // 在 U 盘里 → 跳回车机目录
                return;
            }
        }
        if (tab != TAB_INSTALLED) { showTab(TAB_INSTALLED); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ 界面骨架
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- 顶栏：标题 + 设置 + 重启（和原版一致的位置）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(C_BAR);
        head.setPadding(dp(16), dp(10), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText(APP_TITLE);
        title.setTextColor(C_TX);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnSet = barButton("设置", C_TX);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(14);
        head.addView(btnSet, blp);
        btnSet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });

        Button btnReboot = barButton("重启", C_WARN);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.leftMargin = dp(6);
        head.addView(btnReboot, blp2);
        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReboot(); }
        });

        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        Button btnAbout = barButton("关于", C_TX2);
        head.addView(btnAbout);
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        });
        root.addView(head);

        // ---- 标签栏
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(C_BAR);
        tabs.setPadding(dp(12), 0, dp(12), dp(8));

        tabBtn0 = makeTabButton("已安装的软件");
        tabBtn1 = makeTabButton("存储里的 APK");
        tabBtn2 = makeTabButton("文件管理");
        tabs.addView(tabBtn0, tabLp(0));
        tabs.addView(tabBtn1, tabLp(8));
        tabs.addView(tabBtn2, tabLp(8));

        tabBtn0.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(TAB_INSTALLED); }
        });
        tabBtn1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(TAB_STORAGE); }
        });
        tabBtn2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(TAB_FILES); }
        });
        root.addView(tabs);

        // ---- 工具栏
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(toolbar);

        // ---- 列表
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(C_BG);
        list.setScrollingCacheEnabled(false);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底栏
        footerView = new TextView(this);
        footerView.setBackgroundColor(C_BAR);
        footerView.setTextColor(C_TX3);
        footerView.setTextSize(11.5f);
        footerView.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(footerView);

        adapter = new BaseAdapter() {
            @Override public int getCount() {
                if (tab == TAB_INSTALLED) return apps.size();
                if (tab == TAB_STORAGE) return apks.size();
                return files.size();
            }
            @Override public Object getItem(int p) { return null; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View reuse, ViewGroup parent) {
                RowHolder h;
                if (reuse != null && reuse.getTag() instanceof RowHolder) {
                    h = (RowHolder) reuse.getTag();
                } else {
                    h = buildRow();
                }
                try {
                    if (tab == TAB_INSTALLED) {
                        if (p >= 0 && p < apps.size()) fillAppRow(h, apps.get(p));
                    } else if (tab == TAB_STORAGE) {
                        if (p >= 0 && p < apks.size()) fillApkRow(h, apks.get(p));
                    } else {
                        if (p >= 0 && p < files.size()) fillFileRow(h, files.get(p));
                    }
                } catch (Throwable t) {
                    h.l1.setText("（这一行读取出错）");
                    h.l2.setText(""); h.l3.setText(""); h.l4.setText("");
                    h.icon.setImageDrawable(null);
                    h.badge.setText("");
                }
                return h.outer;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int p, long id) {
                try {
                    if (tab == TAB_INSTALLED) {
                        if (p >= 0 && p < apps.size()) showUninstallTutorial(apps.get(p));
                    } else if (tab == TAB_STORAGE) {
                        if (p >= 0 && p < apks.size()) showInstallTutorial(apks.get(p));
                    } else {
                        if (p >= 0 && p < files.size()) openEntry(files.get(p));
                    }
                } catch (Throwable t) {
                    toast("出错了：" + t);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> a, View v, int p, long id) {
                if (tab != TAB_FILES) return false;
                try {
                    if (p >= 0 && p < files.size()) showFileMenu(files.get(p));
                } catch (Throwable t) {
                    toast("出错了：" + t);
                }
                return true;
            }
        });
        return root;
    }

    private Button barButton(String text, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackgroundColor(0xFF1A2130);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private Button makeTabButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14f);
        btn.setAllCaps(false);
        return btn;
    }

    private LinearLayout.LayoutParams tabLp(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (leftDp > 0) lp.leftMargin = dp(leftDp);
        return lp;
    }

    private void styleTab(Button btn, boolean on) {
        btn.setTextColor(on ? 0xFFFFFFFF : C_TX2);
        btn.setBackgroundColor(on ? C_ACC : C_CARD);
    }

    /** 工具条上的小胶囊按钮 */
    private Button chipBtn(String text, boolean on, boolean leftMargin, final Runnable action) {
        Button c = makeChip(text, on);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        if (leftMargin) lp.leftMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private Button makeChip(String text, boolean on) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12.5f);
        btn.setAllCaps(false);
        btn.setTextColor(on ? 0xFFFFFFFF : C_TX2);
        btn.setBackgroundColor(on ? C_ACC : C_CARD);
        btn.setPadding(dp(10), 0, dp(10), 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        return btn;
    }

    // ------------------------------------------------------------ 标签 / 工具栏
    private void showTab(int t) {
        tab = t;
        styleTab(tabBtn0, t == TAB_INSTALLED);
        styleTab(tabBtn1, t == TAB_STORAGE);
        styleTab(tabBtn2, t == TAB_FILES);
        buildToolbar();
        if (t == TAB_INSTALLED) startLoadApps();
        else if (t == TAB_STORAGE) startLoadApks();
        else openDir(curDir == null ? innerHome() : curDir);
    }

    private void buildToolbar() {
        toolbar.removeAllViews();
        if (tab == TAB_INSTALLED) {
            String[] names = {"全部", "系统应用", "用户应用"};
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                toolbar.addView(chipBtn(names[i], appFilter == i, i > 0, new Runnable() {
                    @Override public void run() {
                        if (appFilter == idx) return;
                        appFilter = idx;
                        buildToolbar();
                        applyAppFilter();
                    }
                }));
            }
        } else if (tab == TAB_STORAGE) {
            String[] names = {"U盘", "内部存储"};
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                toolbar.addView(chipBtn(names[i], storeMode == i, i > 0, new Runnable() {
                    @Override public void run() {
                        if (storeMode == idx) return;
                        storeMode = idx;
                        buildToolbar();
                        startLoadApks();
                    }
                }));
            }
        } else {
            final List<File> usb = usbRootsCached();
            toolbar.addView(chipBtn("车机", under(curDir, innerHome()), false, new Runnable() {
                @Override public void run() { openDir(innerHome()); }
            }));
            toolbar.addView(chipBtn("U盘", underAny(curDir, usb), true, new Runnable() {
                @Override public void run() { openUsb(); }
            }));
            toolbar.addView(chipBtn("上一级", false, true, new Runnable() {
                @Override public void run() { goUp(); }
            }));
            toolbar.addView(chipBtn("新建", false, true, new Runnable() {
                @Override public void run() { newFolder(); }
            }));
            toolbar.addView(chipBtn("粘贴", clip.size() > 0, true, new Runnable() {
                @Override public void run() { paste(); }
            }));
            toolbar.addView(chipBtn("刷新", false, true, new Runnable() {
                @Override public void run() { openDir(curDir); }
            }));
        }
        View sp = new View(this);
        toolbar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        if (tab == TAB_FILES) return;   // 文件管理页用「刷新」就够了

        Button re = new Button(this);
        re.setText("重新扫描");
        re.setTextSize(12.5f);
        re.setAllCaps(false);
        re.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab == TAB_INSTALLED) startLoadApps();
                else startLoadApks();
            }
        });
        toolbar.addView(re, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ 异步加载
    private void startLoadApps() {
        if (busy) { pendingApps = true; return; }
        busy = true;
        footerView.setText("正在读取已安装的软件…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<AppEntry> res = collectApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        allApps.clear();
                        allApps.addAll(res);
                        busy = false;
                        applyAppFilter();
                        if (pendingApps) { pendingApps = false; startLoadApps(); }
                    }
                });
            }
        }).start();
    }

    private void startLoadApks() {
        if (busy) { pendingApks = true; return; }
        busy = true;
        footerView.setText("正在扫描存储，请稍候…");
        final int mode = storeMode;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ApkEntry> res = collectApks(mode);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        apks.clear();
                        apks.addAll(res);
                        busy = false;
                        adapter.notifyDataSetChanged();
                        updateFooter();
                        if (pendingApks) { pendingApks = false; startLoadApks(); }
                    }
                });
            }
        }).start();
    }

    private void applyAppFilter() {
        apps.clear();
        for (int i = 0; i < allApps.size(); i++) {
            AppEntry a = allApps.get(i);
            if (appFilter == 1 && !a.system) continue;
            if (appFilter == 2 && a.system) continue;
            apps.add(a);
        }
        adapter.notifyDataSetChanged();
        updateFooter();
    }

    // ------------------------------------------------------------ 采集：已安装
    private List<AppEntry> collectApps() {
        List<AppEntry> out = new ArrayList<AppEntry>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            return out;
        }
        for (PackageInfo pi : all) {
            if (pi == null || pi.applicationInfo == null) continue;
            ApplicationInfo ai = pi.applicationInfo;
            AppEntry e = new AppEntry();
            e.pkg = pi.packageName == null ? "" : pi.packageName;
            e.verName = pi.versionName == null ? "" : pi.versionName;
            e.verCode = pi.versionCode;
            e.srcDir = ai.sourceDir == null ? "" : ai.sourceDir;
            try {
                e.label = String.valueOf(ai.loadLabel(pm));
            } catch (Throwable t) {
                e.label = e.pkg;
            }
            try {
                e.icon = ai.loadIcon(pm);
            } catch (Throwable t) {
                e.icon = null;
            }
            int slash = e.srcDir.lastIndexOf('/');
            if (slash >= 0) {
                e.dirPath = e.srcDir.substring(0, slash);
                e.fileName = e.srcDir.substring(slash + 1);
            } else {
                e.dirPath = e.srcDir;
                e.fileName = e.srcDir;
            }
            e.system = isSystemPath(e.srcDir)
                    || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.priv = e.srcDir.startsWith("/system/priv-app")
                    || e.srcDir.startsWith("/system_ext/priv-app")
                    || e.srcDir.startsWith("/product/priv-app");
            try {
                e.size = new File(e.srcDir).length();
            } catch (Throwable t) {
                e.size = 0;
            }
            out.add(e);
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                if (a.system != b.system) return a.system ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private static boolean isSystemPath(String p) {
        if (p == null) return false;
        return p.startsWith("/system/") || p.startsWith("/system_ext/")
                || p.startsWith("/product/") || p.startsWith("/vendor/")
                || p.startsWith("/oem/") || p.startsWith("/cust/");
    }

    // ------------------------------------------------------------ 采集：存储
    private List<ApkEntry> collectApks(int mode) {
        List<ApkEntry> out = new ArrayList<ApkEntry>();
        Set<String> seen = new HashSet<String>();
        List<File> roots = (mode == 0 ? usbRoots() : innerRoots());
        for (int i = 0; i < roots.size(); i++) {
            scanDir(roots.get(i), out, seen, 0);
            if (out.size() >= MAX_APK) break;
        }
        Collections.sort(out, new Comparator<ApkEntry>() {
            @Override public int compare(ApkEntry a, ApkEntry b) {
                // 没装的排前面 —— 这些才是真正要动手装的；
                // 车机上已经装了的沉到后面，免得混在「待安装」里看着乱
                int ra = apkRank(a), rb = apkRank(b);
                if (ra != rb) return ra - rb;
                return a.fileName.compareToIgnoreCase(b.fileName);
            }
        });
        return out;
    }

    /** APK 排序权重：0 待安装 / 1 可更新 / 2 解析失败 / 3 版本更低 / 4 已安装 */
    private static int apkRank(ApkEntry e) {
        if (e.parseFailed) return 2;
        if (!e.installed) return 0;
        if (e.verCode > e.installedVerCode) return 1;
        if (e.verCode < e.installedVerCode) return 3;
        return 4;
    }

    private List<File> innerRoots() {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        String[] fixed = {
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                "/storage/emulated/0", "/mnt/sdcard", "/sdcard", "/mnt/sdcard0",
                "/storage/sdcard0", "/storage/sdcard1", "/mnt/external_sd"
        };
        for (int i = 0; i < fixed.length; i++) addDir(out, seen, new File(fixed[i]));
        return out;
    }

    /**
     * 找外置存储（U盘）。三个来源合起来去重：
     *   ① /proc/mounts 里 fs 类型是 vfat / exfat / ntfs … 的挂载点 —— 最可靠。
     *      车机的 U 盘挂载点五花八门（/mnt/usbhost/1、/storage/1A2B-3C4D…），
     *      只有从挂载表读才能覆盖全。
     *   ② /storage 下除 emulated / self 之外的目录（现代 Android 拿卷 ID 当挂载点）。
     *   ③ 常见固定路径兜底。
     */
    private List<File> usbRoots() {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();

        // ① /proc/mounts
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/mounts"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(" ");
                if (p.length < 3) continue;
                if (!isRemovableFs(p[2])) continue;
                String mnt = p[1].replace("\\040", " ");
                if (isInnerMount(mnt)) continue;
                if (mnt.startsWith("/mnt/secure") || mnt.startsWith("/mnt/asec")
                        || mnt.startsWith("/mnt/obb")) continue;
                addDir(out, seen, new File(mnt));
            }
        } catch (Throwable t) {
            // 读不到就靠下面兜底
        } finally {
            closeQ(br);
        }

        // ② /storage 下的卷 ID 目录（1A2B-3C4D 这种）
        File[] cs = null;
        try { cs = new File("/storage").listFiles(); } catch (Throwable t) { cs = null; }
        if (cs != null) {
            for (int i = 0; i < cs.length; i++) {
                File c = cs[i];
                if (c == null || !c.isDirectory()) continue;
                String n = c.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("enc_emulated")) continue;
                if (n.equals("sdcard") || n.equals("sdcard0")) continue;
                if (isInnerMount(c.getAbsolutePath())) continue;
                addDir(out, seen, c);
            }
        }

        // ③ 固定路径兜底
        String[] fixed = {
                "/mnt/usb/sda1", "/mnt/usb/sda", "/mnt/usb/sdb1", "/mnt/usb/sdb",
                "/mnt/usb_storage", "/mnt/usbdrive", "/mnt/udisk", "/storage/usbotg",
                "/storage/udisk", "/storage/sda1", "/storage/usbdisk", "/storage/usbhost1",
                "/mnt/media_rw/usbdisk", "/mnt/media_rw/sda1", "/mnt/media_rw/usb",
                "/mnt/usbhost", "/mnt/usbhost1", "/mnt/usbhost/1", "/storage/usbhost",
                "/mnt/extsd", "/mnt/ext_sd", "/mnt/external_sd", "/storage/external_sd"
        };
        for (int i = 0; i < fixed.length; i++) addDir(out, seen, new File(fixed[i]));

        // 最后按「最像用户插的 U 盘」排一遍序 —— 后面所有取 usb.get(0) 的地方
        // （底栏提示、APK 扫描顺序）就都落在真正那个盘上，不用各自再挑一次
        sortByUsbScore(out);
        return out;
    }

    /** 外置存储排序用的分数：usb 名字优先，其次有条目的，再其次 extsd */
    private static int usbScore(File f) {
        if (f == null) return Integer.MIN_VALUE;
        int score = 0;
        String p;
        try {
            p = f.getAbsolutePath().toLowerCase(Locale.US);
        } catch (Throwable t) {
            p = "";
        }
        if (p.contains("usb")) score += 1000;
        else if (p.contains("extsd") || p.contains("external")) score += 400;
        score += Math.min(countEntries(f), 300);
        return score;
    }

    private static void sortByUsbScore(List<File> list) {
        try {
            Collections.sort(list, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return usbScore(b) - usbScore(a);
                }
            });
        } catch (Throwable t) { }
    }

    /** U 盘常见的文件系统。fuse 是内部存储，不能算 */
    private static boolean isRemovableFs(String fs) {
        return fs.equals("vfat") || fs.equals("exfat") || fs.equals("texfat")
                || fs.equals("ntfs") || fs.equals("fuseblk") || fs.equals("msdos")
                || fs.equals("iso9660") || fs.equals("udf");
    }

    /** 这个挂载点是不是内部存储（要排除掉） */
    private static boolean isInnerMount(String path) {
        if (path == null) return true;
        if (path.startsWith("/storage/emulated") || path.startsWith("/mnt/sdcard")
                || path.startsWith("/storage/sdcard0") || path.startsWith("/mnt/shell")
                || path.startsWith("/mnt/runtime") || path.startsWith("/data/media")) return true;
        try {
            if (path.equals(Environment.getExternalStorageDirectory().getCanonicalPath())) {
                return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    /** usbRoots() 带缓存 —— buildToolbar() 每次切标签都会问，不能每次都去翻挂载表 */
    private List<File> usbRootsCached() {
        long now = System.currentTimeMillis();
        if (usbCache != null && now - usbCacheAt < 8000) return usbCache;
        usbCache = usbRoots();
        usbCacheAt = now;
        return usbCache;
    }

    private static void addDir(List<File> out, Set<String> seen, File f) {
        if (f == null) return;
        if (!f.isDirectory()) return;
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (Throwable t) {
            p = f.getAbsolutePath();
        }
        if (seen.add(p)) out.add(f);
    }

    private void scanDir(File dir, List<ApkEntry> out, Set<String> seen, int depth) {
        if (dir == null || depth > 4 || out.size() >= MAX_APK) return;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (int i = 0; i < fs.length; i++) {
            if (out.size() >= MAX_APK) return;
            File f = fs[i];
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < 4) scanDir(f, out, seen, depth + 1);
                continue;
            }
            if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
            String path;
            try {
                path = f.getCanonicalPath();
            } catch (Throwable t) {
                path = f.getAbsolutePath();
            }
            if (!seen.add(path)) continue;
            // 同一块盘被挂到多个路径时（/mnt/media_rw/xxx 和 /storage/xxx
            // 这种绑定挂载，getCanonicalPath 解析不出来），同一个 APK 会
            // 带着不同的绝对路径出现两遍，列表里就「显示两个」。
            // 名字、大小、修改时间全一样的基本就是同一个文件，再挡一道。
            String idKey = "id:" + name + "|" + f.length() + "|" + f.lastModified();
            if (!seen.add(idKey)) continue;
            out.add(makeApkEntry(f, name, path));
        }
    }

    private ApkEntry makeApkEntry(File f, String name, String path) {
        ApkEntry e = new ApkEntry();
        e.fileName = name;
        e.path = path;
        e.dirPath = f.getParent() == null ? "" : f.getParent();
        try {
            e.size = f.length();
        } catch (Throwable t) {
            e.size = 0;
        }
        PackageManager pm = getPackageManager();
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi != null) {
                e.pkg = pi.packageName == null ? "" : pi.packageName;
                e.verName = pi.versionName == null ? "" : pi.versionName;
                e.verCode = pi.versionCode;
                if (pi.applicationInfo != null) {
                    pi.applicationInfo.sourceDir = path;
                    pi.applicationInfo.publicSourceDir = path;
                    try {
                        e.label = String.valueOf(pi.applicationInfo.loadLabel(pm));
                    } catch (Throwable t) {
                        e.label = e.pkg;
                    }
                    try {
                        e.icon = pi.applicationInfo.loadIcon(pm);
                    } catch (Throwable t) {
                        e.icon = null;
                    }
                }
            }
        } catch (Throwable t) {
            // 读不出来就当解析失败
        }
        if (TextUtils.isEmpty(e.pkg)) e.parseFailed = true;

        // 这个包车机上是不是已经装着了 —— 装着的话不能再标「待安装」
        if (!e.parseFailed) {
            try {
                PackageInfo inst = pm.getPackageInfo(e.pkg, 0);
                e.installed = true;
                e.installedVerCode = inst.versionCode;
                e.installedVerName = inst.versionName == null ? "" : inst.versionName;
            } catch (Throwable t) {
                e.installed = false;
            }
        }
        return e;
    }

    private void updateFooter() {
        if (tab == TAB_INSTALLED) {
            int sys = 0;
            for (int i = 0; i < apps.size(); i++) if (apps.get(i).system) sys++;
            String extra = appFilter == 0 ? "" : "（已筛选，全部共 " + allApps.size() + " 个）";
            footerView.setText("当前列出 " + apps.size() + " 个：系统 " + sys + " / 用户 "
                    + (apps.size() - sys) + extra + "　·　点条目 → 卸载教程");
        } else if (tab == TAB_STORAGE) {
            int wait = 0, up = 0, has = 0, bad = 0;
            for (int i = 0; i < apks.size(); i++) {
                ApkEntry e = apks.get(i);
                if (e.parseFailed) bad++;
                else if (!e.installed) wait++;
                else if (e.verCode > e.installedVerCode) up++;
                else has++;
            }
            String tip = "";
            if (storeMode == 0 && apks.size() == 0) {
                List<File> u = usbRootsCached();
                tip = u.isEmpty()
                        ? "　·　没检测到 U 盘，切「内部存储」看看"
                        : "　·　U 盘挂在 " + u.get(0).getAbsolutePath() + "，里面没有 apk";
            }
            footerView.setText("共 " + apks.size() + " 个 APK"
                    + "　·　待安装 " + wait + " / 可更新 " + up + " / 已安装 " + has
                    + (bad > 0 ? " / 解析失败 " + bad : "")
                    + tip + "　·　点条目 → 安装教程");
        } else {
            String p = curDir == null ? "—" : curDir.getAbsolutePath();
            String c = clip.size() > 0
                    ? "　·　剪贴板 " + clip.size() + " 项（" + (clipCut ? "剪切" : "复制") + "）" : "";
            footerView.setText(p + "　·　" + files.size() + " 项" + c
                    + "　·　点进入 / 长按条目 → 复制·剪切·重命名·删除·属性");
        }
    }

    // ------------------------------------------------------------ 列表行（带复用）
    static class RowHolder {
        LinearLayout outer, row, col;
        ImageView icon;
        TextView glyph;          // 没有真实图标时用的文字占位（DIR / APK …）
        TextView l1, l2, l3, l4, badge;
    }

    private RowHolder buildRow() {
        RowHolder h = new RowHolder();

        h.outer = new LinearLayout(this);
        h.outer.setOrientation(LinearLayout.VERTICAL);
        h.outer.setPadding(dp(10), dp(4), dp(10), dp(4));

        h.row = new LinearLayout(this);
        h.row.setOrientation(LinearLayout.HORIZONTAL);
        h.row.setGravity(Gravity.CENTER_VERTICAL);
        h.row.setBackgroundColor(C_CARD);
        h.row.setPadding(dp(14), dp(11), dp(14), dp(11));
        h.outer.addView(h.row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout iw = new FrameLayout(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(42), dp(42));
        ilp.rightMargin = dp(12);
        h.row.addView(iw, ilp);

        h.icon = new ImageView(this);
        h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iw.addView(h.icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        h.glyph = new TextView(this);
        h.glyph.setTextSize(11.5f);
        h.glyph.setTypeface(Typeface.DEFAULT_BOLD);
        h.glyph.setTextColor(0xFFFFFFFF);
        h.glyph.setGravity(Gravity.CENTER);
        h.glyph.setVisibility(View.GONE);
        iw.addView(h.glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        h.col = new LinearLayout(this);
        h.col.setOrientation(LinearLayout.VERTICAL);
        h.row.addView(h.col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        h.l1 = plainLine();
        h.l2 = plainLine();
        h.l3 = plainLine();
        h.l4 = plainLine();
        h.col.addView(h.l1);
        h.col.addView(h.l2);
        h.col.addView(h.l3);
        h.col.addView(h.l4);

        h.badge = new TextView(this);
        h.badge.setTextSize(11f);
        h.badge.setTextColor(0xFFFFFFFF);
        h.badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        h.row.addView(h.badge);

        h.outer.setTag(h);
        return h;
    }

    private TextView plainLine() {
        TextView t = new TextView(this);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return t;
    }

    private void fillAppRow(RowHolder h, AppEntry e) {
        applyIcon(h, e.icon, firstChar(e.label), 0xFF39404C);
        setLine(h.l1, e.label, 15.5f, C_TX, true);
        setLine(h.l2, e.pkg + (TextUtils.isEmpty(e.verName) ? "" : "  ·  v" + e.verName),
                12f, C_TX2, false);
        setLine(h.l3, "目录  " + e.dirPath, 11f, C_TX3, false);
        setLine(h.l4, "文件  " + e.fileName + (e.size > 0 ? "   (" + size(e.size) + ")" : ""),
                11f, C_TX3, false);
        h.badge.setText(e.system ? (e.priv ? "系统·特权" : "系统应用") : "用户应用");
        h.badge.setBackgroundColor(e.system ? C_SYS : C_USR);
    }

    private void fillApkRow(RowHolder h, ApkEntry e) {
        applyIcon(h, e.icon, "APK", 0xFF2F6FB0);
        setLine(h.l1, e.fileName, 14.5f, C_TX, true);
        setLine(h.l2, e.parseFailed
                ? "（解析失败，可能不是有效 APK 或与 Android 5.1 不兼容）"
                : (TextUtils.isEmpty(e.label) ? e.pkg : e.label + "  ·  " + e.pkg),
                12f, C_TX2, false);
        setLine(h.l3, "目录  " + e.dirPath, 11f, C_TX3, false);

        // 车机上已经装着的话，把装着的版本也写出来，好判断到底是升还是降
        String extra = "";
        if (!e.parseFailed && e.installed) {
            extra = "　·　车机上已装 "
                    + (TextUtils.isEmpty(e.installedVerName)
                        ? "v" + e.installedVerCode : "v" + e.installedVerName);
        }
        setLine(h.l4, "大小  " + size(e.size) + extra, 11f, C_TX3, false);

        // 徽章：装过的绝不再标「待安装」
        if (e.parseFailed) {
            h.badge.setText("解析失败");
            h.badge.setBackgroundColor(0xFF8A4B2A);
        } else if (!e.installed) {
            h.badge.setText("待安装");
            h.badge.setBackgroundColor(C_ACC);
        } else if (e.verCode > e.installedVerCode) {
            h.badge.setText("可更新");
            h.badge.setBackgroundColor(C_USR);
        } else if (e.verCode < e.installedVerCode) {
            h.badge.setText("版本更低");
            h.badge.setBackgroundColor(C_SYS);
        } else {
            h.badge.setText("已安装");
            h.badge.setBackgroundColor(C_TX3);
        }
    }

    /** 有真实图标就画图标，没有就用文字占位 */
    private void applyIcon(RowHolder h, Drawable d, String fallback, int bg) {
        if (d != null) {
            h.glyph.setVisibility(View.GONE);
            h.icon.setImageDrawable(d);
            h.icon.setVisibility(View.VISIBLE);
        } else {
            h.icon.setImageDrawable(null);
            h.icon.setVisibility(View.GONE);
            h.glyph.setText(fallback);
            h.glyph.setBackgroundColor(bg);
            h.glyph.setVisibility(View.VISIBLE);
        }
    }

    private static String firstChar(String s) {
        if (s == null || s.length() == 0) return "?";
        return s.substring(0, 1).toUpperCase(Locale.US);
    }

    private void fillFileRow(RowHolder h, FileEntry e) {
        String ext = extOf(e.name);
        if (e.dir) {
            applyIcon(h, null, "DIR", 0xFF2F6FB0);
        } else {
            applyIcon(h, null, (ext.length() >= 1 && ext.length() <= 4) ? ext : "FILE", 0xFF4A5568);
        }
        setLine(h.l1, e.name, 15f, C_TX, true);
        setLine(h.l2, e.dir ? "文件夹"
                        : ("文件" + (ext.length() > 0 ? "  ·  ." + ext.toLowerCase(Locale.US) : "")),
                12f, C_TX2, false);
        setLine(h.l3, fmtTime(e.mtime), 11f, C_TX3, false);
        setLine(h.l4, e.dir ? "" : size(e.size), 11f, C_TX3, false);
        h.badge.setText(e.dir ? "目录" : "文件");
        h.badge.setBackgroundColor(e.dir ? 0xFF2F6FB0 : 0xFF4A5568);
    }

    private static String extOf(String n) {
        if (n == null) return "";
        int i = n.lastIndexOf('.');
        if (i <= 0 || i == n.length() - 1) return "";
        String e = n.substring(i + 1);
        return e.length() > 5 ? "" : e.toUpperCase(Locale.US);
    }

    private static String fmtTime(long t) {
        if (t <= 0) return "";
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t));
        } catch (Throwable x) {
            return "";
        }
    }

    private static void setLine(TextView t, String text, float sz, int color, boolean bold) {
        t.setText(text);
        t.setTextSize(sz);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private static String size(long n) {
        if (n <= 0) return "—";
        if (n < 1024) return n + " B";
        if (n < 1048576L) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    // ============================================================ 文件管理：浏览
    private File innerHome() {
        try {
            File f = Environment.getExternalStorageDirectory();
            if (f != null && f.isDirectory()) return f;
        } catch (Throwable t) {
            // 忽略，走下面的兜底
        }
        return new File("/sdcard");
    }

    private void goUp() {
        if (curDir == null) { openDir(innerHome()); return; }
        File p = curDir.getParentFile();
        if (p == null || p.equals(curDir)) { toast("已经是根目录了"); return; }
        openDir(p);
    }

    private void openDir(File dir) {
        if (dir == null) dir = innerHome();
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Throwable t) {
            fs = null;
        }
        if (fs == null) {
            toast("读不了这个目录（可能没权限）：" + dir.getAbsolutePath());
            return;
        }
        curDir = dir;
        List<FileEntry> dirs = new ArrayList<FileEntry>();
        List<FileEntry> plain = new ArrayList<FileEntry>();
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f == null) continue;
            FileEntry e = new FileEntry();
            e.file = f;
            e.name = f.getName();
            e.dir = f.isDirectory();
            try { e.size = e.dir ? 0 : f.length(); } catch (Throwable t) { e.size = 0; }
            try { e.mtime = f.lastModified(); } catch (Throwable t) { e.mtime = 0; }
            if (e.dir) dirs.add(e); else plain.add(e);
        }
        Comparator<FileEntry> cmp = new Comparator<FileEntry>() {
            @Override public int compare(FileEntry a, FileEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        };
        Collections.sort(dirs, cmp);
        Collections.sort(plain, cmp);
        files.clear();
        files.addAll(dirs);
        files.addAll(plain);
        adapter.notifyDataSetChanged();
        buildToolbar();          // 「车机 / U盘」的高亮要跟着当前目录走
        updateFooter();
        try { list.setSelection(0); } catch (Throwable t) { }
    }

    /** 跳到 U 盘。插了多个外置存储就让用户选一个。 */
    private void openUsb() {
        usbCache = null;                      // 点这个按钮就是想看现在插没插，强制重扫
        final List<File> usb = usbRootsCached();
        if (usb.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没检测到 U 盘")
                    .setMessage("检查一下：\n\n"
                            + "· U 盘插好了没？插好后点「刷新」再试一次。\n"
                            + "· 车机有的 USB 口只供电不读数据，换另一个口试试。\n"
                            + "· U 盘格式化成 FAT32 / exFAT，别用 NTFS（很多车机不认）。\n"
                            + "· 有的车机要先在原生设置的「存储」里挂载一次。\n\n"
                            + "还是不行：点「车机」进 /sdcard 翻翻，\n"
                            + "有些车机是把 U 盘映射到 /sdcard/usb 这类目录下的。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        // 多个外置存储不再弹「选一个」—— usbRoots() 已经按「最像 U 盘」排过序，
        // 直接取第一个就是用户刚插的那一个
        final File pick = usb.get(0);
        openDir(pick);
        if (usb.size() > 1) {
            toast("已自动选择 " + pick.getAbsolutePath()
                    + "　·　共 " + usb.size() + " 个外置存储");
        }
    }

    /** 目录里有多少个条目。读不到（没权限 / 没挂载）算 0 */
    private static int countEntries(File d) {
        try {
            String[] fs = d.list();
            return fs == null ? 0 : fs.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * dir 是否在 root 里面 —— 用来点亮「车机 / U盘」按钮。
     * 必须按路径段比，不能裸 startsWith：那样 /storage/sdcard2 会被当成在 /storage/sdcard 里。
     */
    private static boolean under(File dir, File root) {
        if (dir == null || root == null) return false;
        try {
            String d = dir.getAbsolutePath();
            String r = root.getAbsolutePath();
            while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
            return d.equals(r) || d.startsWith(r + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean underAny(File dir, List<File> roots) {
        for (int i = 0; i < roots.size(); i++) {
            if (under(dir, roots.get(i))) return true;
        }
        return false;
    }

    private void openEntry(FileEntry e) {
        if (e.dir) { openDir(e.file); return; }
        openFile(e.file);
    }

    private void openFile(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), mimeOf(f.getName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("没有能打开它的应用，或者系统拦了");
        }
    }

    private static String mimeOf(String name) {
        String e = extOf(name).toLowerCase(Locale.US);
        if (e.equals("apk")) return "application/vnd.android.package-archive";
        if (e.equals("png") || e.equals("jpg") || e.equals("jpeg") || e.equals("gif")
                || e.equals("bmp") || e.equals("webp")) return "image/*";
        if (e.equals("mp3") || e.equals("wav") || e.equals("flac") || e.equals("aac")
                || e.equals("ogg") || e.equals("m4a")) return "audio/*";
        if (e.equals("mp4") || e.equals("mkv") || e.equals("avi") || e.equals("3gp")
                || e.equals("mov") || e.equals("flv")) return "video/*";
        if (e.equals("txt") || e.equals("log") || e.equals("ini")
                || e.equals("conf") || e.equals("md")) return "text/plain";
        if (e.equals("zip")) return "application/zip";
        if (e.equals("pdf")) return "application/pdf";
        if (e.equals("xml")) return "text/xml";
        if (e.equals("json")) return "application/json";
        return "*/*";
    }

    // ============================================================ 文件管理：操作
    private void showFileMenu(final FileEntry e) {
        String[] items = {"打开", "复制", "剪切", "重命名", "删除", "属性"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0:
                                openEntry(e);
                                break;
                            case 1:
                                clip.clear(); clip.add(e.file); clipCut = false;
                                toast("已复制：" + e.name);
                                buildToolbar(); updateFooter();
                                break;
                            case 2:
                                clip.clear(); clip.add(e.file); clipCut = true;
                                toast("已剪切：" + e.name);
                                buildToolbar(); updateFooter();
                                break;
                            case 3:
                                renameEntry(e);
                                break;
                            case 4:
                                confirmDelete(e);
                                break;
                            default:
                                showProps(e.file);
                                break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameEntry(final FileEntry e) {
        final EditText et = new EditText(this);
        et.setText(e.name);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = et.getText() == null ? "" : et.getText().toString().trim();
                        if (n.length() == 0) { toast("名字不能为空"); return; }
                        if (n.indexOf('/') >= 0) { toast("名字里不能有 /"); return; }
                        File dst = new File(e.file.getParentFile(), n);
                        if (dst.exists()) { toast("已经有同名的了"); return; }
                        toast(e.file.renameTo(dst) ? "已改名为 " + n : "改名失败（可能没写权限）");
                        openDir(curDir);
                    }
                })
                .show();
    }

    private void confirmDelete(final FileEntry e) {
        String warn = e.dir ? "\n\n⚠ 这是个文件夹，里面的东西会一起删掉。" : "";
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("确定删除 " + e.name + " 吗？删了就找不回来了。" + warn)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final File victim = e.file;
                        final File here = curDir;
                        footerView.setText("正在删除 " + e.name + "…");
                        new Thread(new Runnable() {          // 删大文件夹也在后台跑
                            @Override public void run() {
                                final boolean okk = deleteRec(victim);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        toast(okk ? "已删除 " + e.name
                                                : "删除失败（可能没写权限）");
                                        openDir(here == null ? innerHome() : here);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .show();
    }

    private static boolean deleteRec(File f) {
        try {
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) {
                    for (int i = 0; i < cs.length; i++) deleteRec(cs[i]);
                }
            }
            return f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    private void newFolder() {
        if (curDir == null) { toast("先进入一个目录"); return; }
        final EditText et = new EditText(this);
        et.setHint("文件夹名字");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = et.getText() == null ? "" : et.getText().toString().trim();
                        if (n.length() == 0) { toast("名字不能为空"); return; }
                        if (n.indexOf('/') >= 0) { toast("名字里不能有 /"); return; }
                        File f = new File(curDir, n);
                        if (f.exists()) { toast("已经存在了"); return; }
                        toast(f.mkdirs() ? "已创建 " + n : "创建失败（可能没写权限）");
                        openDir(curDir);
                    }
                })
                .show();
    }

    /**
     * 粘贴。文件 IO 必须放后台 —— 复制一个几百 MB 的视频在 UI 线程跑会直接 ANR。
     */
    private void paste() {
        if (clip.size() == 0) { toast("剪贴板是空的"); return; }
        if (curDir == null) { toast("先进入一个目录"); return; }
        final File target = curDir;
        final List<File> srcs = new ArrayList<File>(clip);
        final boolean cut = clipCut;
        footerView.setText("正在" + (cut ? "移动" : "复制") + " " + srcs.size() + " 项，别退出…");
        new Thread(new Runnable() {
            @Override public void run() {
                int done = 0, fail = 0;
                for (int i = 0; i < srcs.size(); i++) {
                    File src = srcs.get(i);
                    try {
                        if (!src.exists()) { fail++; continue; }
                        File dst = new File(target, src.getName());
                        if (dst.exists()) { fail++; continue; }
                        boolean okk;
                        if (cut) {
                            okk = src.renameTo(dst);
                            if (!okk && copyRec(src, dst)) {  // 跨分区 rename 会失败，退回「复制 + 删源」
                                deleteRec(src);
                                okk = true;
                            }
                        } else {
                            okk = copyRec(src, dst);
                        }
                        if (okk) done++; else fail++;
                    } catch (Throwable t) {
                        fail++;
                    }
                }
                final int d = done, f = fail;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (cut) { clip.clear(); clipCut = false; }
                        toast("完成 " + d + " 项" + (f > 0 ? "，失败 " + f + " 项" : ""));
                        openDir(target);
                    }
                });
            }
        }).start();
    }

    private static boolean copyRec(File src, File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) return false;
                File[] cs = src.listFiles();
                if (cs != null) {
                    for (int i = 0; i < cs.length; i++) {
                        if (!copyRec(cs[i], new File(dst, cs[i].getName()))) return false;
                    }
                }
                return true;
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                closeQ(in);
                closeQ(out);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showProps(File f) {
        StringBuilder b = new StringBuilder();
        b.append("路径   ").append(f.getAbsolutePath()).append("\n");
        b.append("类型   ").append(f.isDirectory() ? "文件夹" : "文件").append("\n");
        if (!f.isDirectory()) {
            b.append("大小   ").append(size(f.length()))
                    .append("（").append(f.length()).append(" 字节）\n");
        }
        b.append("修改   ").append(fmtTime(f.lastModified())).append("\n");
        b.append("可读   ").append(f.canRead() ? "是" : "否").append("\n");
        b.append("可写   ").append(f.canWrite() ? "是" : "否").append("\n");
        b.append("可执行 ").append(f.canExecute() ? "是" : "否").append("\n");
        showText("属性 · " + f.getName(), b.toString());
    }

    private static void closeQ(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable t) { }
        }
    }

    // ============================================================ 设置面板
    private void showSettings() {
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setBackgroundColor(0xFF1B1F26);
        int pad = dp(14);
        settingsBox.setPadding(pad, pad, pad, pad);

        final float limit = getResources().getDisplayMetrics().heightPixels * 0.66f;
        settingsScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(
                        (int) limit, MeasureSpec.AT_MOST));
            }
        };
        settingsScroll.addView(settingsBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildSettingsRows();

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(settingsScroll)
                .setPositiveButton("关闭", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        // 面板关了就别再定时刷了，也把视图引用放掉，免得刷到已经销毁的控件
                        ui.removeCallbacks(wifiTick);
                        ipValueText = null;
                        adbStateText = null;
                        wifiStateText = null;
                        wlanStateText = null;
                        adbToggle = null;
                        wifiToggle = null;
                        wlanToggle = null;
                        wifiExtraText = null;
                        wifiWarnText = null;
                    }
                })
                .show();

        fillSettings();
    }

    /**
     * 存储信息的兜底读数 —— 读数炸了只让这一行显示「读取失败」，
     * 不能把整块面板拖白（面板上只要有一行addView之前抛异常，
     * 后面的行就全都没了，用户看到的就是「只有一行」）。
     */
    private String safeStorageInfo() {
        try {
            return storageInfo();
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    /**
     * 面板的行一次性建好（照原版的结构），之后只刷状态、不重建。
     *
     * 之前是每次点开关就把 settingsBox.removeAllViews() 重建一遍 ——
     * 结果就是滚动位置乱跳、点一下整屏闪，而且开关状态和 toast 经常对不上。
     * 原版是「控件引用存字段 + 集中刷新」，这里改成一样的做法。
     */
    private void buildSettingsRows() {
        // ---------- 打开原生系统设置
        settingsBox.addView(dlgRow("打开原生系统设置", null, new View.OnClickListener() {
            @Override public void onClick(View v) { openNativeSettings(); }
        }));
        settingsBox.addView(dlgSep());

        // ---------- 本机 IP（点一下看全部网卡地址）
        ipValueText = dlgValue("读取中…", P_OFF);
        settingsBox.addView(dlgRow("本机 IP", ipValueText, new View.OnClickListener() {
            @Override public void onClick(View v) { showAddressList(); }
        }));
        settingsBox.addView(dlgSep());

        // ---------- ADB 调试（USB）
        adbStateText = dlgValue("读取中…", P_OFF);
        adbToggle = new ToggleSwitch(this);
        settingsBox.addView(dlgSwitchRow("ADB 调试（USB）", adbToggle, adbStateText,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onAdbRowClicked(); }
                }));
        settingsBox.addView(dlgSep());

        // ---------- 无线 ADB（WiFi）
        wifiStateText = dlgValue("读取中…", P_OFF);
        wifiToggle = new ToggleSwitch(this);
        settingsBox.addView(dlgSwitchRow("无线 ADB（WiFi）", wifiToggle, wifiStateText,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { onWifiRowClicked(); }
                }));

        // 开着的时候显示「adb connect x.x.x.x:5555　·　N 分钟后自动关闭」，点一下复制
        wifiExtraText = new TextView(this);
        wifiExtraText.setTextColor(P_OK);
        wifiExtraText.setTextSize(12f);
        wifiExtraText.setPadding(dp(18), 0, dp(18), dp(4));
        wifiExtraText.setVisibility(View.GONE);
        wifiExtraText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyWifiConnect(); }
        });
        settingsBox.addView(wifiExtraText);

        wifiWarnText = new TextView(this);
        wifiWarnText.setText("⚠ 同一 WiFi 下任何人都能连上这台车机，用完记得关");
        wifiWarnText.setTextColor(P_WARN);
        wifiWarnText.setTextSize(12f);
        wifiWarnText.setPadding(dp(18), 0, dp(18), dp(10));
        wifiWarnText.setVisibility(View.GONE);
        settingsBox.addView(wifiWarnText);

        settingsBox.addView(dlgSep());

        // ---------- WiFi 联网（车机自己上网用的，和上面的无线 ADB 不是一回事）
        wlanStateText = dlgValue("读取中…", P_OFF);
        wlanToggle = new ToggleSwitch(this);
        settingsBox.addView(dlgSwitchRow("WiFi 联网", wlanToggle, wlanStateText,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleWifi(); }
                }));
        settingsBox.addView(dlgSep());

        // ---------- 车机信息
        settingsBox.addView(dlgRow("Android 版本",
                dlgValue(Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）", P_OFF),
                null));
        settingsBox.addView(dlgRow("机型",
                dlgValue((Build.MANUFACTURER + " " + Build.MODEL).trim(), P_OFF), null));
        settingsBox.addView(dlgRow("存储", dlgValue(safeStorageInfo(), P_OFF), null));
        settingsBox.addView(dlgRow("当前身份", dlgValue(
                hasPerm("android.permission.REBOOT")
                        ? "系统应用（重启 / ADB 开关免 root）" : "普通应用", P_OFF), null));
        settingsBox.addView(dlgSep());

        // ---------- 其它
        settingsBox.addView(dlgRow("无线 ADB · 提权自检", null, new View.OnClickListener() {
            @Override public void onClick(View v) { selfCheckRoot(); }
        }));
        settingsBox.addView(dlgRow("无线 ADB · 手敲命令 / 说明", null, new View.OnClickListener() {
            @Override public void onClick(View v) { showText("无线 ADB 说明", wifiAdbHelp()); }
        }));
        settingsBox.addView(dlgRow("关于 " + APP_TITLE, null, new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        }));
    }

    /**
     * 只刷状态，不重建视图。
     * 每一项都「写完回读」，不靠假设 —— ADB / WiFi 的状态有些 ROM 上
     * 写进去既不报错也不生效，只能读回来才算数。
     */
    private void fillSettings() {
        if (settingsBox == null) return;
        try {
            String ip = localIpRaw();
        lastIp = ip;
        if (ipValueText != null) {
            ipValueText.setText(ip == null ? "未连接" : ip);
            ipValueText.setTextColor(ip == null ? P_OFF : P_OK);
        }

        int adb = readAdbEnabled();              // 1 开 / 0 关 / -1 读不到
        if (adbToggle != null) {
            adbToggle.setOn(adb == 1);
            adbToggle.setUsable(adb >= 0 && !adbBusy);
        }
        if (adbStateText != null) {
            adbStateText.setText(adbBusy ? "处理中…"
                    : adb == 1 ? "已开启" : adb == 0 ? "已关闭" : "未知");
            adbStateText.setTextColor(adb == 1 ? P_ON : P_OFF);
        }

        boolean wifiAdb = isWifiAdbOn();
        if (wifiToggle != null) {
            wifiToggle.setOn(wifiAdb);
            wifiToggle.setUsable(ip != null && !wifiBusy);
        }
        if (wifiStateText != null) {
            wifiStateText.setText(wifiBusy ? "处理中…"
                    : wifiAdb ? "已开启" : ip == null ? "未连网络" : "已关闭");
            wifiStateText.setTextColor(wifiAdb ? P_OK : P_OFF);
        }

        boolean wlan = isWifiOn();
        if (wlanToggle != null) {
            wlanToggle.setOn(wlan);
            wlanToggle.setUsable(!wifiBusy);
        }
        if (wlanStateText != null) {
            wlanStateText.setText(wlan ? "已开启" : "已关闭");
            wlanStateText.setTextColor(wlan ? P_ON : P_OFF);
        }

        updateWifiExtra();
        } catch (Throwable t) {
            // 状态刷新出错不能让面板崩 —— 行都建好了，状态下轮再刷
        }
    }

    /**
     * 提权自检。无线 ADB 开关不灵的时候点这个 ——
     * 把候选程序、每一步的输出、提权后的 uid 全摊出来，省得靠猜。
     */
    private void selfCheckRoot() {
        toast("正在自检…");
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder b = new StringBuilder();

                // 一、候选
                b.append("一、提权程序候选（按优先级）\n");
                List<String> cands = suCandidates();
                if (cands.isEmpty()) {
                    b.append("  （一个都没有）\n");
                } else {
                    for (int i = 0; i < cands.size(); i++) {
                        String p = cands.get(i);
                        b.append("  ").append(i + 1).append(". ").append(p).append("\n");
                        try {
                            File f = new File(p);
                            b.append("     可读 ").append(f.canRead() ? "是" : "否")
                                    .append("　可执行 ").append(f.canExecute() ? "是" : "否")
                                    .append("　").append(f.length()).append(" B\n");
                        } catch (Throwable t) { }
                    }
                }
                b.append("\n  内置 mtk-su 释放位置\n    ")
                        .append(getFilesDir()).append("/mtk-su\n");
                b.append("  已经试过没通的\n");
                if (failedSu.isEmpty()) {
                    b.append("    （无）\n");
                } else {
                    for (String s : failedSu) b.append("    ").append(s).append("\n");
                }

                // 二、当前状态
                b.append("\n二、当前状态\n");
                int port = readWifiAdbPort();
                b.append("  service.adb.tcp.port　")
                        .append(port > 0 ? String.valueOf(port) : "（空 / 未设）")
                        .append(port > 0 ? "　→ 无线 ADB 开着\n" : "　→ 无线 ADB 关着\n");
                int adb = readAdbEnabled();
                b.append("  USB ADB（adb_enabled）　")
                        .append(adb == 1 ? "开" : adb == 0 ? "关" : "读不到").append("\n");
                b.append("  本机 IP　").append(localIpRaw() == null ? "（没读到）" : localIpRaw()).append("\n");
                b.append("  倒计时　").append(wifiOffAt <= 0 ? "没在计时"
                        : Math.max(0, (wifiOffAt - System.currentTimeMillis()) / 60000 + 1) + " 分钟后自动关").append("\n");
                b.append("  当前身份　").append(hasPerm("android.permission.REBOOT")
                        ? "系统应用（REBOOT 已生效）" : "普通应用").append("\n");

                // 三、真跑一遍
                b.append("\n三、实际执行（提权 + id）\n\n");
                StringBuilder log = new StringBuilder();
                boolean okk = execScript("id", log);
                b.append(log).append("\n");
                b.append(okk ? "→ 提权跑通了\n" : "→ 提权没跑通\n");

                // 四、怎么读
                b.append("\n四、这份结果怎么读\n");
                b.append("  · 提权成功 → 输出里有 uid=0(root)\n");
                b.append("  · 出现 avc: denied → 被 SELinux 拦了。\n");
                b.append("    这种情况把 mtk-su 投放到 /data/local/tmp 再试\n");
                b.append("    （那个位置 SELinux 明确允许应用执行）：\n");
                b.append("      adb push mtk-su /sdcard/\n");
                b.append("      adb shell \"cp /sdcard/mtk-su /data/local/tmp/ && chmod 755 /data/local/tmp/mtk-su\"\n");
                b.append("  · No such file or directory → 路径不对\n");
                b.append("  · exit 127 → 找不到可执行文件；exit 126 → 没有执行权限\n");
                b.append("  · 提权成功但无线 ADB 还是开不了 →\n");
                b.append("    看执行输出里 getprop 那行有没有回显端口号\n");
                b.append("\n").append(wifiAdbHelp());

                final String txt = b.toString();
                runOnUiThread(new Runnable() {
                    @Override public void run() { showText("提权自检结果", txt); }
                });
            }
        }).start();
    }

    // ------------------------------------------------ 设置面板：行构造（照原版）
    /**
     * 一行「标题 + 右边的值」。value 为 null 就不加右边那块。
     * 尺寸照原版 dlgRow：内边距 18/14，标题 16sp 纯白，右边值 14sp 右对齐。
     */
    private View dlgRow(String label, TextView value, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        makeRowClickable(r, l);

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(Color.WHITE);
        k.setTextSize(16f);
        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) r.addView(value);
        return wrap(r);
    }

    /**
     * 一行「标题 + 状态字 + 开关」。开关本身不接点击，整行接 ——
     * 和原版一样，点哪都能切，免得小开关在车机屏幕上不好点。
     */
    private View dlgSwitchRow(String label, ToggleSwitch sw, TextView state,
                              View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(12), dp(18), dp(12));
        makeRowClickable(r, l);

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(Color.WHITE);
        k.setTextSize(16f);
        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(12);
        r.addView(state, slp);
        r.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap(r);
    }

    /** 右边的值。照原版 dlgValue：14sp、居中偏右。 */
    private TextView dlgValue(String text, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(14f);
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        return v;
    }

    /** 分隔线。照原版 dlgSep：0.5dp 高、颜色 #4A4A4A。 */
    private View dlgSep() {
        View v = new View(this);
        v.setBackgroundColor(P_SEP);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    /** 让整行可点：有点击回调才设，没回调就不设（也就不会有点击反馈）。 */
    private void makeRowClickable(View v, View.OnClickListener l) {
        if (l == null) return;
        v.setOnClickListener(l);
        v.setClickable(true);
    }

    private View wrap(View row) {
        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setPadding(0, dp(1), 0, dp(1));
        o.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return o;
    }

    // ------------------------------------------------------------ 设置：数据
    private boolean hasPerm(String p) {
        try {
            return checkCallingOrSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 本机 IP，纯地址（不带网卡名）。给 adb connect 用。
     * 读不到返回 null。
     *
     * 注意：这里不碰 Socket —— 本应用没有 android.permission.INTERNET，
     * 连 127.0.0.1 都会被内核按 GID 拦掉（EACCES）。所以判断状态一律走
     * 系统属性 / 设置项，不走网络探测。
     */
    private String localIpRaw() {
        // 先问 WiFi 管理器（拿到的肯定是 wlan0 的地址，最适合 adb connect）
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    int ip = wi.getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                    }
                }
            }
        } catch (Throwable t) {
            // 继续试网络接口
        }
        // 再遍历网卡，按「哪个更像能连的」挑一个
        String best = null;
        int bestRank = 99;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en != null && en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (ni == null || ni.isLoopback()) continue;
                int r = ifaceRank(ni.getName());
                if (r >= bestRank) continue;
                for (Enumeration<InetAddress> ea = ni.getInetAddresses();
                     ea != null && ea.hasMoreElements(); ) {
                    InetAddress ia = ea.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                        best = ia.getHostAddress();
                        bestRank = r;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // 读不到就算了
        }
        return best;
    }

    /** 网卡优先级：越小越优先。wlan > eth > usb 共享 > 其它 > 移动网络 */
    private static int ifaceRank(String name) {
        if (name == null) return 90;
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("wlan")) return 0;
        if (n.startsWith("eth")) return 1;
        if (n.contains("usb") || n.contains("rndis")) return 2;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")) return 8;
        if (n.startsWith("tun")) return 9;
        return 5;
    }

    private static String ifaceLabel(String name) {
        if (name == null) return "网络";
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("wlan")) return "WiFi";
        if (n.startsWith("eth")) return "有线";
        if (n.contains("usb") || n.contains("rndis")) return "USB 共享";
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")) return "移动网络";
        if (n.startsWith("tun")) return "VPN";
        return n;
    }

    /** 面板上「本机 IP」显示用的完整串（带网卡名），读不到给提示语 */
    private String localIp() {
        String ip = localIpRaw();
        if (ip == null) return "没读到（车机可能没联网）";
        return ip;
    }

    /** 点「本机 IP」弹全部网卡地址 —— 分不清该连哪个的时候用 */
    private void showAddressList() {
        StringBuilder b = new StringBuilder();
        b.append("这台车机的所有网卡地址\n\n");
        boolean any = false;
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en != null && en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (ni == null || ni.isLoopback()) continue;
                String ip4 = null;
                for (Enumeration<InetAddress> ea = ni.getInetAddresses();
                     ea != null && ea.hasMoreElements(); ) {
                    InetAddress ia = ea.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                        ip4 = ia.getHostAddress();
                        break;
                    }
                }
                if (ip4 == null) continue;
                any = true;
                b.append("  ").append(ni.getName())
                        .append("（").append(ifaceLabel(ni.getName())).append("）\n");
                b.append("      ").append(ip4).append("\n");
                b.append("      adb connect ").append(ip4).append(":").append(ADB_TCP_PORT).append("\n\n");
            }
        } catch (Throwable t) {
            b.append("  读网卡出错：").append(t).append("\n\n");
        }
        if (!any) b.append("  一个 IPv4 地址都没读到。\n\n");
        b.append("无线 ADB 要连的是「WiFi」那张网卡的地址 ——\n")
                .append("电脑和车机在同一个 WiFi 下才能连上。\n")
                .append("USB 共享 / 移动网络的地址一般连不上。\n\n")
                .append("── 前提 ──\n")
                .append("车机这边「无线 ADB（WiFi）」得是开着的，\n")
                .append("电脑那边装好 adb，然后：\n")
                .append("  adb connect <上面的地址>:").append(ADB_TCP_PORT).append("\n")
                .append("  adb devices          # 看到 device 就成了\n\n")
                .append("连不上先看这几条：\n")
                .append("  · 电脑和车机在不在同一个 WiFi\n")
                .append("  · 车机这个开关是不是真开着（设置里那一行）\n")
                .append("  · 车机 WiFi 是不是被路由器隔离了（访客网络常见）\n");
        showText("本机 IP · 全部网卡", b.toString());
    }

    private String storageInfo() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = sf.getBlockCountLong() * sf.getBlockSizeLong();
            long avail = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
            return String.format(Locale.US, "%s 可用 / %s", gb(avail), gb(total));
        } catch (Throwable t) {
            return "读不到";
        }
    }

    private static String gb(long n) {
        if (n <= 0) return "0 GB";
        double g = n / 1073741824.0;
        if (g < 1) return String.format(Locale.US, "%.0f MB", n / 1048576.0);
        return String.format(Locale.US, "%.1f GB", g);
    }

    // ------------------------------------------------------------ 设置：ADB
    /**
     * 读 ADB 调试状态。1 = 开，0 = 关，-1 = 读不到。
     *
     * 照原版 readAdbEnabled 的三级兜底：
     *   ① Settings.Global.getInt("adb_enabled")  —— 最准，但读也要权限，读不到会抛
     *   ② 退回执行 `settings get global adb_enabled`
     *   ③ 再退回看 `getprop init.svc.adbd` 是不是 running / stopped
     * 之所以这么绕：有的车机把 adb_enabled 这个设置项藏了或者改了，
     * 单看一个来源会得出错结论。
     */
    private int readAdbEnabled() {
        try {
            int v = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, -1);
            if (v == 0 || v == 1) return v;
        } catch (Throwable t) {
            // 没读权限，往下走
        }
        try {
            String out = runCmd(new String[]{"settings", "get", "global", "adb_enabled"}, null, 10);
            if (out != null) {
                String[] lines = out.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i].trim();
                    if ("1".equals(s)) return 1;
                    if ("0".equals(s)) return 0;
                }
            }
        } catch (Throwable t) { }
        try {
            String out = runCmd(new String[]{"getprop", "init.svc.adbd"}, null, 10);
            if (out != null) {
                if (out.contains("running")) return 1;
                if (out.contains("stopped")) return 0;
            }
        } catch (Throwable t) { }
        return -1;
    }

    private boolean isAdbEnabled() {
        return readAdbEnabled() == 1;
    }

    /** 点「ADB 调试（USB）」那一行：开着 → 先确认再关；关着 → 直接开 */
    private void onAdbRowClicked() {
        if (adbBusy) return;
        if (adbToggle != null && adbToggle.isOn()) {
            confirmAdbOff();
        } else {
            doAdbToggle(true);
        }
    }

    private void confirmAdbOff() {
        new AlertDialog.Builder(this)
                .setTitle("关闭 ADB 调试")
                .setMessage("关掉之后电脑就连不上了，\n"
                        + "「存储里的 APK」「已安装的软件」里的那些 ADB 命令\n"
                        + "也就没法执行了。\n\n确定关掉？")
                .setNegativeButton("不关", null)
                .setPositiveButton("关掉", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doAdbToggle(false); }
                })
                .show();
    }

    /**
     * 切 ADB 调试。先试直接写 Settings.Global（装进 /system/priv-app 就能成），
     * 写不了才提示用户。写完一定回读 —— 有些 ROM putInt 既不抛异常也不生效。
     */
    private void doAdbToggle(final boolean on) {
        adbBusy = true;
        if (adbStateText != null) adbStateText.setText("处理中…");
        if (adbToggle != null) adbToggle.setUsable(false);

        new Thread(new Runnable() {
            @Override public void run() {
                boolean wrote = false;
                try {
                    Settings.Global.putInt(getContentResolver(),
                            Settings.Global.ADB_ENABLED, on ? 1 : 0);
                    wrote = true;
                } catch (Throwable t) { }
                int now = readAdbEnabled();
                final boolean okk = wrote && now == (on ? 1 : 0);
                final int got = now;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        adbBusy = false;
                        fillSettings();
                        if (okk) {
                            toast(on ? "ADB 调试已开启" : "ADB 调试已关闭");
                        } else if (got < 0) {
                            toast("读不到 ADB 状态");
                            showText("ADB 状态读不到", adbHelp(on));
                        } else {
                            toast("改不了：本应用没有 WRITE_SECURE_SETTINGS 权限");
                            showText("ADB 开关改不了", adbHelp(on));
                        }
                    }
                });
            }
        }).start();
    }

    private String adbHelp(boolean on) {
        return "为什么改不了\n"
                + "  Settings.Global.ADB_ENABLED 需要 android.permission.WRITE_SECURE_SETTINGS，\n"
                + "  这是 signature|privileged 级权限，普通应用拿不到。\n"
                + "\n"
                + "两条解决办法\n"
                + "  ① 把这个应用装进 /system/priv-app（本工具「存储里的 APK」里有完整教程）。\n"
                + "     Android 5.1 没有 privapp 权限白名单，装进去就自动拿到，不用 root。\n"
                + "\n"
                + "  ② 或者你自己在电脑上用 ADB 敲一条：\n"
                + "     adb shell settings put global adb_enabled " + (on ? "1" : "0") + "\n"
                + "\n"
                + "查看当前状态：\n"
                + "  adb shell settings get global adb_enabled\n"
                + "  adb shell getprop init.svc.adbd\n";
    }

    /**
     * 无线 ADB 是不是开着 —— 读系统属性 service.adb.tcp.port，>0 就是开着。
     *
     * ⚠ 这里原来是用 Socket 连 127.0.0.1:5555 探的，那是错的：
     *   本应用没有 android.permission.INTERNET，Android 是按 GID(AID_INET) 在
     *   内核层拦的，连本机回环也拦（socket() 直接 EACCES），
     *   所以那个探针永远返回 false —— 开关状态永远显示「已关闭」，
     *   点开启还会报「没成功」。原版就是读属性，这里改成一样的。
     */
    private int readWifiAdbPort() {
        try {
            String out = runCmd(new String[]{"getprop", "service.adb.tcp.port"}, null, 10);
            if (out == null) return 0;
            String[] lines = out.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.length() == 0) continue;
                try {
                    int p = Integer.parseInt(s);
                    if (p > 0) return p;
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
        return 0;
    }

    private boolean isWifiAdbOn() {
        return readWifiAdbPort() > 0;
    }

    /**
     * 把内置的 assets/mtk-su 释放到应用私有目录并 chmod。
     * 照原版 AwaAPK 的做法 —— 这样用户不用自己 adb push，点一下就能用。
     * 返回 null 表示释放失败。
     */
    private File privateSu() {
        try {
            File f = new File(getFilesDir(), "mtk-su");
            if (!f.exists() || f.length() == 0) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = getAssets().open("mtk-su");
                    out = new FileOutputStream(f);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    closeQ(in);
                    closeQ(out);
                }
            }
            if (f.length() == 0) return null;
            chmod(f.getAbsolutePath());
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 先 chmod 755，还不行就 777（有的车机 /data 挂载策略比较怪） */
    private void chmod(String path) {
        try {
            File f = new File(path);
            runCmd(new String[]{"chmod", "755", path}, null, 15);
            if (!f.canExecute()) runCmd(new String[]{"chmod", "777", path}, null, 15);
        } catch (Throwable t) { }
    }

    /**
     * 候选提权程序，按优先级排（照原版顺序）：
     *   ① 上次跑通的
     *   ② /data/local/tmp/mtk-su —— SELinux 明确允许应用执行这里的东西，最稳
     *   ③ 内置的 mtk-su（释放到应用私有目录）
     *   ④ 系统本来就带的 su
     *   ⑤ /sdcard 下手动放的 —— 放最后！/sdcard 是 FUSE + noexec，
     *      在这台车机上执行必然报 IOException，排前面只会刷一堆没用的报错
     */
    private List<String> suCandidates() {
        List<String> out = new ArrayList<String>();
        addCand(out, workingSu);
        addCand(out, exists("/data/local/tmp/mtk-su") ? "/data/local/tmp/mtk-su" : null);
        if (privateSu == null || !privateSu.exists()) privateSu = privateSu();
        if (privateSu != null && privateSu.exists()) addCand(out, privateSu.getAbsolutePath());
        for (int i = 0; i < SYSTEM_SU.length; i++) {
            if (exists(SYSTEM_SU[i])) addCand(out, SYSTEM_SU[i]);
        }
        addCand(out, exists("/sdcard/mtk-su") ? "/sdcard/mtk-su" : null);
        addCand(out, exists("/sdcard/Download/mtk-su") ? "/sdcard/Download/mtk-su" : null);
        return out;
    }

    private static boolean exists(String p) {
        try {
            return new File(p).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    private void addCand(List<String> out, String p) {
        if (p == null || p.length() == 0) return;
        if (failedSu.contains(p)) return;
        if (!out.contains(p)) out.add(p);
    }

    /**
     * 跑一段提权脚本。跑通返回 true，过程写进 log。
     *
     * 每个候选先试 `su -c "脚本"`，不行再把脚本喂给它的 stdin；
     * 脚本末尾自动追加 echo __AWA_END__，输出里出现它才算跑通 ——
     * 不能只看 exit code，有的 su 失败了也返回 0。
     */
    private boolean execScript(String script, StringBuilder log) {
        List<String> cands = suCandidates();
        if (cands.isEmpty()) {
            log.append("没有可用的提权程序。\n");
            return false;
        }
        if (!script.contains(END_MARK)) script = script + "\necho " + END_MARK;
        for (int i = 0; i < cands.size(); i++) {
            String su = cands.get(i);
            log.append("· 尝试 ").append(su).append("\n");

            String o1 = runCmd(new String[]{su, "-c", script}, null, 30);
            log.append("  [-c]    ").append(oneLine(o1)).append("\n");
            if (hasEnd(o1)) {
                workingSu = su;
                log.append("\n执行输出\n").append(o1).append("\n");
                return true;
            }

            String o2 = runCmd(new String[]{su}, script + "\nexit\n", 30);
            log.append("  [stdin] ").append(oneLine(o2)).append("\n");
            if (hasEnd(o2)) {
                workingSu = su;
                log.append("\n执行输出\n").append(o2).append("\n");
                return true;
            }
            failedSu.add(su);
        }
        log.append("\n所有提权程序都没跑通。\n");
        return false;
    }

    /**
     * 输出里出现结束标记才算真跑通。
     *
     * ⚠ 这里有个坑，原版 AwaAPK 也踩了：
     *   ProcessBuilder.start() 失败时抛的 IOException，它的 getMessage() 里
     *   会带上完整命令行（"Error running exec(). Command: [mtk-su, -c, setprop ...
     *   echo __AWA_END__] Working Directory: ..."）。而命令行本身就含有
     *   echo __AWA_END__ —— 于是裸用 contains() 判断会把「启动失败」
     *   误判成「跑通了」。
     *
     *   结果就是日志里写着「执行输出：EXCEPTION ...」，却返回成功，
     *   真实失败原因被彻底掩盖，状态也一直不对。
     *
     * 所以这里叠三道保险：
     *   ① 开头是 EXCEPTION: 的直接判失败
     *   ② runCmd() 那边返回异常时已经把命令行抹掉了
     *   ③ 结束标记必须单独占一行（shell 回显和异常信息都是
     *      「… echo __AWA_END__」这种带上下文的，真正的输出是单独一行）
     */
    private static boolean hasEnd(String s) {
        if (s == null || s.length() == 0) return false;
        if (s.startsWith("EXCEPTION:")) return false;
        if (s.contains(END_MARK)) {
            // 真正的成功输出里 __AWA_END__ 是单独一行；
            // 异常消息里它是嵌在 "…[mtk-su, -c, … echo __AWA_END__]…" 里的
            String[] lines = s.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals(END_MARK)) return true;
            }
        }
        return false;
    }

    /**
     * 把异常信息收拾干净。ProcessBuilder 的 IOException 消息形如：
     *
     *   Error running exec(). Command: [mtk-su, -c, setprop service.adb.tcp.port -1
     *   stop adbd
     *   start adbd
     *   echo AWA_ADB_OK
     *   echo __AWA_END__] Working Directory: null Environment: null
     *
     * 从 "Command: [" 到 "Environment:" 整段都是命令行回显，里面带着
     * echo __AWA_END__，必须整段抹掉，否则 hasEnd() 会误判成「跑通了」。
     * 抹完之后只剩 "Error running exec()." 这种真正有用的信息。
     */
    private static String safeErr(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        int i = m.indexOf("Command: [");
        if (i >= 0) {
            int j = m.indexOf("]", i);
            m = (j >= 0 ? m.substring(0, i) + m.substring(j + 1) : m.substring(0, i));
            int k = m.indexOf("Working Directory:");
            if (k >= 0) m = m.substring(0, k);
            k = m.indexOf("Environment:");
            if (k >= 0) m = m.substring(0, k);
        }
        m = m.replace(END_MARK, "").replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() == 0 ? t.getClass().getSimpleName() : m;
    }

    private static String oneLine(String s) {
        if (s == null || s.length() == 0) return "(空)";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() == 0) return "(空)";
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
    }

    /**
     * 跑一条命令，读全部输出。超时就把进程 destroy 掉。
     * 看门狗线程负责超时，主线程阻塞在 readAll 上 —— 和原版一个路子。
     */
    private static String runCmd(final String[] cmd, String stdin, final int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process fp = p;
            OutputStream os = p.getOutputStream();
            if (stdin != null) {
                os.write(stdin.getBytes("UTF-8"));
                os.flush();
            }
            closeQ(os);

            Thread dog = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(timeoutSec * 1000L); } catch (Throwable t) { }
                    try { fp.destroy(); } catch (Throwable t) { }
                }
            });
            dog.setDaemon(true);
            dog.start();

            String out = readAll(p.getInputStream());
            p.waitFor();
            return out == null ? "" : out.trim();
        } catch (Throwable t) {
            // 不能把 t.getMessage() 原样返回 —— IOException 的消息里带着完整
            // 命令行，而命令行里有 echo __AWA_END__，hasEnd() 会误判成成功。
            return "EXCEPTION: " + safeErr(t);
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable t) { }
            }
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        } catch (Throwable t) {
            // 超时被 destroy 会走到这里，正常
        } finally {
            closeQ(in);
        }
        return sb.toString();
    }

    // ------------------------------------------------ 无线 ADB：开关 + 倒计时
    /** 点「无线 ADB（WiFi）」那一行：开着就关，关着就开（开了之后弹确认） */
    private void onWifiRowClicked() {
        if (wifiBusy) return;
        boolean on = wifiToggle != null ? !wifiToggle.isOn() : !isWifiAdbOn();
        if (!on) { doWifiAdb(false); return; }
        new AlertDialog.Builder(this)
                .setTitle("开启无线 ADB")
                .setMessage("会用包里内置的 mtk-su 临时提权，执行：\n\n"
                        + "  settings put global adb_enabled 1\n"
                        + "  setprop service.adb.tcp.port " + ADB_TCP_PORT + "\n"
                        + "  stop adbd\n"
                        + "  start adbd\n\n"
                        + "adbd 会重启一下，USB 连着的话会闪断。\n"
                        + "开好之后用 adb connect 本机IP:" + ADB_TCP_PORT + " 连。\n\n"
                        + "⚠ 开着的时候同一 WiFi 下谁都能连上来。\n"
                        + "　 所以开了之后 " + (WIFI_ADB_KEEP_MS / 60000) + " 分钟会自动关，\n"
                        + "　 想多留一会儿就再点一次开关重开。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doWifiAdb(true); }
                })
                .show();
    }

    /**
     * 真的去开 / 关无线 ADB。
     *
     * 开的脚本照原版：先 `settings put global adb_enabled 1` ——
     * adbd 只有在 ADB 调试打开时才会跑，不先开这一项的话，
     * `start adbd` 起不来，属性写了也没人监听。
     *
     * 跑完轮着探状态（adbd 重启要几秒），探到了才算成功，
     * 探不到就把执行过程摊出来给用户看。
     */
    private void doWifiAdb(final boolean on) {
        wifiBusy = true;
        if (wifiStateText != null) wifiStateText.setText("处理中…");
        if (wifiToggle != null) wifiToggle.setUsable(false);
        toast(on ? "正在开启无线 ADB…" : "正在关闭无线 ADB…");

        new Thread(new Runnable() {
            @Override public void run() {
                String script = on
                        ? "settings put global adb_enabled 1\n"
                          + "setprop service.adb.tcp.port " + ADB_TCP_PORT + "\n"
                          + "stop adbd\n"
                          + "start adbd\n"
                          + "echo AWA_ADB_OK"
                        : "setprop service.adb.tcp.port -1\n"
                          + "stop adbd\n"
                          + "start adbd\n"
                          + "echo AWA_ADB_OK";
                StringBuilder sb = new StringBuilder();
                boolean ran = execScript(script, sb);

                // adbd 重启要好几秒，得轮着探，探一次就判死会误报失败
                boolean now = false;
                if (ran) {
                    for (int i = 0; i < 14; i++) {
                        try { Thread.sleep(700); } catch (Throwable t) { }
                        now = isWifiAdbOn();
                        if (now == on) break;
                    }
                }
                final boolean got = now;
                final boolean rootOk = ran;
                final String log = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        wifiBusy = false;
                        if (got == on) {
                            if (on) startWifiCountdown(); else stopWifiCountdown();
                            fillSettings();
                            toast(on ? "无线 ADB 已开启，端口 " + ADB_TCP_PORT
                                     : "无线 ADB 已关闭");
                        } else if (!rootOk) {
                            // 提权本身就没跑通 —— 弹「需要连一次电脑」的引导，
                            // 别只甩一堆异常日志给用户看（原版也是这个路子）
                            fillSettings();
                            showRootHelp(log, on);
                        } else {
                            fillSettings();
                            showText(on ? "开启无线 ADB 没成功" : "关闭无线 ADB 没成功",
                                    "执行过程\n\n" + log + "\n" + wifiAdbHelp());
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 提权没跑通时弹的引导。照原版 AwaAPK 的 showRootHelp。
     *
     * 这台 R621 的 SELinux 策略不允许应用执行自己私有目录里的副本，
     * /sdcard 又是 FUSE + noexec，所以必须靠电脑侧 ADB 把 mtk-su
     * 放进 /data/local/tmp —— 那个位置 SELinux 明确允许应用执行。
     *
     * 这一步只需要做一次，做完就永久有效（除非清理缓存）。
     */
    private void showRootHelp(final String log, final boolean wasOn) {
        // 顺手把内置的 mtk-su 导一份到 /sdcard，省得用户自己找
        final String sdcardPath = releaseMtksuToSdcard();

        new AlertDialog.Builder(this)
                .setTitle("需要连一次电脑")
                .setMessage("软件自己试过了，没能拿到 root。\n\n"
                        + "mtk-su 已经释放到：\n"
                        + "  " + (sdcardPath == null ? "/sdcard/mtk-su" : sdcardPath) + "\n\n"
                        + "这台设备的 SELinux 策略不允许应用直接执行自己的副本，"
                        + "所以需要借一次 ADB 把它放进 /data/local/tmp。\n\n"
                        + "把车机用 USB 连上电脑，执行下面这条命令：\n\n"
                        + "  " + stagingCommand() + "\n\n"
                        + "这条命令只需执行一次。执行完点下面的按钮检测即可。")
                .setPositiveButton("我已完成，检测一下", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // 用户去 /data/local/tmp 放好了，清掉失败缓存再试
                        failedSu.clear();
                        doWifiAdb(wasOn);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 原版 RootHelper.stagingCommand()，一字不差 */
    private static String stagingCommand() {
        return "adb shell \"cp /sdcard/mtk-su /data/local/tmp/mtk-su "
                + "&& chmod 755 /data/local/tmp/mtk-su\"";
    }

    /**
     * 把 assets/mtk-su 导到 /sdcard/mtk-su，让上面的 staging 命令能用。
     * 返回导出的完整路径，失败返回 null。
     */
    private String releaseMtksuToSdcard() {
        File out = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            out = new File(Environment.getExternalStorageDirectory(), "mtk-su");
            // 已经有且大小一样就不重复写
            if (out.isFile() && out.length() == 65144L) return out.getAbsolutePath();
            in = getAssets().open("mtk-su");
            os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQ(in);
            closeQ(os);
            if (out != null) {
                try { out.setReadable(true, false); } catch (Throwable t) { }
            }
        }
    }

    /** 点绿字那行：把 adb connect 命令复制走 */
    private void copyWifiConnect() {
        String cmd = wifiConnectCommand();
        if (cmd == null) { toast("还没连上网络，没有 IP 可用"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
            toast("已复制：" + cmd);
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private String wifiConnectCommand() {
        String ip = localIpRaw();
        if (ip == null) return null;
        return "adb connect " + ip + ":" + ADB_TCP_PORT;
    }

    /**
     * 刷「adb connect …　·　N 分钟后自动关闭」那一行。
     * 照原版 updateWifiExtra：没在倒计时就整行藏起来。
     */
    private void updateWifiExtra() {
        if (wifiExtraText == null || wifiWarnText == null) return;
        if (wifiOffAt <= 0) {
            wifiExtraText.setVisibility(View.GONE);
            wifiWarnText.setVisibility(View.GONE);
            return;
        }
        long left = Math.max(0, wifiOffAt - System.currentTimeMillis());
        long mins = (left + 59999) / 60000;      // 向上取整，剩 1 秒也显示「1 分钟」
        String cmd = wifiConnectCommand();
        wifiExtraText.setText((cmd == null ? "adb connect 车机IP:" + ADB_TCP_PORT : cmd)
                + "　·　" + mins + " 分钟后自动关闭");
        wifiExtraText.setVisibility(View.VISIBLE);
        wifiWarnText.setVisibility(View.VISIBLE);
    }

    /** 开始倒计时：30 分钟后自动关无线 ADB */
    private void startWifiCountdown() {
        setWifiOffAt(System.currentTimeMillis() + WIFI_ADB_KEEP_MS);
        ui.removeCallbacks(wifiTick);
        ui.post(wifiTick);
    }

    /** 停掉倒计时（用户手动关了，或者时间到了已经关掉了） */
    private void stopWifiCountdown() {
        setWifiOffAt(0);
        ui.removeCallbacks(wifiTick);
        updateWifiExtra();
    }

    private void setWifiOffAt(long at) {
        wifiOffAt = at;
        try {
            SharedPreferences p = getPreferences(MODE_PRIVATE);
            p.edit().putLong(PREF_OFF_AT, at).commit();
        } catch (Throwable t) { }
    }

    /**
     * 倒计时的滴答。照原版 wifiTick：
     *   没在倒计时 → 什么都不做
     *   时间到了 → 自动关（正在忙就 5 秒后再来）
     *   还没到   → 刷新那行文字，20 秒后再滴答
     */
    private final Runnable wifiTick = new Runnable() {
        @Override public void run() {
            if (wifiOffAt <= 0) return;
            if (System.currentTimeMillis() >= wifiOffAt) {
                if (wifiBusy) { ui.postDelayed(this, 5000); return; }
                doWifiAdb(false);          // 时间到，自动关
                return;
            }
            updateWifiExtra();
            ui.postDelayed(this, 20000);
        }
    };

    /**
     * 回到前台时接上倒计时。
     * 只有「属性里确实还开着」才接 —— 比如应用被杀掉、或者用户中途
     * 在电脑上敲了 adb usb 关掉了，这里就不会自作主张再去关一次。
     */
    private void resumeWifiAdbTimer() {
        new Thread(new Runnable() {
            @Override public void run() {
                if (readWifiAdbPort() <= 0) return;
                long at = 0;
                try {
                    at = getPreferences(MODE_PRIVATE).getLong(PREF_OFF_AT, 0);
                } catch (Throwable t) { }
                final long offAt = at;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (offAt > 0) {
                            // 上次记的还没到点，接着算
                            wifiOffAt = offAt;
                            ui.removeCallbacks(wifiTick);
                            ui.post(wifiTick);
                        } else {
                            // 开着但没记时间（比如别处开的），重新起一个 30 分钟
                            startWifiCountdown();
                        }
                        fillSettings();
                    }
                });
            }
        }).start();
    }

    private String wifiAdbHelp() {
        return "无线 ADB 是什么\n"
                + "  让车机上的 adbd 额外监听一个 TCP 端口（" + ADB_TCP_PORT + "），\n"
                + "  这样不用插 USB 线，同一 WiFi 下 adb connect 就能连上车机。\n"
                + "\n"
                + "为什么必须 root\n"
                + "  它靠系统属性 service.adb.tcp.port 控制，而系统属性只有 root 能写。\n"
                + "  Android 5.1 的 Settings.Global 里没有 adb_tcp_port 这个设置项\n"
                + "  （那是 Android 6.0 才加的），系统压根不听，\n"
                + "  所以车机内部不存在「免 root 开无线 ADB」的接口 ——\n"
                + "  想做成按钮，就只能借 root。这就是本工具唯一会提权的地方。\n"
                + "\n"
                + "── 用开关一键开（推荐）──\n"
                + "  直接点「无线 ADB」的开启就行。包里内置了 mtk-su（和原版一样），\n"
                + "  第一次用会自动释放到应用私有目录并 chmod，不用你手动投放。\n"
                + "  本工具会执行：\n"
                + "    setprop service.adb.tcp.port " + ADB_TCP_PORT + "\n"
                + "    stop adbd\n"
                + "    start adbd\n"
                + "\n"
                + "  提权程序按这个顺序试：\n"
                + "    ① 上次跑通的那个\n"
                + "    ② /data/local/tmp/mtk-su（你自己投放过的话，优先用它）\n"
                + "    ③ 内置的 mtk-su（释放到应用私有目录）\n"
                + "    ④ 系统本来就带的 su\n"
                + "  每个都先试 su -c \"脚本\"，不行再把脚本喂给 stdin，\n"
                + "  末尾追加 echo __AWA_END__，输出里出现它才算真跑通。\n"
                + "\n"
                + "── 手敲也行（adb shell 里）──\n"
                + "  /data/local/tmp/mtk-su\n"
                + "  setprop service.adb.tcp.port " + ADB_TCP_PORT + "\n"
                + "  stop adbd\n"
                + "  start adbd\n"
                + "\n"
                + "  然后这样连（IP 见「车机信息」里的本机 IP）：\n"
                + "  adb connect 车机IP:" + ADB_TCP_PORT + "\n"
                + "\n"
                + "── 关 ──\n"
                + "  setprop service.adb.tcp.port -1\n"
                + "  stop adbd\n"
                + "  start adbd\n"
                + "\n"
                + "── 免 root 的另一条路：从电脑开 ──\n"
                + "  USB 连着的时候在电脑上敲一条：\n"
                + "    adb tcpip " + ADB_TCP_PORT + "\n"
                + "  这条不用 root —— 是让车机上的 adbd 自己去改属性。\n"
                + "  拔掉 USB 再 adb connect 车机IP:" + ADB_TCP_PORT + " 就行，\n"
                + "  用完在电脑上敲 adb usb 关掉。\n"
                + "\n"
                + "── 开关不灵的时候 ──\n"
                + "  点「设置 → 无线 ADB · 提权自检」，它会把候选程序、\n"
                + "  每一步的输出、提权后的 uid 全摊出来，一眼能看出卡在哪。\n"
                + "\n"
                + "  所有候选都跑不通时，软件会自动弹出「需要连一次电脑」的引导，\n"
                + "  并把内置的 mtk-su 导到 /sdcard/mtk-su 备用。你只要做一次：\n"
                + "  adb push mtk-su /sdcard/\n"
                + "  adb shell \"cp /sdcard/mtk-su /data/local/tmp/mtk-su "
                    + "&& chmod 755 /data/local/tmp/mtk-su\"\n"
                + "\n"
                + "  为什么非这一次不可：这台 R621 的 SELinux 策略不允许应用\n"
                + "  执行自己私有目录里的副本，而 /sdcard 是 FUSE + noexec\n"
                + "  （放那儿能读不能跑）。只有 /data/local/tmp 是 SELinux\n"
                + "  明确允许应用执行的位置。做一次就永久有效。\n"
                + "\n"
                + "⚠ 无线 ADB 开着的时候，同一 WiFi 下谁都能连上来，用完记得关。\n";
    }

    // ------------------------------------------------------------ 设置：WiFi
    private WifiManager wifi() {
        try {
            return (WifiManager) getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isWifiOn() {
        try {
            WifiManager wm = wifi();
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleWifi() {
        final boolean on = !isWifiOn();
        if (!on) {
            new AlertDialog.Builder(this)
                    .setTitle("关闭 WiFi")
                    .setMessage("关掉 WiFi 之后：\n\n"
                            + "· 无线 ADB 会断开（如果你正用它连着电脑）\n"
                            + "· 车机的联网功能会中断\n\n"
                            + "确定要关吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定关闭", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { setWifi(false); }
                    })
                    .show();
            return;
        }
        setWifi(true);
    }

    private void setWifi(boolean on) {
        try {
            WifiManager wm = wifi();
            if (wm == null) {
                toast("这台车机没有 WiFi 服务");
                return;
            }
            wm.setWifiEnabled(on);
            toast(on ? "正在打开 WiFi…" : "正在关闭 WiFi…");
        } catch (Throwable t) {
            toast("WiFi 开关失败：" + t);
        }
        // setWifiEnabled 是异步的，立刻回读还是旧状态、界面会显示反。
        // 等它切完再刷新（同时刷两次，切得快的机器上不用等那么久）。
        if (settingsBox != null) {
            settingsBox.postDelayed(new Runnable() {
                @Override public void run() { fillSettings(); }
            }, 900);
            settingsBox.postDelayed(new Runnable() {
                @Override public void run() { fillSettings(); }
            }, 2600);
        }
    }

    // ------------------------------------------------------------ 设置：原生设置
    /**
     * 「打开原生系统设置」—— 不直接跳主设置页，照原版弹一个入口列表。
     *
     * 车机上最常去的就那几个页（开发者选项 / 应用管理 / 存储 / 无线和网络…），
     * 一层层点太费劲。这里用显式 ComponentName 直接拉对应 Activity，
     * 比 Intent action 准 —— 不同 ROM 的 action 实现不一样。
     *
     * 每一项都带一条兜底：显式组件打不开就退回对应的 android.settings.* action，
     * 再不行退回设置主页。这台 R621 的包名是 com.android.settings。
     */
    private void openNativeSettings() {
        final String P = "com.android.settings";
        final String[][] items = {
                {"开发者选项", P + "/com.android.settings.Settings$DevelopmentSettingsActivity",
                        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"},
                {"应用管理", P + "/com.android.settings.Settings$ManageApplicationsActivity",
                        "android.settings.MANAGE_APPLICATIONS_SETTINGS"},
                {"存储", P + "/com.android.settings.Settings$StorageSettingsActivity",
                        "android.settings.INTERNAL_STORAGE_SETTINGS"},
                {"无线和网络", P + "/com.android.settings.Settings$WirelessSettingsActivity",
                        "android.settings.WIRELESS_SETTINGS"},
                {"蓝牙", P + "/com.android.settings.Settings$BluetoothSettingsActivity",
                        "android.settings.BLUETOOTH_SETTINGS"},
                {"安全", P + "/com.android.settings.Settings$SecuritySettingsActivity",
                        "android.settings.SECURITY_SETTINGS"},
                {"位置信息", P + "/com.android.settings.Settings$LocationSettingsActivity",
                        "android.settings.LOCATION_SOURCE_SETTINGS"},
                {"日期和时间", P + "/com.android.settings.Settings$DateTimeSettingsActivity",
                        "android.settings.DATE_SETTINGS"},
                {"设备信息", P + "/com.android.settings.Settings$DeviceInfoSettingsActivity",
                        "android.settings.DEVICE_INFO_SETTINGS"},
                {"WLAN", P + "/com.android.settings.Settings$WifiSettingsActivity",
                        "android.settings.WIFI_SETTINGS"},
                {"流量使用", P + "/com.android.settings.Settings$DataUsageSummaryActivity", null},
                {"电源使用", P + "/com.android.settings.Settings$PowerUsageSummaryActivity",
                        "android.settings.BATTERY_SAVER_SETTINGS"},
                {"车机设置（ECARX）", null, null},
                {"全部设置（原生）", P + "/com.android.settings.Settings",
                        "android.settings.SETTINGS"},
        };
        String[] labels = new String[items.length];
        for (int i = 0; i < items.length; i++) labels[i] = items[i][0];

        new AlertDialog.Builder(this)
                .setTitle("原生系统设置")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 12) {
                            openEcarxSettings();
                            return;
                        }
                        openNativeSetting(items[w][1], items[w][2]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 打开某一页：先试显式组件，再试 action，最后退回设置主页。
     * 返回 true 表示真的打开了某个页面。
     */
    private boolean openNativeSetting(String componentDesc, String action) {
        // ① 显式组件
        if (componentDesc != null) {
            int slash = componentDesc.indexOf('/');
            if (slash > 0) {
                String pkg = componentDesc.substring(0, slash);
                String cls = componentDesc.substring(slash + 1);
                if (!cls.startsWith(pkg + ".")) cls = pkg + "." + cls;
                try {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.setClassName(pkg, cls);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return true;
                } catch (Throwable t) { }
            }
        }
        // ② Intent action
        if (action != null) {
            try {
                Intent i = new Intent(action);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable t) { }
        }
        // ③ 设置主页
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) { }
        toast("这台车机打不开这个页面");
        return false;
    }

    /** 车机自己的设置（ECARX / 吉利）。包名试一串，哪个在点哪个 */
    private void openEcarxSettings() {
        String[][] cands = {
                {"ecarx.settings", "ecarx.settings.activity.MainSettingActivtiy"},
                {"ecarx.settings", "ecarx.settings.activity.MainSettingActivity"},
                {"com.ecarx.settings", "com.ecarx.settings.activity.MainSettingActivtiy"},
                {"com.ecarx.settings", "com.ecarx.settings.activity.MainSettingActivity"},
                {"com.geely.syssettings", "com.geely.syssettings.MainActivity"},
        };
        for (int i = 0; i < cands.length; i++) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(cands[i][0], cands[i][1]);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable t) { }
        }
        // 都不是，退回原生设置主页
        if (!openNativeSetting("com.android.settings/com.android.settings.Settings",
                Settings.ACTION_SETTINGS)) {
            toast("这台车机隐藏了这个页面");
        }
    }

    // ============================================================ 重启
    private void confirmReboot() {
        if (!hasPerm("android.permission.REBOOT")) {
            showText("重启需要系统权限", rebootHelp());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("重启车机")
                .setMessage("重启会中断车机当前所有操作，包括导航、音乐、蓝牙通话。\n\n"
                        + "请确认车辆已安全停稳。\n\n"
                        + "确定现在重启吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定重启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doReboot(); }
                })
                .show();
    }

    /**
     * 只用系统 REBOOT 权限重启。装到 /system/priv-app 后这个权限自动生效，不需要 root。
     * 拿不到权限就直接把命令摊给用户 —— 本工具不会去提权。
     */
    private void doReboot() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                pm.reboot(null);
                return;   // 成功的话设备已经开始重启，这里不会往下走
            }
        } catch (Throwable t) {
            // SecurityException：没有 REBOOT 权限，下面给命令
        }
        showText("重启没生效", rebootHelp());
    }

    private String rebootHelp() {
        return "本应用现在没有 REBOOT 权限，所以没法直接重启。\n"
                + "本工具不会去提权，下面是命令，你自己执行。\n"
                + "\n"
                + "── 办法一：把本应用装成系统应用（推荐，一劳永逸，免 root）──\n"
                + "Android 5.1 没有 privapp 权限白名单，只要装进 /system/priv-app，\n"
                + "清单里声明的 REBOOT 权限就自动生效，以后点「重启」就直接重启。\n"
                + "步骤看「存储里的 APK」→ 点本工具 APK → 安装教程「二、安装为系统程序」。\n"
                + "\n"
                + "── 办法二：电脑上敲一条 ──\n"
                + "  adb shell reboot\n"
                + "\n"
                + "── 办法三：需要 root 时先提权（mtk-su 自己投放）──\n"
                + "mtk-su 下载地址\n"
                + "  " + MTK_URL + "\n"
                + "  adb push mtk-su /sdcard/\n"
                + "  adb shell \"cp /sdcard/mtk-su /data/local/tmp/ && chmod 755 /data/local/tmp/mtk-su\"\n"
                + "  adb shell \"/data/local/tmp/mtk-su\"\n"
                + "  reboot\n";
    }

    // ============================================================ 安装教程
    private void showInstallTutorial(ApkEntry e) {
        String dirName = safeDir(e);
        String apkName = e.fileName;
        String pkg = TextUtils.isEmpty(e.pkg) ? "<包名>" : e.pkg;
        StringBuilder b = new StringBuilder();

        b.append("【这个 APK】\n");
        b.append("  文件名   ").append(apkName).append("\n");
        b.append("  所在目录 ").append(e.dirPath).append("\n");
        b.append("  完整路径 ").append(e.path).append("\n");
        b.append("  大小     ").append(size(e.size)).append("\n");
        b.append("  包名     ").append(TextUtils.isEmpty(e.pkg) ? "（读不出来）" : e.pkg).append("\n");
        if (!TextUtils.isEmpty(e.verName)) {
            b.append("  版本     ").append(e.verName)
                    .append("（code ").append(e.verCode).append("）\n");
        }
        if (e.parseFailed) {
            b.append("  状态     ⚠ 解析失败（可能不是有效 APK，或与 Android 5.1 不兼容）\n");
        } else if (!e.installed) {
            b.append("  状态     车机上还没装 → 待安装\n");
        } else {
            b.append("  状态     车机上已经装了\n");
            b.append("           装着的版本  ").append(
                    TextUtils.isEmpty(e.installedVerName)
                            ? "code " + e.installedVerCode
                            : e.installedVerName + "（code " + e.installedVerCode + "）").append("\n");
            if (e.verCode > e.installedVerCode) {
                b.append("           → 这个 APK 更新（").append(e.installedVerCode)
                        .append(" → ").append(e.verCode).append("），装上去就是升级\n");
            } else if (e.verCode < e.installedVerCode) {
                b.append("           → 这个 APK 更旧（").append(e.installedVerCode)
                        .append(" → ").append(e.verCode).append("），装上去就是降级\n");
            } else {
                b.append("           → 版本一样，重装只是覆盖，没变化\n");
            }
        }
        b.append("\n");
        b.append("本工具不会替你安装。下面是两条路，你自己动手。\n");

        // ======================= 一、普通安装
        b.append("\n");
        b.append("════════════════════════════════════════\n");
        b.append("一、普通安装 apk（可桌面卸载）\n");
        b.append("════════════════════════════════════════\n");
        b.append("\n");
        b.append("4.1  mtk 文件提权，获取 root 临时权限后安装 apk\n");
        b.append("\n");
        b.append("     先用「文件管理」把两个文件传到车机：\n");
        b.append("       · mtk-su   →  上传到  /sdcard/  目录下\n");
        b.append("       · ").append(apkName).append("  →  上传到  /sdcard/Download/  目录下\n");
        b.append("\n");
        b.append("     mtk-su 下载地址\n");
        b.append("       ").append(MTK_URL).append("\n");
        b.append("\n");
        b.append("     提权命令\n");
        b.append("       cp /sdcard/mtk-su /data/local/tmp/\n");
        b.append("       chmod 755 /data/local/tmp/mtk-su\n");
        b.append("       /data/local/tmp/mtk-su\n");
        b.append("       mount -r -w -o remount -t ext4 /system\n");
        b.append("\n");
        b.append("     安装命令\n");
        b.append("       cp /sdcard/Download/").append(apkName).append(" /data/app\n");
        b.append("       chmod 777 /data/app/").append(apkName).append("\n");
        b.append("\n");
        b.append("     重启命令\n");
        b.append("       reboot\n");
        b.append("\n");
        b.append("     重启后进入就看到了。\n");
        b.append("\n");
        b.append("     ── 如果重启后桌面上还是没出现 ──\n");
        b.append("     说明 PMS 没认这个直接丢进去的文件，改用系统安装器。\n");
        b.append("     一样落在 /data/app、一样可以桌面卸载：\n");
        b.append("       pm install -r \"/sdcard/Download/").append(apkName).append("\"\n");

        // ======================= 二、安装为系统程序
        b.append("\n");
        b.append("════════════════════════════════════════\n");
        b.append("二、安装为系统程序\n");
        b.append("════════════════════════════════════════\n");
        b.append("\n");
        b.append("5.1  提权\n");
        b.append("       （mtk-su 下载地址 ").append(MTK_URL).append(" ）\n");
        b.append("       cp /sdcard/mtk-su /data/local/tmp/\n");
        b.append("       chmod 755 /data/local/tmp/mtk-su\n");
        b.append("       /data/local/tmp/mtk-su\n");
        b.append("       mount -r -w -o remount -t ext4 /system\n");
        b.append("\n");
        b.append("5.2  放进系统分区（注意是 priv-app，不是 /data/app）\n");
        b.append("       mkdir -p /system/priv-app/").append(dirName).append("\n");
        b.append("       cp /sdcard/Download/").append(apkName)
                .append(" /system/priv-app/").append(dirName).append("/").append(apkName).append("\n");
        b.append("       chmod 644 /system/priv-app/").append(dirName)
                .append("/").append(apkName).append("\n");
        b.append("       chown root:root /system/priv-app/").append(dirName)
                .append("/").append(apkName).append("\n");
        b.append("       restorecon -R /system/priv-app/").append(dirName).append("\n");
        b.append("\n");
        b.append("     （目录名 ").append(dirName).append(" 是按包名推的，只保留字母数字下划线，\n");
        b.append("       因为它要拼进 shell 命令。你可以自己改名。）\n");
        b.append("\n");
        b.append("5.3  恢复只读并重启\n");
        b.append("       mount -r -o remount -t ext4 /system\n");
        b.append("       sync\n");
        b.append("       reboot\n");
        b.append("\n");
        b.append("5.4  如果失败\n");
        b.append("       · mount -o remount 报错（只读 / dm-verity 保护）\n");
        b.append("         写不了系统分区，这条路走不通，告诉我，换别的方案。\n");
        b.append("       · pm path 仍指向 /data/app\n");
        b.append("         没装进系统分区，检查 5.2 的路径。\n");
        b.append("       · dumpsys 里没有 MANAGE_USB\n");
        b.append("         权限没拿到，弹窗会退回原样（不会更糟）。\n");
        b.append("       · 开机后应用闪退 / 找不到\n");
        b.append("         试试 restorecon -R /system/priv-app/").append(dirName).append(" 后重启。\n");
        b.append("\n");
        b.append("5.5  回退卸载方法（删除整个目录，重启）\n");
        b.append("       cp /sdcard/mtk-su /data/local/tmp/\n");
        b.append("       chmod 755 /data/local/tmp/mtk-su\n");
        b.append("       /data/local/tmp/mtk-su                      # 进 root shell\n");
        b.append("       mount -r -w -o remount -t ext4 /system      # ← 关键，先变可写\n");
        b.append("       rm -rf /system/priv-app/").append(dirName).append("\n");
        b.append("       mount -r -o remount -t ext4 /system         # 收回只读\n");
        b.append("       sync\n");
        b.append("       reboot\n");
        b.append("\n");
        b.append("     ⚠ 那两条 mount 一个都不能省。只写不收回只读就关机，\n");
        b.append("       脏页要回写、开机还要跑 ext4 日志重放，重启会慢到像死机。\n");
        b.append("\n");
        b.append("────────────────────────────────────────\n");
        b.append("验证一下装到哪了\n");
        b.append("────────────────────────────────────────\n");
        b.append("   adb shell pm path ").append(pkg).append("\n");
        b.append("   /data/app/...   → 普通安装\n");
        b.append("   /system/priv-app/... → 系统应用\n");

        showText("安装教程 · " + apkName, b.toString());
    }

    // ============================================================ 卸载教程
    private void showUninstallTutorial(AppEntry e) {
        StringBuilder b = new StringBuilder();
        b.append("【这个应用】\n");
        b.append("  应用名   ").append(e.label).append("\n");
        b.append("  包名     ").append(e.pkg).append("\n");
        b.append("  版本     ").append(TextUtils.isEmpty(e.verName) ? "—" : e.verName)
                .append(e.verCode > 0 ? "（code " + e.verCode + "）" : "").append("\n");
        b.append("  类型     ").append(e.system
                        ? (e.priv ? "系统应用 · 特权（/system/priv-app）" : "系统应用（/system/app）")
                        : "用户应用（自己装的）").append("\n");
        b.append("  所在目录 ").append(e.dirPath).append("\n");
        b.append("  APK 文件 ").append(e.fileName).append("\n");
        b.append("  完整路径 ").append(e.srcDir).append("\n");
        if (e.size > 0) b.append("  文件大小 ").append(size(e.size)).append("\n");
        b.append("\n");
        b.append("本工具不会替你卸载。下面是命令，你自己在电脑上跑 ADB。\n");

        if (e.system) {
            b.append("\n");
            b.append("════════════════════════════════════════\n");
            b.append("系统应用：优先「停用」，别急着删\n");
            b.append("════════════════════════════════════════\n");
            b.append("⚠ 系统应用跟车机原厂功能绑在一起，删掉可能导致某项功能异常，\n");
            b.append("   而且删了不好恢复。如果只是不想让它出现，用停用就够了。\n");
            b.append("\n");
            b.append("【方案 A · 停用（推荐：安全、随时可恢复、不需要 root）】\n");
            b.append("   adb shell pm disable-user --user 0 ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("   恢复回来：\n");
            b.append("   adb shell pm enable ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("   看看现在是不是停用状态：\n");
            b.append("   adb shell pm list packages -d | grep ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("【方案 B · 真正删除（危险、不可逆，需要临时 root）】\n");
            b.append("   删之前先备份出来！\n");
            b.append("   adb pull ").append(e.srcDir).append(" D:\\backup\\\n");
            b.append("\n");
            b.append("   ① 提权（mtk-su 下载地址 ").append(MTK_URL).append(" ）\n");
            b.append("      cp /sdcard/mtk-su /data/local/tmp/\n");
            b.append("      chmod 755 /data/local/tmp/mtk-su\n");
            b.append("      /data/local/tmp/mtk-su\n");
            b.append("      mount -r -w -o remount -t ext4 /system\n");
            b.append("\n");
            b.append("   ② 删除（目录和里面的 apk 一起删）\n");
            b.append("      rm -rf ").append(e.dirPath).append("\n");
            b.append("\n");
            b.append("   ③ 清掉残留数据（可选，想干净就删）\n");
            b.append("      rm -rf /data/data/").append(e.pkg).append("\n");
            b.append("\n");
            b.append("   ④ 收回只读并重启\n");
            b.append("      mount -r -o remount -t ext4 /system\n");
            b.append("      sync\n");
            b.append("      reboot\n");
            b.append("\n");
            b.append("【删错了怎么救】\n");
            b.append("   用上面备份出来的文件拷回去，再修一次 SELinux 上下文：\n");
            b.append("   adb shell \"mount -o remount,rw /system\"\n");
            b.append("   adb push ").append(e.fileName).append(" ")
                    .append(e.dirPath).append("/").append(e.fileName).append("\n");
            b.append("   adb shell \"chmod 644 ").append(e.srcDir).append("\"\n");
            b.append("   adb shell \"chown root:root ").append(e.srcDir).append("\"\n");
            b.append("   adb shell \"restorecon -R ").append(e.dirPath).append("\"\n");
            b.append("   adb shell \"sync\"\n");
            b.append("   adb shell \"mount -o remount,ro /system\"\n");
            b.append("   adb shell reboot\n");
        } else {
            b.append("\n");
            b.append("════════════════════════════════════════\n");
            b.append("用户应用：直接卸就行\n");
            b.append("════════════════════════════════════════\n");
            b.append("【标准卸载】\n");
            b.append("   adb uninstall ").append(e.pkg).append("\n");
            b.append("   或者\n");
            b.append("   adb shell pm uninstall --user 0 ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("【卸不掉的时候】\n");
            b.append("   先看它是不是设备管理员或者有特殊权限：\n");
            b.append("   adb shell dumpsys device_policy | grep -i ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("   保留数据卸载（方便以后重装）：\n");
            b.append("   adb shell pm uninstall -k --user 0 ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("   只想清数据、不想卸载：\n");
            b.append("   adb shell pm clear ").append(e.pkg).append("\n");
            b.append("\n");
            b.append("【它的文件在哪】\n");
            b.append("   ").append(e.srcDir).append("\n");
            b.append("   注意 /data/app 这个目录普通应用读不到，想看内容得先提权：\n");
            b.append("   adb shell \"ls -l /data/app/ | grep ").append(e.pkg).append("\"\n");
        }

        b.append("\n");
        b.append("────────────────────────────────────────\n");
        b.append("顺手看一眼这个应用当前的状态\n");
        b.append("────────────────────────────────────────\n");
        b.append("   adb shell dumpsys package ").append(e.pkg).append(" | head -40\n");

        showText("卸载教程 · " + e.label, b.toString());
    }

    // ------------------------------------------------------------ 工具
    /** 目录名建议：有包名用包名，没有就用文件名 */
    private static String safeDir(ApkEntry e) {
        return sanitize(TextUtils.isEmpty(e.pkg) ? stripExt(e.fileName) : e.pkg);
    }

    private static String stripExt(String n) {
        if (n == null) return "";
        int i = n.toLowerCase(Locale.US).lastIndexOf(".apk");
        return i > 0 ? n.substring(0, i) : n;
    }

    /** 只保留字母数字下划线 —— 这些名字会被拼进 shell 命令，必须安全 */
    private static String sanitize(String s) {
        if (s == null) return "app";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') b.append(c);
        }
        if (b.length() == 0) return "app";
        if (b.charAt(0) >= '0' && b.charAt(0) <= '9') b.insert(0, 'a');
        return b.toString();
    }

    // ------------------------------------------------------------ 文本弹窗
    private void showText(String title, final String body) {
        final float limit = getResources().getDisplayMetrics().heightPixels * 0.62f;
        ScrollView sv = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(
                        (int) limit, MeasureSpec.AT_MOST));
            }
        };
        sv.setBackgroundColor(0xFF1B1F26);
        int pad = dp(16);
        sv.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFFDCE3EC);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(3), 1f);
        sv.addView(tv, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制全部", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("awa", body));
                            toast("已复制到剪贴板");
                        } catch (Throwable t) {
                            toast("复制失败");
                        }
                    }
                })
                .show();
    }

    private void showAbout() {
        StringBuilder b = new StringBuilder();
        b.append(APP_TITLE).append("\n\n");
        b.append("这个工具干三件事：\n");
        b.append("  1) 已安装的软件 —— 列出车机上的系统 + 用户应用，点进去看\n");
        b.append("     它的目录 / 文件名，和卸载教程命令。\n");
        b.append("  2) 存储里的 APK —— 列出 U盘 / 内部存储里的 APK，点进去看\n");
        b.append("     安装教程命令（普通安装 /data/app、装成系统程序 /system/priv-app）。\n");
        b.append("  3) 文件管理 —— 自带文件管理器。顶栏「车机」「U盘」一键切换两边目录，\n");
        b.append("     长按条目可以 复制 / 剪切 / 重命名 / 删除 / 看属性，\n");
        b.append("     剪贴板里的东西可以粘到另一边的目录里（车机 ↔ U盘 互拷）。\n\n");
        b.append("本工具不会替你安装、不会替你卸载。\n");
        b.append("只有「无线 ADB」这一项会借 root —— 用你自己投放的 mtk-su\n");
        b.append("执行一条 setprop；其余功能一律不提权。\n\n");
        b.append("无线 ADB 开关不灵的时候，去「设置 → 无线 ADB · 提权自检」\n");
        b.append("点一下，它会把提权后的 uid、报错、exit code 全摊出来。\n\n");
        b.append("────────────────────────────\n");
        b.append("顶栏两个按钮\n");
        b.append("  设置 —— 车机信息（IP / 版本 / 机型 / 存储）、ADB 调试开关、\n");
        b.append("          无线 ADB 开关（一键，开 30 分钟自动关）、WiFi 开关、\n");
        b.append("          无线 ADB · 提权自检、打开原生系统设置（14 个常用入口）。\n");
        b.append("  重启 —— 重启车机。\n\n");
        b.append("「存储里的 APK」会自动判断车机上装没装过：\n");
        b.append("  没装 → 待安装；装着且版本更高 → 可更新；\n");
        b.append("  装着但版本更旧 → 版本更低；版本一样 → 已安装。\n");
        b.append("  没装的排在前面，已装的沉到最后。\n\n");
        b.append("重启 / ADB 开关怎么才能免 root\n");
        b.append("  它们要的权限（REBOOT / WRITE_SECURE_SETTINGS）是\n");
        b.append("  signature|privileged 级的。Android 5.1 没有 privapp 权限白名单，\n");
        b.append("  所以只要把本应用装进 /system/priv-app，权限就自动生效，不用 root。\n");
        b.append("  装在 /data/app 当普通应用的话，这些开关改不了，\n");
        b.append("  本工具会把命令显示给你，不会自己去提权。\n\n");
        b.append("mtk-su 下载地址\n");
        b.append("  ").append(MTK_URL).append("\n\n");
        b.append("────────────────────────────\n");
        b.append("包名　com.awa.apkinstaller\n");
        b.append("来源　awa www.akkdiy.cn\n");
        b.append("目标　吉利 ECARX R621（MT6735 / Android 5.1 / API 22）\n");
        showText("关于 " + APP_TITLE, b.toString());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()) + 0.5f);
    }

    // ------------------------------------------------------------ 自绘开关
    /**
     * iOS 风格的小开关，照原版 AwaAPK 的 ToggleSwitch 1:1 还原。
     *
     * 尺寸 / 画法 / 配色全部对着原版字节码抄的：
     *   onMeasure → setMeasuredDimension(dp(56), dp(30))
     *   onDraw    → 轨道 rect(0,0,w,h)，圆角 r = h/2，drawRoundRect
     *               滑块圆心 x = on ? w - r : r，y = r，半径 r - dp(3)
     *   轨道色 开 #FF33B5E5 / 关 #FF5A5F6B
     *          不可用 开 #6633B5E5 / 关 #FF44474F
     *   滑块色 开 #FFFFFFFF / 关 #FFB0B0B0 / 不可用 #FF8A93A6
     *
     * 不用系统 Switch 是因为车机上那个控件又大又丑（4.x 是 Holo 的胶囊），
     * 原版就是自己画的，所以这里也自己画。
     */
    static class ToggleSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean on = false;
        private boolean usable = true;

        ToggleSwitch(Context c) {
            super(c);
        }

        private int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        boolean isOn() {
            return on;
        }

        void setOn(boolean v) {
            if (on != v) { on = v; invalidate(); }
        }

        void setUsable(boolean v) {
            if (usable != v) { usable = v; invalidate(); }
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(56f), dp(30f));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0f, 0f, w, h);

            int track;
            if (on) track = 0xFF33B5E5;
            else track = 0xFF5A5F6B;
            if (!usable) track = on ? 0x6633B5E5 : 0xFF44474F;
            paint.setColor(track);
            canvas.drawRoundRect(rect, r, r, paint);

            float sliderR = r - dp(3f);
            float cx = on ? (w - r) : r;

            int dot;
            if (!usable) dot = 0xFF8A93A6;
            else if (on) dot = 0xFFFFFFFF;
            else dot = 0xFFB0B0B0;
            paint.setColor(dot);
            canvas.drawCircle(cx, r, sliderR, paint);
        }
    }

    // ------------------------------------------------------------ 数据模型
    static class AppEntry {
        String label = "";
        String pkg = "";
        String verName = "";
        int verCode;
        String srcDir = "";
        String dirPath = "";
        String fileName = "";
        long size;
        boolean system;
        boolean priv;
        Drawable icon;
    }

    static class ApkEntry {
        String fileName = "";
        String path = "";
        String dirPath = "";
        long size;
        String pkg = "";
        String verName = "";
        int verCode;
        String label = "";
        Drawable icon;
        boolean parseFailed;
        /** 车机上是不是已经装了这个包 */
        boolean installed;
        /** 车机上装着的版本号 / 版本名（installed 为 true 时有效） */
        int installedVerCode;
        String installedVerName = "";
    }

    /** 文件管理器里的一行 */
    static class FileEntry {
        File file;
        String name = "";
        boolean dir;
        long size;
        long mtime;
    }
}
