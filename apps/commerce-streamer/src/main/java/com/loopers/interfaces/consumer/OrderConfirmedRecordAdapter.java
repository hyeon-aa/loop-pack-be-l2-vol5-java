package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.OrderConfirmationCommand;
import com.loopers.application.purchase.OrderConfirmationHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** record 파싱·DB 처리와 DB 완료 뒤 수동 ack를 연결한다. 구독 등록은 별도다. */
@Component
@RequiredArgsConstructor
public class OrderConfirmedRecordAdapter {
    private final OrderConfirmedMessageParser parser;
    private final OrderConfirmationHandler handler;

    public void handle(ConsumerRecord<String, byte[]> record) {
        handler.handle(parse(record));
    }

    public void handle(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(acknowledgment, "acknowledgment must not be null");
        handle(record);
        acknowledgment.acknowledge();
    }

    private OrderConfirmationCommand parse(ConsumerRecord<String, byte[]> record) {
        try {
            return parser.parse(record.key(), record.value());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidOrderConfirmedMessageException(invalid);
        }
    }
}
