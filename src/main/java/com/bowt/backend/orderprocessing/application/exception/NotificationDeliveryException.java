package com.bowt.backend.orderprocessing.application.exception;

import lombok.Getter;

@Getter
public class NotificationDeliveryException extends RuntimeException {

    private final String channel;
    private final String recipientId;

    public NotificationDeliveryException(String channel, String recipientId) {
        super("Simulated delivery failure on channel '" + channel + "' for recipient " + recipientId);
        this.channel = channel;
        this.recipientId = recipientId;
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
        this.channel = null;
        this.recipientId = null;
    }
}