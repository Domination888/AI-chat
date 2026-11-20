package org.example.aichat.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent review regressions: input cardinality and current user intent are not output contracts. */
class SkillOutputContractReviewTest {
  private final ObjectMapper json=new ObjectMapper();
  private Optional<SkillOutputContract> contract(String instruction,String message){
    return SkillOutputContract.from(List.of(json.valueToTree(Map.of("instructions",instruction))),message);
  }
  @Test void inputObjectQuantityDoesNotImposeAnAnswerCount(){
    assertTrue(contract("用户带3个苹果时，提醒先洗手。","我带了3个苹果。").isEmpty());
    assertTrue(contract("看到用户用3个例子介绍经历时，温和回应。","我用3个例子讲讲经历。").isEmpty());
  }
  @Test void inputQuantityDoesNotMaskTheActualOutputRequirement(){
    var result=contract("用户带3个苹果时，给2条清洗建议，再问一个问题。","我带了3个苹果。").orElseThrow();
    assertEquals(2,result.count());assertEquals(SkillOutputContract.Question.ONE,result.question());
  }
  @Test void inputQuestionsAreNotTheRequestedClosingQuestions(){
    var result=contract("用户说有2个问题时，给3条处理建议，最后问一个问题。","我有2个问题想处理。").orElseThrow();
    assertEquals(3,result.count());assertEquals(SkillOutputContract.Question.ONE,result.question());
  }
  @Test void prohibitedOrAlternativeOutputCountsAreNotExactContracts(){
    assertTrue(contract("不要给3条建议，只问一个问题。","我准备挑照片。").isEmpty());
    assertTrue(contract("给2或3条选片建议。","我准备挑照片。").isEmpty());
  }
  @Test void currentLiteralReplyIntentDisablesTheOlderListFormat(){
    assertTrue(contract("用户说到挑照片时恰好给3条选片建议，再问一个简短问题。","挑照片，先别给建议，只回复收到了。").isEmpty());
  }
  @Test void currentExplicitOutputCountMayOverrideOrDisableTheOlderCount(){
    var result=contract("用户说到挑照片时恰好给3条选片建议，再问一个简短问题。","请改成给我2条挑照片建议。");
    assertTrue(result.isEmpty()||result.get().count()==2,"The current user's explicit two-item request must not be forced into three items");
  }
  @Test void missingImagesOrInputPhotoCountsDoNotCancelTheSkill(){
    String instruction="用户说到挑照片时恰好给3条选片建议，再问一个简短问题。";
    assertEquals(3,contract(instruction,"我正在挑照片，帮我决定从哪里开始。还没有传照片。").orElseThrow().count());
    assertEquals(3,contract(instruction,"我有2张照片，正在挑照片。").orElseThrow().count());
  }
}
