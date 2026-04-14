package com.fantasybaseball;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.parser.Parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools for scraping fantasy baseball news using Playwright and RSS feeds.
 * Filters news to only include items from the last 2 days.
 */
public class WebScraperTools {

    private static final int MAX_AGE_DAYS = 2;
    private static final int TIMEOUT_MS = 45000;
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String TRANSCRIPT_DIR = "/Users/tcunning/src/podcasttranscribe/transcripts";

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    );

    private boolean isRecent(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return true;
        }

        dateText = dateText.trim()
            .replaceAll("(?i)(published|updated|posted):?\\s*", "")
            .replaceAll("\\s+", " ");

        String lower = dateText.toLowerCase();
        if (lower.contains("just now") || lower.contains("moment") ||
            lower.contains("second") || lower.contains("minute") ||
            lower.contains("hour") || lower.contains("today")) {
            return true;
        }
        if (lower.contains("yesterday")) {
            return true;
        }
        if (lower.matches(".*\\d+\\s*(day|d)s?\\s*ago.*")) {
            Matcher m = Pattern.compile("(\\d+)\\s*(day|d)").matcher(lower);
            if (m.find()) {
                int days = Integer.parseInt(m.group(1));
                return days <= MAX_AGE_DAYS;
            }
        }
        if (lower.matches(".*\\d+\\s*(week|month|year)s?\\s*ago.*")) {
            return false;
        }

        LocalDate now = LocalDate.now();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate parsed = LocalDate.parse(dateText, formatter);
                if (parsed.getYear() < 2000) {
                    parsed = parsed.withYear(now.getYear());
                }
                long daysBetween = ChronoUnit.DAYS.between(parsed, now);
                return daysBetween >= 0 && daysBetween <= MAX_AGE_DAYS;
            } catch (DateTimeParseException e) {
                // Try next
            }
        }
        return true;
    }

    private boolean isRecentRssDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return true;
        try {
            // RSS dates are typically in RFC 822 format
            DateTimeFormatter rssFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
            ZonedDateTime parsed = ZonedDateTime.parse(pubDate, rssFormatter);
            long daysBetween = ChronoUnit.DAYS.between(parsed.toLocalDate(), LocalDate.now());
            return daysBetween >= 0 && daysBetween <= MAX_AGE_DAYS;
        } catch (Exception e) {
            return true; // Include if can't parse
        }
    }

    private String fetchWithPlaywright(String url) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
            );
            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setUserAgent(USER_AGENT)
            );
            Page page = context.newPage();
            page.setDefaultTimeout(TIMEOUT_MS);

            page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(5000);

            String html = page.content();
            browser.close();
            return html;
        } catch (Exception e) {
            return null;
        }
    }

    @Tool("Fetches fantasy baseball news from Fangraphs published in the last 2 days")
    public String fetchFangraphsNews() {
        // Fangraphs has Cloudflare protection on main site, try their blog RSS
        try {
            Document doc = Jsoup.connect("https://blogs.fangraphs.com/feed/")
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .parser(Parser.xmlParser())
                    .get();

            StringBuilder news = new StringBuilder("=== FANGRAPHS NEWS (Last 2 Days) ===\n\n");

            Elements items = doc.select("item");
            int count = 0;
            for (Element item : items) {
                if (count >= 10) break;

                String pubDate = item.select("pubDate").text();
                if (!isRecentRssDate(pubDate)) continue;

                String title = item.select("title").text();
                String description = item.select("description").text();
                // Clean HTML from description
                description = Jsoup.parse(description).text();

                if (!title.isBlank()) {
                    news.append("- **").append(title.trim()).append("**\n");
                    if (!description.isBlank()) {
                        String shortDesc = description.length() > 300 ? description.substring(0, 300) + "..." : description;
                        news.append("  ").append(shortDesc.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            return count == 0 ? "No recent news (last 2 days) found on Fangraphs." : news.toString();
        } catch (IOException e) {
            return "Error fetching Fangraphs: " + e.getMessage();
        }
    }

    @Tool("Fetches fantasy baseball player news from NBC Sports/Rotoworld published in the last 2 days")
    public String fetchNbcSportsNews() {
        try {
            String html = fetchWithPlaywright("https://www.nbcsports.com/fantasy/baseball/player-news");
            if (html == null) {
                return "Error: Could not fetch NBC Sports";
            }

            Document doc = Jsoup.parse(html);
            StringBuilder news = new StringBuilder("=== NBC SPORTS/ROTOWORLD PLAYER NEWS (Last 2 Days) ===\n\n");

            // Use the correct selectors found from debugging
            Elements newsItems = doc.select(".PlayerNewsPost");

            int count = 0;
            for (Element item : newsItems) {
                if (count >= 15) break;

                // Extract date
                String dateText = item.select(".PlayerNewsPost-date").text();
                if (!isRecent(dateText)) continue;

                // Extract player name
                String firstName = item.select(".PlayerNewsPost-firstName").text();
                String lastName = item.select(".PlayerNewsPost-lastName").text();
                String playerName = (firstName + " " + lastName).trim();

                // Extract team
                String team = item.select(".PlayerNewsPost-team a").attr("href");
                if (team.contains("/")) {
                    team = team.substring(team.lastIndexOf("/") + 1).replace("-", " ");
                }

                // Extract headline and analysis
                String headline = item.select(".PlayerNewsPost-headline").text();
                String analysis = item.select(".PlayerNewsPost-analysis").text();

                if (!playerName.isBlank() && (!headline.isBlank() || !analysis.isBlank())) {
                    news.append("**").append(playerName);
                    if (!team.isBlank()) {
                        news.append("** (").append(team).append(")");
                    } else {
                        news.append("**");
                    }
                    if (!dateText.isBlank()) {
                        news.append(" - ").append(dateText);
                    }
                    news.append("\n");

                    if (!headline.isBlank()) {
                        news.append("  *").append(headline.trim()).append("*\n");
                    }
                    if (!analysis.isBlank()) {
                        String shortAnalysis = analysis.length() > 350 ? analysis.substring(0, 350) + "..." : analysis;
                        news.append("  ").append(shortAnalysis.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            return count == 0 ? "No recent player news (last 2 days) found on NBC Sports." : news.toString();
        } catch (Exception e) {
            return "Error fetching NBC Sports: " + e.getMessage();
        }
    }

    @Tool("Fetches MLB prospect news from Baseball America published in the last 2 days")
    public String fetchBaseballAmericaNews() {
        List<String> urls = List.of(
            "https://www.baseballamerica.com/",
            "https://www.baseballamerica.com/mlb-prospects-wire/"
        );

        StringBuilder news = new StringBuilder("=== BASEBALL AMERICA NEWS (Last 2 Days) ===\n\n");
        int totalCount = 0;

        for (String url : urls) {
            try {
                String html = fetchWithPlaywright(url);
                if (html == null) continue;

                Document doc = Jsoup.parse(html);
                Elements articles = doc.select("article, .post, .story, .article-card, .entry, .post-item");

                for (Element article : articles) {
                    if (totalCount >= 10) break;

                    // Try to find date
                    String dateText = article.select("time, .date, .timestamp, [datetime]").text();
                    String datetime = article.select("time, [datetime]").attr("datetime");
                    if (!datetime.isBlank()) dateText = datetime;

                    if (!isRecent(dateText)) continue;

                    Element titleEl = article.select("h2 a, h3 a, .title a, h2, h3, .headline a").first();
                    String title = titleEl != null ? titleEl.text() : "";
                    String excerpt = article.select("p, .excerpt, .dek, .summary").text();

                    if (!title.isBlank() && title.length() > 10) {
                        news.append("- **").append(title.trim()).append("**\n");
                        if (!excerpt.isBlank() && excerpt.length() > 20) {
                            String shortExcerpt = excerpt.length() > 250 ? excerpt.substring(0, 250) + "..." : excerpt;
                            news.append("  ").append(shortExcerpt.trim()).append("\n");
                        }
                        news.append("\n");
                        totalCount++;
                    }
                }
            } catch (Exception e) {
                news.append("Could not fetch ").append(url).append("\n\n");
            }
        }

        return totalCount == 0 ? "No recent news (last 2 days) found on Baseball America." : news.toString();
    }

    @Tool("Fetches fantasy baseball news from ESPN published in the last 2 days via RSS feed")
    public String fetchEspnNews() {
        try {
            // Use ESPN's RSS feed - much more reliable than scraping
            Document doc = Jsoup.connect("https://www.espn.com/espn/rss/mlb/news")
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .parser(Parser.xmlParser())
                    .get();

            StringBuilder news = new StringBuilder("=== ESPN MLB/FANTASY NEWS (Last 2 Days) ===\n\n");

            Elements items = doc.select("item");
            int count = 0;
            for (Element item : items) {
                if (count >= 12) break;

                String pubDate = item.select("pubDate").text();
                if (!isRecentRssDate(pubDate)) continue;

                String title = item.select("title").text();
                String description = item.select("description").text();
                String creator = item.select("dc|creator, creator").text();

                if (!title.isBlank()) {
                    news.append("- **").append(title.trim()).append("**\n");
                    if (!description.isBlank()) {
                        String shortDesc = description.length() > 300 ? description.substring(0, 300) + "..." : description;
                        news.append("  ").append(shortDesc.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            // Also try fantasy-specific RSS
            try {
                Document fantasyDoc = Jsoup.connect("https://www.espn.com/espn/rss/fantasy/news")
                        .userAgent(USER_AGENT)
                        .timeout(15000)
                        .parser(Parser.xmlParser())
                        .get();

                Elements fantasyItems = fantasyDoc.select("item");
                for (Element item : fantasyItems) {
                    if (count >= 15) break;

                    String pubDate = item.select("pubDate").text();
                    if (!isRecentRssDate(pubDate)) continue;

                    String title = item.select("title").text();
                    String description = item.select("description").text();

                    // Only include baseball-related
                    String lowerTitle = title.toLowerCase();
                    if (!lowerTitle.contains("baseball") && !lowerTitle.contains("mlb") &&
                        !lowerTitle.contains("pitcher") && !lowerTitle.contains("hitter")) {
                        continue;
                    }

                    if (!title.isBlank()) {
                        news.append("- **").append(title.trim()).append("** (Fantasy)\n");
                        if (!description.isBlank()) {
                            String shortDesc = description.length() > 300 ? description.substring(0, 300) + "..." : description;
                            news.append("  ").append(shortDesc.trim()).append("\n");
                        }
                        news.append("\n");
                        count++;
                    }
                }
            } catch (Exception ignored) {
                // Fantasy RSS might not exist
            }

            return count == 0 ? "No recent news (last 2 days) found on ESPN." : news.toString();
        } catch (IOException e) {
            return "Error fetching ESPN: " + e.getMessage();
        }
    }

    @Tool("Fetches MLB news from CBS Sports published in the last 2 days")
    public String fetchCbsSportsNews() {
        try {
            Document doc = Jsoup.connect("https://www.cbssports.com/rss/headlines/mlb/")
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .parser(Parser.xmlParser())
                    .get();

            StringBuilder news = new StringBuilder("=== CBS SPORTS MLB NEWS (Last 2 Days) ===\n\n");

            Elements items = doc.select("item");
            int count = 0;
            for (Element item : items) {
                if (count >= 12) break;

                String pubDate = item.select("pubDate").text();
                if (!isRecentRssDate(pubDate)) continue;

                String title = item.select("title").text();
                String description = item.select("description").text();
                description = Jsoup.parse(description).text();

                if (!title.isBlank()) {
                    news.append("- **").append(title.trim()).append("**\n");
                    if (!description.isBlank()) {
                        String shortDesc = description.length() > 300 ? description.substring(0, 300) + "..." : description;
                        news.append("  ").append(shortDesc.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            return count == 0 ? "No recent news (last 2 days) found on CBS Sports." : news.toString();
        } catch (IOException e) {
            return "Error fetching CBS Sports: " + e.getMessage();
        }
    }

    @Tool("Fetches official MLB news published in the last 2 days")
    public String fetchMlbNews() {
        try {
            Document doc = Jsoup.connect("https://www.mlb.com/feeds/news/rss.xml")
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .parser(Parser.xmlParser())
                    .get();

            StringBuilder news = new StringBuilder("=== MLB.COM OFFICIAL NEWS (Last 2 Days) ===\n\n");

            Elements items = doc.select("item");
            int count = 0;
            for (Element item : items) {
                if (count >= 12) break;

                String pubDate = item.select("pubDate").text();
                if (!isRecentRssDate(pubDate)) continue;

                String title = item.select("title").text();
                String description = item.select("description").text();
                description = Jsoup.parse(description).text();

                if (!title.isBlank()) {
                    news.append("- **").append(title.trim()).append("**\n");
                    if (!description.isBlank()) {
                        String shortDesc = description.length() > 300 ? description.substring(0, 300) + "..." : description;
                        news.append("  ").append(shortDesc.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            return count == 0 ? "No recent news (last 2 days) found on MLB.com." : news.toString();
        } catch (IOException e) {
            return "Error fetching MLB.com: " + e.getMessage();
        }
    }

    @Tool("Fetches pitching analysis and rankings from Pitcher List published in the last 2 days")
    public String fetchPitcherListNews() {
        try {
            Document doc = Jsoup.connect("https://pitcherlist.com/feed/")
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .parser(Parser.xmlParser())
                    .get();

            StringBuilder news = new StringBuilder("=== PITCHER LIST NEWS (Last 2 Days) ===\n\n");

            Elements items = doc.select("item");
            int count = 0;
            for (Element item : items) {
                if (count >= 10) break;

                String pubDate = item.select("pubDate").text();
                if (!isRecentRssDate(pubDate)) continue;

                String title = item.select("title").text();
                String description = item.select("description").text();
                description = Jsoup.parse(description).text();

                if (!title.isBlank()) {
                    news.append("- **").append(title.trim()).append("**\n");
                    if (!description.isBlank()) {
                        String shortDesc = description.length() > 350 ? description.substring(0, 350) + "..." : description;
                        news.append("  ").append(shortDesc.trim()).append("\n");
                    }
                    news.append("\n");
                    count++;
                }
            }

            return count == 0 ? "No recent news (last 2 days) found on Pitcher List." : news.toString();
        } catch (IOException e) {
            return "Error fetching Pitcher List: " + e.getMessage();
        }
    }

    @Tool("Fetches recent MLB transactions (trades, signings, DFA, waivers) from the last 2 days via MLB Stats API")
    public String fetchMlbTransactions() {
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(MAX_AGE_DAYS);
            String apiUrl = String.format(
                "https://statsapi.mlb.com/api/v1/transactions?startDate=%s&endDate=%s",
                startDate.format(DateTimeFormatter.ISO_DATE),
                endDate.format(DateTimeFormatter.ISO_DATE)
            );

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
            JsonArray transactions = json.getAsJsonArray("transactions");

            StringBuilder news = new StringBuilder("=== MLB TRANSACTIONS (Last 2 Days) ===\n\n");

            int count = 0;
            for (JsonElement el : transactions) {
                if (count >= 20) break;

                JsonObject tx = el.getAsJsonObject();
                String date = tx.has("date") ? tx.get("date").getAsString() : "";
                String typeDesc = tx.has("typeDesc") ? tx.get("typeDesc").getAsString() : "";
                String description = tx.has("description") ? tx.get("description").getAsString() : "";

                // Get player info if available
                String playerName = "";
                if (tx.has("player") && tx.get("player").isJsonObject()) {
                    JsonObject player = tx.getAsJsonObject("player");
                    playerName = player.has("fullName") ? player.get("fullName").getAsString() : "";
                }

                // Get team info
                String teamName = "";
                if (tx.has("toTeam") && tx.get("toTeam").isJsonObject()) {
                    JsonObject team = tx.getAsJsonObject("toTeam");
                    teamName = team.has("name") ? team.get("name").getAsString() : "";
                } else if (tx.has("fromTeam") && tx.get("fromTeam").isJsonObject()) {
                    JsonObject team = tx.getAsJsonObject("fromTeam");
                    teamName = team.has("name") ? team.get("name").getAsString() : "";
                }

                if (!description.isBlank()) {
                    news.append("**").append(date).append("** - ");
                    if (!playerName.isBlank()) {
                        news.append("**").append(playerName).append("**");
                        if (!teamName.isBlank()) {
                            news.append(" (").append(teamName).append(")");
                        }
                        news.append(": ");
                    }
                    news.append(typeDesc).append("\n");
                    news.append("  ").append(description).append("\n\n");
                    count++;
                }
            }

            conn.disconnect();
            return count == 0 ? "No transactions found in the last 2 days." : news.toString();
        } catch (Exception e) {
            return "Error fetching MLB transactions: " + e.getMessage();
        }
    }

    @Tool("Fetches current MLB injury list (IL) and player status updates via MLB Stats API")
    public String fetchMlbInjuries() {
        try {
            // The injuries endpoint doesn't require date filtering - it returns current injuries
            String apiUrl = "https://statsapi.mlb.com/api/v1/injuries";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();

            StringBuilder news = new StringBuilder("=== MLB CURRENT INJURIES ===\n\n");

            // Check if there's an injuries array or if we need to iterate teams
            if (json.has("injuries") && json.get("injuries").isJsonArray()) {
                JsonArray injuries = json.getAsJsonArray("injuries");

                int count = 0;
                for (JsonElement el : injuries) {
                    if (count >= 30) break;

                    JsonObject injury = el.getAsJsonObject();

                    String playerName = "";
                    String position = "";
                    if (injury.has("player") && injury.get("player").isJsonObject()) {
                        JsonObject player = injury.getAsJsonObject("player");
                        playerName = player.has("fullName") ? player.get("fullName").getAsString() : "";
                        if (player.has("primaryPosition") && player.get("primaryPosition").isJsonObject()) {
                            position = player.getAsJsonObject("primaryPosition").has("abbreviation")
                                ? player.getAsJsonObject("primaryPosition").get("abbreviation").getAsString() : "";
                        }
                    }

                    String teamName = "";
                    if (injury.has("team") && injury.get("team").isJsonObject()) {
                        JsonObject team = injury.getAsJsonObject("team");
                        teamName = team.has("name") ? team.get("name").getAsString() : "";
                    }

                    String injuryDesc = injury.has("description") ? injury.get("description").getAsString() : "";
                    String status = injury.has("status") ? injury.get("status").getAsString() : "";
                    String updateDate = injury.has("date") ? injury.get("date").getAsString() : "";

                    if (!playerName.isBlank()) {
                        news.append("**").append(playerName).append("**");
                        if (!position.isBlank()) {
                            news.append(" (").append(position).append(")");
                        }
                        if (!teamName.isBlank()) {
                            news.append(" - ").append(teamName);
                        }
                        news.append("\n");
                        if (!injuryDesc.isBlank()) {
                            news.append("  Injury: ").append(injuryDesc).append("\n");
                        }
                        if (!status.isBlank()) {
                            news.append("  Status: ").append(status).append("\n");
                        }
                        if (!updateDate.isBlank()) {
                            news.append("  Updated: ").append(updateDate).append("\n");
                        }
                        news.append("\n");
                        count++;
                    }
                }

                conn.disconnect();
                return count == 0 ? "No injury data available." : news.toString();
            } else {
                conn.disconnect();
                return "Injury data format not recognized.";
            }
        } catch (Exception e) {
            return "Error fetching MLB injuries: " + e.getMessage();
        }
    }

    @Tool("Fetches 40-man roster moves and status changes for fantasy-relevant players")
    public String fetchRosterMoves() {
        // Combine transactions filtered to roster-relevant types
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(MAX_AGE_DAYS);
            String apiUrl = String.format(
                "https://statsapi.mlb.com/api/v1/transactions?startDate=%s&endDate=%s",
                startDate.format(DateTimeFormatter.ISO_DATE),
                endDate.format(DateTimeFormatter.ISO_DATE)
            );

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
            JsonArray transactions = json.getAsJsonArray("transactions");

            StringBuilder news = new StringBuilder("=== MLB ROSTER MOVES (Last 2 Days) ===\n\n");
            news.append("**Call-Ups & Options:**\n");

            int callUpCount = 0;
            int ilCount = 0;

            StringBuilder callUps = new StringBuilder();
            StringBuilder ilMoves = new StringBuilder();

            for (JsonElement el : transactions) {
                JsonObject tx = el.getAsJsonObject();
                String typeCode = tx.has("typeCode") ? tx.get("typeCode").getAsString() : "";
                String typeDesc = tx.has("typeDesc") ? tx.get("typeDesc").getAsString() : "";
                String description = tx.has("description") ? tx.get("description").getAsString() : "";
                String date = tx.has("date") ? tx.get("date").getAsString() : "";

                String playerName = "";
                if (tx.has("player") && tx.get("player").isJsonObject()) {
                    JsonObject player = tx.getAsJsonObject("player");
                    playerName = player.has("fullName") ? player.get("fullName").getAsString() : "";
                }

                String teamName = "";
                if (tx.has("toTeam") && tx.get("toTeam").isJsonObject()) {
                    JsonObject team = tx.getAsJsonObject("toTeam");
                    teamName = team.has("name") ? team.get("name").getAsString() : "";
                }

                String lowerDesc = description.toLowerCase();

                // Call-ups and options
                if (lowerDesc.contains("recalled") || lowerDesc.contains("selected") ||
                    lowerDesc.contains("optioned") || lowerDesc.contains("outrighted") ||
                    lowerDesc.contains("called up")) {
                    if (callUpCount < 15 && !playerName.isBlank()) {
                        callUps.append("- **").append(playerName).append("**");
                        if (!teamName.isBlank()) callUps.append(" (").append(teamName).append(")");
                        callUps.append(": ").append(typeDesc).append(" - ").append(date).append("\n");
                        callUpCount++;
                    }
                }

                // IL moves
                if (lowerDesc.contains("injured list") || lowerDesc.contains(" il ") ||
                    lowerDesc.contains("disabled") || typeCode.contains("IL")) {
                    if (ilCount < 15 && !playerName.isBlank()) {
                        ilMoves.append("- **").append(playerName).append("**");
                        if (!teamName.isBlank()) ilMoves.append(" (").append(teamName).append(")");
                        ilMoves.append(": ").append(typeDesc).append(" - ").append(date).append("\n");
                        ilCount++;
                    }
                }
            }

            if (callUpCount > 0) {
                news.append(callUps);
            } else {
                news.append("No call-ups or options in the last 2 days.\n");
            }

            news.append("\n**IL Moves:**\n");
            if (ilCount > 0) {
                news.append(ilMoves);
            } else {
                news.append("No IL moves in the last 2 days.\n");
            }

            conn.disconnect();
            return news.toString();
        } catch (Exception e) {
            return "Error fetching roster moves: " + e.getMessage();
        }
    }

    private static final String SUMMARY_CACHE_DIR = TRANSCRIPT_DIR + "/summaries";
    private static final int PODCAST_LOOKBACK_DAYS = 1;

    /**
     * Parses the release date from a transcript filename.
     * Expected format: YYYYMMDD-... (e.g., "20260327-Episode Title.txt")
     * Returns null if the date cannot be parsed.
     */
    private LocalDate parseReleaseDateFromFilename(String filename) {
        if (filename == null || filename.length() < 8) {
            return null;
        }
        try {
            String dateStr = filename.substring(0, 8);
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (Exception e) {
            return null;
        }
    }

    @Tool("Fetches podcast transcript summaries for episodes released in the last 2 days. Returns cached summaries only.")
    public String fetchAllTranscriptSummaries() {
        try {
            Path transcriptPath = Paths.get(TRANSCRIPT_DIR);
            if (!Files.exists(transcriptPath)) {
                return "Transcript directory not found: " + TRANSCRIPT_DIR;
            }

            // Ensure cache directory exists
            Path cacheDir = Paths.get(SUMMARY_CACHE_DIR);
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            StringBuilder result = new StringBuilder("=== PODCAST TRANSCRIPT SUMMARIES (Episodes from Last 3 Days) ===\n\n");
            LocalDate cutoffDate = LocalDate.now().minusDays(PODCAST_LOOKBACK_DAYS);
            int totalCount = 0;
            int cachedCount = 0;
            java.util.List<String> uncachedFiles = new java.util.ArrayList<>();

            try (var dirStream = Files.newDirectoryStream(transcriptPath, "*.txt")) {
                for (Path file : dirStream) {
                    String filename = file.getFileName().toString();
                    LocalDate releaseDate = parseReleaseDateFromFilename(filename);

                    // Skip files without parseable dates or older than cutoff
                    if (releaseDate == null || releaseDate.isBefore(cutoffDate)) {
                        continue;
                    }

                    totalCount++;
                    Path summaryPath = Paths.get(SUMMARY_CACHE_DIR, filename + ".summary");

                    if (Files.exists(summaryPath)) {
                        cachedCount++;
                        String summary = Files.readString(summaryPath);
                        result.append("### ").append(filename).append("\n");
                        result.append(summary).append("\n\n");
                    } else {
                        uncachedFiles.add(filename);
                    }
                }
            }

            if (totalCount == 0) {
                return "No podcast episodes found from the last " + PODCAST_LOOKBACK_DAYS + " days.";
            }

            // Add header with counts
            String header = "Found " + totalCount + " episodes released in the last " + PODCAST_LOOKBACK_DAYS + " days. " + cachedCount + " have summaries.\n\n";
            result.insert(result.indexOf("\n\n") + 2, header);

            // Note uncached count (don't list all files - too verbose)
            if (!uncachedFiles.isEmpty()) {
                result.append("\n[Note: ").append(uncachedFiles.size())
                      .append(" additional episodes do not have summaries.]\n");
            }

            return result.toString();
        } catch (Exception e) {
            return "Error fetching transcript summaries: " + e.getMessage();
        }
    }

    @Tool("Fetches all fantasy baseball news from all sources, filtered to last 2 days")
    public String fetchAllRecentNews() {
        StringBuilder allNews = new StringBuilder();
        allNews.append("Fetching news from last 2 days from all sources...\n\n");
        allNews.append(fetchEspnNews()).append("\n");
        allNews.append(fetchCbsSportsNews()).append("\n");
        allNews.append(fetchMlbNews()).append("\n");
        allNews.append(fetchNbcSportsNews()).append("\n");
        allNews.append(fetchFangraphsNews()).append("\n");
        allNews.append(fetchPitcherListNews()).append("\n");
        allNews.append(fetchBaseballAmericaNews()).append("\n");
        allNews.append(fetchMlbTransactions()).append("\n");
        allNews.append(fetchRosterMoves()).append("\n");
        allNews.append(fetchMlbInjuries()).append("\n");
        return allNews.toString();
    }

    @Tool("Fetches fantasy baseball news from fast sources only (RSS feeds + MLB API, no browser scraping)")
    public String fetchQuickNews() {
        StringBuilder allNews = new StringBuilder();
        allNews.append("Fetching news from fast sources (RSS + API)...\n\n");
        allNews.append(fetchEspnNews()).append("\n");
        allNews.append(fetchCbsSportsNews()).append("\n");
        allNews.append(fetchMlbNews()).append("\n");
        allNews.append(fetchFangraphsNews()).append("\n");
        allNews.append(fetchPitcherListNews()).append("\n");
        allNews.append(fetchMlbTransactions()).append("\n");
        allNews.append(fetchRosterMoves()).append("\n");
        allNews.append(fetchMlbInjuries()).append("\n");
        return allNews.toString();
    }
}
