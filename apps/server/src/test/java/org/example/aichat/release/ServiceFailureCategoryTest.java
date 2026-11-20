package org.example.aichat.release;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
class ServiceFailureCategoryTest {
 @Test void fixedCodesDistinguishKnownFailures(){
  assertEquals("upstream_auth_failed",ServiceFailureCategory.classify(new IOException("模型请求失败（HTTP 401）"),false));
  assertEquals("upstream_rate_limited",ServiceFailureCategory.classify(new IOException("模型请求失败（HTTP 429）"),false));
  assertEquals("upstream_unavailable",ServiceFailureCategory.classify(new IOException("当前模型不支持 MCP 工具调用（HTTP 502）"),false));
  assertEquals("network_timeout",ServiceFailureCategory.classify(new IOException("private",new HttpTimeoutException("secret")),false));
  assertEquals("network_connection_failed",ServiceFailureCategory.classify(new ConnectException("private"),false));
  assertEquals("storage_failed",ServiceFailureCategory.classify(new DataAccessResourceFailureException("secret"),false));
  assertEquals("model_output_contract",ServiceFailureCategory.classify(SkillOutputContract.invalid(),false));
  assertEquals("model_empty_output",ServiceFailureCategory.classify(new IOException("模型没有返回可见内容"),false));
  assertEquals("invalid_input",ServiceFailureCategory.classify(new IllegalArgumentException("private"),false));
 }
 @Test void unknownMessagesNeverClaimSpecificStagesOrEchoSecrets(){
  assertEquals("internal_failed",ServiceFailureCategory.classify(new IOException("secret https://private HTTP 401"),false));
  assertEquals("internal_failed",ServiceFailureCategory.classify(new IOException("模型请求失败（HTTP 401） secret"),false));
  assertEquals("user_interrupted",ServiceFailureCategory.classify(new IOException("secret"),true));
 }
}
