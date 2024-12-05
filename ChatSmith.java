import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import org.apache.commons.imaging.*;

import org.json.*;

import com.sun.net.httpserver.*;

public class ChatSmith {
    public static int port = 80;
    private static String accessToken = null;
    private static long tokenExpiration = 0;
    public static final int MAX_IMAGE_WIDTH = 2000;
    public static final int MAX_IMAGE_HEIGHT = 2000;
    private static final String VULCAN_APPLICATION_ID = "com.smartwidgetlabs.chatgpt";
    private static final String USER_AGENT = "Chat Smith Android, Version 3.9.9(696)";
    public static HttpServer createHttpServer(int initialPort) throws IOException {
        int port = initialPort;
        HttpServer server = null;

        while (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                System.out.println("Server started on port " + port);
                ChatSmith.port = port;
            } catch (BindException e) {
                if (port < 65535) {
                    System.out.println("Port " + port + " is already in use. Trying port " + (port + 1));
                    port++;
                } else {
                    System.err.println("All ports from " + initialPort + " to 65535 are in use. Exiting.");
                    System.exit(1);
                }
            }
        }
        return server;
    }

    public static void main(String[] args) {

        int port = 80;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpServer server = createHttpServer(port);
            server.createContext("/v1/chat/completions", new CompletionHandler());
            server.createContext("/v1/images/generations", new TextToImageHandler());
            server.createContext("/", new CompletionHandler());

            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }

    public static synchronized String[] getTokenArray() {
        try {
            URL url = new URL("https://api.vulcanlabs.co/smith-auth/api/v1/token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-Vulcan-Application-ID", VULCAN_APPLICATION_ID);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);

            JSONObject jsonInput = new JSONObject();
            jsonInput.put("device_id", "C8DC43F3FBE1ADB9");
            jsonInput.put("order_id", "");
            jsonInput.put("product_id", "");
            jsonInput.put("purchase_token", "");
            jsonInput.put("subscription_id", "");

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonInput.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream responseStream = connection.getInputStream();
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                    responseStream = new GZIPInputStream(responseStream);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                JSONObject jsonNode = new JSONObject(response.toString());
                String newAccessToken = jsonNode.getString("AccessToken");
                String expirationStr = jsonNode.getString("AccessTokenExpiration");
                long newExpiration = Instant.parse(expirationStr).getEpochSecond();
                return new String[]{newAccessToken, String.valueOf(newExpiration)};
            } else {
                System.err.println("Token 请求失败，响应码: " + responseCode);
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    System.err.println("错误响应体: " + errorResponse.toString());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static synchronized String getValidToken() {
        long currentTime = Instant.now().getEpochSecond();
        if (accessToken == null || currentTime >= tokenExpiration) {
            String[] tokenData = getTokenArray();
            if (tokenData != null) {
                accessToken = tokenData[0];
                tokenExpiration = Long.parseLong(tokenData[1]);
                System.out.println("Got new token: " + accessToken);
            } else {
                System.err.println("无法获取 token");
            }
        }
        return accessToken;
    }

    static class CompletionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            String requestMethod = exchange.getRequestMethod().toUpperCase();

            if (requestMethod.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equals(requestMethod)) {
                String response = "<html><head><title>Welcome to the ChatGPT API</title></head><body><h1>Welcome to the ChatGPT API</h1><p>This API is used to interact with the ChatGPT model. You can send messages to the model and receive responses.";

                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (!"POST".equals(requestMethod)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            InputStream is = exchange.getRequestBody();
            String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .reduce("", (acc, line) -> acc + line);

            System.out.println("Received JSON: " + requestBody);

            String token = getValidToken();
            if (token == null) {
                System.out.println("Failed to get a valid token.");
                sendError(exchange, "无法获取有效的 token");
                return;
            }

            try {
                JSONObject requestJson = new JSONObject(requestBody);

                boolean hasImageUrl = false;
                String visionApiResponse = null;
                byte[] imageData = null;
                String contentText = "";

                if (requestJson.has("messages")) {
                    JSONArray messages = requestJson.getJSONArray("messages");
                    Iterator<Object> iterator = messages.iterator();
                    while (iterator.hasNext()) {
                        JSONObject message = (JSONObject) iterator.next();
                        if (message.has("content")) {
                            Object contentObj = message.get("content");
                            if (contentObj instanceof JSONArray contentArray) {
                                StringBuilder contentBuilder = new StringBuilder();
                                for (int j = 0; j < contentArray.length(); j++) {
                                    JSONObject contentItem = contentArray.getJSONObject(j);
                                    if (contentItem.has("text")) {
                                        contentBuilder.append(contentItem.getString("text"));
                                    }
                                    if (contentItem.has("type") && "image_url".equals(contentItem.getString("type"))) {
                                        hasImageUrl = true;
                                        JSONObject imageUrlObj = contentItem.getJSONObject("image_url");
                                        String dataUrl = imageUrlObj.getString("url");
                                        if (dataUrl.startsWith("data:image/")) {
                                            imageData = decodeImageData(dataUrl);
                                        } else if (dataUrl.startsWith("http")) {
                                            imageData = downloadImageData(dataUrl);
                                        }
                                        if (imageData != null) {
                                            // 检查并缩放图像
                                            try {
                                                byte[] processedImageData = ImageUtils.checkAndScaleImage(imageData, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT);
                                                visionApiResponse = sendImagePostRequest(processedImageData, contentBuilder.toString());
                                            } catch (IOException e) {
                                                System.err.println("图像处理失败: " + e.getMessage());
                                                sendError(exchange, "图像处理失败: " + e.getMessage());
                                                return;
                                            }
                                        }
                                    }
                                    if (j < contentArray.length() - 1) {
                                        contentBuilder.append(" ");
                                    }
                                }
                                String extractedContent = contentBuilder.toString().trim();
                                if (extractedContent.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("Deleted message with empty content.");
                                } else {
                                    message.put("content", extractedContent);
                                    System.out.println("Extracted and replaced content: " + extractedContent);
                                    contentText = extractedContent;
                                }
                            } else if (contentObj instanceof String) {
                                String contentStr = ((String) contentObj).trim();
                                if (contentStr.isEmpty()) {
                                    iterator.remove();
                                    System.out.println("Deleted message with empty content.");
                                } else {
                                    message.put("content", contentStr);
                                    System.out.println("Retained content: " + contentStr);
                                    contentText = contentStr;
                                }
                            } else {
                                iterator.remove();
                                System.out.println("Deleted message with unexpected content type.");
                            }
                        }
                    }

                    if (messages.isEmpty()) {
                        sendError(exchange, "所有消息的内容均为空。");
                        return;
                    }
                }

                boolean isStream = requestJson.optBoolean("stream", false);
                if (hasImageUrl) {
                    if (visionApiResponse != null) {
                        JSONObject visionJson;
                        try {
                            visionJson = new JSONObject(visionApiResponse);
                        } catch (JSONException je) {
                            System.err.println("Vision API 返回的响应不是有效的 JSON.");
                            sendError(exchange, "Vision API 返回的响应不是有效的 JSON.");
                            return;
                        }

                        if (isStream) {
                            handleVisionStreamResponse(exchange, visionJson);
                        } else {
                            JSONObject openAIResponse = buildOpenAIResponse(visionJson);
                            String openAIResponseString = openAIResponse.toString();

                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, openAIResponseString.getBytes(StandardCharsets.UTF_8).length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(openAIResponseString.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        return;
                    } else {
                        sendError(exchange, "处理图像时发生错误。");
                        return;
                    }
                }

                if (isStream) {
                    requestJson.remove("stream");
                    System.out.println("Stream request detected. Removed 'stream' field.");
                }

                requestJson.put("nsfw_check", false);
                requestJson.put("model", "gpt-4o");
                if (requestJson.has("temperature")) {
                    double tempDouble = requestJson.getDouble("temperature");
                    int tempInt = (int) Math.round(tempDouble);
                    requestJson.put("temperature", tempInt);
                    System.out.println("Converted temperature from " + tempDouble + " to " + tempInt);
                } else {
                    requestJson.put("temperature", 1);
                    System.out.println("Temperature not provided, set to default value 1");
                }

                if (requestJson.has("top_p")) {
                    double topPDouble = requestJson.getDouble("top_p");
                    int topPInt = (int) Math.round(topPDouble);
                    requestJson.put("top_p", topPInt);
                    System.out.println("Converted top_p from " + topPDouble + " to " + topPInt);
                } else {
                    requestJson.put("top_p", 1);
                    System.out.println("Top_p not provided, set to default value 1");
                }

                System.out.println("Stream mode: " + isStream);

                String modifiedRequestBody = requestJson.toString();
                System.out.println("Modified JSON to send to API: " + modifiedRequestBody);

                if (isStream) {
                    handleStreamResponse(exchange, modifiedRequestBody, token);
                } else {
                    handleNormalResponse(exchange, modifiedRequestBody, token);
                }

            } catch (JSONException je) {
                je.printStackTrace();
                sendError(exchange, "JSON 解析错误: " + je.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, "内部服务器错误: " + e.getMessage());
            }
        }

        private void handleVisionStreamResponse(HttpExchange exchange, JSONObject visionJson) throws IOException {
            String assistantContent = "";
            System.out.println(visionJson.toString(4));
            if (visionJson.has("choices") && !visionJson.isNull("choices")) {
                JSONArray choices = visionJson.getJSONArray("choices");
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("Message");
                    if (message == null) continue;
                    if ("assistant".equalsIgnoreCase(message.optString("role", ""))) {
                        assistantContent = message.optString("content", "").trim();
                        break;
                    }
                }
            } else {
                System.err.println("Vision API 响应中缺少 'choices' 字段或其为 null.");
                sendError(exchange, "Vision API 响应中缺少 'choices' 字段或其为 null.");
                return;
            }

            if (assistantContent.isEmpty()) {
                System.out.println("Assistant 的 content 为空，发送 [DONE]。");
                sendDone(exchange);
                return;
            }

            System.out.println("Extracted assistant content: \n" + assistantContent);

            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
            responseHeaders.add("Cache-Control", "no-cache");
            responseHeaders.add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                JSONObject sseJson = new JSONObject();
                JSONArray choicesArray = new JSONArray();

                JSONObject choiceObj = new JSONObject();
                choiceObj.put("index", 0);

                JSONObject contentFilterResults = new JSONObject();
                contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                choiceObj.put("content_filter_results", contentFilterResults);

                JSONObject delta = new JSONObject();
                delta.put("content", assistantContent);
                choiceObj.put("delta", delta);

                choicesArray.put(choiceObj);
                sseJson.put("choices", choicesArray);

                sseJson.put("created", visionJson.optLong("created", Instant.now().getEpochSecond()));
                sseJson.put("id", visionJson.optString("id", ""));
                sseJson.put("model", visionJson.optString("model", "gpt-4o"));
                sseJson.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

                String sseLine = "data: " + sseJson.toString() + "\n\n";
                os.write(sseLine.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String doneLine = "data: [DONE]\n\n";
                os.write(doneLine.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        private JSONObject buildOpenAIResponse(JSONObject apiResponse) {
            JSONObject adjustedResponse = new JSONObject();
            adjustedResponse.put("id", apiResponse.optString("id", ""));
            adjustedResponse.put("object", apiResponse.optString("object", ""));
            adjustedResponse.put("created", apiResponse.optLong("created", Instant.now().getEpochSecond()));
            adjustedResponse.put("model", "gpt-4o-mini-2024-07-18");

            JSONObject usage = apiResponse.optJSONObject("usage");
            if (usage != null) {
                adjustedResponse.put("usage", usage);
            }

            JSONArray choicesArray = apiResponse.optJSONArray("choices");
            JSONArray adjustedChoices = new JSONArray();
            if (choicesArray != null) {
                for (int i = 0; i < choicesArray.length(); i++) {
                    JSONObject choice = choicesArray.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("Message");
                    if (message == null) continue;

                    String role = message.optString("role", "");
                    String content = message.optString("content", "").trim();
                    if (content.isEmpty()) continue;

                    JSONObject adjustedChoice = new JSONObject();
                    adjustedChoice.put("index", choice.optInt("index", i));

                    JSONObject contentFilterResults = new JSONObject();
                    contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                    contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                    contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                    adjustedChoice.put("content_filter_results", contentFilterResults);

                    adjustedChoice.put("finish_reason", choice.optString("finish_reason", ""));
                    JSONObject adjustedMessage = new JSONObject();
                    adjustedMessage.put("content", content);
                    adjustedMessage.put("role", role);
                    adjustedChoice.put("message", adjustedMessage);

                    adjustedChoices.put(adjustedChoice);
                }
            }
            adjustedResponse.put("choices", adjustedChoices);

            JSONArray promptFilterResults = new JSONArray();
            JSONObject promptFilterResult = new JSONObject();
            JSONObject promptContentFilterResults = new JSONObject();
            promptContentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptContentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
            promptFilterResult.put("content_filter_results", promptContentFilterResults);
            promptFilterResult.put("prompt_index", 0);
            promptFilterResults.put(promptFilterResult);
            adjustedResponse.put("prompt_filter_results", promptFilterResults);

            adjustedResponse.put("system_fingerprint", "fp_67802d9a6d");

            return adjustedResponse;
        }

        private byte[] decodeImageData(String dataUrl) {
            try {
                String[] parts = dataUrl.split(",");
                if (parts.length != 2) {
                    System.err.println("无效的 data URL 格式。");
                    return null;
                }
                String base64Data = parts[1];
                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                return imageBytes;
            } catch (IllegalArgumentException e) {
                System.err.println("Base64 解码失败: " + e.getMessage());
                return null;
            }
        }

        private byte[] downloadImageData(String imageUrl) {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();
                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    System.err.println("Failed to download image, response code: " + responseCode);
                    return null;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, n);
                }
                is.close();
                return baos.toByteArray();
            } catch (IOException e) {
                System.err.println("Failed to download image: " + e.getMessage());
                return null;
            }
        }

        private String sendImagePostRequest(byte[] imageData, String content) {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            String LINE_FEED = "\r\n";

            try {
                URL url = new URL("https://api.vulcanlabs.co/smith-v2/api/v7/vision_android");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("X-Auth-Token", "YOUR_AUTH_TOKEN");
                connection.setRequestProperty("Authorization", "Bearer " + getValidToken());
                connection.setRequestProperty("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Chat Smith Android, Version 3.9.5(669)");
                connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("Host", "api.vulcanlabs.co");
                connection.setRequestProperty("Connection", "Keep-Alive");
                connection.setDoOutput(true);
                connection.setUseCaches(false);

                // 获取图像格式
                ImageInfo imageInfo = Imaging.getImageInfo(imageData);
                ImageFormat format = imageInfo.getFormat();
                String formatName = format.getName().toLowerCase();
                String fileName = "image." + formatName;

                try (OutputStream outputStream = connection.getOutputStream();
                     DataOutputStream writer = new DataOutputStream(outputStream)) {

                    JSONObject dataObject = new JSONObject();
                    dataObject.put("model", "gpt-4o");
                    dataObject.put("user", "71D1A17A547F1E22");
                    dataObject.put("nsfw_check", false);

                    JSONArray messagesArray = new JSONArray();
                    JSONObject messageObject = new JSONObject();
                    messageObject.put("role", "user");
                    messageObject.put("content", content);
                    messagesArray.put(messageObject);

                    dataObject.put("messages", messagesArray);

                    String jsonData = dataObject.toString();

                    // 添加 JSON 数据部分
                    writer.writeBytes("--" + boundary + LINE_FEED);
                    writer.writeBytes("Content-Disposition: form-data; name=\"data\"" + LINE_FEED);
                    writer.writeBytes("Content-Type: application/json; charset=utf-8" + LINE_FEED);
                    writer.writeBytes(LINE_FEED);
                    writer.write(jsonData.getBytes(StandardCharsets.UTF_8));
                    writer.writeBytes(LINE_FEED);

                    // 添加图像部分
                    writer.writeBytes("--" + boundary + LINE_FEED);
                    writer.writeBytes("Content-Disposition: form-data; name=\"images[]\"; filename=\"" + fileName + "\"" + LINE_FEED);
                    writer.writeBytes("Content-Type: image/" + formatName + LINE_FEED);
                    writer.writeBytes(LINE_FEED);
                    writer.write(imageData);
                    writer.writeBytes(LINE_FEED);

                    writer.writeBytes("--" + boundary + "--" + LINE_FEED);
                    writer.flush();
                }

                int responseCode = connection.getResponseCode();
                InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                        connection.getInputStream() : connection.getErrorStream();

                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                    responseStream = new GZIPInputStream(responseStream);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                return response.toString();

            } catch (IOException e) {
                System.err.println("发送图像 POST 请求失败: " + e.getMessage());
                return null;
            } catch (ImageReadException e) {
                throw new RuntimeException(e);
            }
        }

        private void handleNormalResponse(HttpExchange exchange, String requestBody, String token) throws IOException {
            URL url = new URL("https://api.vulcanlabs.co/smith-v2/api/v7/chat_android");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-Auth-Token", "DaiExBn7Ib03PWRtbQu4HVQGUEGQKfA8GtrLN1oA8n4nOy9CdRu71OjKBwUZazZQxIgtCVQFCZtoBKgjuLVJpJTenTRjimRkaQUqZwtbXWjckIo3LeXut/Wslmkysgm9G0+lVxx38r0Eifu95+rIk5FMcZrQfZ+ubR0JkItOebU=");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Chat Smith Android, Version 3.9.5(669)");
            connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                    connection.getInputStream() : connection.getErrorStream();

            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                responseStream = new GZIPInputStream(responseStream);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            try {

                JSONObject apiResponse = new JSONObject(response.toString());
                System.out.println("Received Response from API: \n" + apiResponse.toString(4));


                if (apiResponse.has("error") && !apiResponse.isNull("error")) {
                    JSONObject errorObj = apiResponse.getJSONObject("error");
                    String errorMessage = errorObj.optString("message", "未知错误");
                    sendError(exchange, "API 错误: " + errorMessage);
                    return;
                }

                JSONObject adjustedResponse = buildOpenAIResponse(apiResponse);
                String adjustedResponseString = adjustedResponse.toString();
                System.out.println("adjustedResponseString:\n"+adjustedResponse.toString(4));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
//                exchange.sendResponseHeaders(200, adjustedResponseString.getBytes(StandardCharsets.UTF_8).length);
                exchange.sendResponseHeaders(200, apiResponse.toString().getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
//                os.write(adjustedResponseString.getBytes(StandardCharsets.UTF_8));
                    os.write(apiResponse.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (JSONException je) {
                System.err.println("目标 API 返回的响应不是有效的 JSON.");
                sendError(exchange, "目标 API 返回的响应不是有效的 JSON.");
                return;
            }
        }

        private void handleStreamResponse(HttpExchange exchange, String requestBody, String token) throws IOException {
            URL url = new URL("https://api.vulcanlabs.co/smith-v2/api/v7/chat_android");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-Auth-Token", "DaiExBn7Ib03PWRtbQu4HVQGUEGQKfA8GtrLN1oA8n4nOy9CdRu71OjKBwUZazZQxIgtCVQFCZtoBKgjuLVJpJTenTRjimRkaQUqZwtbXWjckIo3LeXut/Wslmkysgm9G0+lVxx38r0Eifu95+rIk5FMcZrQfZ+ubR0JkItOebU=");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-Vulcan-Application-ID", "com.smartwidgetlabs.chatgpt");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Chat Smith Android, Version 3.9.5(669)");
            connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                    connection.getInputStream() : connection.getErrorStream();

            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                responseStream = new GZIPInputStream(responseStream);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            System.out.println("Received Response from API: " + response.toString());

            JSONObject apiResponse;
            try {
                apiResponse = new JSONObject(response.toString());
            } catch (JSONException je) {
                System.err.println("目标 API 返回的响应不是有效的 JSON.");
                sendError(exchange, "目标 API 返回的响应不是有效的 JSON.");
                return;
            }

            if (apiResponse.has("error") && !apiResponse.isNull("error")) {
                JSONObject errorObj = apiResponse.getJSONObject("error");
                String errorMessage = errorObj.optString("message", "未知错误");
                sendError(exchange, "API 错误: " + errorMessage);
                return;
            }

            String assistantContent = "";
            if (apiResponse.has("choices") && !apiResponse.isNull("choices")) {
                JSONArray choices = apiResponse.getJSONArray("choices");
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject choice = choices.getJSONObject(i);
                    JSONObject message = choice.optJSONObject("Message");
                    if (message == null) continue;
                    if ("assistant".equalsIgnoreCase(message.optString("role", ""))) {
                        assistantContent = message.optString("content", "").trim();
                        break;
                    }
                }
            } else {
                System.err.println("API 响应中缺少 'choices' 字段或其为 null.");
                sendError(exchange, "API 响应中缺少 'choices' 字段或其为 null.");
                return;
            }

            if (assistantContent.isEmpty()) {
                System.out.println("Assistant 的 content 为空，发送 [DONE]。");
                sendDone(exchange);
                return;
            }

            System.out.println("Extracted assistant content: " + assistantContent);

            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8");
            responseHeaders.add("Cache-Control", "no-cache");
            responseHeaders.add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                JSONObject sseJson = new JSONObject();
                JSONArray choicesArray = new JSONArray();

                JSONObject choiceObj = new JSONObject();
                choiceObj.put("index", 0);

                JSONObject contentFilterOffsets = new JSONObject();
                contentFilterOffsets.put("check_offset", 790);
                contentFilterOffsets.put("start_offset", 740);
                contentFilterOffsets.put("end_offset", 846);
                choiceObj.put("content_filter_offsets", contentFilterOffsets);

                JSONObject contentFilterResults = new JSONObject();
                contentFilterResults.put("error", new JSONObject().put("code", "").put("message", ""));
                contentFilterResults.put("hate", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("self_harm", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("sexual", new JSONObject().put("filtered", false).put("severity", "safe"));
                contentFilterResults.put("violence", new JSONObject().put("filtered", false).put("severity", "safe"));
                choiceObj.put("content_filter_results", contentFilterResults);

                JSONObject delta = new JSONObject();
                delta.put("content", assistantContent);
                choiceObj.put("delta", delta);

                choicesArray.put(choiceObj);
                sseJson.put("choices", choicesArray);

                sseJson.put("created", apiResponse.optLong("created", Instant.now().getEpochSecond()));
                sseJson.put("id", apiResponse.optString("id", ""));
                sseJson.put("model", apiResponse.optString("model", "gpt-4o-mini-2024-07-18"));
                sseJson.put("system_fingerprint", "fp_67802d9a6d");

                String sseLine = "data: " + sseJson.toString() + "\n\n";
                os.write(sseLine.getBytes(StandardCharsets.UTF_8));
                os.flush();

                String doneLine = "data: [DONE]\n\n";
                os.write(doneLine.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        private void sendDone(HttpExchange exchange) throws IOException {
            OutputStream os = exchange.getResponseBody();
            String doneLine = "data: [DONE]\n\n";
            os.write(doneLine.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }

        private void sendError(HttpExchange exchange, String message) throws IOException {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
