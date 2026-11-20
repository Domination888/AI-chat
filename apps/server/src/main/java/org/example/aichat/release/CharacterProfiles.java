package org.example.aichat.release;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CharacterProfiles {
  private final StoryStore stories;private final ObjectMapper json;
  public CharacterProfiles(StoryStore stories,ObjectMapper json){this.stories=stories;this.json=json;}
  @Transactional public Object importShu(int user,JsonNode input){
    if(input.toString().length()>2*1024*1024||!input.path("card").isObject())throw new IllegalArgumentException("黍角色包缺少角色卡或超过 2MiB");
    var found=stories.db().queryForList("SELECT id FROM role_card WHERE owner_id=? AND role_code='shu' ORDER BY id LIMIT 1",user);int role;
    if(found.isEmpty()){
      var persona=input.path("card").path("persona");String greeting=input.path("greeting").asText("（黍放下手中的谷种，抬眼望向你。）回来了？先歇一歇吧。");
      stories.db().update("INSERT INTO role_card(owner_id,name,role_code,voice_id,avatar,profile,background,personality,greeting) VALUES(?,'黍','shu','shu','','岁兽之一，以耕作为伴，温和从容而有自己的判断。',?,?,?)",user,PromptBudget.trim(persona.toString(),1800),PromptBudget.trim(persona.path("speech").toString(),600),greeting.substring(0,Math.min(2000,greeting.length())));
      role=stories.db().queryForObject("SELECT MAX(id) FROM role_card WHERE owner_id=? AND role_code='shu'",Integer.class,user);
    }else role=((Number)found.get(0).get("id")).intValue();
    stories.db().update("INSERT INTO character_profile(role_id,profile_json) VALUES(?,?) ON CONFLICT(role_id) DO UPDATE SET profile_json=excluded.profile_json",role,input.toString());
    return Map.of("roleId",role,"name","黍","hasLore",input.has("lore"));
  }
  public String context(int role,String message){
    var rows=stories.db().queryForList("SELECT profile_json FROM character_profile WHERE role_id=?",role);if(rows.isEmpty())return "";
    try{
      var profile=json.readTree((String)rows.get(0).get("profile_json"));var card=profile.path("card");var persona=card.path("persona");var out=new StringBuilder("\n【角色核心人设】\n");
      var budgets=Map.of("identity",100,"origin",65,"traits",110,"speech",110,"rules",70);for(String field:List.of("identity","traits","speech","rules","origin"))out.append(field).append('：').append(PromptBudget.trim(persona.path(field).toString(),budgets.get(field))).append('\n');
      if(card.has("mantra"))out.append("表达习惯：").append(PromptBudget.trim(card.path("mantra").asText(),40));
      var lore=new ArrayList<String>();profile.path("lore").properties().forEach(e->{for(String paragraph:e.getValue().asText().split("\\n\\s*\\n"))if(!paragraph.isBlank())lore.add(paragraph);});
      for(String section:List.of("relationships","examples","wardrobe","dragon_bubble")){var value=card.path(section);if(value.isObject())value.properties().forEach(e->lore.add(e.getKey()+"："+e.getValue()));else if(value.isArray())value.forEach(e->lore.add(e.toString()));else if(!value.isMissingNode())lore.add(value.asText());}
      lore.sort(Comparator.comparingDouble((String t)->SearchTerms.overlap(message,t)).reversed());
      for(String text:lore.stream().filter(t->SearchTerms.overlap(message,t)>=0.15).limit(1).toList())out.append("\n【角色背景资料，不代表与用户共同经历】\n").append(PromptBudget.trim(text,140));
      return out.toString();
    }catch(Exception e){throw new IllegalStateException("角色资料无法读取",e);}
  }
}
