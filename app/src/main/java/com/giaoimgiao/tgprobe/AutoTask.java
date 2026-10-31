package com.giaoimgiao.tgprobe;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedHelpers;

/**
 * 定时自动发言引擎
 *
 * 配置行格式:
 *   task=<账号索引>|<HH:mm>|<目标>|<文本>
 *   例: task=0|09:30|@mychannel|🎁 每日签到
 *       task=1|20:00|-1001234567890|晚上好
 *
 * 目标支持: @username / 纯数字 dialogId
 * 执行: 每 20 秒 tick, 到达设定时刻且当天未执行 → 通过 SendMessagesHelper 发送
 * 状态: files/tgprobe_tasks.state, 每行 "acct|HH:mm|peer|hash|yyyy-MM-dd"
 */
public class AutoTask {

    private static final String CONF_PRIVATE = "/data/data/org.telegram.messenger.web/files/tgprobe.conf";
    private static final String CONF_SDCARD = "/sdcard/Download/tgprobe.conf";
    private static final String STATE = "/data/data/org.telegram.messenger.web/files/tgprobe_tasks.state";

    private static volatile boolean started = false;
    private static final List<String> executed = new ArrayList<>();

    public static void start(final ClassLoader cl) {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(15000);
                } catch (Throwable ignored) {
                }
                Main.log("[sched] 定时线程已启动");
                while (true) {
                    try {
                        tick(cl);
                    } catch (Throwable t) {
                        Main.log("[sched] tick 异常: " + t);
                    }
                    try {
                        Thread.sleep(20000);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "tgprobe-scheduler");
        t.setDaemon(true);
        t.start();
    }

    private static void tick(ClassLoader cl) {
        List<String> tasks = loadTasks();
        if (tasks.isEmpty()) return;

        Calendar now = Calendar.getInstance();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());

        for (int i = 0; i < tasks.size(); i++) {
            String raw = tasks.get(i);
            String[] p = raw.split("\\|", 4);
            if (p.length < 4) {
                Main.log("[sched] 任务格式错误: " + raw);
                continue;
            }
            int acct = parseInt(p[0].trim(), 0);
            String hm = p[1].trim();
            String peer = p[2].trim();
            String text = p[3];

            String[] hp = hm.split(":");
            if (hp.length < 2) {
                Main.log("[sched] 时间格式错误: " + hm);
                continue;
            }
            int hh = parseInt(hp[0], -1);
            int mm = parseInt(hp[1], -1);
            if (hh < 0 || mm < 0) continue;

            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, hh);
            target.set(Calendar.MINUTE, mm);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);

            // 还没到点
            if (now.getTimeInMillis() < target.getTimeInMillis()) continue;

            String key = acct + "|" + hm + "|" + peer + "|" + text.hashCode() + "|" + today;
            if (isDone(key)) continue;

            Main.log("[sched] 触发 acct=" + acct + " time=" + hm + " peer=" + peer + " text=" + text);
            boolean ok = false;
            try {
                ok = doSend(cl, acct, peer, text);
            } catch (Throwable t) {
                Main.log("[sched] 发送异常: " + t);
            }
            markDone(key, ok);
        }
    }

    private static boolean doSend(final ClassLoader cl, final int acct,
                                  final String peerStr, final String text) {
        final boolean[] result = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = sendInner(cl, acct, peerStr, text);
                } catch (Throwable t) {
                    Main.log("[sched] sendInner 失败: " + t);
                }
                latch.countDown();
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        return result[0];
    }

    private static boolean sendInner(ClassLoader cl, int acct, String peerStr, String text) {
        Class<?> mcCls = XposedHelpers.findClass("org.telegram.messenger.MessagesController", cl);
        Object mc = XposedHelpers.callStaticMethod(mcCls, "getInstance", new Class[]{int.class}, acct);
        if (mc == null) {
            Main.log("[sched] MessagesController 为空 (acct=" + acct + ")");
            return false;
        }

        long dialogId = resolveDialogId(mc, peerStr);
        if (dialogId == 0) {
            Main.log("[sched] 找不到目标 " + peerStr + " —— 请先在 TG 中打开该频道一次再试");
            return false;
        }

        // access_hash 可用性诊断(真正传入的是 dialogId)
        Object inputPeer = null;
        try {
            inputPeer = XposedHelpers.callMethod(mc, "getInputPeer", new Class[]{long.class}, dialogId);
        } catch (Throwable ignored) {
        }
        if (inputPeer == null
                || inputPeer.getClass().getSimpleName().toLowerCase(Locale.US).contains("empty")) {
            Main.log("[sched] 缺少 access_hash " + peerStr + " (dialogId=" + dialogId
                    + ") —— 请先打开该频道一次");
            return false;
        }

        Class<?> acInstCls = XposedHelpers.findClass("org.telegram.messenger.AccountInstance", cl);
        Object acInst = XposedHelpers.callStaticMethod(acInstCls, "getInstance", new Class[]{int.class}, acct);
        Object smh = XposedHelpers.callMethod(acInst, "getSendMessagesHelper");

        Class<?> smpCls = XposedHelpers.findClass(
                "org.telegram.messenger.SendMessagesHelper$SendMessageParams", cl);
        Object smp = XposedHelpers.newInstance(smpCls);
        // TG 10.13: SendMessageParams.peer 是 long dialogId(不是 InputPeer)
        XposedHelpers.setLongField(smp, "peer", dialogId);
        XposedHelpers.setObjectField(smp, "message", text);
        XposedHelpers.setBooleanField(smp, "notify", true);
        XposedHelpers.setIntField(smp, "scheduleDate", 0);
        // params 是 HashMap,内部直接使用,给空表避免 NPE
        XposedHelpers.setObjectField(smp, "params", new HashMap<String, String>());

        Method target = null;
        Method[] ms = smh.getClass().getMethods();
        for (int i = 0; i < ms.length; i++) {
            Class<?>[] ps = ms[i].getParameterTypes();
            if (ps.length == 1 && ps[0].equals(smpCls)) {
                target = ms[i];
                break;
            }
        }
        if (target == null) {
            Main.log("[sched] 未找到 sendMessage(SendMessageParams) 方法");
            return false;
        }
        try {
            target.invoke(smh, smp);
        } catch (Throwable t) {
            Main.log("[sched] invoke 失败: " + t);
            return false;
        }
        Main.log("[sched] ✔ 已发送 acct=" + acct + " peer=" + peerStr + " dialogId=" + dialogId + " text=" + text);
        return true;
    }

    // ==================== peer 解析 ====================

    private static long resolveDialogId(Object mc, String target) {
        String s = target.trim();
        if (s.startsWith("@")) s = s.substring(1);

        if (s.matches("-?\\d{5,}")) {
            try {
                return Long.parseLong(s);
            } catch (Throwable ignored) {
            }
        }

        // 频道/群: chats 在 10.13 是 ConcurrentHashMap
        long cid = findByUsername(getFieldIfExists(mc, "chats"), s);
        if (cid != 0) return -cid;
        // 用户: users 同样是 ConcurrentHashMap
        long uid = findByUsername(getFieldIfExists(mc, "users"), s);
        if (uid != 0) return uid;
        return 0;
    }

    /** 容器可能是 ConcurrentHashMap(10.13) 或 SparseArray(旧版),统一按 username 匹配 */
    private static long findByUsername(Object container, String username) {
        if (container == null) return 0;
        if (container instanceof java.util.Map) {
            for (Object o : ((java.util.Map<?, ?>) container).values()) {
                long r = matchUsername(o, username);
                if (r != 0) return r;
            }
            return 0;
        }
        if (container instanceof SparseArray) {
            SparseArray<?> arr = (SparseArray<?>) container;
            for (int i = 0; i < arr.size(); i++) {
                long r = matchUsername(arr.valueAt(i), username);
                if (r != 0) return r;
            }
        }
        return 0;
    }

    private static long matchUsername(Object obj, String username) {
        if (obj == null) return 0;
        Object un = getFieldIfExists(obj, "username");
        if (un != null && username.equalsIgnoreCase(String.valueOf(un))) {
            return longField(obj, "id");
        }
        return 0;
    }

    private static Object getFieldIfExists(Object obj, String field) {
        try {
            return XposedHelpers.getObjectField(obj, field);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long longField(Object obj, String field) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ==================== 配置 & 状态 ====================

    private static List<String> loadTasks() {
        List<String> out = new ArrayList<>();
        boolean enabled = true;
        String[] paths = {CONF_PRIVATE, CONF_SDCARD};
        for (int i = 0; i < paths.length; i++) {
            try {
                File f = new File(paths[i]);
                if (!f.exists()) continue;
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("task=")) {
                        out.add(line.substring(5).trim());
                    } else if (line.startsWith("enabled=")) {
                        enabled = !line.substring(8).trim().equals("0");
                    }
                }
                br.close();
            } catch (Throwable ignored) {
            }
        }
        if (!enabled) return new ArrayList<>();
        return out;
    }

    private static boolean isDone(String key) {
        synchronized (executed) {
            if (executed.contains(key)) return true;
        }
        try {
            File f = new File(STATE);
            if (!f.exists()) return false;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith(key)) {
                    br.close();
                    return true;
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void markDone(String key, boolean ok) {
        synchronized (executed) {
            if (executed.size() > 500) executed.clear();
            executed.add(key);
        }
        try {
            File f = new File(STATE);
            if (f.exists() && f.length() > 65536) {
                // 状态文件过大则重建
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((key + (ok ? "" : "|FAIL") + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}