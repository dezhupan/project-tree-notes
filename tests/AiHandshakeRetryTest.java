package com.projecttreenotes.plugin.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;

public final class AiHandshakeRetryTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String result = AiHandshakeRetry.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw handshake();
            return "成功";
        });
        check(result.equals("成功") && attempts.get() == 3, "recovers after two handshake failures");
        expectAttempts(handshake(), 3);
        expectAttempts(new IOException(handshake()), 3);
        expectAttempts(new HttpTimeoutException("timeout"), 1);
        expectAttempts(new IOException("AI 服务返回 HTTP 401"), 1);
        expectAttempts(new IOException("AI 服务返回 HTTP 429"), 1);
        expectAttempts(new IOException("Connection reset"), 1);
        expectAttempts(new SSLHandshakeException("PKIX path building failed"), 1);
        SSLHandshakeException certificate = handshake();
        certificate.initCause(new CertificateException("invalid certificate"));
        expectAttempts(certificate, 1);
        Thread.currentThread().interrupt();
        try {
            AiHandshakeRetry.execute(() -> { throw new AssertionError("request after interrupt"); });
            throw new AssertionError("interrupt ignored");
        } catch (InterruptedException expected) {
            Thread.interrupted();
        }
        Thread cancel = new Thread(() -> {
            try {
                AiHandshakeRetry.execute(() -> {
                    Thread.currentThread().interrupt();
                    throw handshake();
                });
                throw new AssertionError("backoff ignored interruption");
            } catch (InterruptedException expected) {
                attempts.set(99);
            } catch (IOException failure) { throw new AssertionError(failure); }
        });
        cancel.start();
        cancel.join(3000);
        check(!cancel.isAlive() && attempts.get() == 99, "cancel during backoff");
        check(AiClient.readableFailure(handshake()).contains("HTTPS"), "readable handshake failure");

        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = body.contains("reject-me") ? 401 : 200;
            byte[] response = (status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"标签结果\"}}]}"
                    : "unauthorized").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiClient client = new AiClient();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            for (String prompt : new String[]{"src/alpha.py", "目录 src: alpha.py, beta.py", "中文目录/工具.py"}) {
                check(client.request(url, "", "test", 10, prompt).equals("标签结果"), "HTTP response parsed");
            }
            try {
                client.request(url, "", "test", 10, "reject-me");
                throw new AssertionError("401 accepted");
            } catch (IOException expected) {
                check(expected.getMessage().contains("401"), "401 retained");
            }
            check(requests.get() == 4, "no duplicate normal or HTTP error requests");
        } finally { server.stop(0); }
        System.out.println("AiHandshakeRetryTest: PASS (retry bounds, certificate/HTTP exclusions, cancellation, local HTTP)");
    }

    private static SSLHandshakeException handshake() {
        return new SSLHandshakeException("Remote host terminated the handshake");
    }

    private static void expectAttempts(IOException failure, int expected) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try {
            AiHandshakeRetry.execute(() -> { count.incrementAndGet(); throw failure; });
            throw new AssertionError("failure swallowed");
        } catch (IOException actual) {
            check(actual == failure && count.get() == expected, "failure preserved, attempts=" + expected);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
