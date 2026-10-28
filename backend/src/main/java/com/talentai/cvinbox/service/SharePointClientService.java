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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads CVs from a SharePoint (or OneDrive for Business) folder through Microsoft Graph, using an Entra ID
 * app registration with a client secret, and files them into Considered / Rejected sub-folders — the same
 * contract {@link GoogleDriveClientService} gives for a Drive folder.
 *
 * The folder is given as the link a person copies from SharePoint. It is resolved to a drive and an item
 * once, then cached briefly, so a batch of files does not repeat the lookup for every file.
 *
 * The sub-folders are named "Considered" and "Rejected" (no leading dot, which SharePoint is stricter about
 * than Drive or SFTP).
 */
@Service
@Slf4j
public class SharePointClientService {

    private static final String SCOPE = "https://graph.microsoft.com/.default";
    private static final String CONSIDERED_FOLDER = "Considered";
    private static final String REJECTED_FOLDER = "Rejected";
    private static final Duration FOLDER_CACHE_TTL = Duration.ofMinutes(5);

    @Data
    @Builder
    public static class SharePointCv {
        private String itemId;
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

    /** What a pasted link says, before anything is looked up. */
    public record ParsedLink(String host, String sitePath, String serverRelativePath, boolean sharingLink, String original) {}

    private record ResolvedFolder(String driveId, String itemId, String name, Instant resolvedAt) {}

    private record Token(String value, Instant expiresAt) {}

    private final ObjectMapper objectMapper;
    private final String graphBase;
    private final String loginBase;
    /** Redirects are followed by hand so the Authorization header is never sent to the download host. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault())
            .build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, ResolvedFolder> folders = new ConcurrentHashMap<>();

    public SharePointClientService(ObjectMapper objectMapper,
                                   @Value("${app.cv-inbox.sharepoint.graph-base:https://graph.microsoft.com/v1.0}") String graphBase,
                                   @Value("${app.cv-inbox.sharepoint.login-base:https://login.microsoftonline.com}") String loginBase) {
        this.objectMapper = objectMapper;
        this.graphBase = graphBase;
        this.loginBase = loginBase;
    }

    // ── Link parsing ──────────────────────────────────────────────────────────

    /**
     * Understands the three shapes of link people copy: the plain folder address
     * (.../sites/HR/Shared Documents/CV Inbox), the address-bar form of a library view
     * (.../Forms/AllItems.aspx?id=%2Fsites%2FHR%2F...), and a "Copy link" sharing link (.../:f:/s/HR/Eab...).
     *
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public static ParsedLink parseLink(String link) {
        if (link == null || link.isBlank()) throw new IllegalArgumentException("Paste the SharePoint folder link.");
        URI uri;
        try {
            uri = new URI(link.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That does not look like a link. Copy the folder's address from SharePoint.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Use the full https:// link to the SharePoint folder.");
        }
        String host = uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();

        if (path.startsWith("/:")) return new ParsedLink(host, "", "", true, link.trim());

        String idParam = queryParam(uri.getRawQuery(), "id");
        if (idParam != null && idParam.startsWith("/")) {
            path = idParam;
        } else {
            int forms = path.indexOf("/Forms/");
            if (forms >= 0) path = path.substring(0, forms);
        }

        List<String> segments = new ArrayList<>();
        for (String s : path.split("/")) if (!s.isBlank()) segments.add(s);

        String sitePath = "";
        if (segments.size() >= 2 && (segments.get(0).equalsIgnoreCase("sites") || segments.get(0).equalsIgnoreCase("teams"))) {
            sitePath = "/" + segments.get(0) + "/" + segments.get(1);
        }
        int needed = sitePath.isEmpty() ? 1 : 3; // at least the document library
        if (segments.size() < needed) {
            throw new IllegalArgumentException("The link must point to a folder inside a document library, "
                    + "for example https://yourcompany.sharepoint.com/sites/HR/Shared Documents/CV Inbox");
        }
        return new ParsedLink(host, sitePath, "/" + String.join("/", segments), false, link.trim());
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ── Operations ────────────────────────────────────────────────────────────

    public TestConnectionResult testConnection(String tenantId, String clientId, String clientSecret, String folderLink) {
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(clientSecret)) {
            return fail("Enter the tenant ID, the application (client) ID and the client secret.");
        }
        try {
            parseLink(folderLink);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        try {
            String token = accessToken(tenantId.trim(), clientId.trim(), clientSecret);
            folders.remove(folderLink.trim()); // a test always looks the folder up afresh
            ResolvedFolder folder = resolveFolder(token, folderLink);
            int waiting = (int) listFiles(token, folder).stream().filter(SharePointClientService::isProcessable).count();
            return TestConnectionResult.builder().success(true)
                    .message("Connected to folder '" + folder.name() + "' — " + waiting + " CV file" + (waiting == 1 ? "" : "s") + " waiting.")
                    .build();
        } catch (GraphException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            return fail("Could not reach Microsoft: " + e.getMessage());
        }
    }

    /** Lists the CV files sitting directly in the folder and downloads each one. Sub-folders, other file types and
     *  files over the size cap are left untouched. */
    public List<SharePointCv> listAndFetch(String tenantId, String clientId, String clientSecret, String folderLink) throws Exception {
        String token = accessToken(tenantId.trim(), clientId.trim(), clientSecret);
        ResolvedFolder folder = resolveFolder(token, folderLink);
        List<SharePointCv> out = new ArrayList<>();
        for (JsonNode f : listFiles(token, folder)) {
            String name = f.path("name").asText();
            String mime = f.path("file").path("mimeType").asText("");
            if (!CvFileRules.isSupported(name, mime)) {
                log.info("SharePoint CV inbox: skipping unsupported file '{}' ({})", name, mime);
                continue;
            }
            if (!CvFileRules.withinSizeLimit(f.path("size").asLong(0))) {
                log.warn("SharePoint CV inbox: skipping '{}' — larger than {} MB", name, CvFileRules.MAX_FILE_BYTES / (1024 * 1024));
                continue;
            }
            String id = f.path("id").asText();
            out.add(SharePointCv.builder().itemId(id).fileName(CvFileRules.withExtension(name, mime))
                    .content(download(token, folder.driveId(), id)).build());
        }
        return out;
    }

    /** Moves a processed file into Considered or Rejected inside the inbox folder, creating it if needed.
     *  Best effort: a failure is logged, not thrown, so one stuck file does not stop the batch. */
    public void moveFile(String tenantId, String clientId, String clientSecret, String folderLink, String itemId, boolean considered) {
        String target = considered ? CONSIDERED_FOLDER : REJECTED_FOLDER;
        try {
            String token = accessToken(tenantId.trim(), clientId.trim(), clientSecret);
            ResolvedFolder folder = resolveFolder(token, folderLink);
            String targetId = findOrCreateSubfolder(token, folder, target);
            String itemUrl = "/drives/" + folder.driveId() + "/items/" + itemId;
            try {
                patch(token, itemUrl, Map.of("parentReference", Map.of("id", targetId),
                        "@microsoft.graph.conflictBehavior", "rename"));
            } catch (GraphException e) {
                if (e.status != 409) throw e;
                // A file of that name is already there: keep both by renaming this one on the way in.
                String name = getJson(token, itemUrl + "?$select=name").path("name").asText(itemId);
                patch(token, itemUrl, Map.of("parentReference", Map.of("id", targetId), "name", System.currentTimeMillis() + "_" + name));
            }
        } catch (Exception e) {
            log.error("Failed to move SharePoint item {} into the {} folder", itemId, target, e);
        }
    }

    // ── Folder lookup ─────────────────────────────────────────────────────────

    private ResolvedFolder resolveFolder(String token, String folderLink) throws IOException, InterruptedException {
        String key = folderLink.trim();
        ResolvedFolder cached = folders.get(key);
        if (cached != null && cached.resolvedAt().plus(FOLDER_CACHE_TTL).isAfter(Instant.now())) return cached;

        ParsedLink p = parseLink(folderLink);
        String driveId;
        JsonNode item;
        if (p.sharingLink()) {
            String share = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(p.original().getBytes(StandardCharsets.UTF_8));
            item = getJson(token, "/shares/" + share + "/driveItem?$select=id,name,folder,parentReference");
            driveId = item.path("parentReference").path("driveId").asText();
        } else {
            String siteRef = p.sitePath().isEmpty() ? p.host() : p.host() + ":" + p.sitePath();
            String siteId = getJson(token, "/sites/" + siteRef + "?$select=id").path("id").asText();
            JsonNode drives = getJson(token, "/sites/" + siteId + "/drives?$select=id,name,webUrl").path("value");

            // The library whose address is the longest prefix of the folder's address.
            JsonNode bestDrive = null;
            String bestPath = null;
            for (JsonNode d : drives) {
                String drivePath = URI.create(d.path("webUrl").asText()).getPath();
                if (drivePath == null) continue;
                boolean inside = p.serverRelativePath().equalsIgnoreCase(drivePath)
                        || p.serverRelativePath().toLowerCase().startsWith(drivePath.toLowerCase() + "/");
                if (inside && (bestPath == null || drivePath.length() > bestPath.length())) {
                    bestPath = drivePath;
                    bestDrive = d;
                }
            }
            if (bestDrive == null) {
                throw new GraphException(404, "Document library not found on that site. Check the link points inside a library such as Shared Documents.");
            }
            driveId = bestDrive.path("id").asText();
            String relative = p.serverRelativePath().substring(bestPath.length());
            while (relative.startsWith("/")) relative = relative.substring(1);
            item = relative.isEmpty()
                    ? getJson(token, "/drives/" + driveId + "/root?$select=id,name,folder")
                    : getJson(token, "/drives/" + driveId + "/root:/" + encodePath(relative) + "?$select=id,name,folder");
        }
        if (!item.has("folder")) throw new GraphException(400, "That link points to a file, not a folder. Open the folder and copy its link.");

        ResolvedFolder resolved = new ResolvedFolder(driveId, item.path("id").asText(), item.path("name").asText(), Instant.now());
        folders.put(key, resolved);
        return resolved;
    }

    private String findOrCreateSubfolder(String token, ResolvedFolder parent, String name) throws IOException, InterruptedException {
        String existing = "/drives/" + parent.driveId() + "/items/" + parent.itemId() + ":/" + encodePath(name) + "?$select=id,folder";
        try {
            return getJson(token, existing).path("id").asText();
        } catch (GraphException e) {
            if (e.status != 404) throw e;
        }
        try {
            JsonNode created = postJson(token, "/drives/" + parent.driveId() + "/items/" + parent.itemId() + "/children",
                    Map.of("name", name, "folder", Map.of(), "@microsoft.graph.conflictBehavior", "fail"));
            return created.path("id").asText();
        } catch (GraphException e) {
            if (e.status != 409) throw e;
            return getJson(token, existing).path("id").asText(); // created by someone else a moment ago
        }
    }

    private List<JsonNode> listFiles(String token, ResolvedFolder folder) throws IOException, InterruptedException {
        List<JsonNode> files = new ArrayList<>();
        String next = "/drives/" + folder.driveId() + "/items/" + folder.itemId() + "/children?$select=id,name,size,file&$top=200";
        while (next != null) {
            JsonNode page = getJson(token, next);
            for (JsonNode n : page.path("value")) if (n.has("file")) files.add(n);
            next = page.hasNonNull("@odata.nextLink") ? page.get("@odata.nextLink").asText() : null;
        }
        return files;
    }

    /** A file the inbox will actually read: a supported type, within the size cap. */
    private static boolean isProcessable(JsonNode f) {
        return CvFileRules.isSupported(f.path("name").asText(""), f.path("file").path("mimeType").asText(""))
                && CvFileRules.withinSizeLimit(f.path("size").asLong(0));
    }

    // ── Microsoft Graph REST ──────────────────────────────────────────────────

    private byte[] download(String token, String driveId, String itemId) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(graphBase + "/drives/" + driveId + "/items/" + itemId + "/content"))
                .header("Authorization", "Bearer " + token).GET().timeout(Duration.ofSeconds(60)).build(), BodyHandlers.ofByteArray());
        // Graph answers with a redirect to a short-lived, pre-authorised download address; follow it without our token.
        for (int hops = 0; r.statusCode() >= 300 && r.statusCode() < 400 && hops < 3; hops++) {
            String location = r.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Microsoft sent a redirect with no address."));
            r = http.send(HttpRequest.newBuilder(URI.create(location)).GET().timeout(Duration.ofSeconds(60)).build(), BodyHandlers.ofByteArray());
        }
        if (r.statusCode() >= 400) throw new GraphException(r.statusCode(), describe(r.statusCode(), r.body()));
        return r.body();
    }

    private JsonNode getJson(String token, String pathOrUrl) throws IOException, InterruptedException {
        return objectMapper.readTree(send(HttpRequest.newBuilder(URI.create(absolute(pathOrUrl)))
                .header("Authorization", "Bearer " + token).GET().timeout(Duration.ofSeconds(60)).build()).body());
    }

    private JsonNode postJson(String token, String path, Object body) throws IOException, InterruptedException {
        return objectMapper.readTree(send(HttpRequest.newBuilder(URI.create(absolute(path)))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(objectMapper.writeValueAsString(body))).timeout(Duration.ofSeconds(30)).build()).body());
    }

    private void patch(String token, String path, Object body) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(URI.create(absolute(path)))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method("PATCH", BodyPublishers.ofString(objectMapper.writeValueAsString(body))).timeout(Duration.ofSeconds(30)).build());
    }

    private String absolute(String pathOrUrl) {
        return pathOrUrl.startsWith("http") ? pathOrUrl : graphBase + pathOrUrl;
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) throw new GraphException(response.statusCode(), describe(response.statusCode(), response.body()));
        return response;
    }

    /** A message the person setting this up can act on, taken from Microsoft's error where it is clear. */
    private String describe(int status, byte[] body) {
        String code = "", message = "";
        try {
            JsonNode n = objectMapper.readTree(body);
            if (n.path("error").isObject()) {
                code = n.path("error").path("code").asText("");
                message = n.path("error").path("message").asText("");
            } else {
                code = n.path("error").asText("");
                message = n.path("error_description").asText("");
            }
        } catch (Exception ignored) {
            // not JSON — fall through to the generic message
        }
        if (message.contains("AADSTS7000215") || message.contains("AADSTS7000222")) {
            return "The client secret is wrong or has expired. Create a new secret in the Azure app registration and paste its Value (not its Secret ID).";
        }
        if (message.contains("AADSTS700016")) return "No application with that client ID exists in this tenant. Check the tenant ID and the application (client) ID.";
        if (message.contains("AADSTS90002") || message.contains("AADSTS900023")) return "Tenant not found. Check the tenant ID.";
        if (status == 401 || status == 403) {
            return "Microsoft refused access (HTTP " + status + "). The app needs the Sites.Selected (or Sites.ReadWrite.All) permission with admin consent, "
                    + "and access to this site.";
        }
        if (status == 404) return "Not found on SharePoint. Check the folder link, and that the app has access to the site.";
        String first = message.isEmpty() ? code : message.split("\\r?\\n")[0];
        return first.isEmpty() ? "Microsoft request failed (HTTP " + status + ")" : "Microsoft said: " + first + " (HTTP " + status + ")";
    }

    private static class GraphException extends IOException {
        final int status;

        GraphException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ── Sign-in ───────────────────────────────────────────────────────────────

    private String accessToken(String tenantId, String clientId, String clientSecret) throws IOException, InterruptedException {
        String key = tenantId + ":" + clientId + ":" + Integer.toHexString(clientSecret.hashCode());
        Token cached = tokens.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cached.value();

        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret) + "&scope=" + enc(SCOPE);
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(loginBase + "/" + enc(tenantId) + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form)).timeout(Duration.ofSeconds(30)).build());
        JsonNode body = objectMapper.readTree(response.body());
        Token token = new Token(body.path("access_token").asText(), Instant.now().plusSeconds(body.path("expires_in").asLong(3599)));
        tokens.put(key, token);
        return token.value();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static TestConnectionResult fail(String message) {
        return TestConnectionResult.builder().success(false).message(message).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Percent-encodes each segment of a relative path, keeping the slashes. */
    private static String encodePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String seg : path.split("/")) parts.add(enc(seg).replace("+", "%20"));
        return String.join("/", parts);
    }
}
