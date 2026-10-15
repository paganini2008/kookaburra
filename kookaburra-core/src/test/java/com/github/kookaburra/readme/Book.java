package com.github.kookaburra.readme;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;

import java.util.List;

/**
 * The entity used by the README examples.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Entity
public class Book {
    @Id long oid;
    @Version long version;
    @Index String title;
    @Index String author;
    @Index Genre genre;
    @Index(IndexKind.ORDINAL) int year;
    double price;
    @Index List<String> tags;

    public Book() {
    }

    public Book(String title, String author, Genre genre, int year, double price, String... tags) {
        this.title = title;
        this.author = author;
        this.genre = genre;
        this.year = year;
        this.price = price;
        this.tags = List.of(tags);
    }

    public long getOid() { return oid; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public Genre getGenre() { return genre; }
    public int getYear() { return year; }
    public double getPrice() { return price; }
    public List<String> getTags() { return tags; }

    public void setPrice(double price) { this.price = price; }

    /**
     * @Author: Fred Feng
     * @Date: 15/10/2026
     * @Version 1.0.0
     */
    public enum Genre { SCIFI, FANTASY, CRIME }
}
