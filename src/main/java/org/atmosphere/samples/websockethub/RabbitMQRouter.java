/*
 * Copyright 2012 Jeanfrancois Arcand
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
 */
package org.atmosphere.samples.websockethub;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.MessageProperties;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RabbitMQRouter implements AtmosphereConfig.ShutdownHook {
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQRouter.class);

    private static RabbitMQRouter factory;

    public static final String PARAM_HOST = RabbitMQRouter.class.getName() + ".host";
    public static final String PARAM_USER = RabbitMQRouter.class.getName() + ".user";
    public static final String PARAM_PASS = RabbitMQRouter.class.getName() + ".password";
    public static final String PARAM_EXCHANGE_TYPE = RabbitMQRouter.class.getName() + ".exchange";
    public static final String PARAM_VHOST = RabbitMQRouter.class.getName() + ".vhost";
    public static final String PARAM_PORT = RabbitMQRouter.class.getName() + ".port";
    public static final String EXCHANGE_NAME = RabbitMQRouter.class.getName() + ".exchangeName";
    public static final String ROUTING_KEY = RabbitMQRouter.class.getName() + ".routingKeys";


    private final ConnectionFactory connectionFactory;
    private final Connection connection;
    private final Channel channel;
    private String exchange;
    private final List<String> amqRoutingKey = new LinkedList<String>();
    private String queueName;
    private String consumerTag;
    private String exchangeName;

    private final EndpointMapper<Broadcaster> mapper = new DefaultEndpointMapper<Broadcaster>();
    private final Map<String, Broadcaster> broadcasters = new ConcurrentHashMap<String, Broadcaster>();
    private final ObjectMapper oMapper = new ObjectMapper();

    public RabbitMQRouter(AtmosphereConfig config) {

        String s = config.getInitParameter(PARAM_EXCHANGE_TYPE);
        if (s != null) {
            exchange = s;
        } else {
            exchange = "topic";
        }

        String host = config.getInitParameter(PARAM_HOST);
        if (host == null) {
            host = "127.0.0.1";
        }

        String vhost = config.getInitParameter(PARAM_VHOST);
        if (vhost == null) {
            vhost = "/";
        }

        String user = config.getInitParameter(PARAM_USER);
        if (user == null) {
            user = "guest";
        }

        String port = config.getInitParameter(PARAM_PORT);
        if (port == null) {
            port = "5672";
        }

        String password = config.getInitParameter(PARAM_PASS);
        if (password == null) {
            password = "guest";
        }

        exchangeName = config.getInitParameter(EXCHANGE_NAME);
        if (exchangeName == null) {
            exchangeName = "atmosphere." + exchange;
        }

        s = config.getInitParameter(ROUTING_KEY);
        if (s != null) {
            String[] rk = s.split(",");
            for (String r : rk) {
                amqRoutingKey(r);
            }
        } else {
            amqRoutingKey("atmosphere.all");
        }


        try {
            logger.debug("Create Connection Factory");
            connectionFactory = new ConnectionFactory();
            connectionFactory.setUsername(user);
            connectionFactory.setPassword(password);
            connectionFactory.setVirtualHost(vhost);
            connectionFactory.setHost(host);
            connectionFactory.setPort(Integer.valueOf(port));

            logger.debug("Try to acquire a connection ...");
            connection = connectionFactory.newConnection(ExecutorsFactory.getMessageDispatcher(config, "connectionFactory"));
            channel = connection.createChannel();

            logger.debug("Topic creation '{}'...", exchangeName);
            channel.exchangeDeclare(exchangeName, exchange);
        } catch (Exception e) {
            String msg = "Unable to configure RabbitMQBroadcaster";
            logger.error(msg, e);
            throw new RuntimeException(msg, e);
        }
        config.shutdownHook(this);

        routeIn();
    }

    public String register(Broadcaster broadcaster) {
        broadcasters.put(broadcaster.getID(), broadcaster);
        return broadcaster.getID();
    }

    public RabbitMQRouter deliver(String amqRoutingKey, String broadcasterRoutingKey, String message) {
        try {
            channel.basicPublish(exchangeName, amqRoutingKey,
                    MessageProperties.PERSISTENT_TEXT_PLAIN, oMapper.writeValueAsBytes(new Message(broadcasterRoutingKey, message)));
        } catch (IOException e) {
            logger.warn("Failed to send message over RabbitMQ", e);
        }
        return this;
    }

    public RabbitMQRouter deliver(String broadcasterRoutingKey, String message) {
        for (String k : amqRoutingKey) {
            deliver(k, broadcasterRoutingKey, message);
        }
        return this;
    }

    private void routeIn() {
        try {
            if (consumerTag != null) {
                logger.debug("Delete consumer {}", consumerTag);
                channel.basicCancel(consumerTag);
                consumerTag = null;
            }

            if (queueName != null) {
                logger.debug("Delete queue {}", queueName);
                for (String k : amqRoutingKey) {
                    channel.queueUnbind(queueName, exchangeName, k);
                }
                channel.queueDelete(queueName);
                queueName = null;
            }

            queueName = channel.queueDeclare().getQueue();
            for (String k : amqRoutingKey) {
                channel.queueBind(queueName, exchangeName, k);
            }

            logger.info("Create AMQP consumer on queue {}, for routing key {}", queueName, amqRoutingKey);

            DefaultConsumer queueConsumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag,
                                           Envelope envelope,
                                           AMQP.BasicProperties properties,
                                           byte[] body)
                        throws IOException {

                    // Not for us.
//                    if (!amqRoutingKey.contains(envelope.getRoutingKey())) {
//                        logger.debug("Invalid RoutingKey {}. Available one {}", envelope.getRoutingKey(), amqRoutingKey);
//                        return;
//                    }

                    try {
                        JsonNode rootNode = oMapper.readValue(body, JsonNode.class);
                        String key = rootNode.get("routingKey").asText();
                        if (key == null) {
                            logger.error("Missing key {}", new String(body));
                            return;
                        }

                        String message = rootNode.get("message").asText();

                        if (message == null) {
                            logger.error("Missing message {}", new String(body));
                            return;
                        }

                        if (message.isEmpty()) {
                            message = rootNode.get("message").toString();
                        }

                        // Retrieve the Broadcaster associated with this message
                        Broadcaster b = mapper.map(key, broadcasters);
                        if (b == null) {
                            logger.warn("No Broadcaster Found for Message {}", message);
                        } else {
                            b.broadcast(message);
                        }
                    } catch (Exception ex) {
                        logger.error("", ex);
                        logger.error("Unable to decode {}", new String(body));
                    }
                }
            };

            consumerTag = channel.basicConsume(queueName, true, queueConsumer);
            logger.info("Consumer " + consumerTag + " for queue {}, on routing key {}", queueName, amqRoutingKey);

        } catch (Throwable ex) {
            String msg = "Unable to initialize RabbitMQBroadcaster";
            logger.error(msg, ex);
            throw new IllegalStateException(msg, ex);
        }
    }

    public final static synchronized RabbitMQRouter createOrGet(AtmosphereConfig config) {
        if (factory == null) {
            factory = new RabbitMQRouter(config);
        }
        return factory;
    }

    @Override
    public void shutdown() {
        try {
            if (channel != null && channel.isOpen()) {
                if (consumerTag != null) {
                    channel.basicCancel(consumerTag);
                }
            }
            channel.close();
            connection.close();
        } catch (IOException e) {
            logger.trace("", e);
        }
    }

    public void unregister(String broadcasterRoutingKey) {
        broadcasters.remove(broadcasterRoutingKey);
    }


    public RabbitMQRouter exchangeName(String exchangeName) {
        this.exchangeName = exchangeName;
        return this;
    }

    public RabbitMQRouter consumerTag(String consumerTag) {
        this.consumerTag = consumerTag;
        return this;
    }

    public RabbitMQRouter queueName(String queueName) {
        this.queueName = queueName;
        return this;
    }

    public RabbitMQRouter exchange(String exchange) {
        this.exchange = exchange;
        return this;
    }

    public RabbitMQRouter amqRoutingKey(String amqRoutingKey) {
        this.amqRoutingKey.add(amqRoutingKey);
        return this;
    }

    public static final class Message {

        public String routingKey;
        public String message;

        public Message() {
        }

        public Message(String routingKey, String message) {
            this.routingKey = routingKey;
            this.message = message;
        }

        private void setMessage(String message) {
            this.message = message;
        }

        private void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }

        private String getMessage() {
            return message;
        }

        private String getRoutingKey() {
            return routingKey;
        }
    }
}
