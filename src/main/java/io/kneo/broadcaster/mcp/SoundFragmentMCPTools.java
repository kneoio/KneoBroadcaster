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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class SoundFragmentMCPTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentMCPTools.class);

    @Inject
    SoundFragmentService service;

    @Inject
    UserService userService;

    @Tool("get_brand_sound_fragments")
    @Description("Get sound fragments available for a specific brand with optional filtering")
    public CompletableFuture<MCPBrandResponse> getBrandSoundFragments(
            @Parameter("brand") String brandName,
            @Parameter("page") Integer page,
            @Parameter("size") Integer size,
            @Parameter("genres") String genres,
            @Parameter("sources") String sources,
            @Parameter("types") String types
    ) {
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;

        SoundFragmentFilterDTO filter = parseFilters(genres, sources, types);
        String filterDesc = filter != null ? "with filters" : "without filters";

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

    @Tool("search_sound_fragments")
    @Description("Search sound fragments by query term with optional filtering")
    public CompletableFuture<MCPSSoundFragmentResponse> searchSoundFragments(
            @Parameter("query") String searchTerm,
            @Parameter("page") Integer page,
            @Parameter("size") Integer size,
            @Parameter("genres") String genres,
            @Parameter("sources") String sources,
            @Parameter("types") String types
    ) {
        try {
            LOGGER.info("MCP Tool: searchSoundFragments called with query='{}', page={}, size={}",
                    searchTerm, page != null ? page : 1, size != null ? size : 10);

            int pageNum = page != null ? page : 1;
            int pageSize = size != null ? size : 10;

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                LOGGER.error("MCP Tool: Empty search term provided");
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Search term 'query' parameter is required and cannot be empty")
                );
            }

            SoundFragmentFilterDTO filter = parseFilters(genres, sources, types);
            String filterDesc = filter != null ? "with filters" : "without filters";

            return getCurrentUser()
                    .chain(user -> {
                        LOGGER.info("MCP Tool: Got user context: {} {}", user.getClass().getSimpleName(), filterDesc);
                        int offset = (pageNum - 1) * pageSize;

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

    private SoundFragmentFilterDTO parseFilters(String genres, String sources, String types) {
        SoundFragmentFilterDTO filter = new SoundFragmentFilterDTO();
        boolean hasAnyFilter = false;

        if (genres != null && !genres.trim().isEmpty()) {
            List<String> trimmedGenres = Arrays.stream(genres.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (!trimmedGenres.isEmpty()) {
                filter.setGenres(trimmedGenres);
                hasAnyFilter = true;
            }
        }

        if (sources != null && !sources.trim().isEmpty()) {
            List<SourceType> sourceTypes = Arrays.stream(sources.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return SourceType.valueOf(s);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("MCP Tool: Invalid source type: {}", s);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!sourceTypes.isEmpty()) {
                filter.setSources(sourceTypes);
                hasAnyFilter = true;
            }
        }

        if (types != null && !types.trim().isEmpty()) {
            List<PlaylistItemType> playlistTypes = Arrays.stream(types.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return PlaylistItemType.valueOf(s);
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("MCP Tool: Invalid playlist item type: {}", s);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!playlistTypes.isEmpty()) {
                filter.setTypes(playlistTypes);
                hasAnyFilter = true;
            }
        }

        return hasAnyFilter ? filter : null;
    }

    private Uni<IUser> getCurrentUser() {
        return Uni.createFrom().item(io.kneo.core.model.user.SuperUser.build());
    }
}