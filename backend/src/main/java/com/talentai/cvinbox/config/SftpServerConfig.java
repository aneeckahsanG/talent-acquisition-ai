package com.talentai.cvinbox.config;

import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.repository.CvInboxSettingsRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Hosts a self-contained SFTP server HR can connect to (FileZilla, WinSCP, etc.) to drop
 * CVs for the CV Inbox feature. The session is jailed to {@code app.cv-inbox.root-path} via
 * VirtualFileSystemFactory, so HR can only ever see that one folder, never the real filesystem.
 *
 * Port/username/password are no longer fixed at startup — they live in {@link CvInboxSettings}
 * (editable from the UI) and {@link #restart(CvInboxSettings)} is called whenever that changes,
 * so a saved change takes effect immediately without an app restart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SftpServerConfig {

    private final CvInboxSettingsRepository settingsRepository;

    @Value("${app.sftp.host-key-path}")
    private String hostKeyPath;

    @Value("${app.cv-inbox.root-path}")
    private String rootPath;

    private SshServer sshServer;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CvInboxSettings settings = settingsRepository.findById(1L).orElse(null);
        if (settings != null && "EMBEDDED".equals(settings.getMode())) {
            restart(settings);
        }
    }

    /** Stops any running server and starts a fresh one with the given port/username/password. */
    public synchronized void restart(CvInboxSettings settings) {
        stop();
        try {
            // RootedFileSystemProvider's containment checks require an absolute root —
            // a relative path (e.g. the default "./data/cv-inbox") makes every path it
            // resolves fail validation with "Not under root: ./data/cv-inbox".
            Path root = Paths.get(rootPath).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(".CONSIDERED"));
            Files.createDirectories(root.resolve(".REJECTED"));

            String username = settings.getEmbeddedUsername();
            String password = settings.getEmbeddedPassword();

            SshServer server = SshServer.setUpDefaultServer();
            server.setPort(settings.getEmbeddedPort());
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get(hostKeyPath)));
            server.setPasswordAuthenticator((user, pass, session) ->
                    username.equals(user) && password.equals(pass));
            server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
            server.setFileSystemFactory(new VirtualFileSystemFactory(root));
            server.start();
            sshServer = server;

            log.info("CV Inbox SFTP server started on port {} (root: {})", settings.getEmbeddedPort(), root);
        } catch (IOException e) {
            log.error("Failed to start CV Inbox SFTP server on port {}", settings.getEmbeddedPort(), e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (sshServer != null) {
            try {
                sshServer.stop();
                log.info("CV Inbox SFTP server stopped");
            } catch (IOException e) {
                log.warn("Error stopping CV Inbox SFTP server", e);
            } finally {
                sshServer = null;
            }
        }
    }
}
