package com.giaoimgiao.tgprobe;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

/**
 * 签到调度器（事件驱动，无轮询）
 *
 * 一、调度
 *   启动/打开 TG 时算一次"距下一个触发点还有多少毫秒"，用 postDelayed 精确投递。
 *   执行完再排下一次（明天）。全天最多"任务数"条延迟消息，无 while 循环、无 tick。
 *
 * 二、发送门禁（任一不满足 → 只记日志，不发送、不标记已完成）
 *   1. 账号已登录
 *   2. 系统网络在线
 *   3. 该账号的连接状态 == Connected
 *   4. 该账号能解析出这个群的 inputPeer（有 access_hash）
 *
 * 三、完成判定（关键：不把"已排队"当"已发送"）
 *   发送成功只是提交，消息进入"待确认"表；
 *   只有 hook 到 NotificationCenter.messageReceivedByServer（服务端回执）才写完成标记。
 *   超时未回执 → 视为失败 → 退避重试，保证不漏签。
 *
 * 四、重试
 *   退避序列 1/2/5/10/30/60 分钟，不会刷屏式重发。
 */
public class Scheduler {

    private static final String STATE =
            "/data/data/org.telegram.messenger.web/files/tgprobe_sign.state";

    private static final long[] BACKOFF = {
            60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L
    };
    /** 会话缓存还没加载好这类"一会儿就好"的情况用短退避 */
    private static final long[] SOFT_BACKOFF = {
            10_000L, 20_000L, 40_000L, 90_000L, 180_000L
    };
    private static final long CONFIRM_TIMEOUT = 120_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());

    /** 已投递的延迟任务，用于 reload 时撤销 */
    private static final List<Runnable> DISPATCHED = new ArrayList<>();
    /** 已提交、等服务端回执的消息 */
    private static final List<Waiting> WAITING = new ArrayList<>();
    /** job.id() → 重试次数 */
    private static final Map<String, Integer> RETRY = new HashMap<>();
    /** "acct#chat" → dialogId，避免每次都遍历 chats */
    private static final Map<String, Long> PEER_CACHE = new HashMap<>();

    private static ClassLoader cl;
    private static long lastReload = 0;
    private static long lastConnTry = 0;

    private static class Waiting {
        int acct;
        long dialogId;
        String text;
        String markKey;
        long at;
        Config.Job job;
    }

    public static void init(ClassLoader loader) {
        cl = loader;
        reload(true);
    }

    // ==================== 读取配置 ====================

    private static Config loadConfig() {
        Config c = new Config();
        boolean fromPrefs = false;
        try {
            XSharedPreferences p = new XSharedPreferences(Config.PKG, Config.PREFS);
            p.reload();
            if (p.getBoolean("configured", false)) {
                c.enabled = p.getBoolean("enabled", true);
                c.signEnabled = p.getBoolean("sign_enabled", true);
                c.popup = p.getBoolean("popup", true);
                c.watch = p.getString("watch", "");
                c.jobs = Config.parseJobs(p.getString("jobs", ""));
                fromPrefs = true;
            }
        } catch (Throwable t) {
            Main.log("[sched] 读取模块配置失败: " + t);
        }
        if (!fromPrefs) {
            String text = readAll(Config.CONF_PRIVATE);
            if (text == null) text = readAll(Config.CONF_SDCARD);
            if (text != null) {
                c.jobs = Config.parseJobs(text);
                if (text.contains("sign_enabled=0")) c.signEnabled = false;
                if (text.contains("enabled=0")) c.enabled = false;
            }
        }
        return c;
    }

    private static String readAll(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 调度（一次性投递，非轮询） ====================

    public static void reload(final boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() {
                @Override
                public void run() {
                    reload(true);
                }
            });
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastReload < 5000) return;
        lastReload = now;

        for (int i = 0; i < DISPATCHED.size(); i++) {
            H.removeCallbacks(DISPATCHED.get(i));
        }
        DISPATCHED.clear();

        Config c = loadConfig();
        if (!c.enabled || !c.signEnabled) {
            Main.log("[sched] 未启用签到");
            return;
        }
        if (c.jobs.isEmpty()) {
            Main.log("[sched] 没有任务");
            return;
        }
        Main.log("[sched] 载入 " + c.jobs.size() + " 个任务");
        for (int i = 0; i < c.jobs.size(); i++) {
            schedule(c.jobs.get(i));
        }
    }

    private static void schedule(final Config.Job job) {
        long delay = delayOf(job);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                DISPATCHED.remove(this);
                execute(job);
            }
        };
        DISPATCHED.add(r);
        H.postDelayed(r, Math.max(0, delay));
    }

    /** 距下一次可执行还有多少毫秒（"当天额度"语义，不是精确时刻） */
    private static long delayOf(Config.Job job) {
        long now = System.currentTimeMillis();

        // 今天还有没签的账号 → 看是否已过"最早允许时间"，过了就立刻发
        if (!todoAccounts(job).isEmpty()) {
            long notBefore = notBefore(job);
            if (now >= notBefore) return 0;
            return notBefore - now;
        }

        // 今天已完成 → 排到明天（明天零点，或明天的最早允许时间）
        Calendar t = Calendar.getInstance();
        t.add(Calendar.DAY_OF_MONTH, 1);
        t.set(Calendar.HOUR_OF_DAY, 0);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        int[] hm = parseHm(job.time);
        if (hm != null) {
            t.set(Calendar.HOUR_OF_DAY, hm[0]);
            t.set(Calendar.MINUTE, hm[1]);
        }
        return t.getTimeInMillis() - now;
    }

    /** 今天最早允许发送的时刻；未设置时间 = 0（不限制） */
    private static long notBefore(Config.Job job) {
        int[] hm = parseHm(job.time);
        if (hm == null) return 0;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, hm[0]);
        t.set(Calendar.MINUTE, hm[1]);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return t.getTimeInMillis();
    }

    /** 今天还没签的账号（自动枚举所有已登录账号，key 用 UID 保证不会因换槽位而重复） */
    private static List<Integer> todoAccounts(Config.Job job) {
        List<Integer> out = new ArrayList<>();
        String day = day();
        List<Acc> accs = activatedAccounts();
        for (int i = 0; i < accs.size(); i++) {
            Acc a = accs.get(i);
            if (!isDone(markKey(day, job, a.uid))) out.add(a.index);
        }
        return out;
    }

    private static class Acc {
        int index;
        long uid;
    }

    /** 自动枚举所有已登录账号（用 UserConfig.Instance 静态数组，不创建假实例） */
    private static List<Acc> activatedAccounts() {
        List<Acc> out = new ArrayList<>();
        try {
            Class<?> uc = XposedHelpers.findClass(
                    "org.telegram.messenger.UserConfig", cl);
            Object arr = XposedHelpers.getStaticObjectField(uc, "Instance");
            if (!(arr instanceof Object[])) return out;
            Object[] slots = (Object[]) arr;
            for (int i = 0; i < slots.length; i++) {
                Object inst = slots[i];
                if (inst == null) continue;
                Object act = XposedHelpers.callMethod(inst, "isClientActivated");
                if (!Boolean.TRUE.equals(act)) continue;
                Object u = XposedHelpers.callMethod(inst, "getCurrentUser");
                if (u == null) continue;
                long uid = XposedHelpers.getLongField(u, "id");
                if (uid == 0) continue;
                Acc a = new Acc();
                a.index = i;
                a.uid = uid;
                out.add(a);
            }
        } catch (Throwable t) {
            Main.log("[sched] 枚举账号失败: " + t);
        }
        return out;
    }

    // ==================== 执行 ====================

    private static void execute(final Config.Job job) {
        Config c = loadConfig();
        if (!c.enabled || !c.signEnabled) return;

        List<Integer> todo = todoAccounts(job);
        if (todo.isEmpty()) {
            schedule(job);
            return;
        }

        if (!networkOnline()) {
            Main.log("[sched] 网络未就绪，暂不签到（不会标记完成）");
            backoff(job, "网络未就绪");
            return;
        }

        boolean needRetry = false;
        boolean needSoftRetry = false;
        boolean sentAny = false;

        for (int i = 0; i < todo.size(); i++) {
            int acct = todo.get(i);
            long uid = uidOf(acct);

            if (!accountConnected(acct)) {
                Main.log("[sched] acct=" + acct + " uid=" + uid + " 连接未就绪，稍后重试");
                needRetry = true;
                continue;
            }

            long dialogId = resolveDialogId(acct, job.chat);
            if (dialogId == 0) {
                Main.log("[sched] acct=" + acct + " uid=" + uid + " 会话缓存里还没有 @" + job.chat
                        + "，稍等再试（全新账号请先打开这个群一次）");
                needSoftRetry = true;
                continue;
            }

            String key = markKey(day(), job, uid);
            Main.log("[sched] 发送 acct=" + acct + " uid=" + uid + " chat=@" + job.chat
                    + " dialogId=" + dialogId + " text=" + job.text);

            boolean ok = AutoTask.sendByDialog(cl, acct, dialogId, job.text);
            if (ok) {
                markDone(key);

                Waiting w = new Waiting();
                w.acct = acct;
                w.dialogId = dialogId;
                w.text = job.text;
                w.markKey = key;
                w.at = System.currentTimeMillis();
                w.job = job;
                synchronized (WAITING) {
                    WAITING.add(w);
                }
                sentAny = true;
            } else {
                needRetry = true;
            }
        }

        if (sentAny) {
            H.removeCallbacks(CONFIRM_CHECK);
            H.postDelayed(CONFIRM_CHECK, CONFIRM_TIMEOUT);
        }
        if (needRetry) {
            backoff(job, "部分账号未就绪");
        } else if (needSoftRetry) {
            backoff(job, "会话缓存未就绪", true);
        } else if (!sentAny) {
            schedule(job);
        }
    }

    private static long uidOf(int acct) {
        try {
            Class<?> uc = XposedHelpers.findClass(
                    "org.telegram.messenger.UserConfig", cl);
            Object arr = XposedHelpers.getStaticObjectField(uc, "Instance");
            if (arr instanceof Object[]) {
                Object[] slots = (Object[]) arr;
                if (acct >= 0 && acct < slots.length && slots[acct] != null) {
                    Object u = XposedHelpers.callMethod(slots[acct], "getCurrentUser");
                    if (u != null) return XposedHelpers.getLongField(u, "id");
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 退避重试，不做无脑重发 */
    private static void backoff(final Config.Job job, String reason) {
        backoff(job, reason, false);
    }

    /** soft=true 用于"会话缓存还没加载好"这类很快能好的情况 */
    private static void backoff(final Config.Job job, String reason, boolean soft) {
        Integer n = RETRY.get(job.id());
        int idx = n == null ? 0 : n;
        RETRY.put(job.id(), idx + 1);
        long[] table = soft ? SOFT_BACKOFF : BACKOFF;
        long delay = table[Math.min(idx, table.length - 1)];
        Main.log("[sched] " + reason + " → " + (delay / 1000) + " 秒后重试 (" + job.time + " @" + job.chat + ")");

        Runnable r = new Runnable() {
            @Override
            public void run() {
                DISPATCHED.remove(this);
                execute(job);
            }
        };
        DISPATCHED.add(r);
        H.postDelayed(r, delay);
    }

    // ==================== 送达确认 ====================

    public static void onMessageReceivedByServer(final int account, final Object[] args) {
        H.post(new Runnable() {
            @Override
            public void run() {
                handleDelivered(account, args);
            }
        });
    }

    private static void handleDelivered(int account, Object[] args) {
        if (args == null || args.length == 0) return;

        // TG 13.1.0 实测结构:
        //   args[0]=Integer randomId, args[1]=Integer msgId,
        //   args[2]=TLRPC$Message, args[3]=Long dialogId,
        //   args[4]=Long, args[5]=Integer, args[6]=Boolean
        long dlg = 0;
        String txt = null;
        if (args.length > 3 && args[3] instanceof Long) dlg = (Long) args[3];
        if (args.length > 2 && isTlMessage(args[2])) txt = strField(args[2], "message");

        // 兜底：结构变动时按类型找
        if (dlg == 0 || txt == null) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null || !isTlMessage(a)) continue;
                if (dlg == 0) dlg = peerDialogId(a);
                if (txt == null) txt = strField(a, "message");
            }
        }
        if (dlg == 0) return;

        synchronized (WAITING) {
            for (int k = WAITING.size() - 1; k >= 0; k--) {
                Waiting w = WAITING.get(k);
                if (w.acct != account) continue;
                if (w.dialogId != dlg) continue;
                if (txt != null && w.text != null && !w.text.equals(txt)) continue;
                WAITING.remove(k);
                Main.log("[sched] ✔ 服务端已收到 acct=" + account
                        + " chat=@" + w.job.chat + " dialogId=" + dlg);
                break;
            }
        }
    }

    /** 实例真实类型是 TLRPC$TL_message，必须按继承链判断，不能比类名字符串 */
    private static boolean isTlMessage(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        for (int i = 0; c != null && i < 6; i++) {
            if ("org.telegram.tgnet.TLRPC$Message".equals(c.getName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    /** 提交后长时间没收到回执：只告警，不清标记（TG 自己的发送队列会继续重试，避免我们重复发） */
    private static final Runnable CONFIRM_CHECK = new Runnable() {
        @Override
        public void run() {
            List<Waiting> expire = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (WAITING) {
                for (int i = WAITING.size() - 1; i >= 0; i--) {
                    Waiting w = WAITING.get(i);
                    if (now - w.at >= CONFIRM_TIMEOUT) {
                        WAITING.remove(i);
                        expire.add(w);
                    }
                }
            }
            for (int i = 0; i < expire.size(); i++) {
                Waiting w = expire.get(i);
                Main.log("[sched] ! 暂未收到回执 acct=" + w.acct
                        + " chat=@" + w.job.chat + "（已提交，TG 会自动重试，不重复发送）");
            }
        }
    };

    /** 收到 messageSendError：确认这次真的发失败了，清除今日标记让下轮重试 */
    public static void onMessageSendError(final int account, final Object[] args) {
        H.post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Waiting hit = null;
                synchronized (WAITING) {
                    for (int k = WAITING.size() - 1; k >= 0; k--) {
                        Waiting w = WAITING.get(k);
                        if (w.acct != account) continue;
                        if (now - w.at > 5 * 60_000L) continue;
                        WAITING.remove(k);
                        hit = w;
                        break;
                    }
                }
                if (hit == null) return;
                removeDone(hit.markKey);
                Main.log("[sched] ✗ 发送失败回执 acct=" + account + " chat=@" + hit.job.chat
                        + " → 已撤销今日标记，稍后重试");
                backoff(hit.job, "发送失败");
            }
        });
    }

    /** 网络恢复 → 立刻重新调度一次（有未完成任务才会真的执行） */
    public static void onConnectionChanged() {
        long now = System.currentTimeMillis();
        if (now - lastConnTry < 15000) return;
        lastConnTry = now;
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                reload(true);
            }
        }, 3000);
    }

    // ==================== 门禁：网络 / 账号 / 会话 ====================

    private static boolean networkOnline() {
        try {
            Class<?> al = XposedHelpers.findClass(
                    "org.telegram.messenger.ApplicationLoader", cl);
            Object v = XposedHelpers.callStaticMethod(al, "isNetworkOnline");
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            Main.log("[sched] 网络检测失败: " + t);
            return false;
        }
    }

    /** 用 UserConfig.Instance 静态数组判断，避免 getInstance(i) 给空槽造出假实例 */
    private static boolean accountActivated(int acct) {
        try {
            Class<?> uc = XposedHelpers.findClass(
                    "org.telegram.messenger.UserConfig", cl);
            Object arr = XposedHelpers.getStaticObjectField(uc, "Instance");
            if (!(arr instanceof Object[])) return false;
            Object[] slots = (Object[]) arr;
            if (acct < 0 || acct >= slots.length) return false;
            Object inst = slots[acct];
            if (inst == null) return false;
            Object v = XposedHelpers.callMethod(inst, "isClientActivated");
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean accountConnected(int acct) {
        try {
            Class<?> cmCls = XposedHelpers.findClass(
                    "org.telegram.tgnet.ConnectionsManager", cl);
            int connected = XposedHelpers.getStaticIntField(cmCls, "ConnectionStateConnected");
            Object inst = XposedHelpers.callStaticMethod(cmCls, "getInstance",
                    new Class[]{int.class}, acct);
            if (inst == null) return false;
            Object st = XposedHelpers.callMethod(inst, "getConnectionState");
            return st instanceof Integer && ((Integer) st) == connected;
        } catch (Throwable t) {
            Main.log("[sched] 连接状态检测失败: " + t);
            return false;
        }
    }

    private static long resolveDialogId(int acct, String username) {
        String cacheKey = acct + "#" + username.toLowerCase(Locale.US);
        Long cached = PEER_CACHE.get(cacheKey);
        if (cached != null) return cached;

        try {
            Class<?> mcCls = XposedHelpers.findClass(
                    "org.telegram.messenger.MessagesController", cl);
            Object mc = XposedHelpers.callStaticMethod(mcCls, "getInstance",
                    new Class[]{int.class}, acct);
            if (mc == null) return 0;
            Object chatsObj = XposedHelpers.getObjectField(mc, "chats");
            if (chatsObj instanceof Map) {
                for (Object o : ((Map<?, ?>) chatsObj).values()) {
                    if (o == null) continue;
                    Object un = strFieldObj(o, "username");
                    if (un == null) continue;
                    if (!username.equalsIgnoreCase(String.valueOf(un))) continue;
                    long id = longField(o, "id");
                    if (id != 0) {
                        PEER_CACHE.put(cacheKey, -id);
                        return -id;
                    }
                }
            }
        } catch (Throwable t) {
            Main.log("[sched] 解析会话失败: " + t);
        }
        return 0;
    }

    // ==================== 小工具 ====================

    private static int[] parseHm(String s) {
        if (s == null) return null;
        String[] p = s.trim().split(":");
        if (p.length < 2) return null;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String markKey(String day, Config.Job job, long uid) {
        return day + "#" + job.time + "@" + job.chat + "#" + uid;
    }

    private static long peerDialogId(Object message) {
        try {
            Object peer = XposedHelpers.getObjectField(message, "peer_id");
            if (peer == null) return 0;
            String n = peer.getClass().getSimpleName();
            if (n.contains("Channel")) return -longField(peer, "channel_id");
            if (n.contains("Chat")) return -longField(peer, "chat_id");
            if (n.contains("User")) return longField(peer, "user_id");
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object strFieldObj(Object o, String name) {
        try {
            return XposedHelpers.getObjectField(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String strField(Object o, String name) {
        Object v = strFieldObj(o, name);
        return v == null ? null : String.valueOf(v);
    }

    private static long longField(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    // ==================== 状态文件 ====================

    /** 今日完成表（内存缓存，避免每次调度都读文件） */
    private static final Map<String, Boolean> DONE = new HashMap<>();

    private static boolean isDone(String key) {
        synchronized (DONE) {
            Boolean v = DONE.get(key);
            if (v != null) return v;
        }
        boolean r = readDoneFromFile(key);
        synchronized (DONE) {
            if (DONE.size() > 400) DONE.clear();
            DONE.put(key, r);
        }
        return r;
    }

    private static boolean readDoneFromFile(String key) {
        try {
            File f = new File(STATE);
            if (!f.exists()) return false;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().equals(key)) {
                    br.close();
                    return true;
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void markDone(String key) {
        synchronized (DONE) {
            Boolean v = DONE.get(key);
            if (v != null && v) return;
            DONE.put(key, true);
        }
        try {
            File f = new File(STATE);
            if (f.exists() && f.length() > 8192) {
                String keepFrom = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));
                List<String> keep = new ArrayList<>();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    if (s.compareTo(keepFrom) >= 0) keep.add(s);
                }
                br.close();
                f.delete();
                FileOutputStream w = new FileOutputStream(f, false);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < keep.size(); i++) sb.append(keep.get(i)).append('\n');
                w.write(sb.toString().getBytes("UTF-8"));
                w.close();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((key + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 撤销某个标记（发送真失败时用） */
    private static void removeDone(String key) {
        synchronized (DONE) {
            DONE.put(key, false);
        }
        try {
            File f = new File(STATE);
            if (!f.exists()) return;
            List<String> keep = new ArrayList<>();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (!s.equals(key)) keep.add(s);
            }
            br.close();
            f.delete();
            FileOutputStream w = new FileOutputStream(f, false);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep.size(); i++) sb.append(keep.get(i)).append('\n');
            w.write(sb.toString().getBytes("UTF-8"));
            w.close();
        } catch (Throwable ignored) {
        }
    }
}