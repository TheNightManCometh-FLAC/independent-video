package com.christopher.strata;

import java.util.ArrayList;

/** Keep automation on the same footage when the owning clip loses its head or is cut. */
final class AutomationEdits {
    static void shift(ArrayList<Layer> dest, ArrayList<Layer> original, VProject before,
                      int owner, long start, long duration, long removed, boolean split) {
        for (int i = 0; i < dest.size() && i < original.size(); i++) {
            dest.get(i).links.clear();
            for (Music.Link old : original.get(i).links) {
                Music.Link k = old.copy();
                AudioControl.Source a = AudioControl.resolve(before, old.audio, owner, start + Math.max(0, old.startUs));
                if (a != null && (old.phaseUri == null || !old.phaseUri.equals(a.uri))) {
                    k.phaseUs = old.local ? Math.max(a.in(), a.at(start + old.startUs)) : a.in();
                    k.phaseUri = a.uri;
                }
                k.startUs -= removed;
                if (old.endUs >= 0 || split) k.endUs = Math.max(0, (old.endUs < 0 ? duration : old.endUs) - removed);
                k.amountKeys.shift(-removed);
                dest.get(i).links.add(k);
            }
        }
    }
}
