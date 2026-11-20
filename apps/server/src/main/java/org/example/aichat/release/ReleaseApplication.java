package org.example.aichat.release;

import org.example.aichat.rp.RpSessionService;
import org.example.aichat.rp.RpDeliveryService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@Import({RpSessionService.class, RpDeliveryService.class})
@EnableScheduling
public class ReleaseApplication {
  public static void main(String[] args) { SpringApplication.run(ReleaseApplication.class,args); }
}
