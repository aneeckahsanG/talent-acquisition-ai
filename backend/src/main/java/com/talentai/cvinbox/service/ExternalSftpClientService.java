package com.talentai.cvinbox.service;

import com.jcraft.jsch.*;
import com.talentai.cvinbox.entity.CvInboxSettings;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP *client* for the "External SFTP" CV Inbox mode — connects out to an SFTP server HR
 * already has, instead of HR connecting into our embedded server. Uses JSch rather than the
 * MINA SSHD library used for the embedded server role, specifically because JSch has
 * first-class HTTP CONNECT proxy support ({@link ProxyHTTP}), which MINA SSHD's client side
 * does not (its only proxy hook is for the unrelated HAProxy PROXY-protocol handshake).
 */
@Service
@Slf4j
public class ExternalSftpClientService {

    private static final int CONNECT_TIMEOUT_MS = 8000;

    @Data
    @Builder
    public static class RemoteCv {
        private String fileName;
        private byte[] content;
    }

    @Data
    @Builder
    public static class TestConnectionResult {
        private boolean success;
        private String message;
    }

    public TestConnectionResult testConnection(String host, int port, String username, String password,
                                                boolean proxyEnabled, String proxyHost, Integer proxyPort) {
        Session session = null;
        try {
            session = openSession(host, port, username, password, proxyEnabled, proxyHost, proxyPort);
            return TestConnectionResult.builder().success(true)
                    .message("Connected and authenticated successfully.").build();
        } catch (Exception e) {
            log.warn("External SFTP test connection failed for {}@{}:{}", username, host, port, e);
            return TestConnectionResult.builder().success(false)
                    .message(describeFailure(e)).build();
        } finally {
            if (session != null) session.disconnect();
        }
    }

    /** Connects once, lists regular files directly under the root path (skipping
     *  .CONSIDERED/.REJECTED), and downloads each as bytes. */
    public List<RemoteCv> listAndFetch(CvInboxSettings settings) throws Exception {
        Session session = openSession(settings.getExternalHost(), settings.getExternalPort(),
                settings.getExternalUsername(), settings.getExternalPassword(),
                Boolean.TRUE.equals(settings.getProxyEnabled()), settings.getProxyHost(), settings.getProxyPort());
        try {
            ChannelSftp sftp = openSftpChannel(session);
            try {
                ensureRemoteDir(sftp, settings.getExternalRootPath() + "/.CONSIDERED");
                ensureRemoteDir(sftp, settings.getExternalRootPath() + "/.REJECTED");

                List<RemoteCv> results = new ArrayList<>();
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> entries = sftp.ls(settings.getExternalRootPath());
                for (ChannelSftp.LsEntry entry : entries) {
                    if (entry.getAttrs().isDir()) continue;
                    String fileName = entry.getFilename();
                    try (InputStream in = sftp.get(settings.getExternalRootPath() + "/" + fileName)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        in.transferTo(buffer);
                        results.add(RemoteCv.builder().fileName(fileName).content(buffer.toByteArray()).build());
                    }
                }
                return results;
            } finally {
                sftp.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    /** Renames a remote file into .CONSIDERED/ or .REJECTED/ under the configured root — the
     *  remote equivalent of the embedded mode's local move-into-subfolder step. Opens its own
     *  short-lived session (called once per file after {@link #listAndFetch}, which already
     *  closed its own session — kept simple over keeping one session open for the whole batch). */
    public void moveRemoteFile(CvInboxSettings settings, String fileName, boolean considered) {
        Session session = null;
        try {
            session = openSession(settings.getExternalHost(), settings.getExternalPort(),
                    settings.getExternalUsername(), settings.getExternalPassword(),
                    Boolean.TRUE.equals(settings.getProxyEnabled()), settings.getProxyHost(), settings.getProxyPort());
            ChannelSftp sftp = openSftpChannel(session);
            try {
                String root = settings.getExternalRootPath();
                String from = root + "/" + fileName;
                String targetDir = root + "/" + (considered ? ".CONSIDERED" : ".REJECTED");
                String to = targetDir + "/" + fileName;
                try {
                    sftp.rename(from, to);
                } catch (SftpException e) {
                    // Don't clobber an identically-named file moved there on a previous run
                    sftp.rename(from, targetDir + "/" + System.currentTimeMillis() + "_" + fileName);
                }
            } finally {
                sftp.disconnect();
            }
        } catch (Exception e) {
            log.error("Failed to move remote file {} into {} folder", fileName, considered ? ".CONSIDERED" : ".REJECTED", e);
        } finally {
            if (session != null) session.disconnect();
        }
    }

    private Session openSession(String host, int port, String username, String password,
                                 boolean proxyEnabled, String proxyHost, Integer proxyPort) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no");
        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank()) {
            session.setProxy(new ProxyHTTP(proxyHost, proxyPort != null ? proxyPort : 80));
        }
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }

    private ChannelSftp openSftpChannel(Session session) throws JSchException {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(CONNECT_TIMEOUT_MS);
        return channel;
    }

    private void ensureRemoteDir(ChannelSftp sftp, String path) {
        try {
            sftp.mkdir(path);
        } catch (SftpException ignored) {
            // Already exists — fine.
        }
    }

    private String describeFailure(Exception e) {
        if (e instanceof JSchException && e.getMessage() != null) {
            String msg = e.getMessage();
            if (msg.toLowerCase().contains("auth")) return "Authentication failed — check username/password.";
            if (msg.toLowerCase().contains("proxy")) return "Proxy connection failed: " + msg;
            if (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("refused")) {
                return "Could not reach the server: " + msg;
            }
            return msg;
        }
        return e.getMessage() != null ? e.getMessage() : "Connection failed.";
    }
}
