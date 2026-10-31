package com.giaoimgiao.tgprobe;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置模型 + 序列化（模块 App 与 Hook 侧共用，不含任何 Xposed 依赖）
 *
 * 任务一行格式: HH:mm|群用户名|账号索引|内容
 *   09:00|deepblow_group|0,1|🎁 每日签到
 */
public class Config {

    public static final String PKG = "com.giaoimgiao.tgprobe";
    public static final String PREFS = "tgprobe";
    public static final String CONF_PRIVATE =
            "/data/data/org.telegram.messenger.web/files/tgprobe.conf";
    public static final String CONF_SDCARD = "/sdcard/Download/tgprobe.conf";

    public boolean enabled = true;
    public boolean popup = true;
    public boolean signEnabled = true;
    public String watch = "";
    public List<Job> jobs = new ArrayList<>();

    public static class Job {
        public String time = "";
        public String chat = "";
        public String accounts = "0";
        public String text = "";

        public String id() {
            return time + "@" + chat;
        }

        public List<Integer> accountList() {
            List<Integer> out = new ArrayList<>();
            if (accounts != null) {
                for (String s : accounts.split(",")) {
                    try {
                        int v = Integer.parseInt(s.trim());
                        if (v >= 0 && !out.contains(v)) out.add(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (out.isEmpty()) out.add(0);
            return out;
        }

        public String accountsLabel() {
            StringBuilder sb = new StringBuilder();
            for (Integer i : accountList()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(i);
            }
            return sb.toString();
        }
    }

    /** 多任务 → 多行文本 */
    public static String join(List<Job> jobs) {
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(j.time).append('|').append(j.chat).append('|')
                    .append(j.accountsLabel()).append('|').append(j.text);
        }
        return sb.toString();
    }

    public static Job parseJob(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("job=")) s = s.substring(4).trim();
        if (s.isEmpty()) return null;
        String[] p = s.split("\\|", 4);
        if (p.length < 4) return null;

        Job j = new Job();
        j.time = p[0].trim();
        j.chat = p[1].trim();
        if (j.chat.startsWith("@")) j.chat = j.chat.substring(1);
        j.accounts = p[2].trim();
        j.text = p[3];
        if (j.time.isEmpty() || j.chat.isEmpty()) return null;
        return j;
    }

    public static List<Job> parseJobs(String raw) {
        List<Job> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            Job j = parseJob(s);
            if (j != null) out.add(j);
        }
        return out;
    }
}