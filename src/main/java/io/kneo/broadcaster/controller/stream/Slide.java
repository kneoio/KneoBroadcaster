package io.kneo.broadcaster.controller.stream;

import java.util.List;

public record Slide(long start, long end, List<String> fragmentInfo) { }
