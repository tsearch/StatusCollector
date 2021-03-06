package de.tsearch.statuscollector.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tsearch.statuscollector.database.postgres.entity.Broadcaster;
import de.tsearch.statuscollector.database.postgres.entity.StreamStatus;
import de.tsearch.statuscollector.database.postgres.repository.BroadcasterRepository;
import de.tsearch.statuscollector.web.entity.*;
import de.tsearch.tclient.data.EventEnum;
import de.tsearch.tclient.http.respone.webhook.Condition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("webhook")
public class WebhookController {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final ObjectMapper objectMapper;
    private final BroadcasterRepository broadcasterRepository;

    private static final Map<EventEnum, Class<? extends WebhookEvent>> eventContentMap;

    static {
        eventContentMap = Map.of(EventEnum.STREAM_OFFLINE, WebhookContentStreamOfflineEvent.class,
                EventEnum.STREAM_ONLINE, WebhookContentStreamOnlineEvent.class);
    }

    public WebhookController(ObjectMapper objectMapper, BroadcasterRepository broadcasterRepository) {
        this.objectMapper = objectMapper;
        this.broadcasterRepository = broadcasterRepository;
    }

    @PostMapping("{broadcasterId:\\d+}")
    public ResponseEntity<?> request(@RequestHeader("Twitch-Eventsub-Message-Type") String messageType,
                                     @RequestBody String content) {

        switch (messageType) {
            case "webhook_callback_verification":
                return verifyWebhook(content);
            case "notification":
                return notification(content);
            case "revocation":
                logger.info("Webhook revoked! Need to recheck all webhooks");
                break;
            default:
                logger.info("Unknown message type: " + messageType);
                break;
        }

        return ResponseEntity.badRequest().build();
    }

    private ResponseEntity<String> verifyWebhook(String content) {
        WebhookContentChallenge webhookContent;
        try {
            webhookContent = objectMapper.readValue(content, WebhookContentChallenge.class);
        } catch (JsonProcessingException e) {
            logger.error("Cannot parse json webhook notification", e);
            return ResponseEntity.badRequest().build();
        }

        if (webhookContent.getChallenge() != null) {
            final Condition condition = webhookContent.getSubscription().getCondition();
            logger.info("Accept webhook challenge for broadcaster id " + (condition.getBroadcasterUserID() != null ? condition.getBroadcasterUserID() : condition.getUserId()) + " for type " + webhookContent.getSubscription().getType());
            return ResponseEntity.ok(webhookContent.getChallenge());
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    private ResponseEntity<Void> notification(String content) {
        WebhookContent<JsonNode> webhookContent;
        try {
            webhookContent = objectMapper.readValue(content, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            logger.error("Cannot parse json webhook notification", e);
            return ResponseEntity.badRequest().build();
        }

        EventEnum eventEnum = EventEnum.getByWebhookEventType(webhookContent.getSubscription().getType());

        if (eventEnum != null) {
            WebhookEvent event;
            try {
                event = (WebhookEvent) objectMapper.treeToValue(webhookContent.getEvent(), eventContentMap.get(eventEnum));
            } catch (JsonProcessingException e) {
                logger.error("Cannot parse webhook notification content", e);
                return ResponseEntity.badRequest().build();
            }

            switch (eventEnum) {
                case STREAM_ONLINE -> streamOnline((WebhookContentStreamOnlineEvent) event);
                case STREAM_OFFLINE -> streamOffline((WebhookContentStreamOfflineEvent) event);
            }


        }
        return ResponseEntity.ok().build();
    }

    private void streamOffline(WebhookContentStreamOfflineEvent event) {
        logger.info("Broadcaster {}({}) went offline", event.getBroadcasterUserName(), event.getBroadcasterUserID());


        final Optional<Broadcaster> broadcasterOptional1 = broadcasterRepository.findById(event.getBroadcasterUserID());
        if (broadcasterOptional1.isPresent()) {
            final Broadcaster broadcaster = broadcasterOptional1.get();
            broadcaster.setStatus(StreamStatus.OFFLINE);
            broadcaster.setDisplayName(event.getBroadcasterUserName());
            broadcasterRepository.save(broadcaster);
        }
    }

    private void streamOnline(WebhookContentStreamOnlineEvent event) {
        logger.info("Broadcaster {}({}) went online", event.getBroadcasterUserName(), event.getBroadcasterUserID());

        final Optional<Broadcaster> broadcasterOptional1 = broadcasterRepository.findById(event.getBroadcasterUserID());
        if (broadcasterOptional1.isPresent()) {
            final Broadcaster broadcaster = broadcasterOptional1.get();
            broadcaster.setStatus(StreamStatus.ONLINE);
            broadcaster.setDisplayName(event.getBroadcasterUserName());
            broadcasterRepository.save(broadcaster);
        }
    }
}
