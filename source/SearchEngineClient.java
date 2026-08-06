import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class SearchEngineClient
{
    private Properties config = new Properties();
    private Map<String, String> engines = new LinkedHashMap<>();
    private String[] categories;
    private String[] queries;
    private String baseDir;

    // Crawler settings loaded from config
    private int maxRedirects;
    private int maxCrawlDepth;
    private int connectTimeout;
    private int readTimeout;
    private int maxThreads;
    private long crawlDelayMs;

    private static final Map<String, String[]> CATEGORY_EXTENSIONS = Map.of(
        "audio", new String[]{".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".wma"},
        "images", new String[]{".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp"},
        "files", new String[]{".pdf", ".doc", ".docx", ".txt", ".md", ".csv", ".xls", ".xlsx"}
    );

    private static final Map<String, String> CATEGORY_DIRS = Map.of(
        "audio", "audio",
        "images", "photos",
        "files", "files"
    );

    private HttpClient httpClient;
    private ExecutorService threadPool;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> hostLastAccess = new ConcurrentHashMap<>();

    // URLs extracted from <img src> tags — these are images by definition regardless of extension
    private final Set<String> imgSrcLinks = ConcurrentHashMap.newKeySet();

    // Alt text associated with image URLs (for AI classifier context)
    private final Map<String, String> imgAltText = new ConcurrentHashMap<>();

    // URLs already downloaded (to avoid duplicate downloads across pages)
    private final Set<String> downloadedUrls = ConcurrentHashMap.newKeySet();

    // Strategy fields
    private int searchImportance;
    private String activeStrategyName;
    private int strategyParseDepth;
    private boolean strategyFollowLinks;
    private boolean strategyParseScripts;
    private boolean strategyExtractMetadata;
    private boolean strategyDecodeParams;
    private boolean strategyParseDynamic;

    // When true, download all recognized file types found on a page even if
    // the current search category is narrower (e.g. searching "audio" but page also has images)
    private boolean downloadAllFoundOnPage;

    // Debug: halt when page shows downloadables but nothing actually downloads
    private boolean debugHaltOnMissedDownloads;

    // Printers
    private final PagePrinter pagePrinter = new PagePrinter();
    private final RedirectPrinter redirectPrinter = new RedirectPrinter();

    // AI Image Classifier
    private final ImageClassifier imageClassifier = new ImageClassifier();

    public SearchEngineClient(String configPath) throws IOException
    {
        // Default baseDir to the project root (parent of source/)
        this(configPath, Paths.get(configPath).toAbsolutePath().getParent().getParent().getParent().toString());
    }

    public SearchEngineClient(String configPath, String baseDir) throws IOException
    {
        this.baseDir = baseDir;
        config.load(new FileInputStream(configPath));

        for (String key : new String[]{"google", "bing", "yahoo", "duckduckgo", "baidu"})
        {
            if (config.containsKey(key))
                engines.put(key, config.getProperty(key));
        }

        categories = config.getProperty("categories", "audio,images,files").split(",");
        queries = config.getProperty("queries", "captain marvell").split(",");

        // Load crawler config
        maxRedirects = Integer.parseInt(config.getProperty("max.redirects", "1000"));
        maxCrawlDepth = Integer.parseInt(config.getProperty("max.crawl.depth", "10"));
        connectTimeout = Integer.parseInt(config.getProperty("connect.timeout", "10"));
        readTimeout = Integer.parseInt(config.getProperty("read.timeout", "15"));
        maxThreads = Integer.parseInt(config.getProperty("max.threads", "20"));
        crawlDelayMs = Long.parseLong(config.getProperty("crawl.delay.ms", "100"));

        httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // Handle redirects manually to count them
            .connectTimeout(Duration.ofSeconds(connectTimeout))
            .build();

        threadPool = Executors.newFixedThreadPool(maxThreads);

        // Load search importance and select strategy from XML
        searchImportance = Integer.parseInt(config.getProperty("search.importance", "5000"));
        downloadAllFoundOnPage = Boolean.parseBoolean(config.getProperty("download.all.found.on.page", "true"));
        debugHaltOnMissedDownloads = Boolean.parseBoolean(config.getProperty("debug.halt.on.missed.downloads", "false"));
        loadStrategy(configPath);
    }

    /**
     * Loads parsing strategies from document-rules.xml and selects one based on search.importance.
     */
    private void loadStrategy(String configPath)
    {
        try
        {
            // Resolve XML path relative to config file location
            Path xmlPath = Paths.get(configPath).getParent().resolve("document-rules.xml");
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlPath.toFile());
            NodeList strategies = doc.getElementsByTagName("strategy");

            for (int i = 0; i < strategies.getLength(); i++)
            {
                Element el = (Element) strategies.item(i);
                int min = Integer.parseInt(el.getAttribute("importance-min"));
                int max = Integer.parseInt(el.getAttribute("importance-max"));

                if (searchImportance >= min && searchImportance <= max)
                {
                    activeStrategyName = el.getAttribute("name");
                    strategyParseDepth = Integer.parseInt(getElementText(el, "parse-depth", "1"));
                    strategyFollowLinks = Boolean.parseBoolean(getElementText(el, "follow-links", "false"));
                    strategyParseScripts = Boolean.parseBoolean(getElementText(el, "parse-scripts", "false"));
                    strategyExtractMetadata = Boolean.parseBoolean(getElementText(el, "extract-metadata", "false"));
                    strategyDecodeParams = Boolean.parseBoolean(getElementText(el, "decode-params", "false"));
                    strategyParseDynamic = Boolean.parseBoolean(getElementText(el, "parse-dynamic", "false"));

                    // Override crawl depth with strategy parse-depth
                    maxCrawlDepth = strategyParseDepth;
                    CommonRails.println("Strategy selected: " + activeStrategyName.toUpperCase()
                        + " (importance=" + searchImportance + ", depth=" + strategyParseDepth + ")");
                    return;
                }
            }
            activeStrategyName = "fibonacci";
            CommonRails.println("Strategy: fibonacci (default fallback)");
        }
        catch (Exception e)
        {
            activeStrategyName = "fibonacci";
            CommonRails.printError("Could not load strategies from XML: " + e.getMessage() + ". Using default.");
        }
    }

    private String getElementText(Element parent, String tag, String defaultVal)
    {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : defaultVal;
    }

    /**
     * Determines if a URL or page content is still relevant to the configured search queries.
     * Returns true if relevant, false if the redirect has drifted off-topic.
     */
    private boolean isRelevantToSearch(String url, String bodySnippet)
    {
        String combined = (url + " " + bodySnippet).toLowerCase();
        // Check if any query keyword appears in the URL or page snippet
        for (String query : queries)
        {
            for (String word : query.trim().toLowerCase().split("\\s+"))
            {
                if (word.length() >= 4 && combined.contains(word))
                    return true;
            }
        }
        return false;
    }

    /**
     * Fetches a URL, manually following up to maxRedirects redirects.
     * Prunes redirect branches that drift entirely off-topic (no query keywords in URL).
     * Returns the final response body as a String, or empty on failure.
     */
    private String fetchWithRedirects(String url)
    {
        String currentUrl = url;
        int redirectCount = 0;
        // Track consecutive off-topic redirects; prune after 3 in a row
        int offTopicStreak = 0;
        for (int i = 0; i < maxRedirects; i++)
        {
            try
            {
                throttleHost(currentUrl);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .timeout(Duration.ofSeconds(readTimeout))
                    .GET()
                    .build();

                // Start progress bar for read timeout
                long startTime = System.currentTimeMillis();
                var progressDone = new java.util.concurrent.atomic.AtomicBoolean(false);
                String progressLabel = truncateUrl(currentUrl);
                Thread progressThread = new Thread(() -> {
                    while (!progressDone.get())
                    {
                        long elapsed = System.currentTimeMillis() - startTime;
                        int percent = Math.min(100, (int)((elapsed * 100) / (readTimeout * 1000L)));
                        CommonRails.printProgressBar(percent, progressLabel, SearchEngineClient.this);
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                });
                progressThread.setDaemon(true);
                progressThread.start();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                progressDone.set(true);
                progressThread.interrupt();
                CommonRails.printProgressBar(100, progressLabel, SearchEngineClient.this);
                CommonRails.println();

                int status = response.statusCode();

                if (status >= 200 && status < 300)
                {
                    if (redirectCount > 0)
                        redirectPrinter.printComplete(redirectCount);
                    String body = response.body();

                    // Check relevance of final page — prune if entirely off-topic
                    if (redirectCount > 0 && !isRelevantToSearch(currentUrl, body.substring(0, Math.min(body.length(), 2000))))
                    {
                        redirectPrinter.printPruned("Page drifted off-topic", redirectCount, truncateUrl(currentUrl));
                        return "";
                    }

                    printPageContentSummary(currentUrl, body);
                    return body;
                }

                if (status >= 300 && status < 400)
                {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isPresent())
                    {
                        String loc = location.get();
                        if (loc.startsWith("/"))
                        {
                            URI base = URI.create(currentUrl);
                            loc = base.getScheme() + "://" + base.getHost() + loc;
                        }
                        else if (!loc.startsWith("http"))
                        {
                            loc = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1) + loc;
                        }

                        // Check if the redirect target is drifting off-topic
                        if (isRelevantToSearch(loc, ""))
                        {
                            offTopicStreak = 0;
                        }
                        else
                        {
                            offTopicStreak++;
                            if (offTopicStreak >= 3)
                            {
                                redirectPrinter.printPruned("Redirect chain drifted off-topic", redirectCount, truncateUrl(loc));
                                return "";
                            }
                        }

                        redirectCount++;
                        redirectPrinter.printHop(redirectCount, status, currentUrl, loc);
                        currentUrl = loc;
                        continue;
                    }
                }
                return "";
            }
            catch (Exception e)
            {
                CommonRails.println();
                return "";
            }
        }
        redirectPrinter.printMaxReached(maxRedirects, url);
        return "";
    }

    /**
     * Prints a summary of actual downloadable file links found on a page.
     * Uses the same link extraction + filtering logic as the download path.
     */
    private void printPageContentSummary(String url, String html)
    {
        Set<String> links = extractAllLinks(html, url);
        int audio = filterFileLinks(links, "audio").size();
        int images = filterFileLinks(links, "images").size();
        int files = filterFileLinks(links, "files").size();

        pagePrinter.printSummary(truncateUrl(url), audio, images, files);

        // Diagnostic: count raw image references on page vs what we actually extract
        PageImageDiagnostic diag = diagnoseImageYield(html, url, links);
        if (diag.totalImgTags > 0 || diag.totalImageHrefs > 0)
        {
            pagePrinter.printImageDiagnostic(truncateUrl(url), diag);
        }
    }

    /**
     * Analyzes the raw HTML to count all image references and compare against
     * what our extraction pipeline actually captures. Reports reasons for missed images.
     */
    private PageImageDiagnostic diagnoseImageYield(String html, String baseUrl, Set<String> extractedLinks)
    {
        PageImageDiagnostic diag = new PageImageDiagnostic();

        // Count raw <img> tags in HTML
        Matcher imgMatcher = Pattern.compile("<img\\b", Pattern.CASE_INSENSITIVE).matcher(html);
        while (imgMatcher.find()) diag.totalImgTags++;

        // Count <a href> pointing to image files
        Matcher hrefMatcher = Pattern.compile("href\\s*=\\s*[\"']([^\"']+\\.(?:jpg|jpeg|png|gif|bmp|webp|svg|tiff)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (hrefMatcher.find()) diag.totalImageHrefs++;

        // Count background-image CSS references
        Matcher bgMatcher = Pattern.compile("background(?:-image)?\\s*:\\s*url\\(", Pattern.CASE_INSENSITIVE).matcher(html);
        while (bgMatcher.find()) diag.totalCssBackgrounds++;

        // Count data-src / lazy-load image references
        Matcher lazySrcMatcher = Pattern.compile("data-src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (lazySrcMatcher.find()) diag.totalLazyLoad++;

        // Count srcset entries
        Matcher srcsetMatcher = Pattern.compile("srcset\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (srcsetMatcher.find()) diag.totalSrcset++;

        // Count Open Graph / meta images
        Matcher ogMatcher = Pattern.compile("(?:og:image|twitter:image)", Pattern.CASE_INSENSITIVE).matcher(html);
        while (ogMatcher.find()) diag.totalMetaImages++;

        // Grand total of all image references on the page
        diag.totalOnPage = diag.totalImgTags + diag.totalImageHrefs + diag.totalCssBackgrounds
            + diag.totalLazyLoad + diag.totalSrcset + diag.totalMetaImages;

        // How many did our extractor actually capture as image files?
        Set<String> extractedImages = filterFileLinks(extractedLinks, "images");
        diag.extractedCount = extractedImages.size();

        // Now diagnose WHY images are missing
        // Re-scan all img src values and check each one
        Pattern imgSrcPat = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher srcMatcher = imgSrcPat.matcher(html);
        while (srcMatcher.find())
        {
            String src = srcMatcher.group(1);
            String resolvedUrl = resolveForDiag(src, baseUrl);

            if (resolvedUrl.isEmpty())
            {
                diag.missedReasonBlankSrc++;
                continue;
            }

            // Check: is it a data: URI (inline base64)?
            if (src.startsWith("data:"))
            {
                diag.missedReasonDataUri++;
                continue;
            }

            // Check: is it a javascript: link?
            if (src.startsWith("javascript:"))
            {
                diag.missedReasonJavascript++;
                continue;
            }

            // Check: does it have an image extension?
            boolean hasExt = false;
            String lower = resolvedUrl.toLowerCase();
            for (String ext : new String[]{".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp"})
            {
                if (lower.contains(ext)) { hasExt = true; break; }
            }

            if (!hasExt)
            {
                // No image extension — might be a dynamic URL (e.g., /image?id=123)
                diag.missedReasonNoExtension++;
                if (diag.sampleNoExtension.size() < 3)
                    diag.sampleNoExtension.add(resolvedUrl.length() > 100 ? resolvedUrl.substring(0, 97) + "..." : resolvedUrl);
                continue;
            }

            // Check: was it in our extracted set?
            if (!extractedImages.contains(resolvedUrl))
            {
                // It has an image extension but didn't make it to extractedImages
                // Could be: already visited, relative URL resolution failed, or filtered out
                diag.missedReasonNotCaptured++;
                if (diag.sampleNotCaptured.size() < 3)
                    diag.sampleNotCaptured.add(resolvedUrl.length() > 100 ? resolvedUrl.substring(0, 97) + "..." : resolvedUrl);
            }
            else
            {
                diag.confirmedCaptured++;
            }
        }

        diag.missedTotal = diag.totalOnPage - diag.extractedCount;
        return diag;
    }

    /**
     * Resolves a URL for diagnostic purposes (doesn't modify state).
     */
    private String resolveForDiag(String src, String baseUrl)
    {
        if (src == null || src.isEmpty()) return "";
        if (src.startsWith("data:") || src.startsWith("javascript:")) return src;
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/"))
        {
            try
            {
                URI base = URI.create(baseUrl);
                return base.getScheme() + "://" + base.getHost() + src;
            }
            catch (Exception e) { return ""; }
        }
        // Relative
        try
        {
            return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + src;
        }
        catch (Exception e) { return ""; }
    }

    private int countOccurrences(String text, String sub)
    {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private String truncateUrl(String url)
    {
        return url.length() > 80 ? url.substring(0, 77) + "..." : url;
    }

    /**
     * Throttles requests to the same host to respect crawl delay.
     */
    private void throttleHost(String url)
    {
        try
        {
            String host = URI.create(url).getHost();
            if (host == null) return;
            Long lastAccess = hostLastAccess.get(host);
            if (lastAccess != null)
            {
                long elapsed = System.currentTimeMillis() - lastAccess;
                if (elapsed < crawlDelayMs)
                    Thread.sleep(crawlDelayMs - elapsed);
            }
            hostLastAccess.put(host, System.currentTimeMillis());
        }
        catch (Exception ignored) {}
    }

    /**
     * Extracts all hyperlinks from HTML content using the active strategy.
     */
    private Set<String> extractAllLinks(String html, String baseUrl)
    {
        Set<String> links = new LinkedHashSet<>();

        // All strategies: extract href/src/poster/data-src absolute URLs
        Pattern pattern = Pattern.compile("(?:href|src|poster|data-src|data-url|srcset)\\s*=\\s*[\"'](https?://[^\"'\\s<>,]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        while (matcher.find())
            links.add(matcher.group(1));

        // All strategies: extract protocol-relative URLs (//cdn.example.com/...)
        Pattern protoRelative = Pattern.compile("(?:href|src|poster|data-src|data-url|srcset)\\s*=\\s*[\"'](//[^\"'\\s<>,]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher protoRelMatcher = protoRelative.matcher(html);
        while (protoRelMatcher.find())
            links.add("https:" + protoRelMatcher.group(1));

        // All strategies: extract root-relative URLs (/path/...) and resolve them
        Pattern relative = Pattern.compile("(?:href|src|poster|data-src|data-url|srcset)\\s*=\\s*[\"'](/[^\"'\\s<>,]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher relMatcher = relative.matcher(html);
        try
        {
            URI base = URI.create(baseUrl);
            String prefix = base.getScheme() + "://" + base.getHost();
            while (relMatcher.find())
            {
                String val = relMatcher.group(1);
                // Skip protocol-relative (already handled above)
                if (!val.startsWith("//"))
                    links.add(prefix + val);
            }
        }
        catch (Exception ignored) {}

        // All strategies: extract plain relative URLs (no leading / or protocol)
        // These are paths like "images/photo.jpg" or "img/logo.png"
        Pattern plainRelative = Pattern.compile("(?:href|src|poster|data-src|data-url|srcset)\\s*=\\s*[\"']([^\"'\\s<>,/][^\"'\\s<>,]*\\.(?:jpg|jpeg|png|gif|bmp|svg|webp|mp3|wav|ogg|flac|m4a|aac|wma|pdf|doc|docx|txt|md|csv|xls|xlsx)(?:\\?[^\"']*)?)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher plainRelMatcher = plainRelative.matcher(html);
        try
        {
            String baseDir = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
            while (plainRelMatcher.find())
            {
                String val = plainRelMatcher.group(1);
                // Skip data: and javascript: pseudo-URLs
                if (!val.startsWith("data:") && !val.startsWith("javascript:"))
                    links.add(baseDir + val);
            }
        }
        catch (Exception ignored) {}

        // All strategies: extract <img> src values specifically.
        // Since these are in <img> tags, they ARE images regardless of file extension.
        // We add them directly and also tag them so filterFileLinks recognizes them.
        Pattern imgSrc = Pattern.compile("<img([^>]+)src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Pattern altPat = Pattern.compile("alt\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher imgSrcMatcher = imgSrc.matcher(html);
        try
        {
            URI base = URI.create(baseUrl);
            String prefix = base.getScheme() + "://" + base.getHost();
            String baseDirUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
            while (imgSrcMatcher.find())
            {
                String attrs = imgSrcMatcher.group(1);
                String src = imgSrcMatcher.group(2);
                // Skip data: and javascript:
                if (src.startsWith("data:") || src.startsWith("javascript:") || src.isEmpty())
                    continue;

                String resolved;
                if (src.startsWith("http://") || src.startsWith("https://"))
                    resolved = src;
                else if (src.startsWith("//"))
                    resolved = "https:" + src;
                else if (src.startsWith("/"))
                    resolved = prefix + src;
                else
                    resolved = baseDirUrl + src;

                // Add ALL img src URLs — they are images by virtue of being in <img> tags
                links.add(resolved);
                // Also add to the img-src tracking set so filterFileLinks can include them
                imgSrcLinks.add(resolved);

                // Extract alt text for AI classifier
                Matcher altMatcher = altPat.matcher(attrs);
                if (altMatcher.find())
                {
                    String alt = altMatcher.group(1).trim();
                    if (!alt.isEmpty())
                        imgAltText.put(resolved, alt);
                }
            }
        }
        catch (Exception ignored) {}

        // Extract URLs from search engine redirect wrappers (Google /url?q=, Bing, Yahoo)
        Pattern searchRedirect = Pattern.compile("[?&](?:q|u|url|uddg|RU)=(https?(?:%3A|:)[^&\"'\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher srMatcher = searchRedirect.matcher(html);
        while (srMatcher.find())
        {
            try { links.add(URLDecoder.decode(srMatcher.group(1), "UTF-8")); }
            catch (Exception ignored) {}
        }

        // Google Images: extract full-res URLs from JSON data embedded in the page
        // Google Images stores original image URLs in patterns like "ou":"http://..." or ["http://...",width,height]
        Pattern googleImgPat = Pattern.compile("\"ou\"\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher googleImgMatcher = googleImgPat.matcher(html);
        while (googleImgMatcher.find())
        {
            String imgUrl = googleImgMatcher.group(1).replace("\\u003d", "=").replace("\\u0026", "&");
            links.add(imgUrl);
            imgSrcLinks.add(imgUrl);
        }

        // Google Images alternate pattern: "imgurl":"..."
        Pattern googleImgUrl2 = Pattern.compile("\"imgurl\"\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher googleImgUrl2Matcher = googleImgUrl2.matcher(html);
        while (googleImgUrl2Matcher.find())
        {
            String imgUrl = googleImgUrl2Matcher.group(1).replace("\\u003d", "=").replace("\\u0026", "&");
            links.add(imgUrl);
            imgSrcLinks.add(imgUrl);
        }

        // Google Images: extract from imgurl= parameter in links
        Pattern googleImgParam = Pattern.compile("imgurl=(https?[^&\"'\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher googleImgParamMatcher = googleImgParam.matcher(html);
        while (googleImgParamMatcher.find())
        {
            try
            {
                String imgUrl = URLDecoder.decode(googleImgParamMatcher.group(1), "UTF-8");
                links.add(imgUrl);
                imgSrcLinks.add(imgUrl);
            }
            catch (Exception ignored) {}
        }

        // Bing Images: extract from data-bm/m attribute JSON which contains "murl":"..."
        Pattern bingImgPat = Pattern.compile("\"murl\"\\s*:\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher bingImgMatcher = bingImgPat.matcher(html);
        while (bingImgMatcher.find())
        {
            String imgUrl = bingImgMatcher.group(1).replace("\\u003d", "=").replace("\\u0026", "&");
            links.add(imgUrl);
            imgSrcLinks.add(imgUrl);
        }

        // Galileo+: extract from data attributes and meta tags
        if (strategyExtractMetadata)
        {
            Pattern dataPat = Pattern.compile("(?:data-src|data-url|content)\\s*=\\s*[\"'](https?://[^\"'\\s<>]+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher dataMatcher = dataPat.matcher(html);
            while (dataMatcher.find())
                links.add(dataMatcher.group(1));
        }

        // DaVinci+: parse inline scripts for URLs
        if (strategyParseScripts)
        {
            Pattern scriptUrls = Pattern.compile("[\"'](https?://[^\"'\\s<>]+\\.[a-z0-9]{2,5})[\"']", Pattern.CASE_INSENSITIVE);
            Matcher scriptMatcher = scriptUrls.matcher(html);
            while (scriptMatcher.find())
                links.add(scriptMatcher.group(1));
        }

        // Marconi+: decode URL-encoded parameters that contain file URLs
        if (strategyDecodeParams)
        {
            Pattern paramPat = Pattern.compile("[?&](?:url|file|src|redirect|link)=(https?%3A[^&\"'\\s]+)", Pattern.CASE_INSENSITIVE);
            Matcher paramMatcher = paramPat.matcher(html);
            while (paramMatcher.find())
            {
                try { links.add(URLDecoder.decode(paramMatcher.group(1), "UTF-8")); }
                catch (Exception ignored) {}
            }
        }

        // Fermi: extract from dynamically constructed URLs (JS concatenation patterns)
        if (strategyParseDynamic)
        {
            Pattern dynPat = Pattern.compile("(?:window\\.location|location\\.href|url)\\s*=\\s*[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher dynMatcher = dynPat.matcher(html);
            while (dynMatcher.find())
                links.add(dynMatcher.group(1));
        }

        // All strategies: extract CSS background-image: url(...) references
        // These are images embedded via CSS and should be downloaded as images
        Pattern bgImgPat = Pattern.compile("background(?:-image)?\\s*:\\s*url\\([\"']?(https?://[^\"')\\s]+)[\"']?\\)", Pattern.CASE_INSENSITIVE);
        Matcher bgImgMatcher = bgImgPat.matcher(html);
        while (bgImgMatcher.find())
        {
            String bgUrl = bgImgMatcher.group(1);
            links.add(bgUrl);
            imgSrcLinks.add(bgUrl); // Treat as image since it's used as background-image
        }

        // Also extract root-relative and protocol-relative CSS background-image URLs
        Pattern bgImgRelPat = Pattern.compile("background(?:-image)?\\s*:\\s*url\\([\"']?(/[^\"')\\s]+)[\"']?\\)", Pattern.CASE_INSENSITIVE);
        Matcher bgImgRelMatcher = bgImgRelPat.matcher(html);
        try
        {
            URI base = URI.create(baseUrl);
            String prefix = base.getScheme() + "://" + base.getHost();
            while (bgImgRelMatcher.find())
            {
                String val = bgImgRelMatcher.group(1);
                String resolved;
                if (val.startsWith("//"))
                    resolved = "https:" + val;
                else
                    resolved = prefix + val;
                links.add(resolved);
                imgSrcLinks.add(resolved);
            }
        }
        catch (Exception ignored) {}

        // Extract data-src (lazy-loaded images) — these are images meant to load on scroll
        Pattern dataSrcPat = Pattern.compile("data-src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher dataSrcMatcher = dataSrcPat.matcher(html);
        try
        {
            URI base = URI.create(baseUrl);
            String prefix = base.getScheme() + "://" + base.getHost();
            String baseDirUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
            while (dataSrcMatcher.find())
            {
                String src = dataSrcMatcher.group(1);
                if (src.startsWith("data:") || src.startsWith("javascript:") || src.isEmpty())
                    continue;
                String resolved;
                if (src.startsWith("http://") || src.startsWith("https://"))
                    resolved = src;
                else if (src.startsWith("//"))
                    resolved = "https:" + src;
                else if (src.startsWith("/"))
                    resolved = prefix + src;
                else
                    resolved = baseDirUrl + src;
                links.add(resolved);
                imgSrcLinks.add(resolved); // Likely images since data-src is used for lazy-load
            }
        }
        catch (Exception ignored) {}

        return links;
    }

    /**
     * Filters links that match a given category's file extensions.
     * For images: also includes any URL that came from an <img src> tag,
     * since those are images by definition regardless of file extension.
     */
    private Set<String> filterFileLinks(Set<String> links, String category)
    {
        Set<String> matched = new LinkedHashSet<>();
        String[] extensions = CATEGORY_EXTENSIONS.getOrDefault(category.trim(), new String[]{});
        for (String link : links)
        {
            String lower = link.toLowerCase();

            // Extension-based matching (all categories)
            boolean extMatch = false;
            for (String ext : extensions)
            {
                if (lower.contains(ext))
                {
                    extMatch = true;
                    break;
                }
            }

            if (extMatch)
            {
                matched.add(link);
            }
            // For images: also include URLs from <img> tags even without extension
            else if (category.trim().equals("images") && imgSrcLinks.contains(link))
            {
                // Skip obviously non-downloadable URLs
                if (!lower.startsWith("data:") && !lower.startsWith("javascript:")
                    && (lower.startsWith("http://") || lower.startsWith("https://")))
                {
                    matched.add(link);
                }
            }
        }
        return matched;
    }

    /**
     * Recursively crawls pages up to maxCrawlDepth, collecting all links found on pages.
     * Downloads matching files IMMEDIATELY as they are discovered on each page.
     * Uses BFS with concurrent fetching.
     */
    private Set<String> crawl(String startUrl, String[] downloadCategories)
    {
        Set<String> allLinks = ConcurrentHashMap.newKeySet();
        Queue<String> currentLevel = new ConcurrentLinkedQueue<>();
        currentLevel.add(startUrl);

        for (int depth = 0; depth < maxCrawlDepth && !currentLevel.isEmpty(); depth++)
        {
            // Fibonacci strategy: only parse first page, don't follow links beyond depth 0
            if (depth > 0 && !strategyFollowLinks)
                break;

            Queue<String> nextLevel = new ConcurrentLinkedQueue<>();
            List<Future<Set<String>>> futures = new ArrayList<>();

            for (String url : currentLevel)
            {
                if (!visitedUrls.add(url))
                    continue;

                futures.add(threadPool.submit(() ->
                {
                    CommonRails.incrementActiveThreads();
                    try
                    {
                        String html = fetchWithRedirects(url);
                        if (html.isEmpty())
                            return Collections.<String>emptySet();

                        Set<String> pageLinks = extractAllLinks(html, url);
                        allLinks.addAll(pageLinks);

                        // IMMEDIATE DOWNLOAD: download matching files right now
                        for (String dlCategory : downloadCategories)
                        {
                            Set<String> fileLinks = filterFileLinks(pageLinks, dlCategory);
                            for (String fileLink : fileLinks)
                            {
                                // Use downloadedUrls to avoid duplicate downloads
                                if (downloadedUrls.add(fileLink))
                                {
                                    // AI CLASSIFIER: check if image is relevant before downloading
                                    if (dlCategory.trim().equals("images"))
                                    {
                                        String altText = imgAltText.getOrDefault(fileLink, "");
                                        ImageClassifier.ImageVerdict verdict = imageClassifier.classify(fileLink, url, altText, "");
                                        if (!verdict.shouldDownload)
                                        {
                                            continue; // Skip logos, icons, unrelated images
                                        }
                                        // Download with AI-generated filename
                                        downloadFile(fileLink, dlCategory, verdict.suggestedFilename);
                                    }
                                    else
                                    {
                                        downloadFile(fileLink, dlCategory);
                                    }
                                }
                            }
                        }

                        // Add non-file links to next crawl level if strategy allows
                        // Prune links that are entirely off-topic
                        if (strategyFollowLinks)
                        {
                            for (String link : pageLinks)
                            {
                                if (!visitedUrls.contains(link) && isRelevantToSearch(link, ""))
                                    nextLevel.add(link);
                            }
                        }
                        return pageLinks;
                    }
                    finally
                    {
                        CommonRails.decrementActiveThreads();
                    }
                }));
            }

            // Wait for all tasks at this depth
            for (Future<Set<String>> f : futures)
            {
                try { f.get(readTimeout + 5, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }

            currentLevel = nextLevel;
        }
        return allLinks;
    }

    /**
     * Downloads a file from the given URL to the appropriate category directory.
     * Follows redirects to reach the actual file.
     */
    private boolean downloadFile(String fileUrl, String category)
    {
        return downloadFile(fileUrl, category, null);
    }

    /**
     * Downloads a file from the given URL to the appropriate category directory.
     * If suggestedFilename is provided (from AI classifier), uses that instead of URL-derived name.
     * Follows redirects to reach the actual file.
     */
    private boolean downloadFile(String fileUrl, String category, String suggestedFilename)
    {
        CommonRails.incrementActiveThreads();
        try
        {
            // Sanitize URL: encode spaces and other unsafe characters
            String currentUrl = sanitizeUrl(fileUrl);
            if (currentUrl.isEmpty())
            {
                pagePrinter.printFailed("Invalid URL | " + truncateUrl(fileUrl));
                return false;
            }

            // Follow redirects to reach actual file
            for (int r = 0; r < maxRedirects; r++)
            {
                throttleHost(currentUrl);
                URI uri;
                try
                {
                    uri = URI.create(currentUrl);
                }
                catch (Exception e)
                {
                    pagePrinter.printFailed("Malformed URL | " + truncateUrl(currentUrl));
                    return false;
                }

                String path = uri.getPath();
                if (path == null || path.isEmpty()) path = "/";
                String filename;

                // Use AI-suggested filename if provided
                if (suggestedFilename != null && !suggestedFilename.isEmpty())
                {
                    filename = suggestedFilename;
                }
                else
                {
                    filename = path.substring(path.lastIndexOf('/') + 1);
                    if (filename.contains("?"))
                        filename = filename.substring(0, filename.indexOf('?'));
                    // Also strip query from URL-encoded ? (%3F)
                    if (filename.contains("%3F"))
                        filename = filename.substring(0, filename.indexOf("%3F"));
                    if (filename.isEmpty())
                        filename = "download_" + System.currentTimeMillis();
                    // Sanitize filename: remove invalid filesystem characters
                    filename = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
                    if (filename.length() > 200)
                        filename = filename.substring(0, 200);
                    // If downloading an image and filename has no image extension, add .jpg
                    if (category.trim().equals("images"))
                    {
                        String fLower = filename.toLowerCase();
                        boolean hasImgExt = false;
                        for (String ext : CATEGORY_EXTENSIONS.get("images"))
                        {
                            if (fLower.endsWith(ext)) { hasImgExt = true; break; }
                        }
                        if (!hasImgExt)
                            filename = filename + ".jpg";
                    }
                }

                String targetDir = CATEGORY_DIRS.getOrDefault(category.trim(), "files");
                Path dir = Paths.get(baseDir, targetDir);
                Files.createDirectories(dir);

                Path targetFile = dir.resolve(filename);
                if (Files.exists(targetFile))
                {
                    pagePrinter.printSkip(targetFile.toString());
                    return false;
                }

                CommonRails.resumeOutput();
                String dlLabel = "[DOWNLOADING] " + category.trim() + " | " + truncateUrl(currentUrl);
                CommonRails.printProgressBar(0, dlLabel, SearchEngineClient.this);

                // Derive referer from the URL's origin (many image hosts require this)
                String referer = uri.getScheme() + "://" + uri.getHost() + "/";

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Referer", referer)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "identity")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site")
                    .header("Cache-Control", "no-cache")
                    .timeout(Duration.ofSeconds(readTimeout))
                    .GET()
                    .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                // Follow redirects
                if (status >= 300 && status < 400)
                {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isPresent())
                    {
                        String loc = location.get();
                        if (loc.startsWith("/"))
                            loc = uri.getScheme() + "://" + uri.getHost() + loc;
                        else if (!loc.startsWith("http"))
                            loc = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1) + loc;
                        currentUrl = sanitizeUrl(loc);
                        response.body().close();
                        continue;
                    }
                    response.body().close();
                    pagePrinter.printFailed("Redirect without Location | HTTP " + status + " | " + truncateUrl(currentUrl));
                    CommonRails.println();
                    break;
                }

                if (status == 200)
                {
                    // Verify content-type is actually an image (guard against HTML error pages)
                    String contentType = response.headers().firstValue("content-type").orElse("");
                    if (category.trim().equals("images") && !contentType.isEmpty()
                        && !contentType.startsWith("image/")
                        && !contentType.equals("application/octet-stream")
                        && !contentType.startsWith("binary/")
                        && !contentType.equals("application/force-download"))
                    {
                        // Reject content types that are clearly not images
                        if (contentType.startsWith("text/") || contentType.startsWith("application/javascript")
                            || contentType.startsWith("application/json") || contentType.startsWith("application/xml"))
                        {
                            response.body().close();
                            pagePrinter.printFailed("Not an image (content-type: " + contentType + ") | " + truncateUrl(currentUrl));
                            CommonRails.println();
                            return false;
                        }
                        // For other unknown types (e.g. application/pdf), log a warning but still attempt
                        CommonRails.printSystemComponent(this, this.hashCode(),
                            "[WARN] Unexpected content-type: " + contentType + " — attempting download anyway");
                    }

                    long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
                    InputStream is = response.body();
                    OutputStream os = Files.newOutputStream(targetFile);
                    byte[] buf = new byte[8192];
                    long totalRead = 0;
                    int bytesRead;
                    while ((bytesRead = is.read(buf)) != -1)
                    {
                        os.write(buf, 0, bytesRead);
                        totalRead += bytesRead;
                        int percent = contentLength > 0 ? (int)((totalRead * 100) / contentLength) : -1;
                        if (percent >= 0)
                            CommonRails.printProgressBar(percent, dlLabel, SearchEngineClient.this);
                    }
                    os.close();
                    is.close();
                    CommonRails.printProgressBar(100, dlLabel, SearchEngineClient.this);
                    CommonRails.println();

                    long size = Files.size(targetFile);

                    // Delete if file is too small (likely a 1x1 pixel tracker or error)
                    if (category.trim().equals("images") && size < 1000)
                    {
                        Files.deleteIfExists(targetFile);
                        pagePrinter.printFailed("Too small (" + size + " bytes, likely tracker) | " + truncateUrl(currentUrl));
                        return false;
                    }

                    // Rename file to correct extension based on content-type if we defaulted to .jpg
                    if (category.trim().equals("images") && !contentType.isEmpty() && contentType.startsWith("image/"))
                    {
                        String correctExt = contentTypeToExtension(contentType);
                        String currentName = targetFile.getFileName().toString();
                        if (correctExt != null && !currentName.toLowerCase().endsWith(correctExt))
                        {
                            // Strip the wrong extension and add correct one
                            String baseName = currentName.contains(".") ?
                                currentName.substring(0, currentName.lastIndexOf('.')) : currentName;
                            Path correctedFile = targetFile.getParent().resolve(baseName + correctExt);
                            if (!Files.exists(correctedFile))
                            {
                                Files.move(targetFile, correctedFile);
                                targetFile = correctedFile;
                            }
                        }
                    }

                    pagePrinter.printSuccess(targetFile.toString(), size);
                    CommonRails.notifyDownloadComplete(truncateUrl(fileUrl));
                    return true;
                }
                else
                {
                    response.body().close();
                    pagePrinter.printFailed("HTTP " + status + " | " + truncateUrl(currentUrl));
                    CommonRails.println();
                    break;
                }
            }
        }
        catch (Exception e)
        {
            pagePrinter.printFailed(e.getMessage() + " | " + truncateUrl(fileUrl));
            CommonRails.println();
        }
        finally
        {
            CommonRails.decrementActiveThreads();
        }
        return false;
    }

    /**
     * Sanitizes a URL string by encoding unsafe characters that would cause URI.create to throw.
     */
    private String sanitizeUrl(String url)
    {
        if (url == null || url.isEmpty()) return "";
        try
        {
            // Replace common problematic characters in the path/query
            url = url.replace(" ", "%20")
                     .replace("[", "%5B")
                     .replace("]", "%5D")
                     .replace("{", "%7B")
                     .replace("}", "%7D")
                     .replace("|", "%7C")
                     .replace("^", "%5E");
            // Validate it parses
            URI.create(url);
            return url;
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * Maps image content-type to file extension.
     */
    private String contentTypeToExtension(String contentType)
    {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase().split(";")[0].trim();
        switch (ct)
        {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/svg+xml": return ".svg";
            case "image/bmp": return ".bmp";
            case "image/tiff": return ".tiff";
            case "image/x-icon": return ".ico";
            case "image/avif": return ".avif";
            default: return null;
        }
    }

    public List<String> buildSearchURLs(String query, String category)
    {
        List<String> urls = new ArrayList<>();
        String searchTerm = URLEncoder.encode(query.trim() + " " + category.trim(), java.nio.charset.StandardCharsets.UTF_8);

        // For images category, use dedicated image search endpoints for much higher yield
        if (category.trim().equals("images"))
        {
            String imgSearchTerm = URLEncoder.encode(query.trim(), java.nio.charset.StandardCharsets.UTF_8);
            if (engines.containsKey("google"))
                urls.add("https://www.google.com/search?q=" + imgSearchTerm + "&tbm=isch");
            if (engines.containsKey("bing"))
                urls.add("https://www.bing.com/images/search?q=" + imgSearchTerm);
            if (engines.containsKey("yahoo"))
                urls.add("https://images.search.yahoo.com/search/images?p=" + imgSearchTerm);
            if (engines.containsKey("duckduckgo"))
                urls.add("https://duckduckgo.com/?q=" + imgSearchTerm + "&iax=images&ia=images");
            // Also include the standard web search URLs — some results may have embedded images
            for (Map.Entry<String, String> engine : engines.entrySet())
                urls.add(engine.getValue().replace("{query}", searchTerm));
        }
        else
        {
            for (Map.Entry<String, String> engine : engines.entrySet())
                urls.add(engine.getValue().replace("{query}", searchTerm));
        }
        return urls;
    }

    /**
     * Main search: queries all engines, crawls result pages following links,
     * finds file URLs, and downloads them.
     */
    public void searchAll()
    {
        CommonRails.println("=== Captain Marvell Search Engine Client ===");
        CommonRails.println("Strategy: " + activeStrategyName.toUpperCase() + " (importance=" + searchImportance + ")");
        CommonRails.println("Config: maxRedirects=" + maxRedirects + " parseDepth=" + strategyParseDepth
            + " maxThreads=" + maxThreads + " crawlDelay=" + crawlDelayMs + "ms");

        CommonRails.delayableFinePrinter("Features: followLinks=" + strategyFollowLinks + " parseScripts=" + strategyParseScripts
            + " extractMetadata=" + strategyExtractMetadata + " decodeParams=" + strategyDecodeParams
            + " parseDynamic=" + strategyParseDynamic
            + " downloadAllFoundOnPage=" + downloadAllFoundOnPage, 20);

        int totalDownloads = 0;
        int totalImageLinksFound = 0;
        int totalImageDownloaded = 0;
        int totalImageSkipped = 0;
        int totalImageFailed = 0;

        CommonRails.delayableFinePrinter("=== Query: " + Arrays.stream(queries).toList().get(0).trim() + " ===", 20);

        for (String query : queries)
        {
            for (String category : categories)
            {
                CommonRails.printSystemComponent(this, this.hashCode(), "--- Category: " + category.trim().toUpperCase() + " ---");

                List<String> searchUrls = buildSearchURLs(query, category);

                // Determine which categories to download during crawl
                String[] downloadCategories;
                if (downloadAllFoundOnPage)
                    downloadCategories = categories;
                else
                    downloadCategories = new String[]{category};

                Set<String> allCrawledLinks = ConcurrentHashMap.newKeySet();

                for (String searchUrl : searchUrls)
                {
                    CommonRails.printSystemComponent(this, this.hashCode(), "  Crawling from: " + searchUrl);
                    Set<String> found = crawl(searchUrl, downloadCategories);
                    allCrawledLinks.addAll(found);
                }

                // Report what was found (downloads already happened during crawl)
                for (String dlCategory : downloadCategories)
                {
                    Set<String> fileLinks = filterFileLinks(allCrawledLinks, dlCategory);

                    CommonRails.printSystemComponent(this, this.hashCode(),
                        dlCategory.trim().toUpperCase() + " links found: " + fileLinks.size()
                        + " | downloaded so far: " + downloadedUrls.size());

                    if (dlCategory.trim().equals("images"))
                        totalImageLinksFound += fileLinks.size();
                }
                CommonRails.println();
            }
        }

        // Final summary
        totalDownloads = downloadedUrls.size();
        CommonRails.println("╔══════════════════════════════════════════════════════════╗");
        CommonRails.println("║  SEARCH COMPLETE — DOWNLOAD SUMMARY                     ║");
        CommonRails.println("╠══════════════════════════════════════════════════════════╣");
        CommonRails.println("║  Total files downloaded: " + String.format("%-32d", totalDownloads) + "║");
        CommonRails.println("║  Image links found:     " + String.format("%-32d", totalImageLinksFound) + "║");

        // Count actual files in photos/
        try
        {
            long photosCount = Files.list(Paths.get(baseDir, "photos")).count();
            CommonRails.println("║  Files in /photos:      " + String.format("%-32d", photosCount) + "║");
        }
        catch (Exception e)
        {
            CommonRails.println("║  Files in /photos:      (unable to count)                ║");
        }
        CommonRails.println("╚══════════════════════════════════════════════════════════╝");

        shutdown();
    }

    /**
     * Guesses the local file path for a URL (used for skip/fail detection in stats).
     */
    private Path guessLocalPath(String fileUrl, String category)
    {
        try
        {
            URI uri = URI.create(fileUrl);
            String path = uri.getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (filename.contains("?"))
                filename = filename.substring(0, filename.indexOf('?'));
            if (filename.isEmpty()) return null;

            String targetDir = CATEGORY_DIRS.getOrDefault(category.trim(), "files");
            return Paths.get(baseDir, targetDir, filename);
        }
        catch (Exception e) { return null; }
    }

    public void shutdown()
    {
        threadPool.shutdown();
    }

    public void openInBrowser(String category) throws IOException
    {
        for (String query : queries)
        {
            String searchTerm = URLEncoder.encode(query.trim() + " " + category, java.nio.charset.StandardCharsets.UTF_8);
            for (Map.Entry<String, String> engine : engines.entrySet())
            {
                String url = engine.getValue().replace("{query}", searchTerm);
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        }
    }

    public static void main(String[] args) throws IOException
    {
        String configPath = args.length > 0 ? args[0] : "source/configuration/search-engines.config";
        String baseDir = args.length > 1 && !args[1].equals("--open") ? args[1] : ".";
        SearchEngineClient client = new SearchEngineClient(configPath, baseDir);

        if (Arrays.asList(args).contains("--open"))
        {
            int openIdx = Arrays.asList(args).indexOf("--open");
            String category = openIdx + 1 < args.length ? args[openIdx + 1] : "audio";
            client.openInBrowser(category);
        }
        else
        {
            client.searchAll();
        }
    }
}
