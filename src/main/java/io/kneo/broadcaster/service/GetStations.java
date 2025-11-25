package io.kneo.broadcaster.service;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Get a list of available radio stations")
public class GetStations {
    @JsonPropertyDescription("Filter stations by country code (e.g., 'US', 'UK')")
    public String country;

    @JsonPropertyDescription("Search query to filter stations by name")
    public String query;
}
