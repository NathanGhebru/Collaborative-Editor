package com.collaborativeeditor.config;

import com.collaborativeeditor.service.realtime.RealtimeHandshakeInterceptor;
import com.collaborativeeditor.service.realtime.RealtimeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final RealtimeHandshakeInterceptor realtimeHandshakeInterceptor;

    public WebSocketConfig(
            RealtimeWebSocketHandler realtimeWebSocketHandler,
            RealtimeHandshakeInterceptor realtimeHandshakeInterceptor) {
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.realtimeHandshakeInterceptor = realtimeHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler, "/ws/v1/documents/*")
                .addInterceptors(realtimeHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
