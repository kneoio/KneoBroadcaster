package io.kneo.broadcaster.controller;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.service.QueueService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

@QuarkusTest
class QueueControllerTest {

    @InjectMock
    QueueService queueService;

    private final String BRAND = "testBrand";
    private final String BASE_PATH = "/" + BRAND + "/queue";

    @Test
    public void testGetQueue() {
        List<SoundFragmentDTO> mockItems = new ArrayList<>();
        SoundFragmentDTO item = new SoundFragmentDTO();
        item.setId(UUID.randomUUID());
        mockItems.add(item);

        when(queueService.getQueueForBrand(BRAND)).thenReturn(Uni.createFrom().item(mockItems));
        given()
                .when().get(BASE_PATH)
                .then()
                .statusCode(200);
    }



}