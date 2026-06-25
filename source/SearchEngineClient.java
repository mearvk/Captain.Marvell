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
        "images", "images",
        "files", "files"
    );

    private HttpClient httpClient;
    private ExecutorService threadPool;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> hostLastAccess = new ConcurrentHashMap<>();

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

    public SearchEngineClient(String configPath) throws IOException
    {
        this(configPath, ".");
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
                    System.out.println("Strategy selected: " + activeStrategyName.toUpperCase()
                        + " (importance=" + searchImportance + ", depth=" + strategyParseDepth + ")");
                    return;
                }
            }
            activeStrategyName = "fibonacci";
            System.out.println("Strategy: fibonacci (default fallback)");
        }
        catch (Exception e)
        {
            activeStrategyName = "fibonacci";
            System.err.println("Could not load strategies from XML: " + e.getMessage() + ". Using default.");
        }
    }

    private String getElementText(Element parent, String tag, String defaultVal)
    {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : defaultVal;
    }

    /**
     * Fetches a URL, manually following up to maxRedirects redirects.
     * Returns the final response body as a String, or empty on failure.
     */
    private String fetchWithRedirects(String url)
    {
        String currentUrl = url;
        int redirectCount = 0;
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
                        CommonRails.printProgressBar(percent, progressLabel);
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                });
                progressThread.setDaemon(true);
                progressThread.start();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                progressDone.set(true);
                progressThread.interrupt();
                CommonRails.printProgressBar(100, progressLabel);
                System.out.println();

                int status = response.statusCode();

                if (status >= 200 && status < 300)
                {
                    if (redirectCount > 0)
                        System.out.println("    Redirect chain complete (" + redirectCount + " hops)");
                    String body = response.body();
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
                        redirectCount++;
                        System.out.println("    Redirect #" + redirectCount + " [" + status + "] " + currentUrl + " -> " + loc);
                        currentUrl = loc;
                        continue;
                    }
                }
                return "";
            }
            catch (Exception e)
            {
                System.out.println();
                return "";
            }
        }
        System.err.println("  Max redirects (" + maxRedirects + ") reached for: " + url);
        return "";
    }

    /**
     * Prints a summary of file-type links found on a page after following redirects.
     */
    private void printPageContentSummary(String url, String html)
    {
        int audio = 0, images = 0, files = 0;
        String lower = html.toLowerCase();

        for (String ext : CATEGORY_EXTENSIONS.get("audio"))
            audio += countOccurrences(lower, ext);
        for (String ext : CATEGORY_EXTENSIONS.get("images"))
            images += countOccurrences(lower, ext);
        for (String ext : CATEGORY_EXTENSIONS.get("files"))
            files += countOccurrences(lower, ext);

        if (audio + images + files > 0)
        {
            System.out.println("    Page: " + truncateUrl(url)
                + " | audio=" + audio + " images=" + images + " files=" + files);
        }
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

        // All strategies: extract href/src absolute URLs
        Pattern pattern = Pattern.compile("(?:href|src)\\s*=\\s*[\"'](https?://[^\"'\\s<>]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        while (matcher.find())
            links.add(matcher.group(1));

        // All strategies: extract relative URLs and resolve them
        Pattern relative = Pattern.compile("(?:href|src)\\s*=\\s*[\"'](/[^\"'\\s<>]+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher relMatcher = relative.matcher(html);
        try
        {
            URI base = URI.create(baseUrl);
            String prefix = base.getScheme() + "://" + base.getHost();
            while (relMatcher.find())
                links.add(prefix + relMatcher.group(1));
        }
        catch (Exception ignored) {}

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

        return links;
    }

    /**
     * Filters links that match a given category's file extensions.
     */
    private Set<String> filterFileLinks(Set<String> links, String category)
    {
        Set<String> matched = new LinkedHashSet<>();
        String[] extensions = CATEGORY_EXTENSIONS.getOrDefault(category.trim(), new String[]{});
        for (String link : links)
        {
            String lower = link.toLowerCase();
            for (String ext : extensions)
            {
                if (lower.contains(ext))
                {
                    matched.add(link);
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * Recursively crawls pages up to maxCrawlDepth, collecting all links found on pages.
     * Uses BFS with concurrent fetching.
     */
    private Set<String> crawl(String startUrl)
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
                    String html = fetchWithRedirects(url);
                    if (html.isEmpty())
                        return Collections.<String>emptySet();

                    Set<String> pageLinks = extractAllLinks(html, url);
                    allLinks.addAll(pageLinks);

                    // Add non-file links to next crawl level if strategy allows
                    if (strategyFollowLinks)
                    {
                        for (String link : pageLinks)
                        {
                            if (!visitedUrls.contains(link))
                                nextLevel.add(link);
                        }
                    }
                    return pageLinks;
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
     */
    private boolean downloadFile(String fileUrl, String category)
    {
        try
        {
            throttleHost(fileUrl);
            URI uri = URI.create(fileUrl);
            String path = uri.getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (filename.contains("?"))
                filename = filename.substring(0, filename.indexOf('?'));
            if (filename.isEmpty())
                filename = "download_" + System.currentTimeMillis();

            String targetDir = CATEGORY_DIRS.getOrDefault(category.trim(), "files");
            Path dir = Paths.get(baseDir, targetDir);
            Files.createDirectories(dir);

            Path targetFile = dir.resolve(filename);
            if (Files.exists(targetFile))
            {
                System.out.println("    [SKIP] Already exists: " + targetFile);
                return false;
            }

            String dlLabel = "[DOWNLOADING] " + category.trim() + " | " + truncateUrl(fileUrl);
            CommonRails.printProgressBar(0, dlLabel);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(readTimeout))
                .GET()
                .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200)
            {
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
                        CommonRails.printProgressBar(percent, dlLabel);
                }
                os.close();
                is.close();
                CommonRails.printProgressBar(100, dlLabel);
                System.out.println();

                long size = Files.size(targetFile);
                System.out.println("    [SUCCESS] " + targetFile + " (" + size + " bytes)");
                return true;
            }
            else
            {
                CommonRails.printProgressBar(0, "[FAILED] HTTP " + response.statusCode() + " | " + truncateUrl(fileUrl));
                System.out.println();
            }
        }
        catch (Exception e)
        {
            CommonRails.printProgressBar(0, "[FAILED] " + e.getMessage() + " | " + truncateUrl(fileUrl));
            System.out.println();
        }
        return false;
    }

    public List<String> buildSearchURLs(String query, String category)
    {
        List<String> urls = new ArrayList<>();
        String searchTerm = URLEncoder.encode(query.trim() + " " + category.trim(), java.nio.charset.StandardCharsets.UTF_8);
        for (Map.Entry<String, String> engine : engines.entrySet())
            urls.add(engine.getValue().replace("{query}", searchTerm));
        return urls;
    }

    /**
     * Main search: queries all engines, crawls result pages following links,
     * finds file URLs, and downloads them.
     */
    public void searchAll()
    {
        System.out.println("=== Captain Marvell Search Engine Client ===");
        System.out.println("Strategy: " + activeStrategyName.toUpperCase() + " (importance=" + searchImportance + ")");
        System.out.println("Config: maxRedirects=" + maxRedirects + " parseDepth=" + strategyParseDepth
            + " maxThreads=" + maxThreads + " crawlDelay=" + crawlDelayMs + "ms");
        System.out.println("Features: followLinks=" + strategyFollowLinks + " parseScripts=" + strategyParseScripts
            + " extractMetadata=" + strategyExtractMetadata + " decodeParams=" + strategyDecodeParams
            + " parseDynamic=" + strategyParseDynamic
            + " downloadAllFoundOnPage=" + downloadAllFoundOnPage + "\n");

        int totalDownloads = 0;

        for (String query : queries)
        {
            System.out.println("=== Query: " + query.trim() + " ===\n");
            for (String category : categories)
            {
                System.out.println("--- Category: " + category.trim().toUpperCase() + " ---");
                List<String> searchUrls = buildSearchURLs(query, category);

                Set<String> allCrawledLinks = ConcurrentHashMap.newKeySet();

                for (String searchUrl : searchUrls)
                {
                    System.out.println("  Crawling from: " + searchUrl);
                    Set<String> found = crawl(searchUrl);
                    allCrawledLinks.addAll(found);
                }

                // Determine which categories to download
                String[] downloadCategories;
                if (downloadAllFoundOnPage)
                    downloadCategories = categories;
                else
                    downloadCategories = new String[]{category};

                for (String dlCategory : downloadCategories)
                {
                    Set<String> fileLinks = filterFileLinks(allCrawledLinks, dlCategory);
                    if (!fileLinks.isEmpty() && !dlCategory.trim().equals(category.trim()))
                        System.out.println("  Also found " + fileLinks.size() + " " + dlCategory.trim() + " file(s) on page");

                    System.out.println("  " + dlCategory.trim().toUpperCase() + " file links: " + fileLinks.size());
                    for (String fileLink : fileLinks)
                    {
                        if (downloadFile(fileLink, dlCategory))
                            totalDownloads++;
                    }
                }
                System.out.println();
            }
        }

        System.out.println("=== Search complete. Total files downloaded: " + totalDownloads + " ===");
        shutdown();
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
