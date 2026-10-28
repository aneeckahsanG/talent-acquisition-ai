package com.talentai.cvinbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.talentai.cvinbox.service.GoogleDriveClientService;
import com.talentai.cvinbox.service.GoogleDriveClientService.DriveCv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real client against a small fake of the Google token and Drive endpoints. */
class GoogleDriveClientServiceTest {

    private static final String FOLDER = "inbox-folder";
    private static final String EMAIL = "cv-inbox@demo.iam.gserviceaccount.com";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> assertions = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private KeyPair keys;
    private String keyJson;
    private GoogleDriveClientService service;

    /** Subfolders that already exist in the inbox, by name. */
    private final Map<String, String> existingSubfolders = new HashMap<>();
    private boolean folderMissing;

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        keyJson = json.writeValueAsString(Map.of(
                "type", "service_account", "client_email", EMAIL, "private_key", pem, "token_uri", base + "/token"));
        service = new GoogleDriveClientService(json, base + "/drive/v3");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ── Fake Google ───────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        requests.add(ex.getRequestMethod() + " " + path + (query.isEmpty() ? "" : "?" + query));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) authHeaders.add(auth);

        if (path.equals("/token")) {
            Map<String, String> form = parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertions.add(form.get("assertion"));
            reply(ex, 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
            return;
        }

        String p = path.replace("/drive/v3", "");
        if (p.equals("/files") && ex.getRequestMethod().equals("GET")) {
            String q = params.getOrDefault("q", "");
            if (q.contains("name = '")) {
                String name = q.substring(q.indexOf("name = '") + 8, q.indexOf("' and mimeType"));
                String id = existingSubfolders.get(name);
                reply(ex, 200, id == null ? "{\"files\":[]}" : "{\"files\":[{\"id\":\"" + id + "\"}]}");
            } else if (!params.containsKey("pageToken")) {
                reply(ex, 200, "{\"nextPageToken\":\"p2\",\"files\":["
                        + "{\"id\":\"f1\",\"name\":\"Aisha Rahman\",\"mimeType\":\"application/pdf\",\"size\":\"1200\"},"
                        + "{\"id\":\"f2\",\"name\":\"Photo.png\",\"mimeType\":\"image/png\",\"size\":\"500\"}]}");
            } else {
                reply(ex, 200, "{\"files\":["
                        + "{\"id\":\"f3\",\"name\":\"Ben Tan CV\",\"mimeType\":\"application/vnd.google-apps.document\"},"
                        + "{\"id\":\"f4\",\"name\":\"Chen.docx\",\"mimeType\":\"" + DOCX_MIME + "\",\"size\":\"900\"},"
                        + "{\"id\":\"f5\",\"name\":\"Huge.pdf\",\"mimeType\":\"application/pdf\",\"size\":\"99999999\"}]}");
            }
        } else if (p.equals("/files") && ex.getRequestMethod().equals("POST")) {
            reply(ex, 200, "{\"id\":\"created-folder\"}");
        } else if (p.equals("/files/" + FOLDER)) {
            if (folderMissing) reply(ex, 404, "{\"error\":{\"message\":\"File not found: " + FOLDER + ".\"}}");
            else reply(ex, 200, "{\"id\":\"" + FOLDER + "\",\"name\":\"CV Inbox\",\"mimeType\":\"application/vnd.google-apps.folder\"}");
        } else if (p.equals("/files/f3/export")) {
            reply(ex, 200, "Ben Tan resume text");
        } else if (p.startsWith("/files/") && params.containsKey("alt")) {
            reply(ex, 200, "bytes-of-" + p.substring("/files/".length()));
        } else if (p.startsWith("/files/") && ex.getRequestMethod().equals("PATCH")) {
            reply(ex, 200, "{}");
        } else {
            reply(ex, 500, "{\"error\":{\"message\":\"unexpected " + p + "\"}}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            out.put(URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8),
                    i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void listAndFetchReadsEverySupportedFileAcrossPages() throws Exception {
        List<DriveCv> cvs = service.listAndFetch(keyJson, FOLDER);

        List<String> names = cvs.stream().map(DriveCv::getFileName).toList();
        // The image and the over-size file are skipped; names carry an extension matching their content.
        assertEquals(List.of("Aisha Rahman.pdf", "Ben Tan CV.txt", "Chen.docx"), names);
        assertEquals("bytes-of-f1", new String(cvs.get(0).getContent()));
        assertEquals("Ben Tan resume text", new String(cvs.get(1).getContent()), "Google Docs are exported as text");
        assertEquals(List.of("f1", "f3", "f4"), cvs.stream().map(DriveCv::getFileId).toList());
    }

    @Test
    void signsAJwtTheGoogleSideCanVerifyAndReusesTheToken() throws Exception {
        service.listAndFetch(keyJson, FOLDER);
        service.listAndFetch(keyJson, FOLDER);

        assertEquals(1, assertions.size(), "the access token is cached between calls");
        assertTrue(authHeaders.stream().allMatch("Bearer tok-1"::equals));

        String[] parts = assertions.get(0).split("\\.");
        assertEquals(3, parts.length);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "signature must verify with the account's public key");

        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals(EMAIL, claims.get("iss").asText());
        assertEquals("https://www.googleapis.com/auth/drive", claims.get("scope").asText());
        assertEquals(claims.get("iat").asLong() + 3600, claims.get("exp").asLong());
    }

    @Test
    void movingCreatesTheDestinationFolderWhenMissing() {
        service.moveFile(keyJson, FOLDER, "f1", true);

        assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST /drive/v3/files")), "creates .CONSIDERED");
        String patch = requests.stream().filter(r -> r.startsWith("PATCH ")).findFirst().orElseThrow();
        assertTrue(patch.contains("/files/f1"));
        assertTrue(patch.contains("addParents=created-folder"));
        assertTrue(patch.contains("removeParents=" + FOLDER));
    }

    @Test
    void movingReusesAnExistingDestinationFolder() {
        existingSubfolders.put(".REJECTED", "existing-rejected");

        service.moveFile(keyJson, FOLDER, "f2", false);

        assertTrue(requests.stream().noneMatch(r -> r.startsWith("POST /drive/v3/files")), "does not create a second folder");
        assertTrue(requests.stream().anyMatch(r -> r.startsWith("PATCH ") && r.contains("addParents=existing-rejected")));
    }

    @Test
    void aFailedMoveIsSwallowedSoTheBatchContinues() {
        server.stop(0); // every call now fails to connect
        assertDoesNotThrow(() -> service.moveFile(keyJson, FOLDER, "f1", true));
    }

    @Test
    void testConnectionReportsTheFolderAndHowManyCvsAreWaiting() {
        var result = service.testConnection(keyJson, FOLDER);

        assertTrue(result.isSuccess(), result.getMessage());
        assertTrue(result.getMessage().contains("CV Inbox"));
        assertTrue(result.getMessage().contains("3 CV files waiting"), result.getMessage());
    }

    @Test
    void testConnectionTellsTheUserWhoToShareTheFolderWith() {
        folderMissing = true;

        var result = service.testConnection(keyJson, FOLDER);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains(EMAIL), result.getMessage());
    }

    @Test
    void testConnectionRejectsBadKeysWithAReadableMessage() {
        assertFalse(service.testConnection("not json", FOLDER).isSuccess());
        assertTrue(service.testConnection("{\"hello\":1}", FOLDER).getMessage().contains("not a service account key"));
        assertTrue(service.testConnection(null, FOLDER).getMessage().contains("Upload the service account key"));
        assertTrue(service.testConnection(keyJson, " ").getMessage().contains("folder"));
    }

    @Test
    void validateKeyAndEmailHelpers() {
        assertEquals(EMAIL, service.serviceAccountEmail(keyJson));
        assertNull(service.serviceAccountEmail("garbage"));
        assertNull(service.serviceAccountEmail(null));
        assertDoesNotThrow(() -> service.validateKey(keyJson));
        assertThrows(IllegalArgumentException.class, () -> service.validateKey("{}"));
    }

    @Test
    void folderIdIsExtractedFromLinksOrAcceptedAsIs() {
        assertEquals("1AbC_dE-9", GoogleDriveClientService.extractFolderId("https://drive.google.com/drive/folders/1AbC_dE-9?usp=sharing"));
        assertEquals("1AbC_dE-9", GoogleDriveClientService.extractFolderId("https://drive.google.com/drive/u/0/folders/1AbC_dE-9"));
        assertEquals("1AbC_dE-9", GoogleDriveClientService.extractFolderId("https://drive.google.com/open?id=1AbC_dE-9"));
        assertEquals("1AbC_dE-9", GoogleDriveClientService.extractFolderId("  1AbC_dE-9  "));
        assertNull(GoogleDriveClientService.extractFolderId("   "));
        assertNull(GoogleDriveClientService.extractFolderId(null));
    }
}
