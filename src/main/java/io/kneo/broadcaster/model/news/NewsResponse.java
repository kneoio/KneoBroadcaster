package io.kneo.broadcaster.model.news;

import java.util.List;

public class NewsResponse {
    private int offset;
    private int number;
    private int available;
    private List<NewsArticle> news;

    // Getters and setters
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public int getAvailable() { return available; }
    public void setAvailable(int available) { this.available = available; }
    public List<NewsArticle> getNews() { return news; }
    public void setNews(List<NewsArticle> news) { this.news = news; }
}
