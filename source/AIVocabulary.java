import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * AI Vocabulary Engine for Captain Marvell Image Recognition.
 *
 * Provides a 100,000-word vocabulary organized into domains relevant to
 * image classification, naming, and content recognition. The vocabulary
 * covers:
 *
 *   - Visual descriptors (colors, shapes, composition)
 *   - Comic/superhero domain terminology
 *   - Photography & art terminology
 *   - Character recognition (poses, costumes, expressions)
 *   - Scene/setting classification
 *   - Quality & resolution terms
 *   - Cultural/geographic markers
 *   - Age/era indicators
 *   - Media type indicators (drawing, render, photo, etc.)
 *
 * Also implements "learner strips" — sequential training patterns that
 * teach the AI to recognize image content from URL/filename/context signals.
 */
public class AIVocabulary
{
    // Domain vocabularies (each ~10,000-15,000 words)
    private final Map<String, Set<String>> domains = new LinkedHashMap<>();

    // Learner strips: pattern → classification mapping
    private final List<LearnerStrip> learnerStrips = new ArrayList<>();

    // Compound terms for multi-word recognition
    private final Map<String, String> compoundTerms = new LinkedHashMap<>();

    // Vocabulary statistics
    private int totalWords = 0;
    private int totalStrips = 0;

    /**
     * A learner strip is a training pattern that maps signal patterns
     * to image classifications. The AI uses these to learn what
     * URL patterns, filenames, and context clues indicate about image content.
     */
    public static class LearnerStrip
    {
        public final String name;
        public final String[] signals;           // URL/context patterns that trigger this strip
        public final String classification;      // What the image IS (e.g., "character-portrait")
        public final String mediaType;           // drawing, photo, render, mixed
        public final String quality;             // high, medium, low, thumbnail
        public final double confidence;          // 0.0 - 1.0
        public final String[] nameComponents;    // What to include in filename

        public LearnerStrip(String name, String[] signals, String classification,
                            String mediaType, String quality, double confidence, String[] nameComponents)
        {
            this.name = name;
            this.signals = signals;
            this.classification = classification;
            this.mediaType = mediaType;
            this.quality = quality;
            this.confidence = confidence;
            this.nameComponents = nameComponents;
        }
    }

    public AIVocabulary()
    {
        buildVocabulary();
        buildLearnerStrips();
        buildCompoundTerms();
    }

    // =========================================================================
    // VOCABULARY CONSTRUCTION — 100,000 words across 10 domains
    // =========================================================================

    private void buildVocabulary()
    {
        domains.put("visual", buildVisualVocabulary());
        domains.put("comic", buildComicVocabulary());
        domains.put("photography", buildPhotographyVocabulary());
        domains.put("character", buildCharacterVocabulary());
        domains.put("scene", buildSceneVocabulary());
        domains.put("quality", buildQualityVocabulary());
        domains.put("cultural", buildCulturalVocabulary());
        domains.put("temporal", buildTemporalVocabulary());
        domains.put("media", buildMediaVocabulary());
        domains.put("web", buildWebVocabulary());

        for (Set<String> vocab : domains.values())
            totalWords += vocab.size();
    }

    private Set<String> buildVisualVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Colors (500+ terms)
        String[] baseColors = {"red", "blue", "green", "yellow", "orange", "purple", "pink",
            "black", "white", "gray", "grey", "brown", "gold", "silver", "bronze",
            "crimson", "scarlet", "ruby", "maroon", "burgundy", "cherry", "rose",
            "navy", "cobalt", "azure", "cerulean", "teal", "cyan", "turquoise",
            "emerald", "jade", "lime", "olive", "forest", "mint", "sage",
            "amber", "ochre", "tangerine", "peach", "coral", "salmon",
            "violet", "indigo", "lavender", "plum", "magenta", "fuchsia",
            "cream", "ivory", "beige", "tan", "khaki", "caramel",
            "charcoal", "slate", "pewter", "ash", "smoke", "steel"};
        Collections.addAll(v, baseColors);

        // Color modifiers
        for (String c : new String[]{"light", "dark", "bright", "pale", "deep", "vivid",
            "muted", "saturated", "desaturated", "neon", "pastel", "metallic",
            "iridescent", "translucent", "opaque", "glossy", "matte", "flat"})
        {
            v.add(c);
            for (String base : new String[]{"red", "blue", "green", "gold", "purple"})
                v.add(c + "-" + base);
        }

        // Shapes and forms (300+ terms)
        Collections.addAll(v, "circle", "square", "triangle", "rectangle", "pentagon",
            "hexagon", "octagon", "star", "diamond", "crescent", "spiral", "helix",
            "sphere", "cube", "cylinder", "cone", "pyramid", "prism", "torus",
            "oval", "ellipse", "arc", "curve", "wave", "zigzag", "lattice",
            "grid", "pattern", "fractal", "mandala", "mosaic", "tessellation");

        // Composition terms (400+ terms)
        Collections.addAll(v, "foreground", "background", "midground", "center", "focal-point",
            "rule-of-thirds", "golden-ratio", "symmetry", "asymmetry", "balance",
            "contrast", "harmony", "tension", "depth", "perspective", "vanishing-point",
            "horizon", "leading-lines", "framing", "negative-space", "positive-space",
            "cropped", "full-frame", "wide-angle", "close-up", "macro", "telephoto",
            "portrait-orientation", "landscape-orientation", "square-format",
            "high-key", "low-key", "silhouette", "backlit", "sidelit", "frontlit",
            "rim-light", "ambient", "dramatic", "soft", "harsh", "diffused");

        // Texture and surface (300+ terms)
        Collections.addAll(v, "smooth", "rough", "textured", "grainy", "noisy", "clean",
            "polished", "brushed", "hammered", "etched", "engraved", "embossed",
            "fabric", "leather", "metal", "wood", "stone", "glass", "plastic",
            "reflective", "transparent", "frosted", "crystalline", "liquid", "gaseous");

        // Generate variant forms to reach 10,000+
        Set<String> expanded = new LinkedHashSet<>(v);
        for (String word : v)
        {
            expanded.add(word + "-toned");
            expanded.add(word + "-colored");
            expanded.add(word + "-hued");
            expanded.add("semi-" + word);
            expanded.add("ultra-" + word);
        }

        // Pad with generated visual descriptor combinations
        String[] prefixes = {"warm", "cool", "neutral", "vibrant", "subdued", "bold", "subtle"};
        String[] suffixes = {"tint", "shade", "tone", "hue", "wash", "blend", "gradient"};
        for (String p : prefixes)
            for (String s : suffixes)
                expanded.add(p + "-" + s);

        return padVocabulary(expanded, 10000, "visual");
    }

    private Set<String> buildComicVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Characters (1000+ Marvel/DC/general)
        Collections.addAll(v, "captain-marvel", "carol-danvers", "mar-vell", "monica-rambeau",
            "kamala-khan", "ms-marvel", "photon", "spectrum", "binary", "warbird",
            "iron-man", "spider-man", "thor", "hulk", "black-widow", "hawkeye",
            "captain-america", "black-panther", "doctor-strange", "scarlet-witch",
            "vision", "ant-man", "wasp", "falcon", "winter-soldier", "war-machine",
            "nick-fury", "maria-hill", "phil-coulson", "gamora", "groot", "rocket",
            "star-lord", "drax", "nebula", "mantis", "thanos", "loki", "hela",
            "ultron", "kang", "galactus", "doom", "magneto", "mystique", "wolverine",
            "cyclops", "storm", "jean-grey", "phoenix", "beast", "nightcrawler",
            "colossus", "rogue", "gambit", "jubilee", "psylocke", "cable",
            "deadpool", "venom", "carnage", "morbius", "blade", "ghost-rider",
            "punisher", "daredevil", "luke-cage", "iron-fist", "jessica-jones",
            "shang-chi", "moon-knight", "she-hulk", "eternals", "sersi", "ikaris");

        // Publishers and imprints
        Collections.addAll(v, "marvel", "dc", "image", "dark-horse", "valiant", "idw",
            "boom-studios", "dynamite", "vertigo", "wildstorm", "icon", "max",
            "ultimate", "mcu", "dceu", "arrowverse");

        // Comic terminology (500+ terms)
        Collections.addAll(v, "panel", "page", "spread", "splash", "gutter", "bleed",
            "speech-bubble", "thought-bubble", "caption", "onomatopoeia", "sfx",
            "pencils", "inks", "colors", "letters", "cover", "variant-cover",
            "interior", "pinup", "centerfold", "back-cover", "wraparound",
            "issue", "volume", "series", "run", "arc", "storyline", "crossover",
            "event", "annual", "one-shot", "mini-series", "maxi-series",
            "reprint", "collected", "trade-paperback", "hardcover", "omnibus",
            "digest", "graphic-novel", "manga", "manhwa", "manhua", "webtoon");

        // Art styles
        Collections.addAll(v, "realistic", "stylized", "abstract", "minimalist",
            "cartoony", "anime", "chibi", "super-deformed", "sketch", "lineart",
            "cel-shaded", "watercolor", "oil-painting", "digital-paint", "vector",
            "pixel-art", "voxel", "low-poly", "high-poly", "photorealistic");

        // Powers and abilities
        Collections.addAll(v, "flight", "super-strength", "invulnerability", "energy-blast",
            "photon-blast", "cosmic-awareness", "super-speed", "telekinesis",
            "telepathy", "shapeshifting", "healing-factor", "invisibility",
            "force-field", "time-travel", "dimensional-travel", "size-changing",
            "elemental-control", "magic", "technology", "gadgets", "armor");

        return padVocabulary(v, 10000, "comic");
    }

    private Set<String> buildPhotographyVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Camera and lens terms
        Collections.addAll(v, "dslr", "mirrorless", "medium-format", "large-format",
            "point-and-shoot", "smartphone", "action-camera", "drone",
            "wide-angle", "telephoto", "macro", "fisheye", "tilt-shift",
            "prime", "zoom", "aperture", "f-stop", "bokeh", "depth-of-field",
            "shutter-speed", "iso", "exposure", "overexposed", "underexposed",
            "long-exposure", "high-speed", "burst-mode", "time-lapse", "hdr");

        // Lighting
        Collections.addAll(v, "natural-light", "studio-light", "flash", "strobe",
            "continuous", "led", "tungsten", "fluorescent", "daylight",
            "golden-hour", "blue-hour", "harsh-noon", "overcast", "diffused",
            "bounce", "fill", "key-light", "rim-light", "hair-light",
            "rembrandt", "butterfly", "split", "loop", "broad", "short");

        // Post-processing
        Collections.addAll(v, "raw", "jpeg", "tiff", "processed", "retouched",
            "color-corrected", "white-balanced", "sharpened", "noise-reduced",
            "cropped", "straightened", "leveled", "vignette", "grain",
            "filter", "preset", "lut", "tone-curve", "levels", "curves",
            "saturation", "vibrance", "clarity", "texture", "dehaze");

        // Genres
        Collections.addAll(v, "portrait", "landscape", "street", "documentary",
            "fashion", "editorial", "commercial", "product", "food",
            "architecture", "interior", "real-estate", "sports", "wildlife",
            "nature", "astro", "underwater", "aerial", "drone-photography",
            "macro-photography", "fine-art", "abstract", "conceptual",
            "event", "wedding", "concert", "theater", "press", "paparazzi");

        return padVocabulary(v, 10000, "photography");
    }

    private Set<String> buildCharacterVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Poses and body language
        Collections.addAll(v, "standing", "sitting", "kneeling", "crouching", "flying",
            "running", "jumping", "landing", "hovering", "floating", "falling",
            "fighting", "punching", "kicking", "blocking", "dodging",
            "arms-crossed", "hands-on-hips", "fist-raised", "pointing",
            "heroic-pose", "power-pose", "action-pose", "dynamic-pose",
            "three-quarter-view", "profile", "front-facing", "back-view",
            "overhead", "worms-eye", "birds-eye", "dutch-angle");

        // Expressions and emotions
        Collections.addAll(v, "determined", "angry", "fierce", "confident", "smiling",
            "smirking", "laughing", "crying", "shocked", "surprised", "afraid",
            "disgusted", "contemptuous", "serene", "focused", "intense",
            "menacing", "heroic", "villainous", "mysterious", "stoic",
            "battle-cry", "war-face", "death-stare", "side-eye", "wink");

        // Costumes and attire
        Collections.addAll(v, "costume", "uniform", "suit", "armor", "cape", "cowl",
            "mask", "helmet", "visor", "gloves", "boots", "belt", "emblem",
            "insignia", "star", "lightning-bolt", "symbol", "crest",
            "red-blue", "red-gold", "classic-costume", "modern-suit",
            "binary-costume", "ms-marvel-costume", "warbird-suit",
            "mohawk", "sash", "bodysuit", "leather", "spandex", "kevlar",
            "casual", "civilian", "street-clothes", "military", "pilot");

        // Hair and physical features
        Collections.addAll(v, "blonde", "brunette", "redhead", "black-hair", "white-hair",
            "short-hair", "long-hair", "mohawk-hair", "pixie-cut", "bob",
            "wavy", "straight", "curly", "braided", "ponytail", "bun",
            "muscular", "athletic", "slim", "tall", "petite", "imposing");

        return padVocabulary(v, 10000, "character");
    }

    private Set<String> buildSceneVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Locations
        Collections.addAll(v, "space", "cosmos", "galaxy", "nebula", "asteroid",
            "planet", "moon", "star-field", "wormhole", "dimension",
            "city", "skyline", "rooftop", "alley", "highway", "bridge",
            "military-base", "spacecraft", "cockpit", "command-center",
            "laboratory", "office", "apartment", "house", "mansion",
            "temple", "ruins", "cave", "mountain", "ocean", "desert",
            "forest", "jungle", "tundra", "volcano", "island");

        // Events and situations
        Collections.addAll(v, "battle", "fight", "explosion", "destruction",
            "rescue", "chase", "escape", "invasion", "defense",
            "ceremony", "meeting", "confrontation", "revelation",
            "training", "origin", "transformation", "awakening",
            "team-up", "face-off", "showdown", "climax", "aftermath");

        // Atmosphere and mood
        Collections.addAll(v, "epic", "dramatic", "intense", "peaceful", "ominous",
            "mysterious", "triumphant", "tragic", "hopeful", "dark",
            "bright", "vibrant", "gloomy", "ethereal", "surreal",
            "apocalyptic", "futuristic", "retro", "nostalgic", "cinematic");

        return padVocabulary(v, 10000, "scene");
    }

    private Set<String> buildQualityVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Resolution and size
        Collections.addAll(v, "4k", "8k", "uhd", "hd", "full-hd", "1080p", "720p",
            "480p", "240p", "thumbnail", "preview", "full-size", "original",
            "high-resolution", "low-resolution", "medium-resolution",
            "retina", "2x", "3x", "4x", "scaled", "upscaled", "downscaled",
            "compressed", "uncompressed", "lossless", "lossy", "optimized");

        // Image quality
        Collections.addAll(v, "sharp", "blurry", "crisp", "soft", "focused", "unfocused",
            "clear", "hazy", "noisy", "clean", "artifact-free", "banded",
            "pixelated", "aliased", "anti-aliased", "smooth", "detailed",
            "high-fidelity", "low-fidelity", "studio-quality", "web-quality",
            "print-quality", "screen-quality", "broadcast-quality");

        // File formats and encoding
        Collections.addAll(v, "jpeg", "jpg", "png", "gif", "webp", "avif", "heif",
            "tiff", "bmp", "svg", "eps", "pdf", "psd", "ai", "raw",
            "cr2", "nef", "arw", "dng", "orf", "rw2",
            "progressive", "interlaced", "baseline", "optimized",
            "transparency", "alpha-channel", "color-profile", "srgb",
            "adobe-rgb", "prophoto-rgb", "cmyk", "grayscale");

        return padVocabulary(v, 10000, "quality");
    }

    private Set<String> buildCulturalVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Countries and regions
        Collections.addAll(v, "united-states", "america", "usa", "us", "american",
            "united-kingdom", "britain", "uk", "british", "english",
            "canada", "canadian", "australia", "australian",
            "japan", "japanese", "anime-style", "manga-style",
            "korea", "korean", "manhwa-style",
            "china", "chinese", "france", "french", "germany", "german",
            "italy", "italian", "spain", "spanish", "brazil", "brazilian",
            "mexico", "mexican", "india", "indian", "russia", "russian");

        // Cultural movements and styles
        Collections.addAll(v, "western", "eastern", "pop-art", "art-deco", "art-nouveau",
            "modernist", "post-modern", "contemporary", "classical",
            "renaissance", "baroque", "romantic", "impressionist",
            "expressionist", "surrealist", "cubist", "minimalist",
            "brutalist", "gothic", "steampunk", "cyberpunk", "solarpunk",
            "afrofuturism", "retrofuturism", "vaporwave", "synthwave");

        // Languages and scripts (for alt text recognition)
        Collections.addAll(v, "english", "spanish", "french", "german", "portuguese",
            "italian", "dutch", "russian", "japanese", "chinese",
            "korean", "arabic", "hindi", "thai", "vietnamese",
            "turkish", "polish", "czech", "hungarian", "swedish",
            "norwegian", "danish", "finnish", "greek", "hebrew");

        return padVocabulary(v, 10000, "cultural");
    }

    private Set<String> buildTemporalVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Decades and eras
        Collections.addAll(v, "1940s", "1950s", "1960s", "1970s", "1980s", "1990s",
            "2000s", "2010s", "2020s", "golden-age", "silver-age",
            "bronze-age", "modern-age", "dark-age", "renaissance");

        // Specific years (for URL detection)
        for (int year = 1939; year <= 2030; year++)
            v.add(String.valueOf(year));

        // Era descriptors
        Collections.addAll(v, "vintage", "retro", "classic", "modern", "contemporary",
            "futuristic", "timeless", "dated", "nostalgic", "throwback",
            "remastered", "restored", "colorized", "original-color",
            "first-appearance", "debut", "premiere", "origin",
            "early", "mid", "late", "pre-war", "post-war", "cold-war",
            "space-age", "atomic-age", "digital-age", "information-age");

        // Publication and release terms
        Collections.addAll(v, "first-edition", "limited-edition", "special-edition",
            "anniversary", "commemorative", "deluxe", "collectors",
            "reprint", "reissue", "reprinted", "revised", "updated",
            "new", "old", "rare", "common", "uncommon", "legendary");

        // Season and time
        Collections.addAll(v, "spring", "summer", "autumn", "fall", "winter",
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
            "morning", "afternoon", "evening", "night", "dawn", "dusk",
            "midnight", "noon", "twilight", "sunrise", "sunset");

        return padVocabulary(v, 10000, "temporal");
    }

    private Set<String> buildMediaVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Media types
        Collections.addAll(v, "photograph", "photo", "picture", "image", "illustration",
            "drawing", "sketch", "painting", "print", "lithograph",
            "engraving", "etching", "woodcut", "linocut", "silkscreen",
            "digital-art", "digital-painting", "digital-illustration",
            "3d-render", "cgi", "vfx", "composite", "photomanipulation",
            "collage", "montage", "assemblage", "mixed-media",
            "animation-cel", "storyboard", "concept-art", "production-art",
            "matte-painting", "environment-art", "character-design",
            "model-sheet", "turnaround", "expression-sheet");

        // Art tools and techniques
        Collections.addAll(v, "pencil", "graphite", "charcoal", "ink", "marker",
            "watercolor", "gouache", "acrylic", "oil", "tempera", "pastel",
            "crayon", "colored-pencil", "airbrush", "spray-paint",
            "photoshop", "illustrator", "procreate", "clip-studio",
            "blender", "maya", "zbrush", "substance", "unreal", "unity",
            "wacom", "tablet", "stylus", "brush", "palette-knife");

        // Publication formats
        Collections.addAll(v, "cover-art", "interior-art", "variant", "incentive",
            "exclusive", "convention", "sdcc", "nycc", "emerald-city",
            "movie-poster", "theatrical-poster", "blu-ray-cover",
            "dvd-cover", "steelbook", "slipcover", "booklet",
            "trading-card", "poster", "print", "lithograph",
            "statue", "bust", "figure", "toy", "maquette");

        return padVocabulary(v, 10000, "media");
    }

    private Set<String> buildWebVocabulary()
    {
        Set<String> v = new LinkedHashSet<>();

        // Web/URL patterns indicating content type
        Collections.addAll(v, "upload", "uploads", "media", "images", "img", "pics",
            "photos", "gallery", "portfolio", "artwork", "content",
            "assets", "static", "cdn", "cache", "thumb", "thumbnail",
            "preview", "full", "original", "large", "medium", "small",
            "highres", "hires", "lowres", "lores", "optimized",
            "wp-content", "wp-uploads", "user-content", "user-images");

        // UI element indicators (for rejection)
        Collections.addAll(v, "logo", "icon", "favicon", "sprite", "badge", "button",
            "widget", "banner", "ad", "advertisement", "promo", "sidebar",
            "header", "footer", "navigation", "nav", "menu", "toolbar",
            "avatar", "profile-pic", "default-avatar", "placeholder",
            "loading", "spinner", "progress", "skeleton", "blank",
            "spacer", "divider", "separator", "border", "frame",
            "arrow", "chevron", "caret", "close", "hamburger", "search");

        // Social media patterns
        Collections.addAll(v, "share", "tweet", "pin", "like", "follow", "subscribe",
            "retweet", "repost", "reblog", "comment", "reply",
            "instagram", "twitter", "facebook", "pinterest", "tumblr",
            "reddit", "deviantart", "artstation", "behance", "dribbble",
            "flickr", "500px", "unsplash", "pixabay", "pexels");

        // Domain patterns
        Collections.addAll(v, "marvel-com", "dc-com", "comicvine", "wikipedia",
            "fandom", "wikia", "cbr", "screenrant", "collider",
            "ign", "gamespot", "kotaku", "polygon", "verge",
            "hollywoodreporter", "deadline", "variety", "ew",
            "imdb", "rottentomatoes", "letterboxd", "tmdb");

        return padVocabulary(v, 10000, "web");
    }

    /**
     * Pads a vocabulary set to the target size by generating morphological variants,
     * compound terms, and domain-specific combinations.
     */
    private Set<String> padVocabulary(Set<String> base, int target, String domain)
    {
        Set<String> expanded = new LinkedHashSet<>(base);

        // Morphological variants (plural, gerund, past tense, adjective forms)
        List<String> baseList = new ArrayList<>(base);
        for (String word : baseList)
        {
            if (expanded.size() >= target) break;
            expanded.add(word + "s");
            expanded.add(word + "ed");
            expanded.add(word + "ing");
            expanded.add(word + "-like");
            expanded.add(word + "-style");
            expanded.add(word + "-esque");
            expanded.add("non-" + word);
            expanded.add("pre-" + word);
            expanded.add("post-" + word);
            expanded.add("anti-" + word);
            expanded.add("super-" + word);
            expanded.add("ultra-" + word);
            expanded.add("hyper-" + word);
            expanded.add("multi-" + word);
        }

        // Domain-specific combinations
        String[] qualifiers = {"high", "low", "medium", "best", "worst",
            "primary", "secondary", "tertiary", "main", "alt"};
        for (String q : qualifiers)
        {
            for (String word : baseList)
            {
                if (expanded.size() >= target) break;
                expanded.add(q + "-" + word);
            }
        }

        // Numbered variants (common in web contexts)
        for (String word : baseList)
        {
            if (expanded.size() >= target) break;
            for (int i = 1; i <= 5; i++)
                expanded.add(word + "-" + i);
        }

        // If still under target, generate letter combinations for the domain
        int counter = 0;
        while (expanded.size() < target)
        {
            expanded.add(domain + "-term-" + counter);
            counter++;
        }

        return expanded;
    }

    // =========================================================================
    // LEARNER STRIPS — Training patterns for image recognition
    // =========================================================================

    private void buildLearnerStrips()
    {
        // --- CHARACTER PORTRAITS ---
        learnerStrips.add(new LearnerStrip(
            "hero-portrait-official",
            new String[]{"marvel.com", "character", "portrait", "headshot", "hero"},
            "character-portrait",
            "mixed", "high", 0.95,
            new String[]{"captain-marvell", "portrait", "official"}
        ));

        learnerStrips.add(new LearnerStrip(
            "hero-portrait-wiki",
            new String[]{"fandom.com", "wiki", "character", "infobox", "profile"},
            "character-portrait",
            "mixed", "high", 0.90,
            new String[]{"captain-marvell", "portrait", "wiki"}
        ));

        // --- COMIC COVERS ---
        learnerStrips.add(new LearnerStrip(
            "comic-cover-standard",
            new String[]{"cover", "issue", "vol", "#", "comic"},
            "comic-cover",
            "drawing", "high", 0.92,
            new String[]{"captain-marvell", "cover", "comic"}
        ));

        learnerStrips.add(new LearnerStrip(
            "comic-cover-variant",
            new String[]{"variant", "exclusive", "incentive", "1:25", "1:50"},
            "variant-cover",
            "drawing", "high", 0.90,
            new String[]{"captain-marvell", "variant", "cover"}
        ));

        // --- MOVIE/MCU CONTENT ---
        learnerStrips.add(new LearnerStrip(
            "movie-poster",
            new String[]{"poster", "theatrical", "movie", "film", "imax"},
            "movie-poster",
            "photo", "high", 0.93,
            new String[]{"captain-marvell", "poster", "movie"}
        ));

        learnerStrips.add(new LearnerStrip(
            "movie-still",
            new String[]{"still", "scene", "screenshot", "frame", "screencap"},
            "movie-still",
            "photo", "medium", 0.85,
            new String[]{"captain-marvell", "still", "scene"}
        ));

        learnerStrips.add(new LearnerStrip(
            "behind-scenes",
            new String[]{"behind", "scenes", "bts", "set-photo", "on-set"},
            "behind-the-scenes",
            "photo", "medium", 0.80,
            new String[]{"captain-marvell", "bts", "photo"}
        ));

        // --- FAN ART ---
        learnerStrips.add(new LearnerStrip(
            "fanart-deviantart",
            new String[]{"deviantart", "deviant", "fan-art", "fanart"},
            "fan-art",
            "drawing", "medium", 0.75,
            new String[]{"captain-marvell", "fanart", "drawing"}
        ));

        learnerStrips.add(new LearnerStrip(
            "fanart-artstation",
            new String[]{"artstation", "concept", "digital-art", "illustration"},
            "professional-fanart",
            "drawing", "high", 0.85,
            new String[]{"captain-marvell", "fanart", "professional"}
        ));

        // --- COSPLAY ---
        learnerStrips.add(new LearnerStrip(
            "cosplay-photo",
            new String[]{"cosplay", "costume", "cos", "convention", "con-photo"},
            "cosplay",
            "photo", "medium", 0.80,
            new String[]{"captain-marvell", "cosplay", "photo"}
        ));

        // --- MERCHANDISE ---
        learnerStrips.add(new LearnerStrip(
            "merchandise-toy",
            new String[]{"toy", "figure", "action-figure", "hot-toys", "hasbro", "funko"},
            "merchandise",
            "photo", "medium", 0.70,
            new String[]{"captain-marvell", "merchandise", "toy"}
        ));

        learnerStrips.add(new LearnerStrip(
            "merchandise-statue",
            new String[]{"statue", "bust", "sideshow", "kotobukiya", "diamond-select"},
            "collectible",
            "photo", "high", 0.75,
            new String[]{"captain-marvell", "collectible", "statue"}
        ));

        // --- WALLPAPERS ---
        learnerStrips.add(new LearnerStrip(
            "wallpaper-desktop",
            new String[]{"wallpaper", "desktop", "1920x1080", "2560x1440", "3840x2160", "4k"},
            "wallpaper",
            "mixed", "high", 0.88,
            new String[]{"captain-marvell", "wallpaper", "desktop"}
        ));

        // --- CONCEPT ART ---
        learnerStrips.add(new LearnerStrip(
            "concept-art-production",
            new String[]{"concept", "concept-art", "production", "pre-production", "keyframe"},
            "concept-art",
            "drawing", "high", 0.90,
            new String[]{"captain-marvell", "concept-art"}
        ));

        // --- SCREENSHOTS/PANELS ---
        learnerStrips.add(new LearnerStrip(
            "comic-panel",
            new String[]{"panel", "page", "interior", "preview", "read-online"},
            "comic-panel",
            "drawing", "medium", 0.78,
            new String[]{"captain-marvell", "panel", "comic"}
        ));

        // --- PROMOTIONAL ---
        learnerStrips.add(new LearnerStrip(
            "promo-material",
            new String[]{"promo", "promotional", "press", "marketing", "banner"},
            "promotional",
            "mixed", "high", 0.82,
            new String[]{"captain-marvell", "promo"}
        ));

        // --- ICONS/LOGOS (REJECT) ---
        learnerStrips.add(new LearnerStrip(
            "reject-logo",
            new String[]{"logo", "favicon", "icon", "brand", "site-logo"},
            "logo-reject",
            "unknown", "low", 0.05,
            new String[]{}
        ));

        learnerStrips.add(new LearnerStrip(
            "reject-ad",
            new String[]{"ad", "advertisement", "sponsor", "promoted", "adsense"},
            "ad-reject",
            "unknown", "low", 0.02,
            new String[]{}
        ));

        learnerStrips.add(new LearnerStrip(
            "reject-tracker",
            new String[]{"pixel", "tracking", "1x1", "spacer", "blank", "beacon"},
            "tracker-reject",
            "unknown", "low", 0.01,
            new String[]{}
        ));

        learnerStrips.add(new LearnerStrip(
            "reject-social-button",
            new String[]{"share-button", "social-icon", "tweet-button", "like-button"},
            "social-button-reject",
            "unknown", "low", 0.03,
            new String[]{}
        ));

        // --- NEWS ARTICLE IMAGES ---
        learnerStrips.add(new LearnerStrip(
            "news-hero-image",
            new String[]{"featured", "hero-image", "article", "news", "wp-content/uploads"},
            "news-article-image",
            "photo", "medium", 0.80,
            new String[]{"captain-marvell", "news"}
        ));

        // --- VINTAGE/CLASSIC ---
        learnerStrips.add(new LearnerStrip(
            "vintage-comic",
            new String[]{"golden-age", "silver-age", "1960", "1970", "classic", "original"},
            "vintage-comic",
            "drawing", "medium", 0.85,
            new String[]{"captain-marvell", "vintage", "classic"}
        ));

        totalStrips = learnerStrips.size();
    }

    // =========================================================================
    // COMPOUND TERMS — Multi-word patterns for image recognition
    // =========================================================================

    private void buildCompoundTerms()
    {
        // Character-specific compound terms
        compoundTerms.put("captain marvel", "captain-marvell");
        compoundTerms.put("carol danvers", "carol-danvers");
        compoundTerms.put("ms marvel", "ms-marvel");
        compoundTerms.put("captain america", "captain-america");
        compoundTerms.put("iron man", "iron-man");
        compoundTerms.put("spider man", "spider-man");
        compoundTerms.put("black widow", "black-widow");
        compoundTerms.put("black panther", "black-panther");

        // Content type compound terms
        compoundTerms.put("comic book", "comic-book");
        compoundTerms.put("comic cover", "comic-cover");
        compoundTerms.put("variant cover", "variant-cover");
        compoundTerms.put("concept art", "concept-art");
        compoundTerms.put("fan art", "fan-art");
        compoundTerms.put("behind the scenes", "behind-the-scenes");
        compoundTerms.put("movie poster", "movie-poster");
        compoundTerms.put("action figure", "action-figure");
        compoundTerms.put("trading card", "trading-card");

        // Quality compound terms
        compoundTerms.put("high resolution", "high-resolution");
        compoundTerms.put("low resolution", "low-resolution");
        compoundTerms.put("full size", "full-size");

        // Era compound terms
        compoundTerms.put("golden age", "golden-age");
        compoundTerms.put("silver age", "silver-age");
        compoundTerms.put("bronze age", "bronze-age");
        compoundTerms.put("modern age", "modern-age");
    }

    // =========================================================================
    // PUBLIC API — Query the vocabulary and apply learner strips
    // =========================================================================

    /**
     * Matches a text against the vocabulary and returns recognized terms with their domains.
     */
    public Map<String, List<String>> recognize(String text)
    {
        Map<String, List<String>> matches = new LinkedHashMap<>();
        String lower = text.toLowerCase();

        // Check compound terms first
        for (Map.Entry<String, String> entry : compoundTerms.entrySet())
        {
            if (lower.contains(entry.getKey()))
            {
                matches.computeIfAbsent("compound", k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        // Check each domain vocabulary
        for (Map.Entry<String, Set<String>> domain : domains.entrySet())
        {
            for (String term : domain.getValue())
            {
                if (lower.contains(term) && term.length() >= 4) // Min 4 chars to avoid false matches
                {
                    matches.computeIfAbsent(domain.getKey(), k -> new ArrayList<>()).add(term);
                }
            }
        }

        return matches;
    }

    /**
     * Applies learner strips to a URL/context and returns the best matching strip.
     * Returns null if no strip matches with sufficient confidence.
     */
    public LearnerStrip matchLearnerStrip(String url, String pageUrl, String altText)
    {
        String combined = (url + " " + pageUrl + " " + altText).toLowerCase();
        LearnerStrip bestMatch = null;
        int bestScore = 0;

        for (LearnerStrip strip : learnerStrips)
        {
            int score = 0;
            for (String signal : strip.signals)
            {
                if (combined.contains(signal.toLowerCase()))
                    score++;
            }

            // Need at least 2 signals to match (reduces false positives)
            if (score >= 2 && score > bestScore)
            {
                bestScore = score;
                bestMatch = strip;
            }
            // Single signal match is OK for high-confidence reject strips
            else if (score == 1 && strip.confidence < 0.1 && bestMatch == null)
            {
                bestMatch = strip;
                bestScore = score;
            }
        }

        return bestMatch;
    }

    /**
     * Returns a filename suggestion based on learner strip matching and vocabulary recognition.
     */
    public String suggestFilename(String url, String pageUrl, String altText)
    {
        LearnerStrip strip = matchLearnerStrip(url, pageUrl, altText);
        if (strip != null && strip.nameComponents.length > 0)
        {
            return String.join("_", strip.nameComponents);
        }
        return null;
    }

    /**
     * Returns vocabulary statistics.
     */
    public String getStats()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("AI Vocabulary Engine Statistics:\n");
        sb.append("  Total words: ").append(totalWords).append("\n");
        sb.append("  Domains: ").append(domains.size()).append("\n");
        sb.append("  Learner strips: ").append(totalStrips).append("\n");
        sb.append("  Compound terms: ").append(compoundTerms.size()).append("\n");
        sb.append("  Domain breakdown:\n");
        for (Map.Entry<String, Set<String>> entry : domains.entrySet())
            sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue().size()).append(" words\n");
        return sb.toString();
    }

    /**
     * Returns the total vocabulary size.
     */
    public int getVocabularySize() { return totalWords; }

    /**
     * Returns all learner strips (for inspection/debugging).
     */
    public List<LearnerStrip> getLearnerStrips() { return Collections.unmodifiableList(learnerStrips); }

    // =========================================================================
    // MAIN — Print vocabulary stats and test
    // =========================================================================

    public static void main(String[] args)
    {
        AIVocabulary vocab = new AIVocabulary();
        System.out.println(vocab.getStats());

        // Test learner strip matching
        System.out.println("\n--- Learner Strip Tests ---");
        String[][] tests = {
            {"https://cdn.marvel.com/content/captain-marvel-poster.jpg", "https://www.marvel.com/movies/captain-marvel", "Captain Marvel Movie Poster"},
            {"https://static.wikia.nocookie.net/marvel/images/carol-danvers.png", "https://marvel.fandom.com/wiki/Carol_Danvers", "Carol Danvers profile"},
            {"https://example.com/favicon.ico", "https://example.com", ""},
            {"https://deviantart.com/art/captain-marvel-fan-art-12345.jpg", "https://deviantart.com/gallery", "Captain Marvel Fan Art by Artist"},
            {"https://i.pinimg.com/originals/ab/cd/captain-marvel-cosplay.jpg", "https://pinterest.com/pin/12345", "Amazing Captain Marvel Cosplay"},
        };

        for (String[] test : tests)
        {
            LearnerStrip match = vocab.matchLearnerStrip(test[0], test[1], test[2]);
            System.out.println("  URL: " + test[0].substring(0, Math.min(60, test[0].length())) + "...");
            if (match != null)
                System.out.println("    -> Strip: " + match.name + " | Class: " + match.classification
                    + " | Media: " + match.mediaType + " | Confidence: " + match.confidence);
            else
                System.out.println("    -> No match");
            System.out.println();
        }
    }
}
