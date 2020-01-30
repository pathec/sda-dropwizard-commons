package org.sdase.commons.server.kafka.builder;

import com.github.ftrossbach.club_topicana.core.ExpectedTopicConfiguration;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;
import org.apache.kafka.common.serialization.Deserializer;
import org.sdase.commons.server.kafka.config.ConsumerConfig;
import org.sdase.commons.server.kafka.config.ListenerConfig;
import org.sdase.commons.server.kafka.consumer.ErrorHandler;
import org.sdase.commons.server.kafka.consumer.MessageHandler;
import org.sdase.commons.server.kafka.consumer.strategies.legacy.CallbackMessageHandler;
import org.sdase.commons.server.kafka.topicana.TopicConfigurationBuilder;

public class MessageHandlerRegistration<K, V> {

  private Deserializer<K> keyDeserializer;
  private Deserializer<V> valueDeserializer;
  private Collection<ExpectedTopicConfiguration> topics;
  private MessageHandler<K, V> handler;
  private ErrorHandler<K, V> errorHandler;
  private boolean checkTopicConfiguration;
  private ConsumerConfig consumerConfig;
  private String consumerConfigName;
  private String listenerConfigName;
  private ListenerConfig listenerConfig;

  public Deserializer<K> getKeyDeserializer() {
    return keyDeserializer;
  }

  public boolean isCheckTopicConfiguration() {
    return checkTopicConfiguration;
  }

  public String getListenerConfigName() {
    return listenerConfigName;
  }

  public Deserializer<V> getValueDeserializer() {
    return valueDeserializer;
  }

  public Collection<ExpectedTopicConfiguration> getTopics() {
    return topics;
  }

  public Collection<String> getTopicsNames() {
    return topics.stream()
        .map(ExpectedTopicConfiguration::getTopicName)
        .collect(Collectors.toList());
  }

  public MessageHandler<K, V> getHandler() {
    return handler;
  }

  public ConsumerConfig getConsumerConfig() {
    return consumerConfig;
  }

  public String getConsumerConfigName() {
    return consumerConfigName;
  }

  public ListenerConfig getListenerConfig() {
    return listenerConfig;
  }

  public ErrorHandler<K, V> getErrorHandler() {
    return errorHandler;
  }

  public interface ListenerBuilder<K, V> {

    TopicBuilder<K, V> withListenerConfig(String name);

    TopicBuilder<K, V> withListenerConfig(ListenerConfig config);

    TopicBuilder<K, V> withDefaultListenerConfig();
  }

  public interface TopicBuilder<K, V> {

    /**
     * @param topic configure the topic to consume
     * @return builder
     */
    ConsumerBuilder<K, V> forTopic(String topic);

    /**
     * @param topics Collection of topics to consume
     * @return builder
     */
    ConsumerBuilder<K, V> forTopics(Collection<String> topics);

    /**
     * @param topicConfiguration topic to consume given as topic configuration, e.g. predefined in
     *     config This is necessary if you want to check the topic configuration during startup
     * @return builder
     */
    ConsumerBuilder<K, V> forTopicConfigs(
        Collection<ExpectedTopicConfiguration> topicConfiguration);
  }

  public interface ConsumerBuilder<K, V> {

    /**
     * Define optional step to process a configuration check of the topic. If the topic differs,
     * a @{@link com.github.ftrossbach.club_topicana.core.MismatchedTopicConfigException} will be
     * thrown.
     *
     * @return builder
     */
    ConsumerBuilder<K, V> checkTopicConfiguration();

    /**
     * @param name name of a consumer config given in the configuration yaml.
     * @return builder
     */
    HandlerBuilder<K, V> withConsumerConfig(String name);

    /**
     * @param consumerConfig configuration for a consumer
     * @return builder
     */
    HandlerBuilder<K, V> withConsumerConfig(ConsumerConfig consumerConfig);

    /**
     * use the default configuration (1 instance, sync commit...)
     *
     * @return builder
     */
    HandlerBuilder<K, V> withDefaultConsumer();
  }

  public interface HandlerBuilder<K, V> {

    /**
     * Define key deserializer. This overwrites configuration from ConsumerConfig
     *
     * @param keyDeserializer the serializer
     * @return builder
     */
    HandlerBuilder<K, V> withKeyDeserializer(Deserializer<K> keyDeserializer);

    /**
     * Define the value deserializer. This overwrites configuration from ConsumerConfig
     *
     * @param valueDeserializer the serializer
     * @return builder
     */
    HandlerBuilder<K, V> withValueDeserializer(Deserializer<V> valueDeserializer);

    /**
     * The message handler. Either an instance of @link {@link MessageHandler} or @{@link
     * CallbackMessageHandler}
     *
     * @param handler handler with business logic
     * @return builder
     */
    ErrorHandlerBuilder<K, V> withHandler(MessageHandler<K, V> handler);
  }

  public interface ErrorHandlerBuilder<K, V> {
    /**
     * @param errorHandler error handler for handle errors during processing in @{@link
     *     MessageHandler}
     * @return builder
     */
    FinalBuilder<K, V> withErrorHandler(ErrorHandler<K, V> errorHandler);
  }

  public interface FinalBuilder<K, V> {
    MessageHandlerRegistration<K, V> build();
  }

  public static <K, V> ListenerBuilder<K, V> builder() {
    return new Builder<>();
  }

  private static class Builder<K, V>
      implements ConsumerBuilder<K, V>,
          TopicBuilder<K, V>,
          HandlerBuilder<K, V>,
          FinalBuilder<K, V>,
          ListenerBuilder<K, V>,
          ErrorHandlerBuilder<K, V> {

    private Deserializer<?> keyDeserializer;
    private Deserializer<?> valueDeserializer;
    private Collection<ExpectedTopicConfiguration> topics;
    private MessageHandler<K, V> handler;
    private boolean topicExistCheck = false;
    private ConsumerConfig consumerConfig;
    private ListenerConfig listenerConfig;
    private String consumerName;
    private String listenerName;
    private ErrorHandler<K, V> errorHandler;

    private Builder() {}

    @Override
    public ConsumerBuilder<K, V> forTopic(@NotNull String topic) {
      this.topics = Collections.singletonList(TopicConfigurationBuilder.builder(topic).build());
      return this;
    }

    @Override
    public ConsumerBuilder<K, V> forTopics(@NotNull Collection<String> topics) {
      this.topics =
          topics.stream()
              .map(t -> TopicConfigurationBuilder.builder(t).build())
              .collect(Collectors.toList());
      return this;
    }

    @Override
    public ConsumerBuilder<K, V> forTopicConfigs(
        Collection<ExpectedTopicConfiguration> topicConfiguration) {
      this.topics = topicConfiguration;
      return this;
    }

    @Override
    public ErrorHandlerBuilder<K, V> withHandler(@NotNull MessageHandler<K, V> handler) {
      this.handler = handler;
      return this;
    }

    @Override
    public ConsumerBuilder<K, V> checkTopicConfiguration() {
      this.topicExistCheck = true;
      return this;
    }

    @Override
    public HandlerBuilder<K, V> withConsumerConfig(String name) {
      this.consumerName = name;
      return this;
    }

    @Override
    public HandlerBuilder<K, V> withKeyDeserializer(@NotNull Deserializer<K> keyDeserializer) {
      this.keyDeserializer = keyDeserializer;
      return this;
    }

    @Override
    public HandlerBuilder<K, V> withValueDeserializer(@NotNull Deserializer<V> valueDeserializer) {
      this.valueDeserializer = valueDeserializer;
      return this;
    }

    @Override
    public HandlerBuilder<K, V> withConsumerConfig(@NotNull ConsumerConfig consumerConfig) {
      this.consumerConfig = consumerConfig;
      return this;
    }

    @Override
    public HandlerBuilder<K, V> withDefaultConsumer() {
      this.consumerConfig = null;
      return this;
    }

    @Override
    public TopicBuilder<K, V> withListenerConfig(String name) {
      this.listenerName = name;
      return this;
    }

    @Override
    public TopicBuilder<K, V> withListenerConfig(ListenerConfig config) {
      this.listenerConfig = config;
      return this;
    }

    @Override
    public TopicBuilder<K, V> withDefaultListenerConfig() {
      this.listenerConfig = ListenerConfig.getDefault();
      return this;
    }

    @Override
    public FinalBuilder<K, V> withErrorHandler(ErrorHandler<K, V> errorHandler) {
      this.errorHandler = errorHandler;
      return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public MessageHandlerRegistration<K, V> build() {
      MessageHandlerRegistration<K, V> build = new MessageHandlerRegistration<>();
      build.handler = handler;
      build.keyDeserializer = (Deserializer<K>) keyDeserializer;
      build.valueDeserializer = (Deserializer<V>) valueDeserializer;
      build.topics = topics;
      build.checkTopicConfiguration = topicExistCheck;
      build.consumerConfig = consumerConfig;
      build.consumerConfigName = consumerName;
      build.listenerConfig = listenerConfig;
      build.listenerConfigName = listenerName;
      build.errorHandler = errorHandler;

      return build;
    }
  }
}
