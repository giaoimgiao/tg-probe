package com.giaoimgiao.tgprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.util.Date;
import java.util.Locale;

/**
 * TG Probe v2.0
 * 目标: org.telegram.messenger.web
 *
 * 1) ProfileActivity.onResume → 弹出面板展示当前用户号码可见性
 * 2) MessagesController.putUsers → 可选批量观测(默认关, log_bulk=1 打开)
 *
 * 数据来源: 服务端下发的 TLRPC.User.phone (空 = 未下发)
 * 日志: /data/data/org.telegram.messenger.web/files/tgprobe.log
 * 配置: 私有目录 tgprobe.conf 优先, /sdcard/Download/tgprobe.conf 作为补充
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TARGET = "org.telegram.messenger.web";
    private static final String LOG_PRIVATE = "/data/data/org.telegram.messenger.web/files/tgprobe.log";
    private static final String LOG_SDCARD = "/sdcard/Download/tgprobe.log";
    private static final String CONF_PRIVATE = "/data/data/org.telegram.messenger.web/files/tgprobe.conf";
    private static final String CONF_SDCARD = "/sdcard/Download/tgprobe.conf";

    private static volatile boolean cfgEnabled = true;
    private static volatile boolean cfgPopup = true;
    private static volatile boolean cfgLogBulk = false;
    private static volatile String watchUser = "";

    private static final Object LOG_LOCK = new Object();

    // 弹窗去重
    private static long lastShownId = 0;
    private static long lastShownTs = 0;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;

        loadConfig();
        log("========== TGProbe v2.0 注入 ==========");
        log("process=" + lpparam.processName + " enabled=" + cfgEnabled
                + " popup=" + cfgPopup + " log_bulk=" + cfgLogBulk + " watch=" + watchUser);

        if (cfgLogBulk || !watchUser.isEmpty()) {
            try {
                hookPutUsers(lpparam);
            } catch (Throwable t) {
                log("hook putUsers 异常: " + t);
            }
        }
        if (TARGET.equals(lpparam.processName)) {
            try {
                hookProfileActivity(lpparam);
            } catch (Throwable t) {
                log("hook ProfileActivity 异常: " + t);
            }
        }
    }

    // ==================== 用户资料页 → 面板 ====================

    private void hookProfileActivity(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("org.telegram.ui.ProfileActivity",
                lpparam.classLoader, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!cfgEnabled || !cfgPopup) return;
                        final Object fragment = param.thisObject;
                        try {
                            Object actObj = XposedHelpers.callMethod(fragment, "getParentActivity");
                            if (!(actObj instanceof Activity)) return;

                            Object full = XposedHelpers.callMethod(fragment, "getUserInfo");
                            if (full == null) return;
                            Object user = XposedHelpers.getObjectField(full, "user");
                            if (user == null) return;

                            final long id = XposedHelpers.getLongField(user, "id");
                            if (id == 0) return;

                            long now = System.currentTimeMillis();
                            if (id == lastShownId && now - lastShownTs < 20000) return;
                            lastShownId = id;
                            lastShownTs = now;

                            final String username = str(user, "username");
                            final String phone = str(user, "phone");
                            final boolean contact = bool(user, "contact");
                            final boolean mutual = bool(user, "mutual_contact");
                            final boolean bot = bool(user, "bot");
                            final boolean visible = phone != null && !phone.isEmpty();

                            log((visible ? "[公开] " : "[隐藏] ") + "id=" + id
                                    + " user=" + (username == null ? "-" : "@" + username)
                                    + " phone=" + (visible ? phone : "NULL")
                                    + " contact=" + contact + " mutual=" + mutual);

                            showPanel((Activity) actObj, id, username, phone, contact, mutual, bot);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        log("hook ProfileActivity 完成");
    }

    private void showPanel(final Activity act, final long id, final String username,
                           final String phone, final boolean contact,
                           final boolean mutual, final boolean bot) {
        final boolean visible = phone != null && !phone.isEmpty();

        final StringBuilder sb = new StringBuilder();
        sb.append("用户: ").append(username == null ? "(无用户名)" : "@" + username).append('\n');
        sb.append("ID: ").append(id).append('\n');
        sb.append("──────────────\n");
        if (bot) {
            sb.append("机器人账号: 无号码\n");
        } else if (visible) {
            sb.append("号码: 【公开可见】\n\n");
            sb.append("手机号\n").append(phone).append('\n');
        } else {
            sb.append("号码: 【未公开】\n\n");
            sb.append("服务端未下发 phone 字段\n");
            sb.append("(对方设为\"我的联系人\"或\"不允许任何人\")\n");
        }
        sb.append("\n──────────────\n");
        sb.append("在联系人: ").append(contact ? "是" : "否").append('\n');
        sb.append("互加好友: ").append(mutual ? "是" : "否").append('\n');
        sb.append("\n数据来源: User.phone (服务端下发值)");

        Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing()) return;
                    ScrollView sv = new ScrollView(act);
                    TextView tv = new TextView(act);
                    tv.setText(sb.toString());
                    tv.setTextIsSelectable(true);
                    tv.setTextSize(15);
                    tv.setPadding(48, 32, 48, 32);
                    sv.addView(tv);

                    new AlertDialog.Builder(act)
                            .setTitle(visible ? "TG Probe · 公开" : "TG Probe · 隐藏")
                            .setView(sv)
                            .setPositiveButton("关闭", null)
                            .show();
                } catch (Throwable t) {
                    log("dialog 失败: " + t);
                }
            }
        });
    }

    // ==================== 批量观测(可选) ====================

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
            boolean visible = phone != null && !phone.isEmpty();

            boolean isWatch = !watchUser.isEmpty()
                    && (watchUser.equalsIgnoreCase(username) || watchUser.equals(String.valueOf(id)));
            if (!isWatch && !visible) return;

            log((isWatch ? "★" : " ") + " id=" + id
                    + " user=" + (username == null ? "-" : "@" + username)
                    + " phone=" + (visible ? phone : "NULL(不可见)")
                    + " contact=" + contact + " mutual=" + mutual
                    + (fromCache ? " [cache]" : " [net]"));
        } catch (Throwable ignored) {
        }
    }

    // ==================== 工具 ====================

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
        String[] paths = {CONF_PRIVATE, CONF_SDCARD};
        for (String p : paths) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
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
                    } else if ("popup".equals(k)) {
                        cfgPopup = !"0".equals(v);
                    } else if ("log_bulk".equals(k)) {
                        cfgLogBulk = !"0".equals(v);
                    }
                }
                br.close();
            } catch (Throwable ignored) {
            }
        }
        log("配置: enabled=" + cfgEnabled + " popup=" + cfgPopup
                + " log_bulk=" + cfgLogBulk + " watch=" + watchUser);
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