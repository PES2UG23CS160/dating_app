package com.unfold;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
class AbuseLimits extends OncePerRequestFilter {
 private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
 private record Window(long start,int count){}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String path=request.getRequestURI();boolean auth=path.matches("/api/auth/(login|register|google)");boolean photo=path.equals("/api/me/photo");
  if(request.getMethod().equals("POST")&&(auth||photo)){
   long now=System.currentTimeMillis();windows.entrySet().removeIf(e->now-e.getValue().start()>60000);String key=request.getRemoteAddr()+(auth?":auth":":photo");
   if(windows.size()>10000&&!windows.containsKey(key)){response.setStatus(429);return;}
   Window w=windows.compute(key,(k,v)->v==null||now-v.start()>60000?new Window(now,1):new Window(v.start(),v.count()+1));
   if(w.count()>(auth?30:6)){response.setStatus(429);response.setContentType("application/json");response.getWriter().write("{\"message\":\"Please wait a minute before trying again\"}");return;}
  }chain.doFilter(request,response);
 }
}
