package io.opensimilarity.api;

import io.opensimilarity.embedding.Embedder;
import io.opensimilarity.store.Store;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final Store store;
    private final Optional<Embedder> embedder;

    public HealthController(Store store, Optional<Embedder> embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("embeddingModel", embedder.map(Embedder::modelId).orElse("disabled"));
        try {
            body.put("pgvector", store.pgvectorVersion());
            body.put("database", "UP");
            body.put("status", "OK");
            return ResponseEntity.ok(body);
        } catch (DataAccessException e) {
            log.warn("Health check could not reach the database", e);
            body.put("database", "DOWN");
            body.put("status", "DOWN");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
