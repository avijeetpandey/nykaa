package com.avijeet.nykaa.cdc;

import com.avijeet.nykaa.constants.KafkaTopics;
import com.avijeet.nykaa.dto.cdc.ProductCdcEvent;
import com.avijeet.nykaa.dto.cdc.ProductCdcPayload;
import com.avijeet.nykaa.entities.product.ProductDocument;
import com.avijeet.nykaa.enums.Brand;
import com.avijeet.nykaa.enums.Category;
import com.avijeet.nykaa.repository.elasticsearch.ProductSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCdcConsumer {

    private final ProductSearchRepository productSearchRepository;

    @KafkaListener(
            topics = KafkaTopics.PRODUCT_CHANGES,
            groupId = "nykaa-cdc-elasticsearch",
            containerFactory = "cdcKafkaListenerContainerFactory"
    )
    public void handleProductChange(ProductCdcEvent event, Acknowledgment ack) {
        if (event == null || event.getOp() == null) {
            log.warn("[CDC] Received null or malformed CDC event — skipping.");
            ack.acknowledge();
            return;
        }

        log.debug("[CDC] op={} product id={}", event.getOp(),
                event.getAfter() != null ? event.getAfter().getId() : event.getBefore() != null ? event.getBefore().getId() : "?");

        switch (event.getOp()) {
            case "c", "u", "r" -> upsert(event.getAfter());
            case "d" -> delete(event.getBefore());
            default -> log.warn("[CDC] Unknown op='{}' — skipping.", event.getOp());
        }

        ack.acknowledge();
    }

    private void upsert(ProductCdcPayload payload) {
        if (payload == null || payload.getId() == null) {
            log.warn("[CDC] Upsert skipped — payload is null or missing id.");
            return;
        }
        try {
            ProductDocument doc = ProductDocument.builder()
                    .id(String.valueOf(payload.getId()))
                    .name(payload.getName())
                    .price(payload.getPrice())
                    .category(payload.getCategory() != null ? Category.valueOf(payload.getCategory()) : null)
                    .brand(payload.getBrand() != null ? Brand.valueOf(payload.getBrand()) : null)
                    .stockQuantity(payload.getStockQuantity())
                    .build();
            productSearchRepository.save(doc);
            log.info("[CDC] Upserted product {} in Elasticsearch.", payload.getId());
        } catch (Exception ex) {
            log.error("[CDC] Failed to upsert product {} in Elasticsearch: {}", payload.getId(), ex.getMessage());
        }
    }

    private void delete(ProductCdcPayload payload) {
        if (payload == null || payload.getId() == null) {
            log.warn("[CDC] Delete skipped — payload is null or missing id.");
            return;
        }
        try {
            productSearchRepository.deleteById(String.valueOf(payload.getId()));
            log.info("[CDC] Deleted product {} from Elasticsearch.", payload.getId());
        } catch (Exception ex) {
            log.error("[CDC] Failed to delete product {} from Elasticsearch: {}", payload.getId(), ex.getMessage());
        }
    }
}
