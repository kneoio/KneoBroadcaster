package io.kneo.broadcaster.controller.stream;

public interface IHlsMetrics {
    int getSegmentCount();
    long getTotalBytesProcessed();
    int getTotalSegmentsCreated();
    double getAverageSegmentSize();
    double getAverageBitrate();
}
