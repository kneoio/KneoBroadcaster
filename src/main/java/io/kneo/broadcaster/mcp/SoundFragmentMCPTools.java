package io.kneo.broadcaster.mcp;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import io.kneo.broadcaster.dto.SoundFragmentDTO;
import io.kneo.broadcaster.dto.filter.SoundFragmentFilterDTO;
import io.kneo.broadcaster.dto.mcp.MCPBrandResponse;
import io.kneo.broadcaster.dto.mcp.MCPSSoundFragmentResponse;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.cnst.SourceType;
import io.kneo.broadcaster.service.SoundFragmentService;
import io.kneo.broadcaster.util.BrandActivityLogger;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
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
    @Description("Get sound fragments available for a specific brand with optional filtering")
    public CompletableFuture<MCPBrandResponse> getBrandSoundFragments(
            @Parameter("brand") String brandName,
            @Parameter("page") Optional<Integer> page,
            @Parameter("size") Optional<Integer> size,
            @Parameter("genres") Optional<String> genres,
            @Parameter("sources") Optional<String> sources,
            @Parameter("types") Optional<String> types
    ) {
        int pageNum = page.orElse(1);
        int pageSize = size.orElse(10);

        // Parse filters
        SoundFragmentFilterDTO filter = parseFilters(genres, sources, types);
        String filterDesc = filter != null ? "with filters" : "without filters";

        // Log the start of the operation
        BrandActivityLogger.logActivity(brandName, "fragment_query",
                "Fetching page %d with size %d %s", pageNum, pageSize, filterDesc);

        return getCurrentUser()
                .chain(user -> {
                    int offset = (pageNum - 1) * pageSize;
                    return Uni.combine().all().unis(
                            service.getBrandSoundFragments(brandName, pageSize, offset, filter),
                            service.getCountBrandSoundFragments(brandName, user, filter)
                    ).asTuple();
                })
                .<MCPBrandResponse>map(tuple -> {
                    List<BrandSoundFragmentDTO> fragments = tuple.getItem1();
                    long totalCount = tuple.getItem2();
                    int maxPage = RuntimeUtil.countMaxPage(totalCount, pageSize);

                    // Log the results
                    BrandActivityLogger.logActivity(brandName, "fragment_results",
                            "Found %d fragments (page %d of %d) %s",
                            fragments.size(), pageNum, maxPage, filterDesc);

                    if (fragments.isEmpty()) {
                        BrandActivityLogger.logActivity(brandName, "fragment_warning",
                                "No fragments found for this query %s", filterDesc);
                    }

                    return new MCPBrandResponse(fragments, totalCount, pageNum, maxPage);
                })
                .onFailure().invoke(failure -> {
                    BrandActivityLogger.logActivity(brandName, "fragment_error",
                            "Failed to get fragments: %s", failure.getMessage());
                })
                .convert().toCompletableFuture();
    }

    @Tool("search_soundfragments")
    @Description("Search sound fragments by query term with optional filtering")
    public CompletableFuture<MCPSSoundFragmentResponse> searchSoundFragments(
            @Parameter("query") String searchTerm,
            @Parameter("page") Optional<Integer> page,
            @Parameter("size") Optional<Integer> size,
            @Parameter("genres") Optional<String> genres,
            @Parameter("sources") Optional<String> sources,
            @Parameter("types") Optional<String> types
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

            // Parse filters
            SoundFragmentFilterDTO filter = parseFilters(genres, sources, types);
            String filterDesc = filter != null ? "with filters" : "without filters";

            return getCurrentUser()
                    .chain(user -> {
                        LOGGER.info("MCP Tool: Got user context: {} {}", user.getClass().getSimpleName(), filterDesc);
                        // Calculate offset for pagination
                        int offset = (pageNum - 1) * pageSize;

                        // Get both search results and count in parallel
                        return Uni.combine().all().unis(
                                service.getSearchCount(searchTerm, user, filter),
                                service.search(searchTerm, pageSize, offset, user, filter)
                        ).asTuple();
                    })
                    .map(tuple -> {
                        long totalCount = tuple.getItem1();
                        List<SoundFragmentDTO> fragments = tuple.getItem2();
                        int maxPage = RuntimeUtil.countMaxPage(totalCount, pageSize);

                        LOGGER.info("MCP Tool: Search completed {}. Found {} fragments, total count: {}",
                                filterDesc, fragments.size(), totalCount);

                        return new MCPSSoundFragmentResponse(fragments, totalCount, pageNum, maxPage);
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

    @Tool("get_all_soundfragments")
    @Description("Get all sound fragments with optional filtering and pagination")
    public CompletableFuture<MCPSSoundFragmentResponse> getAllSoundFragments(
            @Parameter("page") Optional<Integer> page,
            @Parameter("size") Optional<Integer> size,
            @Parameter("genres") Optional<String> genres,
            @Parameter("sources") Optional<String> sources,
            @Parameter("types") Optional<String> types
    ) {
        try {
            int pageNum = page.orElse(1);
            int pageSize = size.orElse(10);

            // Parse filters
            SoundFragmentFilterDTO filter = parseFilters(genres, sources, types);
            String filterDesc = filter != null ? "with filters" : "without filters";

            LOGGER.info("MCP Tool: getAllSoundFragments called with page={}, size={} {}",
                    pageNum, pageSize, filterDesc);

            return getCurrentUser()
                    .chain(user -> {
                        int offset = (pageNum - 1) * pageSize;

                        // Get both results and count in parallel
                        return Uni.combine().all().unis(
                                service.getAllCount(user, filter),
                                service.getAllDTO(pageSize, offset, user, filter)
                        ).asTuple();
                    })
                    .map(tuple -> {
                        long totalCount = tuple.getItem1();
                        List<SoundFragmentDTO> fragments = tuple.getItem2();
                        int maxPage = RuntimeUtil.countMaxPage(totalCount, pageSize);

                        LOGGER.info("MCP Tool: GetAll completed {}. Found {} fragments, total count: {}",
                                filterDesc, fragments.size(), totalCount);

                        return new MCPSSoundFragmentResponse(fragments, totalCount, pageNum, maxPage);
                    })
                    .onFailure().invoke(throwable -> {
                        LOGGER.error("MCP Tool: GetAll failed", throwable);
                    })
                    .convert().toCompletableFuture();
        } catch (Exception e) {
            LOGGER.error("MCP Tool: Exception in getAllSoundFragments", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Parse filter parameters from MCP tool parameters
     */
    private SoundFragmentFilterDTO parseFilters(Optional<String> genres, Optional<String> sources, Optional<String> types) {
        SoundFragmentFilterDTO filter = new SoundFragmentFilterDTO();
        boolean hasAnyFilter = false;

        // Parse genres
        if (genres.isPresent() && !genres.get().trim().isEmpty()) {
            List<String> genreList = Arrays.asList(genres.get().split(","));
            List<String> trimmedGenres = new ArrayList<>();
            for (String genre : genreList) {
                String trimmed = genre.trim();
                if (!trimmed.isEmpty()) {
                    trimmedGenres.add(trimmed);
                }
            }
            if (!trimmedGenres.isEmpty()) {
                filter.setGenres(trimmedGenres);
                hasAnyFilter = true;
            }
        }

        // Parse sources
        if (sources.isPresent() && !sources.get().trim().isEmpty()) {
            List<String> sourceList = Arrays.asList(sources.get().split(","));
            List<SourceType> sourceTypes = new ArrayList<>();
            for (String source : sourceList) {
                String trimmed = source.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        sourceTypes.add(SourceType.valueOf(trimmed));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("MCP Tool: Invalid source type: {}", trimmed);
                    }
                }
            }
            if (!sourceTypes.isEmpty()) {
                filter.setSources(sourceTypes);
                hasAnyFilter = true;
            }
        }

        // Parse types
        if (types.isPresent() && !types.get().trim().isEmpty()) {
            List<String> typeList = Arrays.asList(types.get().split(","));
            List<PlaylistItemType> playlistTypes = new ArrayList<>();
            for (String type : typeList) {
                String trimmed = type.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        playlistTypes.add(PlaylistItemType.valueOf(trimmed));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("MCP Tool: Invalid playlist item type: {}", trimmed);
                    }
                }
            }
            if (!playlistTypes.isEmpty()) {
                filter.setTypes(playlistTypes);
                hasAnyFilter = true;
            }
        }

        return hasAnyFilter ? filter : null;
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