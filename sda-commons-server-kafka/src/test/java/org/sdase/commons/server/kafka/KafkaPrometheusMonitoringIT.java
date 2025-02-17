package org.sdase.commons.server.kafka;

import static io.dropwizard.testing.ConfigOverride.config;
import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.salesforce.kafka.test.junit5.SharedKafkaTestResource;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sdase.commons.server.kafka.builder.MessageListenerRegistration;
import org.sdase.commons.server.kafka.builder.ProducerRegistration;
import org.sdase.commons.server.kafka.consumer.IgnoreAndProceedErrorHandler;
import org.sdase.commons.server.kafka.consumer.strategies.autocommit.AutocommitMLS;
import org.sdase.commons.server.kafka.dropwizard.KafkaTestApplication;
import org.sdase.commons.server.kafka.dropwizard.KafkaTestConfiguration;
import org.sdase.commons.server.kafka.helper.MetricsHelper;
import org.sdase.commons.server.kafka.producer.MessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class KafkaPrometheusMonitoringIT {

  private static final Logger LOGGER = LoggerFactory.getLogger(KafkaPrometheusMonitoringIT.class);

  @RegisterExtension
  @Order(0)
  private static final SharedKafkaTestResource KAFKA =
      new SharedKafkaTestResource()
          // we only need one consumer offsets partition
          .withBrokerProperty("offsets.topic.num.partitions", "1")
          // we don't need to wait that a consumer group rebalances since we always start with a
          // fresh kafka instance
          .withBrokerProperty("group.initial.rebalance.delay.ms", "0");

  private static final String CONSUMER_1 = "consumer1";

  private static final String PRODUCER_1 = "producer1";

  @RegisterExtension
  @Order(1)
  private static final DropwizardAppExtension<KafkaTestConfiguration> DROPWIZARD_APP_EXTENSION =
      new DropwizardAppExtension<>(
          KafkaTestApplication.class,
          resourceFilePath("test-config-default.yml"),
          config("kafka.brokers", KAFKA::getKafkaConnectString),

          // performance improvements in the tests
          config("kafka.config.heartbeat\\.interval\\.ms", "250"));

  private List<Long> resultsLong = Collections.synchronizedList(new ArrayList<>());
  private List<Long> resultsLong2 = Collections.synchronizedList(new ArrayList<>());

  private KafkaBundle<KafkaTestConfiguration> kafkaBundle;

  @BeforeEach
  void before() {
    KafkaTestApplication app = DROPWIZARD_APP_EXTENSION.getApplication();

    kafkaBundle = app.kafkaBundle();

    resultsLong.clear();
    resultsLong2.clear();
  }

  @Test
  void shouldWriteHelpAndTypeToMetrics() {
    String topic = "shouldWriteHelpAndTypeToMetrics_Topic";
    KAFKA.getKafkaTestUtils().createTopic(topic, 1, (short) 1);

    AutocommitMLS<Long, Long> longLongAutocommitMLS =
        new AutocommitMLS<>(
            record -> resultsLong.add(record.value()), new IgnoreAndProceedErrorHandler<>());
    createMessageListener(topic, CONSUMER_1, longLongAutocommitMLS);

    MessageProducer<Long, Long> producer = registerProducer(topic, PRODUCER_1);

    // pass in messages
    producer.send(1L, 1L);
    producer.send(2L, 2L);

    await()
        .atMost(KafkaBundleConsts.N_MAX_WAIT_MS, MILLISECONDS)
        .until(() -> resultsLong.size() == 2);

    List<MetricFamilySamples> list =
        Collections.list(CollectorRegistry.defaultRegistry.metricFamilySamples());

    String[] metrics = {
      "kafka_producer_topic_message",
      "kafka_consumer_topic_message_duration",
      "kafka_consumer_records_lag"
    };

    assertThat(list).extracting(m -> m.name).contains(metrics);

    list.forEach(
        mfs -> {
          assertThat(mfs.samples.size()).isPositive();
          for (Collector.MetricFamilySamples.Sample sample : mfs.samples) {
            LOGGER.info(
                "Sample: name={}, value={}, labelNames={}, labelValues={}",
                sample.name,
                sample.value,
                sample.labelNames,
                sample.labelValues);
          }
        });

    assertThat(
            CollectorRegistry.defaultRegistry.getSampleValue(
                "kafka_producer_topic_message_total",
                new String[] {"producer_name", "topic_name"},
                new String[] {PRODUCER_1, topic}))
        .as("sample value for metric 'kafka_producer_topic_message_total'")
        .isEqualTo(2);

    assertThat(
            CollectorRegistry.defaultRegistry.getSampleValue(
                "kafka_consumer_topic_message_duration_count",
                new String[] {"consumer_name", "topic_name"},
                new String[] {CONSUMER_1 + "-0", topic}))
        .as("sample value for metric 'kafka_consumer_topic_message_duration_count'")
        .isEqualTo(2);
  }

  @Test
  void writeKafkaInternalMetricsToPrometheus() {
    String topic = "writeKafkaInternalMetricsToPrometheus_Topic_1";
    KAFKA.getKafkaTestUtils().createTopic(topic, 1, (short) 1);

    String topic2 = "writeKafkaInternalMetricsToPrometheus_Topic_2";
    KAFKA.getKafkaTestUtils().createTopic(topic2, 1, (short) 1);

    AutocommitMLS<Long, Long> longLongAutocommitMLS =
        new AutocommitMLS<>(
            record -> resultsLong.add(record.value()), new IgnoreAndProceedErrorHandler<>());
    createMessageListener(topic, CONSUMER_1, longLongAutocommitMLS);

    AutocommitMLS<Long, Long> longLongAutocommitMLS2 =
        new AutocommitMLS<>(
            record -> resultsLong2.add(record.value()), new IgnoreAndProceedErrorHandler<>());
    createMessageListener(topic2, "consumer3", longLongAutocommitMLS2);

    MessageProducer<Long, Long> producer = registerProducer(topic, PRODUCER_1);
    MessageProducer<Long, Long> producer2 = registerProducer(topic2, "producer3");

    // pass in messages
    producer.send(1L, 1L);
    producer.send(2L, 2L);

    producer2.send(1L, 1L);
    producer2.send(2L, 2L);

    attachPrometheusToMicrometer();

    await()
        .atMost(KafkaBundleConsts.N_MAX_WAIT_MS, MILLISECONDS)
        .until(() -> resultsLong.size() == 2);

    await()
        .atMost(KafkaBundleConsts.N_MAX_WAIT_MS, MILLISECONDS)
        .until(() -> resultsLong2.size() == 2);

    ArrayList<Collector.MetricFamilySamples> collectorMetricSamplesErrorTotal =
        Collections.list(
            CollectorRegistry.defaultRegistry.filteredMetricFamilySamples(
                s -> s.equals("kafka_producer_record_error_total")));

    //    assert that metric exists
    assertThat(collectorMetricSamplesErrorTotal).hasSize(1);

    //    assert that samples for both producers are recorded
    assertThat(collectorMetricSamplesErrorTotal.get(0).samples).hasSize(2);

    List<Collector.MetricFamilySamples.Sample> samples =
        collectorMetricSamplesErrorTotal.get(0).samples;

    List<String> sampleLabelValueNames =
        samples.stream().map(s -> s.labelValues.get(0)).collect(Collectors.toList());
    assertThat(sampleLabelValueNames).contains("producer1", "producer3");
  }

  @Test
  void checkIfInternalMetricChange() {
    String topic = "checkIfInternalMetricChange_Topic";
    KAFKA.getKafkaTestUtils().createTopic(topic, 1, (short) 1);

    MessageProducer<Long, Long> producer = registerProducer(topic, PRODUCER_1);

    AutocommitMLS<Long, Long> longLongAutocommitMLS =
        new AutocommitMLS<>(
            record -> resultsLong.add(record.value()), new IgnoreAndProceedErrorHandler<>());
    createMessageListener(topic, CONSUMER_1, longLongAutocommitMLS);

    producer.send(1L, 1L);
    producer.send(2L, 2L);

    await()
        .atMost(KafkaBundleConsts.N_MAX_WAIT_MS, MILLISECONDS)
        .until(() -> resultsLong.size() == 2);

    assertThat(MetricsHelper.getListOfMetrics()).containsAll(getMetricsThatShouldExist());
  }

  //  Keep in mind that internal metrics use minus while prometheus uses underscore
  private List<String> getMetricsThatShouldExist() {

    List<String> result =
        List.of(
            "producer-metrics-flush-time-ns-total",
            "producer-metrics-txn-init-time-ns-total",
            "producer-metrics-txn-begin-time-ns-total",
            "producer-metrics-txn-send-offsets-time-ns-total",
            "producer-metrics-txn-commit-time-ns-total",
            "producer-metrics-txn-abort-time-ns-total",
            "producer-metrics-metadata-wait-time-ns-total",
            "producer-metrics-buffer-exhausted-total",
            "producer-metrics-buffer-exhausted-rate",
            "producer-metrics-bufferpool-wait-time-total",
            "producer-metrics-bufferpool-wait-ratio",
            "producer-metrics-bufferpool-wait-time-ns-total",
            "producer-metrics-waiting-threads",
            "producer-metrics-buffer-total-bytes",
            "producer-metrics-buffer-available-bytes",
            "producer-metrics-produce-throttle-time-avg",
            "producer-metrics-produce-throttle-time-max",
            "producer-metrics-connection-close-total",
            "producer-metrics-connection-close-rate",
            "producer-metrics-connection-creation-total",
            "producer-metrics-connection-creation-rate",
            "producer-metrics-successful-authentication-total",
            "producer-metrics-successful-authentication-rate",
            "producer-metrics-successful-reauthentication-total",
            "producer-metrics-successful-reauthentication-rate",
            "producer-metrics-successful-authentication-no-reauth-total",
            "producer-metrics-failed-authentication-total",
            "producer-metrics-failed-authentication-rate",
            "producer-metrics-failed-reauthentication-total",
            "producer-metrics-failed-reauthentication-rate",
            "producer-metrics-reauthentication-latency-max",
            "producer-metrics-reauthentication-latency-avg",
            "producer-metrics-network-io-total",
            "producer-metrics-network-io-rate",
            "producer-metrics-outgoing-byte-total",
            "producer-metrics-outgoing-byte-rate",
            "producer-metrics-request-total",
            "producer-metrics-request-rate",
            "producer-metrics-request-size-avg",
            "producer-metrics-request-size-max",
            "producer-metrics-incoming-byte-total",
            "producer-metrics-incoming-byte-rate",
            "producer-metrics-response-total",
            "producer-metrics-response-rate",
            "producer-metrics-select-total",
            "producer-metrics-select-rate",
            "producer-metrics-io-wait-time-ns-avg",
            "producer-metrics-io-waittime-total",
            "producer-metrics-io-wait-ratio",
            "producer-metrics-io-wait-time-ns-total",
            "producer-metrics-io-time-ns-avg",
            "producer-metrics-iotime-total",
            "producer-metrics-io-ratio",
            "producer-metrics-io-time-ns-total",
            "producer-metrics-connection-count",
            "producer-metrics-batch-size-avg",
            "producer-metrics-batch-size-max",
            "producer-metrics-compression-rate-avg",
            "producer-metrics-record-queue-time-avg",
            "producer-metrics-record-queue-time-max",
            "producer-metrics-request-latency-avg",
            "producer-metrics-request-latency-max",
            "producer-metrics-record-send-total",
            "producer-metrics-record-send-rate",
            "producer-metrics-records-per-request-avg",
            "producer-metrics-record-retry-total",
            "producer-metrics-record-retry-rate",
            "producer-metrics-record-error-total",
            "producer-metrics-record-error-rate",
            "producer-metrics-record-size-max",
            "producer-metrics-record-size-avg",
            "producer-metrics-requests-in-flight",
            "producer-metrics-metadata-age",
            "producer-metrics-batch-split-total",
            "producer-metrics-batch-split-rate",
            "app-info-version",
            "app-info-commit-id",
            "app-info-start-time-ms",
            "producer-node-metrics-request-total",
            "producer-node-metrics-request-rate",
            "producer-node-metrics-request-size-avg",
            "producer-node-metrics-request-size-max",
            "producer-node-metrics-outgoing-byte-total",
            "producer-node-metrics-outgoing-byte-rate",
            "producer-node-metrics-response-total",
            "producer-node-metrics-response-rate",
            "producer-node-metrics-incoming-byte-total",
            "producer-node-metrics-incoming-byte-rate",
            "producer-node-metrics-request-latency-avg",
            "producer-node-metrics-request-latency-max",
            "producer-topic-metrics-record-send-total",
            "producer-topic-metrics-record-send-total",
            "producer-topic-metrics-record-send-rate",
            "producer-topic-metrics-byte-total",
            "producer-topic-metrics-byte-rate",
            "producer-topic-metrics-compression-rate",
            "producer-topic-metrics-record-retry-total",
            "producer-topic-metrics-record-retry-rate",
            "producer-topic-metrics-record-error-total",
            "producer-topic-metrics-record-error-rate");

    return result;
  }

  private void createMessageListener(
      String topic, String consumerConfigKey, AutocommitMLS<Long, Long> longLongAutocommitMLS) {
    kafkaBundle.createMessageListener(
        MessageListenerRegistration.builder()
            .withDefaultListenerConfig()
            .forTopic(topic)
            .withConsumerConfig(consumerConfigKey)
            .withListenerStrategy(longLongAutocommitMLS)
            .build());
  }

  private MessageProducer<Long, Long> registerProducer(String topic, String producerConfigKey) {
    return kafkaBundle.registerProducer(
        ProducerRegistration.<Long, Long>builder()
            .forTopic(topic)
            .withProducerConfig(producerConfigKey)
            .build());
  }

  private void attachPrometheusToMicrometer() {
    PrometheusMeterRegistry meterRegistry =
        new PrometheusMeterRegistry(key -> null, CollectorRegistry.defaultRegistry, Clock.SYSTEM);

    Metrics.addRegistry(meterRegistry);
  }
}
