package com.avijeet.nykaa.constants;

public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String ORDER_CREATED      = "nykaa.order.created";
    public static final String ORDER_CONFIRMED    = "nykaa.order.confirmed";
    public static final String ORDER_CANCELLED    = "nykaa.order.cancelled";
    public static final String PAYMENT_PROCESSED  = "nykaa.payment.processed";
    public static final String PAYMENT_FAILED     = "nykaa.payment.failed";
    public static final String INVENTORY_RESERVED = "nykaa.inventory.reserved";
    public static final String INVENTORY_ROLLBACK = "nykaa.inventory.rollback";
    public static final String PRODUCT_CHANGES    = "nykaa.product.changes";
}
