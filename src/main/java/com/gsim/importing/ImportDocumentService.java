package com.gsim.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ImportDocumentService — 统一管理 import 目录下的本地导入文档和 wiki 下载文档。
 *
 * 来源:
 * - LOCAL_IMPORT: 用户手动放入 ./import 的 txt/md 文件
 * - WIKI_DOWNLOADED: 之前联网/wiki 流程下载到 ./import/web/ 的缓存文件
 *
 * 安全: 不允许 path traversal，不跟随 symlink 到 import 目录外。
 */
public class ImportDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ImportDocumentService.class);

    public static final String SOURCE_LOCAL_IMPORT = "LOCAL_IMPORT";
    public static final String SOURCE_WIKI_DOWNLOADED = "WIKI_DOWNLOADED";

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");
    private static final int MAX_FULL_READ_CHARS = 30000;
    private static final int DEFAULT_LIMIT = 8000;

    private final Path importDir;

    public ImportDocumentService(Path importDir) {
        this.importDir = importDir.toAbsolutePath().normalize();
    }

    /** 列出所有可读导入文档。不跟随 symlink 遍历，逐个校验路径安全。 */
    public List<ImportDocumentInfo> listDocuments() throws IOException {
        List<ImportDocumentInfo> results = new ArrayList<>();
        if (!Files.isDirectory(importDir)) {
            return results;
        }

        try (Stream<Path> files = Files.walk(importDir)) {
            files.filter(f -> Files.isRegularFile(f) && !Files.isSymbolicLink(f))
                    .filter(this::isAllowedFile)
                    .forEach(file -> {
                        try {
                            results.add(toDocumentInfo(file));
                        } catch (ImportDocumentException e) {
                            log.debug("Skipping unsafe file: {} — {}", file, e.getMessage());
                        } catch (Exception e) {
                            log.debug("Skipping unreadable file: {}", file, e);
                        }
                    });
        }
        results.sort(Comparator.comparing(ImportDocumentInfo::source)
                .thenComparing(ImportDocumentInfo::displayName));
        return results;
    }

    /** 读取文档（带分页）。 */
    public ImportDocumentReadResult readDocument(String documentId, int offset, int limit, boolean full)
            throws IOException {
        Path file = resolveSafe(documentId);
        if (!Files.exists(file)) {
            throw new ImportDocumentException("IMPORT_DOCUMENT_NOT_FOUND", "Document not found: " + documentId);
        }
        if (!isAllowedFile(file)) {
            throw new ImportDocumentException("UNSUPPORTED_IMPORT_DOCUMENT_TYPE",
                    "Unsupported file type: " + documentId);
        }

        String content = readFileContent(file);
        int originalLength = content.length();

        int effectiveLimit = full ? MAX_FULL_READ_CHARS : Math.max(1, limit);
        if (offset < 0) offset = 0;

        String source = determineSource(file);
        if (offset >= originalLength) {
            return new ImportDocumentReadResult(
                    documentId, source, file.getFileName().toString(),
                    originalLength, offset, effectiveLimit,
                    "none", false, "none", "");
        }

        int end = Math.min(offset + effectiveLimit, originalLength);
        boolean truncated = end < originalLength;
        String returnedContent = content.substring(offset, end);

        return new ImportDocumentReadResult(
                documentId, source, file.getFileName().toString(),
                originalLength, offset, effectiveLimit,
                offset + "-" + (end - 1), truncated,
                truncated ? String.valueOf(end) : "none",
                returnedContent);
    }

    /** 在 import 文档中搜索关键词。 */
    public List<ImportDocumentSearchMatch> searchDocuments(
            String query, String documentId, String source,
            int maxResults, int contextChars, boolean caseSensitive) throws IOException {

        if (query == null || query.isBlank()) {
            throw new ImportDocumentException("IMPORT_QUERY_EMPTY", "Search query must not be empty");
        }

        int effectiveMaxResults = Math.max(1, maxResults);
        int effectiveContextChars = Math.max(0, contextChars);

        List<ImportDocumentSearchMatch> results = new ArrayList<>();
        String searchQuery = caseSensitive ? query : query.toLowerCase();

        List<ImportDocumentInfo> candidates;
        if (documentId != null && !documentId.isBlank()) {
            Path file = resolveSafe(documentId);
            if (!Files.exists(file) || !isAllowedFile(file)) return results;
            candidates = List.of(toDocumentInfo(file));
        } else {
            candidates = listDocuments();
        }

        for (ImportDocumentInfo doc : candidates) {
            if (source != null && !source.isBlank() && !doc.source().equals(source)) continue;
            try {
                Path file = resolveSafe(doc.documentId());
                String content = readFileContent(file);
                String searchContent = caseSensitive ? content : content.toLowerCase();

                int idx = 0;
                int matchIndex = 0;
                int queryLen = query.length();
                while (queryLen > 0 && (idx = searchContent.indexOf(searchQuery, idx)) >= 0
                        && matchIndex < effectiveMaxResults) {
                    int previewStart = Math.max(0, idx - effectiveContextChars / 3);
                    int previewEnd = Math.min(content.length(),
                            idx + queryLen + effectiveContextChars * 2 / 3);
                    // 尽量在词边界截断
                    while (previewStart > 0 && content.charAt(previewStart) != '\n'
                            && content.charAt(previewStart) != ' ') previewStart--;
                    while (previewEnd < content.length() && content.charAt(previewEnd) != '\n'
                            && content.charAt(previewEnd) != ' ') previewEnd++;

                    String preview = (previewStart > 0 ? "..." : "")
                            + content.substring(previewStart, Math.min(previewEnd, content.length()))
                            + (previewEnd < content.length() ? "..." : "");

                    results.add(new ImportDocumentSearchMatch(
                            doc.documentId(), doc.source(), doc.displayName(),
                            idx, preview));
                    idx += queryLen;
                    matchIndex++;
                }
            } catch (Exception e) {
                log.debug("Search skip file {}: {}", doc.documentId(), e.getMessage());
            }
        }

        results.sort(Comparator.comparingInt(ImportDocumentSearchMatch::offset));
        if (results.size() > effectiveMaxResults) {
            return results.subList(0, effectiveMaxResults);
        }
        return results;
    }

    /** 路径安全解析 — 阻止 path traversal。 */
    Path resolveSafe(String documentId) throws ImportDocumentException {
        Path resolved = importDir.resolve(documentId).normalize();
        if (!resolved.startsWith(importDir)) {
            throw new ImportDocumentException("IMPORT_PATH_REJECTED",
                    "Path traversal rejected: " + documentId);
        }
        // 检查 symlink 不指向外部
        if (Files.isSymbolicLink(resolved)) {
            try {
                Path realTarget = resolved.toRealPath();
                if (!realTarget.startsWith(importDir.toRealPath())) {
                    throw new ImportDocumentException("IMPORT_PATH_REJECTED",
                            "Symlink target outside import dir: " + documentId);
                }
            } catch (IOException e) {
                throw new ImportDocumentException("IMPORT_PATH_REJECTED",
                        "Cannot resolve symlink: " + documentId);
            }
        }
        return resolved;
    }

    private boolean isAllowedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (name.endsWith("." + ext)) return true;
        }
        return false;
    }

    private String determineSource(Path file) {
        // web/ 子目录下的文件视为 WIKI_DOWNLOADED
        Path webDir = importDir.resolve("web").normalize();
        if (file.toAbsolutePath().normalize().startsWith(webDir)) {
            return SOURCE_WIKI_DOWNLOADED;
        }
        return SOURCE_LOCAL_IMPORT;
    }

    private ImportDocumentInfo toDocumentInfo(Path file) throws IOException {
        String relPath = importDir.relativize(file).toString();
        // Security: validate path before reading file metadata/content
        resolveSafe(relPath);
        long size = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        int charCount;
        try {
            charCount = readFileContent(file).length();
        } catch (Exception e) {
            charCount = -1;
        }
        return new ImportDocumentInfo(
                relPath, determineSource(file),
                file.getFileName().toString(), relPath,
                size, charCount, lastModified);
    }

    private String readFileContent(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 尝试 UTF-8 with BOM
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                    && bytes[2] == (byte) 0xBF) {
                return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            }
            throw e;
        }
    }

    // === Result records ===

    public record ImportDocumentInfo(
            String documentId, String source, String displayName,
            String relativePath, long sizeBytes, int charCount, long lastModified) {}

    public record ImportDocumentReadResult(
            String documentId, String source, String displayName,
            int originalLength, int offset, int limit,
            String returnedRange, boolean truncated,
            String nextOffset, String content) {}

    public record ImportDocumentSearchMatch(
            String documentId, String source, String displayName,
            int offset, String preview) {}

    /** Import 文档操作异常。 */
    public static class ImportDocumentException extends IOException {
        private final String errorCode;

        public ImportDocumentException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() { return errorCode; }
    }
}
