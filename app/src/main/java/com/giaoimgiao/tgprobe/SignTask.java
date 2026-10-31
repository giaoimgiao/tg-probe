package com.giaoimgiao.tgprobe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * 无感签到
 *
 * 每天第一次打开目标群时, 后台静默发送签到消息:
 *   - 不弹窗、不确认、不 Toast
 *   - 对 sign_accounts 里列出的账号各发一次
 *   - 每个群每天只发一次(状态写 files/tgprobe_sign.state)
 *
 * 为什么挂在"打开群"这一刻:
 *   dialogId 直接来自 ChatActivity, 天然带当前账号的 access_hash,
 *   不存在解析失败/发错会话的问题。
 */
public class SignTask {

    private static final String CONF_PRIVATE = "/data/data/org.telegram.messenger.web/files/tgprobe.conf";
    private static final String CONF_SDCARD = "/sdcard/Download/tgprobe.conf";
    private static final String STATE = "/data/data/org.telegram.messenger.web/files/tgprobe_sign.state";

    public static void onChatResume(Object activity, ClassLoader cl) {
        try {
            long dialogId = XposedHelpers.getLongField(activity, "dialog_id");
            if (dialogId == 0) return;

            Object chat = XposedHelpers.getObjectField(activity, "currentChat");
            if (chat == null) return;

            Object un = XposedHelpers.getObjectField(chat, "username");
            String chatName = un == null ? null : String.valueOf(un);
            long chatId = XposedHelpers.getLongField(chat, "id");

            Config c = loadConfig();
            if (!c.enabled || !c.signEnabled) return;
            if (!c.signChat.isEmpty()) {
                if (chatName == null || !c.signChat.equalsIgnoreCase(chatName)) return;
            }

            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String key = day + "#" + chatId;
            if (isDone(key)) return;

            // 先记状态, 防止 onResume 多次触发导致重复发送
            markDone(key);

            Main.log("[sign] 自动签到 chat=" + chatName + " dialogId=" + dialogId
                    + " 账号=" + c.accounts + " 文本=" + c.signText);

            for (int i = 0; i < c.accounts.size(); i++) {
                AutoTask.sendByDialog(cl, c.accounts.get(i), dialogId, c.signText);
            }
        } catch (Throwable t) {
            Main.log("[sign] 异常: " + t);
        }
    }

    // ==================== 配置 ====================

    static class Config {
        boolean enabled = true;
        boolean signEnabled = true;
        String signChat = "";
        String signText = "🎁 每日签到";
        List<Integer> accounts = new ArrayList<>();
    }

    private static Config loadConfig() {
        Config c = new Config();
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
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ("enabled".equals(k)) {
                        c.enabled = !"0".equals(v);
                    } else if ("sign_enabled".equals(k)) {
                        c.signEnabled = !"0".equals(v);
                    } else if ("sign_chat".equals(k)) {
                        c.signChat = v;
                    } else if ("sign_text".equals(k)) {
                        if (!v.isEmpty()) c.signText = v;
                    } else if ("sign_accounts".equals(k)) {
                        c.accounts.clear();
                        for (String s : v.split(",")) {
                            try {
                                c.accounts.add(Integer.parseInt(s.trim()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                br.close();
            } catch (Throwable ignored) {
            }
        }
        if (c.accounts.isEmpty()) c.accounts.add(0);
        return c;
    }

    private static boolean isDone(String key) {
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

    private static void markDone(String key) {
        try {
            File f = new File(STATE);
            if (f.exists() && f.length() > 65536) f.delete();
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((key + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}