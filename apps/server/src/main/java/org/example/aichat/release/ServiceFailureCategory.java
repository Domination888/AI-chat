package org.example.aichat.release;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;

/** Fixed operational codes only; never persists exception text or user data. */
public final class ServiceFailureCategory {
  private ServiceFailureCategory() {}
  private static final Pattern HTTP=Pattern.compile("(?:模型请求失败|模型连接失败|内部模型任务失败|当前模型不支持 MCP 工具调用)（HTTP (\\d{3})）");
  public static String classify(Exception failure,OpenAiTransport.Control control){
    if(control.stopped.get())return control.cancellationCause()==null?"operation_interrupted":control.cancellationCause().category();
    return classify(failure,false);
  }
  public static String classify(Exception failure,boolean interrupted){
    if(interrupted)return "user_interrupted";
    var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
    var chain=new ArrayList<Throwable>();for(Throwable e=failure;e!=null&&seen.add(e)&&chain.size()<16;e=e.getCause())chain.add(e);
    for(var e:chain)if(e instanceof UpstreamHttpFailure f)return f.status==401||f.status==403?"upstream_auth_failed":f.status==429?"upstream_rate_limited":f.status>=500?"upstream_unavailable":f.unsupportedTools?"model_tools_unsupported":"upstream_request_rejected";
    if(chain.stream().anyMatch(e->e instanceof DataAccessException))return "storage_failed";
    if(chain.stream().anyMatch(e->e instanceof HttpTimeoutException||e instanceof SocketTimeoutException||e instanceof TimeoutException))return "network_timeout";
    if(chain.stream().anyMatch(e->e instanceof ConnectException||e instanceof UnknownHostException||e instanceof NoRouteToHostException))return "network_connection_failed";
    for(var e:chain){
      String message=Objects.toString(e.getMessage(),"");
      if(e instanceof IOException){var match=HTTP.matcher(message);if(match.matches()){
        int status=Integer.parseInt(match.group(1));
        if(status==401||status==403)return "upstream_auth_failed";
        if(status==429)return "upstream_rate_limited";
        if(status>=500)return "upstream_unavailable";
        return "upstream_request_rejected";
      }
      if(message.equals("模型没有返回可见内容"))return "model_empty_output";
      if(message.equals("模型连接提前结束，请重试"))return "model_stream_incomplete";
      if(message.startsWith("当前模型服务未接受严格技能输出格式（HTTP ")||message.startsWith("模型服务未接受严格技能输出格式（HTTP 400）"))return "model_output_contract";
      }
      if(e instanceof IllegalArgumentException&&message.equals(SkillOutputContract.invalid().getMessage()))return "model_output_contract";
    }
    if(failure instanceof IllegalArgumentException)return "invalid_input";
    return "internal_failed";
  }
}
