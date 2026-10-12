#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Atomos Shogun Studio (初代) AMP 远程控制软件
通过 RJ45 网口 + AMP(Advanced Media Protocol) 控制
双录制单元(A/B)各自独立 IP，分别建立 TCP 连接

已实测协议 (2026-09-21, 固件出厂态):
  - TCP 端口 3811（出厂默认 IP: A=192.168.1.100, B=192.168.1.101）
  - 打开通道:  b"CRAT0007204Vtr1\n"   -> b"1001" (ACK)
  - 状态查询:  b"REAQ0007204Vtr1\n"   -> b"1112xx" (末字节状态: 00停止 01播放 02录制)
  - 控制命令:  b"CMDS0004<4位VTR码>\n" -> b"1001" (ACK)
      VTR 码(Sony 9-pin 0x20 传输族): 2000停止 2001播放 2002录制
                                     2003待机关 2004待机开 2005弹出
  - 关闭通道:  b"STOP0000\n"
  - 应答: 1001=ACK  1111=NACK  2222=ERROR
注意 (源自设备特性):
  - 同时只允许一个客户端连接
  - 播放/录制模式切换时设备会主动断开 TCP，本软件会自动重连
  - 命令间隔不能小于 500ms，过快会导致设备协议栈卡死
"""

import socket
import threading
import time
import concurrent.futures
import tkinter as tk
from tkinter import ttk, scrolledtext
import datetime

# AMP 协议默认端口（实测，非此前猜测的 8900）
DEFAULT_PORT = 3811
# Shogun Studio 两个录制单元出厂默认 IP
DEFAULT_IP_A = "192.168.1.100"
DEFAULT_IP_B = "192.168.1.101"

# 协议常量
CHAN_OPEN = b"CRAT0007204Vtr1\n"
CHAN_QUERY = b"REAQ0007204Vtr1\n"
CHAN_CLOSE = b"STOP0000\n"
ACK = b"1001"
NACK = b"1111"
ERR = b"2222"

# 发送最小间隔（秒），设备实现要求
MIN_CMD_GAP = 0.55
# 应答读取超时
RECV_TIMEOUT = 2.0

# VTR 传输命令码 (0x20 族)
VTR_COMMANDS = {
    "停止 STOP": ("2000", "00"),
    "播放 PLAY": ("2001", "01"),
    "录制 REC": ("2002", "02"),
    "待机关 STANDBY_OFF": ("2003", None),
    "待机开 STANDBY_ON": ("2004", None),
    "弹出 EJECT": ("2005", None),
}

# 状态码 -> 文字
STATE_MAP = {"00": "停止", "01": "播放", "02": "录制"}


def get_all_interface_ips():
    """获取本机所有网络接口的 IPv4 地址 (接口名, IP, 点分掩码)。
    解析 macOS `ifconfig`，兼容 Python 3.8+（socket.getifaddrs 仅 3.11+ 可用）"""
    import re
    import subprocess
    ips = []
    try:
        r = subprocess.run(["ifconfig"], capture_output=True, timeout=5)
        text = r.stdout.decode("utf-8", errors="replace")
    except Exception:
        return ips
    cur = None
    for line in text.splitlines():
        m = re.match(r"^([A-Za-z0-9]+):\s+flags", line)
        if m:
            cur = m.group(1)
            continue
        m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+).*?netmask\s+"
                      r"(0x[0-9a-fA-F]+|\d+\.\d+\.\d+\.\d+)", line)
        if m and cur:
            ip, raw_mask = m.group(1), m.group(2)
            if ip.startswith("127."):
                continue
            if raw_mask.startswith("0x"):
                hx = raw_mask[2:].zfill(8)
                mask = ".".join(str(int(hx[i:i + 2], 16)) for i in (0, 2, 4, 6))
            else:
                mask = raw_mask
            ips.append((cur, ip, mask))
    return ips


# 设备出厂/常见默认网段（无论本机在哪个网段都自动扫一遍）
COMMON_PREFIXES = ["192.168.0.", "192.168.1.", "192.168.2.", "192.168.5.",
                   "192.168.10.", "192.168.88.", "192.168.100.",
                   "10.0.0.", "10.0.1."]


def get_arp_neighbors():
    """解析 macOS `arp -an`，返回已有 MAC 地址（非 incomplete）的邻居 IP 列表"""
    import re
    import subprocess
    out = []
    try:
        r = subprocess.run(["arp", "-an"], capture_output=True, timeout=5)
        text = r.stdout.decode("utf-8", errors="replace")
    except Exception:
        return out
    for line in text.splitlines():
        if "incomplete" in line or "[ethernet]" not in line:
            continue
        m = re.search(r"\((\d+\.\d+\.\d+\.\d+)\)\s+at\s+"
                      r"([0-9a-fA-F]{1,2}(?::[0-9a-fA-F]{1,2}){5})", line)
        if m and not m.group(1).startswith(("224.", "239.")):
            ip = m.group(1)
            if ip.split(".")[-1] not in ("0", "255"):
                out.append(ip)
    return out


def mask_prefix_len(netmask):
    try:
        n = 0
        for b in (netmask or "").split("."):
            n = (n << 8) | int(b)
        return bin(n).count("1")
    except Exception:
        return 24


def slash24(prefix3):
    return [f"{prefix3}{i}" for i in range(1, 255)]


def build_stage1_targets():
    """阶段1（快速）：本机各网卡所在 /24 + 出厂常见网段，去重"""
    prefixes = set(COMMON_PREFIXES)
    local_ips = set()
    for _iface, ip, _mask in get_all_interface_ips():
        p = ip.split(".")
        if len(p) == 4:
            prefixes.add(f"{p[0]}.{p[1]}.{p[2]}.")
            local_ips.add(ip)
    targets = sorted({t for pre in prefixes for t in slash24(pre)},
                     key=lambda x: tuple(int(p) for p in x.split(".")))
    return targets, local_ips


def build_stage2_targets():
    """阶段2（兜底）：本机掩码小于 /24 时（如 /16 直连），展开整个大网段"""
    targets = set()
    for _iface, ip, mask in get_all_interface_ips():
        p = ip.split(".")
        if len(p) != 4 or mask_prefix_len(mask) >= 24:
            continue
        bits = mask_prefix_len(mask)
        if bits >= 16:
            targets.update(f"{p[0]}.{p[1]}.{a}.{b}"
                           for a in range(0, 256) for b in range(1, 255))
        elif bits >= 8:
            targets.update(f"{p[0]}.{a}.{b}.{c}"
                           for a in range(0, 256) for b in range(0, 256)
                           for c in range(1, 255))
    return sorted(targets, key=lambda x: tuple(int(p) for p in x.split(".")))


class AMPChannel:
    """单个录制单元(A/B)的 AMP 连接，线程安全，自动重连"""

    def __init__(self, name, log_cb):
        self.name = name          # "A" / "B"
        self.log = log_cb
        self.sock = None
        self.ip = ""
        self.port = DEFAULT_PORT
        self.channel_open = False
        self.last_send = 0.0
        self.lock = threading.RLock()
        self.state = ""           # 最近一次状态码

    # ---------- 底层 ----------
    def _ensure_gap(self):
        wait = self.last_send + MIN_CMD_GAP - time.time()
        while wait > 0:
            time.sleep(min(0.05, wait))
            wait = self.last_send + MIN_CMD_GAP - time.time()

    def _read_answer(self, timeout=RECV_TIMEOUT):
        """读取应答，返回 bytes（至少 4 字节）或 None"""
        self.sock.settimeout(0.2)
        buf = b""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                chunk = self.sock.recv(256)
            except socket.timeout:
                if buf:
                    return buf
                continue
            except OSError:
                return None
            if not chunk:  # 对端关闭连接
                return None
            buf += chunk
            if len(buf) >= 4:
                # 应答通常 4 字节；状态查询可能 6 字节，多等一拍
                if buf.startswith((ACK, NACK, ERR)):
                    return buf
                time.sleep(0.12)
                try:
                    self.sock.settimeout(0.15)
                    more = self.sock.recv(256)
                    if more:
                        buf += more
                except OSError:
                    pass
                return buf
        return buf if buf else None

    def _tcp_connect(self, timeout=3):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect((self.ip, self.port))
        self.sock = s

    def _open_channel(self):
        """TCP 已连上后发送 CRAT 打开 AMP 通道"""
        time.sleep(0.25)
        self._ensure_gap()
        self.sock.sendall(CHAN_OPEN)
        self.last_send = time.time()
        ans = self._read_answer()
        # B 单元怪癖：CRAT 可能不回 1001，而直接回 1112xx 状态帧，语义等同通道已开
        # （只认 1112 前缀，不能放通任意 "11" 开头，否则会误吞 1111 NACK）
        status_ack = bool(ans and ans[:4] == b"1112" and len(ans) >= 6)
        self.channel_open = bool(ans and ans[:4] == ACK) or status_ack
        if not self.channel_open:
            raise RuntimeError(f"CRAT 开通道失败: {ans!r}" if ans else "CRAT 无应答")
        if status_ack and len(ans) >= 2:
            tail = ans[-2:].decode("ascii", "ignore")
            if tail.isdigit():
                self.state = tail
                self.log(f"[{self.name}] 以状态帧 {ans!r} 确认通道(B 单元)")
        return ans

    # ---------- 对外 ----------
    def connect(self, ip, port=DEFAULT_PORT):
        """完整连接：TCP + 开通道。返回 (ok, msg)"""
        with self.lock:
            self.disconnect_locked()
            self.ip, self.port = ip, port
            try:
                self._tcp_connect()
                self._open_channel()
                return True, f"[{self.name}] 已连接 {ip}:{port}，AMP 通道就绪"
            except Exception as e:
                self.disconnect_locked()
                return False, f"[{self.name}] 连接 {ip}:{port} 失败: {e}"

    def disconnect_locked(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        self.sock = None
        self.channel_open = False

    def disconnect(self):
        with self.lock:
            self.disconnect_locked()

    @property
    def connected(self):
        return self.sock is not None and self.channel_open

    def _drain(self):
        """排空接收缓冲区中的陈旧应答/状态推送"""
        try:
            self.sock.setblocking(False)
            for _ in range(16):
                if not self.sock.recv(256):
                    break
        except OSError:
            pass
        finally:
            self.sock.settimeout(0.2)

    def _send_frame_once(self, frame):
        """发送一帧并读应答（调用方持锁）。返回 (ok, answer)"""
        self._ensure_gap()
        self._drain()
        self.sock.sendall(frame)
        self.last_send = time.time()
        ans = self._read_answer()
        if ans is None:
            return False, None
        # 1001 ACK、11xxxx 状态帧均为有效应答；仅 NACK/ERROR 判失败
        if ans[:4] in (NACK, ERR):
            return False, ans
        return True, ans

    def send_frame(self, frame, allow_reconnect=True):
        """对外发帧：失败时自动重连并重发一次（模式切换会导致设备断链）"""
        with self.lock:
            if self.sock is None:
                if not allow_reconnect:
                    return False, None, "未连接"
                ok, msg = self.connect(self.ip, self.port)
                if not ok:
                    return False, None, msg
            try:
                ok, ans = self._send_frame_once(frame)
                if ok:
                    return True, ans, ""
                # 链路异常（对端断开）→ 重连一次再发
                if ans is None and allow_reconnect:
                    self.log(f"[{self.name}] 连接被设备断开，自动重连…", False)
                    ok2, msg2 = self.connect(self.ip, self.port)
                    if ok2:
                        ok, ans = self._send_frame_once(frame)
                        if ok:
                            return True, ans, ""
                    return False, ans, f"重连后仍失败 ({msg2})"
                return False, ans, self._explain(ans)
            except (OSError, BrokenPipeError) as e:
                self.disconnect_locked()
                if allow_reconnect:
                    self.log(f"[{self.name}] 链路异常({e})，自动重连…", False)
                    ok2, msg2 = self.connect(self.ip, self.port)
                    if ok2:
                        ok, ans = self._send_frame_once(frame)
                        if ok:
                            return True, ans, ""
                    return False, None, f"重连失败: {msg2}"
                return False, None, str(e)

    @staticmethod
    def _explain(ans):
        if ans is None:
            return "无应答"
        if ans[:4] == NACK:
            return f"设备拒绝 NACK: {ans!r}"
        if ans[:4] == ERR:
            return f"设备错误 ERROR: {ans!r}"
        return f"未知应答: {ans!r}"

    def send_vtr(self, code4):
        """发送 4 位 VTR 命令码，如 2000/2001/2002"""
        frame = f"CMDS{len(code4):04d}{code4}\n".encode("ascii")
        ok, ans, err = self.send_frame(frame)
        # 1001=ACK；部分固件直接回 11xx 状态帧，语义同为已接受
        if ok and ans and (ans[:4] == ACK or ans[:2] == b"11"):
            return True, f"已执行 {code4}，应答 {ans!r}"
        return False, err or self._explain(ans)

    def query_state(self):
        """查询状态，返回 (ok, state_text, raw)"""
        ok, ans, err = self.send_frame(CHAN_QUERY)
        if not ok:
            return False, err or "查询失败", b""
        # 形如 111200：取末 2 位
        tail = ans[-2:].decode("ascii", errors="replace")
        self.state = tail
        return True, STATE_MAP.get(tail, f"状态码 {tail}"), ans

    def close(self):
        """礼貌关闭 AMP 通道后断开"""
        with self.lock:
            if self.sock:
                try:
                    self._ensure_gap()
                    self.sock.sendall(CHAN_CLOSE)
                except Exception:
                    pass
            self.disconnect_locked()


class ChannelPanel(ttk.LabelFrame):
    """单通道(A/B)控制面板"""

    def __init__(self, master, channel, amp, app, **kwargs):
        super().__init__(master, text=f" 录制单元 {channel} ", **kwargs)
        self.channel = channel
        self.amp = amp
        self.app = app

        top = ttk.Frame(self)
        top.pack(fill="x", padx=8, pady=(6, 2))
        self.conn_var = tk.StringVar(value="● 未连接")
        self.conn_label = ttk.Label(top, textvariable=self.conn_var, foreground="gray")
        self.conn_label.pack(side="left")
        self.state_var = tk.StringVar(value="状态: -")
        ttk.Label(top, textvariable=self.state_var).pack(side="right")

        # 传输控制
        transport = ttk.Frame(self)
        transport.pack(fill="x", padx=8, pady=4)
        ttk.Button(transport, text="● 录制", width=10,
                   command=lambda: self.app.run_async(self.do_record)).grid(row=0, column=0, padx=3, pady=3)
        ttk.Button(transport, text="■ 停止", width=10,
                   command=lambda: self.app.run_async(self.do_stop)).grid(row=0, column=1, padx=3, pady=3)
        ttk.Button(transport, text="▶ 播放", width=10,
                   command=lambda: self.app.run_async(self.do_play)).grid(row=0, column=2, padx=3, pady=3)
        ttk.Button(transport, text="查询状态", width=10,
                   command=lambda: self.app.run_async(self.do_query)).grid(row=1, column=0, padx=3, pady=3)
        ttk.Button(transport, text="连接", width=10,
                   command=lambda: self.app.run_async(self.do_connect)).grid(row=1, column=1, padx=3, pady=3)
        ttk.Button(transport, text="断开", width=10,
                   command=self.do_disconnect).grid(row=1, column=2, padx=3, pady=3)

    def refresh_conn(self):
        if self.amp.connected:
            self.conn_var.set(f"● 已连接 {self.amp.ip}")
            self.conn_label.config(foreground="green")
        else:
            self.conn_var.set("● 未连接")
            self.conn_label.config(foreground="gray")

    def do_connect(self):
        ip = self.app.ip_a.get().strip() if self.channel == "A" else self.app.ip_b.get().strip()
        port = self.app.port
        self.app.log(f"正在连接单元 {self.channel}: {ip}:{port} …", True)
        ok, msg = self.amp.connect(ip, port)
        self.app.log(msg, ok)
        self.app.root.after(0, self.refresh_conn)
        if ok:
            self.do_query()

    def do_disconnect(self):
        self.app.run_async(self._disconnect_worker)

    def _disconnect_worker(self):
        self.amp.close()
        self.app.log(f"[{self.channel}] 已断开", True)
        self.app.root.after(0, self.refresh_conn)
        self.app.root.after(0, lambda: self.state_var.set("状态: -"))

    def _vtr(self, code4, label):
        if not self.amp.connected:
            self.app.log(f"[{self.channel}] 未连接，{label} 未发送", False)
            return
        ok, msg = self.amp.send_vtr(code4)
        self.app.log(f"[{self.channel}] {label}({code4}) → {msg}", ok)
        if ok:
            self.app.root.after(
                800, lambda: self.app.run_async(self.do_query, quiet=True))

    def do_record(self):
        self._vtr("2002", "录制")

    def do_stop(self):
        self._vtr("2000", "停止")

    def do_play(self):
        self._vtr("2001", "播放")

    def do_query(self, quiet=False):
        if not self.amp.connected:
            return
        ok, text, raw = self.amp.query_state()
        if ok:
            self.app.root.after(0, lambda: self.state_var.set(f"状态: {text} ({self.amp.state})"))
            if not quiet:
                self.app.log(f"[{self.channel}] 状态: {text}  原始: {raw!r}", True)
        elif not quiet:
            self.app.log(f"[{self.channel}] 状态查询失败: {text}", False)


class App:
    def __init__(self, root):
        self.root = root
        self.root.title("Atomos Shogun Studio AMP 控制台 (TCP 3811)")
        self.root.geometry("900x720")
        self.root.minsize(780, 640)

        self.amp_a = AMPChannel("A", self.log_threadsafe)
        self.amp_b = AMPChannel("B", self.log_threadsafe)
        self._scanning = False
        self.scan_cancel = threading.Event()

        self._build_ui()

    def _build_ui(self):
        # ===== 网络连接 =====
        conn = ttk.LabelFrame(self.root, text=" 网络连接 (AMP / TCP 3811) ")
        conn.pack(fill="x", padx=10, pady=8)

        ttk.Label(conn, text="单元A IP:").grid(row=0, column=0, padx=(8, 3), pady=8)
        self.ip_a = tk.StringVar(value=DEFAULT_IP_A)
        ttk.Entry(conn, textvariable=self.ip_a, width=15).grid(row=0, column=1)

        ttk.Label(conn, text="单元B IP:").grid(row=0, column=2, padx=(8, 3))
        self.ip_b = tk.StringVar(value=DEFAULT_IP_B)
        ttk.Entry(conn, textvariable=self.ip_b, width=15).grid(row=0, column=3)

        ttk.Label(conn, text="端口:").grid(row=0, column=4, padx=(8, 3))
        self.port_var = tk.StringVar(value=str(DEFAULT_PORT))
        ttk.Entry(conn, textvariable=self.port_var, width=7).grid(row=0, column=5)

        self.btn_scan = ttk.Button(conn, text="🔍 全自动扫描", command=self.start_scan)
        self.btn_scan.grid(row=0, column=6, padx=6)
        self.btn_connect_all = ttk.Button(conn, text="全部连接", command=self.connect_all)
        self.btn_connect_all.grid(row=0, column=7, padx=3)
        ttk.Button(conn, text="全部断开", command=self.disconnect_all).grid(row=0, column=8, padx=(3, 8))

        scan_row = ttk.Frame(conn)
        scan_row.grid(row=1, column=0, columnspan=9, sticky="ew", padx=8, pady=(0, 8))
        ttk.Label(scan_row, text="发现的 AMP 设备:").pack(side="left")
        self.devices_var = tk.StringVar()
        self.devices_combo = ttk.Combobox(scan_row, textvariable=self.devices_var,
                                          state="readonly", width=40)
        self.devices_combo.pack(side="left", fill="x", expand=True, padx=6)
        self.devices_combo.bind("<<ComboboxSelected>>", self.on_device_selected)
        self.scan_status = ttk.Label(scan_row, text="", foreground="blue")
        self.scan_status.pack(side="left", padx=6)

        # ===== 双通道 =====
        dual = ttk.Frame(self.root)
        dual.pack(fill="x", padx=10, pady=4)
        self.panel_a = ChannelPanel(dual, "A", self.amp_a, self)
        self.panel_a.pack(side="left", fill="both", expand=True, padx=(0, 5))
        self.panel_b = ChannelPanel(dual, "B", self.amp_b, self)
        self.panel_b.pack(side="left", fill="both", expand=True, padx=(5, 0))

        # ===== 全局控制 =====
        gf = ttk.LabelFrame(self.root, text=" 全局控制 (A+B 同时) ")
        gf.pack(fill="x", padx=10, pady=6)
        g = ttk.Frame(gf)
        g.pack(fill="x", padx=8, pady=6)
        ttk.Button(g, text="● 全部录制", width=14,
                   command=lambda: self.run_async(self.all_record)).pack(side="left", padx=4)
        ttk.Button(g, text="■ 全部停止", width=14,
                   command=lambda: self.run_async(self.all_stop)).pack(side="left", padx=4)
        ttk.Button(g, text="▶ 全部播放", width=14,
                   command=lambda: self.run_async(self.all_play)).pack(side="left", padx=4)
        ttk.Button(g, text="刷新全部状态", width=14,
                   command=lambda: self.run_async(self.query_all)).pack(side="left", padx=4)

        # ===== 原始命令台 =====
        cf = ttk.LabelFrame(self.root, text=" AMP 原始命令控制台 ")
        cf.pack(fill="both", expand=True, padx=10, pady=8)

        row = ttk.Frame(cf)
        row.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Label(row, text="目标:").pack(side="left")
        self.target_var = tk.StringVar(value="A")
        ttk.Combobox(row, textvariable=self.target_var, values=["A", "B", "A+B"],
                     state="readonly", width=5).pack(side="left", padx=4)
        ttk.Label(row, text="原始帧(自动补换行):").pack(side="left")
        self.cmd_var = tk.StringVar()
        ent = ttk.Entry(row, textvariable=self.cmd_var)
        ent.pack(side="left", fill="x", expand=True, padx=6)
        ent.bind("<Return>", lambda e: self.run_async(self.send_raw))
        ttk.Button(row, text="发送", width=8,
                   command=lambda: self.run_async(self.send_raw)).pack(side="left", padx=3)

        q = ttk.Frame(cf)
        q.pack(fill="x", padx=8, pady=2)
        ttk.Label(q, text="VTR快捷:").pack(side="left")
        for label, (code, _st) in VTR_COMMANDS.items():
            ttk.Button(q, text=label, width=14,
                       command=lambda c=code: self.send_vtr_quick(c)
                       ).pack(side="left", padx=2)
        ttk.Button(q, text="REAQ状态", width=10,
                   command=lambda: self.run_async(self.query_selected)).pack(side="left", padx=6)
        ttk.Button(q, text="清空日志", width=9, command=self.clear_log).pack(side="right")

        self.log_text = scrolledtext.ScrolledText(cf, height=12, state="disabled",
                                                  font=("Menlo", 11))
        self.log_text.pack(fill="both", expand=True, padx=8, pady=6)

        self.log(f"默认: A={DEFAULT_IP_A}  B={DEFAULT_IP_B}  端口 {DEFAULT_PORT}", True)
        self.log("协议: CRAT 开通道 → CMDS+VTR码(2000停/2001放/2002录) → 1001 ACK", True)

    # ---------- 工具 ----------
    @property
    def port(self):
        try:
            return int(self.port_var.get().strip())
        except ValueError:
            return DEFAULT_PORT

    def run_async(self, fn, *args, **kwargs):
        threading.Thread(target=fn, args=args, kwargs=kwargs, daemon=True).start()

    def log_threadsafe(self, msg, ok=True):
        self.root.after(0, lambda: self.log(msg, ok))

    def log(self, msg, ok=True):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        self.log_text.config(state="normal")
        self.log_text.insert("end", f"[{ts}] {msg}\n", "ok" if ok else "err")
        self.log_text.see("end")
        self.log_text.config(state="disabled")

    def clear_log(self):
        self.log_text.config(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.config(state="disabled")

    def _target_amps(self):
        t = self.target_var.get()
        if t == "A":
            return [self.amp_a]
        if t == "B":
            return [self.amp_b]
        return [self.amp_a, self.amp_b]

    # ---------- 连接管理 ----------
    def connect_all(self):
        self.run_async(self._connect_all_worker)

    def _connect_all_worker(self):
        port = self.port
        for amp, ip in ((self.amp_a, self.ip_a.get().strip()),
                        (self.amp_b, self.ip_b.get().strip())):
            ok, msg = amp.connect(ip, port)
            self.log(msg, ok)
            self.root.after(0, self.panel_a.refresh_conn if amp is self.amp_a
                            else self.panel_b.refresh_conn)
        self.query_all()

    def disconnect_all(self):
        def _w():
            for amp, panel in ((self.amp_a, self.panel_a), (self.amp_b, self.panel_b)):
                amp.close()
                self.root.after(0, panel.refresh_conn)
            self.log("A/B 均已断开", True)
        self.run_async(_w)

    # ---------- 全局命令 ----------
    def _all_vtr(self, code4, label):
        for amp in (self.amp_a, self.amp_b):
            if not amp.connected:
                self.log(f"[{amp.name}] 未连接，跳过 {label}", False)
                continue
            ok, msg = amp.send_vtr(code4)
            self.log(f"[{amp.name}] {label} → {msg}", ok)
        self.root.after(900, lambda: self.run_async(self.query_all))

    def all_record(self):
        self._all_vtr("2002", "全部录制")

    def all_stop(self):
        self._all_vtr("2000", "全部停止")

    def all_play(self):
        self._all_vtr("2001", "全部播放")

    def query_all(self):
        for amp, panel in ((self.amp_a, self.panel_a), (self.amp_b, self.panel_b)):
            if amp.connected:
                ok, text, raw = amp.query_state()
                if ok:
                    self.root.after(0,
                        lambda p=panel, t=text, a=amp: p.state_var.set(f"状态: {t} ({a.state})"))
                else:
                    self.log(f"[{amp.name}] 状态查询失败: {text}", False)

    # ---------- 选中目标命令 ----------
    def send_vtr_quick(self, code):
        self.cmd_var.set(f"CMDS0004{code}")
        self.run_async(self.send_raw)

    def query_selected(self):
        for amp in self._target_amps():
            if amp.connected:
                ok, text, _raw = amp.query_state()
                self.log(f"[{amp.name}] 状态: {text}", ok)

    def send_raw(self):
        raw = self.cmd_var.get().strip()
        if not raw:
            return
        # 4 位纯数字/十六进制 → 自动封装为 CMDS
        if len(raw) == 4 and all(c in "0123456789abcdefABCDEF" for c in raw):
            frame = f"CMDS{len(raw):04d}{raw}\n".encode("ascii")
        else:
            frame = (raw if raw.endswith("\n") else raw + "\n").encode("ascii")
        for amp in self._target_amps():
            if not amp.connected:
                self.log(f"[{amp.name}] 未连接，帧未发送: {raw}", False)
                continue
            ok, ans, err = amp.send_frame(frame)
            if ok:
                self.log(f"[{amp.name}] >>> {raw}  <<< {ans!r}", True)
            else:
                self.log(f"[{amp.name}] >>> {raw}  失败: {err or ans!r}", False)

    # ---------- 扫描 ----------
    def start_scan(self):
        # 扫描中点按钮 = 请求停止
        if getattr(self, "_scanning", False):
            self.scan_cancel.set()
            self.scan_status.config(text="正在停止…", foreground="orange")
            return

        self._scanning = True
        self.scan_cancel = threading.Event()
        self.btn_scan_state(True)
        self.devices_combo["values"] = []
        self.devices_var.set("")
        self.scan_status.config(text="全自动扫描中…", foreground="blue")
        self.run_async(self._scan_worker)

    def _probe_amp(self, tip, port):
        """探测单个 IP 的 AMP 3811 服务，CRAT 握手确认"""
        s = socket.socket()
        s.settimeout(0.4)
        try:
            if s.connect_ex((tip, port)) != 0:
                return None
            try:
                s.sendall(CHAN_OPEN)
                s.settimeout(1.0)
                r = s.recv(32)
                if r[:4] == ACK:
                    return (tip, "AMP ACK 1001")
            except OSError:
                pass
            return (tip, "端口开放(非AMP应答)")
        finally:
            s.close()

    def _scan_pool(self, targets, port, found, found_ips, label):
        """并发扫描一批目标，实时回报进度与发现"""
        total = len(targets)
        done = 0
        self.log(f"[{label}] {total} 个地址…", True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=600) as pool:
            futures = {pool.submit(self._probe_amp, ip, port): ip for ip in targets}
            try:
                for fut in concurrent.futures.as_completed(futures):
                    done += 1
                    if done % 100 == 0 or done == total:
                        self.root.after(
                            0, self.scan_status.config,
                            {"text": f"[{label}] {done}/{total}，已发现 {len(found)} 台"})
                    if self.scan_cancel.is_set():
                        for f in futures:
                            f.cancel()
                        break
                    r = fut.result()
                    if r and r[0] not in found_ips:
                        found_ips.add(r[0])
                        found.append(r)
                        self.log(f"  ✦ 发现设备 {r[0]}  {r[1]}", True)
                    # 已凑齐双单元，无需继续
                    if len(found) >= 2:
                        self.scan_cancel.set()
                        break
            finally:
                pass
        return not self.scan_cancel.is_set()

    def _scan_worker(self):
        port = self.port
        found = []
        found_ips = set()
        local = {ip for _, ip, _ in get_all_interface_ips()}
        t0 = time.time()
        try:
            # 阶段 0：ARP 表已有邻居（秒出，最可靠的二层证据）
            arp_ips = get_arp_neighbors()
            arp_ips = [ip for ip in arp_ips if ip not in local]
            if arp_ips:
                self.log(f"[ARP] 缓存中有 {len(arp_ips)} 个邻居，优先探测: {arp_ips}", True)
                self._scan_pool(sorted(arp_ips, key=lambda x: tuple(map(int, x.split('.')))),
                                port, found, found_ips, "ARP邻居")

            # 阶段 1：本机 /24 + 出厂常见网段（高并发约 5~10 秒）
            if len(found) < 2 and not self.scan_cancel.is_set():
                targets, local_ips = build_stage1_targets()
                targets = [t for t in targets if t not in found_ips and t not in local_ips]
                self._scan_pool(targets, port, found, found_ips, "快速网段")

            # 阶段 2：仍没找到且本机是 /16 等大掩码，展开整个大网段兜底
            if not found and not self.scan_cancel.is_set():
                wide = build_stage2_targets()
                wide = [t for t in wide if t not in found_ips and t not in local]
                if wide:
                    self.log(f"快速网段无结果，自动扩展到大网段 {len(wide)} 个地址…", True)
                    self.scan_cancel = threading.Event()  # 清掉“凑齐2台”的提前停止标记
                    self._scan_pool(wide, port, found, found_ips, "大网段兜底")
        except Exception as e:
            self.log(f"扫描线程异常: {e!r}", False)
        self.log(f"扫描结束，耗时 {time.time()-t0:.0f}s", True)
        self.root.after(0, self._scan_done, found)

    def btn_scan_state(self, scanning):
        self.btn_scan.config(
            state="normal",
            text="⏹ 停止扫描" if scanning else "🔍 全自动扫描")

    def _scan_done(self, found):
        self._scanning = False
        self.btn_scan_state(False)
        if self.scan_cancel.is_set() and not found:
            self.scan_status.config(text="已停止", foreground="orange")
            self.log("扫描被手动停止", False)
            return
        if not found:
            self.scan_status.config(text="未发现 AMP 设备", foreground="red")
            self.log("未发现设备。请确认: 1.设备开机 2.网线接好 3.设备 AMP 服务正常", False)
            return
        found.sort(key=lambda x: tuple(int(p) for p in x[0].split(".")))
        vals = [f"{ip}  {info}" for ip, info in found]
        self.devices_combo["values"] = vals
        self.scan_status.config(text=f"发现 {len(found)} 台", foreground="green")
        self.log(f"共发现 {len(found)} 台 AMP 设备:", True)
        for ip, info in found:
            self.log(f"  → {ip}  {info}", True)
        # 自动按顺序填入 A/B
        if len(found) >= 1:
            self.ip_a.set(found[0][0])
        if len(found) >= 2:
            self.ip_b.set(found[1][0])

    def on_device_selected(self, event=None):
        sel = self.devices_var.get()
        if sel:
            self.log(f"已选中: {sel}", True)


def main():
    root = tk.Tk()
    style = ttk.Style()
    if "aqua" in style.theme_names():
        style.theme_use("aqua")
    app = App(root)
    app.log_text.tag_config("ok", foreground="black")
    app.log_text.tag_config("err", foreground="red")
    root.mainloop()


if __name__ == "__main__":
    main()
