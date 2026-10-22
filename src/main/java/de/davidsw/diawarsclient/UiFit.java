package de.davidsw.diawarsclient;

/**
 * Helps fixed-width button rows fit narrow GUI widths. The GUI width is not
 * fixed: it depends on the aspect ratio and GUI scale, and unusual setups
 * (4:3 / 5:4 monitors, large windows, small game windows) easily produce a
 * much narrower GUI than the usual 16:9 layout. Fixed rows such as
 * {@code 3 * 110 px} buttons are then cut off at the left/right edges.
 * {@code fit} scales the row's widths proportionally and squeezes the gaps,
 * so the row always stays inside the screen; the caller still centres it.
 */
final class UiFit {
    private UiFit() {
    }

    /**
     * Scales a row of button widths (with one gap shared between all
     * neighbours) down until the total fits into {@code avail}. Returns
     * {@code widths.length + 1} entries: the scaled widths followed by the
     * scaled gap. A row that already fits is returned unchanged.
     */
    static int[] fit(int[] widths, int gap, int avail, int minButton, int minGap) {
        int count = widths.length;
        int widthSum = 0;
        for (int width : widths) {
            widthSum += width;
        }
        int total = widthSum + gap * (count - 1);
        int[] result = new int[count + 1];
        if (total <= avail) {
            for (int i = 0; i < count; i++) {
                result[i] = widths[i];
            }
            result[count] = gap;
            return result;
        }
        double scale = (double) avail / Math.max(1, total);
        int scaledSum = 0;
        for (int i = 0; i < count; i++) {
            result[i] = Math.max(minButton, (int) Math.round(widths[i] * scale));
            scaledSum += result[i];
        }
        // Squeeze the gaps into the space the (possibly clamped) widths left
        // over; if even the minimum widths overflow, the row overflows a
        // little in both directions instead of being cut off at one side.
        int leftoverGap = (avail - scaledSum) / Math.max(1, count - 1);
        result[count] = Math.max(minGap, Math.min((int) Math.round(gap * scale), leftoverGap));
        return result;
    }
}
