package com.talentai.cvinbox;

import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.service.ExternalSftpClientService;
import com.talentai.cvinbox.service.ExternalSftpClientService.RemoteCv;
import com.talentai.cvinbox.service.GoogleDriveClientService;
import com.talentai.cvinbox.service.GoogleDriveClientService.DriveCv;
import com.talentai.cvinbox.service.SharePointClientService;
import com.talentai.cvinbox.service.SharePointClientService.SharePointCv;
import com.talentai.cvinbox.source.CvSource;
import com.talentai.cvinbox.source.CvSourceFactory;
import com.talentai.cvinbox.source.EmbeddedFolderSource;
import com.talentai.cvinbox.source.GoogleDriveSource;
import com.talentai.cvinbox.source.InboxFile;
import com.talentai.cvinbox.source.SftpSource;
import com.talentai.cvinbox.source.SharePointSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CvSourcesTest {

    // ── local folder (embedded SFTP server) ───────────────────────────────────

    @Test
    void theLocalFolderIsPreparedAndOnlyFilesAreListed(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("aisha.txt"), "Aisha CV");
        Files.createDirectories(root.resolve("a-subfolder"));

        List<InboxFile> files = new EmbeddedFolderSource(root.toString()).fetchAll();

        assertEquals(1, files.size());
        assertEquals("aisha.txt", files.get(0).fileName());
        assertEquals("Aisha CV", new String(files.get(0).content(), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(root.resolve(".CONSIDERED")));
        assertTrue(Files.isDirectory(root.resolve(".REJECTED")));
    }

    @Test
    void aMissingLocalFolderIsCreated(@TempDir Path parent) throws Exception {
        Path root = parent.resolve("not-yet-there");

        assertTrue(new EmbeddedFolderSource(root.toString()).fetchAll().isEmpty());
        assertTrue(Files.isDirectory(root.resolve(".CONSIDERED")));
    }

    @Test
    void processedFilesMoveIntoTheMatchingSubfolder(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("keep.txt"), "a");
        Files.writeString(root.resolve("drop.txt"), "b");
        CvSource source = new EmbeddedFolderSource(root.toString());
        List<InboxFile> files = source.fetchAll();

        for (InboxFile f : files) source.fileAway(f, f.fileName().equals("keep.txt"));

        assertTrue(Files.exists(root.resolve(".CONSIDERED").resolve("keep.txt")));
        assertTrue(Files.exists(root.resolve(".REJECTED").resolve("drop.txt")));
        assertFalse(Files.exists(root.resolve("keep.txt")));
        assertFalse(Files.exists(root.resolve("drop.txt")));
        assertTrue(source.fetchAll().isEmpty(), "nothing is picked up twice");
    }

    @Test
    void aFileWithTheSameNameAsAnEarlierOneDoesNotOverwriteIt(@TempDir Path root) throws Exception {
        CvSource source = new EmbeddedFolderSource(root.toString());
        Files.writeString(root.resolve("cv.txt"), "first");
        source.fileAway(source.fetchAll().get(0), true);
        Files.writeString(root.resolve("cv.txt"), "second");

        source.fileAway(source.fetchAll().get(0), true);

        try (var kept = Files.list(root.resolve(".CONSIDERED"))) {
            assertEquals(2, kept.count(), "both copies are kept");
        }
        assertEquals("first", Files.readString(root.resolve(".CONSIDERED").resolve("cv.txt")));
    }

    // ── SFTP and Google Drive hand their work to the existing clients ─────────

    @Test
    void sftpFilesAreFetchedAndFiledAwayThroughTheClient() throws Exception {
        ExternalSftpClientService client = mock(ExternalSftpClientService.class);
        CvInboxSettings settings = CvInboxSettings.builder().mode("EXTERNAL").externalHost("hr.example.com").externalPort(22).build();
        when(client.listAndFetch(settings)).thenReturn(List.of(RemoteCv.builder().fileName("a.pdf").content(new byte[]{1}).build()));
        CvSource source = new SftpSource(settings, client);

        List<InboxFile> files = source.fetchAll();
        source.fileAway(files.get(0), false);

        assertEquals("a.pdf", files.get(0).fileName());
        verify(client).moveRemoteFile(settings, "a.pdf", false);
        assertTrue(source.label().contains("hr.example.com"));
    }

    @Test
    void driveFilesAreFetchedAndFiledAwayByTheirDriveId() throws Exception {
        GoogleDriveClientService client = mock(GoogleDriveClientService.class);
        when(client.listAndFetch("{key}", "folder-1")).thenReturn(List.of(
                DriveCv.builder().fileId("file-9").fileName("b.docx").content(new byte[]{2}).build()));
        CvSource source = new GoogleDriveSource("{key}", "folder-1", client);

        List<InboxFile> files = source.fetchAll();
        source.fileAway(files.get(0), true);

        assertEquals("b.docx", files.get(0).fileName());
        verify(client).moveFile("{key}", "folder-1", "file-9", true);
    }

    @Test
    void sharePointFilesAreFetchedAndFiledAwayByTheirItemId() throws Exception {
        SharePointClientService client = mock(SharePointClientService.class);
        when(client.listAndFetch("tenant", "client", "secret", "https://x.sharepoint.com/sites/HR/Docs/CVs")).thenReturn(List.of(
                SharePointCv.builder().itemId("item-3").fileName("c.pdf").content(new byte[]{3}).build()));
        CvSource source = new SharePointSource("tenant", "client", "secret", "https://x.sharepoint.com/sites/HR/Docs/CVs", client);

        List<InboxFile> files = source.fetchAll();
        source.fileAway(files.get(0), false);

        assertEquals("c.pdf", files.get(0).fileName());
        verify(client).moveFile("tenant", "client", "secret", "https://x.sharepoint.com/sites/HR/Docs/CVs", "item-3", false);
    }

    // ── choosing the source from the saved settings ───────────────────────────

    @Test
    void theSavedModeDecidesWhichSourceIsUsed() {
        CvSourceFactory factory = new CvSourceFactory(mock(ExternalSftpClientService.class), mock(GoogleDriveClientService.class), mock(SharePointClientService.class), "./inbox");

        assertInstanceOf(EmbeddedFolderSource.class, factory.forSettings(null));
        assertInstanceOf(EmbeddedFolderSource.class, factory.forSettings(CvInboxSettings.builder().mode("EMBEDDED").build()));
        assertInstanceOf(EmbeddedFolderSource.class, factory.forSettings(CvInboxSettings.builder().mode(null).build()));
        assertInstanceOf(SftpSource.class, factory.forSettings(CvInboxSettings.builder().mode("EXTERNAL").build()));
        assertInstanceOf(GoogleDriveSource.class, factory.forSettings(CvInboxSettings.builder().mode("GOOGLE_DRIVE").googleFolderId("f").build()));
        assertInstanceOf(SharePointSource.class, factory.forSettings(CvInboxSettings.builder().mode("SHAREPOINT").build()));
    }
}
