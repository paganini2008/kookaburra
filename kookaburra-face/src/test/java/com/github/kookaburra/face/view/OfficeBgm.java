package com.github.kookaburra.face.view;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * Synthesises the background music of the tutorial video: an original, light and cheerful "office" track (upbeat
 * corporate / ukulele pop) at 116 BPM in C major over I – V – vi – IV (C, G, Am, F): a plucked ukulele-like strum
 * on the off-beats, a bouncy eighth-note bass, kick, hand claps on 2 and 4, a shaker, and a composed marimba melody
 * with a glockenspiel answer. Nothing is sampled, so the track carries no third-party rights. Used by
 * {@link TutorialVideo}.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class OfficeBgm {

    private OfficeBgm() {
    }

    static final int RATE = 44_100;
    static final double BPM = 116;
    static final double BEAT = 60.0 / BPM;
    static final double BAR = 4 * BEAT;

    /** C, G, Am, F as MIDI notes (bright ukulele-like voicings above middle C). */
    static final int[][] CHORDS = {
            {60, 64, 67, 72},   // C
            {59, 62, 67, 71},   // G
            {60, 64, 69, 72},   // Am
            {60, 65, 69, 72},   // F
    };
    static final int[] ROOTS = {36, 43, 45, 41};

    /**
     * The melody over two times four bars: (beat offset in eighths, MIDI note, length in eighths) per note; a question
     * phrase and an answer that ends on the tonic.
     */
    static final int[][][] MELODY = {
            {{0, 76, 2}, {2, 79, 1}, {3, 76, 1}, {4, 74, 2}, {6, 72, 2}},
            {{0, 74, 3}, {3, 71, 1}, {4, 74, 2}, {6, 79, 2}},
            {{0, 76, 2}, {2, 72, 1}, {3, 76, 1}, {4, 81, 3}},
            {{0, 79, 2}, {2, 77, 2}, {4, 76, 2}, {6, 74, 2}},
            {{0, 76, 2}, {2, 79, 1}, {3, 76, 1}, {4, 74, 2}, {6, 72, 2}},
            {{0, 74, 3}, {3, 71, 1}, {4, 74, 2}, {6, 77, 2}},
            {{0, 76, 1}, {1, 77, 1}, {2, 79, 2}, {4, 81, 2}, {6, 79, 2}},
            {{0, 77, 2}, {2, 74, 2}, {4, 72, 4}},
    };

    static double hz(double midi) {
        return 440 * Math.pow(2, (midi - 69) / 12);
    }

    /** Writes {@code seconds} of music as 16-bit stereo WAV to {@code out}. */
    static void render(double seconds, Path out) throws IOException {
        int n = (int) (seconds * RATE);
        double[] l = new double[n];
        double[] r = new double[n];
        Random rnd = new Random(2026);
        int bars = (int) Math.ceil(seconds / BAR) + 1;
        double eighth = BEAT / 2;

        for (int bar = 0; bar < bars; bar++) {
            double t0 = bar * BAR;
            int[] chord = CHORDS[bar % 4];
            boolean full = bar >= 2 && t0 < seconds - 2 * BAR;          // two bars of intro, the end thins out
            // ukulele strum: down on the beat, up on the "and", accents on the off-beats (island / office feel)
            for (int e = 0; e < 8; e++) {
                boolean up = e % 2 == 1;
                double accent = up ? 0.11 : 0.07;
                double jitter = rnd.nextGaussian() * 0.004;
                for (int k = 0; k < chord.length; k++) {
                    int string = up ? chord.length - 1 - k : k;
                    pluck(l, r, t0 + e * eighth + jitter + k * 0.009, 0.9, hz(chord[string]), accent, (k - 1.5) * 0.15, rnd);
                }
            }
            // bass: root on the beats, octave and fifth bouncing on the off-beats
            if (bar >= 1) {
                int root = ROOTS[bar % 4];
                int[] line = {root, root + 12, root + 7, root + 12, root, root + 12, root + 7, root + 10};
                for (int e = 0; e < 8; e++) {
                    tone(l, r, t0 + e * eighth, eighth * 0.9, hz(line[e]), e % 2 == 0 ? 0.12 : 0.07, 0.004, 0.18, 0.15, 0, false);
                }
            }
            if (full) {
                for (int b = 0; b < 4; b++) {
                    double tb = t0 + b * BEAT;
                    kick(l, r, tb, b % 2 == 0 ? 0.32 : 0.18);
                    if (b % 2 == 1) {
                        clap(l, r, tb, 0.09, rnd);
                    }
                    for (int e = 0; e < 4; e++) {                        // sixteenth shaker
                        hat(l, r, tb + e * BEAT / 4, e == 2 ? 0.026 : 0.014, rnd);
                    }
                }
            }
            // marimba melody, glockenspiel echo an octave up on the answer phrases
            if (bar >= 2 && t0 < seconds - 2 * BAR) {
                int[][] phrase = MELODY[(bar - 2) % MELODY.length];
                for (int[] note : phrase) {
                    double at = t0 + note[0] * eighth;
                    marimba(l, r, at, note[2] * eighth + 0.3, hz(note[1]), 0.07, -0.15);
                    if (((bar - 2) / 4) % 2 == 1) {
                        tone(l, r, at + eighth * 0.5, 0.9, hz(note[1] + 12), 0.016, 0.002, 0.45, 0.0, 0.4, false);
                    }
                }
            }
        }
        reverb(l, r);
        // fades and normalisation
        double fadeIn = 0.8;
        double fadeOut = 3.5;
        double peak = 1e-9;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double g = Math.min(1, t / fadeIn) * Math.min(1, (seconds - t) / fadeOut);
            l[i] = Math.tanh(l[i] * 1.1) * g;
            r[i] = Math.tanh(r[i] * 1.1) * g;
            peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        }
        writeWav(out.toString(), l, r, 0.89 / peak);
    }

    /** A plucked string (Karplus-Strong): bright attack, quick natural decay, like a ukulele or nylon guitar. */
    static void pluck(double[] l, double[] r, double start, double length, double f, double amp, double pan, Random rnd) {
        int period = Math.max(2, (int) Math.round(RATE / f));
        double[] ring = new double[period];
        for (int i = 0; i < period; i++) {
            ring[i] = rnd.nextDouble() * 2 - 1;
        }
        int s0 = (int) (start * RATE);
        int len = (int) (length * RATE);
        double gl = amp * Math.sqrt((1 - pan) / 2);
        double gr = amp * Math.sqrt((1 + pan) / 2);
        int pos = 0;
        for (int i = 0; i < len; i++) {
            int at = s0 + i;
            if (at < 0 || at >= l.length) {
                continue;
            }
            double v = ring[pos];
            int next = (pos + 1) % period;
            ring[pos] = 0.5 * (ring[pos] + ring[next]) * 0.994;      // low-pass and damping
            pos = next;
            double env = Math.min(1, (len - i) / (0.05 * RATE));
            l[at] += v * gl * env;
            r[at] += v * gr * env;
        }
    }

    /** A marimba-like bar: sine with a fourth-harmonic strike that fades fast. */
    static void marimba(double[] l, double[] r, double start, double length, double f, double amp, double pan) {
        int s0 = (int) (start * RATE);
        int len = (int) (length * RATE);
        double gl = amp * Math.sqrt((1 - pan) / 2);
        double gr = amp * Math.sqrt((1 + pan) / 2);
        for (int i = 0; i < len; i++) {
            int at = s0 + i;
            if (at < 0 || at >= l.length) {
                continue;
            }
            double t = (double) i / RATE;
            double ph = 2 * Math.PI * f * t;
            double v = Math.sin(ph) * Math.exp(-t / 0.42) + 0.35 * Math.sin(4 * ph) * Math.exp(-t / 0.05);
            double env = Math.min(1, t / 0.002) * Math.min(1, (length - t) / 0.08);
            l[at] += v * env * gl;
            r[at] += v * env * gr;
        }
    }

    /** A hand clap: three quick noise bursts and a short tail. */
    static void clap(double[] l, double[] r, double start, double amp, Random rnd) {
        int s0 = (int) (start * RATE);
        double prev = 0;
        for (int i = 0; i < 0.2 * RATE; i++) {
            int at = s0 + i;
            if (at >= l.length) {
                break;
            }
            double t = (double) i / RATE;
            double bursts = t < 0.03 ? (Math.sin(Math.PI * t / 0.01) > 0 ? 1 : 0.3) : Math.exp(-(t - 0.03) / 0.06);
            double x = rnd.nextGaussian();
            double v = (x - 0.6 * prev) * bursts * amp;
            prev = x;
            l[at] += v * 1.05;
            r[at] += v * 0.95;
        }
    }

    /**
     * A decaying tone: sine plus a few harmonics (electric-piano like when {@code bright} &gt; 0), slight vibrato.
     *
     * @param attack seconds
     * @param decay  time constant in seconds
     * @param pan    -1 left … 1 right
     * @param pad    sustained (no decay, release at the end)
     */
    static void tone(double[] l, double[] r, double start, double length, double f, double amp, double attack, double decay,
                     double bright, double pan, boolean pad) {
        int s0 = (int) (start * RATE);
        int len = (int) (length * RATE);
        double gl = amp * Math.sqrt((1 - pan) / 2);
        double gr = amp * Math.sqrt((1 + pan) / 2);
        for (int i = 0; i < len; i++) {
            int at = s0 + i;
            if (at < 0 || at >= l.length) {
                continue;
            }
            double t = (double) i / RATE;
            double env = Math.min(1, t / attack);
            env *= pad ? Math.min(1, (length - t) / 0.4) : Math.exp(-t / decay);
            double ph = 2 * Math.PI * f * t + 0.002 * Math.sin(2 * Math.PI * 5 * t);
            double v = Math.sin(ph);
            if (pad) {
                v = 0.5 * v + 0.5 * Math.sin(ph * 1.003);
            } else if (bright > 0) {
                v += bright * Math.sin(2 * ph) * Math.exp(-t / 0.25) + 0.08 * Math.sin(3 * ph) * Math.exp(-t / 0.1);
            }
            double trem = pad ? 1 : 1 + 0.08 * Math.sin(2 * Math.PI * 4.5 * t);
            l[at] += v * env * gl * trem;
            r[at] += v * env * gr * (2 - trem);
        }
    }

    static void kick(double[] l, double[] r, double start, double amp) {
        int s0 = (int) (start * RATE);
        double phase = 0;
        for (int i = 0; i < 0.35 * RATE; i++) {
            int at = s0 + i;
            if (at >= l.length) {
                break;
            }
            double t = (double) i / RATE;
            double f = 45 + 75 * Math.exp(-t / 0.04);
            phase += 2 * Math.PI * f / RATE;
            double v = Math.sin(phase) * Math.exp(-t / 0.13) * amp;
            l[at] += v;
            r[at] += v;
        }
    }

    static void hat(double[] l, double[] r, double start, double amp, Random rnd) {
        int s0 = (int) (start * RATE);
        double prev = 0;
        for (int i = 0; i < 0.08 * RATE; i++) {
            int at = s0 + i;
            if (at >= l.length) {
                break;
            }
            double t = (double) i / RATE;
            double x = rnd.nextGaussian();
            double v = (x - prev) * Math.exp(-t / 0.022) * amp;     // high-passed noise
            prev = x;
            l[at] += v * 0.7;
            r[at] += v * 1.3;
        }
    }

    /** A small Schroeder reverb (parallel combs) mixed in for a soft room. */
    static void reverb(double[] l, double[] r) {
        int[] delays = {1557, 1617, 1491, 1422, 1277, 1356};
        double[] outL = new double[l.length];
        double[] outR = new double[r.length];
        for (int k = 0; k < delays.length; k++) {
            int d = delays[k];
            double[] buf = new double[d];
            int pos = 0;
            double[] src = k % 2 == 0 ? l : r;
            double[] dst = k % 2 == 0 ? outL : outR;
            for (int i = 0; i < src.length; i++) {
                double y = buf[pos];
                buf[pos] = src[i] + y * 0.78;
                pos = (pos + 1) % d;
                dst[i] += y;
            }
        }
        for (int i = 0; i < l.length; i++) {
            l[i] += outL[i] * 0.06;
            r[i] += outR[i] * 0.06;
        }
    }

    static void writeWav(String file, double[] l, double[] r, double gain) throws IOException {
        int n = l.length;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + n * 4));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 4));
            out.writeShort(Short.reverseBytes((short) 4));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(n * 4));
            byte[] frame = new byte[4 * 4096];
            int f = 0;
            for (int i = 0; i < n; i++) {
                short a = (short) Math.max(-32768, Math.min(32767, Math.round(l[i] * gain * 32767)));
                short b = (short) Math.max(-32768, Math.min(32767, Math.round(r[i] * gain * 32767)));
                frame[f++] = (byte) a;
                frame[f++] = (byte) (a >> 8);
                frame[f++] = (byte) b;
                frame[f++] = (byte) (b >> 8);
                if (f == frame.length) {
                    out.write(frame);
                    f = 0;
                }
            }
            out.write(frame, 0, f);
        }
    }
}
