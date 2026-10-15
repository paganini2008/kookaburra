import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Synthesises the background music of the tutorial video: an original, relaxed "office" lo-fi loop
 * (electric piano chords Fmaj7 – Em7 – Dm7 – Cmaj7, soft pad, bass, brushed drums, a sparse bell melody) at 84 BPM.
 * Nothing is sampled, so the track carries no third-party rights.
 * <p>
 * Usage: {@code java OfficeBgm.java <seconds> <out.wav>}
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class OfficeBgm {

    static final int RATE = 44_100;
    static final double BPM = 84;
    static final double BEAT = 60.0 / BPM;
    static final double BAR = 4 * BEAT;

    /** Chords as MIDI notes (voicings around middle C). */
    static final int[][] CHORDS = {
            {53, 57, 60, 64},   // Fmaj7
            {52, 55, 59, 62},   // Em7
            {50, 53, 57, 60},   // Dm7
            {48, 52, 55, 59},   // Cmaj7
    };
    static final int[] ROOTS = {41, 40, 38, 36};
    static final int[] PENTA = {72, 74, 76, 79, 81, 84};

    static double hz(double midi) {
        return 440 * Math.pow(2, (midi - 69) / 12);
    }

    public static void main(String[] args) throws IOException {
        double seconds = Double.parseDouble(args[0]);
        int n = (int) (seconds * RATE);
        double[] l = new double[n];
        double[] r = new double[n];
        Random rnd = new Random(2026);
        int bars = (int) Math.ceil(seconds / BAR) + 1;

        for (int bar = 0; bar < bars; bar++) {
            double t0 = bar * BAR;
            int[] chord = CHORDS[bar % 4];
            boolean drums = bar >= 2 && t0 < seconds - 2 * BAR;
            // pad: whole bar, slow attack
            for (int note : chord) {
                tone(l, r, t0, BAR + 0.4, hz(note - 12), 0.022, 0.6, 0.8, 0.0, -0.2, true);
            }
            // electric piano: beat 1 and the "and" of 2, with a little humanised timing
            double[] hits = {0, 1.5 * BEAT, 3 * BEAT};
            for (int h = 0; h < hits.length; h++) {
                double jitter = rnd.nextGaussian() * 0.008;
                for (int k = 0; k < chord.length; k++) {
                    double strum = k * 0.012;
                    tone(l, r, t0 + hits[h] + jitter + strum, 1.6, hz(chord[k]), h == 0 ? 0.055 : 0.04, 0.008, 1.1, 0.35, 0.25, false);
                }
            }
            // bass on 1 and 3 (and a pickup)
            tone(l, r, t0, 1.4 * BEAT, hz(ROOTS[bar % 4]), 0.11, 0.01, 0.9, 0.12, 0, false);
            tone(l, r, t0 + 2 * BEAT, 1.2 * BEAT, hz(ROOTS[bar % 4] + 7), 0.08, 0.01, 0.8, 0.12, 0, false);
            if (drums) {
                for (int b = 0; b < 4; b++) {
                    double tb = t0 + b * BEAT;
                    if (b == 0 || b == 2) {
                        kick(l, r, tb, 0.30);
                    } else {
                        snare(l, r, tb, 0.055, rnd);
                    }
                    for (int e = 0; e < 2; e++) {
                        double swing = e == 1 ? BEAT * 0.58 : 0;
                        hat(l, r, tb + swing, e == 0 ? 0.03 : 0.02, rnd);
                    }
                }
            }
            // sparse bell melody every other bar after the intro
            if (bar >= 4 && bar % 2 == 0 && t0 < seconds - 3 * BAR) {
                int notes = 2 + rnd.nextInt(3);
                for (int k = 0; k < notes; k++) {
                    double at = t0 + (rnd.nextInt(8) * 0.5) * BEAT;
                    int note = PENTA[rnd.nextInt(PENTA.length)];
                    tone(l, r, at, 1.4, hz(note), 0.028, 0.004, 0.7, 0.0, 0.35, false);
                }
            }
        }
        reverb(l, r);
        // fades and normalisation
        double fadeIn = 1.5;
        double fadeOut = 4.0;
        double peak = 1e-9;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double g = Math.min(1, t / fadeIn) * Math.min(1, (seconds - t) / fadeOut);
            l[i] = Math.tanh(l[i] * 1.2) * g;
            r[i] = Math.tanh(r[i] * 1.2) * g;
            peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        }
        writeWav(args[1], l, r, 0.89 / peak);
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

    static void snare(double[] l, double[] r, double start, double amp, Random rnd) {
        int s0 = (int) (start * RATE);
        double lp = 0;
        for (int i = 0; i < 0.25 * RATE; i++) {
            int at = s0 + i;
            if (at >= l.length) {
                break;
            }
            double t = (double) i / RATE;
            lp += 0.25 * (rnd.nextGaussian() - lp);          // brushed, dark noise
            double v = (lp * 0.9 + 0.3 * Math.sin(2 * Math.PI * 190 * t)) * Math.exp(-t / 0.07) * amp;
            l[at] += v * 0.9;
            r[at] += v * 1.1;
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
