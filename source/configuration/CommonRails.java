package configuration;

public final class CommonRails
{
    public static java.util.function.Consumer<Exception> EXCEPTION_SINK = e -> {e.printStackTrace(System.err);};

    private static final Boolean USE_COLORED_OUTPUT = true;

    private CommonRails() {}

    public static void printSystemComponent(Object owner, int hash, String line)
    {
        String oid = Integer.toHexString(hash);
        System.out.println("[" + oid + "] " + line);
    }

    public static void printSystemComponent(Object owner, int hash, String line, String color)
    {
        String oid = Integer.toHexString(hash);
        System.out.println(color + "[" + oid + "] " + line + "\u001B[0m");
    }

    public static void printShutdownSignal(final Object OWNER, final String MODULE, final String PHASE)
    {
        printSystemComponent(OWNER, OWNER.hashCode(), ". [shutdown] " + PHASE + " " + MODULE + " .", "\u001B[31m");
    }

    public static void delayableFinePrinter(final String TEXT, final int DELAY)
    {
        if (!USE_COLORED_OUTPUT)
        {
            System.out.println(TEXT);
            System.out.print("\u001B[0m");
            return;
        }

        int[] codes = new int[20];
        for (int k = 0; k < 20; k++) codes[k] = 236 + k;

        try
        {
            for (int color : codes)
            {
                System.out.print("\033[38;5;" + color + "m" + TEXT + "\r");
                Thread.sleep(DELAY > 0 ? DELAY : 21);
            }
            System.out.print("\u001B[0m");
            System.out.println(TEXT);
            System.out.print("\u001B[0m");
        }
        catch (Exception e)
        {
            EXCEPTION_SINK.accept(e);
        }
    }

    public static void setExceptionSink(final java.util.function.Consumer<Exception> SINK)
    {
        if (SINK != null) EXCEPTION_SINK = SINK;
    }
}
