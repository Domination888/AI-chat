package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillLiteralClosingRound11Test {
  private final ObjectMapper json=new ObjectMapper();
  private final String original="给3条观星准备事项，并以“清单已备好”结尾。";
  private Optional<SkillOutputContract> parse(String instruction,String latest){return SkillOutputContract.from(List.<JsonNode>of(json.valueToTree(Map.of("instructions",instruction))),latest);}
  private JsonNode result(String closing,String question){return json.valueToTree(Map.of("items",List.of("准备器材","检查环境","安排时间"),"closing",closing,"question",question));}
  @Test void originalUiInstructionRequiresExactModelSuppliedLiteral(){
    var contract=parse(original,"请给我观星清单。").orElseThrow();
    assertEquals("清单已备好",json.valueToTree(contract.responseFormat()).at("/json_schema/schema/properties/closing/enum/0").asText());
    assertTrue(contract.render(result("清单已备好","")).endsWith("清单已备好"));
    for(String bad:List.of("","清单已备好。"," 清单已备好","<开心>清单已备好"))assertThrows(IllegalArgumentException.class,()->contract.render(result(bad,"")));
    assertThrows(IllegalArgumentException.class,()->contract.render(result("清单已备好","还需要什么？")));
  }
  @Test void eachSupportedQuotePairAndLiteralNumbersAreGeneric(){
    for(String[] pair:List.of(new String[]{"“","”"},new String[]{"‘","’"},new String[]{"\"","\""},new String[]{"'","'"},new String[]{"「","」"},new String[]{"『","』"})){
      var contract=parse("给3条建议，以"+pair[0]+"给8条也可"+pair[1]+"结尾。","开始").orElseThrow();
      assertEquals(3,contract.count());assertEquals("给8条也可",contract.literal());
    }
  }
  @Test void latestClosingAndQuestionAxesDoNotRequireRepeatedCount(){
    var replacement=parse(original,"最后一句为‘准备完毕’。").orElseThrow();assertEquals(3,replacement.count());assertEquals("准备完毕",replacement.literal());
    var both=parse(original,"给2条建议，以\"已经准备\"结尾。").orElseThrow();assertEquals(2,both.count());assertEquals("已经准备",both.literal());
    for(String cancel:List.of("不用固定尾句。","取消尾句。","不要结尾。"))assertEquals(SkillOutputContract.Closing.NONE,parse(original,cancel).orElseThrow().closing());
    assertEquals("清单已备好",parse(original,"不要总结。").orElseThrow().literal());
    assertTrue(parse(original,"不要列表，只回复收到。").isEmpty());
    var noQuestions=parse(original,"不要问我问题。").orElseThrow();assertEquals(SkillOutputContract.Question.NONE,noQuestions.question());
    assertThrows(IllegalArgumentException.class,()->noQuestions.render(json.valueToTree(Map.of("items",List.of("器材？","环境","时间"),"closing","清单已备好","question",""))));
  }
  @Test void explicitQuestionComesBeforeLiteralAndConflictingLastInstructionsExit(){
    var contract=parse("给3条建议，再问一个问题，并以“准备完毕”结尾。","开始").orElseThrow();
    assertEquals(SkillOutputContract.Question.ONE,contract.question());assertTrue(contract.render(result("准备完毕","先准备什么？")).endsWith("先准备什么？\n\n准备完毕"));
    assertTrue(parse("给3条建议，最后问一个问题，并以“准备完毕”结尾。","开始").isEmpty());
    assertTrue(parse("给3条建议，不要问问题，以“准备好了吗？”结尾。","开始").isEmpty());
  }
  @Test void summariesAndOneQuestionKeepTheirExistingOrder(){
    var contract=parse("给3条建议，最后用一句总结，再问一个问题。","开始").orElseThrow();
    assertTrue(contract.summary());assertTrue(contract.render(result("先准备再行动。","先做哪项？")).endsWith("先准备再行动。\n\n先做哪项？"));
    assertFalse(parse("给3条建议，最后用一句总结，再问一个问题。","不要总结。").orElseThrow().summary());
  }
  @Test void ambiguousConditionalQuotedFactsAndUnsafeLiteralsAreConservative(){
    for(String instruction:List.of("给3条建议，如果下雨以“准备完毕”结尾。","给3条建议，原文提到以“准备完毕”结尾。","给3条建议，如果下雨，请以“准备完毕”结尾。","给3条建议，这篇文章以“准备完毕”结尾。","给3条建议，以“甲”结尾，以“乙”结尾。","给3条建议，以“<开心>准备完毕”结尾。","给3条建议，以“<scene>准备完毕</scene>”结尾。","给3条建议，以“"+"长".repeat(601)+"”结尾。"))assertTrue(parse(instruction,"开始").isEmpty(),instruction);
    assertEquals(3,parse("给3条建议，参考原话“给8条建议”。","开始").orElseThrow().count());
    assertTrue(SkillOutputContract.from(List.<JsonNode>of(json.valueToTree(Map.of("instructions","给3条建议，以“甲”结尾。")),json.valueToTree(Map.of("instructions","给3条建议，以“乙”结尾。"))),"开始").isEmpty());
  }
  @Test void noneScansAllFieldsAndWrongLiteralNeverPasses(){
    var contract=parse("给3条建议，不要问问题，最后用一句总结。","开始").orElseThrow();
    assertThrows(IllegalArgumentException.class,()->contract.render(result("准备好了吗？","")));
    assertThrows(IllegalArgumentException.class,()->contract.render(result("准备完毕","准备好了吗？")));
    var literal=parse(original,"不要问问题。").orElseThrow();assertThrows(IllegalArgumentException.class,()->literal.render(result("准备好了吗？","")));
  }
}
