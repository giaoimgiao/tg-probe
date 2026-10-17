package com.giaoimgiao.tgprobe;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * TG Probe 配置界面
 * 配置: /sdcard/Download/tgprobe.conf
 * 日志: /sdcard/Download/tgprobe.log (若 TG 无外部存储写权限, 日志在 TG 私有目录)
 */
public class MainActivity extends Activity {

    private static final String CONF_PATH = "/sdcard/Download/tgprobe.conf";
    private static final String LOG_PATH = "/sdcard/Download/tgprobe.log";

    private EditText etWatch;
    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadConfig();
        refreshLog();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("TG Probe  手机号可见性观测");
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("观测点: MessagesController.putUsers\n"
                + "日志里的 phone= 值即服务端下发的原始值\n"
                + "  phone=NULL(不可见) → 未下发\n"
                + "  phone=+86...      → 已下发(对我可见)\n"
                + "留空 watch = 只记录号码可见的用户\n"
                + "填 username / id = 该用户无论可见与否都记录(★)");
        tip.setTextSize(12);
        tip.setPadding(0, 12, 0, 18);
        root.addView(tip);

        TextView lbl = new TextView(this);
        lbl.setText("关注用户 (username 或 id, 可留空)");
        lbl.setTextSize(13);
        root.addView(lbl);

        etWatch = new EditText(this);
        etWatch.setSingleLine(true);
        etWatch.setHint("例如 giaoimgaio");
        root.addView(etWatch);

        Button save = new Button(this);
        save.setText("保存配置");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this,
                        saveConfig() ? "已保存, 重启 Telegram 生效" : "保存失败(检查存储权限)",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("刷新日志");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
        row.addView(refresh);

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    new File(LOG_PATH).delete();
                    tvLog.setText("(已清空)");
                } catch (Throwable ignored) {
                }
            }
        });
        row.addView(clear);
        root.addView(row);

        TextView lbl2 = new TextView(this);
        lbl2.setText("日志尾部");
        lbl2.setTextSize(13);
        lbl2.setPadding(0, 16, 0, 6);
        root.addView(lbl2);

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        root.addView(tvLog);
        return sv;
    }

    private void loadConfig() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("watch=")) {
                    etWatch.setText(line.substring(6).trim());
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
    }

    private boolean saveConfig() {
        try {
            String v = etWatch.getText().toString().trim();
            String sb = "# TG Probe config\n"
                    + "# enabled=0 关闭观测\n"
                    + "# watch=username 或 id, 留空=只记录号码可见的用户\n"
                    + "enabled=1\n"
                    + "watch=" + v + "\n";
            File f = new File(CONF_PATH);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(sb.getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshLog() {
        try {
            File f = new File(LOG_PATH);
            if (!f.exists()) {
                tvLog.setText("(暂无日志)\n若 Telegram 写不了外部存储, 日志在:\n/data/data/org.telegram.messenger.web/files/tgprobe.log");
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                all.append(line).append('\n');
            }
            br.close();
            int len = all.length();
            int start = Math.max(0, len - 6000);
            tvLog.setText(all.substring(start));
        } catch (Throwable t) {
            tvLog.setText("读取失败: " + t);
        }
    }
}