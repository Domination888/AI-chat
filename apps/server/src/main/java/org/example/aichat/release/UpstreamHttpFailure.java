package org.example.aichat.release;
import java.io.IOException;
import java.util.Set;
/** Safe upstream status; response bodies and credentials never become error messages. */
final class UpstreamHttpFailure extends IOException {
 final int status;final boolean unsupportedTools;
 UpstreamHttpFailure(int status,boolean unsupportedTools){super(message(status,unsupportedTools));this.status=status;this.unsupportedTools=unsupportedTools;}
 static String message(int status,boolean tools){
  String reason=status==401||status==403?"模型服务认证失败，请检查 API 密钥及访问权限":status==429?"模型服务请求限流，请稍后重试":status>=500?"模型服务暂不可用，请稍后重试":tools?"模型服务明确表示不支持工具调用，请更换模型或关闭工具":"模型服务拒绝了请求，请检查模型配置";
  return reason+"（HTTP "+status+"）";
 }
 static boolean unsupportedCode(String code){return Set.of("unsupported_tools","tools_not_supported","unsupported_tool_choice","tool_choice_not_supported").contains(code);}
}
