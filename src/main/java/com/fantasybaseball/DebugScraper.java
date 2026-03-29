package com.fantasybaseball;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

public class DebugScraper {
    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://www.nbcsports.com/fantasy/baseball/player-news";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();
            page.setDefaultTimeout(45000);

            System.out.println("Fetching: " + url);
            page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // Wait for AJAX content to load
            page.waitForTimeout(8000);

            // Get full HTML and search for patterns
            String html = page.content();

            // Look for specific player news card patterns
            var cards = page.querySelectorAll("[class*='PlayerNewsPost'], [class*='Card'], [class*='newsPost']");
            System.out.println("\n=== FOUND " + cards.size() + " CARD ELEMENTS ===");

            for (int i = 0; i < Math.min(cards.size(), 5); i++) {
                var card = cards.get(i);
                System.out.println("\n--- Card " + (i + 1) + " ---");
                System.out.println("Class: " + card.getAttribute("class"));
                String inner = card.innerHTML();
                if (inner.length() > 800) inner = inner.substring(0, 800);
                System.out.println("Inner HTML: " + inner);
            }

            // Try finding by looking at the structure
            var allDivs = page.querySelectorAll("div[class]");
            System.out.println("\n=== UNIQUE CLASS PATTERNS WITH 'news' or 'player' (first 30) ===");
            java.util.Set<String> seen = new java.util.HashSet<>();
            int count = 0;
            for (var div : allDivs) {
                String cls = div.getAttribute("class");
                if (cls != null && (cls.toLowerCase().contains("news") || cls.toLowerCase().contains("player"))) {
                    if (!seen.contains(cls) && count < 30) {
                        seen.add(cls);
                        System.out.println("  " + cls);
                        count++;
                    }
                }
            }

            browser.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
