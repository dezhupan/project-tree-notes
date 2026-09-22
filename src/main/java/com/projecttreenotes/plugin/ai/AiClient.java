package com.projecttreenotes.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

public final class AiClient {
    private static final Pattern HTTP_STATUS = Pattern.compile("(?:AI 服务返回 )?HTTP\\s+(\\d{3})");
    private final HttpClient client = createClient();

    public String request(String baseUrl,
                          String apiKey,
                          String model,
                          int timeoutSeconds,
                          String prompt) throws IOException, InterruptedException {
        URI endpoint = endpoint(baseUrl);
        validateTransport(endpoint, apiKey);
        if (model == null || model.isBlank()) throw new IllegalArgumentException("尚未填写模型名称");
        boolean ollama = isOllama(endpoint);
        boolean local = isLoopback(endpoint.getHost());
        if (!ollama && !local && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("尚未配置 API Key，请先打开设置 | 工具 | 项目中文说明");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model.trim());
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "你只根据用户提供的项目证据生成简体中文项目树短标签，并严格输出 JSON。");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);
        if (!ollama && model.trim().toLowerCase().startsWith("deepseek-v4-")) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!ollama && apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpResponse<String> response = AiHandshakeRetry.execute(() -> client.send(request.build(), HttpResponse.BodyHandlers.ofString()));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI 服务返回 HTTP " + response.statusCode() + "：" + compact(response.body(), 240));
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (ollama) return required(json.getAsJsonObject("message"), "content");
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new IOException("AI 响应缺少 choices");
        return required(choices.get(0).getAsJsonObject().getAsJsonObject("message"), "content");
    }

    public static String configurationError(String baseUrl, String apiKey, String model) {
        if (baseUrl == null || baseUrl.isBlank()) return "请填写 API 地址";
        URI uri;
        try {
            uri = endpoint(baseUrl);
        } catch (RuntimeException invalid) {
            return "API 地址格式不正确";
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return "API 地址必须以 http:// 或 https:// 开头";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) return "API 地址缺少服务器域名";
        if (model == null || model.isBlank()) return "请选择或填写模型名称";
        if (!isLoopback(uri.getHost()) && (apiKey == null || apiKey.isBlank())) return "请填写 API Key";
        try {
            validateTransport(uri, apiKey);
        } catch (IllegalArgumentException insecure) {
            return insecure.getMessage();
        }
        return "";
    }

    private static HttpClient createClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        try {
            ProxySelector proxy = ProxySelector.getDefault();
            if (proxy != null) builder.proxy(proxy);
            Authenticator authenticator = Authenticator.getDefault();
            if (authenticator != null) builder.authenticator(authenticator);
        } catch (RuntimeException ignored) {
            // Restricted environments safely fall back to a direct connection.
        }
        return builder.build();
    }

    public static String readableFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof HttpTimeoutException) return "请求超时，请检查网络或换用更快的模型";
        if (cause instanceof UnknownHostException) return "找不到 API 服务器，请检查地址和网络";
        if (cause instanceof ConnectException) return "无法连接 API，请检查网络或 PyCharm 代理设置";
        if (cause instanceof SSLException) return "HTTPS 握手失败，请检查代理、证书或 API 服务连接";
        String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        Matcher status = HTTP_STATUS.matcher(message);
        if (status.find()) {
            return switch (Integer.parseInt(status.group(1))) {
                case 400 -> "请求参数或模型名称不正确";
                case 401 -> "API Key 无效";
                case 402 -> "API 账户余额不足";
                case 403 -> "API Key 没有调用权限";
                case 404 -> "API 地址或模型名称不正确";
                case 408 -> "API 服务响应超时";
                case 429 -> "请求过于频繁，请稍后再试";
                default -> status.group(1).startsWith("5") ? "API 服务暂时不可用" : "API 返回 HTTP " + status.group(1);
            };
        }
        if (message.contains("choices") || message.contains("Json")) return "接口返回格式不兼容";
        return message.isBlank() ? "网络或接口异常" : compact(message, 120);
    }

    static URI endpoint(String configured) {
        String base = configured == null ? "" : configured.trim().replaceAll("/+$", "");
        if (base.isBlank()) throw new IllegalArgumentException("尚未填写 API 地址");
        URI uri = URI.create(base);
        if (isOllama(uri)) return URI.create(base.endsWith("/api/chat") ? base : base + "/api/chat");
        return URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
    }

    private static void validateTransport(URI endpoint, String apiKey) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        if (scheme == null || host == null) throw new IllegalArgumentException("API 地址无效");
        boolean local = isLoopback(host);
        if (!"https".equalsIgnoreCase(scheme) && !local && apiKey != null && !apiKey.isBlank()) {
            throw new IllegalArgumentException("为防止 API Key 泄露，非本机服务必须使用 HTTPS");
        }
    }

    private static boolean isOllama(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String host = uri.getHost() == null ? "" : uri.getHost();
        return path.endsWith("/api/chat")
                || (uri.getPort() == 11434 && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")));
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
    }

    private static String required(JsonObject object, String name) throws IOException {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            throw new IOException("AI 响应缺少 " + name);
        }
        String value = object.get(name).getAsString().trim();
        if (value.isBlank()) throw new IOException("AI 返回了空内容");
        return value;
    }

    private static String compact(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}

