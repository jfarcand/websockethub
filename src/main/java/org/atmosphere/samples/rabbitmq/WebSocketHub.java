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
package org.atmosphere.samples.rabbitmq;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandlerAdapter;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@WebSocketHandlerService(
        broadcasterCache = UUIDBroadcasterCache.class,
        interceptors = {AtmosphereResourceStateRecovery.class, HeartbeatInterceptor.class},
        path = "/{email}")
public class WebSocketHub extends WebSocketHandlerAdapter {

    private final Logger logger = LoggerFactory.getLogger(WebSocketHub.class);
    private RabbitMQRouter router;
    private String routingKey;

    @Override
    public void onTextMessage(final WebSocket webSocket, String data) throws IOException {
        router.deliver(routingKey, data);
    }

    @Override
    public void onOpen(final WebSocket webSocket) throws IOException {
        AtmosphereResource r = webSocket.resource();
        router = RabbitMQRouter.createOrGet(r.getAtmosphereConfig());
        routingKey = router.register(r.getBroadcaster());
    }

    @Override
    public void onClose(WebSocket webSocket) {
        if (router != null) {
            router.unregister(routingKey);
        }
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.error("", t);
    }
}
