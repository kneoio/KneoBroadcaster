package io.kneo.broadcaster.util;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class Randomizator {
    private final ConcurrentHashMap<String, AtomicBoolean> lastTwo = new ConcurrentHashMap<>();

    public int decideFragmentCount(String slug) {
        var flag = lastTwo.computeIfAbsent(slug, s -> new AtomicBoolean(false));

        if (flag.getAndSet(false)) return 1;

        boolean pickTwo = ThreadLocalRandom.current().nextDouble() < 0.3;
        flag.set(pickTwo);
        return pickTwo ? 2 : 1;
    }
}
