package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ModelConnectionApiErrorTest {
  @Test void actualConnectionEndpointKeepsAppSessionAndReturnsOnlySafeUpstreamReason()throws Exception {
    var identity=new IdentityService.Identity(9,"fixture","fixture@example.test",false);
    for(int upstream:List.of(401,403,429,503)){
      new StructuredOutputTransportTest().fixture(upstream,"{\"error\":\"secret-body-and-key\"}",i->"",f->{
        var connections=mock(ModelConnections.class);when(connections.forUser(9)).thenReturn(f.connection());
        var mvc=MockMvcBuilders.standaloneSetup(new StoryApi(null,null,null,connections,f.transport(),null,null)).setControllerAdvice(new ApiErrors()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));
        try{
          var response=mvc.perform(post("/api/v1/me/model-connection/test")).andExpect(status().isBadGateway()).andReturn().getResponse();
          var body=new ObjectMapper().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
          assertEquals(upstream==401||upstream==403?"upstream_auth_failed":upstream==429?"upstream_rate_limited":"upstream_unavailable",body.path("code").asText());
          assertTrue(body.path("message").asText().contains("HTTP "+upstream));
          assertFalse(body.toString().contains("secret-body-and-key"));
          assertEquals(identity,SecurityConfiguration.current());
          assertEquals(1,f.requests().size());
        }catch(Exception e){throw new RuntimeException(e);}finally{SecurityContextHolder.clearContext();}
      });
    }
  }
  @Test void unexpectedErrorsStillDoNotExposeArbitraryExceptionText(){
    var response=new ApiErrors().unexpected(new java.io.IOException("private-key-in-unexpected-message"));
    assertEquals(500,response.getStatusCode().value());
    assertEquals("服务暂不可用，请稍后重试",((Map<?,?>)response.getBody()).get("message"));
  }
}
