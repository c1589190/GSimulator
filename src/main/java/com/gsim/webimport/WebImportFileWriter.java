package com.gsim.webimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * WebImportFileWriter — 将抓取的页面写入 import/web/{host}/{safe-title}-{hash}.txt。
 */
public class WebImportFileWriter {

    private static final Logger log = LoggerFactory.getLogger(WebImportFileWriter.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final Path webImportDir;

    public WebImportFileWriter(Path importDir) {
        this.webImportDir = importDir.resolve("web");
    }

    /**
     * 将抓取的页面写入文件。
     * @return 写入的文件路径
     */
    public Path write(CrawledPage page) throws IOException {
        ensureDir();

        // 创建 host 子目录
        Path hostDir = webImportDir.resolve(sanitizeFilename(page.host()));
        Files.createDirectories(hostDir);

        // 生成文件名
        String safeTitle = sanitizeFilename(page.title());
        if (safeTitle.length() > 60) {
            safeTitle = safeTitle.substring(0, 60);
        }
        if (safeTitle.isBlank()) {
            safeTitle = "untitled";
        }

        String hash = shortHash(page.url());
        String filename = safeTitle + "-" + hash + ".txt";
        Path filePath = hostDir.resolve(filename);

        // 构建文件内容
        String content = buildFileContent(page);

        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        log.info("Written: {}", filePath);

        return filePath;
    }

    /**
     * 获取 web import 目录。
     */
    public Path getWebImportDir() {
        return webImportDir;
    }

    /**
     * 确保 web import 目录存在。
     */
    public void ensureDir() throws IOException {
        Files.createDirectories(webImportDir);
    }

    private String buildFileContent(CrawledPage page) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(page.title()).append("\n");
        sb.append("Source URL: ").append(page.url()).append("\n");
        sb.append("Fetched At: ").append(DATE_FMT.format(page.fetchedAt())).append("\n");
        sb.append("Site: ").append(page.host()).append("\n");
        sb.append("Crawler: ").append(page.crawlerName()).append("\n");
        sb.append("Collection Hint: world_lore\n");
        sb.append("Tags: web,").append(page.host()).append("\n");
        sb.append("---\n");
        sb.append(page.cleanedText());
        return sb.toString();
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "untitled";
        // 替换不安全字符
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // fallback: use hashCode
            return Integer.toHexString(input.hashCode());
        }
    }
}
