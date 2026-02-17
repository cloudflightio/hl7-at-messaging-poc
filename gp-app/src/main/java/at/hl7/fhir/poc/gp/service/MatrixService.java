package at.hl7.fhir.poc.gp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Service
@Slf4j
public class MatrixService {

    @Value("${matrix.server.url}")
    private String matrixServerUrl;

    @Value("${matrix.username}")
    private String username;

    @Value("${matrix.password}")
    private String password;

    @Value("${matrix.room.alias:messaging}")
    private String roomAlias;

    @Value("${matrix.server.name:matrix.local}")
    private String matrixServerName;

    private WebClient webClient;
    private String accessToken;
    private String roomId;
    private String nextBatch;
    private ObjectMapper objectMapper;

    private final BundleParser bundleParser;

    public MatrixService(BundleParser bundleParser) {
        this.bundleParser = bundleParser;
    }

    @PostConstruct
    public void init() {
        // Configure WebClient with 50MB buffer for large FHIR bundles with PDFs
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(50 * 1024 * 1024)) // 50MB
                .build();

        webClient = WebClient.builder()
                .baseUrl(matrixServerUrl)
                .exchangeStrategies(strategies)
                .build();
        objectMapper = new ObjectMapper();
        log.info("Matrix service initialized for server: {} (max buffer: 50MB)", matrixServerUrl);
    }

    public boolean login() {
        try {
            Map<String, Object> loginRequest = new HashMap<>();
            loginRequest.put("type", "m.login.password");
            loginRequest.put("user", username);
            loginRequest.put("password", password);

            String response = webClient.post()
                    .uri("/_matrix/client/v3/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(loginRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            accessToken = jsonNode.get("access_token").asText();
            log.info("Successfully logged in to Matrix as {}", username);

            // Look up room ID
            resolveRoomId();

            // Join the room
            joinRoom();

            // Initial sync to get next_batch token
            initialSync();

            return true;
        } catch (Exception e) {
            log.error("Failed to login to Matrix", e);
            return false;
        }
    }

    private void resolveRoomId() {
        try {
            String response = webClient.get()
                    .uri("/_matrix/client/v3/directory/room/{alias}",
                            "#" + roomAlias + ":" + matrixServerName)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            roomId = jsonNode.get("room_id").asText();
            log.info("Resolved room alias {} to ID {}", roomAlias, roomId);
        } catch (Exception e) {
            log.error("Failed to resolve room alias", e);
        }
    }

    private void joinRoom() {
        if (roomId == null) return;

        try {
            webClient.post()
                    .uri("/_matrix/client/v3/join/{roomId}", roomId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Joined room {}", roomId);
        } catch (Exception e) {
            log.warn("Failed to join room (may already be joined): {}", e.getMessage());
        }
    }

    private void initialSync() {
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/_matrix/client/v3/sync")
                            .queryParam("timeout", "0")
                            .build())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            nextBatch = jsonNode.get("next_batch").asText();
            log.info("Initial sync complete, next_batch: {}", nextBatch);
        } catch (Exception e) {
            log.error("Failed initial sync", e);
        }
    }

    @Scheduled(fixedRate = 5000)
    public void pollForMessages() {
        if (accessToken == null || roomId == null || nextBatch == null) {
            return;
        }

        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/_matrix/client/v3/sync")
                            .queryParam("since", nextBatch)
                            .queryParam("timeout", "3000")
                            .build())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            nextBatch = jsonNode.get("next_batch").asText();

            // Process room events
            JsonNode rooms = jsonNode.path("rooms").path("join").path(roomId);
            JsonNode timeline = rooms.path("timeline").path("events");

            if (timeline.isArray()) {
                for (JsonNode event : timeline) {
                    processEvent(event);
                }
            }

        } catch (Exception e) {
            log.debug("Error polling for messages: {}", e.getMessage());
        }
    }

    private void processEvent(JsonNode event) {
        try {
            String eventType = event.path("type").asText();
            if (!"m.room.message".equals(eventType)) {
                return;
            }

            JsonNode content = event.path("content");
            String msgType = content.path("msgtype").asText();

            if ("at.fhir.message".equals(msgType)) {
                String fhirBundle = null;

                // Check for media URL first (new approach for large bundles)
                String mxcUri = content.path("fhir_bundle_url").asText();
                if (mxcUri != null && mxcUri.startsWith("mxc://")) {
                    log.info("Downloading FHIR bundle from Matrix media: {}", mxcUri);
                    fhirBundle = downloadMedia(mxcUri);
                }

                // Fall back to inline bundle (legacy/small bundles)
                if (fhirBundle == null || fhirBundle.isEmpty()) {
                    fhirBundle = content.path("fhir_bundle").asText();
                }

                if (fhirBundle != null && !fhirBundle.isEmpty()) {
                    log.info("Received FHIR message from Matrix");

                    // Parse and save to FHIR server
                    bundleParser.parseAndSaveBundle(fhirBundle);
                }
            }
        } catch (Exception e) {
            log.error("Error processing Matrix event", e);
        }
    }

    /**
     * Download content from Matrix media repository using mxc:// URI.
     */
    private String downloadMedia(String mxcUri) {
        try {
            // Parse mxc://server/mediaId format
            String uriPart = mxcUri.substring(6); // Remove "mxc://"
            String[] parts = uriPart.split("/", 2);
            if (parts.length != 2) {
                log.error("Invalid mxc URI format: {}", mxcUri);
                return null;
            }
            String serverName = parts[0];
            String mediaId = parts[1];

            byte[] content = webClient.get()
                    .uri("/_matrix/client/v1/media/download/{serverName}/{mediaId}", serverName, mediaId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();

            if (content != null) {
                log.info("Downloaded media from Matrix: {} bytes", content.length);
                return new String(content);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to download media from Matrix: {}", mxcUri, e);
            return null;
        }
    }

    public boolean isConnected() {
        return accessToken != null && roomId != null;
    }

    /**
     * Send a FHIR message bundle via Matrix.
     * Uploads the bundle as media and sends a message event referencing it.
     */
    public boolean sendFhirMessage(String fhirBundleJson) {
        if (accessToken == null) {
            if (!login()) {
                return false;
            }
        }

        if (roomId == null) {
            log.error("Room ID not resolved");
            return false;
        }

        try {
            // Upload the FHIR bundle as media (to handle large bundles)
            String mxcUri = uploadMedia(fhirBundleJson.getBytes(), "application/json", "fhir-bundle.json");
            if (mxcUri == null) {
                log.error("Failed to upload FHIR bundle to Matrix media");
                return false;
            }

            String txnId = UUID.randomUUID().toString();

            // Send a message event referencing the uploaded media
            Map<String, Object> messageContent = new HashMap<>();
            messageContent.put("msgtype", "at.fhir.message");
            messageContent.put("body", "FHIR Message Bundle");
            messageContent.put("fhir_bundle_url", mxcUri);

            String response = webClient.put()
                    .uri("/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}",
                            roomId, txnId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(messageContent)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            String eventId = jsonNode.get("event_id").asText();
            log.info("Sent FHIR message via media upload, event ID: {}, media URI: {}", eventId, mxcUri);

            return true;
        } catch (Exception e) {
            log.error("Failed to send FHIR message", e);
            return false;
        }
    }

    /**
     * Upload content to Matrix media repository.
     * Returns the mxc:// URI of the uploaded content.
     */
    private String uploadMedia(byte[] content, String contentType, String filename) {
        try {
            String response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/_matrix/media/v3/upload")
                            .queryParam("filename", filename)
                            .build())
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", contentType)
                    .bodyValue(content)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            String mxcUri = jsonNode.get("content_uri").asText();
            log.info("Uploaded media to Matrix: {} ({} bytes)", mxcUri, content.length);
            return mxcUri;
        } catch (Exception e) {
            log.error("Failed to upload media to Matrix", e);
            return null;
        }
    }
}
