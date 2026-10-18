package com.christopher.strata;

/**
 * Music ducking: lowers the music while other sound (clip audio, overlay audio) is present.
 * Pure Java, shared by preview and export so they behave the same.
 *
 * Input is a sidechain level per 10 ms frame on the timeline (peak of the other sources, 0..1).
 * A frame counts as "speech/sound present" above THRESH (-30 dBFS). Presence is held for HOLD
 * after the sound stops (so the music doesn't swell back between words), then the gain moves
 * toward its target with separate attack (fast down) and release (slow up) time constants.
 * The whole timeline is known in advance, so the duck LOOKS AHEAD: the music is already down
 * when the first word arrives instead of dipping a quarter-second into it.
 */
final class Duck {
    static final int RATE = 100;                 // frames per second
    static final float THRESH = 0.0316f;         // -30 dBFS
    static final float ATTACK = 0.12f, RELEASE = 0.7f, HOLD = 0.35f, LOOKAHEAD = 0.25f;   // seconds
    static final float[] PRESETS_DB = {0, 6, 12, 18};
    static final String[] PRESET_NAMES = {"Off", "Light", "Medium", "Strong"};

    /** Gain (linear) per frame for a duck depth in dB; null when depthDb <= 0. */
    static float[] gains(float[] side, float depthDb) {
        if (side == null || depthDb <= 0) return null;
        float low = (float) Math.pow(10, -depthDb / 20.0);
        float a = (float) Math.exp(-1.0 / (ATTACK * RATE)), r = (float) Math.exp(-1.0 / (RELEASE * RATE));
        int holdFrames = Math.round(HOLD * RATE), look = Math.round(LOOKAHEAD * RATE);
        float[] g = new float[side.length];
        float cur = 1;
        int held = 0, nextOn = Integer.MAX_VALUE;   // index of the next frame with sound, scanning ahead
        for (int i = 0; i < side.length; i++) {
            if (nextOn < i || nextOn == Integer.MAX_VALUE) {
                nextOn = Integer.MAX_VALUE;
                for (int j = i; j < Math.min(side.length, i + look + 1); j++) if (side[j] >= THRESH) { nextOn = j; break; }
            }
            boolean on = nextOn <= i + look;
            if (on) held = holdFrames; else if (held > 0) held--;
            float target = on || held > 0 ? low : 1f;
            float k = target < cur ? a : r;
            cur = target + (cur - target) * k;
            g[i] = cur;
        }
        return g;
    }

    /** Linear interpolation of a gain curve at timeline time t (us); 1 when no curve. */
    static float at(float[] g, long t) {
        if (g == null || g.length == 0) return 1f;
        double f = t * (RATE / 1e6);
        if (f <= 0) return g[0];
        int i = (int) f;
        if (i >= g.length - 1) return g[g.length - 1];
        float w = (float) (f - i);
        return g[i] * (1 - w) + g[i + 1] * w;
    }
}
