package com.aron.autobill.selftest;

import com.aron.autobill.core.UpdateChecker;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * 真 HTTP 的下载考卷。
 *
 * 为什么单独一份：UpdateSelftest 用 file:// 当服务器，走的是另一条分支，
 * 把 HTTP 那半边的代码（Range 头、206/416 判定、302 跟随、200 回退）
 * 全都绕过去了。这些恰恰是断点续传在真机上唯一会走的路。
 *
 * 服务器用 JDK 自带的 com.sun.net.httpserver 起在 127.0.0.1 的随机端口上，
 * 不联网、不依赖外部服务，跑完就停。
 */
public class UpdateHttpSelftest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  -> " + detail); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        ok(name, c, "期望=" + want + " 实际=" + got);
    }

    /** 1MB 测试载荷，头四字节是 zip 魔数（否则 promote 会拒绝） */
    static byte[] payload() {
        byte[] b = new byte[1024 * 1024];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i % 251);
        b[0] = 'P'; b[1] = 'K'; b[2] = 3; b[3] = 4;
        return b;
    }

    /**
     * @param rangeSupport false 模拟「不认 Range 的老服务器」：忽略 Range 头，一律回 200 全量
     */
    static HttpServer start(byte[] body, boolean rangeSupport) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        s.createContext("/apk", ex -> {
            try {
                String range = ex.getRequestHeaders().getFirst("Range");
                if (range != null && rangeSupport) {
                    long from = Long.parseLong(range.substring("bytes=".length(), range.indexOf('-')));
                    if (from >= body.length) {
                        ex.sendResponseHeaders(416, -1);   // .part 已到末尾
                        return;
                    }
                    ex.getResponseHeaders().add("Content-Range",
                            "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
                    ex.sendResponseHeaders(206, body.length - from);
                    ex.getResponseBody().write(body, (int) from, body.length - (int) from);
                } else {
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                }
            } finally {
                ex.close();
            }
        });

        // GitHub Release 资产一律 302 到 CDN，跟随重定向必须验证
        s.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/apk");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        s.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });

        s.start();
        return s;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 更新下载 HTTP 考卷 ===");
        byte[] body = payload();
        HttpServer s = start(body, true);
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            testFull(base, body);
            testResume(base, body);
            testPartAlreadyComplete(base, body);
            testRedirect(base, body);
            test404(base);
        } finally {
            s.stop(0);
        }

        // 服务器不认 Range 的那条路要换一台服务器
        HttpServer s2 = start(body, false);
        try {
            testNoRangeSupport("http://127.0.0.1:" + s2.getAddress().getPort(), body);
        } finally {
            s2.stop(0);
        }

        System.out.println();
        System.out.println("==== HTTP 下载自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    static void testFull(String base, byte[] body) throws Exception {
        System.out.println("\n【整包下载】");
        File dir = tempDir();
        File dest = new File(dir, "a.apk");
        long n = UpdateChecker.download(base + "/apk", dest, null, 8000, null);
        eq("返回字节数", (long) body.length, n);
        ok("promote 通过", UpdateChecker.promote(dest, UpdateChecker.sha256Hex(new File(dir, "a.apk.part"))),
                "失败");
        ok("内容一致", Arrays.equals(body, java.nio.file.Files.readAllBytes(dest.toPath())), "内容不一致");
    }

    static void testResume(String base, byte[] body) throws Exception {
        System.out.println("\n【断点续传（服务器回 206）】");
        File dir = tempDir();
        File dest = new File(dir, "b.apk");
        File part = new File(dest.getPath() + ".part");

        // 造半截文件：前 400KB 与源一致
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(body, 0, 400 * 1024);
        }
        final boolean[] got206 = {false};
        long n = UpdateChecker.download(base + "/apk", dest, (r, t) -> {
            if (t == body.length) got206[0] = true;
        }, 8000, null);

        eq("续传后总字节数", (long) body.length, n);
        ok("进度总数对齐（说明拿的是 206 的续传长度）", got206[0], "进度回调没收到正确 total");
        ok("续传内容逐字节一致", Arrays.equals(body, java.nio.file.Files.readAllBytes(part.toPath())),
                "拼接错位");
    }

    /**
     * .part 已经完整（上次写完了、但 promote 前进程被杀）。
     * 这时 Range 从 EOF 开始，服务器回 416 —— 旧代码把 416 直接抛给用户，
     * .part 又一直留着，于是每次重试都是同一个错，除了清数据没出路。
     */
    static void testPartAlreadyComplete(String base, byte[] body) throws Exception {
        System.out.println("\n【.part 已完整 → 服务器 416】");
        File dir = tempDir();
        File dest = new File(dir, "c.apk");
        File part = new File(dest.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(body);
        }
        eq("构造完整 .part", (long) body.length, part.length());

        boolean threw = false;
        long n = -1;
        try {
            n = UpdateChecker.download(base + "/apk", dest, null, 8000, null);
        } catch (Exception e) {
            threw = true;
            System.out.println("       异常：" + e.getMessage());
        }
        ok("416 不再让更新彻底卡死", !threw, "仍然抛异常");
        if (!threw) {
            eq("自动清空重下后字节数正确", (long) body.length, n);
            ok("重下内容一致", Arrays.equals(body, java.nio.file.Files.readAllBytes(part.toPath())),
                    "内容不一致");
        }
    }

    static void testRedirect(String base, byte[] body) throws Exception {
        System.out.println("\n【302 重定向】");
        File dir = tempDir();
        File dest = new File(dir, "d.apk");
        long n = UpdateChecker.download(base + "/redirect", dest, null, 8000, null);
        eq("跟随 302 后字节数", (long) body.length, n);
    }

    static void test404(String base) throws Exception {
        System.out.println("\n【错误响应】");
        File dir = tempDir();
        File dest = new File(dir, "e.apk");
        String msg = null;
        try {
            UpdateChecker.download(base + "/missing", dest, null, 8000, null);
        } catch (Exception e) {
            msg = String.valueOf(e.getMessage());
        }
        ok("404 抛异常且带状态码（UI 才能翻成人话）",
                msg != null && msg.contains("404"), "message=" + msg);
    }

    /** 老服务器不认 Range：必须丢掉半截文件重下，不能在后面追加 */
    static void testNoRangeSupport(String base, byte[] body) throws Exception {
        System.out.println("\n【服务器不支持 Range（回 200）】");
        File dir = tempDir();
        File dest = new File(dir, "f.apk");
        File part = new File(dest.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(body, 0, 300 * 1024);
        }
        long n = UpdateChecker.download(base + "/apk", dest, null, 8000, null);
        eq("总字节数不翻倍", (long) body.length, n);
        eq("文件长度不翻倍", (long) body.length, part.length());
        ok("内容逐字节一致", Arrays.equals(body, java.nio.file.Files.readAllBytes(part.toPath())),
                "把整包追加到半截文件后面了");
    }

    static File tempDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "autobill_http_" + System.nanoTime());
        dir.mkdirs();
        dir.deleteOnExit();
        return dir;
    }
}
