import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * AI Image Classifier for Captain Marvell system.
 *
 * Performs two functions:
 * 1. PRE-DOWNLOAD FILTER: Determines if an image URL is likely relevant content
 *    (not a logo, icon, tracker pixel, or unrelated site chrome).
 * 2. CONTEXTUAL NAMING: Generates descriptive filenames based on URL context,
 *    source domain, inferred content type, and quality signals.
 *
 * Integrates with AIVocabulary (100,000-word vocabulary) and learner strips
 * for enhanced image recognition and naming accuracy.
 *
 * Target: 85% coverage (accept some false positives to avoid missing real content).
 */
public class ImageClassifier
{
    // AI Vocabulary engine for enhanced recognition
    private final AIVocabulary vocabulary = new AIVocabulary();

    // --- Relevance keywords (what we WANT) ---
    private static final String[] RELEVANCE_KEYWORDS = {
        "captain", "marvel", "carol", "danvers", "mar-vell", "marvell",
        "avenger", "superhero", "comic", "poster", "cover", "artwork",
        "hero", "cosplay", "costume", "shield", "power", "flight",
        "kree", "photon", "binary", "warbird"
    };

    // --- Logo/icon indicators (what we DON'T want) ---
    private static final String[] LOGO_INDICATORS = {
        "logo", "favicon", "icon", "sprite", "badge", "button",
        "brand", "branding", "widget", "banner-ad", "advert",
        "tracking", "pixel", "spacer", "blank", "placeholder",
        "avatar-default", "gravatar", "ui-", "interface",
        "arrow", "chevron", "caret", "hamburger", "menu-icon",
        "social-", "share-", "like-", "tweet-", "pinterest-",
        "facebook_sharing", "twitter_card", "og-image-default",
        "site-logo", "header-logo", "footer-logo", "nav-",
        "loader", "spinner", "loading", "progress-bar",
        "emoji", "smiley", "emoticon", "sticker"
    };

    // --- Known logo/utility domains or paths ---
    private static final String[] LOGO_DOMAINS = {
        "gravatar.com", "googleusercontent.com/s/", "platform-lookaside",
        "abs.twimg.com/emoji", "abs.twimg.com/icons",
        "s.w.org", "secure.gravatar.com",
        "badges.", "widgets.", "buttons.",
        "pagead2.googlesyndication.com", "ad.doubleclick.net",
        "pixel.", "analytics.", "tracker."
    };

    // --- Size indicators suggesting icons/thumbnails ---
    private static final Pattern SIZE_PATTERN = Pattern.compile(
        "(?:^|[/_-])([0-9]{1,3})x([0-9]{1,3})(?:[/_.-]|$)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TINY_SIZE_PATTERN = Pattern.compile(
        "(?:width|height|size|dim)(?:=|%3D)([0-9]{1,3})", Pattern.CASE_INSENSITIVE);

    // --- Source quality tiers ---
    private static final Map<String, String> DOMAIN_QUALITY = new LinkedHashMap<>();
    static {
        // High-quality known sources
        DOMAIN_QUALITY.put("marvel.com", "official");
        DOMAIN_QUALITY.put("cdn.marvel.com", "official");
        DOMAIN_QUALITY.put("marvelcinematicuniverse.fandom.com", "wiki");
        DOMAIN_QUALITY.put("marvel.fandom.com", "wiki");
        DOMAIN_QUALITY.put("comicvine.gamespot.com", "wiki");
        DOMAIN_QUALITY.put("wikipedia.org", "wiki");
        DOMAIN_QUALITY.put("wikimedia.org", "wiki");
        DOMAIN_QUALITY.put("deviantart.com", "fanart");
        DOMAIN_QUALITY.put("artstation.com", "fanart");
        DOMAIN_QUALITY.put("pinimg.com", "social");
        DOMAIN_QUALITY.put("i.pinimg.com", "social");
        DOMAIN_QUALITY.put("imgur.com", "social");
        DOMAIN_QUALITY.put("i.imgur.com", "social");
        DOMAIN_QUALITY.put("flickr.com", "photo");
        DOMAIN_QUALITY.put("staticflickr.com", "photo");
        DOMAIN_QUALITY.put("thedirect.com", "news");
        DOMAIN_QUALITY.put("heroichollywood.com", "news");
        DOMAIN_QUALITY.put("screenrant.com", "news");
        DOMAIN_QUALITY.put("srcdn.com", "news");
        DOMAIN_QUALITY.put("cbr.com", "news");
        DOMAIN_QUALITY.put("archive.org", "archive");
    }

    // --- Content type inference from URL patterns ---
    private static final Map<String, String> CONTENT_PATTERNS = new LinkedHashMap<>();
    static {
        CONTENT_PATTERNS.put("poster|movie-poster|film-poster", "poster");
        CONTENT_PATTERNS.put("cover|album-cover|book-cover|comic-cover", "cover");
        CONTENT_PATTERNS.put("screenshot|screen-cap|screencap|scene", "screenshot");
        CONTENT_PATTERNS.put("cosplay|costume|cos-play", "cosplay");
        CONTENT_PATTERNS.put("fan-art|fanart|deviant|drawing|illustration|sketch", "fanart");
        CONTENT_PATTERNS.put("wallpaper|desktop|1920|2560|4k|uhd", "wallpaper");
        CONTENT_PATTERNS.put("photo|photograph|still|behind-the-scenes|bts", "photo");
        CONTENT_PATTERNS.put("comic|panel|page|issue|vol", "comic");
        CONTENT_PATTERNS.put("concept|concept-art|production", "concept-art");
        CONTENT_PATTERNS.put("toy|figure|action-figure|statue|collectible", "merchandise");
        CONTENT_PATTERNS.put("trailer|promo|promotional|teaser", "promo");
    }

    // --- Country/origin inference from domain TLDs ---
    private static final Map<String, String> TLD_COUNTRY = new LinkedHashMap<>();
    static {
        TLD_COUNTRY.put(".com", "us");
        TLD_COUNTRY.put(".co.uk", "uk");
        TLD_COUNTRY.put(".uk", "uk");
        TLD_COUNTRY.put(".de", "de");
        TLD_COUNTRY.put(".fr", "fr");
        TLD_COUNTRY.put(".jp", "jp");
        TLD_COUNTRY.put(".cn", "cn");
        TLD_COUNTRY.put(".kr", "kr");
        TLD_COUNTRY.put(".br", "br");
        TLD_COUNTRY.put(".ru", "ru");
        TLD_COUNTRY.put(".es", "es");
        TLD_COUNTRY.put(".it", "it");
        TLD_COUNTRY.put(".nl", "nl");
        TLD_COUNTRY.put(".au", "au");
        TLD_COUNTRY.put(".ca", "ca");
        TLD_COUNTRY.put(".in", "in");
    }

    /**
     * Classification result for a single image URL.
     */
    public static class ImageVerdict
    {
        public final boolean shouldDownload;
        public final String suggestedFilename;
        public final String rejectReason;     // null if shouldDownload=true
        public final double relevanceScore;   // 0.0 - 1.0
        public final String contentType;      // poster, fanart, screenshot, etc.
        public final String sourceQuality;    // official, wiki, news, social, unknown
        public final String countryOfOrigin;  // us, uk, jp, etc.
        public final String mediaType;        // drawing, photo, mixed, unknown
        public final String estimatedAge;     // modern, classic, vintage, unknown

        public ImageVerdict(boolean shouldDownload, String suggestedFilename, String rejectReason,
                            double relevanceScore, String contentType, String sourceQuality,
                            String countryOfOrigin, String mediaType, String estimatedAge)
        {
            this.shouldDownload = shouldDownload;
            this.suggestedFilename = suggestedFilename;
            this.rejectReason = rejectReason;
            this.relevanceScore = relevanceScore;
            this.contentType = contentType;
            this.sourceQuality = sourceQuality;
            this.countryOfOrigin = countryOfOrigin;
            this.mediaType = mediaType;
            this.estimatedAge = estimatedAge;
        }
    }

    /**
     * Classifies an image URL and determines whether it should be downloaded,
     * and what it should be named.
     *
     * @param imageUrl    The full URL of the image
     * @param pageUrl     The page where this image was found (for context)
     * @param altText     The alt text of the img tag (may be empty)
     * @param imgContext  Surrounding HTML context (e.g., parent element class names)
     * @return ImageVerdict with download decision and suggested filename
     */
    public ImageVerdict classify(String imageUrl, String pageUrl, String altText, String imgContext)
    {
        String urlLower = imageUrl.toLowerCase();
        String pageLower = (pageUrl != null ? pageUrl : "").toLowerCase();
        String altLower = (altText != null ? altText : "").toLowerCase();
        String ctxLower = (imgContext != null ? imgContext : "").toLowerCase();

        // --- PHASE 1: Quick reject (logos, icons, trackers) ---
        String rejectReason = checkForReject(urlLower, altLower, ctxLower);
        if (rejectReason != null)
        {
            return new ImageVerdict(false, null, rejectReason, 0.0,
                "rejected", "unknown", "unknown", "unknown", "unknown");
        }

        // --- PHASE 2: Size check (reject tiny images) ---
        rejectReason = checkSize(urlLower);
        if (rejectReason != null)
        {
            return new ImageVerdict(false, null, rejectReason, 0.0,
                "rejected", "unknown", "unknown", "unknown", "unknown");
        }

        // --- PHASE 2.5: Learner strip check (fast-track reject or classify) ---
        AIVocabulary.LearnerStrip strip = vocabulary.matchLearnerStrip(imageUrl, pageUrl, altText);
        if (strip != null && strip.confidence < 0.1)
        {
            // Reject strips (logos, ads, trackers)
            return new ImageVerdict(false, null, "Learner strip reject: " + strip.name,
                strip.confidence, strip.classification, "unknown", "unknown", "unknown", "unknown");
        }

        // --- PHASE 3: Relevance scoring (enhanced with vocabulary) ---
        double relevance = scoreRelevance(urlLower, pageLower, altLower, ctxLower);

        // Boost relevance if vocabulary recognizes comic/character terms
        Map<String, List<String>> vocabMatches = vocabulary.recognize(urlLower + " " + altLower + " " + pageLower);
        if (vocabMatches.containsKey("comic") || vocabMatches.containsKey("character"))
            relevance = Math.min(1.0, relevance + 0.2);

        // Below threshold = reject (threshold set low for 85% coverage)
        if (relevance < 0.15)
        {
            return new ImageVerdict(false, null, "Low relevance score: " + String.format("%.2f", relevance),
                relevance, "unknown", "unknown", "unknown", "unknown", "unknown");
        }

        // --- PHASE 4: Context analysis ---
        String sourceQuality = inferSourceQuality(imageUrl);
        String contentType;
        String mediaType;

        // Use learner strip classification if available (higher accuracy)
        if (strip != null && strip.confidence >= 0.7)
        {
            contentType = strip.classification;
            mediaType = strip.mediaType;
        }
        else
        {
            contentType = inferContentType(urlLower, altLower, pageLower);
            mediaType = inferMediaType(urlLower, altLower, pageLower, ctxLower);
        }

        String countryOfOrigin = inferCountry(imageUrl);
        String estimatedAge = inferAge(urlLower, pageLower);

        // --- PHASE 5: Generate descriptive filename (vocabulary-enhanced) ---
        String filename;
        if (strip != null && strip.nameComponents.length > 0)
        {
            // Use learner strip naming components as base, then enrich
            filename = generateFilenameFromStrip(imageUrl, strip, sourceQuality,
                countryOfOrigin, estimatedAge, altText);
        }
        else
        {
            filename = generateFilename(imageUrl, contentType, sourceQuality,
                countryOfOrigin, mediaType, estimatedAge, altText);
        }

        return new ImageVerdict(true, filename, null, relevance,
            contentType, sourceQuality, countryOfOrigin, mediaType, estimatedAge);
    }

    /**
     * Simplified classify for when only URL is available.
     */
    public ImageVerdict classify(String imageUrl, String pageUrl)
    {
        return classify(imageUrl, pageUrl, "", "");
    }

    // =========================================================================
    // PHASE 1: Reject detection
    // =========================================================================

    private String checkForReject(String urlLower, String altLower, String ctxLower)
    {
        // Check URL for logo/icon indicators
        for (String indicator : LOGO_INDICATORS)
        {
            if (urlLower.contains(indicator))
                return "Logo/icon indicator in URL: " + indicator;
        }

        // Check known logo domains
        for (String domain : LOGO_DOMAINS)
        {
            if (urlLower.contains(domain))
                return "Known utility domain: " + domain;
        }

        // Check alt text for UI element indicators
        if (!altLower.isEmpty())
        {
            if (altLower.equals("logo") || altLower.equals("icon") || altLower.equals("avatar")
                || altLower.startsWith("advertisement") || altLower.equals("ad"))
                return "Alt text indicates non-content: " + altLower;
        }

        // Check context for common non-content containers
        if (ctxLower.contains("class=\"logo\"") || ctxLower.contains("class=\"icon\"")
            || ctxLower.contains("class=\"ad\"") || ctxLower.contains("class=\"advertisement\"")
            || ctxLower.contains("id=\"logo\"") || ctxLower.contains("id=\"site-logo\""))
            return "Parent container is logo/ad element";

        // SVG files that are likely UI elements (unless from wikimedia/content sources)
        if (urlLower.endsWith(".svg") && !urlLower.contains("wiki") && !urlLower.contains("comic"))
        {
            // Most SVGs on web pages are icons/logos
            if (!urlLower.contains("captain") && !urlLower.contains("marvel"))
                return "SVG likely a UI element";
        }

        // Data URI check (shouldn't reach here but safety)
        if (urlLower.startsWith("data:"))
            return "Data URI";

        return null; // No reject
    }

    // =========================================================================
    // PHASE 2: Size check
    // =========================================================================

    private String checkSize(String urlLower)
    {
        // Check for explicit size in URL (e.g., 16x16, 32x32, 48x48)
        Matcher sizeMatcher = SIZE_PATTERN.matcher(urlLower);
        if (sizeMatcher.find())
        {
            int w = Integer.parseInt(sizeMatcher.group(1));
            int h = Integer.parseInt(sizeMatcher.group(2));
            // Reject if both dimensions are very small (likely icon)
            if (w <= 64 && h <= 64)
                return "Too small (" + w + "x" + h + ")";
            // Reject 1x1 tracking pixels
            if (w == 1 || h == 1)
                return "Tracking pixel (" + w + "x" + h + ")";
        }

        // Check query param sizes
        Matcher paramSizeMatcher = TINY_SIZE_PATTERN.matcher(urlLower);
        if (paramSizeMatcher.find())
        {
            int size = Integer.parseInt(paramSizeMatcher.group(1));
            if (size <= 48)
                return "Size param too small (" + size + ")";
        }

        return null;
    }

    // =========================================================================
    // PHASE 3: Relevance scoring
    // =========================================================================

    private double scoreRelevance(String urlLower, String pageLower, String altLower, String ctxLower)
    {
        double score = 0.0;
        String combined = urlLower + " " + pageLower + " " + altLower + " " + ctxLower;

        // Keyword matches
        int keywordHits = 0;
        for (String keyword : RELEVANCE_KEYWORDS)
        {
            if (combined.contains(keyword))
                keywordHits++;
        }

        // More keyword hits = higher relevance
        score += Math.min(keywordHits * 0.15, 0.6);

        // Bonus for being on a relevant page (even if image URL is generic)
        if (pageLower.contains("captain") || pageLower.contains("marvel")
            || pageLower.contains("marvell") || pageLower.contains("avenger"))
            score += 0.25;

        // Bonus for meaningful alt text
        if (!altLower.isEmpty() && altLower.length() > 10)
            score += 0.1;

        // Bonus for coming from known quality sources
        String quality = inferSourceQuality(urlLower);
        if (quality.equals("official") || quality.equals("wiki"))
            score += 0.2;
        else if (quality.equals("news") || quality.equals("fanart"))
            score += 0.15;

        // Penalty for generic stock photo sites
        if (urlLower.contains("shutterstock") || urlLower.contains("gettyimages")
            || urlLower.contains("istockphoto") || urlLower.contains("stock"))
            score -= 0.3;

        // Penalty for ad networks
        if (urlLower.contains("doubleclick") || urlLower.contains("googlesyndication")
            || urlLower.contains("adnxs") || urlLower.contains("adsense"))
            score -= 0.5;

        // Bonus for image-specific paths
        if (urlLower.contains("/image") || urlLower.contains("/photo")
            || urlLower.contains("/media") || urlLower.contains("/upload"))
            score += 0.1;

        return Math.max(0.0, Math.min(1.0, score));
    }

    // =========================================================================
    // PHASE 4: Context inference
    // =========================================================================

    private String inferSourceQuality(String url)
    {
        String lower = url.toLowerCase();
        for (Map.Entry<String, String> entry : DOMAIN_QUALITY.entrySet())
        {
            if (lower.contains(entry.getKey()))
                return entry.getValue();
        }
        return "unknown";
    }

    private String inferContentType(String urlLower, String altLower, String pageLower)
    {
        String combined = urlLower + " " + altLower + " " + pageLower;
        for (Map.Entry<String, String> entry : CONTENT_PATTERNS.entrySet())
        {
            String[] patterns = entry.getKey().split("\\|");
            for (String p : patterns)
            {
                if (combined.contains(p))
                    return entry.getValue();
            }
        }
        // Default inference from URL structure
        if (urlLower.contains("thumb") || urlLower.contains("preview"))
            return "thumbnail";
        if (urlLower.contains("full") || urlLower.contains("original") || urlLower.contains("highres"))
            return "full-image";
        return "image";
    }

    private String inferCountry(String url)
    {
        try
        {
            String host = URI.create(url).getHost();
            if (host == null) return "unknown";
            for (Map.Entry<String, String> entry : TLD_COUNTRY.entrySet())
            {
                if (host.endsWith(entry.getKey()))
                    return entry.getValue();
            }
        }
        catch (Exception ignored) {}
        return "us"; // Default assumption for .com
    }

    private String inferMediaType(String urlLower, String altLower, String pageLower, String ctxLower)
    {
        String combined = urlLower + " " + altLower + " " + pageLower;

        if (combined.contains("drawing") || combined.contains("illustration")
            || combined.contains("sketch") || combined.contains("artwork")
            || combined.contains("deviant") || combined.contains("artstation")
            || combined.contains("fan-art") || combined.contains("fanart")
            || combined.contains("comic") || combined.contains("panel"))
            return "drawing";

        if (combined.contains("photo") || combined.contains("still")
            || combined.contains("behind-the-scenes") || combined.contains("set-photo")
            || combined.contains("cosplay") || combined.contains("premiere"))
            return "photo";

        if (combined.contains("render") || combined.contains("cgi")
            || combined.contains("3d") || combined.contains("concept-art"))
            return "render";

        if (combined.contains("poster") || combined.contains("cover"))
            return "mixed";

        return "unknown";
    }

    private String inferAge(String urlLower, String pageLower)
    {
        String combined = urlLower + " " + pageLower;

        // Year detection in URLs/paths
        Pattern yearPat = Pattern.compile("(?:20[12][0-9]|19[6-9][0-9])");
        Matcher yearMatcher = yearPat.matcher(combined);
        if (yearMatcher.find())
        {
            int year = Integer.parseInt(yearMatcher.group());
            if (year >= 2020) return "modern";
            if (year >= 2010) return "recent";
            if (year >= 1990) return "classic";
            return "vintage";
        }

        // Inference from content markers
        if (combined.contains("mcu") || combined.contains("endgame")
            || combined.contains("infinity") || combined.contains("phase"))
            return "modern";

        if (combined.contains("original") || combined.contains("classic")
            || combined.contains("first-appearance") || combined.contains("golden-age"))
            return "vintage";

        return "unknown";
    }

    // =========================================================================
    // PHASE 5: Filename generation
    // =========================================================================

    /**
     * Generate filename from a matched learner strip (higher accuracy naming).
     */
    private String generateFilenameFromStrip(String imageUrl, AIVocabulary.LearnerStrip strip,
                                              String sourceQuality, String countryOfOrigin,
                                              String estimatedAge, String altText)
    {
        StringBuilder name = new StringBuilder();

        // Start with strip name components
        for (String component : strip.nameComponents)
            name.append(component).append("_");

        // Add source quality if not already covered
        if (!sourceQuality.equals("unknown"))
            name.append(sourceQuality).append("_");

        // Add country if non-US
        if (!countryOfOrigin.equals("unknown") && !countryOfOrigin.equals("us"))
            name.append(countryOfOrigin).append("_");

        // Add age
        if (!estimatedAge.equals("unknown"))
            name.append(estimatedAge).append("_");

        // Add quality tier from strip
        if (!strip.quality.equals("unknown"))
            name.append(strip.quality).append("_");

        // Content slug from alt text or URL
        String contentSlug = extractContentSlug(imageUrl, altText);
        if (contentSlug != null && !contentSlug.isEmpty())
            name.append(contentSlug).append("_");

        // Unique hash
        String hash = String.format("%04x", imageUrl.hashCode() & 0xFFFF);
        name.append(hash);

        // Extension
        String ext = extractExtension(imageUrl);
        name.append(ext);

        // Sanitize
        String filename = name.toString()
            .replaceAll("[^a-zA-Z0-9._\\-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("_-", "_")
            .replaceAll("-_", "_")
            .toLowerCase();

        if (filename.length() > 180)
        {
            String extension = filename.substring(filename.lastIndexOf('.'));
            filename = filename.substring(0, 180 - extension.length()) + extension;
        }

        return filename;
    }

    private String generateFilename(String imageUrl, String contentType, String sourceQuality,
                                     String countryOfOrigin, String mediaType, String estimatedAge,
                                     String altText)
    {
        StringBuilder name = new StringBuilder();

        // Base: captain-marvell
        name.append("captain-marvell");

        // Content type
        if (!contentType.equals("image") && !contentType.equals("unknown"))
            name.append("_").append(contentType);

        // Source quality
        if (!sourceQuality.equals("unknown"))
            name.append("_").append(sourceQuality);

        // Media type (drawing vs photo)
        if (!mediaType.equals("unknown"))
            name.append("_").append(mediaType);

        // Country
        if (!countryOfOrigin.equals("unknown") && !countryOfOrigin.equals("us"))
            name.append("_").append(countryOfOrigin);

        // Age
        if (!estimatedAge.equals("unknown"))
            name.append("_").append(estimatedAge);

        // Disambiguator from source domain
        String domainSlug = extractDomainSlug(imageUrl);
        if (domainSlug != null && !domainSlug.isEmpty())
            name.append("_").append(domainSlug);

        // Meaningful slug from alt text or URL path
        String contentSlug = extractContentSlug(imageUrl, altText);
        if (contentSlug != null && !contentSlug.isEmpty())
            name.append("_").append(contentSlug);

        // Unique hash suffix to prevent collisions
        String hash = String.format("%04x", imageUrl.hashCode() & 0xFFFF);
        name.append("_").append(hash);

        // Extension from URL
        String ext = extractExtension(imageUrl);
        name.append(ext);

        // Sanitize
        String filename = name.toString()
            .replaceAll("[^a-zA-Z0-9._\\-]", "-")
            .replaceAll("-{2,}", "-")
            .toLowerCase();

        // Limit length
        if (filename.length() > 180)
        {
            String extension = filename.substring(filename.lastIndexOf('.'));
            filename = filename.substring(0, 180 - extension.length()) + extension;
        }

        return filename;
    }

    private String extractDomainSlug(String url)
    {
        try
        {
            String host = URI.create(url).getHost();
            if (host == null) return null;
            // Remove www. and common prefixes
            host = host.replaceFirst("^(?:www|cdn|static|images?|img|media)\\.", "");
            // Take the main domain name only
            String[] parts = host.split("\\.");
            if (parts.length >= 2)
                return parts[0];
            return host;
        }
        catch (Exception e) { return null; }
    }

    private String extractContentSlug(String url, String altText)
    {
        // Prefer alt text if meaningful
        if (altText != null && altText.length() > 5 && altText.length() < 60)
        {
            return altText.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        }

        // Fall back to URL path filename (without extension)
        try
        {
            String path = URI.create(url).getPath();
            if (path == null) return null;
            String file = path.substring(path.lastIndexOf('/') + 1);
            // Remove extension
            if (file.contains("."))
                file = file.substring(0, file.lastIndexOf('.'));
            // Remove size suffixes and hashes
            file = file.replaceAll("-[0-9]+x[0-9]+$", "")
                       .replaceAll("_[a-f0-9]{6,}$", "")
                       .replaceAll("-[a-f0-9]{8,}$", "");
            if (file.length() > 3 && file.length() < 60)
                return file.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        }
        catch (Exception ignored) {}
        return null;
    }

    private String extractExtension(String url)
    {
        String lower = url.toLowerCase();
        // Check for known extensions in URL
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".tiff"})
        {
            int idx = lower.indexOf(ext);
            if (idx != -1)
            {
                // Make sure it's at end or followed by ? or non-alpha
                int endIdx = idx + ext.length();
                if (endIdx >= lower.length() || !Character.isLetter(lower.charAt(endIdx)))
                    return ext.equals(".jpeg") ? ".jpg" : ext;
            }
        }
        return ".jpg"; // Default
    }

    // =========================================================================
    // Utility: batch classify for integration with SearchEngineClient
    // =========================================================================

    /**
     * Quick check if a URL should be downloaded. Returns true if it passes the filter.
     * This is the fast path — no filename generation.
     */
    public boolean shouldDownload(String imageUrl, String pageUrl)
    {
        String urlLower = imageUrl.toLowerCase();
        String pageLower = (pageUrl != null ? pageUrl : "").toLowerCase();

        // Quick reject checks
        if (checkForReject(urlLower, "", "") != null) return false;
        if (checkSize(urlLower) != null) return false;

        // Relevance check
        double relevance = scoreRelevance(urlLower, pageLower, "", "");
        return relevance >= 0.15;
    }
}
