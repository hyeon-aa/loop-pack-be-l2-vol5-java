package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaListener;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 @KafkaListener·factory의 정상/중복 경로. 오류 정책은 테스트 전용 bean으로 분리한다. */
@SpringBootTest(classes = {KafkaFailureHandlingExperimentTest.DatabaseConfig.class,
    OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaListener.class,
    OrderConfirmedKafkaListenerIntegrationTest.TestErrors.class}, properties = {
        "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.concurrency=1",
        "commerce.order-events.consumer.auto-startup=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedKafkaListenerIntegrationTest extends KafkaPurchaseExperimentSupport {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "actual-order-listener-" + UUID.randomUUID();
    @Autowired private KafkaListenerEndpointRegistry registry;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        properties.add("commerce.order-events.topic", () -> TOPIC);
    }

    @Override
    @BeforeAll
    void startExperimentBroker() {
        broker = BROKER;
    }

    @AfterAll
    void stopSubscriptions() {
        registry.stop();
    }

    @Test
    @Timeout(70)
    void actualSubscriptionCommitsAfterPurchaseAndAcknowledgesAnIdenticalReplay() throws Exception {
        String group = "purchase-activity";
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        var container = registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, partition);
            try {
                container.start();
                assertThat(sendOrder(producer, TOPIC, 42L).offset()).isZero();
                await(() -> committed(admin, group, partition) == 1L);
                assertOrders(42L);
                List<Long> rowIds = activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList();
                Long confirmationId = confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId();

                assertThat(sendOrder(producer, TOPIC, 42L).offset()).isEqualTo(1);
                await(() -> committed(admin, group, partition) == 2L);
                assertOrders(42L);
                assertThat(activities.findAll().stream().map(ProductPurchaseActivity::getId).sorted().toList())
                    .isEqualTo(rowIds);
                assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId())
                    .isEqualTo(confirmationId);
                report("ACTUAL_ORDER_LISTENER_INTEGRATION", Map.of("group", group,
                    "listenerId", OrderConfirmedKafkaListener.ID, "committedOffsets", List.of(0, 1, 2),
                    "confirmationRows", confirmations.count(), "activityRows", activities.count(),
                    "rowIdsUnchanged", true));
            } finally {
                container.stop();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestErrors {
        @Bean(name = OrderConfirmedKafkaConfiguration.ERROR_HANDLER)
        CommonErrorHandler testOnlyErrorHandler() {
            return new CommonContainerStoppingErrorHandler();
        }
    }
}
