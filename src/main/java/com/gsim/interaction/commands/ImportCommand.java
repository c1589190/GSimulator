package com.gsim.interaction.commands;

import com.gsim.app.AppConfig;
import com.gsim.importdata.ImportManager;
import com.gsim.importdata.ImportResult;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.webimport.WebImportManager;
import com.gsim.webimport.WebImportRequest;
import com.gsim.webimport.WebImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * /import 命令 — 支持本地文件导入和 URL 网页导入。
 *
 * 行为：
 * - /import              → 扫描 import/ 目录下的本地文件并入库
 * - /import URL          → 先抓网页生成 txt，再入库
 * - /import URL --fetch-only          → 只抓网页不入库
 * - /import URL --no-crawl            → 只抓当前页不递归
 * - /import URL --max-pages N --depth N --delay-ms N → 自定义爬取参数
 */
public class ImportCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(ImportCommand.class);

    private final AppConfig config;
    private final ImportManager importManager;
    private WebImportManager webImportManager;

    public ImportCommand(AppConfig config, ImportManager importManager) {
        this.config = config;
        this.importManager = importManager;
        this.webImportManager = new WebImportManager(config.getImportDir());
    }

    @Override
    public String name() {
        return "import";
    }

    @Override
    public String description() {
        return "导入资料到知识库。无参数时导入 import/ 目录下的本地文件。" +
                "传入 URL 时抓取网页并导入。";
    }

    @Override
    public String usage() {
        return "/import                              — 导入本地文件\n" +
                "/import <URL>                        — 抓取网页并导入\n" +
                "/import <URL> --fetch-only           — 只抓网页不入库\n" +
                "/import <URL> --no-crawl             — 只抓当前页不递归\n" +
                "/import <URL> --max-pages N          — 最多抓取 N 页 (默认 50)\n" +
                "/import <URL> --depth N              — 递归深度 (默认 2)\n" +
                "/import <URL> --delay-ms N           — 请求间隔毫秒 (默认 1000)";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        // 无参数：本地导入
        if (args == null || args.length == 0 || args[0].isBlank()) {
            return doLocalImport();
        }

        String firstArg = args[0];

        // 如果是 URL（以 http:// 或 https:// 开头）
        if (firstArg.startsWith("http://") || firstArg.startsWith("https://")) {
            return doWebImport(args);
        }

        // 其他情况：把整个参数当作本地文件路径处理
        // 暂不支持，返回错误
        return InteractionResult.fail("Unknown argument: " + firstArg +
                " — use /import for local files or /import <URL> for web import.");
    }

    private InteractionResult doLocalImport() {
        ImportResult result = importManager.doImport();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 本地导入结果 ===\n");
        sb.append(result.summary()).append("\n");
        sb.append("Target collection: ").append(result.targetCollection()).append("\n");
        if (!result.successFiles().isEmpty()) {
            sb.append("Success:\n");
            result.successFiles().forEach(f -> sb.append("  ✓ ").append(f).append("\n"));
        }
        if (!result.failFiles().isEmpty()) {
            sb.append("Failed:\n");
            result.failFiles().forEach(f -> sb.append("  ✗ ").append(f).append("\n"));
        }
        return InteractionResult.ok(result.logPath(), sb.toString());
    }

    private InteractionResult doWebImport(String[] args) {
        // CommandParser gives us the entire remaining string as args[0]
        // (because /import uses the same parsing rule as /run and /searchdb)
        // So we need to split by whitespace ourselves
        String[] tokens = args[0].split("\\s+");
        String urlStr = tokens[0];

        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (Exception e) {
            return InteractionResult.fail("Invalid URL: " + urlStr);
        }

        // 解析参数
        WebImportRequest.Builder builder = WebImportRequest.builder(uri)
                .userAgent(config.getWebResearchUserAgent())
                .timeoutSeconds(config.getWebResearchTimeoutSeconds());

        // 解析 flags (from tokens[1] onwards)
        for (int i = 1; i < tokens.length; i++) {
            String arg = tokens[i];
            switch (arg) {
                case "--fetch-only":
                    builder.fetchOnly(true);
                    break;
                case "--no-crawl":
                    builder.crawlEnabled(false);
                    break;
                case "--max-pages":
                    if (i + 1 < tokens.length) {
                        try {
                            builder.maxPages(Integer.parseInt(tokens[++i]));
                        } catch (NumberFormatException e) {
                            return InteractionResult.fail("Invalid --max-pages value: " + tokens[i]);
                        }
                    }
                    break;
                case "--depth":
                    if (i + 1 < tokens.length) {
                        try {
                            builder.maxDepth(Integer.parseInt(tokens[++i]));
                        } catch (NumberFormatException e) {
                            return InteractionResult.fail("Invalid --depth value: " + tokens[i]);
                        }
                    }
                    break;
                case "--delay-ms":
                    if (i + 1 < tokens.length) {
                        try {
                            builder.delayMillis(Long.parseLong(tokens[++i]));
                        } catch (NumberFormatException e) {
                            return InteractionResult.fail("Invalid --delay-ms value: " + tokens[i]);
                        }
                    }
                    break;
                default:
                    // 未知 flag，忽略
                    log.warn("Unknown flag: {}", arg);
            }
        }

        // --no-crawl 等价于 --max-pages 1 --depth 0
        if (!builder.build().crawlEnabled()) {
            builder.maxPages(1).maxDepth(0);
        }

        WebImportRequest request = builder.build();

        // 执行 web import
        if (webImportManager == null) {
            webImportManager = new WebImportManager(config.getImportDir());
        }

        WebImportResult result = webImportManager.execute(request);

        // 格式化输出
        StringBuilder sb = new StringBuilder();
        sb.append("=== Web 导入结果 ===\n");
        sb.append("URL: ").append(result.url()).append("\n");
        sb.append("Host: ").append(result.host()).append("\n");
        sb.append("Crawler: ").append(result.crawlerName()).append("\n");
        sb.append("Pages fetched: ").append(result.pagesFetched()).append("\n");
        sb.append("Pages failed: ").append(result.pagesFailed()).append("\n");
        sb.append("Files written: ").append(result.filesWritten()).append("\n");
        sb.append("Fetch-only mode: ").append(result.fetchOnly()).append("\n");

        if (!result.writtenFiles().isEmpty()) {
            sb.append("\nWritten files:\n");
            result.writtenFiles().forEach(f -> sb.append("  📄 ").append(f).append("\n"));
        }

        if (!result.errors().isEmpty()) {
            sb.append("\nErrors:\n");
            result.errors().forEach(e -> sb.append("  ⚠ ").append(e).append("\n"));
        }

        // 如果非 fetch-only 且有写入文件，继续本地入库
        if (!result.fetchOnly() && result.filesWritten() > 0) {
            sb.append("\n>>> 开始入库...\n");
            ImportResult importResult = importManager.importSpecificFiles(result.writtenFiles());
            sb.append(importResult.summary()).append("\n");
        }

        return InteractionResult.ok(result.summary(), sb.toString(), result.writtenFiles().stream().map(Path::toString).toList());
    }
}
