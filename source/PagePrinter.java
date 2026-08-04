/**
 * PagePrinter - Prints page content summaries and image diagnostics in CommonRails component format.
 */
public class PagePrinter
{
    private final int hash = this.hashCode();

    public void printSummary(String url, int audio, int images, int files)
    {
        if (audio + images + files > 0)
        {
            CommonRails.printSystemComponent(this, hash,
                "Page: " + url + " | audio=" + audio + " images=" + images + " files=" + files);
        }
    }

    /**
     * Prints the image yield diagnostic — how many images are on the page HTML
     * vs how many we are actually capturing and downloading.
     */
    public void printImageDiagnostic(String url, PageImageDiagnostic diag)
    {
        // Always show the yield line
        String yieldColor;
        double pct = diag.yieldPercent();
        if (pct >= 70) yieldColor = "\u001b[32m";       // green — good yield
        else if (pct >= 30) yieldColor = "\u001b[33m";  // yellow — moderate
        else yieldColor = "\u001b[31m";                  // red — poor yield

        CommonRails.printSystemComponent(this, hash,
            "[IMAGE DIAG] " + url);
        CommonRails.printSystemComponent(this, hash,
            "  " + yieldColor + diag.summary() + "\u001b[0m");

        // Show missed breakdown if yield is below 70%
        if (pct < 70 && diag.missedTotal > 0)
        {
            String[] lines = diag.missedBreakdown().split("\n");
            for (String line : lines)
            {
                CommonRails.printSystemComponent(this, hash, "  " + line);
            }
        }
    }

    public void printSkip(String path)
    {
        CommonRails.printSystemComponent(this, hash, "[SKIP] Already exists: " + path);
    }

    public void printSuccess(String path, long bytes)
    {
        CommonRails.printSystemComponent(this, hash, "[SUCCESS] " + path + " (" + bytes + " bytes)");
    }

    public void printFailed(String reason)
    {
        CommonRails.printSystemComponent(this, hash, "[FAILED] " + reason);
    }
}
