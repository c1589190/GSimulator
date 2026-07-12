package com.gsim.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件搜索服务 — 在指定目录下的 txt 文件中进行关键词匹配搜索。
 *
 * 文件格式要求（由 WebImportFileWriter 生成）：
 * <pre>
 * # Title
 * Source URL: ...
 * ...
 * ---
 * content...
 * </pre>
 */
public class LocalFileSearchService {

    private final Path rootDir;

    public LocalFileSearchService(Path rootDir) {
        this.rootDir = rootDir;
    }

    /**
     * 在 rootDir 下递归搜索所有 txt 文件。
     *
     * @param keyword   搜索关键词
     * @param maxResults 最大返回结果数
     * @return 按 score 降序排列的结果列表
     */
    public List<ToolResult.Item> search(String keyword, int maxResults) {
        List<ToolResult.Item> results = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();

        if (!Files.isDirectory(rootDir)) {
            return results;
        }

        try (Stream<Path> files = Files.walk(rootDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .forEach(file -> {
                        try {
                            ToolResult.Item item = searchFile(file, lowerKeyword, keyword);
                            if (item != null) {
                                results.add(item);
                            }
                        } catch (Exception ignored) {
                            // skip unreadable files
                        }
                    });
        } catch (IOException ignored) {
            // skip
        }

        results.sort(Comparator.comparingDouble(ToolResult.Item::score).reversed());
        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    private ToolResult.Item searchFile(Path file, String lowerKeyword, String originalKeyword) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String lowerContent = content.toLowerCase();

        // 解析标题（首行 # Title）
        String title = extractTitle(content);

        // 计算 score：标题匹配权重更高
        double score = 0;
        int titleHits = countMatches(title.toLowerCase(), lowerKeyword);
        int contentHits = countMatches(lowerContent, lowerKeyword);

        if (contentHits == 0) return null;

        score = titleHits * 10.0 + contentHits * 1.0;

        // 提取 snippet
        String body = extractBody(content);
        String snippet = buildSnippet(body, lowerKeyword, 300);

        // 相对路径
        String relativePath = rootDir.relativize(file).toString();

        return new ToolResult.Item(title, relativePath, snippet, score);
    }

    private String extractTitle(String content) {
        int newline = content.indexOf('\n');
        if (newline > 0) {
            String firstLine = content.substring(0, newline).trim();
            if (firstLine.startsWith("# ")) {
                return firstLine.substring(2).trim();
            }
        }
        return "Untitled";
    }

    private String extractBody(String content) {
        int sep = content.indexOf("\n---\n");
        if (sep >= 0) {
            return content.substring(sep + 5);
        }
        int sep2 = content.indexOf("\n---");
        if (sep2 >= 0) {
            return content.substring(sep2 + 4);
        }
        // no separator, return all after first line
        int nl = content.indexOf('\n');
        return nl >= 0 ? content.substring(nl + 1) : content;
    }

    private int countMatches(String text, String keyword) {
        if (keyword.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }

    private String buildSnippet(String body, String lowerKeyword, int maxLen) {
        if (body.isEmpty() || lowerKeyword.isEmpty()) {
            return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        }

        String lowerBody = body.toLowerCase();
        int pos = lowerBody.indexOf(lowerKeyword);
        if (pos < 0) {
            return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
        }

        int start = Math.max(0, pos - maxLen / 3);
        int end = Math.min(body.length(), pos + maxLen * 2 / 3);

        // 尽量在词边界截断
        while (start > 0 && body.charAt(start) != '\n' && body.charAt(start) != ' ') start--;
        while (end < body.length() && body.charAt(end) != '\n' && body.charAt(end) != ' ') end++;

        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("...");
        String fragment = body.substring(start, Math.min(end, body.length()));
        // 高亮关键词
        String highlighted = highlight(fragment, lowerKeyword);
        sb.append(highlighted);
        if (end < body.length()) sb.append("...");

        String result = sb.toString();
        if (result.length() > maxLen + 20) {
            result = result.substring(0, maxLen) + "...";
        }
        return result;
    }

    /**
     * 用 ANSI 粗体高亮关键词（CLI 友好）。
     */
    private String highlight(String text, String lowerKeyword) {
        if (lowerKeyword.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        String lower = text.toLowerCase();
        int idx = 0;
        while (idx < text.length()) {
            int found = lower.indexOf(lowerKeyword, idx);
            if (found < 0) {
                sb.append(text.substring(idx));
                break;
            }
            sb.append(text, idx, found);
            sb.append("\033[1m"); // bold
            sb.append(text, found, found + lowerKeyword.length());
            sb.append("\033[0m"); // reset
            idx = found + lowerKeyword.length();
        }
        return sb.toString();
    }
}
