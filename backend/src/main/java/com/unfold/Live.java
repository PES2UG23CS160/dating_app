package com.unfold;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
@Configuration @EnableWebSocket @EnableScheduling
class Live extends TextWebSocketHandler implements WebSocketConfigurer {
 private final Map<String,WebSocketSession> sockets=new ConcurrentHashMap<>();
 private final JwtDecoder decoder; private final String origin;
 Live(JwtDecoder decoder,@Value("${unfold.origin}")String origin){this.decoder=decoder;this.origin=origin;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(this,"/ws").setAllowedOrigins(origin);}
 @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{if(!(session.getPrincipal() instanceof JwtAuthenticationToken)){session.close(CloseStatus.NOT_ACCEPTABLE);return;}sockets.put(session.getId(),session);}
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){sockets.remove(session.getId());}
 @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {session.close(CloseStatus.NOT_ACCEPTABLE);}
 void changed(long... ids){Runnable action=()->send(ids);if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){action.run();}});else action.run();}
 private void send(long... ids){for(var s:sockets.values())try{var auth=(JwtAuthenticationToken)s.getPrincipal();decoder.decode(auth.getToken().getTokenValue());long id=Long.parseLong(auth.getName());if(Arrays.stream(ids).anyMatch(i->i==id))synchronized(s){if(s.isOpen())s.sendMessage(new TextMessage("{\"type\":\"refresh\"}"));}}catch(Exception e){close(s);}}
 private void close(WebSocketSession s){sockets.remove(s.getId());try{s.close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}}
 @Scheduled(fixedDelay=15000) void revalidate(){for(var s:sockets.values())try{decoder.decode(((JwtAuthenticationToken)s.getPrincipal()).getToken().getTokenValue());}catch(Exception e){close(s);}}
}
