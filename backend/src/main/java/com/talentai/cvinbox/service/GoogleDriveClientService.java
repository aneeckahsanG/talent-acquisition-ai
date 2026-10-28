package com.talentai.cvinbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads CVs from a Google Drive folder using a service account (a robot Google account the
 * folder is shared with), and files them into .CONSIDERED / .REJECTED sub-folders — the same
 * contract {@link ExternalSftpClientService} gives for an SFTP folder.
 *
 * Talks to the Drive v3 REST API directly. The service-account sign-in is the standard
 * JWT-bearer OAuth flow: sign a short-lived JWT with the account's private key and exchange it
 * for an access token. Done with the JDK alone, so no Google client libraries are needed.
 */
@Service
@Slf4j
public class GoogleDriveClientService {

    private static final String SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String GOOGLE_DOC_MIME = "application/vnd.google-apps.document";
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final String CONSIDERED_FOLDER = ".CONSIDERED";
    private static final String REJECTED_FOLDER = ".REJECTED";
    private static final Pattern FOLDER_URL_ID = Pattern.compile("/folders/([A-Za-z0-9_-]+)");
    private static final Pattern QUERY_ID = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");

    @Data
    @Builder
    public static class DriveCv {
        private String fileId;
        /** Always carries an extension that matches its content, so text extraction picks the right reader. */
        private String fileName;
        private byte[] content;
    }

    @Data
    @Builder
    public static class TestConnectionResult {
        private boolean success;
        private String message;
    }

    private record Credentials(String clientEmail, PrivateKey privateKey, String tokenUri) {}

    private record Token(String value, Instant expiresAt) {}

    private final ObjectMapper objectMapper;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public GoogleDriveClientService(ObjectMapper objectMapper,
                                    @Value("${app.cv-inbox.google-drive.api-base:https://www.googleapis.com/drive/v3}") String apiBase) {
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
    }

    // ── Settings helpers ──────────────────────────────────────────────────────

    /** Accepts a folder link or a bare folder ID and returns the ID. */
    public static String extractFolderId(String linkOrId) {
        if (linkOrId == null) return null;
        String s = linkOrId.trim();
        if (s.isEmpty()) return null;
        Matcher m = FOLDER_URL_ID.matcher(s);
        if (m.find()) return m.group(1);
        m = QUERY_ID.matcher(s);
        if (m.find()) return m.group(1);
        return s;
    }

    /** The address the Drive folder must be shared with, or null if the key JSON is not usable. */
    public String serviceAccountEmail(String keyJson) {
        try {
            return parseCredentials(keyJson).clientEmail();
        } catch (Exception e) {
            return null;
        }
    }

    /** @throws IllegalArgumentException with a message fit to show the user if the key is unusable. */
    public void validateKey(String keyJson) {
        try {
            parseCredentials(keyJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    public TestConnectionResult testConnection(String keyJson, String folderId) {
        Credentials creds;
        try {
            creds = parseCredentials(keyJson);
        } catch (Exception e) {
            return TestConnectionResult.builder().success(false).message(e.getMessage()).build();
        }
        if (folderId == null || folderId.isBlank()) {
            return TestConnectionResult.builder().success(false).message("Enter the Drive folder link or ID.").build();
        }
        try {
            String token = accessToken(creds);
            JsonNode folder = getJson(token, "/files/" + folderId + "?fields=id,name,mimeType&supportsAllDrives=true");
            if (!FOLDER_MIME.equals(folder.path("mimeType").asText())) {
                return TestConnectionResult.builder().success(false)
                        .message("That ID is a file, not a folder. Use the folder's link or ID.").build();
            }
            int waiting = (int) listFolderFiles(token, folderId).stream().filter(GoogleDriveClientService::isProcessable).count();
            return TestConnectionResult.builder().success(true)
                    .message("Connected to folder '" + folder.path("name").asText() + "' — "
                            + waiting + " CV file" + (waiting == 1 ? "" : "s") + " waiting.").build();
        } catch (DriveException e) {
            String hint = e.status == 404
                    ? "Folder not found, or it is not shared with " + creds.clientEmail() + " (share it as Editor)."
                    : e.getMessage();
            return TestConnectionResult.builder().success(false).message(hint).build();
        } catch (Exception e) {
            return TestConnectionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /** Lists the CV files sitting directly in the folder and downloads each one. Sub-folders, other
     *  file types and files over the size cap are left untouched. */
    public List<DriveCv> listAndFetch(String keyJson, String folderId) throws Exception {
        String token = accessToken(parseCredentials(keyJson));
        List<DriveCv> out = new ArrayList<>();
        for (JsonNode f : listFolderFiles(token, folderId)) {
            String id = f.path("id").asText();
            String name = f.path("name").asText();
            String mime = f.path("mimeType").asText();
            if (!isSupported(f)) {
                log.info("Drive CV inbox: skipping unsupported file '{}' ({})", name, mime);
                continue;
            }
            if (!isProcessable(f)) {
                log.warn("Drive CV inbox: skipping '{}' — larger than {} MB", name, MAX_FILE_BYTES / (1024 * 1024));
                continue;
            }
            byte[] content = GOOGLE_DOC_MIME.equals(mime)
                    ? getBytes(token, "/files/" + id + "/export?mimeType=text/plain")
                    : getBytes(token, "/files/" + id + "?alt=media&supportsAllDrives=true");
            out.add(DriveCv.builder().fileId(id).fileName(withExtension(name, mime)).content(content).build());
        }
        return out;
    }

    /** Moves a processed file into .CONSIDERED or .REJECTED inside the inbox folder, creating it if needed.
     *  Best effort: a failure is logged, not thrown, so one stuck file does not stop the batch. */
    public void moveFile(String keyJson, String folderId, String fileId, boolean considered) {
        try {
            String token = accessToken(parseCredentials(keyJson));
            String targetId = findOrCreateSubfolder(token, folderId, considered ? CONSIDERED_FOLDER : REJECTED_FOLDER);
            send(HttpRequest.newBuilder(URI.create(apiBase + "/files/" + fileId
                            + "?addParents=" + targetId + "&removeParents=" + folderId + "&supportsAllDrives=true"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method("PATCH", BodyPublishers.ofString("{}"))
                    .timeout(Duration.ofSeconds(30)).build());
        } catch (Exception e) {
            log.error("Failed to move Drive file {} into the {} folder", fileId,
                    considered ? CONSIDERED_FOLDER : REJECTED_FOLDER, e);
        }
    }

    // ── Drive REST ────────────────────────────────────────────────────────────

    private List<JsonNode> listFolderFiles(String token, String folderId) throws IOException, InterruptedException {
        String q = "'" + folderId + "' in parents and trashed = false and mimeType != '" + FOLDER_MIME + "'";
        List<JsonNode> files = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/files?q=" + enc(q) + "&fields=" + enc("nextPageToken,files(id,name,mimeType,size)")
                    + "&pageSize=100&orderBy=createdTime&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
            JsonNode page = getJson(token, path);
            page.path("files").forEach(files::add);
            pageToken = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
        } while (pageToken != null);
        return files;
    }

    private String findOrCreateSubfolder(String token, String parentId, String name) throws IOException, InterruptedException {
        String q = "'" + parentId + "' in parents and name = '" + name + "' and mimeType = '" + FOLDER_MIME + "' and trashed = false";
        JsonNode found = getJson(token, "/files?q=" + enc(q) + "&fields=" + enc("files(id)") + "&supportsAllDrives=true&includeItemsFromAllDrives=true");
        if (found.path("files").size() > 0) return found.path("files").get(0).path("id").asText();

        String body = objectMapper.writeValueAsString(Map.of("name", name, "mimeType", FOLDER_MIME, "parents", List.of(parentId)));
        HttpResponse<byte[]> created = send(HttpRequest.newBuilder(URI.create(apiBase + "/files?supportsAllDrives=true"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30)).build());
        return objectMapper.readTree(created.body()).path("id").asText();
    }

    private JsonNode getJson(String token, String path) throws IOException, InterruptedException {
        return objectMapper.readTree(getBytes(token, path));
    }

    private byte[] getBytes(String token, String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(60)).build()).body();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new DriveException(response.statusCode(), describe(response.statusCode(), response.body()));
        }
        return response;
    }

    private String describe(int status, byte[] body) {
        try {
            JsonNode n = objectMapper.readTree(body);
            String msg = n.path("error").path("message").asText("");
            if (msg.isEmpty()) msg = n.path("error_description").asText("");
            if (msg.isEmpty() && n.path("error").isTextual()) msg = n.path("error").asText();
            if (!msg.isEmpty()) return "Google Drive said: " + msg + " (HTTP " + status + ")";
        } catch (Exception ignored) {
            // not JSON — fall through to the generic message
        }
        return "Google Drive request failed (HTTP " + status + ")";
    }

    private static class DriveException extends IOException {
        final int status;

        DriveException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ── Service-account sign-in ───────────────────────────────────────────────

    private String accessToken(Credentials creds) throws Exception {
        Token cached = tokens.get(creds.clientEmail());
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cached.value();

        long now = Instant.now().getEpochSecond();
        String header = b64(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = b64(objectMapper.writeValueAsBytes(Map.of(
                "iss", creds.clientEmail(), "scope", SCOPE, "aud", creds.tokenUri(),
                "iat", now, "exp", now + 3600)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(creds.privateKey());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String assertion = header + "." + claims + "." + b64(signer.sign());

        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + enc(assertion);
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(creds.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(30)).build());
        JsonNode body = objectMapper.readTree(response.body());
        Token token = new Token(body.path("access_token").asText(),
                Instant.now().plusSeconds(body.path("expires_in").asLong(3600)));
        tokens.put(creds.clientEmail(), token);
        return token.value();
    }

    private Credentials parseCredentials(String keyJson) throws Exception {
        if (keyJson == null || keyJson.isBlank()) {
            throw new IllegalArgumentException("Upload the service account key (JSON) first.");
        }
        JsonNode n;
        try {
            n = objectMapper.readTree(keyJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("That file is not valid JSON. Use the key file downloaded from Google Cloud.");
        }
        String email = n.path("client_email").asText("");
        String pem = n.path("private_key").asText("");
        if (email.isEmpty() || pem.isEmpty()) {
            throw new IllegalArgumentException("That JSON is not a service account key (missing client_email or private_key).");
        }
        try {
            byte[] der = Base64.getDecoder().decode(pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", ""));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            return new Credentials(email, key, n.path("token_uri").asText(DEFAULT_TOKEN_URI));
        } catch (Exception e) {
            throw new IllegalArgumentException("The private key in that JSON could not be read.");
        }
    }

    // ── File handling ─────────────────────────────────────────────────────────

    /** A file the inbox will actually read: a supported type, within the size cap. */
    private static boolean isProcessable(JsonNode f) {
        return isSupported(f) && f.path("size").asLong(0) <= MAX_FILE_BYTES;
    }

    private static boolean isSupported(JsonNode f) {
        return isSupported(f.path("name").asText(""), f.path("mimeType").asText(""));
    }

    private static boolean isSupported(String name, String mime) {
        String n = name.toLowerCase();
        return GOOGLE_DOC_MIME.equals(mime) || PDF_MIME.equals(mime) || DOCX_MIME.equals(mime)
                || mime.startsWith("text/")
                || n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".txt") || n.endsWith(".md");
    }

    private static String withExtension(String name, String mime) {
        String n = name.toLowerCase();
        if (GOOGLE_DOC_MIME.equals(mime) && !n.endsWith(".txt")) return name + ".txt";
        if (PDF_MIME.equals(mime) && !n.endsWith(".pdf")) return name + ".pdf";
        if (DOCX_MIME.equals(mime) && !n.endsWith(".docx")) return name + ".docx";
        return name;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
