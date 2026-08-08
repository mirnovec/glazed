package com.nnpg.glazed.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

// one webhook sender for everything. every module used to have its own broken copy
public final class GlazedWebhook {

    public static final int COLOR_GLAZED = 0x7600FF;

    private static final Logger LOG = LoggerFactory.getLogger("Glazed");
    private static final String DEFAULT_AVATAR = "https://i.imgur.com/OL2y1cr.png";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            // daemon so a stuck webhook cant keep the game alive
            Thread thread = new Thread(r, "Glazed-Webhook-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .executor(EXECUTOR)
        .build();

    private GlazedWebhook() {}

    public static Builder to(String url) {
        return new Builder(url);
    }

    // is this actually a discord webhook url
    public static boolean isValidUrl(String url) {
        if (url == null) return false;

        String trimmed = url.trim();
        return trimmed.startsWith("https://discord.com/api/webhooks/")
            || trimmed.startsWith("https://discordapp.com/api/webhooks/")
            || trimmed.startsWith("https://canary.discord.com/api/webhooks/")
            || trimmed.startsWith("https://ptb.discord.com/api/webhooks/");
    }

    public static final class Builder {
        private final String url;
        private final JsonObject embed = new JsonObject();
        private final JsonArray fields = new JsonArray();

        private String username = "Glazed";
        private String avatarUrl = DEFAULT_AVATAR;
        private String content = "";
        private Path imageFile;
        private Consumer<String> errorHandler;

        private Builder(String url) {
            this.url = url == null ? "" : url.trim();
            this.embed.addProperty("color", COLOR_GLAZED);
            this.embed.addProperty("timestamp", Instant.now().toString());

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "Sent by Glazed");
            this.embed.add("footer", footer);
        }

        public Builder username(String username) {
            if (username != null && !username.isBlank()) this.username = username;
            return this;
        }

        public Builder avatar(String avatarUrl) {
            if (avatarUrl != null && !avatarUrl.isBlank()) this.avatarUrl = avatarUrl;
            return this;
        }

        public Builder title(String title) {
            this.embed.addProperty("title", title);
            return this;
        }

        public Builder description(String description) {
            this.embed.addProperty("description", description);
            return this;
        }

        public Builder color(int color) {
            this.embed.addProperty("color", color);
            return this;
        }

        public Builder thumbnail(String url) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", url);
            this.embed.add("thumbnail", thumbnail);
            return this;
        }

        public Builder footer(String text) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", text);
            this.embed.add("footer", footer);
            return this;
        }

        public Builder field(String name, String value, boolean inline) {
            JsonObject field = new JsonObject();
            field.addProperty("name", name);
            field.addProperty("value", value);
            field.addProperty("inline", inline);
            this.fields.add(field);
            return this;
        }

        // pings you, does nothing if the id is blank
        public Builder ping(String userId) {
            if (userId != null && !userId.trim().isEmpty()) {
                this.content = "<@" + userId.trim().replaceAll("[^0-9]", "") + ">";
            }
            return this;
        }

        public Builder image(Path imageFile) {
            this.imageFile = imageFile;
            return this;
        }

        // runs on the main thread so modules can print to chat safely
        public Builder onError(Consumer<String> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public CompletableFuture<Boolean> send() {
            if (!isValidUrl(url)) {
                fail("Webhook URL is not a valid Discord webhook");
                return CompletableFuture.completedFuture(false);
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("username", username);
            payload.addProperty("avatar_url", avatarUrl);
            payload.addProperty("content", content);

            if (fields.size() > 0) embed.add("fields", fields);

            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            return CompletableFuture
                .supplyAsync(() -> dispatch(payload), EXECUTOR)
                .exceptionally(throwable -> {
                    fail(throwable.getMessage());
                    return false;
                });
        }

        private boolean dispatch(JsonObject payload) {
            for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
                try {
                    HttpResponse<String> response = imageFile != null
                        ? postMultipart(payload)
                        : postJson(payload);

                    int status = response.statusCode();
                    if (status >= 200 && status < 300) return true;

                    // 429 tells us how long to wait, anything else isnt worth retrying.
                    // discord will still rate limit you into oblivion if you spam finds, thats on you
                    if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                        Thread.sleep(retryAfterMillis(response));
                        continue;
                    }

                    fail("Webhook rejected with HTTP " + status);
                    return false;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    fail(e.getMessage());
                    return false;
                }
            }

            fail("Webhook rate limited, giving up");
            return false;
        }

        private HttpResponse<String> postJson(JsonObject payload) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> postMultipart(JsonObject payload) throws IOException, InterruptedException {
            String boundary = "GlazedBoundary" + System.nanoTime();
            String fileName = imageFile.getFileName().toString();

            List<byte[]> parts = new ArrayList<>();
            parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + payload + "\r\n").getBytes(StandardCharsets.UTF_8));

            parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(Files.readAllBytes(imageFile));
            parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            int total = parts.stream().mapToInt(part -> part.length).sum();
            byte[] body = new byte[total];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, body, offset, part.length);
                offset += part.length;
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private long retryAfterMillis(HttpResponse<String> response) {
            return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return (long) (Double.parseDouble(value) * 1000L);
                    } catch (NumberFormatException e) {
                        return 1000L;
                    }
                })
                .orElse(1000L);
        }

        private void fail(String message) {
            String text = message == null ? "unknown error" : message;
            LOG.warn("Webhook send failed: {}", text);

            if (errorHandler == null) return;

            // chat has to be on the main thread
            Minecraft.getInstance().execute(() -> errorHandler.accept(text));
        }
    }
}
