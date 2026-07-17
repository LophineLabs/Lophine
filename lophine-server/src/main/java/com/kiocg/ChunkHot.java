package com.kiocg;

import java.util.Arrays;

public class ChunkHot {
    // 热度统计总区间数量
    private static final int TIMES_LENGTH = 10;
    // 当前统计区间下标
    private int index = -1;

    // 热度统计区间
    private final long[] times = new long[TIMES_LENGTH];
    // 存放临时的区间数值
    // 用于修正正在统计的当前区间热度没有计入总值的问题
    private long temp;
    // 所有区间的热度总值
    private long total;

    // 用于每个具体统计的计算
    private long nanos;
    // 当前统计是否进行中
    private volatile boolean started = false;

    /**
     * 更新区间下标
     */
    public void nextTick() {
        this.index = ++this.index % TIMES_LENGTH;
    }

    /**
     * 开始统计一个新区间
     */
    public void start() {
        started = true;
        temp = times[this.index];
        times[this.index] = 0L;
    }

    public boolean isStarted() {
        return this.started;
    }

    /**
     * 结束当前区间的统计
     * 将统计值更新入热度总值
     */
    public void stop() {
        started = false;
        total -= temp;
        total += times[this.index];
    }

    /**
     * 开始一个具体统计
     */
    public void startTicking() {
        if (!started) return;
        nanos = System.nanoTime();
    }

    /**
     * 结束一个具体统计
     * 将统计值计入当前热度区间
     */
    public void stopTickingAndCount() {
        if (!started) return;
        // 定义一个具体统计的最大值为 1,000,000
        // 有时候某个具体统计的计算值会在某1刻飙升，可能是由于保存数据到磁盘？
        times[this.index] += Math.min(System.nanoTime() - nanos, 1000000L);
    }

    /**
     * 清空统计 (当区块卸载时)
     */
    public void clear() {
        started = false;
        Arrays.fill(times, 0L);
        temp = 0L;
        total = 0L;
        nanos = 0L;
    }

    /**
     * @return 获取区块热度平均值
     */
    public long getAverage() {
        return total / ((long) TIMES_LENGTH * 20L);
    }
}