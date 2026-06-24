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

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300)
                    return response.body();

                if (status >= 300 && status < 400)
                {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isPresent())
                    {
                        String loc = location.get();
                        // Handle relative redirects
                        if (loc.startsWith("/"))
                        {
                            URI base = URI.create(currentUrl);
                            loc = base.getScheme() + "://" + base.getHost() + loc;
                        }
                        else if (!loc.startsWith("http"))
                        {
                            loc = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1) + loc;
                        }
                        currentUrl = loc;
                        continue;
                    }
                }
                return "";
            }
            catch (Exception e)
            {
                return "";
            }
        }
        System.err.println("  Max redirects (" + maxRedirects + ") reached for: " + url);
        return "";
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
     * Recursively crawls pages up to maxCrawlDepth, collecting file links matching the category.
     * Uses BFS with concurrent fetching.
     */
    private Set<String> crawl(String startUrl, String category)
    {
        Set<String> fileLinks = ConcurrentHashMap.newKeySet();
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

                    Set<String> allLinks = extractAllLinks(html, url);
                    Set<String> matched = filterFileLinks(allLinks, category);
                    fileLinks.addAll(matched);

                    // Add non-file links to next crawl level if strategy allows
                    if (strategyFollowLinks)
                    {
                        for (String link : allLinks)
                        {
                            if (!matched.contains(link) && !visitedUrls.contains(link))
                                nextLevel.add(link);
                        }
                    }
                    return matched;
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
        return fileLinks;
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
                System.out.println("    Already exists: " + targetFile);
                return false;
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(readTimeout))
                .GET()
                .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200)
            {
                Files.copy(response.body(), targetFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("    Downloaded: " + targetFile + " (" + Files.size(targetFile) + " bytes)");
                return true;
            }
        }
        catch (Exception e)
        {
            System.err.println("    Download failed: " + fileUrl + " (" + e.getMessage() + ")");
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
            + " parseDynamic=" + strategyParseDynamic + "\n");

        int totalDownloads = 0;

        for (String query : queries)
        {
            System.out.println("=== Query: " + query.trim() + " ===\n");
            for (String category : categories)
            {
                System.out.println("--- Category: " + category.trim().toUpperCase() + " ---");
                List<String> searchUrls = buildSearchURLs(query, category);

                Set<String> allFileLinks = ConcurrentHashMap.newKeySet();

                for (String searchUrl : searchUrls)
                {
                    System.out.println("  Crawling from: " + searchUrl);
                    Set<String> found = crawl(searchUrl, category);
                    allFileLinks.addAll(found);
                }

                System.out.println("  Total unique file links: " + allFileLinks.size());
                for (String fileLink : allFileLinks)
                {
                    if (downloadFile(fileLink, category))
                        totalDownloads++;
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
