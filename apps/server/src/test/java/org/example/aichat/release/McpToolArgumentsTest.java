package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

class McpToolArgumentsTest {
 private final ObjectMapper json=new ObjectMapper();
 private JsonObjectSchema schema(){return JsonObjectSchema.builder().addProperty("classes",JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build()).required("classes").build();}
 private McpClient client(){var c=mock(McpClient.class);when(c.listTools()).thenReturn(List.of(ToolSpecification.builder().name("lookup_class").parameters(schema()).build()));return c;}
 @Test void missingRequiredClassListIsActionableAndNeverCallsRemoteTool()throws Exception{
  var client=client();var error=assertThrows(IllegalArgumentException.class,()->McpRegistry.executeClient(client,"lookup_class",json.readTree("{}")));
  assertTrue(error.getMessage().contains("classes"));assertTrue(error.getMessage().contains("数组(array)"));verify(client,never()).executeTool(any());
  assertEquals(400,new ApiErrors().invalid(error).getStatusCode().value());
 }
 @ParameterizedTest @CsvSource(delimiter='|',value={"[]|JSON 对象","null|JSON 对象","{\"classes\":\"flex\"}|数组(array)","{\"classes\":[42]}|classes[0]","{\"classes\":null}|数组(array)"})
 void structuralFailuresDoNotReachRemote(String input,String expected)throws Exception{
  var c=client();var error=assertThrows(IllegalArgumentException.class,()->McpRegistry.executeClient(c,"lookup_class",json.readTree(input)));assertTrue(error.getMessage().contains(expected),error.getMessage());verify(c,never()).executeTool(any());
 }
 @Test void validArgumentsRemainUnchangedAndReturnNormalOutput()throws Exception{
  var c=client();when(c.executeTool(any())).thenReturn(ToolExecutionResult.builder().resultText("display: flex").build());var args=json.readTree("{\"classes\":[\"flex\"],\"optionalExtension\":true}");assertEquals("display: flex",McpRegistry.executeClient(c,"lookup_class",args));verify(c).executeTool(argThat(call->call.name().equals("lookup_class")&&call.arguments().equals(args.toString())));
 }
 @Test void nestedRequiredAndBasicTypesHaveFieldPaths()throws Exception{
  var spec=JsonObjectSchema.builder().addProperty("options",JsonObjectSchema.builder().addBooleanProperty("enabled").addIntegerProperty("count").addNumberProperty("ratio").required("enabled","count","ratio").build()).required("options").build();
  for(String input:List.of("{\"options\":{}}","{\"options\":{\"enabled\":1,\"count\":2,\"ratio\":0.5}}","{\"options\":{\"enabled\":true,\"count\":2.5,\"ratio\":0.5}}","{\"options\":{\"enabled\":true,\"count\":2,\"ratio\":\"half\"}}"))assertTrue(assertThrows(IllegalArgumentException.class,()->McpToolArguments.validate(spec,json.readTree(input))).getMessage().contains("options."));
  assertDoesNotThrow(()->McpToolArguments.validate(spec,json.readTree("{\"options\":{\"enabled\":true,\"count\":2,\"ratio\":0.5}}")));
 }
 @Test void remoteParameterAndToolErrorsUseLocalSafeMessages()throws Exception{
  var c=client();var args=json.readTree("{\"classes\":[\"flex\"]}");when(c.executeTool(any())).thenThrow(new ToolArgumentsException("Authorization: token-private remote-body",-32602));var invalid=assertThrows(IllegalArgumentException.class,()->McpRegistry.executeClient(c,"lookup_class",args));assertTrue(invalid.getMessage().contains("工具拒绝"));assertFalse(invalid.getMessage().contains("private"));
  doThrow(new ToolExecutionException("remote-body token-private")).when(c).executeTool(any());var remote=assertThrows(ResponseStatusException.class,()->McpRegistry.executeClient(c,"lookup_class",args));assertEquals(502,remote.getStatusCode().value());assertTrue(remote.getReason().contains("工具执行失败"));assertFalse(remote.getReason().contains("remote-body"));assertFalse(remote.getReason().contains("token-private"));
 }
}
