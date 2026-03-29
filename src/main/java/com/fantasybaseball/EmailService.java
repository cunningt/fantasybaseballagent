package com.fantasybaseball;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Service for saving reports to files and sending via email.
 */
public class EmailService {

    private final Properties config;
    private final String outputDirectory;

    public EmailService() throws IOException {
        this.config = loadConfig();
        this.outputDirectory = config.getProperty("output.directory", "./output");

        // Ensure output directory exists
        Files.createDirectories(Paths.get(outputDirectory));
    }

    private Properties loadConfig() throws IOException {
        Properties props = new Properties();
        Path configPath = Paths.get("email.properties");

        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
            }
        } else {
            System.err.println("Warning: email.properties not found. Email functionality disabled.");
        }
        return props;
    }

    /**
     * Saves the report to a text file and returns the file path.
     */
    public String saveToFile(String content) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        String filename = String.format("fantasy-baseball-news_%s.txt", timestamp);
        Path filePath = Paths.get(outputDirectory, filename);

        Files.writeString(filePath, content);
        System.out.println("Report saved to: " + filePath.toAbsolutePath());

        return filePath.toString();
    }

    /**
     * Converts markdown content to styled HTML email.
     */
    public String convertToHtmlEmail(String markdownContent) {
        // Parse markdown with table support
        List<Extension> extensions = List.of(TablesExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(extensions).build();

        Node document = parser.parse(markdownContent);
        String htmlBody = renderer.render(document);

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"));

        // Use StringBuilder to avoid format string issues with % in content
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 800px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f5f5f5;
                    }
                    .container {
                        background: white;
                        border-radius: 12px;
                        padding: 30px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    .header {
                        background: linear-gradient(135deg, #e8f5e9 0%, #c8e6c9 100%);
                        padding: 25px;
                        border-radius: 12px 12px 0 0;
                        margin: -30px -30px 25px -30px;
                        text-align: center;
                    }
                    .header h1 {
                        margin: 0;
                        font-size: 32px;
                        font-weight: 900;
                        font-style: italic;
                        color: #1a4ed8;
                        text-shadow:
                            -2px -2px 0 #fff,
                            2px -2px 0 #fff,
                            -2px 2px 0 #fff,
                            2px 2px 0 #fff,
                            3px 3px 0 #333;
                        letter-spacing: 1px;
                    }
                    .header .subtitle {
                        color: #c41e3a;
                        font-size: 14px;
                        font-weight: 600;
                        font-style: italic;
                        margin-top: 8px;
                    }
                    h1 { color: #1e5128; font-size: 24px; margin-top: 30px; border-bottom: 2px solid #4e9f3d; padding-bottom: 10px; }
                    h2 { color: #2d6a4f; font-size: 20px; margin-top: 25px; }
                    h3 { color: #40916c; font-size: 17px; margin-top: 20px; }
                    p { margin: 12px 0; }
                    ul { padding-left: 20px; }
                    li { margin: 8px 0; }
                    strong { color: #1e5128; }
                    em { color: #555; }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin: 20px 0;
                        font-size: 14px;
                    }
                    th {
                        background: #1e5128;
                        color: white;
                        padding: 12px 15px;
                        text-align: left;
                        font-weight: 600;
                    }
                    td {
                        padding: 12px 15px;
                        border-bottom: 1px solid #ddd;
                    }
                    tr:nth-child(even) { background: #f8f9fa; }
                    tr:hover { background: #e8f5e9; }
                    .injury-alert {
                        background: #fff3cd;
                        border-left: 4px solid #ffc107;
                        padding: 15px;
                        margin: 15px 0;
                        border-radius: 0 8px 8px 0;
                    }
                    .callup-alert {
                        background: #d4edda;
                        border-left: 4px solid #28a745;
                        padding: 15px;
                        margin: 15px 0;
                        border-radius: 0 8px 8px 0;
                    }
                    .footer {
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 1px solid #ddd;
                        font-size: 12px;
                        color: #666;
                        text-align: center;
                    }
                    .badge {
                        display: inline-block;
                        padding: 3px 10px;
                        border-radius: 12px;
                        font-size: 12px;
                        font-weight: 600;
                        margin-right: 5px;
                    }
                    .badge-add { background: #28a745; color: white; }
                    .badge-drop { background: #dc3545; color: white; }
                    .badge-monitor { background: #ffc107; color: #333; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Scoresheet Newsy McAwesome</h1>
                        <div class="subtitle">""");
        html.append(dateStr);
        html.append("""
                        </div>
                    </div>
                    """);
        html.append(htmlBody);
        html.append("""
                    <div class="footer">
                        <p>Generated by Fantasy Baseball News Agent</p>
                        <p>Powered by LangChain4J + Qwen 3.5</p>
                    </div>
                </div>
            </body>
            </html>
            """);

        return html.toString();
    }

    /**
     * Sends the report via email to all configured recipients.
     */
    public void sendEmail(String markdownContent) {
        String recipients = config.getProperty("recipients", "");
        if (recipients.isBlank()) {
            System.err.println("No recipients configured in email.properties");
            return;
        }

        String host = config.getProperty("smtp.host", "");
        String username = config.getProperty("smtp.username", "");
        String password = config.getProperty("smtp.password", "");

        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            System.err.println("SMTP settings incomplete in email.properties");
            return;
        }

        // Setup mail session
        Properties mailProps = new Properties();
        mailProps.put("mail.smtp.host", host);
        mailProps.put("mail.smtp.port", config.getProperty("smtp.port", "587"));
        mailProps.put("mail.smtp.auth", config.getProperty("smtp.auth", "true"));
        mailProps.put("mail.smtp.starttls.enable", config.getProperty("smtp.starttls", "true"));

        Session session = Session.getInstance(mailProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            // Create message
            MimeMessage message = new MimeMessage(session);

            String fromName = config.getProperty("smtp.from.name", "Fantasy Baseball Agent");
            message.setFrom(new InternetAddress(username, fromName));

            // Add all recipients as BCC (hidden from each other)
            for (String recipient : recipients.split(",")) {
                recipient = recipient.trim();
                if (!recipient.isBlank()) {
                    message.addRecipient(Message.RecipientType.BCC, new InternetAddress(recipient));
                }
            }

            // Set subject with date
            String subjectPrefix = config.getProperty("subject.prefix", "[Fantasy Baseball]");
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
            message.setSubject(subjectPrefix + " Daily News - " + dateStr);

            // Create multipart message (HTML + plain text fallback)
            MimeMultipart multipart = new MimeMultipart("alternative");

            // Plain text version
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(markdownContent, "utf-8");
            multipart.addBodyPart(textPart);

            // HTML version
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(convertToHtmlEmail(markdownContent), "text/html; charset=utf-8");
            multipart.addBodyPart(htmlPart);

            message.setContent(multipart);

            // Send
            Transport.send(message);
            System.out.println("Email sent successfully to: " + recipients);

        } catch (Exception e) {
            System.err.println("Failed to send email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Saves to file and sends email.
     */
    public void saveAndSend(String content) throws IOException {
        saveToFile(content);
        sendEmail(content);
    }

    /**
     * Saves HTML version to file.
     */
    public String saveHtmlToFile(String markdownContent) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        String filename = String.format("fantasy-baseball-news_%s.html", timestamp);
        Path filePath = Paths.get(outputDirectory, filename);

        Files.writeString(filePath, convertToHtmlEmail(markdownContent));
        System.out.println("HTML report saved to: " + filePath.toAbsolutePath());

        return filePath.toString();
    }
}
