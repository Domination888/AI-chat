package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillOutputContractTest {
  final ObjectMapper json=new ObjectMapper();
  Optional<SkillOutputContract> parse(String... instructions){return SkillOutputContract.from(Arrays.stream(instructions).<JsonNode>map(s->json.valueToTree(Map.of("instructions",s))).toList(),"我在挑照片，准备整理行李");}
  @Test void arabicAndCommonChineseNumbersHaveGenericBounds(){
    for(var row:Map.of("1",1,"两",2,"三",3,"4",4,"十",10,"十一",11,"十二",12).entrySet())assertEquals(row.getValue(),parse("请列出"+row.getKey()+"条旅行建议").orElseThrow().count());
    for(String n:List.of("0","13","二十"))assertTrue(parse("请给"+n+"条建议").isEmpty());
  }
  @Test void questionAndSummaryCountsAreNotMistakenForItemCounts(){
    var photo=parse("用户说到挑照片时恰好给3条选片建议，再问一个简短问题；定时开场时提醒休息眼睛。").orElseThrow();assertEquals(3,photo.count());assertEquals(SkillOutputContract.Question.ONE,photo.question());
    var summary=parse("给两条整理建议，最后用一句总结，不要问问题。").orElseThrow();assertEquals(2,summary.count());assertTrue(summary.summary());assertEquals(SkillOutputContract.Question.NONE,summary.question());
  }
  @Test void conflictsRangesAndUnresolvedConditionsAreNotGuessed(){
    for(String s:List.of("给3条建议，再给4条步骤。","至少给3条建议。","给3到4条建议。","如果下雨给3条建议。","用户说到骑车时给3条建议。","定时开场时给3条建议。","给3条建议，最后问两个问题。","给3条建议，最后问一个问题，不要问问题。"))assertTrue(parse(s).isEmpty(),s);
    assertTrue(parse("给三条建议。","给四条步骤。").isEmpty());assertTrue(parse("请自然聊聊编号3的版本，没有规定输出条数。").isEmpty());
    for(String input:List.of("当用户带3个苹果时，请自然聊天。","当用户给出3个关键词时，帮他解释。","用户输入3个问题后，请自然回答。","使用3个关键词自然聊天。"))assertTrue(parse(input).isEmpty(),input);
    assertEquals(2,parse("当用户给出3个关键词时，用两条建议回应。").orElseThrow().count());
  }
  @Test void exactSchemaAndRenderingFollowTheParsedContract()throws Exception{
    var c=parse("给两条整理建议，再问一个问题。").orElseThrow();JsonNode format=json.valueToTree(c.responseFormat());assertEquals(2,format.at("/json_schema/schema/properties/items/minItems").asInt());assertEquals(2,format.at("/json_schema/schema/properties/items/maxItems").asInt());
    String result=c.render(json.readTree("{\"items\":[\"先确定用途。\",\"再按时间整理。\"],\"closing\":\"\",\"question\":\"你想先从哪步开始？\"}"));assertEquals("1. 先确定用途。\n2. 再按时间整理。\n\n你想先从哪步开始？",result);
  }
  @Test void latestExplicitUserCountAndQuestionPreferenceOverrideTheSkill(){
    var skills=List.<JsonNode>of(json.valueToTree(Map.of("instructions","给三条建议，再问一个问题。")));
    var latest=SkillOutputContract.from(skills,"这次只给两条建议，不要问问题。").orElseThrow();assertEquals(2,latest.count());assertEquals(SkillOutputContract.Question.NONE,latest.question());
    assertTrue(SkillOutputContract.from(skills,"这次不要编号，直接聊天。").isEmpty());
  }
  @Test void latestExplicitNoSummaryOverridesTheSavedClosingRequirement(){
    var skills=List.<JsonNode>of(json.valueToTree(Map.of("instructions","给3条整理建议，最后用一句总结。")));
    for(String request:List.of("整理桌面，只给2条建议，不要总结。","整理桌面，只给2条建议，无需总结。")){
      var contract=SkillOutputContract.from(skills,request).orElseThrow();assertEquals(2,contract.count());assertFalse(contract.summary());assertEquals("",json.valueToTree(contract.responseFormat()).at("/json_schema/schema/properties/closing/enum/0").asText("missing"));
    }
  }
  @Test void wrongCountsEmptyDuplicatePlaceholderAndNonStringItemsFailWithoutPadding()throws Exception{
    var c=parse("给两条建议，不要问问题。").orElseThrow();
    for(String items:List.of("[]","[\"只有一条\"]","[\"\",\"有效\"]","[\"相同\",\"相同\"]","[\"...\",\"有效\"]","[1,\"有效\"]","[\"<scene>{}</scene>\",\"有效\"]","[\"<开心>\",\"有效\"]"))assertThrows(IllegalArgumentException.class,()->c.render(json.readTree("{\"items\":"+items+",\"closing\":\"\",\"question\":\"\"}")),items);
    assertThrows(IllegalArgumentException.class,()->c.render(null));
  }
  @Test void closingAndQuestionMustActuallyMeetTheEndingRequirement()throws Exception{
    var one=parse("给两条建议，最后问一个问题。").orElseThrow();var none=parse("给两条建议，不要问问题。").orElseThrow();var summary=parse("给两条建议，最后用一句总结，不要问问题。").orElseThrow();
    for(String q:List.of("","这不是问题。","先做什么？再做什么？"))assertThrows(IllegalArgumentException.class,()->one.render(json.valueToTree(Map.of("items",List.of("甲","乙"),"closing","","question",q))));
    assertThrows(IllegalArgumentException.class,()->none.render(json.valueToTree(Map.of("items",List.of("甲？","乙"),"closing","","question",""))));
    assertThrows(IllegalArgumentException.class,()->summary.render(json.valueToTree(Map.of("items",List.of("甲","乙"),"closing","","question",""))));
    assertTrue(summary.render(json.valueToTree(Map.of("items",List.of("甲","乙"),"closing","先准备再行动。","question",""))).endsWith("先准备再行动。"));
  }
}
