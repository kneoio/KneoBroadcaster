package io.kneo.broadcaster.controller.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Deprecated
public class KeySet {
    public static final int DEFAULT_INCREMENT_STEP = 1;
    public static final int DEFAULT_START_VALUE = 0;
    public static final int MAX_WINDOW_SIZE = 4;
    public static final int MIN_WINDOW_SIZE = 1;

    private final AtomicInteger[] window;
    private final int windowSize;
    private final int incrementStep;

    public KeySet(int windowSize) {
        this.windowSize = clampWindowSize(windowSize);
        this.incrementStep = DEFAULT_INCREMENT_STEP;
        this.window = new AtomicInteger[this.windowSize];
        initializeWindow(DEFAULT_START_VALUE);
    }

    private int clampWindowSize(int size) {
        return Math.min(Math.max(size, MIN_WINDOW_SIZE), MAX_WINDOW_SIZE);
    }

    private void initializeWindow(long startValue) {
        for (int i = 0; i < windowSize; i++) {
            window[i] = new AtomicInteger((int) (startValue + (i * (windowSize > 1 ? DEFAULT_INCREMENT_STEP : 0))));
        }
    }

    public synchronized void slide() {
        if (windowSize == 1) {
            window[0].addAndGet(incrementStep);
            return;
        }
        for (int i = 0; i < windowSize - 1; i++) {
            window[i].set(window[i + 1].get());
        }
        window[windowSize - 1].addAndGet(incrementStep);
    }

    public int current() {
        return window[0].get();
    }

    public int next() {
        return windowSize > 1 ? window[1].get() : window[0].get();
    }

    public List<Integer> getCurrentWindowKeys() {
        List<Integer> keys = new ArrayList<>(windowSize);
        for (AtomicInteger atomicInteger : window) {
            keys.add(atomicInteger.get());
        }
        return keys;
    }
}