package org.example.aichat.release;

import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** Public MCP diagnostics contain only locally authored text and numeric HTTP status. */
final class McpConnectionFailure extends RuntimeException {
  private final String code;
  private McpConnectionFailure(String code,String message){super(message);this.code=code;}
  String code(){return code;}
  static McpConnectionFailure http(int status){
    return switch(status){
      case 401 -> new McpConnectionFailure("mcp_auth_required","MCP 服务要求鉴权（HTTP 401）。请检查此服务的请求头或凭据，再重新连接；无需重新登录幕间。");
      case 403 -> new McpConnectionFailure("mcp_access_denied","MCP 服务或出站代理拒绝访问（HTTP 403）。请检查服务权限与管理员的出口规则。");
      case 404 -> new McpConnectionFailure("mcp_endpoint_missing","MCP 地址不存在（HTTP 404）。请核对服务方提供的 MCP HTTP 或旧版 SSE 端点。");
      case 410 -> new McpConnectionFailure("mcp_endpoint_retired","MCP 端点已停用（HTTP 410）。请向服务方确认新地址；支持 Streamable HTTP 的服务请选择“HTTP”，旧协议请选择“旧版 HTTP SSE”。");
      case 405 -> new McpConnectionFailure("mcp_transport_mismatch","MCP 端点不接受此请求方式（HTTP 405）。请按服务方协议选择 HTTP（Streamable HTTP）或旧版 HTTP SSE；地址后缀不能决定协议。");
      case 429 -> new McpConnectionFailure("mcp_rate_limited","MCP 服务请求限流（HTTP 429）。请稍后重新连接。");
      default -> new McpConnectionFailure("mcp_http_error","MCP 连接请求失败（HTTP "+status+"）。"+(status>=300&&status<400?"该地址要求跳转，请核对最终的 MCP 地址。":"请核对服务状态后重试。"));
    };
  }
  static McpConnectionFailure protocol(){return new McpConnectionFailure("mcp_transport_mismatch","MCP 地址未返回所选协议的数据。请按服务方说明选择 HTTP（Streamable HTTP）或旧版 HTTP SSE，并核对对应端点。");}
  static McpConnectionFailure protocolVersion(){return new McpConnectionFailure("mcp_protocol_version","MCP 服务协商的协议版本不兼容。请确认服务支持 2025-06-18 或较早的兼容版本。");}
  static McpConnectionFailure sessionExpired(){return new McpConnectionFailure("mcp_session_expired","MCP 会话已失效，重新初始化后仍被拒绝。请重新连接此服务后再试。");}
  static McpConnectionFailure messageLimit(){return new McpConnectionFailure("mcp_message_too_large","MCP 返回的消息超过大小或数量限制。请缩小请求范围后重试。");}
  static McpConnectionFailure closed(){return new McpConnectionFailure("mcp_connection_failed","MCP 连接尚未就绪或已关闭，请重新连接此服务。");}
  static McpConnectionFailure busy(){return new McpConnectionFailure("mcp_busy","MCP 待处理请求过多，请稍后重试。");}
  static McpConnectionFailure from(Throwable error){
    Throwable cause=error;
    for(int depth=0;cause!=null&&depth<20;depth++,cause=cause.getCause()){
      if(cause instanceof McpConnectionFailure known)return known;
      if(cause instanceof TimeoutException||cause instanceof HttpTimeoutException||cause instanceof SocketTimeoutException)return new McpConnectionFailure("mcp_timeout","MCP 连接或初始化超时。请检查服务是否在线及网络连接，再重试。");
      if(cause instanceof SSLException)return new McpConnectionFailure("mcp_tls_error","MCP 的 HTTPS 证书校验失败。请服务管理员检查证书和域名配置；不会跳过安全校验。");
      if(cause instanceof UnknownHostException||cause instanceof UnresolvedAddressException)return new McpConnectionFailure("mcp_dns_error","MCP 域名无法解析。请检查地址及公网 DNS 配置后重试。");
      if(cause instanceof ConnectException)return new McpConnectionFailure("mcp_connection_failed","无法连接 MCP 服务。请确认服务已启动、地址与端口正确，并检查网络或出站代理。");
    }
    return new McpConnectionFailure("mcp_connection_failed","MCP 连接或初始化失败。请确认服务可用，核对 HTTP 或旧版 SSE 协议后重新连接。");
  }
}
