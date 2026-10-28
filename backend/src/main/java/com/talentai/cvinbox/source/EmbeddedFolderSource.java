package com.talentai.cvinbox.source;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The folder the embedded SFTP server writes into; HR drops files there over SFTP. */
@Slf4j
public class EmbeddedFolderSource implements CvSource {

    private final String rootPath;

    public EmbeddedFolderSource(String rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public String label() {
        return "embedded";
    }

    @Override
    public List<InboxFile> fetchAll() throws IOException {
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(root.resolve(".CONSIDERED"));
        Files.createDirectories(root.resolve(".REJECTED"));

        List<Path> files;
        try (Stream<Path> stream = Files.list(root)) {
            files = stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.error("Failed to list CV inbox folder {}", rootPath, e);
            throw e;
        }

        List<InboxFile> out = new ArrayList<>();
        for (Path file : files) {
            byte[] content = null;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException e) {
                // Keep the file in the batch with no content: it is recorded as failed and filed away, not lost.
                log.error("Failed to read CV inbox file {}", file.getFileName(), e);
            }
            out.add(new InboxFile(file.toString(), file.getFileName().toString(), content));
        }
        return out;
    }

    @Override
    public void fileAway(InboxFile file, boolean considered) {
        Path source = Paths.get(file.ref());
        Path targetDir = source.getParent().resolve(considered ? ".CONSIDERED" : ".REJECTED");
        try {
            Path target = targetDir.resolve(source.getFileName());
            if (Files.exists(target)) {
                // Don't clobber an identically-named file processed on a previous run
                target = targetDir.resolve(System.currentTimeMillis() + "_" + source.getFileName());
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to move processed file {} to {}", source.getFileName(), targetDir.getFileName(), e);
        }
    }
}
