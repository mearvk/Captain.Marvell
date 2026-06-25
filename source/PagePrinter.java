/**
 * PagePrinter - Prints page content summaries in CommonRails component format.
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
