package com.github.kookaburra.face.view;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Test helper: saves a scene snapshot as PNG under target/screenshots (for visual review).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Shots {

    private Shots() {
    }

    static void save(Scene scene, String name) {
        WritableImage img;
        if (Platform.isFxApplicationThread()) {
            img = scene.snapshot(null);
        } else {
            CompletableFuture<WritableImage> f = new CompletableFuture<>();
            Platform.runLater(() -> f.complete(scene.snapshot(null)));
            img = f.join();
        }
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, r.getArgb(x, y));
            }
        }
        try {
            File dir = new File("target/screenshots");
            dir.mkdirs();
            ImageIO.write(out, "png", new File(dir, name + ".png"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
