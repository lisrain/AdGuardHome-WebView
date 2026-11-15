package org.adguardhome;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 从 AdGuardHome for Root 模块的配置文件里读出 HTTP 监听地址。
 * 用户改了监听端口或地址后，App 不需要手动跟着改。
 */
final class RootConfigReader {
    private static final String CONFIG_PATH = "/data/adb/agh/bin/AdGuardHome.yaml";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String DEFAULT_PORT = "3000";
    private static final long TIMEOUT_MS = 5000;

    interface Callback {
        /** 读取成功时返回可用的 URL，失败时返回 null。 */
        void onResult(String url);
    }

    private RootConfigReader() {
    }

    static void detect(Callback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String url = readAddress();
            mainHandler.post(() -> callback.onResult(url));
        }, "agh-config-reader").start();
    }

    private static String readAddress() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "cat " + CONFIG_PATH)
                    .redirectErrorStream(true)
                    .start();
            if (!awaitExit(process)) {
                return null;
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return parseAddress(output.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Android 上 Process.waitFor(timeout) 需要 API 26，这里用轮询兼容低版本。 */
    private static boolean awaitExit(Process process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        process.destroy();
        return false;
    }

    private static String parseAddress(String yaml) {
        boolean inHttpSection = false;
        for (String rawLine : yaml.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int indent = rawLine.indexOf(line);
            if (indent == 0) {
                inHttpSection = line.startsWith("http:");
                continue;
            }
            if (inHttpSection && line.startsWith("address:")) {
                String value = line.substring("address:".length()).trim()
                        .replace("\"", "")
                        .replace("'", "");
                return toUrl(value);
            }
        }
        return null;
    }

    private static String toUrl(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        int separator = address.lastIndexOf(':');
        String host = separator > 0 ? address.substring(0, separator) : address;
        String port = separator > 0 ? address.substring(separator + 1) : DEFAULT_PORT;
        if (!port.matches("\\d+")) {
            return null;
        }
        // 监听在通配地址上时，从本机访问要走回环地址
        if (host.isEmpty() || "0.0.0.0".equals(host)
                || "[::]".equals(host) || "::".equals(host) || "[::1]".equals(host)) {
            host = LOOPBACK;
        }
        return "http://" + host + ":" + port;
    }
}
