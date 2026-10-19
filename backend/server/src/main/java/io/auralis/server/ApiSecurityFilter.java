package io.auralis.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(1)
class ApiSecurityFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String,Bucket> limits=new ConcurrentHashMap<>();
    ApiSecurityFilter(AuthService auth,ObjectMapper json) { this.auth=auth; this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Cache-Control","no-store");
        String path=request.getRequestURI();
        if(!path.startsWith("/api/v1/")) { chain.doFilter(request,response); return; }
        boolean login=path.equals("/api/v1/auth/login")||path.equals("/api/v1/auth/register");
        long minute=System.currentTimeMillis()/60000;
        if(limits.size()>10000) limits.entrySet().removeIf(entry->entry.getValue().minute<minute);
        // Use the socket address, never a spoofable client-supplied X-Forwarded-For value.
        String key=request.getRemoteAddr()+(login?":auth":":api");
        Bucket bucket=limits.compute(key,(k,v)->v==null||v.minute!=minute?new Bucket(minute):v);
        if(!bucket.take(login?20:1000)) { response.setHeader("Retry-After","60"); error(response,429,"Слишком много запросов. Повторите через минуту"); return; }
        if(path.equals("/api/v1/health")||login) { chain.doFilter(request,response); return; }
        String header=request.getHeader("Authorization");
        String token=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;
        var user=auth.authenticate(token);
        if(user.isEmpty()) { error(response,401,"Требуется вход в аккаунт"); return; }
        request.setAttribute("auralis.user",user.get());
        request.setAttribute("auralis.token",token);
        chain.doFilter(request,response);
    }
    private void error(HttpServletResponse r,int status,String message) throws IOException { r.setStatus(status); r.setContentType("application/json;charset=UTF-8"); json.writeValue(r.getOutputStream(),Map.of("error",message)); }
    private static final class Bucket { final long minute; int count; Bucket(long minute){this.minute=minute;} synchronized boolean take(int max){return ++count<=max;} }
}
