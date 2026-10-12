package com.shogun.amp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全自动 AMP 设备扫描：
 * 阶段0 /proc/net/arp 邻居（老版本安卓可读）
 * 阶段1 本机所在 /24 + 出厂常见网段（约 2~6 秒）
 * 阶段2 本机为 /16 等大掩码（OTG 有线直连）时展开整个大网段
 * 每个候选 TCP 连 3811，连通后发 CRAT，1001 才确认为 AMP 设备。
 */
public class Scanner {

    private static final byte[] CHAN_OPEN = "CRAT0007204Vtr1\n".getBytes(StandardCharsets.US_ASCII);

    private static final String[] COMMON_PREFIXES = {
            "192.168.0.", "192.168.1.", "192.168.2.", "192.168.5.",
            "192.168.10.", "192.168.88.", "192.168.100.",
            "10.0.0.", "10.0.1."
    };

    public interface Listener {
        void onLog(String s);
        void onProgress(String stage, int done, int total, int found);
        void onFound(String ip, String info);
        void onFinished(List<String[]> found, boolean cancelled);
    }

    private final Context ctx;
    private final int port;
    private final Listener listener;
    private volatile boolean cancelled;
    private ExecutorService pool;

    public Scanner(Context ctx, int port, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
    }

    public void start() {
        new Thread(this::run, "amp-scanner").start();
    }

    private void run() {
        long t0 = System.currentTimeMillis();
        List<String[]> found = new ArrayList<>();
        Set<String> foundIps = new LinkedHashSet<>();
        Set<String> local = localIps();
        try {
            // 阶段 0：ARP 邻居
            List<String> arp = arpNeighbors();
            arp.removeIf(ip -> local.contains(ip) || ip.endsWith(".0") || ip.endsWith(".255"));
            if (!arp.isEmpty() && !cancelled) {
                listener.onLog("[ARP] 邻居 " + arp.size() + " 个，优先探测");
                scanBatch(sorted(arp), "ARP邻居", found, foundIps);
            }

            // 阶段 1：本机 /24 + 常见网段
            if (found.size() < 2 && !cancelled) {
                Set<String> targets = new HashSet<>();
                for (String pre : COMMON_PREFIXES) addSlash24(targets, pre);
                for (String pre : localSlash24Prefixes()) addSlash24(targets, pre);
                targets.removeAll(local);
                targets.removeAll(foundIps);
                scanBatch(sorted(targets), "快速网段", found, foundIps);
            }

            // 阶段 2：大掩码兜底
            if (found.isEmpty() && !cancelled) {
                Set<String> wide = wideTargets();
                wide.removeAll(local);
                wide.removeAll(foundIps);
                if (!wide.isEmpty()) {
                    listener.onLog("快速网段无结果，扩展到大网段 " + wide.size() + " 个地址…");
                    scanBatch(sorted(wide), "大网段兜底", found, foundIps);
                }
            }
        } catch (Exception e) {
            listener.onLog("扫描线程异常: " + e);
        }
        listener.onLog("扫描结束，耗时 " + (System.currentTimeMillis() - t0) / 1000 + "s");
        found.sort((a, b) -> {
            String[] x = a[0].split("\\.");
            String[] y = b[0].split("\\.");
            for (int i = 0; i < 4; i++) {
                int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                if (c != 0) return c;
            }
            return 0;
        });
        listener.onFinished(found, cancelled);
    }

    private void scanBatch(List<String> targets, String stage,
                           List<String[]> found, Set<String> foundIps) {
        listener.onLog("[" + stage + "] " + targets.size() + " 个地址…");
        int total = targets.size();
        AtomicInteger done = new AtomicInteger();
        Set<Future<?>> futures = ConcurrentHashMap.newKeySet();
        pool = Executors.newFixedThreadPool(500);
        for (String ip : targets) {
            if (cancelled) break;
            futures.add(pool.submit(() -> {
                if (cancelled) return;
                String[] r = probe(ip);
                int n = done.incrementAndGet();
                if (n % 100 == 0 || n == total) {
                    listener.onProgress(stage, n, total, found.size());
                }
                if (r != null && !foundIps.contains(r[0])) {
                    synchronized (found) {
                        if (!foundIps.contains(r[0])) {
                            foundIps.add(r[0]);
                            found.add(r);
                        }
                    }
                    listener.onFound(r[0], r[1]);
                    if (found.size() >= 2) cancelled = true;
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignore) {
            }
        }
        pool.shutdown();
    }

    /** 返回 ["IP","说明"] 或 null */
    private String[] probe(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 400);
        } catch (Exception e) {
            closeQ(s);
            return null;
        }
        try {
            s.getOutputStream().write(CHAN_OPEN);
            s.getOutputStream().flush();
            s.setSoTimeout(900);
            byte[] b = new byte[16];
            int n = s.getInputStream().read(b);
            if (n >= 4 && "1001".equals(new String(b, 0, 4))) {
                return new String[]{ip, "AMP ACK 1001"};
            }
            // B 单元怪癖：CRAT 直接回 1112xx 状态帧代替 ACK，同样确认为 AMP 设备
            if (n >= 6 && "1112".equals(new String(b, 0, 4))) {
                return new String[]{ip, "AMP 状态帧 " + new String(b, 4, Math.min(2, n - 4))};
            }
            return null; // 端口开放但非 AMP 应答，不计入发现
        } catch (Exception e) {
            return null;
        } finally {
            closeQ(s);
        }
    }

    private static void closeQ(Socket s) {
        try {
            s.close();
        } catch (Exception ignore) {
        }
    }

    // ---------------- 网段来源 ----------------

    private Set<String> localIps() {
        Set<String> out = new HashSet<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    String h = la.getAddress().getHostAddress();
                    if (h != null && h.contains(".") && !h.startsWith("127.")) {
                        out.add(h.split("%")[0]);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 本机各接口所在 /24 前缀（a.b.c.） */
    private List<String> localSlash24Prefixes() {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    String h = la.getAddress().getHostAddress();
                    if (h == null || !h.contains(".") || h.startsWith("127.")) continue;
                    String[] p = h.split("\\.");
                    if (p.length >= 4) out.add(p[0] + "." + p[1] + "." + p[2] + ".");
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 本机掩码 < /24（如 /16 直连）时展开大网段 */
    private Set<String> wideTargets() {
        Set<String> out = new HashSet<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    String h = la.getAddress().getHostAddress();
                    if (h == null || !h.contains(".") || h.startsWith("127.")) continue;
                    String[] p = h.split("\\.");
                    int bits = la.getPrefixLength();
                    if (bits >= 24) continue;
                    if (bits >= 16) {
                        for (int a = 0; a < 256; a++)
                            for (int b = 1; b < 255; b++)
                                out.add(p[0] + "." + p[1] + "." + a + "." + b);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private List<String> arpNeighbors() {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine(); // 表头
            while ((line = br.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                // MAC 可能不补零(如 e6:9:..)，用正则判断而非写死 17 字符
                if (f.length >= 4 && f[3].matches("(?i)([0-9a-f]{1,2}:){5}[0-9a-f]{1,2}")
                        && !"00:00:00:00:00:00".equals(f[3])) {
                    String ip = f[0];
                    if (ip.startsWith("224.") || ip.startsWith("239.")
                            || ip.endsWith(".0") || ip.endsWith(".255")) continue;
                    out.add(ip);
                }
            }
        } catch (Exception ignore) {
            // Android 10+ 普通应用无权读取，忽略
        }
        return out;
    }

    private static void addSlash24(Set<String> targets, String prefix) {
        for (int i = 1; i < 255; i++) targets.add(prefix + i);
    }

    private static List<String> sorted(java.util.Collection<String> in) {
        List<String> l = new ArrayList<>(new LinkedHashSet<>(in));
        l.sort((a, b) -> {
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            int n = Math.min(x.length, y.length);
            for (int i = 0; i < n; i++) {
                int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                if (c != 0) return c;
            }
            return 0;
        });
        return l;
    }
}
