/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.elasticsearch;

import com.google.common.collect.Sets;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ElasticsearchSinkTask extends SinkTask {

  private static final Logger log = LoggerFactory.getLogger(ElasticsearchSinkTask.class);
  private ElasticsearchWriter writer;
  private JestClient client;

  @Override
  public String version() {
    return Version.getVersion();
  }

  @Override
  public void start(Map<String, String> props) {
    start(props, null);
  }

  // public for testing
  public void start(Map<String, String> props, JestClient client) {
    try {
      log.info("Starting ElasticsearchSinkTask.");

      ElasticsearchSinkConnectorConfig config = new ElasticsearchSinkConnectorConfig(props);
      String type = config.getString(ElasticsearchSinkConnectorConfig.TYPE_NAME_CONFIG);
      boolean ignoreKey =
          config.getBoolean(ElasticsearchSinkConnectorConfig.KEY_IGNORE_CONFIG);
      boolean ignoreSchema =
          config.getBoolean(ElasticsearchSinkConnectorConfig.SCHEMA_IGNORE_CONFIG);


      Map<String, String> topicToIndexMap =
          parseMapConfig(config.getList(ElasticsearchSinkConnectorConfig.TOPIC_INDEX_MAP_CONFIG));
      Set<String> topicIgnoreKey =
          new HashSet<>(config.getList(ElasticsearchSinkConnectorConfig.TOPIC_KEY_IGNORE_CONFIG));
      Set<String> topicIgnoreSchema = new HashSet<>(
          config.getList(ElasticsearchSinkConnectorConfig.TOPIC_SCHEMA_IGNORE_CONFIG)
      );

      long flushTimeoutMs =
          config.getLong(ElasticsearchSinkConnectorConfig.FLUSH_TIMEOUT_MS_CONFIG);
      int maxBufferedRecords =
          config.getInt(ElasticsearchSinkConnectorConfig.MAX_BUFFERED_RECORDS_CONFIG);
      int batchSize =
          config.getInt(ElasticsearchSinkConnectorConfig.BATCH_SIZE_CONFIG);
      long lingerMs =
          config.getLong(ElasticsearchSinkConnectorConfig.LINGER_MS_CONFIG);
      int maxInFlightRequests =
          config.getInt(ElasticsearchSinkConnectorConfig.MAX_IN_FLIGHT_REQUESTS_CONFIG);
      long retryBackoffMs =
          config.getLong(ElasticsearchSinkConnectorConfig.RETRY_BACKOFF_MS_CONFIG);
      int maxRetry =
          config.getInt(ElasticsearchSinkConnectorConfig.MAX_RETRIES_CONFIG);
      boolean dropInvalidMessage =
          config.getBoolean(ElasticsearchSinkConnectorConfig.DROP_INVALID_MESSAGE_CONFIG);

      if (client != null) {
        this.client = client;
      } else {
        List<String> address =
            config.getList(ElasticsearchSinkConnectorConfig.CONNECTION_URL_CONFIG);
        final JestClientFactory factory = new JestClientFactory();

        HttpClientConfig.Builder clientConfig = new HttpClientConfig.Builder(address);
        clientConfig.multiThreaded(true);
        String username =
            config.getString(ElasticsearchSinkConnectorConfig.CONNECTION_USERNAME_CONFIG);
        String password =
            config.getString(ElasticsearchSinkConnectorConfig.CONNECTION_PASSWORD_CONFIG);

        if (username != null && password != null) {
          new ElasticsearchAuth(address, clientConfig, username, password).invoke();
        }

        factory.setHttpClientConfig(clientConfig.build());
        this.client = factory.getObject();
      }

      ElasticsearchWriter.Builder builder = new ElasticsearchWriter.Builder(this.client)
          .setType(type)
          .setIgnoreKey(ignoreKey, topicIgnoreKey)
          .setIgnoreSchema(ignoreSchema, topicIgnoreSchema)
          .setTopicToIndexMap(topicToIndexMap)
          .setFlushTimoutMs(flushTimeoutMs)
          .setMaxBufferedRecords(maxBufferedRecords)
          .setMaxInFlightRequests(maxInFlightRequests)
          .setBatchSize(batchSize)
          .setLingerMs(lingerMs)
          .setRetryBackoffMs(retryBackoffMs)
          .setMaxRetry(maxRetry)
          .setDropInvalidMessage(dropInvalidMessage);

      writer = builder.build();
      writer.start();
    } catch (ConfigException e) {
      throw new ConnectException(
          "Couldn't start ElasticsearchSinkTask due to configuration error:",
          e
      );
    }
  }

  @Override
  public void open(Collection<TopicPartition> partitions) {
    log.debug("Opening the task for topic partitions: {}", partitions);
    Set<String> topics = new HashSet<>();
    for (TopicPartition tp : partitions) {
      topics.add(tp.topic());
    }
    writer.createIndicesForTopics(topics);
  }

  @Override
  public void put(Collection<SinkRecord> records) throws ConnectException {
    log.trace("Putting {} to Elasticsearch.", records);
    writer.write(records);
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
    log.trace("Flushing data to Elasticsearch with the following offsets: {}", offsets);
    writer.flush();
  }

  @Override
  public void close(Collection<TopicPartition> partitions) {
    log.debug("Closing the task for topic partitions: {}", partitions);
  }

  @Override
  public void stop() throws ConnectException {
    log.info("Stopping ElasticsearchSinkTask.");
    if (writer != null) {
      writer.stop();
    }
    if (client != null) {
      client.shutdownClient();
    }
  }

  private Map<String, String> parseMapConfig(List<String> values) {
    Map<String, String> map = new HashMap<>();
    for (String value: values) {
      String[] parts = value.split(":");
      String topic = parts[0];
      String type = parts[1];
      map.put(topic, type);
    }
    return map;
  }

}
