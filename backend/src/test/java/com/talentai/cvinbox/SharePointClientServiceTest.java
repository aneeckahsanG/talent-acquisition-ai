package com.talentai.cvinbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.talentai.cvinbox.service.SharePointClientService;
import com.talentai.cvinbox.service.SharePointClientService.ParsedLink;
import com.talentai.cvinbox.service.SharePointClientService.SharePointCv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real client against a small fake of Microsoft's sign-in and Graph endpoints. */
class SharePointClientServiceTest {

    private static final String LINK = "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/CV%20Inbox";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokenForms = new CopyOnWriteArrayList<>();
    private final List<String> graphAuth = new CopyOnWriteArrayList<>();
    private final List<String> downloadAuth = new CopyOnWriteArrayList<>();
    private final List<String> patchBodies = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private String base;
    private SharePointClientService service;

    // switches for the scenarios
    private boolean subfolderMissing = true;
    private boolean moveConflict;
    private boolean forbidden;
    private boolean folderIsFile;
    private int conflictsSent;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new SharePointClientService(json, base + "/v1.0", base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ── Fake Microsoft ────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        requests.add(method + " " + path + (query.isEmpty() ? "" : "?" + query));
        String auth = ex.getRequestHeaders().getFirst("Authorization");

        if (path.startsWith("/download/")) {
            downloadAuth.add(String.valueOf(auth));
            reply(ex, 200, "content-of-" + path.substring("/download/".length()));
            return;
        }
        if (path.endsWith("/oauth2/v2.0/token")) {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenForms.add(URLDecoder.decode(form, StandardCharsets.UTF_8));
            if (form.contains("client_secret=bad")) {
                reply(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret provided.\\r\\nTrace ID: x\"}");
            } else {
                reply(ex, 200, "{\"access_token\":\"graph-token\",\"expires_in\":3599}");
            }
            return;
        }
        if (auth != null) graphAuth.add(auth);
        String p = path.replace("/v1.0", "");

        if (forbidden && (p.startsWith("/sites") || p.startsWith("/shares"))) {
            reply(ex, 403, "{\"error\":{\"code\":\"accessDenied\",\"message\":\"Access denied\"}}");
        } else if (p.equals("/sites/contoso.sharepoint.com:/sites/HR")) {
            reply(ex, 200, "{\"id\":\"site-1\"}");
        } else if (p.equals("/sites/site-1/drives")) {
            reply(ex, 200, "{\"value\":["
                    + "{\"id\":\"drive-1\",\"name\":\"Documents\",\"webUrl\":\"https://contoso.sharepoint.com/sites/HR/Shared%20Documents\"},"
                    + "{\"id\":\"drive-2\",\"name\":\"Archive\",\"webUrl\":\"https://contoso.sharepoint.com/sites/HR/Archive\"}]}");
        } else if (p.equals("/drives/drive-1/root:/CV%20Inbox")) {
            reply(ex, 200, folderIsFile ? "{\"id\":\"file-x\",\"name\":\"CV Inbox\"}"
                    : "{\"id\":\"folder-1\",\"name\":\"CV Inbox\",\"folder\":{}}");
        } else if (p.equals("/drives/drive-1/root:/Nested/Sub%20Folder")) {
            reply(ex, 200, "{\"id\":\"folder-nested\",\"name\":\"Sub Folder\",\"folder\":{}}");
        } else if (p.equals("/drives/drive-1/root:/Missing")) {
            reply(ex, 404, "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"The resource could not be found.\"}}");
        } else if (p.equals("/drives/drive-1/root")) {
            reply(ex, 200, "{\"id\":\"root-1\",\"name\":\"Documents\",\"folder\":{}}");
        } else if (p.equals("/drives/drive-2/root:/Old")) {
            reply(ex, 200, "{\"id\":\"folder-old\",\"name\":\"Old\",\"folder\":{}}");
        } else if (p.startsWith("/shares/") && p.endsWith("/driveItem")) {
            reply(ex, 200, "{\"id\":\"folder-1\",\"name\":\"CV Inbox\",\"folder\":{},\"parentReference\":{\"driveId\":\"drive-1\"}}");
        } else if (p.matches("/drives/drive-\\d/items/folder-(nested|old)/children") && method.equals("GET")) {
            reply(ex, 200, "{\"value\":[]}");
        } else if (p.equals("/drives/drive-1/items/folder-1/children") && method.equals("GET")) {
            if (!query.contains("page=2")) {
                reply(ex, 200, "{\"value\":["
                        + "{\"id\":\"f1\",\"name\":\"Aisha Rahman.pdf\",\"size\":1200,\"file\":{\"mimeType\":\"application/pdf\"}},"
                        + "{\"id\":\"f2\",\"name\":\"Photo.png\",\"size\":500,\"file\":{\"mimeType\":\"image/png\"}},"
                        + "{\"id\":\"s1\",\"name\":\"Older\",\"folder\":{\"childCount\":4}}],"
                        + "\"@odata.nextLink\":\"" + base + "/v1.0/drives/drive-1/items/folder-1/children?page=2\"}");
            } else {
                reply(ex, 200, "{\"value\":["
                        + "{\"id\":\"f3\",\"name\":\"Ben Tan.docx\",\"size\":900,\"file\":{\"mimeType\":\"" + DOCX_MIME + "\"}},"
                        + "{\"id\":\"f4\",\"name\":\"Huge.pdf\",\"size\":99999999,\"file\":{\"mimeType\":\"application/pdf\"}},"
                        + "{\"id\":\"f5\",\"name\":\"Resume\",\"size\":700,\"file\":{\"mimeType\":\"application/pdf\"}}]}");
            }
        } else if (p.matches("/drives/drive-1/items/f\\d/content")) {
            ex.getResponseHeaders().add("Location", base + "/download/" + p.split("/")[4]);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } else if (p.equals("/drives/drive-1/items/folder-1:/Considered")) {
            if (subfolderMissing) reply(ex, 404, "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"nope\"}}");
            else reply(ex, 200, "{\"id\":\"considered-1\",\"folder\":{}}");
        } else if (p.equals("/drives/drive-1/items/folder-1:/Rejected")) {
            reply(ex, 200, "{\"id\":\"rejected-1\",\"folder\":{}}");
        } else if (p.equals("/drives/drive-1/items/folder-1/children") && method.equals("POST")) {
            reply(ex, 201, "{\"id\":\"created-1\"}");
        } else if (p.matches("/drives/drive-1/items/f\\d") && method.equals("PATCH")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            patchBodies.add(body);
            if (moveConflict && !body.contains("\"name\"")) {
                conflictsSent++;
                reply(ex, 409, "{\"error\":{\"code\":\"nameAlreadyExists\",\"message\":\"exists\"}}");
            } else {
                reply(ex, 200, "{}");
            }
        } else if (p.matches("/drives/drive-1/items/f\\d") && method.equals("GET")) {
            reply(ex, 200, "{\"name\":\"cv.pdf\"}");
        } else {
            reply(ex, 500, "{\"error\":{\"message\":\"unexpected " + method + " " + p + "\"}}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private List<SharePointCv> fetch(String link) throws Exception {
        return service.listAndFetch("tenant-1", "client-1", "secret-1", link);
    }

    // ── Link parsing ──────────────────────────────────────────────────────────

    @Test
    void aPlainFolderAddressIsUnderstood() {
        ParsedLink p = SharePointClientService.parseLink(LINK);

        assertEquals("contoso.sharepoint.com", p.host());
        assertEquals("/sites/HR", p.sitePath());
        assertEquals("/sites/HR/Shared Documents/CV Inbox", p.serverRelativePath());
        assertFalse(p.sharingLink());
    }

    @Test
    void theAddressBarFormOfALibraryViewIsUnderstood() {
        ParsedLink p = SharePointClientService.parseLink("https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Forms/AllItems.aspx"
                + "?id=%2Fsites%2FHR%2FShared%20Documents%2FCV%20Inbox&viewid=abc");

        assertEquals("/sites/HR", p.sitePath());
        assertEquals("/sites/HR/Shared Documents/CV Inbox", p.serverRelativePath());
    }

    @Test
    void aLibraryViewWithoutAFolderPointsAtTheLibraryItself() {
        ParsedLink p = SharePointClientService.parseLink("https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Forms/AllItems.aspx");

        assertEquals("/sites/HR/Shared Documents", p.serverRelativePath());
    }

    @Test
    void teamSitesAndTheRootSiteAreUnderstood() {
        assertEquals("/teams/Talent", SharePointClientService.parseLink("https://contoso.sharepoint.com/teams/Talent/Shared%20Documents/CVs").sitePath());

        ParsedLink root = SharePointClientService.parseLink("https://contoso.sharepoint.com/Shared%20Documents/CVs");
        assertEquals("", root.sitePath());
        assertEquals("/Shared Documents/CVs", root.serverRelativePath());
    }

    @Test
    void aSharingLinkIsRecognisedAndKeptVerbatim() {
        ParsedLink p = SharePointClientService.parseLink("https://contoso.sharepoint.com/:f:/s/HR/EabcDEF123?e=xyz");

        assertTrue(p.sharingLink());
        assertEquals("https://contoso.sharepoint.com/:f:/s/HR/EabcDEF123?e=xyz", p.original());
    }

    @Test
    void linksThatCannotWorkAreRejectedWithAReadableMessage() {
        for (String bad : new String[]{null, " ", "not a link", "http://contoso.sharepoint.com/sites/HR/Docs/CVs",
                "https://contoso.sharepoint.com/sites/HR", "https://contoso.sharepoint.com/"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SharePointClientService.parseLink(bad), String.valueOf(bad));
            assertFalse(e.getMessage().isBlank());
        }
    }

    // ── Reading the folder ────────────────────────────────────────────────────

    @Test
    void everySupportedFileIsReadAcrossPagesAndTheRestIsLeftAlone() throws Exception {
        List<SharePointCv> cvs = fetch(LINK);

        // the image, the sub-folder and the over-size file are skipped; a file with no extension gets the right one
        assertEquals(List.of("Aisha Rahman.pdf", "Ben Tan.docx", "Resume.pdf"), cvs.stream().map(SharePointCv::getFileName).toList());
        assertEquals(List.of("f1", "f3", "f5"), cvs.stream().map(SharePointCv::getItemId).toList());
        assertEquals("content-of-f1", new String(cvs.get(0).getContent()));
    }

    @Test
    void theTokenIsNeverSentToTheDownloadAddress() throws Exception {
        fetch(LINK);

        assertEquals(3, downloadAuth.size());
        assertTrue(downloadAuth.stream().allMatch("null"::equals), "download addresses are pre-authorised: " + downloadAuth);
        assertTrue(graphAuth.stream().allMatch("Bearer graph-token"::equals));
    }

    @Test
    void signsInWithTheClientCredentialsAndReusesTheToken() throws Exception {
        fetch(LINK);
        fetch(LINK);

        assertEquals(1, tokenForms.size(), "the access token is cached between calls");
        String form = tokenForms.get(0);
        assertTrue(form.contains("grant_type=client_credentials"));
        assertTrue(form.contains("client_id=client-1"));
        assertTrue(form.contains("client_secret=secret-1"));
        assertTrue(form.contains("scope=https://graph.microsoft.com/.default"));
        assertTrue(requests.stream().anyMatch(r -> r.contains("/tenant-1/oauth2/v2.0/token")));
    }

    @Test
    void theLibraryWhoseAddressBestMatchesIsUsed() throws Exception {
        fetch("https://contoso.sharepoint.com/sites/HR/Archive/Old");

        assertTrue(requests.stream().anyMatch(r -> r.contains("/drives/drive-2/root:/Old")), requests.toString());
    }

    @Test
    void folderNamesWithSpacesAreEncodedPerSegment() throws Exception {
        fetch("https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Nested/Sub%20Folder");

        assertTrue(requests.stream().anyMatch(r -> r.contains("/drives/drive-1/root:/Nested/Sub%20Folder")), requests.toString());
    }

    @Test
    void aSharingLinkIsResolvedThroughTheSharesApi() throws Exception {
        String sharing = "https://contoso.sharepoint.com/:f:/s/HR/EabcDEF123?e=xyz";

        List<SharePointCv> cvs = fetch(sharing);

        String expected = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(sharing.getBytes(StandardCharsets.UTF_8));
        assertTrue(requests.stream().anyMatch(r -> r.contains("/shares/" + expected + "/driveItem")), requests.toString());
        assertEquals(3, cvs.size());
    }

    // ── Filing away ───────────────────────────────────────────────────────────

    private void move(String itemId, boolean considered) {
        service.moveFile("tenant-1", "client-1", "secret-1", LINK, itemId, considered);
    }

    @Test
    void movingCreatesTheConsideredFolderWhenItIsMissing() {
        subfolderMissing = true;

        move("f1", true);

        assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST ") && r.contains("/items/folder-1/children")), "creates Considered");
        assertEquals(1, patchBodies.size());
        assertTrue(patchBodies.get(0).contains("\"id\":\"created-1\""), patchBodies.get(0));
        assertTrue(patchBodies.get(0).contains("conflictBehavior"), patchBodies.get(0));
    }

    @Test
    void movingReusesAnExistingFolder() {
        move("f2", false);

        assertTrue(requests.stream().noneMatch(r -> r.startsWith("POST ") && r.contains("/children")), "does not create a second Rejected folder");
        assertTrue(patchBodies.get(0).contains("\"id\":\"rejected-1\""), patchBodies.get(0));
    }

    @Test
    void aNameClashKeepsBothFilesByRenamingTheNewcomer() {
        subfolderMissing = false;
        moveConflict = true;

        move("f1", true);

        assertEquals(1, conflictsSent);
        assertEquals(2, patchBodies.size());
        assertTrue(patchBodies.get(1).contains("_cv.pdf"), "timestamp-prefixed name: " + patchBodies.get(1));
    }

    @Test
    void aFailedMoveIsSwallowedSoTheBatchContinues() {
        server.stop(0);

        assertDoesNotThrow(() -> move("f1", true));
    }

    // ── Test connection ───────────────────────────────────────────────────────

    @Test
    void testConnectionReportsTheFolderAndHowManyCvsAreWaiting() {
        var result = service.testConnection("tenant-1", "client-1", "secret-1", LINK);

        assertTrue(result.isSuccess(), result.getMessage());
        assertTrue(result.getMessage().contains("'CV Inbox'"), result.getMessage());
        assertTrue(result.getMessage().contains("3 CV files waiting"), result.getMessage());
    }

    @Test
    void aWrongSecretIsExplainedInPlainWords() {
        var result = service.testConnection("tenant-1", "client-1", "bad", LINK);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("client secret"), result.getMessage());
        assertTrue(result.getMessage().contains("Value"), "tells the user to copy the secret's Value: " + result.getMessage());
    }

    @Test
    void anAppWithoutAccessToTheSiteIsToldWhichPermissionToGrant() {
        forbidden = true;

        var result = service.testConnection("tenant-1", "client-1", "secret-1", LINK);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Sites.Selected"), result.getMessage());
    }

    @Test
    void aMissingFolderALinkToAFileAndAMissingLibraryAreEachExplained() {
        assertTrue(service.testConnection("tenant-1", "client-1", "secret-1",
                "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Missing").getMessage().contains("Not found"));
        assertTrue(service.testConnection("tenant-1", "client-1", "secret-1",
                "https://contoso.sharepoint.com/sites/HR/Nope/Folder").getMessage().contains("library"));

        folderIsFile = true;
        assertTrue(service.testConnection("tenant-1", "client-1", "secret-1", LINK).getMessage().contains("not a folder"));
    }

    @Test
    void incompleteSettingsAreCaughtBeforeAnythingIsSent() {
        assertTrue(service.testConnection("", "client-1", "secret-1", LINK).getMessage().contains("tenant ID"));
        assertTrue(service.testConnection("tenant-1", "client-1", null, LINK).getMessage().contains("client secret"));
        assertTrue(service.testConnection("tenant-1", "client-1", "secret-1", "nonsense").getMessage().length() > 5);
        assertTrue(requests.isEmpty(), "nothing was sent to Microsoft: " + requests);
    }

    @Test
    void aTestAlwaysLooksTheFolderUpAfresh() {
        service.testConnection("tenant-1", "client-1", "secret-1", LINK);
        service.testConnection("tenant-1", "client-1", "secret-1", LINK);

        long lookups = requests.stream().filter(r -> r.contains("/drives/drive-1/root:/CV%20Inbox")).count();
        assertEquals(2, lookups);
    }

    @Test
    void theFolderIsLookedUpOnceForABatchOfFiles() throws Exception {
        fetch(LINK);
        move("f1", true);
        move("f3", false);

        long lookups = requests.stream().filter(r -> r.contains("/drives/drive-1/root:/CV%20Inbox")).count();
        assertEquals(1, lookups, "moves reuse the folder resolved for the listing");
    }
}
