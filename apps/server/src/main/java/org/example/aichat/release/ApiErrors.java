package org.example.aichat.release;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null ? "操作失败" : e.getReason())); }
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null ? "输入不合法" : e.getMessage())); }
  @ExceptionHandler(PublicContent.Unavailable.class)
  ResponseEntity<?> publicContent(PublicContent.Unavailable e) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message",e.getMessage(),"code","public_content_unavailable")); }
  @ExceptionHandler(McpConnectionFailure.class)
  ResponseEntity<?> mcpConnection(McpConnectionFailure e) { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message",e.getMessage(),"code",e.code())); }
  @ExceptionHandler(UpstreamHttpFailure.class)
  ResponseEntity<?> modelConnection(UpstreamHttpFailure e) {
    // An upstream 401 is a model credential error, not an expired app session.
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message",UpstreamHttpFailure.message(e.status,e.unsupportedTools),"code",ServiceFailureCategory.classify(e,false)));
  }
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(org.springframework.web.servlet.resource.NoResourceFoundException e) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message","功能地址不存在")); }
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(org.springframework.web.HttpRequestMethodNotSupportedException e) { return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("message","该地址不支持此操作")); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) { return ResponseEntity.internalServerError().body(Map.of("message","服务暂不可用，请稍后重试")); }
}
