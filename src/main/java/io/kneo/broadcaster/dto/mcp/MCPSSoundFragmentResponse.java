package io.kneo.broadcaster.dto.mcp;

import io.kneo.broadcaster.dto.SoundFragmentDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class MCPSSoundFragmentResponse {
    private List<SoundFragmentDTO> fragments;
    private long totalCount;
    private int page;
    private int maxPage;

    public MCPSSoundFragmentResponse(List<SoundFragmentDTO> fragments, long totalCount, int page, int maxPage) {
        this.fragments = fragments;
        this.totalCount = totalCount;
        this.page = page;
        this.maxPage = maxPage;
    }
}
