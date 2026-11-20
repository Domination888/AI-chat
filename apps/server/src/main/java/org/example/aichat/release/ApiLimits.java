package org.example.aichat.release;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/** Size and request limits apply before JSON decoding, independently of model quotas. */
public class ApiLimits extends OncePerRequestFilter {
  private final RuntimeMode mode;private final Map<Integer,Deque<Instant>> rates=new ConcurrentHashMap<>();
  public ApiLimits(RuntimeMode mode){this.mode=mode;}
  /** Zero reserves this request; a positive value is the remaining wait. */
  private synchronized int reserveOrWaitSeconds(int user){
    var now=Instant.now();rates.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().getLast().isBefore(now.minusSeconds(60)));
    var q=rates.computeIfAbsent(user,k->new ArrayDeque<>());while(!q.isEmpty()&&q.getFirst().isBefore(now.minusSeconds(60)))q.removeFirst();
    if(q.size()>=200)return (int)Math.max(1,(q.getFirst().plusSeconds(60).toEpochMilli()-now.toEpochMilli()+999)/1000);
    q.addLast(now);return 0;
  }
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException {
    long max=!mode.cloud()&&req.getRequestURI().equals("/api/v1/offline/import")?128L*1024*1024:25L*1024*1024;
    if(req.getContentLengthLong()>max){res.setStatus(413);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");res.getWriter().write("{\"message\":\"请求内容过大\"}");return;}
    var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    if(mode.cloud()&&a!=null&&a.getPrincipal() instanceof IdentityService.Identity who){
      int wait=reserveOrWaitSeconds(who.id());
      if(wait>0){res.setStatus(429);res.setHeader("Retry-After",Integer.toString(wait));res.setContentType("application/json");res.setCharacterEncoding("UTF-8");res.getWriter().write("{\"message\":\"操作过于频繁，请在 "+wait+" 秒后重试。\",\"code\":\"request_rate_limited\",\"retryAfterSeconds\":"+wait+"}");return;}
    }
    var input=req.getInputStream();var bounded=new ServletInputStream(){long count;
      private void add(int n)throws IOException{if(n>0&&(count+=n)>max)throw new IOException("Request size limit exceeded");}
      public int read()throws IOException{int n=input.read();if(n!=-1)add(1);return n;}
      public int read(byte[] b,int off,int len)throws IOException{int n=input.read(b,off,len);add(n);return n;}
      public boolean isFinished(){return input.isFinished();}public boolean isReady(){return input.isReady();}public void setReadListener(ReadListener listener){input.setReadListener(listener);}
    };
    chain.doFilter(new HttpServletRequestWrapper(req){public ServletInputStream getInputStream(){return bounded;}public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(bounded,java.nio.charset.StandardCharsets.UTF_8));}},res);
  }
}
