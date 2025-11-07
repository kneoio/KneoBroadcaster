package io.kneo.broadcaster.model.news;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Setter
@Getter
public class NewsArticle {
    private int id;
    private String title;
    private String text;
    private String summary;
    private String url;
    private String image;
    private String video;
    private OffsetDateTime publishDate;
    private String author;
    private List<String> authors;
    private String language;
    private String category;
    private String sourceCountry;

}
