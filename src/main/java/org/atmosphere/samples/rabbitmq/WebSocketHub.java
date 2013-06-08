/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.samples.rabbitmq;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.util.SimpleBroadcaster;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Singleton
@WebSocketHandlerService(
        broadcaster = SimpleBroadcaster.class,
        broadcasterCache = UUIDBroadcasterCache.class,
        interceptors = {AtmosphereResourceStateRecovery.class, HeartbeatInterceptor.class},
        path = "/")
public class WebSocketHub extends WebSocketHandlerAdapter {

    private final Logger logger = LoggerFactory.getLogger(WebSocketHub.class);
    private RabbitMQRouter router;
    private final static String ROUTING_KEY = "id";

    @Override
    public void onTextMessage(final WebSocket webSocket, String data) throws IOException {
        router.deliver(routingKey(webSocket), data);
    }

    @Override
    public void onOpen(final WebSocket webSocket) throws IOException {
        AtmosphereResource r = webSocket.resource();
        router = RabbitMQRouter.createOrGet(r.getAtmosphereConfig());

        String routingKey = routingKey(webSocket);
        if (routingKey == null) {
            logger.error("Routing Key is null for {}", r.uuid());
            webSocket.close();
            return;
        }

        //Sanity check to make sure nobody is hacking the routingKey
        BroadcasterFactory f = r.getAtmosphereConfig().getBroadcasterFactory();
        if (r.getAtmosphereConfig().getInitParameter("WebSocketHub.checkSecurity") != null) {
            try {
                f.get(routingKey);
            } catch (IllegalStateException ex) {
                logger.error("Routing Key is used for {}", routingKey);
                webSocket.close();
                return;
            }
        }

        router.register(f.lookup(routingKey, true).addAtmosphereResource(r));
    }

    String routingKey(WebSocket webSocket) {
        AtmosphereResource r = webSocket.resource();
        return "/" + r.getRequest().getParameter(ROUTING_KEY);
    }

    @Override
    public void onClose(WebSocket webSocket) {
        if (router != null) {
            router.unregister(routingKey(webSocket));
        }
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.error("", t);
    }
}
