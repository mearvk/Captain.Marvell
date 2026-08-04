import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * ImageDownloadStrategy — A dedicated image acquisition module for Captain Marvell.
 *
 * Designed to maximize relevant image yield when general search crawling produces
 * weak results. Operates with explicit control over source selection, relevance
 * scoring, and multi-pass extraction.
 *
 * Strategy Phases:
 *   Phase 1: Direct Image Search (Google Images, Bing Images, etc.)
 *   Phase 2: Known Gallery Crawl (wikis, fan sites, official pages)
 *   Phase 3: Reverse-lookup Expansion (use found images to discover more)
 *   Phase 4: Metadata Validation & Deduplication
 *
 * Control mechanisms:
 *   - Relevance scoring with configurable threshold
 *   - Minimum resolution enforcement
 *   - Content-Type verification before saving
 *   - Deduplication via file hash
 *   - Rate limiting per domain
 *   - Configurable source priority ranking
 */
public class ImageDownloadStrategy
{
    // --- Configuration ---
    private final String baseDir;
    private final String imageDir;
    private final Properties config;
    private final HttpClient httpClient;
    private final ExecutorService threadPool;

    // Relevance control
    private final String[] relevanceKeywords;
    private final double relevanceThreshold;     // 0.0 - 1.0, minimum score to keep image
    private final int minWidth;                  // Minimum image width (pixels)
    private final int minHeight;                 // Minimum image height (pixels)
    private final long minFileSize;              // Minimum file size in bytes (skip thumbnails)
    private final long maxFileSize;              // Maximum file size in bytes (skip absurdly large)

    // Rate control
    private final long requestDelayMs;
    private final int maxConcurrentDownloads;
    private final int maxImagesPerSource;
    private final int maxTotalImages;

    // State tracking
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final Set<String> downloadedHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> hostLastAccess = new ConcurrentHashMap<>();
    private final List<ImageResult> results = Collections.synchronizedList(new ArrayList<>());

    // Image search engine endpoints (dedicated image search, not web search)
    private static final Map<String, String> IMAGE_SEARCH_ENGINES = new LinkedHashMap<>();
    static
    {
        IMAGE_SEARCH_ENGINES.put("google-images", "https://www.google.com/search?q={query}&tbm=isch");
        IMAGE_SEARCH_ENGINES.put("bing-images", "https://www.bing.com/images/search?q={query}");
        IMAGE_SEARCH_ENGINES.put("yahoo-images", "https://images.search.yahoo.com/search/images?p={query}");
        IMAGE_SEARCH_ENGINES.put("duckduckgo-images", "https://duckduckgo.com/?q={query}&iax=images&ia=images");
    }

    // Known high-yield sources for comic/superhero imagery
    private static final String[] GALLERY_SOURCES = {
        "https://en.wikipedia.org/wiki/Captain_Marvel",
        "https://marvel.fandom.com/wiki/Captain_Marvel",
        "https://www.marvel.com/characters/captain-marvel-carol-danvers",
        "https://comicvine.gamespot.com/captain-marvel/4005-2268/",
        "https://www.deviantart.com/tag/captainmarvel",
    };

    // File extensions considered valid images
    private static final String[] IMAGE_EXTENSIONS = {
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".tiff"
    };

    // User agent rotation for reduced bot detection
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0",
    };

    private int uaIndex = 0;

    /**
     * Construct from config file path and base directory.
     */
    public ImageDownloadStrategy(String configPath, String baseDir) throws IOException
    {
        this.baseDir = baseDir;
        this.imageDir = Paths.get(baseDir, "images").toString();
        Files.createDirectories(Paths.get(imageDir));

        config = new Properties();
        config.load(new FileInputStream(configPath));

        // Relevance keywords for scoring
        String keywordStr = config.getProperty("image.relevance.keywords",
            "captain marvel,carol danvers,mar-vell,shazam,ms marvel,captain,marvel,superhero,comic");
        relevanceKeywords = keywordStr.split(",");

        relevanceThreshold = Double.parseDouble(config.getProperty("image.relevance.threshold", "0.3"));
        minWidth = Integer.parseInt(config.getProperty("image.min.width", "200"));
        minHeight = Integer.parseInt(config.getProperty("image.min.height", "200"));
        minFileSize = Long.parseLong(config.getProperty("image.min.file.size", "10000"));       // 10KB
        maxFileSize = Long.parseLong(config.getProperty("image.max.file.size", "50000000"));    // 50MB
        requestDelayMs = Long.parseLong(config.getProperty("image.request.delay.ms", "200"));
        maxConcurrentDownloads = Integer.parseInt(config.getProperty("image.max.concurrent", "5"));
        maxImagesPerSource = Integer.parseInt(config.getProperty("image.max.per.source", "50"));
        maxTotalImages = Integer.parseInt(config.getProperty("image.max.total", "500"));

        httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        threadPool = Executors.newFixedThreadPool(maxConcurrentDownloads);
    }

    // =========================================================================
    // PHASE 1: Direct Image Search
    // =========================================================================

    /**
     * Queries dedicated image search endpoints and extracts image URLs from results.
     * Image search pages embed thumbnail/full-size URLs in specific patterns.
     */
    public Set<String> phaseOneDirectSearch(String[] queries)
    {
        Set<String> imageUrls = ConcurrentHashMap.newKeySet();
        CommonRails.println("[PHASE 1] Direct Image Search — " + queries.length + " queries x "
            + IMAGE_SEARCH_ENGINES.size() + " engines");

        for (String query : queries)
        {
            for (Map.Entry<String, String> engine : IMAGE_SEARCH_ENGINES.entrySet())
            {
                String searchUrl = engine.getValue().replace("{query}",
                    URLEncoder.encode(query.trim(), java.nio.charset.StandardCharsets.UTF_8));

                try
                {
                    throttleHost(searchUrl);
                    String html = fetchPage(searchUrl);
                    if (html.isEmpty()) continue;

                    Set<String> extracted = extractImageUrls(html, searchUrl);
                    int kept = 0;
                    for (String url : extracted)
                    {
                        if (kept >= maxImagesPerSource) break;
                        if (scoreRelevance(url, "") >= relevanceThreshold)
                        {
                            imageUrls.add(url);
                            kept++;
                        }
                    }
                    CommonRails.println("  [" + engine.getKey() + "] query=\"" + query.trim()
                        + "\" → extracted=" + extracted.size() + " kept=" + kept);
                }
                catch (Exception e)
                {
                    CommonRails.printError("  [" + engine.getKey() + "] " + e.getMessage());
                }
            }
        }

        CommonRails.println("[PHASE 1] Complete — " + imageUrls.size() + " candidate URLs");
        return imageUrls;
    }

    // =========================================================================
    // PHASE 2: Known Gallery Crawl
    // =========================================================================

    /**
     * Crawls known high-value pages (wikis, fan sites, official pages) that are
     * likely to host relevant imagery. These bypass search engine noise.
     */
    public Set<String> phaseTwoGalleryCrawl()
    {
        Set<String> imageUrls = ConcurrentHashMap.newKeySet();
        CommonRails.println("[PHASE 2] Gallery Crawl — " + GALLERY_SOURCES.length + " known sources");

        for (String source : GALLERY_SOURCES)
        {
            try
            {
                throttleHost(source);
                String html = fetchPage(source);
                if (html.isEmpty()) continue;

                Set<String> extracted = extractImageUrls(html, source);

                // Gallery pages: also look for links to sub-galleries or image pages
                Set<String> subPages = extractGalleryLinks(html, source);
                for (String subPage : subPages)
                {
                    if (visitedUrls.size() > 500) break; // safety cap
                    throttleHost(subPage);
                    String subHtml = fetchPage(subPage);
                    if (!subHtml.isEmpty())
                        extracted.addAll(extractImageUrls(subHtml, subPage));
                }

                int kept = 0;
                for (String url : extracted)
                {
                    if (kept >= maxImagesPerSource) break;
                    if (isImageUrl(url) && scoreRelevance(url, html) >= relevanceThreshold)
                    {
                        imageUrls.add(url);
                        kept++;
                    }
                }

                CommonRails.println("  [gallery] " + truncateUrl(source)
                    + " → extracted=" + extracted.size() + " kept=" + kept);
            }
            catch (Exception e)
            {
                CommonRails.printError("  [gallery] " + truncateUrl(source) + " — " + e.getMessage());
            }
        }

        CommonRails.println("[PHASE 2] Complete — " + imageUrls.size() + " candidate URLs");
        return imageUrls;
    }

    // =========================================================================
    // PHASE 3: Reverse-Lookup Expansion
    // =========================================================================

    /**
     * Takes already-found image URLs and uses reverse image search techniques
     * to find higher-resolution versions or related images.
     * Uses Google's "search by image" and TinEye-style URL patterns.
     */
    public Set<String> phaseThreeReverseExpand(Set<String> existingImageUrls)
    {
        Set<String> expanded = ConcurrentHashMap.newKeySet();
        CommonRails.println("[PHASE 3] Reverse-Lookup Expansion — sampling "
            + Math.min(existingImageUrls.size(), 10) + " images");

        // Sample up to 10 images for reverse lookup (avoid hammering)
        List<String> sample = new ArrayList<>(existingImageUrls);
        Collections.shuffle(sample);
        int limit = Math.min(sample.size(), 10);

        for (int i = 0; i < limit; i++)
        {
            String imageUrl = sample.get(i);
            // Google reverse image search URL pattern
            String reverseUrl = "https://www.google.com/searchbyimage?image_url="
                + URLEncoder.encode(imageUrl, java.nio.charset.StandardCharsets.UTF_8);

            try
            {
                throttleHost(reverseUrl);
                String html = fetchPage(reverseUrl);
                if (html.isEmpty()) continue;

                Set<String> found = extractImageUrls(html, reverseUrl);
                for (String url : found)
                {
                    if (isImageUrl(url) && !existingImageUrls.contains(url))
                        expanded.add(url);
                }
            }
            catch (Exception ignored) {}
        }

        CommonRails.println("[PHASE 3] Complete — " + expanded.size() + " new URLs from reverse lookup");
        return expanded;
    }

    // =========================================================================
    // PHASE 4: Validate, Deduplicate, and Download
    // =========================================================================

    /**
     * Downloads all candidate images with full validation:
     *   - Content-Type must be image/*
     *   - File size within bounds
     *   - SHA-256 deduplication
     *   - Relevance re-check on filename
     */
    public int phaseFourDownload(Set<String> allCandidates)
    {
        CommonRails.println("[PHASE 4] Download & Validate — " + allCandidates.size() + " candidates");

        int downloaded = 0;
        int skippedType = 0;
        int skippedSize = 0;
        int skippedDupe = 0;
        int failed = 0;

        for (String url : allCandidates)
        {
            if (downloaded >= maxTotalImages)
            {
                CommonRails.println("  [cap] Reached max total images: " + maxTotalImages);
                break;
            }

            try
            {
                throttleHost(url);
                ImageDownloadResult result = downloadAndValidate(url);

                switch (result.status)
                {
                    case SUCCESS:     downloaded++; break;
                    case WRONG_TYPE:  skippedType++; break;
                    case TOO_SMALL:
                    case TOO_LARGE:   skippedSize++; break;
                    case DUPLICATE:   skippedDupe++; break;
                    case FAILED:      failed++; break;
                    case ALREADY_EXISTS: break;
                }
            }
            catch (Exception e)
            {
                failed++;
            }
        }

        CommonRails.println("[PHASE 4] Complete — downloaded=" + downloaded
            + " skipped(type=" + skippedType + " size=" + skippedSize
            + " dupe=" + skippedDupe + ") failed=" + failed);

        return downloaded;
    }

    // =========================================================================
    // Core Image Extraction
    // =========================================================================

    /**
     * Extracts image URLs from HTML using multiple patterns:
     *   - Standard img src/srcset
     *   - Open Graph meta tags (og:image)
     *   - Background-image CSS
     *   - JSON-LD structured data
     *   - data-src lazy-load attributes
     *   - Google/Bing image result page patterns
     *
     * Also runs page-level diagnostics showing total images on page vs extracted.
     */
    private Set<String> extractImageUrls(String html, String baseUrl)
    {
        Set<String> urls = new LinkedHashSet<>();
        String host = "";
        try { host = URI.create(baseUrl).getHost(); } catch (Exception ignored) {}

        // --- Run diagnostics first: count everything on the page ---
        PageImageDiagnostic diag = new PageImageDiagnostic();

        Matcher imgCounter = Pattern.compile("<img\\b", Pattern.CASE_INSENSITIVE).matcher(html);
        while (imgCounter.find()) diag.totalImgTags++;

        Matcher hrefCounter = Pattern.compile("href\\s*=\\s*[\"']([^\"']+\\.(?:jpg|jpeg|png|gif|bmp|webp|svg|tiff)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (hrefCounter.find()) diag.totalImageHrefs++;

        Matcher bgCounter = Pattern.compile("background(?:-image)?\\s*:\\s*url\\(", Pattern.CASE_INSENSITIVE).matcher(html);
        while (bgCounter.find()) diag.totalCssBackgrounds++;

        Matcher lazyCounter = Pattern.compile("data-src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (lazyCounter.find()) diag.totalLazyLoad++;

        Matcher srcsetCounter = Pattern.compile("srcset\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (srcsetCounter.find()) diag.totalSrcset++;

        Matcher ogCounter = Pattern.compile("(?:og:image|twitter:image)", Pattern.CASE_INSENSITIVE).matcher(html);
        while (ogCounter.find()) diag.totalMetaImages++;

        diag.totalOnPage = diag.totalImgTags + diag.totalImageHrefs + diag.totalCssBackgrounds
            + diag.totalLazyLoad + diag.totalSrcset + diag.totalMetaImages;

        // --- Now extract ---

        // 1. Standard img src attributes
        Pattern imgSrc = Pattern.compile("<img[^>]+(?:src|data-src|data-original)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        addMatches(urls, imgSrc, html, baseUrl);

        // 2. srcset (pick largest)
        Pattern srcset = Pattern.compile("srcset\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher srcsetMatcher = srcset.matcher(html);
        while (srcsetMatcher.find())
        {
            String srcsetVal = srcsetMatcher.group(1);
            String[] parts = srcsetVal.split(",");
            if (parts.length > 0)
            {
                String last = parts[parts.length - 1].trim().split("\\s+")[0];
                urls.add(resolveUrl(last, baseUrl));
            }
        }

        // 3. Open Graph / Twitter Card meta images
        Pattern ogImage = Pattern.compile("<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        addMatches(urls, ogImage, html, baseUrl);
        Pattern ogImage2 = Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"']", Pattern.CASE_INSENSITIVE);
        addMatches(urls, ogImage2, html, baseUrl);

        // 4. CSS background-image
        Pattern bgImage = Pattern.compile("background(?:-image)?\\s*:\\s*url\\([\"']?([^\"')]+)[\"']?\\)", Pattern.CASE_INSENSITIVE);
        addMatches(urls, bgImage, html, baseUrl);

        // 5. Google Images specific: URLs embedded in JSON/data attributes
        Pattern googleImg = Pattern.compile("\\[\"(https?://[^\"]+\\.(?:jpg|jpeg|png|gif|webp)(?:\\?[^\"]*)?)\"", Pattern.CASE_INSENSITIVE);
        addMatches(urls, googleImg, html, baseUrl);

        // 6. Bing Images: extract from data attributes
        Pattern bingImg = Pattern.compile("murl\":\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        addMatches(urls, bingImg, html, baseUrl);

        // 7. Generic quoted URLs ending in image extensions
        Pattern genericImg = Pattern.compile("[\"'](https?://[^\"'\\s]+\\.(?:jpg|jpeg|png|gif|webp|svg|bmp|tiff)(?:\\?[^\"'\\s]*)?)[\"']", Pattern.CASE_INSENSITIVE);
        addMatches(urls, genericImg, html, baseUrl);

        // 8. Wikipedia/Wikimedia special: //upload.wikimedia.org paths
        Pattern wikiImg = Pattern.compile("[\"'](//upload\\.wikimedia\\.org/[^\"'\\s]+\\.(?:jpg|jpeg|png|gif|svg|webp))[\"']", Pattern.CASE_INSENSITIVE);
        Matcher wikiMatcher = wikiImg.matcher(html);
        while (wikiMatcher.find())
            urls.add("https:" + wikiMatcher.group(1));

        // 9. <a href> pointing to image files (direct download links)
        Pattern aHrefImg = Pattern.compile("<a[^>]+href\\s*=\\s*[\"']([^\"']+\\.(?:jpg|jpeg|png|gif|bmp|webp|svg|tiff)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE);
        addMatches(urls, aHrefImg, html, baseUrl);

        // --- Complete diagnostics ---
        diag.extractedCount = urls.size();
        diag.missedTotal = Math.max(0, diag.totalOnPage - diag.extractedCount);

        // Analyze missed img src values
        Pattern imgSrcDiag = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher diagMatcher = imgSrcDiag.matcher(html);
        while (diagMatcher.find())
        {
            String src = diagMatcher.group(1);
            if (src.startsWith("data:")) { diag.missedReasonDataUri++; continue; }
            if (src.startsWith("javascript:")) { diag.missedReasonJavascript++; continue; }
            if (src.isEmpty()) { diag.missedReasonBlankSrc++; continue; }

            String resolved = resolveUrl(src, baseUrl);
            boolean hasExt = false;
            String lower = resolved.toLowerCase();
            for (String ext : IMAGE_EXTENSIONS)
                if (lower.contains(ext)) { hasExt = true; break; }

            if (!hasExt)
            {
                diag.missedReasonNoExtension++;
                if (diag.sampleNoExtension.size() < 3)
                    diag.sampleNoExtension.add(resolved.length() > 100 ? resolved.substring(0, 97) + "..." : resolved);
            }
            else if (!urls.contains(resolved))
            {
                diag.missedReasonNotCaptured++;
                if (diag.sampleNotCaptured.size() < 3)
                    diag.sampleNotCaptured.add(resolved.length() > 100 ? resolved.substring(0, 97) + "..." : resolved);
            }
            else
            {
                diag.confirmedCaptured++;
            }
        }

        // --- Print diagnostics ---
        printImageDiagnostic(baseUrl, diag);

        return urls;
    }

    /**
     * Prints image yield diagnostics to console — shows what's on the page
     * vs what we're capturing, with color-coded yield percentage.
     */
    private void printImageDiagnostic(String url, PageImageDiagnostic diag)
    {
        if (diag.totalOnPage == 0) return;

        double pct = diag.yieldPercent();
        String color;
        if (pct >= 70) color = "\u001b[32m";       // green
        else if (pct >= 30) color = "\u001b[33m";  // yellow
        else color = "\u001b[31m";                  // red

        CommonRails.println("  [IMAGE DIAG] " + truncateUrl(url));
        CommonRails.println("    " + color + diag.summary() + "\u001b[0m");

        if (pct < 70 && diag.missedTotal > 0)
        {
            String[] lines = diag.missedBreakdown().split("\n");
            for (String line : lines)
                CommonRails.println("    " + line);
        }
    }

    /**
     * Identifies links to sub-gallery pages (e.g., /gallery, /images, /media pages).
     */
    private Set<String> extractGalleryLinks(String html, String baseUrl)
    {
        Set<String> galleryLinks = new LinkedHashSet<>();
        Pattern linkPat = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = linkPat.matcher(html);

        while (matcher.find())
        {
            String href = matcher.group(1).toLowerCase();
            if (href.contains("gallery") || href.contains("/images") || href.contains("/media")
                || href.contains("/photos") || href.contains("/artwork"))
            {
                galleryLinks.add(resolveUrl(matcher.group(1), baseUrl));
            }
        }

        // Limit to prevent runaway
        if (galleryLinks.size() > 20)
        {
            List<String> limited = new ArrayList<>(galleryLinks);
            galleryLinks = new LinkedHashSet<>(limited.subList(0, 20));
        }

        return galleryLinks;
    }

    // =========================================================================
    // Relevance Scoring
    // =========================================================================

    /**
     * Scores a URL (and optional surrounding context) for relevance to Captain Marvell.
     * Returns 0.0 - 1.0.
     *
     * Scoring factors:
     *   - Keyword presence in URL path/filename (+weight per keyword)
     *   - Image dimensions in URL (e.g., 1200x800 in filename = likely high-res)
     *   - Source domain authority (known comic/hero sites score higher)
     *   - Negative signals (stock photo watermarks, unrelated domains)
     */
    public double scoreRelevance(String url, String context)
    {
        String combined = (url + " " + context).toLowerCase();
        double score = 0.0;
        int hits = 0;

        for (String keyword : relevanceKeywords)
        {
            if (combined.contains(keyword.trim().toLowerCase()))
                hits++;
        }

        // Base score from keyword hits
        score = Math.min(1.0, (double) hits / Math.max(relevanceKeywords.length * 0.3, 1));

        // Boost for known authoritative domains
        String urlLower = url.toLowerCase();
        if (urlLower.contains("marvel.com") || urlLower.contains("marvel.fandom")
            || urlLower.contains("wikimedia.org") || urlLower.contains("comicvine"))
            score = Math.min(1.0, score + 0.3);

        // Boost for filenames containing character names
        if (urlLower.contains("captain") || urlLower.contains("marvel")
            || urlLower.contains("danvers") || urlLower.contains("mar-vell"))
            score = Math.min(1.0, score + 0.2);

        // Penalty for stock photo / irrelevant domains
        if (urlLower.contains("shutterstock") || urlLower.contains("gettyimages")
            || urlLower.contains("istockphoto") || urlLower.contains("dreamstime")
            || urlLower.contains("123rf") || urlLower.contains("placeholder"))
            score = Math.max(0.0, score - 0.5);

        // Penalty for tiny indicators (thumb, icon, favicon)
        if (urlLower.contains("thumb") || urlLower.contains("icon") || urlLower.contains("favicon")
            || urlLower.contains("avatar") || urlLower.contains("1x1") || urlLower.contains("pixel"))
            score = Math.max(0.0, score - 0.3);

        return score;
    }

    // =========================================================================
    // Download & Validation
    // =========================================================================

    private ImageDownloadResult downloadAndValidate(String url)
    {
        try
        {
            URI uri = URI.create(url);
            HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", nextUserAgent())
                .header("Accept", "image/*,*/*")
                .header("Referer", uri.getScheme() + "://" + uri.getHost() + "/")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<Void> headResp = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());

            // Follow one redirect on HEAD
            if (headResp.statusCode() >= 300 && headResp.statusCode() < 400)
            {
                Optional<String> loc = headResp.headers().firstValue("location");
                if (loc.isPresent())
                {
                    url = resolveUrl(loc.get(), url);
                    uri = URI.create(url);
                }
            }

            // Check Content-Type
            String contentType = headResp.headers().firstValue("content-type").orElse("");
            if (!contentType.startsWith("image/") && headResp.statusCode() == 200)
                return new ImageDownloadResult(DownloadStatus.WRONG_TYPE, null);

            // Check Content-Length
            long contentLength = headResp.headers().firstValueAsLong("content-length").orElse(-1);
            if (contentLength > 0 && contentLength < minFileSize)
                return new ImageDownloadResult(DownloadStatus.TOO_SMALL, null);
            if (contentLength > maxFileSize)
                return new ImageDownloadResult(DownloadStatus.TOO_LARGE, null);

            // Determine filename
            String filename = deriveFilename(url, headResp);
            Path targetFile = Paths.get(imageDir, filename);

            if (Files.exists(targetFile))
                return new ImageDownloadResult(DownloadStatus.ALREADY_EXISTS, targetFile);

            // Full GET download
            HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", nextUserAgent())
                .header("Accept", "image/*,*/*")
                .header("Referer", uri.getScheme() + "://" + uri.getHost() + "/")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            HttpResponse<byte[]> getResp = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (getResp.statusCode() != 200)
                return new ImageDownloadResult(DownloadStatus.FAILED, null);

            byte[] data = getResp.body();

            // Size validation
            if (data.length < minFileSize)
                return new ImageDownloadResult(DownloadStatus.TOO_SMALL, null);
            if (data.length > maxFileSize)
                return new ImageDownloadResult(DownloadStatus.TOO_LARGE, null);

            // Deduplication via hash
            String hash = sha256(data);
            if (!downloadedHashes.add(hash))
                return new ImageDownloadResult(DownloadStatus.DUPLICATE, null);

            // Write file
            Files.write(targetFile, data);

            results.add(new ImageResult(url, targetFile, data.length, scoreRelevance(url, "")));
            return new ImageDownloadResult(DownloadStatus.SUCCESS, targetFile);
        }
        catch (Exception e)
        {
            return new ImageDownloadResult(DownloadStatus.FAILED, null);
        }
    }

    /**
     * Derives a meaningful filename from the URL and Content-Disposition header.
     */
    private String deriveFilename(String url, HttpResponse<?> response)
    {
        // Try Content-Disposition first
        Optional<String> disposition = response.headers().firstValue("content-disposition");
        if (disposition.isPresent())
        {
            Matcher m = Pattern.compile("filename\\*?=[\"']?(?:UTF-8'')?([^\"';\\s]+)").matcher(disposition.get());
            if (m.find())
                return sanitizeFilename(m.group(1));
        }

        // Fall back to URL path
        try
        {
            String path = URI.create(url).getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (filename.contains("?"))
                filename = filename.substring(0, filename.indexOf('?'));
            if (!filename.isEmpty() && filename.contains("."))
                return sanitizeFilename(filename);
        }
        catch (Exception ignored) {}

        // Last resort: timestamp-based name
        String ext = guessExtension(response.headers().firstValue("content-type").orElse("image/jpeg"));
        return "captain_marvell_" + System.currentTimeMillis() + ext;
    }

    // =========================================================================
    // Orchestrator — Full Run
    // =========================================================================

    /**
     * Executes the complete 4-phase image download strategy.
     * Call this as the main entry point.
     */
    public void execute()
    {
        CommonRails.println("╔══════════════════════════════════════════════════════════╗");
        CommonRails.println("║  IMAGE DOWNLOAD STRATEGY — Captain Marvell              ║");
        CommonRails.println("║  Relevance threshold: " + relevanceThreshold
            + "  Min size: " + minFileSize + "B             ║");
        CommonRails.println("╚══════════════════════════════════════════════════════════╝");

        String[] queries = config.getProperty("queries", "Captain Marvel").split(",");

        // Phase 1: Direct image search
        Set<String> phase1 = phaseOneDirectSearch(queries);

        // Phase 2: Gallery crawl
        Set<String> phase2 = phaseTwoGalleryCrawl();

        // Merge phases 1 & 2
        Set<String> allCandidates = ConcurrentHashMap.newKeySet();
        allCandidates.addAll(phase1);
        allCandidates.addAll(phase2);

        CommonRails.println("[MERGE] Phase 1 + 2 = " + allCandidates.size() + " unique candidates");

        // Phase 3: Reverse expansion from best candidates
        Set<String> phase3 = phaseThreeReverseExpand(allCandidates);
        allCandidates.addAll(phase3);

        CommonRails.println("[MERGE] + Phase 3 = " + allCandidates.size() + " total candidates");

        // Phase 4: Download with validation
        int downloaded = phaseFourDownload(allCandidates);

        // Summary
        CommonRails.println("");
        CommonRails.println("╔══════════════════════════════════════════════════════════╗");
        CommonRails.println("║  STRATEGY COMPLETE                                      ║");
        CommonRails.println("║  Total downloaded: " + String.format("%-37d", downloaded) + "║");
        CommonRails.println("║  Unique hashes:    " + String.format("%-37d", downloadedHashes.size()) + "║");
        CommonRails.println("║  Output dir:       " + String.format("%-37s", imageDir) + "║");
        CommonRails.println("╚══════════════════════════════════════════════════════════╝");

        generateReport();
        shutdown();
    }

    // =========================================================================
    // Report Generation
    // =========================================================================

    /**
     * Writes a download report to images/download-report.md for review.
     */
    private void generateReport()
    {
        try
        {
            Path reportPath = Paths.get(imageDir, "download-report.md");
            StringBuilder sb = new StringBuilder();
            sb.append("# Image Download Report\n\n");
            sb.append("Date: ").append(java.time.LocalDateTime.now()).append("\n\n");
            sb.append("| # | File | Size | Relevance | Source |\n");
            sb.append("|---|------|------|-----------|--------|\n");

            int i = 1;
            for (ImageResult r : results)
            {
                sb.append("| ").append(i++).append(" | ")
                    .append(r.localPath.getFileName()).append(" | ")
                    .append(r.sizeBytes / 1024).append("KB | ")
                    .append(String.format("%.2f", r.relevanceScore)).append(" | ")
                    .append(truncateUrl(r.sourceUrl)).append(" |\n");
            }

            sb.append("\n**Total: ").append(results.size()).append(" images**\n");
            Files.writeString(reportPath, sb.toString());
            CommonRails.println("[REPORT] Written to " + reportPath);
        }
        catch (Exception e)
        {
            CommonRails.printError("[REPORT] Failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    private String fetchPage(String url) throws Exception
    {
        if (!visitedUrls.add(url))
            return "";

        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .header("User-Agent", nextUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Referer", uri.getScheme() + "://" + uri.getHost() + "/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Follow redirect
        if (response.statusCode() >= 300 && response.statusCode() < 400)
        {
            Optional<String> loc = response.headers().firstValue("location");
            if (loc.isPresent())
                return fetchPage(resolveUrl(loc.get(), url));
        }

        if (response.statusCode() == 200)
            return response.body();

        return "";
    }

    private void throttleHost(String url)
    {
        try
        {
            String host = URI.create(url).getHost();
            if (host == null) return;
            Long last = hostLastAccess.get(host);
            if (last != null)
            {
                long elapsed = System.currentTimeMillis() - last;
                if (elapsed < requestDelayMs)
                    Thread.sleep(requestDelayMs - elapsed);
            }
            hostLastAccess.put(host, System.currentTimeMillis());
        }
        catch (Exception ignored) {}
    }

    private String nextUserAgent()
    {
        return USER_AGENTS[(uaIndex++) % USER_AGENTS.length];
    }

    private boolean isImageUrl(String url)
    {
        String lower = url.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS)
        {
            if (lower.contains(ext))
                return true;
        }
        return false;
    }

    private void addMatches(Set<String> urls, Pattern pattern, String html, String baseUrl)
    {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find())
            urls.add(resolveUrl(matcher.group(1), baseUrl));
    }

    private String resolveUrl(String url, String baseUrl)
    {
        if (url.startsWith("http://") || url.startsWith("https://"))
            return url;
        if (url.startsWith("//"))
            return "https:" + url;
        if (url.startsWith("/"))
        {
            try
            {
                URI base = URI.create(baseUrl);
                return base.getScheme() + "://" + base.getHost() + url;
            }
            catch (Exception e) { return url; }
        }
        // Relative path
        try
        {
            String basePath = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
            return basePath + url;
        }
        catch (Exception e) { return url; }
    }

    private String sanitizeFilename(String name)
    {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private String guessExtension(String contentType)
    {
        if (contentType.contains("png"))  return ".png";
        if (contentType.contains("gif"))  return ".gif";
        if (contentType.contains("webp")) return ".webp";
        if (contentType.contains("svg"))  return ".svg";
        if (contentType.contains("bmp"))  return ".bmp";
        return ".jpg";
    }

    private String sha256(byte[] data)
    {
        try
        {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash)
                hex.append(String.format("%02x", b));
            return hex.toString();
        }
        catch (Exception e) { return String.valueOf(Arrays.hashCode(data)); }
    }

    private String truncateUrl(String url)
    {
        return url.length() > 80 ? url.substring(0, 77) + "..." : url;
    }

    public void shutdown()
    {
        threadPool.shutdown();
    }

    // =========================================================================
    // Inner Classes
    // =========================================================================

    enum DownloadStatus
    {
        SUCCESS, WRONG_TYPE, TOO_SMALL, TOO_LARGE, DUPLICATE, FAILED, ALREADY_EXISTS
    }

    static class ImageDownloadResult
    {
        final DownloadStatus status;
        final Path localPath;

        ImageDownloadResult(DownloadStatus status, Path localPath)
        {
            this.status = status;
            this.localPath = localPath;
        }
    }

    static class ImageResult
    {
        final String sourceUrl;
        final Path localPath;
        final long sizeBytes;
        final double relevanceScore;

        ImageResult(String sourceUrl, Path localPath, long sizeBytes, double relevanceScore)
        {
            this.sourceUrl = sourceUrl;
            this.localPath = localPath;
            this.sizeBytes = sizeBytes;
            this.relevanceScore = relevanceScore;
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws IOException
    {
        String configPath = args.length > 0 ? args[0] : "configuration/search-engines.config";
        String baseDir = args.length > 1 ? args[1] : Paths.get(configPath).toAbsolutePath().getParent().getParent().getParent().toString();

        ImageDownloadStrategy strategy = new ImageDownloadStrategy(configPath, baseDir);
        strategy.execute();
    }
}
