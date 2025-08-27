package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.mcp.MemoryMcpDTO;
import io.kneo.broadcaster.service.MemoryService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class MemoryMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryMCPTools.class);

    @Inject
    MemoryService service;

    @Tool("get_memory_by_type")
    @Description("Get memory data by type for a specific brand")
    public CompletableFuture<MemoryMcpDTO> getMemoryByType(
            @Parameter("brand") String brandName,
            @Parameter("types") String... types
    ) {
        return service.getByType(brandName, types)
                .chain(memoryData -> mapToMemoryMcpDTO(brandName, types, memoryData))
                .convert().toCompletableFuture();
    }

    private Uni<MemoryMcpDTO> mapToMemoryMcpDTO(String brandName, String[] types, Object memoryData) {
        return Uni.createFrom().item(
                MemoryMcpDTO.builder()
                        .requestedTypes(List.of(types))
                        .memoryData(Map.of("data", memoryData))
                        .build()
        );
    }
}