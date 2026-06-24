import java.io.*;
import java.net.*;
import java.util.*;

public class SearchEngineClient
{
    private Properties config = new Properties();
    private Map<String, String> engines = new LinkedHashMap<>();
    private String[] categories;
    private String[] queries;

    public SearchEngineClient(String configPath) throws IOException
    {
        config.load(new FileInputStream(configPath));

        for (String key : new String[]{"google", "bing", "yahoo", "duckduckgo", "baidu"})
        {
            if (config.containsKey(key))
                engines.put(key, config.getProperty(key));
        }

        categories = config.getProperty("categories", "audio,images,files").split(",");
        queries = config.getProperty("queries", "captain marvell").split(",");
    }

    public List<String> buildSearchURLs(String query, String category)
    {
        List<String> urls = new ArrayList<>();
        String searchTerm = URLEncoder.encode(query.trim() + " " + category.trim(), java.nio.charset.StandardCharsets.UTF_8);

        for (Map.Entry<String, String> engine : engines.entrySet())
        {
            String url = engine.getValue().replace("{query}", searchTerm);
            urls.add(engine.getKey() + ": " + url);
        }
        return urls;
    }

    public void searchAll()
    {
        System.out.println("=== Captain Marvell Search Engine Client ===\n");

        for (String query : queries)
        {
            System.out.println("=== Query: " + query.trim() + " ===\n");
            for (String category : categories)
            {
                System.out.println("--- Category: " + category.trim().toUpperCase() + " ---");
                List<String> urls = buildSearchURLs(query, category);
                for (String url : urls)
                    System.out.println("  " + url);
                System.out.println();
            }
        }
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
        String configPath = args.length > 0 ? args[0] : "configuration/search-engines.config";
        SearchEngineClient client = new SearchEngineClient(configPath);

        if (args.length > 1 && args[1].equals("--open"))
        {
            String category = args.length > 2 ? args[2] : "audio";
            client.openInBrowser(category);
        }
        else
        {
            client.searchAll();
        }
    }
}
