package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.model.live.LiveSoundFragment;
import io.kneo.broadcaster.service.QueueService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@QuarkusTest
class QueueControllerTest {

    @InjectMock
    QueueService queueService;

    private final String BRAND = "testBrand";
    private final String BASE_PATH = "/" + BRAND + "/queue";

    @Test
    public void testGetQueue() {
        List<LiveSoundFragment> mockItems = new ArrayList<>();
        LiveSoundFragment item = new LiveSoundFragment();
        item.setSoundFragmentId(UUID.randomUUID());
        mockItems.add(item);


    }



}