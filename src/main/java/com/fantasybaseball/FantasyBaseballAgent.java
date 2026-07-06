package com.fantasybaseball;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Fantasy Baseball News Agent using LangChain4J with Qwen 3.5 via omlx.
 *
 * Prerequisites:
 * 1. Start omlx server on 127.0.0.1:8000
 * 2. Configure email.properties for email delivery
 */
public class FantasyBaseballAgent {

    // Agent interface with system prompt
    interface BaseballNewsAssistant {
        @SystemMessage("""
            You are an expert fantasy baseball analyst assistant. Your job is to:
            1. Fetch the latest fantasy baseball news from multiple sources (last 2 days only)
            2. Analyze and summarize the most important stories
            3. Highlight actionable fantasy insights (players to add/drop, injury updates, prospect call-ups)
            4. Focus on news that impacts fantasy baseball decisions

            IMPORTANT: All news is filtered to the last 2 days. Focus only on recent, actionable information.

            When summarizing news:
            - Lead with the most impactful stories from the past 2 days
            - Mention specific player names and their teams
            - Include injury statuses and expected return dates when available
            - Note any prospect promotions or demotions
            - Highlight hot/cold streaks that could affect roster decisions
            - Clearly indicate dates when available
            - Use markdown formatting with headers, bold, and tables for clarity

            Be concise but thorough. Group related news together by category (injuries, call-ups, performance).
            """)
        String chat(String userMessage);
    }

    private static String lastResponse = null;
    private static EmailService emailService = null;

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  Fantasy Baseball News Agent");
        System.out.println("  Powered by Qwen 3.5 via Ollama + LangChain4J");
        System.out.println("==============================================\n");

        // Initialize email service
        try {
            emailService = new EmailService();
            System.out.println("Email service initialized.\n");
        } catch (Exception e) {
            System.err.println("Warning: Email service not available: " + e.getMessage() + "\n");
        }

        // Configure the OpenAI-compatible model (omlx)
        // Longer timeout needed for full report (scraping + LLM processing)
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("http://127.0.0.1:8000/v1")
                .apiKey("not-needed")
                .modelName("Qwen3.5-4B-MLX-4bit")
                .timeout(Duration.ofMinutes(30))
                .temperature(0.7)
                .build();

        // Create the tools instance
        WebScraperTools tools = new WebScraperTools();

        // Build the AI service with tools and memory
        BaseballNewsAssistant assistant = AiServices.builder(BaseballNewsAssistant.class)
                .chatLanguageModel(model)
                .tools(tools)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        // Check for command-line arguments (non-interactive mode)
        if (args.length > 0) {
            runCommandLine(args, assistant);
            return;
        }

        // Interactive chat loop
        Scanner scanner = new Scanner(System.in);

        printHelp();

        while (true) {
            System.out.print("You: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye! Good luck with your fantasy team!");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            // Handle special commands
            if (input.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            }

            if (input.equalsIgnoreCase("save")) {
                saveLastResponse();
                continue;
            }

            if (input.equalsIgnoreCase("email")) {
                emailLastResponse();
                continue;
            }

            if (input.equalsIgnoreCase("send")) {
                saveAndEmailLastResponse();
                continue;
            }

            if (input.equalsIgnoreCase("podcasts")) {
                sendPodcastSummaries();
                continue;
            }

            if (input.equalsIgnoreCase("daily")) {
                // Generate full report, save, and email
                generateDailyReport(assistant, true, false);
                continue;
            }

            if (input.equalsIgnoreCase("report")) {
                // Generate full report and save only (no email)
                generateDailyReport(assistant, false, false);
                continue;
            }

            if (input.equalsIgnoreCase("quick")) {
                // Generate quick report (RSS + API only, no Playwright)
                generateDailyReport(assistant, false, true);
                continue;
            }

            // Map shortcuts to full queries
            String query = mapShortcutToQuery(input);

            try {
                System.out.println("\nAgent: Analyzing sources...\n");
                String response = assistant.chat(query);
                lastResponse = response;
                System.out.println("Agent: " + response + "\n");
                System.out.println("(Type 'save' to save, 'email' to send, or 'send' for both)\n");
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Make sure Ollama is running with the qwen3.5:4b model.");
                System.err.println("Ensure omlx server is running on 127.0.0.1:8000\n");
            }
        }

        scanner.close();
    }

    /**
     * Run in non-interactive command-line mode.
     * Usage: java ... FantasyBaseballAgent [command] [--email]
     * Commands: quick, report, daily
     * --email: Send email after generating report
     */
    private static void runCommandLine(String[] args, BaseballNewsAssistant assistant) {
        String command = args[0].toLowerCase();
        boolean sendEmail = false;

        // Check for --email flag
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--email") || arg.equalsIgnoreCase("-e")) {
                sendEmail = true;
            }
        }

        System.out.println("Running in command-line mode: " + command + (sendEmail ? " (with email)" : ""));

        switch (command) {
            case "quick":
                generateDailyReport(assistant, sendEmail, true);
                break;
            case "report":
                generateDailyReport(assistant, sendEmail, false);
                break;
            case "daily":
                // daily always sends email
                generateDailyReport(assistant, true, false);
                break;
            case "podcasts":
                sendPodcastSummaries();
                break;
            case "podcasts-preview":
                // print the digest markdown to stdout without emailing (dry run)
                System.out.println(getPodcastSummariesMarkdown());
                break;
            case "help":
            case "--help":
            case "-h":
                printCommandLineHelp();
                break;
            default:
                System.err.println("Unknown command: " + command);
                printCommandLineHelp();
                System.exit(1);
        }
    }

    private static void printCommandLineHelp() {
        System.out.println("Usage: mvn exec:java -Dexec.args=\"[command] [options]\"");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  quick          Fast report (RSS + API only), save to file");
        System.out.println("  report         Full report (all sources), save to file");
        System.out.println("  daily          Full report, save, and email");
        System.out.println("  podcasts       Email most recent podcast summaries");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --email, -e    Send email after generating report");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mvn exec:java -Dexec.args=\"quick\"");
        System.out.println("  mvn exec:java -Dexec.args=\"quick --email\"");
        System.out.println("  mvn exec:java -Dexec.args=\"daily\"");
    }

    private static void printHelp() {
        System.out.println("Commands (all filtered to last 2 days):");
        System.out.println("  'news'         - Get a summary of ALL fantasy baseball news");
        System.out.println("  'quick'        - Fast report (RSS + API only, no browser) + save");
        System.out.println("  'report'       - Full report (all sources incl. browser) + save");
        System.out.println("  'daily'        - Full report + save + email");
        System.out.println("  'transactions' - MLB transactions (trades, signings, DFA)");
        System.out.println("  'injuries'     - Current IL and injury updates");
        System.out.println("  'rosters'      - Call-ups, options, and roster moves");
        System.out.println("  'pitchers'     - Pitcher List analysis and rankings");
        System.out.println("  'espn'         - ESPN news");
        System.out.println("  'cbs'          - CBS Sports news");
        System.out.println("  'mlb'          - Official MLB.com news");
        System.out.println("  'nbc'          - NBC Sports/Rotoworld player news");
        System.out.println("  'fangraphs'    - Fangraphs analysis");
        System.out.println("  'prospects'    - Baseball America prospect news");
        System.out.println();
        System.out.println("Output commands:");
        System.out.println("  'save'         - Save last response to text file");
        System.out.println("  'email'        - Email last response to recipients");
        System.out.println("  'send'         - Save AND email last response");
        System.out.println("  'podcasts'     - Email most recent podcast summaries");
        System.out.println("  'help'         - Show this help message");
        System.out.println("  'quit'         - Exit the agent");
        System.out.println("\nOr ask any fantasy baseball question!\n");
    }

    private static String mapShortcutToQuery(String input) {
        return switch (input.toLowerCase()) {
            case "news" -> "Fetch recent news from all sources and give me a comprehensive summary of the most important fantasy baseball news from the last 2 days.";
            case "transactions" -> "Fetch MLB transactions from the last 2 days and summarize the most fantasy-relevant moves (trades, signings, DFAs, waivers).";
            case "injuries" -> "Fetch current MLB injuries and summarize the most fantasy-relevant IL placements and expected return dates.";
            case "rosters" -> "Fetch roster moves from the last 2 days and highlight call-ups, options, and IL moves that impact fantasy.";
            case "pitchers" -> "Fetch news from Pitcher List and summarize pitcher rankings, streaming options, and pitching analysis.";
            case "espn" -> "Fetch the latest ESPN fantasy baseball news (last 2 days) and highlight the most actionable items.";
            case "cbs" -> "Fetch the latest CBS Sports MLB news (last 2 days) and summarize key fantasy insights.";
            case "mlb" -> "Fetch official MLB.com news (last 2 days) and highlight fantasy-relevant stories.";
            case "fangraphs" -> "Fetch the latest news from Fangraphs (last 2 days) and summarize the key fantasy insights.";
            case "nbc" -> "Fetch the latest NBC Sports player news (last 2 days) and summarize injury updates and player moves.";
            case "prospects" -> "Fetch news from Baseball America (last 2 days) and summarize any prospect call-ups or demotions that could impact fantasy leagues.";
            default -> input;
        };
    }

    private static void saveLastResponse() {
        if (lastResponse == null) {
            System.out.println("No response to save. Run a query first.\n");
            return;
        }
        if (emailService == null) {
            System.err.println("Email service not initialized.\n");
            return;
        }
        try {
            String txtPath = emailService.saveToFile(lastResponse);
            System.out.println("Saved: " + txtPath);

            String htmlPath = emailService.saveHtmlToFile(lastResponse);
            System.out.println("Saved: " + htmlPath);
        } catch (Exception e) {
            System.err.println("Error saving file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void emailLastResponse() {
        if (lastResponse == null) {
            System.out.println("No response to email. Run a query first.\n");
            return;
        }
        if (emailService == null) {
            System.err.println("Email service not initialized. Check email.properties.\n");
            return;
        }
        emailService.sendEmail(lastResponse);
    }

    private static void saveAndEmailLastResponse() {
        saveLastResponse();
        emailLastResponse();
    }

    private static void sendPodcastSummaries() {
        if (emailService == null) {
            System.err.println("Email service not initialized. Check email.properties.\n");
            return;
        }
        List<PodcastEpisode> episodes = collectRecentEpisodes();
        String summaries = getPodcastSummariesMarkdown();
        if (summaries.isEmpty()) {
            System.out.println("No recent podcast summaries found (last " + PODCAST_LOOKBACK_DAYS + " days).\n");
            return;
        }
        System.out.println("Sending podcast summaries...");
        emailService.sendEmail(summaries);
        recordEmailed(episodes);
    }

    /** Append the emailed episodes to postprocessing/emailed.log so the health
     *  report (pipeline_report.py) can tell which episodes actually went out. */
    private static void recordEmailed(List<PodcastEpisode> episodes) {
        Path log = Paths.get(TRANSCRIPT_DIR, "emailed.log");
        String stamp = LocalDate.now().toString();
        StringBuilder sb = new StringBuilder();
        for (PodcastEpisode ep : episodes) {
            sb.append(stamp).append('\t')
              .append(ep.released().toString().replace("-", "")).append('-')
              .append(ep.title()).append('\n');
        }
        try {
            Files.writeString(log, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write emailed.log: " + e.getMessage());
        }
    }

    private static final String TRANSCRIPT_DIR = "/Users/tcunning/src/podcasttranscribe/postprocessing";
    private static final String SUMMARY_CACHE_DIR = TRANSCRIPT_DIR + "/summaries";
    private static final int PODCAST_LOOKBACK_DAYS = 2;

    // Podcasts to surface at the top of the digest, in this order (matched against
    // the "**Podcast:**" header each summary carries).
    private static final List<String> PRIORITY_PODCASTS = List.of(
        "Baseball America", "RotoWire Fantasy Baseball Podcast");

    /** One episode's summary plus the metadata parsed from its summary header. */
    private record PodcastEpisode(String podcast, String title, LocalDate released,
                                  String url, String body) {}

    /**
     * Reads podcast summaries for episodes released within the lookback window and formats
     * a digest grouped by show: priority podcasts first, then the rest alphabetically, newest
     * episode first within each. Summaries are pre-generated, so no LLM processing here.
     */
    private static String getPodcastSummariesMarkdown() {
        List<PodcastEpisode> episodes = collectRecentEpisodes();
        if (episodes.isEmpty()) {
            return "";
        }

        // group by podcast name; keep insertion order of first appearance for stability
        Map<String, List<PodcastEpisode>> byPodcast = new LinkedHashMap<>();
        for (PodcastEpisode ep : episodes) {
            byPodcast.computeIfAbsent(ep.podcast(), k -> new ArrayList<>()).add(ep);
        }

        // ordering: priority shows first (in configured order), then the rest alphabetically
        List<String> order = new ArrayList<>();
        for (String p : PRIORITY_PODCASTS) {
            if (byPodcast.containsKey(p)) order.add(p);
        }
        byPodcast.keySet().stream()
            .filter(p -> !PRIORITY_PODCASTS.contains(p))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(order::add);

        StringBuilder result = new StringBuilder();
        result.append("# Podcast Insights\n\n");
        for (String podcast : order) {
            List<PodcastEpisode> eps = byPodcast.get(podcast);
            eps.sort(Comparator.comparing(PodcastEpisode::released).reversed());
            result.append("## ").append(podcast).append("\n\n");
            for (PodcastEpisode ep : eps) {
                result.append("### ").append(ep.title()).append("\n");
                result.append("*Released: ").append(ep.released()).append("*");
                if (!ep.url().isBlank()) {
                    result.append(" · [Listen](").append(ep.url()).append(")");
                }
                result.append("\n\n").append(ep.body()).append("\n\n");
            }
        }
        result.append("---\n\n_")
              .append(episodes.size()).append(" episode")
              .append(episodes.size() == 1 ? "" : "s").append(" across ")
              .append(order.size()).append(" podcast")
              .append(order.size() == 1 ? "" : "s").append(" · generated ")
              .append(LocalDate.now()).append("_\n");
        return result.toString();
    }

    private static List<PodcastEpisode> collectRecentEpisodes() {
        List<PodcastEpisode> episodes = new ArrayList<>();
        Path transcriptPath = Paths.get(TRANSCRIPT_DIR);
        if (!Files.exists(transcriptPath)) {
            return episodes;
        }
        LocalDate cutoffDate = LocalDate.now().minusDays(PODCAST_LOOKBACK_DAYS);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        try (var dirStream = Files.newDirectoryStream(transcriptPath, "*.txt")) {
            for (Path file : dirStream) {
                String filename = file.getFileName().toString();
                if (filename.length() < 9) continue;
                LocalDate releaseDate;
                try {
                    releaseDate = LocalDate.parse(filename.substring(0, 8), formatter);
                } catch (Exception e) {
                    continue;
                }
                if (releaseDate.isBefore(cutoffDate)) continue;

                Path summaryPath = Paths.get(SUMMARY_CACHE_DIR, filename + ".summary");
                if (!Files.exists(summaryPath)) {
                    String baseName = filename.endsWith(".txt")
                        ? filename.substring(0, filename.length() - 4) : filename;
                    summaryPath = Paths.get(SUMMARY_CACHE_DIR, baseName + ".summary");
                    if (!Files.exists(summaryPath)) continue;
                }

                String title = filename.substring(9);
                if (title.endsWith(".txt")) title = title.substring(0, title.length() - 4);

                String summary = Files.readString(summaryPath);
                String podcast = extractHeaderField(summary, "Podcast");
                String url = extractHeaderField(summary, "URL");
                if (podcast.isBlank()) podcast = "Other";
                episodes.add(new PodcastEpisode(podcast, title, releaseDate, url,
                                                stripSummaryHeader(summary)));
            }
        } catch (IOException e) {
            System.err.println("Error reading podcast summaries: " + e.getMessage());
        }
        return episodes;
    }

    /** Reads a "**Field:** value" line from the top of a summary, or "" if absent. */
    private static String extractHeaderField(String summary, String field) {
        for (String line : summary.split("\n", 8)) {
            String prefix = "**" + field + ":**";
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
            if (line.startsWith("##") || line.startsWith("---")) break;  // past the header block
        }
        return "";
    }

    // Drops the leading Podcast/URL header block (and its "---" divider), which the
    // digest now renders itself, so it isn't duplicated under each episode.
    private static String stripSummaryHeader(String summary) {
        String[] lines = summary.split("\n");
        int start = 0;
        boolean sawHeader = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("**Podcast:**") || t.startsWith("**URL:**")) {
                sawHeader = true;
            } else if (sawHeader && (t.isEmpty() || t.equals("---"))) {
                start = i + 1;  // skip the divider/blank lines after the header
            } else if (sawHeader) {
                break;
            } else {
                break;  // no header present
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) sb.append(lines[i]).append("\n");
        return sb.toString().strip();
    }

    private static void generateDailyReport(BaseballNewsAssistant assistant, boolean sendEmail, boolean quickMode) {
        if (quickMode) {
            System.out.println("\nAgent: Generating quick report (RSS + API sources only)...\n");
        } else {
            System.out.println("\nAgent: Generating comprehensive daily report...\n");
        }
        try {
            String prompt;
            if (quickMode) {
                prompt = "Call fetchQuickNews to get all fantasy baseball news from the last 2 days (RSS feeds, MLB API).\n\n" +
                    "Create a fantasy baseball brief with these sections:\n" +
                    "1. Executive Summary (top 5 most impactful items)\n" +
                    "2. Injury Report (IL placements, returns, updates)\n" +
                    "3. Transactions & Roster Moves (trades, signings, call-ups, options)\n" +
                    "4. Pitching News (rankings, streaming options)\n" +
                    "5. Action Items (clear add/drop recommendations)\n\n" +
                    "Use markdown formatting with headers and bold text for player names.";
            } else {
                prompt = "Call fetchAllRecentNews to get all fantasy baseball news from the last 2 days.\n\n" +
                    "Create a comprehensive daily fantasy baseball brief with these sections:\n" +
                    "1. Executive Summary (top 5 most impactful items)\n" +
                    "2. Injury Report (IL placements, returns, updates)\n" +
                    "3. Transactions & Roster Moves (trades, signings, call-ups, options)\n" +
                    "4. Pitching News (rankings, streaming options, starts to target/avoid)\n" +
                    "5. Prospect Watch (call-ups, demotions, debut candidates)\n" +
                    "6. Action Items (clear add/drop/trade recommendations)\n\n" +
                    "Use markdown formatting with headers and bold text for player names.";
            }
            String response = assistant.chat(prompt);

            // Append podcast summaries directly (already pre-processed, no need for LLM)
            String podcastSection = getPodcastSummariesMarkdown();
            if (!podcastSection.isEmpty()) {
                response = response + "\n\n" + podcastSection;
            }

            lastResponse = response;
            System.out.println("Agent: " + response + "\n");

            // Save to file
            if (emailService != null) {
                try {
                    System.out.println("Saving report to file...");
                    String txtPath = emailService.saveToFile(response);
                    System.out.println("Saved: " + txtPath);

                    String htmlPath = emailService.saveHtmlToFile(response);
                    System.out.println("Saved: " + htmlPath);

                    // Only email if requested
                    if (sendEmail) {
                        System.out.println("Emailing report...");
                        emailService.sendEmail(response);
                    }
                } catch (Exception e) {
                    System.err.println("Error saving files: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.err.println("Email service not initialized - cannot save files.");
            }
        } catch (Exception e) {
            System.err.println("Error generating daily report: " + e.getMessage());
        }
    }
}
