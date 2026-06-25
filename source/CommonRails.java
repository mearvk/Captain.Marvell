/**
 * CommonRails - Shared output utilities for Captain Marvell system.
 * All console printing should go through this class.
 */
public class CommonRails
{
    private static final String ORANGE_BLOCK = "\u001b[38;5;208m█\u001b[0m";
    private static final String SPACE = " ";
    private static final int TOTAL_BARS = 20;
    private static final int LINE_WIDTH = 120;

    public static void printProgressBar(int percent)
    {
        printProgressBar(percent, "");
    }

    public static void printProgressBar(int percent, String label)
    {
        int clamped = Math.max(0, Math.min(100, percent));
        int completedBars = (clamped * TOTAL_BARS) / 100;
        int remainingBars = TOTAL_BARS - completedBars;

        // Build progress bar string
        StringBuilder bar = new StringBuilder();
        bar.append("[");
        for (int i = 0; i < completedBars; i++)
            bar.append(ORANGE_BLOCK);
        for (int i = 0; i < remainingBars; i++)
            bar.append(SPACE);
        bar.append("] ").append(String.format("%3d%%", clamped));

        // Bar visible length (without ANSI codes): [ + 20 chars + ] + space + 4 chars = 27
        int barVisibleLen = 1 + TOTAL_BARS + 2 + 4;

        // Left side: label
        String left = (label != null && !label.isEmpty()) ? "    " + label : "    ";
        int leftLen = left.length();

        // Compute spacing to push bar to far right
        int spacing = LINE_WIDTH - leftLen - barVisibleLen;
        if (spacing < 1) spacing = 1;

        StringBuilder line = new StringBuilder();
        line.append(left);
        for (int i = 0; i < spacing; i++)
            line.append(' ');
        line.append(bar);

        System.out.print("\r" + line.toString());
    }

    public static void println(String message)
    {
        System.out.println(message);
    }

    public static void printError(String message)
    {
        System.err.println("\u001b[31m" + message + "\u001b[0m");
    }
}
