import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class AIModule
{
    private String baseDir;
    private List<Map<String, String>> trainingData = new ArrayList<>();

    public AIModule(String baseDir) throws IOException
    {
        this.baseDir = baseDir;
        loadTrainingData();
    }

    private void loadTrainingData() throws IOException
    {
        String json = new String(Files.readAllBytes(Paths.get(baseDir, "source", "training", "training-data.json")));
        // Simple JSON array parser for training entries
        int idx = 0;
        while ((idx = json.indexOf("\"category\"", idx)) != -1)
        {
            Map<String, String> entry = new HashMap<>();
            entry.put("category", extractValue(json, idx));
            idx = json.indexOf("\"prompt\"", idx);
            entry.put("prompt", extractValue(json, idx));
            idx = json.indexOf("\"completion\"", idx);
            entry.put("completion", extractValue(json, idx));
            trainingData.add(entry);
            idx++;
        }
    }

    private String extractValue(String json, int startIdx)
    {
        int colon = json.indexOf(":", startIdx);
        int quote1 = json.indexOf("\"", colon + 1);
        int quote2 = json.indexOf("\"", quote1 + 1);
        // Handle escaped quotes
        while (json.charAt(quote2 - 1) == '\\') quote2 = json.indexOf("\"", quote2 + 1);
        return json.substring(quote1 + 1, quote2);
    }

    public void processIncomingFiles() throws IOException
    {
        CommonRails.println("=== AI Module: Processing Incoming Files ===\n");

        List<String> audioFiles = listFiles("audio");
        List<String> imageFiles = listFiles("images");
        List<String> generalFiles = listFiles("files");

        CommonRails.println("Audio files found: " + audioFiles.size());
        CommonRails.println("Image files found: " + imageFiles.size());
        CommonRails.println("General files found: " + generalFiles.size());
        CommonRails.println();

        generateOriginsDocument(audioFiles, imageFiles, generalFiles);
        generateDescriptionDocument(audioFiles, imageFiles, generalFiles);
    }

    private List<String> listFiles(String subdir) throws IOException
    {
        Path dir = Paths.get(baseDir, subdir);
        if (!Files.exists(dir)) return Collections.emptyList();
        return Files.list(dir)
            .filter(Files::isRegularFile)
            .map(p -> p.getFileName().toString())
            .collect(Collectors.toList());
    }

    /**
     * Appends content to an existing file, or creates it with a header if new.
     * Per document-rules.xml: only append and refine, never overwrite.
     */
    private void appendToDocument(Path path, String header, String content) throws IOException
    {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path))
        {
            Files.write(path, (header + "\n\n").getBytes());
        }
        String timestamp = java.time.LocalDateTime.now().toString();
        String section = "\n---\n## Update: " + timestamp + "\n\n" + content;
        Files.write(path, section.getBytes(), StandardOpenOption.APPEND);
    }

    private void generateOriginsDocument(List<String> audio, List<String> images, List<String> files) throws IOException
    {
        StringBuilder content = new StringBuilder();

        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("origins"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Source Material Analyzed\n\n");
        content.append("- Audio sources: ").append(audio.size()).append(" files\n");
        content.append("- Image sources: ").append(images.size()).append(" files\n");
        content.append("- Document sources: ").append(files.size()).append(" files\n\n");

        content.append("### Relation to God\n\n");
        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("relation_to_god"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Relation to Her Creator\n\n");
        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("relation_to_creator"))
                content.append(entry.get("completion")).append("\n\n");

        Path path = Paths.get(baseDir, "files", "origins-of-captain-marvell.md");
        appendToDocument(path, "# The Origins of Captain Marvell", content.toString());
        CommonRails.println("Appended: files/origins-of-captain-marvell.md");
    }

    private void generateDescriptionDocument(List<String> audio, List<String> images, List<String> files) throws IOException
    {
        StringBuilder content = new StringBuilder();

        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("description"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Intelligence\n\n");
        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("intelligence"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Length of Travel in Home Universe\n\n");
        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("travel"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Number of Probable Friends\n\n");
        for (Map<String, String> entry : trainingData)
            if (entry.get("category").equals("friends"))
                content.append(entry.get("completion")).append("\n\n");

        content.append("### Source Material\n\n");
        content.append("- Audio: ").append(audio.size()).append(" files\n");
        content.append("- Images: ").append(images.size()).append(" files\n");
        content.append("- Documents: ").append(files.size()).append(" files\n");

        Path path = Paths.get(baseDir, "files", "description-of-captain-marvell.md");
        appendToDocument(path, "# Captain Marvell: Description", content.toString());
        CommonRails.println("Appended: files/description-of-captain-marvell.md");
    }

    public static void main(String[] args) throws IOException
    {
        String baseDir = args.length > 0 ? args[0] : "..";
        AIModule ai = new AIModule(baseDir);
        ai.processIncomingFiles();
    }
}
