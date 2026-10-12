package com.shogun.amp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String DEFAULT_IP_A = "192.168.1.100";
    private static final String DEFAULT_IP_B = "192.168.1.101";

    // ---------------- 配色（深色广播机风格） ----------------
    private static final int COL_BG      = 0xFF0B0F16;
    private static final int COL_CARD    = 0xFF141C29;
    private static final int COL_STROKE   = 0xFF26344A;
    private static final int COL_FIELD   = 0xFF0B1220;
    private static final int COL_INK     = 0xFFE7EDF6;
    private static final int COL_DIM     = 0xFF8595AB;
    private static final int COL_ACCENT  = 0xFF38BDF8;
    private static final int COL_REC     = 0xFFDC2626;
    private static final int COL_PLAY    = 0xFF16A34A;
    private static final int COL_STOPBTN = 0xFF334155;
    private static final int COL_AMBER   = 0xFFD97706;
    private static final int COL_SCREEN  = 0xFF05080E;

    private AmpChannel ampA, ampB;
    private ScheduledExecutorService poller;

    private EditText etIpA, etIpB, etPort, etCmd;
    private Button btnScan;
    private TextView tvScanStatus, tvFound, tvLog;
    private ProgressBar scanProgress;
    private ScrollView logScroll;
    private Spinner spTarget, spFps, spMode;
    private Switch swAuto;

    private UnitViews unitA, unitB;
    private Scanner scanner;
    private boolean scanning;
    private volatile boolean autoPoll = true;

    private final Handler tick = new Handler(Looper.getMainLooper());

    private static class UnitViews {
        String ch;
        AmpChannel amp;
        LinearLayout card;
        GradientDrawable cardDraw;
        TextView conn;
        TextView pill;
        GradientDrawable pillDraw;
        TextView tc;
        TextView tcLabel;
        TextView tvFps;
        TimecodeClock clock = new TimecodeClock();
        String lastCode;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF070A10);
        ampA = new AmpChannel("A", this::log);
        ampB = new AmpChannel("B", this::log);

        buildUi();
        applyState(unitA);
        applyState(unitB);

        tick.postDelayed(new Runnable() {
            @Override public void run() {
                renderTc(unitA);
                renderTc(unitB);
                tick.postDelayed(this, 33);
            }
        }, 100);

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "amp-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(() -> {
            if (!autoPoll) return;
            if (ampA.isConnected()) ampA.submit(() -> doQuery(unitA, true));
            if (ampB.isConnected()) ampB.submit(() -> doQuery(unitB, true));
        }, 2000, 2000, TimeUnit.MILLISECONDS);

        log("默认: A=" + DEFAULT_IP_A + "  B=" + DEFAULT_IP_B + "  端口 " + AmpChannel.DEFAULT_PORT, true);
        log("协议: CRAT 开通道 → REAQ 查询 → CMDS+VTR码(2000停/2001放/2002录) → 1001 ACK", true);
        log("AMP 不提供机内时间码读取；屏幕内时间码为本地 REC RUN 计时或手机 TOD 时钟", true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tick.removeCallbacksAndMessages(null);
        ampA.shutdown();
        ampB.shutdown();
        if (poller != null) poller.shutdownNow();
        if (scanner != null) scanner.cancel();
    }

    // ================= 基础控件 =================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout vl() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(matchWrap());
        return l;
    }

    private LinearLayout hl() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutParams(matchWrap());
        return l;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightLp(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private void margins(View v, int l, int t, int r, int b) {
        Object lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).setMargins(dp(l), dp(t), dp(r), dp(b));
        }
    }

    private TextView tv(String text, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (style >= 0) t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable round(int bgColor, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(radiusDp));
        g.setColor(bgColor);
        return g;
    }

    private Button mkBtn(String text, int bgColor, int inkColor, float sp, int heightDp, boolean bold) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(inkColor);
        b.setTextSize(sp);
        if (bold) b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(heightDp));
        b.setMinimumHeight(dp(heightDp));
        b.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable g = round(bgColor, 10);
        b.setBackground(g);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp)));
        return b;
    }

    private Button bigBtn(String text, int bgColor, int inkColor) {
        return mkBtn(text, bgColor, inkColor, 15, 52, true);
    }

    private Button outlineBtn(String text) {
        Button b = mkBtn(text, COL_FIELD, COL_INK, 12, 42, false);
        ((GradientDrawable) b.getBackground()).setStroke(dp(1), COL_STROKE);
        return b;
    }

    private Button textBtn(String text, int color) {
        Button b = mkBtn(text, Color.TRANSPARENT, color, 12, 40, false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private EditText darkField(String val, String hint) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setHint(hint);
        e.setHintTextColor(0xFF54627A);
        e.setTextColor(COL_INK);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable g = round(COL_FIELD, 8);
        g.setStroke(dp(1), COL_STROKE);
        e.setBackground(g);
        e.setLayoutParams(matchWrap());
        return e;
    }

    private Spinner mkSpinner(String[] items, int selected) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(COL_INK);
                t.setTextSize(12);
                return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(selected);
        sp.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable g = round(COL_FIELD, 8);
        g.setStroke(dp(1), COL_STROKE);
        sp.setBackground(g);
        return sp;
    }

    private LinearLayout cardShell(String title) {
        LinearLayout box = vl();
        GradientDrawable g = round(COL_CARD, 14);
        g.setStroke(dp(1), COL_STROKE);
        box.setBackground(g);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setElevation(dp(2));
        LinearLayout.LayoutParams clp = matchWrap();
        clp.bottomMargin = dp(10);
        box.setLayoutParams(clp);

        TextView t = tv(title, 13, COL_ACCENT, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(2), 0, dp(2), dp(8));
        box.addView(t);
        return box;
    }

    private LinearLayout labeled(String label, View field, float weight) {
        LinearLayout wrap = vl();
        wrap.setLayoutParams(weightLp(weight));
        TextView t = tv(label, 10, COL_DIM, Typeface.NORMAL);
        t.setPadding(dp(2), 0, 0, dp(3));
        wrap.addView(t);
        wrap.addView(field);
        return wrap;
    }

    // ================= UI 组装 =================

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        outer.setBackgroundColor(COL_BG);
        LinearLayout root = vl();
        root.setPadding(dp(12), dp(12), dp(12), dp(20));
        outer.addView(root);

        // ---- 标题 ----
        TextView title = tv("SHOGUN STUDIO", 19, COL_INK, Typeface.BOLD);
        title.setLetterSpacing(0.18f);
        root.addView(title);
        TextView sub = tv("AMP 远程控制台  ·  TCP 3811  ·  双录制单元", 11, COL_ACCENT, Typeface.NORMAL);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        root.addView(buildNetworkCard());
        unitA = buildUnitCard("A", ampA);
        unitB = buildUnitCard("B", ampB);
        root.addView(unitA.card);
        root.addView(unitB.card);
        root.addView(buildGlobalCard());
        root.addView(buildConsoleCard());
        root.addView(buildLogCard());

        setContentView(outer);
    }

    private LinearLayout buildNetworkCard() {
        LinearLayout c = cardShell("网络连接");

        LinearLayout row1 = hl();
        etIpA = darkField(DEFAULT_IP_A, null);
        etIpB = darkField(DEFAULT_IP_B, null);
        etPort = darkField(String.valueOf(AmpChannel.DEFAULT_PORT), null);
        row1.addView(labeled("单元 A IP", etIpA, 1f));
        row1.addView(labeled("单元 B IP", etIpB, 1f));
        row1.addView(labeled("端口", etPort, 0.7f));
        c.addView(row1);

        LinearLayout row2 = hl();
        row2.setGravity(Gravity.CENTER_VERTICAL);
        btnScan = bigBtn("🔍  全自动扫描", COL_ACCENT, 0xFF062033);
        btnScan.setLayoutParams(weightLp(1.5f));
        row2.addView(btnScan);
        Button allConn = outlineBtn("全部连接");
        allConn.setTextColor(COL_ACCENT);
        allConn.setLayoutParams(weightLp(1f));
        allConn.setOnClickListener(v -> connectAll());
        row2.addView(allConn);
        Button allDisc = outlineBtn("全部断开");
        allDisc.setLayoutParams(weightLp(1f));
        allDisc.setOnClickListener(v -> disconnectAll());
        row2.addView(allDisc);
        c.addView(row2);

        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgress(0);
        scanProgress.setVisibility(View.GONE);
        scanProgress.getProgressDrawable().setColorFilter(COL_ACCENT, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams plp = matchWrap();
        plp.topMargin = dp(6);
        scanProgress.setLayoutParams(plp);
        c.addView(scanProgress);

        tvScanStatus = tv("", 12, COL_ACCENT, Typeface.NORMAL);
        tvScanStatus.setPadding(dp(2), dp(6), dp(2), 0);
        c.addView(tvScanStatus);
        tvFound = tv("", 12, COL_PLAY, Typeface.BOLD);
        tvFound.setPadding(dp(2), dp(4), dp(2), dp(2));
        c.addView(tvFound);

        btnScan.setOnClickListener(v -> onScan());
        return c;
    }

    private UnitViews buildUnitCard(String chName, AmpChannel amp) {
        UnitViews u = new UnitViews();
        u.ch = chName;
        u.amp = amp;

        LinearLayout card = vl();
        GradientDrawable cardDraw = round(COL_CARD, 14);
        cardDraw.setStroke(dp(1), COL_STROKE);
        card.setBackground(cardDraw);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams clp = matchWrap();
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        // 头部
        LinearLayout head = hl();
        TextView t = tv("录制单元 " + chName, 16, COL_INK, Typeface.BOLD);
        head.addView(t);
        u.conn = tv("未连接", 12, COL_DIM, Typeface.NORMAL);
        u.conn.setGravity(Gravity.RIGHT);
        u.conn.setLayoutParams(weightLp(1f));
        head.addView(u.conn);
        card.addView(head);

        // 时间码屏
        LinearLayout screen = vl();
        GradientDrawable sd = round(COL_SCREEN, 10);
        sd.setStroke(dp(1), 0xFF1C2738);
        screen.setBackground(sd);
        screen.setPadding(dp(12), dp(8), dp(12), dp(10));
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(9);
        screen.setLayoutParams(slp);

        LinearLayout scrHead = hl();
        u.tcLabel = tv("REC RUN · 本地计时", 10, COL_DIM, Typeface.NORMAL);
        u.tcLabel.setLayoutParams(weightLp(1f));
        scrHead.addView(u.tcLabel);
        u.tvFps = tv("25 fps", 10, COL_DIM, Typeface.NORMAL);
        scrHead.addView(u.tvFps);
        screen.addView(scrHead);

        LinearLayout scrBody = hl();
        scrBody.setGravity(Gravity.CENTER_VERTICAL);
        u.tc = tv("--:--:--:--", 27, 0xFF4B5A6E, Typeface.BOLD);
        u.tc.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        u.tc.setLetterSpacing(0.05f);
        u.tc.setLayoutParams(weightLp(1f));
        scrBody.addView(u.tc);

        u.pill = new TextView(this);
        u.pill.setText("离线");
        u.pill.setTextSize(12);
        u.pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        u.pill.setTextColor(Color.WHITE);
        u.pill.setGravity(Gravity.CENTER);
        u.pill.setPadding(dp(12), dp(5), dp(12), dp(6));
        u.pillDraw = round(COL_STOPBTN, 30);
        u.pill.setBackground(u.pillDraw);
        scrBody.addView(u.pill);
        screen.addView(scrBody);
        card.addView(screen);

        // 主传输按钮
        LinearLayout r1 = hl();
        LinearLayout.LayoutParams r1lp = matchWrap();
        r1lp.topMargin = dp(9);
        r1.setLayoutParams(r1lp);
        Button rec = bigBtn("●  录制", COL_REC, Color.WHITE);
        rec.setLayoutParams(weightLp(1.15f));
        rec.setOnClickListener(v -> vtr(u, "2002", "录制"));
        Button play = bigBtn("▶  播放", COL_PLAY, Color.WHITE);
        play.setLayoutParams(weightLp(1f));
        play.setOnClickListener(v -> vtr(u, "2001", "播放"));
        Button stop = bigBtn("■  停止", COL_STOPBTN, Color.WHITE);
        stop.setLayoutParams(weightLp(1f));
        stop.setOnClickListener(v -> vtr(u, "2000", "停止"));
        r1.addView(rec);
        r1.addView(play);
        r1.addView(stop);
        card.addView(r1);

        // 次要 VTR
        LinearLayout r2 = hl();
        LinearLayout.LayoutParams r2lp = matchWrap();
        r2lp.topMargin = dp(6);
        r2.setLayoutParams(r2lp);
        Button stbyOn = outlineBtn("待机开 2004");
        stbyOn.setLayoutParams(weightLp(1f));
        stbyOn.setOnClickListener(v -> vtr(u, "2004", "待机开"));
        Button stbyOff = outlineBtn("待机关 2003");
        stbyOff.setLayoutParams(weightLp(1f));
        stbyOff.setOnClickListener(v -> vtr(u, "2003", "待机关"));
        Button eject = outlineBtn("弹出 2005");
        eject.setLayoutParams(weightLp(1f));
        eject.setOnClickListener(v -> vtr(u, "2005", "弹出"));
        r2.addView(stbyOn);
        r2.addView(stbyOff);
        r2.addView(eject);
        card.addView(r2);

        // 连接工具行
        LinearLayout r3 = hl();
        LinearLayout.LayoutParams r3lp = matchWrap();
        r3lp.topMargin = dp(6);
        r3.setLayoutParams(r3lp);
        Button conn = textBtn("连接", COL_ACCENT);
        conn.setOnClickListener(v -> connectOne(u));
        Button disc = textBtn("断开", COL_DIM);
        disc.setOnClickListener(v -> u.amp.submit(() -> {
            u.amp.close();
            log("[" + chName + "] 已断开", true);
            runOnUiThread(() -> markOffline(u));
        }));
        Button query = textBtn("查询状态", COL_DIM);
        query.setOnClickListener(v -> u.amp.submit(() -> doQuery(u, false)));
        Button reset = textBtn("TC 归零", COL_AMBER);
        reset.setOnClickListener(v -> u.clock.reset());
        View spacer = new View(this);
        spacer.setLayoutParams(weightLp(1f));
        r3.addView(conn);
        r3.addView(disc);
        r3.addView(query);
        r3.addView(spacer);
        r3.addView(reset);
        card.addView(r3);

        u.card = card;
        u.cardDraw = cardDraw;
        return u;
    }

    private LinearLayout buildGlobalCard() {
        LinearLayout c = cardShell("全局控制  ·  A + B");

        LinearLayout r1 = hl();
        Button rec = bigBtn("● 全部录制", COL_REC, Color.WHITE);
        rec.setLayoutParams(weightLp(1.15f));
        rec.setOnClickListener(v -> allVtr("2002", "全部录制"));
        Button stop = bigBtn("■ 全部停止", COL_STOPBTN, Color.WHITE);
        stop.setLayoutParams(weightLp(1f));
        stop.setOnClickListener(v -> allVtr("2000", "全部停止"));
        Button play = bigBtn("▶ 全部播放", COL_PLAY, Color.WHITE);
        play.setLayoutParams(weightLp(1f));
        play.setOnClickListener(v -> allVtr("2001", "全部播放"));
        Button refresh = outlineBtn("刷新");
        refresh.setLayoutParams(weightLp(0.8f));
        refresh.setOnClickListener(v -> queryAll());
        r1.addView(rec);
        r1.addView(stop);
        r1.addView(play);
        r1.addView(refresh);
        c.addView(r1);

        LinearLayout r2 = hl();
        LinearLayout.LayoutParams r2lp = matchWrap();
        r2lp.topMargin = dp(8);
        r2.setLayoutParams(r2lp);
        swAuto = new Switch(this);
        swAuto.setText("自动刷新 2s");
        swAuto.setTextColor(COL_INK);
        swAuto.setTextSize(12);
        swAuto.setChecked(true);
        swAuto.setLayoutParams(weightLp(1.1f));
        swAuto.setOnCheckedChangeListener((b, checked) -> autoPoll = checked);
        r2.addView(swAuto);

        TimecodeClock.Fps[] rates = TimecodeClock.Fps.values();
        String[] rateLabels = new String[rates.length];
        int sel25 = 0;
        for (int i = 0; i < rates.length; i++) {
            rateLabels[i] = rates[i].label;
            if (rates[i] == TimecodeClock.Fps.F25) sel25 = i;
        }
        spFps = mkSpinner(rateLabels, sel25);
        spFps.setLayoutParams(weightLp(1f));
        r2.addView(spFps);

        spMode = mkSpinner(new String[]{"REC RUN", "TIME OF DAY"}, 0);
        spMode.setLayoutParams(weightLp(1.15f));
        r2.addView(spMode);
        c.addView(r2);

        TextView note = tv("注：AMP 协议不支持读取机内时间码。REC RUN 为依据录制状态推算的本地计时；"
                + "TIME OF DAY 取手机时钟，需设备与手机时钟一致才等于机内 ToD。", 10, COL_DIM, Typeface.NORMAL);
        note.setPadding(dp(2), dp(8), dp(2), 0);
        c.addView(note);
        return c;
    }

    private LinearLayout buildConsoleCard() {
        LinearLayout c = cardShell("AMP 原始命令控制台");

        LinearLayout crow = hl();
        TextView tg = tv("目标", 12, COL_DIM, Typeface.NORMAL);
        tg.setPadding(0, 0, dp(6), 0);
        crow.addView(tg);
        spTarget = mkSpinner(new String[]{"A", "B", "A + B"}, 0);
        spTarget.setLayoutParams(weightLp(0.7f));
        crow.addView(spTarget);

        etCmd = darkField("", "CMDS00042000 或直接 4 位码 2000");
        LinearLayout cmdWrap = vl();
        cmdWrap.setLayoutParams(weightLp(1.4f));
        cmdWrap.setPadding(dp(6), 0, dp(6), 0);
        cmdWrap.addView(etCmd);
        crow.addView(cmdWrap);
        Button send = outlineBtn("发送");
        send.setTextColor(COL_ACCENT);
        send.setOnClickListener(v -> sendRaw());
        crow.addView(send);
        c.addView(crow);

        LinearLayout qrow = hl();
        LinearLayout.LayoutParams qlp = matchWrap();
        qlp.topMargin = dp(6);
        qrow.setLayoutParams(qlp);
        String[][] quick = {
                {"停止 2000", "2000"}, {"播放 2001", "2001"}, {"录制 2002", "2002"},
                {"待机关 2003", "2003"}
        };
        for (String[] q : quick) {
            Button b = outlineBtn(q[0]);
            b.setTextSize(11);
            b.setLayoutParams(weightLp(1f));
            b.setOnClickListener(v -> {
                etCmd.setText("CMDS0004" + q[1]);
                sendRaw();
            });
            qrow.addView(b);
        }
        c.addView(qrow);

        LinearLayout qrow2 = hl();
        String[][] quick2 = {
                {"待机开 2004", "2004"}, {"弹出 2005", "2005"}
        };
        for (String[] q : quick2) {
            Button b = outlineBtn(q[0]);
            b.setTextSize(11);
            b.setLayoutParams(weightLp(1f));
            b.setOnClickListener(v -> {
                etCmd.setText("CMDS0004" + q[1]);
                sendRaw();
            });
            qrow2.addView(b);
        }
        Button reaq = outlineBtn("REAQ 状态");
        reaq.setTextSize(11);
        reaq.setLayoutParams(weightLp(1f));
        reaq.setOnClickListener(v -> querySelected());
        qrow2.addView(reaq);
        c.addView(qrow2);

        etCmd.setOnEditorActionListener((v, actionId, event) -> {
            sendRaw();
            return true;
        });
        return c;
    }

    private LinearLayout buildLogCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(COL_CARD, 14);
        g.setStroke(dp(1), COL_STROKE);
        c.setBackground(g);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams clp = matchWrap();
        clp.bottomMargin = dp(10);
        c.setLayoutParams(clp);

        LinearLayout titleRow = hl();
        titleRow.setPadding(dp(2), 0, dp(2), dp(8));
        TextView titleView = tv("通信日志", 13, COL_ACCENT, Typeface.BOLD);
        titleView.setLetterSpacing(0.06f);
        titleView.setLayoutParams(weightLp(1f));
        titleRow.addView(titleView);
        Button clear = textBtn("清空", COL_DIM);
        clear.setOnClickListener(v -> tvLog.setText(""));
        titleRow.addView(clear);
        c.addView(titleRow);

        logScroll = new ScrollView(this);
        GradientDrawable lg = round(0xFF05070B, 10);
        lg.setStroke(dp(1), 0xFF1B2536);
        logScroll.setBackground(lg);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(10.5f);
        tvLog.setTextColor(0xFFCBD5E1);
        tvLog.setPadding(dp(9), dp(8), dp(9), dp(8));
        logScroll.addView(tvLog);
        c.addView(logScroll);
        return c;
    }

    // ================= 状态着色 =================

    private void setBlink(View v, boolean on) {
        if (on) {
            AlphaAnimation a = new AlphaAnimation(1f, 0.2f);
            a.setDuration(450);
            a.setRepeatCount(Animation.INFINITE);
            a.setRepeatMode(Animation.REVERSE);
            v.startAnimation(a);
        } else {
            v.clearAnimation();
            v.setAlpha(1f);
        }
    }

    private void applyState(UnitViews u) {
        if (u == null) return;
        boolean online = u.amp.isConnected();
        if (!online) {
            u.conn.setText("未连接");
            u.conn.setTextColor(COL_DIM);
            setPill(u, "离线", COL_STOPBTN);
            u.cardDraw.setStroke(dp(1), COL_STROKE);
            setBlink(u.pill, false);
            return;
        }
        u.conn.setText(u.amp.getIp());
        u.conn.setTextColor(COL_PLAY);

        String code = u.lastCode;
        if (code == null || code.isEmpty()) {
            setPill(u, "已连接", COL_ACCENT);
            u.cardDraw.setStroke(dp(1), COL_STROKE);
            setBlink(u.pill, false);
            return;
        }
        switch (code) {
            case "00":
                setPill(u, "■ 停止", COL_STOPBTN);
                u.cardDraw.setStroke(dp(1), COL_STROKE);
                setBlink(u.pill, false);
                break;
            case "01":
                setPill(u, "▶ 播放", COL_PLAY);
                u.cardDraw.setStroke(dp(2), COL_PLAY);
                setBlink(u.pill, false);
                break;
            case "02":
                setPill(u, "● 录制中", COL_REC);
                u.cardDraw.setStroke(dp(2), COL_REC);
                setBlink(u.pill, true);
                break;
            default:
                setPill(u, "码 " + code, COL_AMBER);
                u.cardDraw.setStroke(dp(1), COL_AMBER);
                setBlink(u.pill, false);
                break;
        }
    }

    private void setPill(UnitViews u, String text, int bg) {
        u.pill.setText(text);
        u.pillDraw.setColor(bg);
    }

    private void renderTc(UnitViews u) {
        if (u == null) return;
        TimecodeClock.Fps fps = TimecodeClock.Fps.values()[spFps.getSelectedItemPosition()];
        boolean tod = spMode.getSelectedItemPosition() == 1;
        boolean online = u.amp.isConnected();
        if (!online) {
            u.tc.setText("--:--:--:--");
            u.tc.setTextColor(0xFF4B5A6E);
        } else {
            u.tc.setText(tod ? TimecodeClock.formatTod(fps) : u.clock.formatRecRun(fps));
            String code = u.lastCode;
            if ("02".equals(code)) u.tc.setTextColor(0xFFF87171);
            else if ("01".equals(code)) u.tc.setTextColor(0xFF4ADE80);
            else u.tc.setTextColor(COL_INK);
        }
        u.tvFps.setText(fps.label + " fps");
        u.tcLabel.setText(tod ? "TIME OF DAY · 手机时钟" : "REC RUN · 本地计时");
    }

    // ================= 日志 =================

    private void log(String msg, boolean ok) {
        runOnUiThread(() -> {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            SpannableStringBuilder sb = new SpannableStringBuilder("[" + ts + "] " + msg + "\n");
            sb.setSpan(new ForegroundColorSpan(ok ? 0xFFCBD5E1 : 0xFFF87171),
                    0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvLog.append(sb);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ================= 连接 =================

    private int port() {
        try {
            return Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            return AmpChannel.DEFAULT_PORT;
        }
    }

    private void connectOne(UnitViews u) {
        String ip = ("A".equals(u.ch) ? etIpA : etIpB).getText().toString().trim();
        int port = port();
        log("正在连接单元 " + u.ch + ": " + ip + ":" + port + " …", true);
        u.amp.submit(() -> {
            String err = u.amp.connect(ip, port);
            if (err == null) {
                log("[" + u.ch + "] 已连接 " + ip + ":" + port + "，AMP 通道就绪", true);
                runOnUiThread(() -> applyState(u));
                doQuery(u, false);
            } else {
                log("[" + u.ch + "] 连接失败: " + err, false);
                runOnUiThread(() -> applyState(u));
            }
        });
    }

    private void connectAll() {
        connectOne(unitA);
        connectOne(unitB);
    }

    private void disconnectAll() {
        ampA.submit(() -> {
            ampA.close();
            runOnUiThread(() -> markOffline(unitA));
        });
        ampB.submit(() -> {
            ampB.close();
            runOnUiThread(() -> markOffline(unitB));
        });
        log("A/B 均已断开", true);
    }

    private void markOffline(UnitViews u) {
        u.clock.pause();
        u.lastCode = null;
        applyState(u);
    }

    // ================= 命令 =================

    private void vtr(UnitViews u, String code, String label) {
        u.amp.submit(() -> {
            if (!u.amp.isConnected()) {
                log("[" + u.ch + "] 未连接，" + label + " 未发送", false);
                return;
            }
            Object[] r = u.amp.sendVtr(code);
            boolean ok = (Boolean) r[0];
            log("[" + u.ch + "] " + label + "(" + code + ") → " + r[1], ok);
            if (ok) u.amp.schedule(() -> doQuery(u, true), 800);
        });
    }

    private void allVtr(String code, String label) {
        vtr(unitA, code, label);
        vtr(unitB, code, label);
    }

    private void doQuery(UnitViews u, boolean quiet) {
        if (!u.amp.isConnected()) return;
        String st = u.amp.queryState();
        if (st != null) {
            String code = u.amp.getStateCode();
            runOnUiThread(() -> {
                boolean rec = "02".equals(code);
                if (rec) u.clock.start();
                else u.clock.pause();
                u.lastCode = code;
                applyState(u);
            });
            if (!quiet) log("[" + u.ch + "] 状态: " + st, true);
        } else if (!quiet) {
            log("[" + u.ch + "] 状态查询失败", false);
        }
    }

    private void queryAll() {
        ampA.submit(() -> doQuery(unitA, false));
        ampB.submit(() -> doQuery(unitB, false));
    }

    private List<UnitViews> targets() {
        String t = (String) spTarget.getSelectedItem();
        List<UnitViews> l = new ArrayList<>();
        if ("A".equals(t)) l.add(unitA);
        else if ("B".equals(t)) l.add(unitB);
        else {
            l.add(unitA);
            l.add(unitB);
        }
        return l;
    }

    private void querySelected() {
        for (UnitViews u : targets()) {
            u.amp.submit(() -> doQuery(u, false));
        }
    }

    private void sendRaw() {
        String raw = etCmd.getText().toString().trim();
        if (raw.isEmpty()) return;
        final byte[] frame;
        final String shown;
        if (raw.length() == 4 && raw.matches("[0-9a-fA-F]{4}")) {
            frame = ("CMDS0004" + raw + "\n").getBytes();
            shown = "CMDS0004" + raw;
        } else {
            frame = (raw.endsWith("\n") ? raw : raw + "\n").getBytes();
            shown = raw;
        }
        for (UnitViews u : targets()) {
            u.amp.submit(() -> {
                if (!u.amp.isConnected()) {
                    log("[" + u.ch + "] 未连接，帧未发送: " + shown, false);
                    return;
                }
                Object[] r = u.amp.sendFrame(frame);
                boolean ok = (Boolean) r[0];
                byte[] ans = (byte[]) r[1];
                if (ok) {
                    StringBuilder hex = new StringBuilder();
                    if (ans != null) for (byte b : ans) hex.append(String.format("%02x", b));
                    log("[" + u.ch + "] >>> " + shown + "  <<< " + hex, true);
                } else {
                    log("[" + u.ch + "] >>> " + shown + "  失败: " + r[2], false);
                }
            });
        }
    }

    // ================= 扫描 =================

    private void onScan() {
        if (scanning) {
            if (scanner != null) scanner.cancel();
            tvScanStatus.setText("正在停止…");
            tvScanStatus.setTextColor(COL_AMBER);
            return;
        }
        scanning = true;
        tvFound.setText("");
        btnScan.setText("⏹  停止扫描");
        tvScanStatus.setText("全自动扫描中…");
        tvScanStatus.setTextColor(COL_ACCENT);
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setProgress(0);

        scanner = new Scanner(this, port(), new Scanner.Listener() {
            @Override
            public void onLog(String s) {
                log(s, true);
            }

            @Override
            public void onProgress(String stage, int done, int total, int found) {
                runOnUiThread(() -> {
                    if (total > 0) {
                        scanProgress.setMax(total);
                        scanProgress.setProgress(done);
                    }
                    tvScanStatus.setText("[" + stage + "] " + done + "/" + total + "，已发现 " + found + " 台");
                });
            }

            @Override
            public void onFound(String ip, String info) {
                log("✦ 发现设备 " + ip + "  " + info, true);
            }

            @Override
            public void onFinished(List<String[]> found, boolean cancelled) {
                runOnUiThread(() -> scanDone(found, cancelled));
            }
        });
        scanner.start();
    }

    private void scanDone(List<String[]> found, boolean cancelled) {
        scanning = false;
        btnScan.setText("🔍  全自动扫描");
        scanProgress.setVisibility(View.GONE);
        if (cancelled && found.isEmpty()) {
            tvScanStatus.setText("已停止");
            tvScanStatus.setTextColor(COL_AMBER);
            log("扫描被手动停止", false);
            return;
        }
        if (found.isEmpty()) {
            tvScanStatus.setText("未发现 AMP 设备");
            tvScanStatus.setTextColor(COL_REC);
            log("未发现设备。请确认: 1.设备开机 2.手机与设备同一路由器/网线 3.设备 AMP 服务正常", false);
            return;
        }
        tvScanStatus.setText("发现 " + found.size() + " 台，已填入 IP");
        tvScanStatus.setTextColor(COL_PLAY);
        StringBuilder sb = new StringBuilder();
        for (String[] f : found) {
            sb.append("✦ ").append(f[0]).append("  ").append(f[1]).append("\n");
        }
        tvFound.setText(sb.toString().trim());
        etIpA.setText(found.get(0)[0]);
        if (found.size() >= 2) etIpB.setText(found.get(1)[0]);
        log("已自动填入 A=" + found.get(0)[0]
                + (found.size() >= 2 ? "  B=" + found.get(1)[0] : ""), true);
    }
}
