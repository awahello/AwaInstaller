// 验证提权「跑通判定」的修复是否真的堵住了 __AWA_END__ 误判。
// 单独建个文件跑，不依赖 android.jar。
public class TestHasEnd {

    static final String END_MARK = "__AWA_END__";

    // ---- 下面这两段逻辑必须和 MainActivity.java 里的一字不差 ----
    static boolean hasEnd(String s) {
        if (s == null || s.length() == 0) return false;
        if (s.startsWith("EXCEPTION:")) return false;
        if (s.contains(END_MARK)) {
            String[] lines = s.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals(END_MARK)) return true;
            }
        }
        return false;
    }

    static String safeErr(Throwable t) {
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

    // ---- 测试 ----
    static int pass = 0, fail = 0;

    static void t(String name, boolean got, boolean want) {
        if (got == want) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  期望 " + want + " 实际 " + got); }
    }

    static void ts(String name, String got, String want) {
        if (want.equals(got)) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  期望 \"" + want + "\" 实际 \"" + got + "\""); }
    }

    public static void main(String[] a) {
        System.out.println("=== hasEnd() 判定测试 ===");

        // ① 真实成功：脚本正常跑完，echo 单独一行
        t("正常成功（标记单独一行）",
                hasEnd("service.adb.tcp.port -1\nstop adbd\nstart adbd\n__AWA_END__"),
                true);

        // ② 标记前后有空格也算成功
        t("成功后带空白",
                hasEnd("some output\n  __AWA_END__  \n"),
                true);

        // ③ 关键 bug：IOException 消息里带完整命令行，命令行含 echo __AWA_END__
        String ioMsg = "Error running exec(). Command: [/sdcard/mtk-su, -c, "
                + "setprop service.adb.tcp.port -1\nstop adbd\nstart adbd\n"
                + "echo AWA_ADB_OK\necho __AWA_END__] "
                + "Working Directory: null Environment: null";
        t("IOException 带命令行（旧 bug 的触发场景）",
                hasEnd("EXCEPTION: " + ioMsg),
                false);

        // ④ 另一种异常形态：没走 EXCEPTION: 前缀，就纯异常消息
        t("纯异常消息（无前缀）",
                hasEnd(ioMsg),
                false);

        // ⑤ su 不支持 -c，把用法打回来（里面回显了脚本）
        t("su 用法回显脚本",
                hasEnd("usage: su [-c CMD] [-s SHELL] [-d] ...\n"
                        + "残留: echo __AWA_END__"),
                false);

        // ⑥ 空 / null
        t("null", hasEnd(null), false);
        t("空串", hasEnd(""), false);

        // ⑦ 正常但没标记（脚本被 SELinux 拦了，只有 avc denied）
        t("SELinux 拒绝（无标记）",
                hasEnd("avc:  denied  { execute } for ...\n1|/system/bin/sh:"),
                false);

        System.out.println();
        System.out.println("=== safeErr() 清洗测试 ===");

        String cleaned = safeErr(new java.io.IOException(ioMsg));
        t("清洗后不含结束标记", cleaned.contains(END_MARK), false);
        t("清洗后不含命令行", cleaned.contains("mtk-su"), false);
        t("清洗后不含 Working Directory 尾巴", cleaned.contains("Working Directory"), false);
        t("清洗后不含 Environment 尾巴", cleaned.contains("Environment"), false);
        t("清洗后不含换行", cleaned.replace("\n", "").length() == cleaned.length(), true);
        System.out.println("    清洗后 = \"" + cleaned + "\"");

        String cleaned2 = safeErr(new java.io.IOException("Error running exec(). Command: [x]"));
        t("无 Environment 时不越界", cleaned2.contains("]"), false);
        System.out.println("    清洗后 = \"" + cleaned2 + "\"");

        String cleaned3 = safeErr(new RuntimeException());
        ts("无消息时退回类名", cleaned3, "RuntimeException");

        // 经过 safeErr 处理过的异常，hasEnd 必须判失败
        t("safeErr + hasEnd 组合判定", hasEnd("EXCEPTION: " + cleaned), false);

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) { System.out.println("!!! 有失败项 !!!"); System.exit(1); }
        System.out.println("全部通过");
    }
}
