package com.giaoimgiao.tgprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面
 *
 * 配置写入 SharedPreferences("tgprobe")，Hook 侧通过 XSharedPreferences 读取。
 * 同时导出 /sdcard/Download/tgprobe.conf 便于查看和备份。
 */
public class MainActivity extends Activity {

    private final List<Config.Job> jobs = new ArrayList<>();

    private TextView tvJobs;
    private EditText etTime;
    private EditText etChat;
    private EditText etText;
    private CheckBox cbA0;
    private CheckBox cbA1;
    private CheckBox cbA2;
    private CheckBox cbPopup;
    private EditText etWatch;
    private TextView tvStatus;

    private int editing = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        load();
        renderJobs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) return;
        save(true);
    }

    // ==================== UI ====================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF808080);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        sv.addView(root);

        TextView header = new TextView(this);
        header.setText("TG 自动签到");
        header.setTextSize(22);
        header.setGravity(Gravity.CENTER);
        root.addView(header);

        TextView intro = new TextView(this);
        intro.setText("设置好时间、群和账号。以后每天只要手机开着 Telegram，"
                + "到点就会自动签到一次，成功后当天不再重复。\n"
                + "不需要进群，也不需要点任何按钮。");
        intro.setTextSize(13);
        intro.setPadding(0, dp(8), 0, dp(6));
        root.addView(intro);

        // ---------- 任务列表 ----------
        root.addView(title("我的签到任务"));
        root.addView(hint("点一下可以修改，长按可以删除"));

        tvJobs = new TextView(this);
        tvJobs.setTextSize(14);
        tvJobs.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvJobs.setBackgroundColor(0x11000000);
        tvJobs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseEdit();
            }
        });
        tvJobs.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                chooseDelete();
                return true;
            }
        });
        root.addView(tvJobs);

        // ---------- 编辑区 ----------
        root.addView(title("新建 / 修改任务"));

        root.addView(hint("第 1 步：几点发（24 小时制）"));
        etTime = new EditText(this);
        etTime.setHint("例如 09:00");
        etTime.setSingleLine(true);
        root.addView(etTime);

        root.addView(hint("第 2 步：发到哪个群（填群用户名，不带 @）"));
        etChat = new EditText(this);
        etChat.setHint("例如 deepblow_group");
        etChat.setSingleLine(true);
        root.addView(etChat);

        root.addView(hint("第 3 步：用哪些账号发（可多选）"));
        LinearLayout accRow = new LinearLayout(this);
        accRow.setOrientation(LinearLayout.HORIZONTAL);
        cbA0 = new CheckBox(this);
        cbA0.setText("账号 1");
        cbA1 = new CheckBox(this);
        cbA1.setText("账号 2");
        cbA2 = new CheckBox(this);
        cbA2.setText("账号 3");
        accRow.addView(cbA0);
        accRow.addView(cbA1);
        accRow.addView(cbA2);
        root.addView(accRow);
        root.addView(hint("「账号 1 / 2 / 3」按 Telegram 左上角切换账号的顺序排"));

        root.addView(hint("第 4 步：发什么内容"));
        etText = new EditText(this);
        etText.setHint("🎁 每日签到");
        root.addView(etText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnAdd = new Button(this);
        btnAdd.setText("添加 / 保存任务");
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyEditor();
            }
        });
        btnRow.addView(btnAdd);

        Button btnReset = new Button(this);
        btnReset.setText("清空输入");
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearEditor();
            }
        });
        btnRow.addView(btnReset);
        root.addView(btnRow);

        // ---------- 其它设置 ----------
        root.addView(title("其它"));

        cbPopup = new CheckBox(this);
        cbPopup.setText("打开用户资料页时显示手机号可见性面板");
        root.addView(cbPopup);

        root.addView(hint("下面这项一般留空。填了某个用户名，就会额外记录这个人的号码状态。"));
        etWatch = new EditText(this);
        etWatch.setHint("关注用户名（可留空）");
        etWatch.setSingleLine(true);
        root.addView(etWatch);

        Button btnSave = new Button(this);
        btnSave.setText("保存全部设置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        root.addView(btnSave);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(0xFF4C6B4C);
        tvStatus.setPadding(0, dp(10), 0, 0);
        root.addView(tvStatus);

        root.addView(hint("\n第一次使用某个账号时，需要用它打开一次这个群，"
                + "让 Telegram 记住这个群的凭证。之后就不用再进了。"));

        return sv;
    }

    // ==================== 任务列表 ====================

    private void renderJobs() {
        if (jobs.isEmpty()) {
            tvJobs.setText("（还没有任务，在下面添加一个）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jobs.size(); i++) {
            Config.Job j = jobs.get(i);
            sb.append(i + 1).append(".  ").append(j.time)
                    .append("  →  @").append(j.chat).append('\n');
            sb.append("     账号 ").append(j.accountsLabel())
                    .append(" · ").append(j.text);
            if (i < jobs.size() - 1) sb.append("\n\n");
        }
        tvJobs.setText(sb.toString());
    }

    private void chooseEdit() {
        if (jobs.isEmpty()) return;
        final String[] items = new String[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            Config.Job j = jobs.get(i);
            items[i] = j.time + "  @" + j.chat;
        }
        new AlertDialog.Builder(this)
                .setTitle("要修改哪一个？")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        loadToEditor(which);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseDelete() {
        if (jobs.isEmpty()) return;
        final String[] items = new String[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            Config.Job j = jobs.get(i);
            items[i] = j.time + "  @" + j.chat;
        }
        new AlertDialog.Builder(this)
                .setTitle("要删除哪一个？")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        jobs.remove(which);
                        if (editing == which) clearEditor();
                        renderJobs();
                        save(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadToEditor(int index) {
        Config.Job j = jobs.get(index);
        editing = index;
        etTime.setText(j.time);
        etChat.setText(j.chat);
        etText.setText(j.text);
        List<Integer> accts = j.accountList();
        cbA0.setChecked(accts.contains(0));
        cbA1.setChecked(accts.contains(1));
        cbA2.setChecked(accts.contains(2));
        tvStatus.setText("正在修改第 " + (index + 1) + " 个任务，改完点「添加 / 保存任务」");
    }

    private void clearEditor() {
        editing = -1;
        etTime.setText("");
        etChat.setText("");
        etText.setText("");
        cbA0.setChecked(true);
        cbA1.setChecked(false);
        cbA2.setChecked(false);
        tvStatus.setText("");
    }

    private void applyEditor() {
        String time = etTime.getText().toString().trim();
        String chat = etChat.getText().toString().trim();
        String text = etText.getText().toString();

        if (time.isEmpty() || chat.isEmpty()) {
            Toast.makeText(this, "请先填写时间和群用户名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!time.matches("\\d{1,2}:\\d{2}")) {
            Toast.makeText(this, "时间请按 24 小时制填写，例如 09:00", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] hm = time.split(":");
        int h = Integer.parseInt(hm[0]);
        int m = Integer.parseInt(hm[1]);
        if (h > 23 || m > 59) {
            Toast.makeText(this, "时间超出范围", Toast.LENGTH_SHORT).show();
            return;
        }
        time = (h < 10 ? "0" + h : "" + h) + ":" + (m < 10 ? "0" + m : "" + m);

        if (chat.startsWith("@")) chat = chat.substring(1);
        if (text.trim().isEmpty()) text = "🎁 每日签到";

        StringBuilder acc = new StringBuilder();
        if (cbA0.isChecked()) acc.append("0,");
        if (cbA1.isChecked()) acc.append("1,");
        if (cbA2.isChecked()) acc.append("2,");
        if (acc.length() == 0) {
            Toast.makeText(this, "请至少勾选一个账号", Toast.LENGTH_SHORT).show();
            return;
        }
        String accounts = acc.substring(0, acc.length() - 1);

        Config.Job j = new Config.Job();
        j.time = time;
        j.chat = chat;
        j.accounts = accounts;
        j.text = text;

        if (editing >= 0 && editing < jobs.size()) {
            jobs.set(editing, j);
        } else {
            jobs.add(j);
        }
        editing = -1;
        renderJobs();
        clearEditor();
        save(false);
    }

    // ==================== 读写配置 ====================

    private void load() {
        SharedPreferences sp = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        cbPopup.setChecked(sp.getBoolean("popup", true));
        etWatch.setText(sp.getString("watch", ""));

        if (sp.getBoolean("configured", false)) {
            jobs.clear();
            jobs.addAll(Config.parseJobs(sp.getString("jobs", "")));
            clearEditor();
            return;
        }
        // 首次使用：尝试导入旧的配置文件
        try {
            File f = new File(Config.CONF_SDCARD);
            if (f.exists()) {
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                br.close();
                jobs.clear();
                jobs.addAll(Config.parseJobs(sb.toString()));
            }
        } catch (Throwable ignored) {
        }
        clearEditor();
    }

    private void save(boolean silent) {
        SharedPreferences sp = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        sp.edit()
                .putBoolean("configured", true)
                .putBoolean("enabled", true)
                .putBoolean("sign_enabled", true)
                .putBoolean("popup", cbPopup.isChecked())
                .putString("watch", etWatch.getText().toString().trim())
                .putString("jobs", Config.join(jobs))
                .apply();

        String exported = writeBackup();
        if (!silent) {
            Toast.makeText(this, exported == null
                            ? "设置已保存（备份文件没权限写，但不影响使用）"
                            : "设置已保存",
                    Toast.LENGTH_SHORT).show();
            tvStatus.setText("已保存。" + (exported == null ? "" : "备份在 " + exported));
        }
    }

    /** 导出可读的配置文件，方便备份/查看 */
    private String writeBackup() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# TG 自动签到 配置备份\n");
            sb.append("# 注意：改这个文件不会生效，请在 App 界面里修改\n\n");
            sb.append("enabled=1\n");
            sb.append("sign_enabled=1\n");
            sb.append("popup=").append(cbPopup.isChecked() ? 1 : 0).append('\n');
            sb.append("watch=").append(etWatch.getText().toString().trim()).append('\n');
            sb.append('\n');
            for (int i = 0; i < jobs.size(); i++) {
                Config.Job j = jobs.get(i);
                sb.append("job=").append(j.time).append('|').append(j.chat)
                        .append('|').append(j.accountsLabel())
                        .append('|').append(j.text).append('\n');
            }
            File f = new File(Config.CONF_SDCARD);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }
}