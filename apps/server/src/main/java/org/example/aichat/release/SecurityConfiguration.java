package org.example.aichat.release;

import java.util.Arrays;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean SecurityFilterChain security(HttpSecurity http,IdentityService identities,RuntimeMode mode) throws Exception {
    http.csrf(c->c.disable()).cors(c->c.configurationSource(corsConfigurationSource(mode))).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'")));
    http.authorizeHttpRequests(a->a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers("/api/health","/api/v1/auth/**","/api/attachments/*/content","/admin/**").permitAll()
      .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated());
    http.exceptionHandling(e->e.accessDeniedHandler((req,res,x)->{res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"message\":\"没有访问权限\"}");}).authenticationEntryPoint((req,res,x)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"请登录或重新连接本地服务\"}");}));
    http.addFilterBefore(new OncePerRequestFilter() {
      @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws java.io.IOException,ServletException {
        var who=identities.authenticate(req);
        if(who!=null) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(who,null,java.util.List.of(new SimpleGrantedAuthority(who.admin()?"ROLE_ADMIN":"ROLE_USER"))));
        chain.doFilter(req,res);
      }
    },AnonymousAuthenticationFilter.class);
    http.addFilterAfter(new ApiLimits(mode),AnonymousAuthenticationFilter.class);
    return http.build();
  }
  @Bean CorsConfigurationSource corsConfigurationSource(RuntimeMode mode) {
    var c=new CorsConfiguration();c.setAllowedOrigins(Arrays.asList(mode.value("allowed-origins","app://.").split(",")));
    c.setAllowedMethods(java.util.List.of("GET","POST","PUT","DELETE","OPTIONS"));
    c.setAllowedHeaders(java.util.List.of("Authorization","Content-Type"));c.setMaxAge(3600L);
    var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
  }
  public static IdentityService.Identity current() {
    var a=SecurityContextHolder.getContext().getAuthentication();
    if(a==null || !(a.getPrincipal() instanceof IdentityService.Identity who)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
    return who;
  }
}
