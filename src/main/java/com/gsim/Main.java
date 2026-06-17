package com.gsim;

import com.gsim.app.GSimulatorApplication;

/**
 * GSimulator 主入口。
 * 只负责创建应用并启动，不包含业务逻辑。
 */
public class Main {

    public static void main(String[] args) {
        try {
            GSimulatorApplication app = new GSimulatorApplication();
            app.start();
        } catch (Exception e) {
            System.err.println("GSimulator failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
