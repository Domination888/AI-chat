package org.example.aichat.release.cloud;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Controller @ConditionalOnProperty(name="story.mode",havingValue="cloud")
public class AdminPage {
  @GetMapping({"/admin","/admin/"}) public String page(){return "forward:/admin/index.html";}
}
