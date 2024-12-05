import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class TextToImageHandler implements HttpHandler {
    private static final String VULCAN_API_URI = "https://api.vulcanlabs.co/smith-v2/api/v1/text2image";
    private static final String VULCAN_APPLICATION_ID = "com.smartwidgetlabs.chatgpt";
    private static final String USER_AGENT = "Chat Smith Android, Version 3.9.9(696)";

    // Token management variables
    private static String accessToken = null;
    private static long tokenExpiration = 0;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Set CORS headers
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String requestMethod = exchange.getRequestMethod().toUpperCase();

        // Handle preflight (OPTIONS) requests
        if (requestMethod.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // Only allow POST requests
        if (!"POST".equals(requestMethod)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Asynchronously handle the request
        CompletableFuture.runAsync(() -> {
            try {
                // Read request body
                InputStream is = exchange.getRequestBody();
                String requestBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);

                System.out.println("Received Image Generations JSON: " + requestBody);

                JSONObject userInput = new JSONObject(requestBody);

                // Validate required fields
                if (!userInput.has("prompt")) {
                    sendError(exchange, "缺少必需的字段: prompt");
                    return;
                }

                String userPrompt = userInput.optString("prompt", "").trim();
                String responseFormat = userInput.optString("response_format", "b64_json").trim(); // Default to b64_json
                int n = userInput.optInt("n", 1); // Number of images to generate

                if (userPrompt.isEmpty()) {
                    sendError(exchange, "Prompt 不能为空。");
                    return;
                }

                // Optional: Refine the prompt using OpenAI API
                userPrompt = refinePrompt(userPrompt);
                if (userPrompt == null || userPrompt.isEmpty()) {
                    sendError(exchange, "Failed to refine the prompt using OpenAI API.");
                    return;
                }
                System.out.println("Refined Prompt: " + userPrompt);
                System.out.println("Number of images to generate (n): " + n);

                // Construct the JSON payload for Vulcan API
                JSONObject payload = new JSONObject();
                payload.put("model", "stable-diffusion-xl-v1-0");
                payload.put("negative_prompt", "");
                payload.put("width", 1024);
                payload.put("height", 1024);
                payload.put("prompt", userPrompt);
                payload.put("steps", 20);
                payload.put("guidance", 7.5);
                payload.put("output_format", "jpeg");
                payload.put("scheduler", "euler");

                // Send the request to Vulcan API
                JSONObject vulcanResponse = sendVulcanRequest(payload);
                if (vulcanResponse == null) {
                    sendError(exchange, "Failed to generate image using Vulcan API.");
                    return;
                }

                // Extract Base64 image from the response
                String imageBase64 = vulcanResponse.getJSONObject("data").getString("image");
                byte[] imageBytes = Base64.getDecoder().decode(imageBase64);

                // Prepare the response JSON
                JSONObject responseJson = new JSONObject();
                responseJson.put("created", System.currentTimeMillis() / 1000); // Unix timestamp
                JSONArray dataArray = new JSONArray();

                for (int i = 0; i < n; i++) {
                    JSONObject dataObject = new JSONObject();
                    dataObject.put("b64_json", imageBase64);
                    dataArray.put(dataObject);
                }

                responseJson.put("data", dataArray);

                // Send the response
                byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (JSONException je) {
                je.printStackTrace();
                try {
                    sendError(exchange, "JSON 解析错误: " + je.getMessage());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendError(exchange, "内部服务器错误: " + e.getMessage());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }, executor);
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

    /**
     * Sends a POST request to the Vulcan API and returns the JSON response.
     *
     * @param inputJson The JSON payload to send.
     * @return The JSON response from the Vulcan API, or null if an error occurs.
     */
    private JSONObject sendVulcanRequest(JSONObject inputJson) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(VULCAN_API_URI);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");

            // Set headers
            connection.setRequestProperty("Authorization", "Bearer " + ChatSmith.getValidToken());
            connection.setRequestProperty("X-Vulcan-Application-ID", VULCAN_APPLICATION_ID);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("X-Vulcan-Request-ID", "914948789" + Instant.now().getEpochSecond());
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setDoOutput(true);

            // Set timeouts
            connection.setConnectTimeout(30000); // 30 seconds
            connection.setReadTimeout(60000);    // 60 seconds

            // Send JSON payload
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = inputJson.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Handle response
            int responseCode = connection.getResponseCode();
            String contentEncoding = connection.getHeaderField("Content-Encoding");
            InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                    connection.getInputStream() : connection.getErrorStream();

            // If the response is GZIP-encoded, wrap the stream with GZIPInputStream
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                responseStream = new GZIPInputStream(responseStream);
            }

            // Read the response
            String responseString = readStream(responseStream);

            if (responseCode >= 200 && responseCode < 300) {
                return new JSONObject(responseString);
            } else {
                System.err.println("Vulcan API returned error (" + responseCode + "): " + responseString);
                return null;
            }

        } catch (IOException | JSONException e) {
            System.err.println("Failed to call Vulcan API: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    /**
     * Reads an InputStream and converts it to a String using UTF-8 encoding.
     *
     * @param stream The InputStream to read.
     * @return The resulting String.
     * @throws IOException If an I/O error occurs.
     */
    private String readStream(InputStream stream) throws IOException {
        StringBuilder responseBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
        }
        return responseBuilder.toString();
    }



    /**
     * 使用 OpenAI 的 chat/completions API 润色用户的提示词
     *
     * @param prompt 用户的原始提示词
     * @return 润色后的提示词
     */
    private String refinePrompt(String prompt) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:"+ChatSmith.port+"/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + "OPENAI_API_KEY");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // 设置超时时间（以毫秒为单位）
            connection.setConnectTimeout(30000); // 30 秒连接超时
            connection.setReadTimeout(60000);    // 60 秒读取超时

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4o");
            requestBody.put("stream", false); // 这里设置为 false，因为我们需要完整的润色结果

            // 设置系统和用户消息
            JSONArray messages = new JSONArray();

            // 适当的系统内容，引导模型润色提示词
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "You are an assistant that refines and improves user-provided prompts for image generation. Ensure the prompt is clear, descriptive, and optimized for generating high-quality images. Only tell me in English in few long sentences and always for anime style.");
            messages.put(systemMessage);

            // 用户的原始提示词
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.put(userMessage);

            requestBody.put("messages", messages);

            // 发送请求体
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 处理响应
            int responseCode = connection.getResponseCode();
            InputStream responseStream = (responseCode >= 200 && responseCode < 300) ?
                    connection.getInputStream() : connection.getErrorStream();

            if (responseCode >= 200 && responseCode < 300) {
                // 读取响应内容
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }

                JSONObject responseJson = new JSONObject(responseBuilder.toString());
                JSONArray choices = responseJson.getJSONArray("choices");
                if (choices.length() > 0) {
                    JSONObject firstChoice = choices.getJSONObject(0);
                    //if have message
                    if(firstChoice.has("message")){
                        String refinedPrompt = firstChoice.getJSONObject("message").getString("content").trim();
                        return refinedPrompt;
                    }else{ //
                        String refinedPrompt = firstChoice.getJSONObject("Message").getString("content").trim();
                        return refinedPrompt;
                    }
                } else {
                    System.err.println("OpenAI API 返回的 choices 数组为空。");
                    return null;
                }
            } else {
                // 读取错误信息
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
                StringBuilder errorBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorBuilder.append(line);
                }

                String errorResponse = errorBuilder.toString();
                System.err.println("OpenAI API 返回错误 (" + responseCode + "): " + errorResponse);
                return null;
            }

        } catch (IOException | JSONException e) {
            System.err.println("调用 OpenAI API 失败: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
