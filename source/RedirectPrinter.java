/**
 * RedirectPrinter - Prints redirect chain events in CommonRails component format.
 */
public class RedirectPrinter
{
    private final int hash = this.hashCode();

    public void printHop(int count, int status, String from, String to)
    {
        CommonRails.printSystemComponent(this, hash,
            "Redirect #" + count + " [" + status + "] " + from + " -> " + to);
    }

    public void printComplete(int hops)
    {
        CommonRails.printSystemComponent(this, hash, "Redirect chain complete (" + hops + " hops)");
    }

    public void printPruned(String reason, int hops, String url)
    {
        CommonRails.printSystemComponent(this, hash,
            "[PRUNED] " + reason + " after " + hops + " hops: " + url);
    }

    public void printMaxReached(int max, String url)
    {
        CommonRails.printSystemComponent(this, hash,
            "Max redirects (" + max + ") reached for: " + url);
    }
}
