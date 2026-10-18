package com.christopher.strata;

/** Exact input shared by source marks and timeline placement. */
final class EditTime {
    static long parse(String input, int fps) {
        String s = input.trim();
        try {
            String[] parts = s.split(":", -1);
            double seconds;
            if (parts.length == 1) seconds = Double.parseDouble(s);
            else if (parts.length == 2) {
                long minutes = Long.parseLong(parts[0]);
                double sec = Double.parseDouble(parts[1]);
                if (minutes < 0 || sec < 0 || sec >= 60) throw new NumberFormatException();
                seconds = minutes * 60.0 + sec;
            } else if (parts.length == 3) {
                long minutes = Long.parseLong(parts[0]);
                int sec = Integer.parseInt(parts[1]), frames = Integer.parseInt(parts[2]);
                if (minutes < 0 || sec < 0 || sec >= 60 || frames < 0 || frames >= fps) throw new NumberFormatException();
                seconds = minutes * 60.0 + sec + frames / (double) fps;
            } else throw new NumberFormatException();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86400) throw new NumberFormatException();
            return Timeline.quant(Math.round(seconds * 1e6), fps);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Use seconds, m:ss.sss or m:ss:ff (frames).");
        }
    }
}
