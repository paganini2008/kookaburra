package com.github.kookaburra.face.view;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Turns the recorded clips of {@link TutorialRecorder} into the tutorial video: every clip is encoded on its own
 * (a still, or a before / after pair with a mouse cursor gliding to the clicked control and a click ring), then the
 * clips are joined with cross-fades, English subtitles are burned in (libass), the synthesised background music
 * ({@link OfficeBgm}) is mixed in, and a poster is drawn from the first frame. Needs {@code ffmpeg} with libx264 and
 * libass ({@code FFMPEG} environment variable, Homebrew or {@code PATH}).
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class TutorialVideo {

    static final int FPS = 30;
    /** Cross-fade between two Studio clips (same window: almost a cut) and around slides. */
    static final double FADE_UI = 0.25;
    static final double FADE_SLIDE = 0.7;
    /** The cursor starts moving at this time of a clip. */
    static final double MOVE_START = 0.35;
    private static final String FONT = "Helvetica Neue";

    /**
     * One clip of the video.
     *
     * @param before  picture until the click (null = {@code after} all along)
     * @param after   picture after the click (or the only picture)
     * @param target  where the cursor goes and clicks (frame pixels), or null for a clip without a click
     * @param cursor  whether the mouse cursor is shown (Studio clips) or not (slides)
     * @param clickAt seconds into the clip at which the click happens
     */
    record Clip(String name, Path before, Path after, double seconds, String subtitle, Point2D target, boolean cursor, double clickAt) {
    }

    private final Path work;
    private final String ffmpeg;

    TutorialVideo(Path work) {
        this.work = work;
        this.ffmpeg = tool("ffmpeg");
    }

    /** Renders {@code clips} into {@code video} and {@code poster}; returns the length in seconds. */
    double render(List<Clip> clips, Path video, Path poster) throws IOException, InterruptedException {
        Files.createDirectories(work);
        Path cursor = work.resolve("cursor.png");
        Path ring = work.resolve("ring.png");
        ImageIO.write(cursorImage(), "png", cursor.toFile());
        ImageIO.write(ringImage(), "png", ring.toFile());

        // 1 · every clip on its own, with the cursor where needed
        List<Path> parts = new ArrayList<>();
        Point2D at = new Point2D.Double(TutorialRecorder.W / 2.0, TutorialRecorder.H / 2.0);
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            Path part = work.resolve(String.format("clip-%02d.mp4", i));
            encode(c, at, cursor, ring, part);
            if (c.target() != null) {
                at = c.target();
            }
            parts.add(part);
        }

        // 2 · cross-fades, subtitles, music
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-loglevel", "error"));
        for (Path p : parts) {
            cmd.addAll(List.of("-i", p.toString()));
        }
        StringBuilder graph = new StringBuilder();
        List<String> events = new ArrayList<>();
        String last = "0:v";
        double offset = clips.get(0).seconds();
        double start = 0;
        events.add(event(0.2, clips.get(0).seconds() - 0.3, clips.get(0).subtitle()));
        for (int i = 1; i < clips.size(); i++) {
            double fade = clips.get(i).cursor() && clips.get(i - 1).cursor() ? FADE_UI : FADE_SLIDE;
            double xfadeAt = offset - fade;
            graph.append('[').append(last).append("][").append(i).append(":v]xfade=transition=fade:duration=")
                    .append(fmt(fade)).append(":offset=").append(fmt(xfadeAt)).append("[x").append(i).append("];");
            last = "x" + i;
            start = xfadeAt;
            offset = xfadeAt + clips.get(i).seconds();
            if (!clips.get(i).subtitle().isBlank()) {
                events.add(event(start + fade / 2, offset - 0.3, clips.get(i).subtitle()));
            }
        }
        double total = offset;
        Path ass = work.resolve("subs.ass");
        Files.writeString(ass, subtitles(events), StandardCharsets.UTF_8);
        Path music = work.resolve("bgm.wav");
        OfficeBgm.render(total, music);
        cmd.addAll(List.of("-i", music.toString()));
        String fonts = Files.isDirectory(Path.of("/System/Library/Fonts")) ? ":fontsdir=/System/Library/Fonts" : "";
        graph.append('[').append(last).append("]subtitles=").append(escape(ass.toString())).append(fonts).append("[vout];")
                .append('[').append(parts.size()).append(":a]highpass=f=40,lowpass=f=9000,loudnorm=I=-20:TP=-2:LRA=7[aout]");
        Files.createDirectories(video.toAbsolutePath().getParent());
        cmd.addAll(List.of("-filter_complex", graph.toString(), "-map", "[vout]", "-map", "[aout]", "-t", fmt(total),
                "-c:v", "libx264", "-preset", "slow", "-crf", "22", "-pix_fmt", "yuv420p", "-r", String.valueOf(FPS),
                "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-movflags", "+faststart", video.toString()));
        run(cmd);

        // 3 · poster: the first frame with a play badge
        writePoster(ImageIO.read(clips.get(0).after().toFile()), total, poster);
        return total;
    }

    /** One clip: the picture (switching from before to after at the click), the click ring and the gliding cursor. */
    private void encode(Clip c, Point2D from, Path cursor, Path ring, Path out) throws IOException, InterruptedException {
        Path before = c.before() == null ? c.after() : c.before();
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-loglevel", "error",
                "-loop", "1", "-framerate", String.valueOf(FPS), "-t", fmt(c.seconds()), "-i", before.toString(),
                "-loop", "1", "-framerate", String.valueOf(FPS), "-t", fmt(c.seconds()), "-i", c.after().toString(),
                "-loop", "1", "-framerate", String.valueOf(FPS), "-t", fmt(c.seconds()), "-i", cursor.toString(),
                "-loop", "1", "-framerate", String.valueOf(FPS), "-t", fmt(c.seconds()), "-i", ring.toString()));
        StringBuilder g = new StringBuilder();
        double click = c.target() == null ? 0 : c.clickAt();
        g.append("[0:v][1:v]overlay=enable='gte(t,").append(fmt(click)).append(")'[pic];");
        String lastLabel = "pic";
        if (c.cursor()) {
            Point2D to = c.target() == null ? from : c.target();
            if (c.target() != null) {
                g.append("[pic][3:v]overlay=x=").append(fmt(to.getX() - RING / 2.0)).append(":y=").append(fmt(to.getY() - RING / 2.0))
                        .append(":enable='between(t,").append(fmt(click)).append(',').append(fmt(click + 0.45)).append(")'[ring];");
                lastLabel = "ring";
            }
            double move = Math.max(0.3, click - 0.15 - MOVE_START);
            String p = "clip((t-" + fmt(MOVE_START) + ")/" + fmt(move) + ",0,1)";
            String ease = "(" + p + ")*(" + p + ")*(3-2*(" + p + "))";
            String x = fmt(from.getX() - HOT) + "+(" + fmt(to.getX() - from.getX()) + ")*" + ease;
            String y = fmt(from.getY() - HOT) + "+(" + fmt(to.getY() - from.getY()) + ")*" + ease;
            g.append('[').append(lastLabel).append("][2:v]overlay=x='").append(x).append("':y='").append(y)
                    .append("':eval=frame[cur];");
            lastLabel = "cur";
        }
        g.append('[').append(lastLabel).append("]format=yuv420p,setsar=1[v]");
        cmd.addAll(List.of("-filter_complex", g.toString(), "-map", "[v]", "-t", fmt(c.seconds()), "-r", String.valueOf(FPS),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p", out.toString()));
        run(cmd);
    }

    // ------------------------------------------------------------------ cursor, ring, poster

    /** Cursor hot spot offset inside {@link #cursorImage()} (the arrow tip). */
    private static final int HOT = 4;
    private static final int RING = 72;

    /** A macOS-like arrow cursor, white with a dark outline and a soft shadow, about twice the screen size. */
    static BufferedImage cursorImage() {
        BufferedImage img = new BufferedImage(48, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] xs = {0, 0, 9, 15, 21, 15, 27};
            int[] ys = {0, 37, 28, 41, 38, 25, 25};
            Polygon arrow = new Polygon();
            for (int i = 0; i < xs.length; i++) {
                arrow.addPoint(HOT + (int) (xs[i] * 1.3), HOT + (int) (ys[i] * 1.3));
            }
            g.translate(2, 3);
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(arrow);
            g.translate(-2, -3);
            g.setColor(Color.WHITE);
            g.fill(arrow);
            g.setColor(new Color(0x0B0E14));
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arrow);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The click marker: a cyan ring. */
    static BufferedImage ringImage() {
        BufferedImage img = new BufferedImage(RING, RING, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 229, 255, 70));
            g.fillOval(6, 6, RING - 12, RING - 12);
            g.setColor(new Color(0, 229, 255, 230));
            g.setStroke(new BasicStroke(5f));
            g.drawOval(6, 6, RING - 12, RING - 12);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The first frame with a "Watch the tutorial (m:ss)" badge, 1280 wide, JPEG. */
    static void writePoster(BufferedImage first, double seconds, Path poster) throws IOException {
        int w = 1280;
        int h = first.getHeight() * w / first.getWidth();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(first, 0, 0, w, h, null);
            int bw = 506;
            int bh = 66;
            int bx = (w - bw) / 2;
            int by = 620;
            g.setColor(new Color(0x00E5FF));
            g.fillRect(bx, by, bw, bh);
            g.setColor(new Color(0x0B0E14));
            Polygon play = new Polygon(new int[]{bx + 44, bx + 44, bx + 64}, new int[]{by + 22, by + 44, by + 33}, 3);
            g.fill(play);
            long s = Math.round(seconds);
            g.setFont(new Font(FONT, Font.PLAIN, 32));
            g.drawString(String.format(Locale.ROOT, "Watch the tutorial (%d:%02d)", s / 60, s % 60), bx + 92, by + 44);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        Files.deleteIfExists(poster);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(poster.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ------------------------------------------------------------------ subtitles

    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    private static String event(double from, double to, String text) {
        // keep "[ n elements ]" on one line
        String kept = BRACKETS.matcher(text).replaceAll(m -> m.group().replace(" ", "\\\\h"));
        return "Dialogue: 0," + ts(from) + "," + ts(to) + ",Sub,,0,0,0,,{\\fad(250,250)}" + kept;
    }

    private static String subtitles(List<String> events) {
        return """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 1920
                PlayResY: 1080
                WrapStyle: 0
                ScaledBorderAndShadow: yes

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Sub,%s,50,&H00FFFFFF,&H00FFFFFF,&H00140E0B,&H64000000,0,0,0,0,100,100,0,0,1,2,1,2,140,140,62,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                """.formatted(FONT) + String.join("\n", events) + "\n";
    }

    private static String ts(double t) {
        int h = (int) (t / 3600);
        int m = (int) (t % 3600 / 60);
        return String.format(Locale.ROOT, "%d:%02d:%05.2f", h, m, t % 60);
    }

    // ------------------------------------------------------------------ process plumbing

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** A path inside a filter graph: ':' and '\' escaped. */
    private static String escape(String path) {
        return path.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'");
    }

    /** ffmpeg / ffprobe from {@code $FFMPEG}'s directory, Homebrew, /usr/local or the PATH. */
    static String tool(String name) {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("FFMPEG");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env).resolveSibling(name).toString());
        }
        candidates.add("/opt/homebrew/bin/" + name);
        candidates.add("/usr/local/bin/" + name);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                candidates.add(Path.of(dir, name).toString());
            }
        }
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) {
                return c;
            }
        }
        throw new IllegalStateException(name + " not found: install ffmpeg (with libx264 and libass) or set FFMPEG");
    }

    static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IllegalStateException(cmd.get(0) + " failed (" + p.exitValue() + "): " + output);
        }
    }

    /** Duration of a media file in seconds (ffprobe). */
    static double duration(Path media) {
        try {
            Process p = new ProcessBuilder(tool("ffprobe"), "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=nw=1:nk=1", media.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return Double.parseDouble(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
