package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.dto.BrandSoundFragmentDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class MCPBrandResponse {
    private List<BrandSoundFragmentDTO> fragments;
    private long totalCount;
    private int page;
    private int maxPage;

    public MCPBrandResponse(List<BrandSoundFragmentDTO> fragments, long totalCount, int page, int maxPage) {
        this.fragments = fragments;
        this.totalCount = totalCount;
        this.page = page;
        this.maxPage = maxPage;
    }
}
