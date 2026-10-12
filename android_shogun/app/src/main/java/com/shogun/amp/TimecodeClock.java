package com.shogun.amp;

import java.util.Calendar;
import java.util.Locale;

/**
 * 手机端时间码显示时钟。
 *
 * 重要说明：Shogun Studio 的 AMP 网口协议(TCP 3811)只上报传输状态(停/放/录)，
 * 不提供机内时间码(LTC/内嵌)读取。因此本时钟的时间码并非来自设备：
 *  - REC_RUN：依据 REAQ 状态在“录制开始/停止”之间累加的本地计时（本条录制时长）；
 *  - TOD    ：手机系统时钟（Time of Day），仅在设备与手机时钟一致时才与机内 ToD 吻合。
 *
 * 帧率支持 23.976 / 24 / 25 / 29.97 丢帧 / 30，时间换算基于真实帧速率。
 */
public class TimecodeClock {

    public enum Fps {
        F23_976("23.976", 24000, 1001, false),
        F24("24", 24, 1, false),
        F25("25", 25, 1, false),
        F29_97_DF("29.97 DF", 30000, 1001, true),
        F30("30", 30, 1, false);

        public final String label;
        public final long num; // 帧速率分子
        public final long den; // 帧速率分母
        public final boolean dropFrame;

        Fps(String label, long num, long den, boolean df) {
            this.label = label;
            this.num = num;
            this.den = den;
            this.dropFrame = df;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private long accNanos;
    private long segStart = -1;

    /** 进入录制状态：开始(或继续)累计 */
    public synchronized void start() {
        if (segStart < 0) segStart = System.nanoTime();
    }

    /** 离开录制状态：冻结当前时长 */
    public synchronized void pause() {
        if (segStart >= 0) {
            accNanos += System.nanoTime() - segStart;
            segStart = -1;
        }
    }

    /** REC RUN 归零（停止状态下也可使用） */
    public synchronized void reset() {
        accNanos = 0;
        if (segStart >= 0) segStart = System.nanoTime();
    }

    private synchronized long elapsedNanos() {
        return accNanos + (segStart >= 0 ? System.nanoTime() - segStart : 0);
    }

    /** REC RUN 时间码 */
    public String formatRecRun(Fps fps) {
        long nanos = elapsedNanos();
        // 整体乘法换算（100 小时量级内不溢出 long）
        long frames = nanos * fps.num / (fps.den * 1_000_000_000L);
        return formatFrames(frames, fps);
    }

    /** TIME OF DAY 时间码（手机时钟，本地时区） */
    public static String formatTod(Fps fps) {
        Calendar c = Calendar.getInstance();
        long ms = ((c.get(Calendar.HOUR_OF_DAY) * 3600L
                + c.get(Calendar.MINUTE) * 60L
                + c.get(Calendar.SECOND)) * 1000L
                + c.get(Calendar.MILLISECOND));
        long frames = ms * fps.num / (fps.den * 1000L);
        return formatFrames(frames, fps);
    }

    /** 帧计数 -> HH:MM:SS:FF（29.97 时为 HH;MM;SS;FF 丢帧显示） */
    private static String formatFrames(long frames, Fps fps) {
        long h, m, s, f;
        char sep = ':';

        if (fps.dropFrame) {
            // SMPTE 丢帧标号换算（每10分钟块补18帧，块内每分钟补2帧），仅 29.97 使用
            long inBlock = frames % 17982L;
            long labeled = frames + 18L * (frames / 17982L) + 2L * (inBlock / 1798L);
            h = (labeled / 108000L) % 24L;
            m = (labeled / 1800L) % 60L;
            s = (labeled / 30L) % 60L;
            f = labeled % 30L;
            sep = ';';
        } else {
            long perSec = fps.num / fps.den;
            long perMin = perSec * 60L;
            long perHour = perMin * 60L;
            h = (frames / perHour) % 24L;
            m = (frames / perMin) % 60L;
            s = (frames / perSec) % 60L;
            f = frames % perSec;
        }
        return String.format(Locale.US, "%02d%c%02d%c%02d%c%02d",
                h, sep, m, sep, s, sep, f);
    }
}
