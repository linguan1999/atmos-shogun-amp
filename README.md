# Atomos Shogun AMP 控制

通过 RJ45 网口 + AMP (Advanced Media Protocol) 远程控制 **Atomos Shogun Studio（初代）** 的双录制单元（A/B），包含桌面端与安卓端两套实现。

## 功能

- 双录制单元独立连接、独立控制（停止 / 播放 / 录制 / 待机 / 弹出）
- 全自动设备扫描：ARP 邻居 → 本机各 /24 + 出厂常见段 → 大掩码兜底，扫到即握手确认
- 状态轮询与自动重连（设备在播放↔录制切换时会主动断开 TCP）
- 命令节流（≥500ms），防止设备协议栈卡死
- 安卓端本地时间码时钟（REC RUN / TOD，支持 23.976 / 24 / 25 / 29.97DF / 30 帧率）

## 组成

| 文件/目录 | 说明 |
|---|---|
| `shogun_amp_control.py` | 桌面控制软件（Python 3 + tkinter，零第三方依赖），双 IP 独立连接 |
| `android_shogun/` | 安卓应用「Shogun AMP 控制台」（原生 Java，零依赖，minSdk 26 / target 35） |
| `ui_preview.html` | 安卓 UI 的网页预览 |
| `更新ShogunAmp-debug.apk` | 安卓调试包 |

## 使用方法

### 桌面端

```bash
python3 shogun_amp_control.py
```

### 安卓端

```bash
cd android_shogun
./gradlew assembleDebug
```

生成的 APK 在 `app/build/outputs/apk/debug/`。

## 网络连接

- Mac / 手机经网口直连设备 RJ45（设备无 Wi-Fi 控制）
- 出厂默认 IP：录制单元 A = `192.168.1.100`，B = `192.168.1.101`
- 控制端建议手动配置 `192.168.x.x/16`，保证能直达设备

## AMP 协议摘要（已实测）

- TCP 端口 **3811**
- 打开通道：`CRAT0007204Vtr1\n` → `1001`(ACK)
- 状态查询：`REAQ0007204Vtr1\n` → `1112xx`（末字节：00 停止 / 01 播放 / 02 录制）
- 控制命令：`CMDS%04d<4位VTR码>\n`，VTR 码：`2000` 停止 / `2001` 播放 / `2002` 录制 / `2003` 待机关 / `2004` 待机开 / `2005` 弹出
- 关闭通道：`STOP0000\n`
- 应答：`1001`=ACK，`1111`=NACK，`2222`=ERROR

### 设备特性（实测）

- 同时只允许一个客户端连接
- B 单元对 CRAT 会回 `1112xx` 状态帧代替 ACK，需同等视为通道就绪
- 播放↔录制切换时设备主动断开 TCP（需自动重连）
- 命令间隔必须 ≥500ms

协议依据：SKAARHOJ DC_ATOMOS_Shogun 文档 + douggillespie/atomos (Java)。
