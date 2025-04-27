import io.kneo.broadcaster.controller.stream.KeySet;
import io.kneo.broadcaster.controller.stream.PlaylistFragmentRange;
import io.kneo.broadcaster.service.stream.SliderTimer;
import io.smallrye.mutiny.subscription.Cancellable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class TimerDrivenKeySetDemo {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final QueuePopulator populator = new QueuePopulator();
    private final Map<Integer, PlaylistFragmentRange> mainQueue = populator.getQueueMap();
    private final KeySet keySet = new KeySet();
    private final AtomicReference<LocalTime> expectedEndTime = new AtomicReference<>();


    public void run() {
        populator.addFragmentToQueue("Initial Song A", 185);
        populator.addFragmentToQueue("Song B", 210);
        populator.addFragmentToQueue("Song C", 150);
        populator.addFragmentToQueue("Song D", 245);

        PlaylistFragmentRange initialRange = mainQueue.get(keySet.current());
        LocalTime startTime = LocalTime.now();
        if (initialRange != null && initialRange.getFragment() != null) {
            int initialDuration = calculateDurationFromSegments(initialRange);
            expectedEndTime.set(startTime.plusSeconds(initialDuration));
            System.out.println(startTime.format(TIME_FORMATTER) + " -> Initial State: keys(cur=" + keySet.current() + ",nxt=" + keySet.next() + "), frag=" + initialRange.getFragment().getTitle() + ", seq=[" + initialRange.getStart() + "-" + initialRange.getEnd() + "]" + ", end=" + expectedEndTime.get().format(TIME_FORMATTER));
        } else {
            expectedEndTime.set(startTime);
            System.out.println(startTime.format(TIME_FORMATTER) + " -> Initial State: keys(cur=" + keySet.current() + ",nxt=" + keySet.next() + "), QUEUE EMPTY!");
        }


        SliderTimer sliderTimer = new SliderTimer();
        final int timerIntervalSeconds = SliderTimer.INTERVAL_SECONDS;

        Cancellable subscription = sliderTimer.getTicker()
                .subscribe().with(
                        tick -> {
                            LocalTime tickTime = LocalTime.now();
                            System.out.println("-------------------------------------");
                            System.out.println(tickTime.format(TIME_FORMATTER) + " -> Tick #" + tick + " received.");

                            LocalTime currentExpectedEnd = expectedEndTime.get();
                            int currentKey = keySet.current();
                            PlaylistFragmentRange currentRange = mainQueue.get(currentKey);

                            if (currentRange == null) {
                                System.out.println(tickTime.format(TIME_FORMATTER) + " -> Skip: No fragment for key " + currentKey);
                            } else if (currentExpectedEnd != null && tickTime.isBefore(currentExpectedEnd)) {
                                System.out.println(tickTime.format(TIME_FORMATTER) + " -> Skip: Frag '" + currentRange.getFragment().getTitle() + "' ends at " + currentExpectedEnd.format(TIME_FORMATTER));
                            } else {
                                System.out.println(tickTime.format(TIME_FORMATTER) + " -> SLIDING (Expected end " + (currentExpectedEnd != null ? currentExpectedEnd.format(TIME_FORMATTER) : "N/A") + " passed)");
                                // Added KeySet current/next keys (using captured currentKey and current keySet.next())
                                System.out.println(tickTime.format(TIME_FORMATTER) + " -> Before: keys(cur=" + currentKey + ",nxt=" + keySet.next() + "), frag=" + currentRange.getFragment().getTitle() + ", seq=[" + currentRange.getStart() + "-" + currentRange.getEnd() + "]");

                                currentRange.setStale(true);

                                keySet.slide(); // KeySet state changes here
                                int newCurrentKey = keySet.current();
                                int newNextKey = keySet.next();

                                PlaylistFragmentRange newCurrentRange = mainQueue.get(newCurrentKey);
                                if (newCurrentRange != null && newCurrentRange.getFragment() != null) {
                                    int duration = calculateDurationFromSegments(newCurrentRange);
                                    LocalTime newExpectedEnd = tickTime.plusSeconds(duration);
                                    expectedEndTime.set(newExpectedEnd);
                                    // Added KeySet current/next keys (using new keys after slide)
                                    System.out.println(tickTime.format(TIME_FORMATTER) + " -> After:  keys(cur=" + newCurrentKey + ",nxt=" + newNextKey + "), frag=" + newCurrentRange.getFragment().getTitle() + ", seq=[" + newCurrentRange.getStart() + "-" + newCurrentRange.getEnd() + "]");
                                    System.out.println(tickTime.format(TIME_FORMATTER) + " -> New End: " + newExpectedEnd.format(TIME_FORMATTER));

                                } else {
                                    // Added KeySet current/next keys (using new keys after slide)
                                    System.out.println(tickTime.format(TIME_FORMATTER) + " -> After:  keys(cur=" + newCurrentKey + ",nxt=" + newNextKey + "), frag=NOT FOUND!");
                                    expectedEndTime.set(tickTime);
                                }
                            }
                            System.out.println("-------------------------------------");
                        },
                        failure -> {
                            System.err.println("Timer subscription failed: " + failure);
                        }
                );

        System.out.println("Timer active. Waiting for ticks (approx every " + timerIntervalSeconds + " seconds)...");
        System.out.println("Press Ctrl+C to stop.");

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main thread interrupted.");
            subscription.cancel();
        }
    }

    private int calculateDurationFromSegments(PlaylistFragmentRange range) {
        if (range == null || range.getSegments() == null || range.getSegments().isEmpty()) {
            return 0;
        }
        return range.getSegments().size();
    }


    public static void main(String[] args) {
        TimerDrivenKeySetDemo demo = new TimerDrivenKeySetDemo();
        demo.run();
    }
}