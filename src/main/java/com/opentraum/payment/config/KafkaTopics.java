package com.opentraum.payment.config;

public class KafkaTopics {

    private KafkaTopics() {}

    public static final String SEAT_ASSIGNMENT     = "seat-assignment";
    public static final String SEAT_ASSIGNMENT_DLQ = "seat-assignment-dlq";
    public static final String REFUND              = "refund";
}
