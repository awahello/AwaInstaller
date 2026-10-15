package com.awa.apkinstaller;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 占位用：清单里声明了 AboutActivity，这里给一个最简单的实现，避免启动时找不到类。 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF14161A);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (getResources().getDisplayMetrics().density * 28 + 0.5f);
        box.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("Awa的安装助手\n\n"
                + "三个标签：已安装的软件 / 存储里的 APK / 文件管理。\n"
                + "点条目只显示对应的 ADB 安装 / 卸载教程命令，\n"
                + "「文件管理」自带一个文件管理器，不用装第三方文件管理器。\n"
                + "本工具不执行安装、卸载、提权。\n\n"
                + "com.awa.apkinstaller\n"
                + "awa www.akkdiy.cn");
        t.setTextColor(0xFFDCE3EC);
        t.setTextSize(14f);
        t.setLineSpacing(6f, 1f);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        sv.addView(box);
        setContentView(sv);
    }
}
