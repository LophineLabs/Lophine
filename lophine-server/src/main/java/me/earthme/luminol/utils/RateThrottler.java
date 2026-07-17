package me.earthme.luminol.utils;

import org.jetbrains.annotations.NotNull;

public class RateThrottler {
    private static final byte STATE_NOT_BEGIN = 0;
    private static final byte STATE_RECORDING = 1;
    private static final byte STATE_DESTROYED = 2;

    private byte status;
    private int recordedThisTick = 0;

    private int totallyTicked = 0;
    private int totalCount = 0;

    private void checkDestroyed() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }
    }

    public void increase() {
        this.recordedThisTick++;
    }

    public void mergeWith(@NotNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        this.totallyTicked += other.totallyTicked;
        this.totalCount += other.totalCount;
    }

    public void splitInto(@NotNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        other.totalCount = this.totalCount;
        other.totallyTicked = this.totallyTicked;
    }

    public double getAvgCount() {
        return (double) this.totalCount / Math.min(this.totallyTicked, 1);
    }

    public int getCountThisTick() {
        return this.recordedThisTick;
    }

    public boolean isOutOfRate(int expected) {
        return this.recordedThisTick >= expected;
    }

    public void destroy() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }

        this.status = STATE_DESTROYED;
    }

    public void begin() {
        this.checkDestroyed();

        if (this.status == STATE_RECORDING) {
            throw new IllegalStateException("Attempt to begin a already recording throttler!");
        }

        this.status = STATE_RECORDING;
    }

    public void done() {
        this.checkDestroyed();

        if (this.status == STATE_NOT_BEGIN) {
            throw new IllegalStateException("Attempt to done a already done or new throttler!");

        }

        this.status = STATE_NOT_BEGIN;

        this.totallyTicked++;
        this.totalCount += this.recordedThisTick;

        this.recordedThisTick = 0;
    }
}
