package com.gsim.importdata;

import java.util.List;

/**
 * 导入结果。
 */
public record ImportResult(
        int totalFiles,
        int successCount,
        int failCount,
        List<String> successFiles,
        List<String> failFiles,
        String targetCollection,
        String logPath) {
    /**
     * 生成导入结果的摘要字符串。
     *
     * @return 包含导入统计信息的摘要字符串
     */
    public String summary() {
        return String.format(
                "Imported %d/%d files to %s. Failures: %d.", successCount, totalFiles, targetCollection, failCount);
    }
}
