package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Index;

/**
 * Book: a product subclass.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Book extends Product {
    @Index String author;
    int pages;
}
