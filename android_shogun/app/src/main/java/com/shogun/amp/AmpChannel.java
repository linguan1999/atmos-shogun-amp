package com.shogun.amp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 单个录制单元(A/B)的 AMP 连接。
 * 协议与桌面版 shogun_amp_control.py 完全一致（已在真机握手验证）：
 *   TCP 3811；CRAT0007204Vtr1 开通道 -> 1001(ACK)
 *   REAQ0007204Vtr1 查询 -> 1112xx（00停 01放 02录）
 *   CMDS0004<4位VTR码> 控制（2000停/2001放/2002录…）
 * 特性：命令间隔 ≥550ms、模式切换断链自动重连、发送前排空陈旧应答。
 */
public class AmpChannel {

    public static final int DEFAULT_PORT = 3811;

    private static final byte[] CHAN_OPEN = "CRAT0007204Vtr1\n".getBytes();
    private static final byte[] CHAN_QUERY = "REAQ0007204Vtr1\n".getBytes();
    private static final byte[] CHAN_CLOSE = "STOP0000\n".getBytes();
    private static final String ACK = "1001";
    private static final String NACK = "1111";
    private static final String ERR = "2222";

    private static final long MIN_CMD_GAP_MS = 550;

    private static final Map<String, String> STATE_MAP = new HashMap<>();
    static {
        STATE_MAP.put("00", "停止");
        STATE_MAP.put("01", "播放");
        STATE_MAP.put("02", "录制");
    }

    public interface LogCb {
        void log(String msg, boolean ok);
    }

    private final String name;
    private final LogCb cb;
    private final ScheduledExecutorService exec;

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private String ip = "";
    private int port = DEFAULT_PORT;
    private boolean channelOpen;
    private long lastSend;
    private final Object lock = new Object();
    private String state = "";

    public AmpChannel(String name, LogCb cb) {
        this.name = name;
        this.cb = cb;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "amp-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(Runnable r) {
        exec.execute(r);
    }

    public void schedule(Runnable r, long delayMs) {
        exec.schedule(r, delayMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    public String getName() {
        return name;
    }

    public String getIp() {
        return ip;
    }

    public boolean isConnected() {
        synchronized (lock) {
            return sock != null && channelOpen;
        }
    }

    // ---------------- 底层 ----------------

    private void ensureGap() throws InterruptedException {
        long wait = lastSend + MIN_CMD_GAP_MS - System.currentTimeMillis();
        while (wait > 0) {
            Thread.sleep(Math.min(50, wait));
            wait = lastSend + MIN_CMD_GAP_MS - System.currentTimeMillis();
        }
    }

    private void drain() {
        if (sock == null) return;
        try {
            int n;
            int guard = 0;
            while ((n = in.available()) > 0 && guard++ < 16) {
                byte[] skip = new byte[Math.min(n, 1024)];
                in.read(skip);
            }
        } catch (Exception ignore) {
        }
    }

    private byte[] readAnswer() {
        byte[] buf = new byte[64];
        int len = 0;
        try {
            sock.setSoTimeout(2000);
            // 先凑满 4 字节帧头
            while (len < 4) {
                int c = in.read();
                if (c < 0) return len > 0 ? java.util.Arrays.copyOf(buf, len) : null;
                buf[len++] = (byte) c;
            }
            String head = new String(buf, 0, 4);
            if (ACK.equals(head) || NACK.equals(head) || ERR.equals(head)) {
                return java.util.Arrays.copyOf(buf, len);
            }
            // 其他帧（如 111200 状态帧）继续读到 150ms 静默为止
            try {
                sock.setSoTimeout(150);
                while (len < buf.length) {
                    int c = in.read();
                    if (c < 0) break;
                    buf[len++] = (byte) c;
                }
            } catch (java.net.SocketTimeoutException e) {
                // 静默即帧结束
            }
        } catch (java.net.SocketTimeoutException e) {
            return len > 0 ? java.util.Arrays.copyOf(buf, len) : null;
        } catch (Exception e) {
            return len > 0 ? java.util.Arrays.copyOf(buf, len) : null;
        }
        return java.util.Arrays.copyOf(buf, len);
    }

    private void tcpConnect() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), 3000);
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private void openChannel() throws Exception {
        Thread.sleep(250);
        synchronized (lock) {
            ensureGap();
            out.write(CHAN_OPEN);
            out.flush();
            lastSend = System.currentTimeMillis();
        }
        byte[] ans = readAnswer();
        boolean ack = ans != null && ans.length >= 4
                && ACK.equals(new String(ans, 0, 4));
        // B 单元怪癖：CRAT 不回 1001，而是直接回 1112xx 状态帧，语义等同通道已开
        boolean statusAck = ans != null && ans.length >= 6
                && "1112".equals(new String(ans, 0, 4));
        channelOpen = ack || statusAck;
        if (!channelOpen) {
            throw new RuntimeException("CRAT 开通道失败: " + (ans == null ? "无应答" : repr(ans)));
        }
        if (statusAck) {
            String tail = new String(ans, ans.length - 2, 2);
            if (tail.matches("[0-9]{2}")) state = tail;
            cb.log("[" + name + "] 以状态帧 " + repr(ans) + " 确认通道(B 单元)", true);
        }
    }

    private void disconnectLocked() {
        if (sock != null) {
            try {
                sock.close();
            } catch (Exception ignore) {
            }
        }
        sock = null;
        in = null;
        out = null;
        channelOpen = false;
    }

    // ---------------- 对外（调用方已在本通道单线程内） ----------------

    public String connect(String newIp, int newPort) {
        synchronized (lock) {
            disconnectLocked();
            ip = newIp;
            port = newPort;
            try {
                tcpConnect();
                openChannel();
                return null;
            } catch (Exception e) {
                disconnectLocked();
                return e.getMessage();
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (sock != null) {
                try {
                    ensureGap();
                    out.write(CHAN_CLOSE);
                    out.flush();
                } catch (Exception ignore) {
                }
            }
            disconnectLocked();
        }
    }

    private static String repr(byte[] b) {
        StringBuilder sb = new StringBuilder("b'");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.append("'").toString();
    }

    private static String explain(byte[] ans) {
        if (ans == null) return "无应答";
        String h = ans.length >= 4 ? new String(ans, 0, 4) : "";
        if (NACK.equals(h)) return "设备拒绝 NACK: " + repr(ans);
        if (ERR.equals(h)) return "设备错误 ERROR: " + repr(ans);
        return "未知应答: " + repr(ans);
    }

    /** 发送一帧；断链自动重连并重发一次。返回 (ok, answer) */
    public Object[] sendFrame(byte[] frame) {
        synchronized (lock) {
            if (sock == null) {
                String e1 = connect(ip, port);
                if (e1 != null) return new Object[]{false, null, e1};
            }
            try {
                ensureGap();
                drain();
                out.write(frame);
                out.flush();
                lastSend = System.currentTimeMillis();
                byte[] ans = readAnswer();
                if (ans != null && ans.length >= 4) {
                    String h = new String(ans, 0, 4);
                    if (!NACK.equals(h) && !ERR.equals(h)) {
                        return new Object[]{true, ans, ""};
                    }
                    return new Object[]{false, ans, explain(ans)};
                }
                // 链路被设备断开（模式切换的正常现象）→ 重连一次
                cb.log("[" + name + "] 连接被设备断开，自动重连…", false);
                String rc = connect(ip, port);
                if (rc != null) return new Object[]{false, null, "重连失败: " + rc};
                ensureGap();
                drain();
                out.write(frame);
                out.flush();
                lastSend = System.currentTimeMillis();
                byte[] ans2 = readAnswer();
                if (ans2 != null && ans2.length >= 4) {
                    String h = new String(ans2, 0, 4);
                    if (!NACK.equals(h) && !ERR.equals(h)) {
                        return new Object[]{true, ans2, ""};
                    }
                    return new Object[]{false, ans2, explain(ans2)};
                }
                return new Object[]{false, ans2, "重发无应答"};
            } catch (Exception e) {
                disconnectLocked();
                cb.log("[" + name + "] 链路异常(" + e.getMessage() + ")，自动重连…", false);
                String rc = connect(ip, port);
                if (rc != null) return new Object[]{false, null, "重连失败: " + rc};
                try {
                    ensureGap();
                    drain();
                    out.write(frame);
                    out.flush();
                    lastSend = System.currentTimeMillis();
                    byte[] ans2 = readAnswer();
                    if (ans2 != null && ans2.length >= 4) {
                        String h = new String(ans2, 0, 4);
                        if (!NACK.equals(h) && !ERR.equals(h)) {
                            return new Object[]{true, ans2, ""};
                        }
                    }
                    return new Object[]{false, ans2, "重发失败"};
                } catch (Exception e2) {
                    return new Object[]{false, null, e2.getMessage()};
                }
            }
        }
    }

    public Object[] sendVtr(String code4) {
        byte[] frame = ("CMDS0004" + code4 + "\n").getBytes();
        Object[] r = sendFrame(frame);
        boolean ok = (Boolean) r[0];
        byte[] ans = (byte[]) r[1];
        if (ok && ans != null && ans.length >= 4) {
            String h4 = new String(ans, 0, 4);
            String h2 = new String(ans, 0, 2);
            if (ACK.equals(h4) || "11".equals(h2)) {
                return new Object[]{true, "已执行 " + code4 + "，应答 " + repr(ans)};
            }
        }
        String err = r[2] == null ? explain(ans) : (String) r[2];
        return new Object[]{false, err};
    }

    /** 返回状态文字；失败返回 null */
    public String queryState() {
        Object[] r = sendFrame(CHAN_QUERY);
        if (!(Boolean) r[0]) return null;
        byte[] ans = (byte[]) r[1];
        if (ans == null || ans.length < 2) return null;
        String tail = new String(ans, ans.length - 2, 2);
        state = tail;
        return STATE_MAP.getOrDefault(tail, "状态码 " + tail);
    }

    public String getStateCode() {
        return state;
    }
}
