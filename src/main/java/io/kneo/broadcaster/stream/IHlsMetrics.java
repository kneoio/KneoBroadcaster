package io.kneo.broadcaster.stream;

public interface IHlsMetrics {
    int getSegmentCount();
    long getTotalBytesProcessed();
    int getTotalSegmentsCreated();
    double getAverageSegmentSize();
    double getAverageBitrate();
}
