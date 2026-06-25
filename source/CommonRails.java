import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * CommonRails - Shared output utilities for Captain Marvell system.
 * All console printing should go through this class.
 *
 * After a download completes, output pauses and shows active thread count
 * instead of printing past the latest download line.
 */
public class CommonRails
{
    private static final String ORANGE_BLOCK = "\u001b[38;5;208m█\u001b[0m";
    private static final String SPACE = " ";
    private static final int TOTAL_BARS = 20;
    private static final int LINE_WIDTH = 120;

    // Tracks active download/worker threads
    private static final AtomicInteger activeThreads = new AtomicInteger(0);

    // When true, a download just completed — hold output on thread status line
    private static volatile boolean pauseAfterDownload = false;
    private static volatile String lastDownloadLabel = "";

    public static void incrementActiveThreads() { activeThreads.incrementAndGet(); }
    public static void decrementActiveThreads() { activeThreads.decrementAndGet(); }
    public static int getActiveThreadCount() { return activeThreads.get(); }

    /**
     * Called when a download finishes. Pauses further progress output and
     * displays thread count on the current line.
     */
    public static void notifyDownloadComplete(String label)
    {
        lastDownloadLabel = label;
        pauseAfterDownload = true;
        printThreadStatus();
    }

    /**
     * Resumes normal output after the pause.
     */
    public static void resumeOutput()
    {
        pauseAfterDownload = false;
    }

    public static void printProgressBar(int percent)
    {
        printProgressBar(percent, "");
    }

    public static void printProgressBar(int percent, String label)
    {
        // If paused after a download, show thread status instead
        if (pauseAfterDownload)
        {
            printThreadStatus();
            return;
        }

        int clamped = Math.max(0, Math.min(100, percent));
        int completedBars = (clamped * TOTAL_BARS) / 100;
        int remainingBars = TOTAL_BARS - completedBars;

        StringBuilder bar = new StringBuilder();
        bar.append("[");
        for (int i = 0; i < completedBars; i++)
            bar.append(ORANGE_BLOCK);
        for (int i = 0; i < remainingBars; i++)
            bar.append(SPACE);
        bar.append("] ").append(String.format("%3d%%", clamped));

        int barVisibleLen = 1 + TOTAL_BARS + 2 + 4;

        String left = (label != null && !label.isEmpty()) ? "    " + label : "    ";
        int leftLen = left.length();

        int spacing = LINE_WIDTH - leftLen - barVisibleLen;
        if (spacing < 1) spacing = 1;

        StringBuilder line = new StringBuilder();
        line.append(left);
        for (int i = 0; i < spacing; i++)
            line.append(' ');
        line.append(bar);

        System.out.print("\r" + line.toString());
    }

    /**
     * Prints the active thread count status line (overwrites current line).
     */
    public static void printThreadStatus()
    {
        String status = "    [PAUSED] Last download: " + lastDownloadLabel
            + " | Active threads: " + activeThreads.get();
        // Pad to line width to overwrite previous content
        StringBuilder sb = new StringBuilder(status);
        while (sb.length() < LINE_WIDTH) sb.append(' ');
        System.out.print("\r" + sb.toString());
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
