package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.service.QueueService;
import io.kneo.broadcaster.service.manipulation.mixing.MergingType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class QueueMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueMCPTools.class);

    @Inject
    QueueService service;

    @Tool("add_to_queue")
    @Description("Add a song to the queue for a specific brand")
    public CompletableFuture<Boolean> addToQueue(
            @Parameter("brand") String brand,
            @Parameter("mergingMethod") MergingType mergingMethod,
            @Parameter("songIds") Map<String, UUID> songIds,
            @Parameter("filePaths") Map<String, String> filePaths,
            @Parameter("priority") Integer priority
    ) {
        AddToQueueMcpDTO dto = new AddToQueueMcpDTO();
        dto.setMergingMethod(mergingMethod);
        dto.setPriority(priority);
        dto.setSoundFragments(songIds);
        dto.setFilePaths(filePaths);

        return service.addToQueue(brand, dto)
                .replaceWith(true)
                .convert().toCompletableFuture();
    }
}