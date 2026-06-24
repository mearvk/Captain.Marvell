import java.io.*;

public class Main
{
    public static void main(String[] args) throws IOException
    {
        String baseDir = args.length > 0 ? args[0] : "..";
        String configPath = "configuration/search-engines.config";

        System.out.println("=== Captain Marvell System ===\n");

        // Run search engine client
        System.out.println("[1] Running Search Engine Client...\n");
        SearchEngineClient client = new SearchEngineClient(configPath);
        client.searchAll();

        // Run AI module
        System.out.println("[2] Running AI Module...\n");
        AIModule ai = new AIModule(baseDir);
        ai.processIncomingFiles();

        System.out.println("\n=== Complete ===");
    }
}
