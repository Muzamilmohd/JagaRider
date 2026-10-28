import java.io.*;
import java.util.Random;

/** Sintesis bunyi notifikasi JagaRide: rain.wav (hujan) dan storm.wav (hujan lebat + guruh). */
public class GenSounds {
    static final int SR = 22050;

    public static void main(String[] a) throws Exception {
        String out = a[0];
        write(out + "/rain.wav", rain(4.0, 30, 0.22, 1));
        write(out + "/storm.wav", storm(6.5));
    }

    /** Hujan: desis bunyi bising ditapis + titisan rawak. */
    static double[] rain(double secs, double dropsPerSec, double bed, long seed) {
        Random r = new Random(seed);
        int n = (int) (secs * SR);
        double[] s = new double[n];
        double lp = 0, slow = 0;
        for (int i = 0; i < n; i++) {
            double w = r.nextGaussian();
            lp += 0.45 * (w - lp);          // potong frekuensi tinggi
            slow += 0.02 * (lp - slow);     // buang frekuensi rendah
            s[i] = (lp - slow) * bed;
        }
        int drops = (int) (secs * dropsPerSec);
        for (int d = 0; d < drops; d++) {
            int at = r.nextInt(n);
            double f = 1200 + r.nextDouble() * 3500;
            double tau = 0.003 + r.nextDouble() * 0.010;
            double amp = 0.04 + r.nextDouble() * 0.22;
            for (int k = 0; k < SR * tau * 6 && at + k < n; k++) {
                double t = (double) k / SR;
                double ff = f * (1 - 0.3 * t / (tau * 6)); // pic turun sedikit
                s[at + k] += amp * Math.exp(-t / tau) * Math.sin(2 * Math.PI * ff * t);
            }
        }
        fade(s, 0.35, 0.9);
        return s;
    }

    /** Hujan lebat + petir (retak) + guruh bergemuruh. */
    static double[] storm(double secs) {
        double[] s = rain(secs, 70, 0.32, 7);
        Random r = new Random(42);
        int n = s.length;
        // guruh: bunyi "brown" ditapis rendah, sampul beberapa gelombang
        double brown = 0, l1 = 0, l2 = 0;
        double[] rumble = new double[n];
        double[] at = {0.30, 0.75, 1.45, 2.30, 3.3};
        double[] amp = {1.0, 0.85, 0.65, 0.45, 0.25};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            brown = 0.995 * brown + 0.06 * r.nextGaussian();
            l1 += 0.035 * (brown - l1);
            l2 += 0.035 * (l1 - l2);
            double env = 0;
            for (int k = 0; k < at.length; k++) {
                double dt = t - at[k];
                if (dt > 0) env += amp[k] * (1 - Math.exp(-dt / 0.08)) * Math.exp(-dt / 0.9);
            }
            rumble[i] = l2 * env;
        }
        normalize(rumble, 0.95);
        // retak petir di awal
        int c0 = (int) (0.28 * SR);
        double hp = 0, prev = 0;
        for (int k = 0; k < SR * 0.5 && c0 + k < n; k++) {
            double t = (double) k / SR;
            double w = r.nextGaussian();
            hp = 0.7 * (hp + w - prev);
            prev = w;
            rumble[c0 + k] += 0.55 * hp * Math.exp(-t / 0.07);
        }
        for (int i = 0; i < n; i++) s[i] = 0.55 * s[i] + rumble[i];
        fade(s, 0.2, 1.2);
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
        System.out.println(path + " " + new File(path).length() + " bytes");
    }

    static void le32(DataOutputStream o, int v) throws IOException { o.writeByte(v); o.writeByte(v >> 8); o.writeByte(v >> 16); o.writeByte(v >> 24); }
    static void le16(DataOutputStream o, int v) throws IOException { o.writeByte(v); o.writeByte(v >> 8); }
}
