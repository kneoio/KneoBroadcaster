package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.mcp.AddToQueueMcpDTO;
import io.kneo.broadcaster.dto.queue.IntroPlusSong;
import io.kneo.broadcaster.service.QueueService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.List;
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
            @Parameter("songId") String songId,
            @Parameter("filePaths") List<String> filePaths,
            @Parameter("priority") Integer priority
    ) {
        IntroPlusSong mergingMethod = new IntroPlusSong();
        mergingMethod.setSoundFragmentUUID(UUID.fromString(songId));

        if (filePaths != null && !filePaths.isEmpty()) {
            mergingMethod.setFilePath(Paths.get(filePaths.get(0)));
        }

        AddToQueueMcpDTO dto = new AddToQueueMcpDTO();
        dto.setBrandName(brand);
        dto.setMergingMethod(mergingMethod);
        dto.setPriority(priority);

        return service.addToQueue(brand, dto)
                .replaceWith(true)
                .convert().toCompletableFuture();
    }
}