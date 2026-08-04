import java.util.*;

/**
 * Diagnostic data for image yield analysis on a single page.
 * Captures what the page contains vs what we extract, and why the gap exists.
 */
public class PageImageDiagnostic
{
    // --- What the page contains (raw HTML counts) ---
    public int totalImgTags = 0;         // <img> elements
    public int totalImageHrefs = 0;      // <a href="*.jpg|png|...">
    public int totalCssBackgrounds = 0;  // background-image: url(...)
    public int totalLazyLoad = 0;        // data-src attributes
    public int totalSrcset = 0;          // srcset attributes
    public int totalMetaImages = 0;      // og:image, twitter:image
    public int totalOnPage = 0;          // sum of all above

    // --- What we extracted ---
    public int extractedCount = 0;       // Images that passed through our pipeline
    public int confirmedCaptured = 0;    // img src values we confirmed are in extracted set

    // --- Why images are missing ---
    public int missedTotal = 0;                 // totalOnPage - extractedCount
    public int missedReasonDataUri = 0;         // data: URIs (inline base64, can't download)
    public int missedReasonJavascript = 0;      // javascript: pseudo-URLs
    public int missedReasonBlankSrc = 0;        // empty or null src
    public int missedReasonNoExtension = 0;     // URL has no image file extension (dynamic URL)
    public int missedReasonNotCaptured = 0;     // Has extension but didn't make it through pipeline

    // Sample URLs for debugging
    public List<String> sampleNoExtension = new ArrayList<>();
    public List<String> sampleNotCaptured = new ArrayList<>();

    /**
     * Returns the yield percentage (extracted / total on page).
     */
    public double yieldPercent()
    {
        if (totalOnPage == 0) return 0.0;
        return (extractedCount * 100.0) / totalOnPage;
    }

    /**
     * Returns a compact summary string for console output.
     */
    public String summary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("YIELD: %d/%d (%.1f%%)", extractedCount, totalOnPage, yieldPercent()));
        sb.append(String.format(" | img=%d href=%d css=%d lazy=%d srcset=%d meta=%d",
            totalImgTags, totalImageHrefs, totalCssBackgrounds, totalLazyLoad, totalSrcset, totalMetaImages));
        return sb.toString();
    }

    /**
     * Returns detailed breakdown of missed images for debugging.
     */
    public String missedBreakdown()
    {
        if (missedTotal <= 0) return "No missed images.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("MISSED: %d images not captured", missedTotal));

        if (missedReasonDataUri > 0)
            sb.append(String.format("\n    data: URIs (base64 inline):   %d", missedReasonDataUri));
        if (missedReasonJavascript > 0)
            sb.append(String.format("\n    javascript: pseudo-URLs:      %d", missedReasonJavascript));
        if (missedReasonBlankSrc > 0)
            sb.append(String.format("\n    blank/empty src:              %d", missedReasonBlankSrc));
        if (missedReasonNoExtension > 0)
        {
            sb.append(String.format("\n    no image extension (dynamic): %d", missedReasonNoExtension));
            for (String sample : sampleNoExtension)
                sb.append("\n      e.g. ").append(sample);
        }
        if (missedReasonNotCaptured > 0)
        {
            sb.append(String.format("\n    has extension, not captured:  %d", missedReasonNotCaptured));
            for (String sample : sampleNotCaptured)
                sb.append("\n      e.g. ").append(sample);
        }

        return sb.toString();
    }
}
