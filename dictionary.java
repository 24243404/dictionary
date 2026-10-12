import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.TreeMap;

public class dictionary {
    private static final Path DATA_FILE = Paths.get("dictionary-data.properties");
    private static final Path HTML_FILE = Paths.get("dictionary.html");
    private static final Path CSS_FILE = Paths.get("dictionary.css");
    private static final Scanner INPUT = new Scanner(System.in);
    private static final TreeMap<String, String> entries = new TreeMap<String, String>();

    public static void main(String[] args) {
        if (args.length > 0 && "--console".equals(args[0])) {
            runConsole();
            return;
        }

        try {
            loadEntries();
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
            startWebServer(port);
        } catch (NumberFormatException e) {
            System.err.println("Port must be a number between 1 and 65535.");
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        } catch (IOException e) {
            System.err.println("Could not start the dictionary: " + e.getMessage());
        }
    }

    private static void startWebServer(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/entries", dictionary::handleEntries);
        server.createContext("/", dictionary::handlePage);
        server.start();
        System.out.println("Dictionary is running at http://127.0.0.1:" + port);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void handlePage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Path file;
        String contentType;
        if ("/".equals(path) || "/dictionary.html".equals(path)) {
            file = HTML_FILE;
            contentType = "text/html; charset=utf-8";
        } else if ("/dictionary.css".equals(path)) {
            file = CSS_FILE;
            contentType = "text/css; charset=utf-8";
        } else {
            sendText(exchange, 404, "Page not found.");
            return;
        }

        if (!Files.isRegularFile(file)) {
            sendText(exchange, 500, file.getFileName() + " was not found. Start the app from its project folder.");
            return;
        }

        byte[] page = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(page);
        }
    }

    private static void handleEntries(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                sendJson(exchange, 200, entriesAsJson());
            } else if ("POST".equals(method)) {
                addEntry(exchange);
            } else if ("DELETE".equals(method)) {
                deleteEntry(exchange);
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                sendText(exchange, 405, "Method not allowed.");
            }
        } catch (IllegalArgumentException e) {
            sendText(exchange, 400, e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void addEntry(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange.getRequestBody());
        String word = normalizeWord(form.get("word"));
        String definition = form.get("definition") == null ? "" : form.get("definition").trim();
        if (word.isEmpty() || definition.isEmpty()) {
            throw new IllegalArgumentException("Both word and definition are required.");
        }
        if (word.length() > 100 || definition.length() > 2000) {
            throw new IllegalArgumentException("Word must be at most 100 characters and definition at most 2000.");
        }

        TreeMap<String, String> updatedEntries = new TreeMap<String, String>(entries);
        updatedEntries.put(word, definition);
        if (!saveAndReplace(updatedEntries)) {
            sendText(exchange, 500, "Could not save the dictionary entry.");
            return;
        }

        sendJson(exchange, 200, "{\"word\":\"" + jsonEscape(word)
                + "\",\"definition\":\"" + jsonEscape(definition) + "\"}");
    }

    private static void deleteEntry(HttpExchange exchange) throws IOException {
        String word = normalizeWord(parseForm(exchange.getRequestURI().getRawQuery()).get("word"));
        if (word.isEmpty()) {
            throw new IllegalArgumentException("A word is required.");
        }
        if (!entries.containsKey(word)) {
            sendText(exchange, 404, "Word not found.");
            return;
        }

        TreeMap<String, String> updatedEntries = new TreeMap<String, String>(entries);
        updatedEntries.remove(word);
        if (!saveAndReplace(updatedEntries)) {
            sendText(exchange, 500, "Could not save the dictionary change.");
            return;
        }
        sendText(exchange, 200, "Word removed.");
    }

    private static Map<String, String> readForm(InputStream input) throws IOException {
        byte[] body = input.readAllBytes();
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseForm(String encodedForm) {
        Map<String, String> values = new HashMap<String, String>();
        if (encodedForm == null || encodedForm.isEmpty()) {
            return values;
        }

        try {
            for (String pair : encodedForm.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                values.put(decodeFormValue(key), decodeFormValue(value));
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Request contains invalid URL encoding.");
        }
        return values;
    }

    private static String decodeFormValue(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("UTF-8 is not available.", e);
        }
    }

    private static String entriesAsJson() {
        StringBuilder json = new StringBuilder("[");
        synchronized (entries) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append("{\"word\":\"").append(jsonEscape(entry.getKey()))
                        .append("\",\"definition\":\"").append(jsonEscape(entry.getValue()))
                        .append("\"}");
            }
        }
        return json.append(']').toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        sendResponse(exchange, status, body);
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        sendResponse(exchange, status, body);
    }

    private static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static void runConsole() {
        try {
            loadEntries();
        } catch (IOException e) {
            System.err.println("Could not load the dictionary: " + e.getMessage());
            return;
        }

        System.out.println("Welcome to your Dictionary!");
        boolean running = true;
        while (running) {
            printMenu();
            String choice = readLine("Choose an option: ").trim();
            switch (choice) {
                case "1":
                    searchWord();
                    break;
                case "2":
                    addOrUpdateWord();
                    break;
                case "3":
                    removeWord();
                    break;
                case "4":
                    listWords();
                    break;
                case "5":
                    running = false;
                    System.out.println("Goodbye!");
                    break;
                default:
                    System.out.println("Please choose a number from 1 to 5.");
            }
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("1. Search for a word");
        System.out.println("2. Add or update a word");
        System.out.println("3. Remove a word");
        System.out.println("4. List all words");
        System.out.println("5. Exit");
    }

    private static void loadEntries() throws IOException {
        if (Files.exists(DATA_FILE)) {
            Properties savedEntries = new Properties();
            try (Reader reader = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
                savedEntries.load(reader);
            }
            for (String word : savedEntries.stringPropertyNames()) {
                entries.put(normalizeWord(word), savedEntries.getProperty(word));
            }
            return;
        }

        entries.put("computer", "An electronic device that processes and stores data.");
        entries.put("dictionary", "A reference book or resource that explains words and their meanings.");
        entries.put("java", "A programming language used to build applications for many platforms.");
        saveEntries(entries);
    }

    private static void searchWord() {
        String word = normalizeWord(readLine("Enter a word to search for: "));
        String definition = entries.get(word);
        if (definition == null) {
            System.out.println("The word \"" + word + "\" is not in the dictionary.");
        } else {
            System.out.println(word + ": " + definition);
        }
    }

    private static void addOrUpdateWord() {
        String word = normalizeWord(readLine("Enter the word: "));
        if (word.isEmpty()) {
            System.out.println("A word cannot be empty.");
            return;
        }
        String definition = readLine("Enter its definition: ").trim();
        if (definition.isEmpty()) {
            System.out.println("A definition cannot be empty.");
            return;
        }

        TreeMap<String, String> updatedEntries = new TreeMap<String, String>(entries);
        boolean alreadyExists = updatedEntries.containsKey(word);
        updatedEntries.put(word, definition);
        if (saveAndReplace(updatedEntries)) {
            System.out.println(alreadyExists ? "Definition updated." : "Word added.");
        }
    }

    private static void removeWord() {
        String word = normalizeWord(readLine("Enter the word to remove: "));
        if (!entries.containsKey(word)) {
            System.out.println("The word \"" + word + "\" is not in the dictionary.");
            return;
        }
        TreeMap<String, String> updatedEntries = new TreeMap<String, String>(entries);
        updatedEntries.remove(word);
        if (saveAndReplace(updatedEntries)) {
            System.out.println("Word removed.");
        }
    }

    private static void listWords() {
        if (entries.isEmpty()) {
            System.out.println("The dictionary is empty.");
            return;
        }
        System.out.println("Words in the dictionary:");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            System.out.println("- " + entry.getKey() + ": " + entry.getValue());
        }
    }

    private static boolean saveAndReplace(TreeMap<String, String> updatedEntries) {
        synchronized (entries) {
            try {
                saveEntries(updatedEntries);
                entries.clear();
                entries.putAll(updatedEntries);
                return true;
            } catch (IOException e) {
                System.err.println("Could not save the change: " + e.getMessage());
                return false;
            }
        }
    }

    private static void saveEntries(TreeMap<String, String> words) throws IOException {
        Properties savedEntries = new Properties();
        for (Map.Entry<String, String> entry : words.entrySet()) {
            savedEntries.setProperty(entry.getKey(), entry.getValue());
        }
        try (Writer writer = Files.newBufferedWriter(DATA_FILE, StandardCharsets.UTF_8)) {
            savedEntries.store(writer, "Dictionary words and definitions");
        }
    }

    private static String normalizeWord(String word) {
        return word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
    }

    private static String readLine(String prompt) {
        System.out.print(prompt);
        return INPUT.nextLine();
    }
}
