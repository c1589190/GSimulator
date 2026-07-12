package com.gsim.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * CLI 工具权限门禁 — 当工具需要确认时，在终端阻塞等待用户输入。
 *
 * <p>数字菜单模式：Y=允许一次, A=本轮全部允许, N=拒绝。
 * 默认 Y（直接回车），任何其他输入视为 N。
 */
public class CliToolPermissionGate implements ToolPermissionGate {

    private final PrintStream out;
    private final BufferedReader in;

    /** 使用 System.out 和 System.in。 */
    public CliToolPermissionGate() {
        this(System.out, new BufferedReader(new InputStreamReader(System.in)));
    }

    /** 注入自定义输出流和输入流（测试用）。 */
    public CliToolPermissionGate(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    @Override
    public ConfirmationChoice askConfirmation(ToolConfirmationRequest request) {
        out.println();
        out.println("╔══════════════════════════════════════╗");
        out.println("║  ⚠  工具确认请求                     ║");
        out.println("╠══════════════════════════════════════╣");
        out.printf ("║  工具: %-28s ║%n", truncate(request.toolName(), 28));
        out.printf ("║  分类: %-28s ║%n", request.category().name());
        out.printf ("║  原因: %-28s ║%n", truncate(request.reason(), 28));
        if (request.branchId() != null) {
            out.printf("║  分支: %-28s ║%n", truncate(request.branchId(), 28));
        }
        if (request.params() != null && !request.params().isEmpty()) {
            out.println("╠══════════════════════════════════════╣");
            out.println("║  参数:                               ║");
            request.params().forEach((k, v) ->
                    out.printf("║    %s=%s%n", truncate(k, 10), truncate(v, 20)));
        }
        out.println("╠══════════════════════════════════════╣");
        out.println("║  [Y] 允许一次                        ║");
        out.println("║  [A] 本轮全部允许（写入工具）         ║");
        out.println("║  [N] 拒绝                            ║");
        out.println("╚══════════════════════════════════════╝");
        out.print("  请选择 [Y/a/N]: ");
        out.flush();

        try {
            String line = in.readLine();
            if (line == null) {
                return ConfirmationChoice.DENY;
            }
            String choice = line.trim().toLowerCase();
            if (choice.isEmpty() || "y".equals(choice) || "yes".equals(choice)) {
                return ConfirmationChoice.ALLOW_ONCE;
            }
            if ("a".equals(choice) || "all".equals(choice)) {
                return ConfirmationChoice.ALLOW_ALL_THIS_TURN;
            }
            return ConfirmationChoice.DENY;
        } catch (Exception e) {
            return ConfirmationChoice.DENY;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
