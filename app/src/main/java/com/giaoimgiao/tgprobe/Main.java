package com.giaoimgiao.tgprobe;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TG Probe
 * 目标: org.telegram.messenger.web
 *
 * 观测点: MessagesController.putUsers(ArrayList, boolean)
 * 该方法是客户端接收 User 对象的汇聚点, 参数即服务端解密后的 TL 对象,
 * 因此这里读到的 phone 值就是服务端实际下发的值(空 = 未下发)。
 *
 * 日志: TG私有目录 files/tgprobe.log (+ /sdcard/Download/tgprobe.log)
 * 配置: /sdcard/Download/tgprobe.conf
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TARGET = "org.telegram.messenger.web";
    private static final String LOG_PRIVATE = "/data/data/org.telegram.messenger.web/files/tgprobe.log";
    private static final String LOG_SDCARD = "/sdcard/Download/tgprobe.log";
    private static final String CONF_PATH = "/sdcard/Download/tgprobe.conf";

    private static volatile boolean cfgEnabled = true;
    private static volatile String watchUser = "";

    private static final Object LOG_LOCK = new Object();
    private static final Set<String> seen = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        loadConfig();
        log("========== TGProbe 注入 ==========");
        log("process=" + lpparam.processName + " enabled=" + cfgEnabled
                + " watch=" + (watchUser.isEmpty() ? "-" : watchUser));

        try {
            hookPutUsers(lpparam);
        } catch (Throwable t) {
            log("hook putUsers 异常: " + t);
        }
    }

    private void hookPutUsers(final XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!cfgEnabled) return;
                try {
                    Object arg0 = param.args[0];
                    if (!(arg0 instanceof ArrayList)) return;
                    boolean fromCache = param.args.length > 1 && Boolean.TRUE.equals(param.args[1]);
                    ArrayList<?> list = (ArrayList<?>) arg0;
                    for (int i = 0; i < list.size(); i++) {
                        dumpUser(list.get(i), fromCache);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        // 多账号共用同一方法, class 级 hook 覆盖所有 MessagesController 实例
        XposedHelpers.findAndHookMethod("org.telegram.messenger.MessagesController",
                lpparam.classLoader, "putUsers", ArrayList.class, boolean.class, hook);
        log("hook putUsers 完成");
    }

    private void dumpUser(Object u, boolean fromCache) {
        if (u == null) return;
        try {
            long id = XposedHelpers.getLongField(u, "id");
            if (id == 0) return;
            if (bool(u, "bot") || bool(u, "deleted")) return;

            String username = str(u, "username");
            String phone = str(u, "phone");
            boolean contact = bool(u, "contact");
            boolean mutual = bool(u, "mutual_contact");

            boolean isWatch = !watchUser.isEmpty()
                    && (watchUser.equalsIgnoreCase(username) || watchUser.equals(String.valueOf(id)));
            boolean visible = phone != null && !phone.isEmpty();

            // 默认只记录"号码对我可见"的用户; watch 指定的用户无论可见与否都记
            if (!visible && !isWatch) return;

            String key = id + "|" + phone + "|" + contact + "|" + mutual + "|" + fromCache;
            if (!seen.add(key)) return;
            if (seen.size() > 4000) seen.clear();

            log((isWatch ? "★" : " ") + " id=" + id
                    + " user=" + (username == null ? "-" : "@" + username)
                    + " phone=" + (visible ? phone : "NULL(不可见)")
                    + " contact=" + contact + " mutual=" + mutual
                    + (fromCache ? " [cache]" : " [net]"));
        } catch (Throwable ignored) {
        }
    }

    private static String str(Object o, String field) {
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean bool(Object o, String field) {
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void loadConfig() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) {
                    cfgEnabled = !"0".equals(v);
                } else if ("watch".equals(k)) {
                    watchUser = v;
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        log("配置: enabled=" + cfgEnabled + " watch=" + watchUser);
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        synchronized (LOG_LOCK) {
            try {
                File f = new File(LOG_PRIVATE);
                File p = f.getParentFile();
                if (p != null && !p.exists()) p.mkdirs();
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            try {
                FileOutputStream fos2 = new FileOutputStream(LOG_SDCARD, true);
                fos2.write(line.getBytes("UTF-8"));
                fos2.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
