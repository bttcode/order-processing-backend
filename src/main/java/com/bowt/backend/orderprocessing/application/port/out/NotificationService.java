package com.bowt.backend.orderprocessing.application.port.out;

/**
 * Outbound delivery port used by {@code CustomerNotificationListener} (and,
 * where relevant, the other listeners) to actually send a message to an
 * external channel (email, SMS, warehouse system webhook, accounting export).
 */
public interface NotificationService {

    void send(NotificationRequest request);

    record NotificationRequest(String recipientId, String channel, String subject, String message) {
    }
}