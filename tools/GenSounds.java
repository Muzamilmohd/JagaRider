import java.io.*;
import java.util.Random;

/**
 * Sintesis bunyi notifikasi JagaRide (2 saat): rain.wav (hujan) dan storm.wav (hujan lebat + guruh).
 * Hujan = bunyi bising ditapis sahaja (tiada nada — nada pendek berbunyi macam kicauan burung).
 * Jalankan: java tools/GenSounds.java app/src/main/res/raw
 */
public class GenSounds {
    static final int SR = 22050;
    static final double SECS = 2.0;

    public static void main(String[] a) throws Exception {
        String out = a[0];
        write(out + "/rain.wav", rain(SECS, 1.0, 1));
        write(out + "/storm.wav", storm(SECS));
    }

    /** Desir hujan: bunyi bising jalur tengah-tinggi + "tap" titisan (letusan bising pendek, bukan nada). */
    static double[] rain(double secs, double heavy, long seed) {
        Random r = new Random(seed);
        int n = (int) (secs * SR);
        double[] s = new double[n];
        double lp = 0, slow = 0, gust = 0;
        for (int i = 0; i < n; i++) {
            double w = r.nextGaussian();
            lp += 0.55 * (w - lp);          // potong frekuensi sangat tinggi
            slow += 0.04 * (lp - slow);     // buang frekuensi rendah → desir
            gust += 0.0004 * (r.nextGaussian() * 3 - gust); // turun naik perlahan
            s[i] = (lp - slow) * (0.8 + 0.2 * Math.tanh(gust));
        }
        // titisan: letusan bising sangat pendek (tiada pic)
        int drops = (int) (secs * 90 * heavy);
        for (int d = 0; d < drops; d++) {
            int at = r.nextInt(n);
            double amp = 0.3 + r.nextDouble() * 0.9;
            int len = (int) (SR * (0.002 + r.nextDouble() * 0.004));
            double hp = 0, prev = 0;
            for (int k = 0; k < len && at + k < n; k++) {
                double w = r.nextGaussian();
                hp = 0.6 * (hp + w - prev);
                prev = w;
                s[at + k] += amp * hp * Math.exp(-(double) k / (len / 3.0));
            }
        }
        fade(s, 0.15, 0.4);
        return s;
    }

    /** Hujan lebat + guruh rendah bergemuruh (tiada retak tajam). */
    static double[] storm(double secs) {
        double[] s = rain(secs, 1.8, 7);
        Random r = new Random(42);
        int n = s.length;
        double brown = 0, l1 = 0, l2 = 0;
        double[] rumble = new double[n];
        double[] at = {0.05, 0.45, 0.95};
        double[] amp = {1.0, 0.8, 0.5};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            brown = 0.995 * brown + 0.06 * r.nextGaussian();
            l1 += 0.03 * (brown - l1);
            l2 += 0.03 * (l1 - l2);
            double env = 0;
            for (int k = 0; k < at.length; k++) {
                double dt = t - at[k];
                if (dt > 0) env += amp[k] * (1 - Math.exp(-dt / 0.06)) * Math.exp(-dt / 0.5);
            }
            rumble[i] = l2 * env;
        }
        normalize(rumble, 1.0);
        normalize(s, 1.0);
        for (int i = 0; i < n; i++) s[i] = 0.55 * s[i] + 0.75 * rumble[i];
        fade(s, 0.1, 0.4);
        return s;
    }

    static void fade(double[] s, double in, double out) {
        int a = (int) (in * SR), b = (int) (out * SR);
        for (int i = 0; i < a && i < s.length; i++) s[i] *= (double) i / a;
        for (int i = 0; i < b && i < s.length; i++) s[s.length - 1 - i] *= (double) i / b;
    }

    static void normalize(double[] s, double peak) {
        double m = 1e-9;
        for (double v : s) m = Math.max(m, Math.abs(v));
        for (int i = 0; i < s.length; i++) s[i] *= peak / m;
    }

    static void write(String path, double[] s) throws IOException {
        normalize(s, 0.89);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
            int data = s.length * 2;
            o.writeBytes("RIFF"); le32(o, 36 + data); o.writeBytes("WAVE");
            o.writeBytes("fmt "); le32(o, 16); le16(o, 1); le16(o, 1); le32(o, SR); le32(o, SR * 2); le16(o, 2); le16(o, 16);
            o.writeBytes("data"); le32(o, data);
            for (double v : s) le16(o, (int) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
        }
        System.out.println(path + " " + new File(path).length() + " bytes, " + (s.length / (double) SR) + " s");
    }

    static void le32(DataOutputStream o, int v) throws IOException { o.writeByte(v); o.writeByte(v >> 8); o.writeByte(v >> 16); o.writeByte(v >> 24); }
    static void le16(DataOutputStream o, int v) throws IOException { o.writeByte(v); o.writeByte(v >> 8); }
}
