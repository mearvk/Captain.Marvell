import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * CommonRails - Shared output utilities for Captain Marvell system.
 * Loads print configuration from source/configuration/print-method.xml.
 * Uses grayscale fade-in printing (dark grey -> white) with ANSI 256-color codes.
 */
public class CommonRails
{
    private static final String ORANGE_BLOCK = "\u001b[38;5;208m█\u001b[0m";
    private static final String SPACE = " ";
    private static final int TOTAL_BARS = 20;
    private static final int LINE_WIDTH = 120;
    private static final String RESET = "\u001B[0m";

    // Configurable from print-method.xml
    private static String PREFIX = "-- : ";
    private static String OID_LABEL = "Object ID";
    private static String OID_FORMAT = "%010d";
    private static String DATE_LABEL = "Date";
    private static String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss z";
    private static String DATE_TIMEZONE = "America/New_York";
    private static String CURRENT_LABEL = "Current";
    private static String CURRENT_PREFIX = "@";
    private static int PAD_WIDTH = 39;
    private static String DECORATOR_START = ".";
    private static String DECORATOR_END = ".";
    private static int FADE_STEPS = 20;
    private static int FADE_DELAY_MS = 20;
    private static int POST_FADE_DELAY_MS = 200;
    private static boolean COLORED_OUTPUT = true;
    private static String OID_FORMAT_ZERO = "0000000000";

    // Tracks active download/worker threads
    private static final AtomicInteger activeThreads = new AtomicInteger(0);
    private static volatile boolean pauseAfterDownload = false;
    private static volatile String lastDownloadLabel = "";

    static { loadConfig(); }

    private static void loadConfig()
    {
        try
        {
            // Try to find print-method.xml relative to working directory
            Path xmlPath = Paths.get("source", "configuration", "print-method.xml");
            if (!Files.exists(xmlPath))
                xmlPath = Paths.get("configuration", "print-method.xml");
            if (!Files.exists(xmlPath))
                return;

            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlPath.toFile());
            doc.getDocumentElement().normalize();

            NodeList blocks = doc.getElementsByTagName("block");
            for (int i = 0; i < blocks.getLength(); i++)
            {
                Element el = (Element) blocks.item(i);
                String name = el.getAttribute("name");
                switch (name)
                {
                    case "Prefix" -> PREFIX = text(el, "value", PREFIX);
                    case "ObjectId" -> {
                        OID_LABEL = text(el, "label", OID_LABEL);
                        OID_FORMAT = text(el, "format", OID_FORMAT);
                    }
                    case "Date" -> {
                        DATE_LABEL = text(el, "label", DATE_LABEL);
                        DATE_FORMAT = text(el, "format", DATE_FORMAT);
                        DATE_TIMEZONE = text(el, "timezone", DATE_TIMEZONE);
                    }
                    case "Current" -> {
                        CURRENT_LABEL = text(el, "label", CURRENT_LABEL);
                        CURRENT_PREFIX = text(el, "prefix", CURRENT_PREFIX);
                        PAD_WIDTH = Integer.parseInt(text(el, "pad-width", String.valueOf(PAD_WIDTH)));
                    }
                    case "Message" -> {
                        DECORATOR_START = text(el, "decorator-start", DECORATOR_START);
                        DECORATOR_END = text(el, "decorator-end", DECORATOR_END);
                    }
                }
            }

            NodeList grace = doc.getElementsByTagName("grace");
            if (grace.getLength() > 0)
            {
                Element g = (Element) grace.item(0);
                FADE_STEPS = Integer.parseInt(text(g, "fade-steps", String.valueOf(FADE_STEPS)));
                FADE_DELAY_MS = Integer.parseInt(text(g, "fade-delay-ms", String.valueOf(FADE_DELAY_MS)));
                POST_FADE_DELAY_MS = Integer.parseInt(text(g, "post-fade-delay-ms", String.valueOf(POST_FADE_DELAY_MS)));
            }

            NodeList control = doc.getElementsByTagName("control");
            if (control.getLength() > 0)
            {
                Element c = (Element) control.item(0);
                COLORED_OUTPUT = Boolean.parseBoolean(text(c, "colored-output", "true"));
            }
        }
        catch (Exception ignored) {}
    }

    private static String text(Element el, String tag, String def)
    {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return def;
        String v = nl.item(0).getTextContent().trim();
        return v.isEmpty() ? def : v;
    }

    public static void incrementActiveThreads() { activeThreads.incrementAndGet(); }
    public static void decrementActiveThreads() { activeThreads.decrementAndGet(); }
    public static int getActiveThreadCount() { return activeThreads.get(); }

    /**
     * Grayscale fade-in print: dark grey -> full white using ANSI 256-color codes 236..255.
     * Uses \r to overwrite in place, then prints final line once.
     */
    public static void delayableFinePrinter(final String text, final int delay)
    {
        if (!COLORED_OUTPUT)
        {
            System.out.print("\r" + text + "\r");
            System.out.flush();
            return;
        }

        int[] codes = new int[FADE_STEPS];
        for (int k = 0; k < FADE_STEPS; k++) codes[k] = 236 + k;

        try
        {
            for (int color : codes)
            {
                System.out.print("\r\033[38;5;" + color + "m" + text + RESET);
                System.out.flush();
                Thread.sleep(delay > 0 ? delay : FADE_DELAY_MS);
            }
            System.out.print("\r" + RESET + text + "\n");
            System.out.flush();
        }
        catch (Exception e)
        {
            System.out.print("\r" + RESET + text + "\n");
            System.out.flush();
        }
    }

    /**
     * Called when a download finishes.
     */
    public static void notifyDownloadComplete(String label)
    {
        lastDownloadLabel = label;
        pauseAfterDownload = true;
        printThreadStatus();
    }

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
        printProgressBar(percent, label, null);
    }

    public static void printProgressBar(int percent, String label, Object owner)
    {
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

        String msg = (label != null && !label.isEmpty()) ? label + " " + bar : bar.toString();

        // Build component-style prefix
        String simple = owner != null
            ? (owner instanceof Class<?> c ? c.getSimpleName() : owner.getClass().getSimpleName())
            : "SearchEngineClient";
        String hashStr = owner != null ? String.format(OID_FORMAT, owner.hashCode()) : OID_FORMAT_ZERO;
        String oidColor = "\u001b[38;5;208m";

        SimpleDateFormat fmt = new SimpleDateFormat(DATE_FORMAT);
        fmt.setTimeZone(TimeZone.getTimeZone(DATE_TIMEZONE));
        String ts = fmt.format(new Date());
        String padded = padClassname(simple);

        String line = PREFIX + "[" + OID_LABEL + ": " + oidColor + hashStr + RESET + "] ["
            + DATE_LABEL + ": " + ts + "] " + padded + " " + msg;

        System.out.print("\r" + line);
        System.out.flush();
    }

    public static void printThreadStatus()
    {
        String status = "    [PAUSED] Last download: " + lastDownloadLabel
            + " | Active threads: " + activeThreads.get();
        StringBuilder sb = new StringBuilder(status);
        while (sb.length() < LINE_WIDTH) sb.append(' ');
        System.out.print("\r" + sb.toString());
    }

    /**
     * Component-style print with object ID, timestamp, padded class name, and fade-in.
     * Format: -- : [Object ID: XXXXXXXXXX] [Date: ...] [Current: @ClassName     ] . message .
     */
    public static void printSystemComponent(Object owner, int hash, String line)
    {
        String simple = owner instanceof Class<?> c ? c.getSimpleName() : owner.getClass().getSimpleName();
        String hashStr = String.format(OID_FORMAT, hash);
        String oidColor = "\u001b[38;5;208m";

        SimpleDateFormat fmt = new SimpleDateFormat(DATE_FORMAT);
        fmt.setTimeZone(TimeZone.getTimeZone(DATE_TIMEZONE));
        String ts = fmt.format(new Date());

        String padded = padClassname(simple);

        // Apply decorators
        String msg = DECORATOR_START + " " + line + " " + DECORATOR_END;

        String ref = PREFIX + "[" + OID_LABEL + ": " + oidColor + hashStr + RESET + "] ["
            + DATE_LABEL + ": " + ts + "] " + padded + " " + msg;
        delayableFinePrinter(ref, FADE_DELAY_MS);
    }

    private static String padClassname(String name)
    {
        String inner = CURRENT_LABEL + ": " + CURRENT_PREFIX + name;
        int pad = Math.max(0, PAD_WIDTH - inner.length());
        return "[" + inner + " ".repeat(pad) + "]";
    }

    public static void println(String message)
    {
        delayableFinePrinter(message, FADE_DELAY_MS);
    }

    public static void println()
    {
        System.out.println();
    }

    public static void printError(String message)
    {
        System.err.println("\u001b[31m" + message + "\u001b[0m");
    }
}
