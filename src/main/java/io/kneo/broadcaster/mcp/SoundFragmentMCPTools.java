package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.mcp.MCPBrandResponse;
import io.kneo.broadcaster.dto.mcp.MCPSearchResponse;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class SoundFragmentMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentMCPTools.class);

    @Inject
    SoundFragmentService service;
    
    @Inject
    UserService userService;
    
    @Tool("get_brand_soundfragments")
    @Description("Get sound fragments available for a specific brand")
    public CompletableFuture<MCPBrandResponse> getBrandSoundFragments(
        @Parameter("brand") String brandName,
        @Parameter("page") Optional<Integer> page,
        @Parameter("size") Optional<Integer> size
    ) {
        int pageNum = page.orElse(1);
        int pageSize = size.orElse(10);
        
        return getCurrentUser()
            .chain(user -> {
                int offset = (pageNum - 1) * pageSize;
                return Uni.combine().all().unis(
                    service.getBrandSoundFragments(brandName, pageSize, offset),
                    service.getCountBrandSoundFragments(brandName, user)
                ).asTuple();
            })
            .<MCPBrandResponse>map(tuple -> {
                List<BrandSoundFragmentDTO> fragments = tuple.getItem1();
                long totalCount = tuple.getItem2();
                int maxPage = RuntimeUtil.countMaxPage(totalCount, pageSize);
                
                return new MCPBrandResponse(fragments, totalCount, pageNum, maxPage);
            })
            .convert().toCompletableFuture();
    }
    
    @Tool("search_soundfragments") 
    @Description("Search sound fragments by query term")
    public CompletableFuture<MCPSearchResponse> searchSoundFragments(
        @Parameter("query") String searchTerm,
        @Parameter("page") Optional<Integer> page,
        @Parameter("size") Optional<Integer> size
    ) {
        try {
            LOGGER.info("MCP Tool: searchSoundFragments called with query='{}', page={}, size={}",
                searchTerm, page.orElse(1), size.orElse(10));

            int pageNum = page.orElse(1);
            int pageSize = size.orElse(10);

            // Validate search term
            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                LOGGER.error("MCP Tool: Empty search term provided");
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Search term 'query' parameter is required and cannot be empty")
                );
            }

            return getCurrentUser()
                .chain(user -> {
                    LOGGER.info("MCP Tool: Got user context: {}", user.getClass().getSimpleName());
                    // Calculate offset for pagination
                    int offset = (pageNum - 1) * pageSize;

                    // Get both search results and count in parallel
                    return Uni.combine().all().unis(
                        service.getSearchCount(searchTerm, user),
                        service.search(searchTerm, pageSize, offset, user)
                    ).asTuple();
                })
                .map(tuple -> {
                    long totalCount = tuple.getItem1();
                    List<SoundFragmentDTO> fragments = tuple.getItem2();
                    int maxPage = RuntimeUtil.countMaxPage(totalCount, pageSize);

                    LOGGER.info("MCP Tool: Search completed. Found {} fragments, total count: {}",
                        fragments.size(), totalCount);

                    return new MCPSearchResponse(fragments, totalCount, pageNum, maxPage);
                })
                .onFailure().invoke(throwable -> {
                    LOGGER.error("MCP Tool: Search failed", throwable);
                })
                .convert().toCompletableFuture();
        } catch (Exception e) {
            LOGGER.error("MCP Tool: Exception in searchSoundFragments", e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Get the current user context for MCP tools.
     * For MCP tools, we need to handle user context differently since there's no HTTP request.
     * This implementation creates a SuperUser context similar to how AiHelperController works.
     */
    private Uni<IUser> getCurrentUser() {
        // Create a SuperUser context for MCP tools, similar to how other services use SuperUser.build()
        // This ensures MCP tools have the necessary permissions to access sound fragments
        return Uni.createFrom().item(io.kneo.core.model.user.SuperUser.build());
    }
}
