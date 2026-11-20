package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.example.aichat.rp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.*;

class OfflineMigrationTest {
 @Test void animeStarterUpgradePreservesPrivateCardsAndConversationHistory()throws Exception{
  var data=Files.createTempDirectory("story-anime-upgrade-");
  var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+data.resolve("test.sqlite")));
  var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",data.toString()));
  var migrations=new Migrations(db,mode);migrations.afterPropertiesSet();new StarterLibrary(db,mode);
  var fresh=db.queryForList("SELECT name,role_code,profile,background,personality,greeting FROM role_card ORDER BY id");
  db.update("UPDATE role_card SET name='引路人',profile='旧简介',background='旧背景',personality='旧性格',greeting='旧问候' WHERE role_code='voyager'");
  db.update("UPDATE role_card SET profile='旧简介',background='旧背景',personality='旧性格',greeting='旧问候' WHERE role_code IN ('garden','observatory')");
  db.update("INSERT INTO role_card(id,owner_id,name,role_code,avatar,background) VALUES(99,1,'引路人','voyager','/release-art/voyager-character.png','用户的人设')");
  db.update("INSERT INTO conversation(id,user_id,role_id,title) VALUES('keep',1,3,'原有故事')");
  db.update("INSERT INTO history(id,conversation_id,sender,content) VALUES(1,'keep','user','原有消息')");
  db.update("DELETE FROM schema_version WHERE version=4");
  migrations.afterPropertiesSet();migrations.afterPropertiesSet();new StarterLibrary(db,mode);
  assertEquals(fresh,db.queryForList("SELECT name,role_code,profile,background,personality,greeting FROM role_card WHERE owner_id IS NULL ORDER BY id"));
  assertEquals("用户的人设",db.queryForObject("SELECT background FROM role_card WHERE id=99",String.class));
  assertEquals("引路人",db.queryForObject("SELECT name FROM role_card WHERE id=99",String.class));
  assertEquals("原有消息",db.queryForObject("SELECT content FROM history WHERE conversation_id='keep'",String.class));
  assertEquals(3,db.queryForObject("SELECT role_id FROM conversation WHERE id='keep'",Integer.class));
 }
 @Test void oldWorldsMessagesAndAttachmentsImportWithoutOverwriting()throws Exception{
  var data=Files.createTempDirectory("story-import-");var source=new DriverManagerDataSource("jdbc:sqlite:"+data.resolve("test.sqlite"));var db=new JdbcTemplate(source);var json=new ObjectMapper();var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",data.toString()));new Migrations(db,mode).afterPropertiesSet();new StarterLibrary(db,mode);
  var sessions=new RpSessionService(db,json);var attachments=new Attachments(db,new FilesystemObjects(mode),json,mode);var stories=new StoryStore(db,sessions,attachments,json);var importer=new OfflineImport(stories,attachments,json,mode,new CompanionService(stories,json,new PublicContent(),mode));var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
  String initial=json.writeValueAsString(new RpState()),current="{\"location\":\"花园\",\"events\":[\"一起看花\"]}";
  var input=json.valueToTree(Map.of("format","legacy-ai-chat-v1","roles",List.of(Map.of("id",100,"name","旧角色")),"conversations",List.of(Map.of("id","old","role_id",100,"user_id",88)),"rp_world",List.of(Map.of("id","old-world","role_id",100,"user_id",88,"state_json",current,"initial_json",initial)),"rp_session",List.of(Map.of("conversation_id","old","world_id","old-world")),"history",List.of(Map.of("id",100,"conversation_id","old","sender","user","content","去看花"),Map.of("id",101,"conversation_id","old","sender","assistant","content","走吧")),"rp_turn",List.of(Map.of("id",50,"world_id","old-world","conversation_id","old","history_id",101,"state_json",current,"user_text","去看花","assistant_text","走吧","status","complete","images_json","[]","bookmarked",true)),"legacy_memories",List.of(Map.of("text","未分配世界的旧记忆"))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("rp_audio_sentence",json.valueToTree(List.of(Map.of("stream_id","old-audio","conversation_id","old","idx",0,"text","走吧","heard",true))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("companion_interests",json.valueToTree(List.of(Map.of("topic","摄影","enabled",false,"user_id",88))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("companion_settings",json.valueToTree(List.of(Map.of("settings_json","{\"timezone\":\"Asia/Tokyo\",\"city\":\"东京\",\"dailyEnabled\":true,\"worldNetworkEnabled\":true}"))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("character_profile",json.valueToTree(List.of(Map.of("role_id",100,"profile_json","{\"card\":{\"name\":\"旧角色\"},\"lore\":{\"origin\":\"原始背景\"}}"))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("message_context",json.valueToTree(List.of(Map.of("history_id",101,"conversation_id","old","context_json","{\"companion\":true,\"candidateId\":\"old-candidate\",\"sources\":[{\"title\":\"原始来源\"}]}"))));
  ((com.fasterxml.jackson.databind.node.ObjectNode)input).set("memory_feedback",json.valueToTree(List.of(Map.of("history_id",101,"world_id","old-world","correction","看的是梅花","hidden",true))));
  transaction.execute(s->{try{return importer.load(input);}catch(Exception e){throw new RuntimeException(e);}});
  var companion=new CompanionService(stories,json,new PublicContent(),mode);
  assertEquals(1,companion.interests(1).size());assertEquals(0,companion.interests(88).size());assertEquals("Asia/Tokyo",companion.settings(1).path("timezone").asText());assertFalse(companion.settings(1).path("dailyEnabled").asBoolean());assertFalse(companion.settings(1).path("worldNetworkEnabled").asBoolean());

  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rp_audio_sentence WHERE heard=TRUE",Integer.class));
  assertEquals(1,db.queryForObject("SELECT user_id FROM conversation WHERE id='old'",Integer.class));assertEquals("花园",sessions.state("old").location);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM history",Integer.class));assertEquals(1,sessions.album("old").size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM index_queue",Integer.class));assertTrue(Files.exists(data.resolve("legacy-memories-review.json")));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_entry",Integer.class));assertThrows(IllegalArgumentException.class,()->importer.load(input));
  db.update("INSERT INTO rp_turn(id,world_id,conversation_id,history_id,state_json,user_text,assistant_text,status,images_json,bookmarked,created_at) SELECT id+1000,world_id,conversation_id,history_id,state_json,user_text,assistant_text,status,images_json,bookmarked,created_at FROM rp_turn WHERE history_id=101");
  assertEquals(2,stories.history(1,"old",200,null,"").size());
  String branch=sessions.branch("old",101);stories.copyBranchContext("old",branch,101);assertEquals("花园",sessions.state(branch).location);
  assertEquals("看的是梅花",db.queryForObject("SELECT correction FROM memory_feedback WHERE world_id=?",String.class,sessions.worldId(branch)));var context=json.readTree(db.queryForObject("SELECT context_json FROM message_context WHERE conversation_id=?",String.class,branch));assertEquals("原始来源",context.path("sources").path(0).path("title").asText());assertFalse(context.has("candidateId"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM character_profile",Integer.class));
  long first=db.queryForObject("SELECT MIN(id) FROM history WHERE conversation_id=?",Long.class,branch);sessions.rewind(branch,first);assertEquals("",sessions.state(branch).location);assertEquals("花园",sessions.state("old").location);
 }
 @Test void historyOnlyExportBuildsTurnsForBranchingAndRecall()throws Exception{
  var data=Files.createTempDirectory("story-old-history-");var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+data.resolve("test.sqlite")));var json=new ObjectMapper();var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",data.toString()));new Migrations(db,mode).afterPropertiesSet();new StarterLibrary(db,mode);
  var sessions=new RpSessionService(db,json);var files=new Attachments(db,new FilesystemObjects(mode),json,mode);var store=new StoryStore(db,sessions,files,json);
  var input=json.valueToTree(Map.of("format","legacy-ai-chat-v1","roles",List.of(Map.of("id",10,"name","旧角色")),"conversations",List.of(Map.of("id","history-only","role_id",10)),"history",List.of(Map.of("id",10,"conversation_id","history-only","sender","user","content","我叫青禾"),Map.of("id",11,"conversation_id","history-only","sender","assistant","content","青禾，你好"))));
  new OfflineImport(store,files,json,mode,new CompanionService(store,json,new PublicContent(),mode)).load(input);
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM rp_turn",Integer.class));assertEquals("我叫青禾",db.queryForObject("SELECT user_text FROM rp_turn",String.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM index_queue",Integer.class));String branch=sessions.branch("history-only",11);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM history WHERE conversation_id=?",Integer.class,branch));
 }
 @Test void migrationChecksumCannotBeSilentlyReplaced()throws Exception{var data=Files.createTempDirectory("story-checksum-");var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+data.resolve("test.sqlite")));var mode=new RuntimeMode(new MockEnvironment().withProperty("story.mode","offline").withProperty("story.data-dir",data.toString()));var m=new Migrations(db,mode);m.afterPropertiesSet();m.afterPropertiesSet();db.update("UPDATE schema_version SET checksum='tampered'");assertThrows(IllegalStateException.class,m::afterPropertiesSet);}
 @Test void promptBudgetKeepsLatestInputAndBoundsContext(){String message="新的选择";var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<100;i++)rows.add(Map.of("sender",i%2==0?"user":"assistant","content","过往".repeat(600)));var prompt=PromptBudget.assemble("背景".repeat(5000),rows,message,message);assertEquals(message,prompt.get(prompt.size()-1).get("content"));assertTrue(prompt.stream().mapToInt(r->PromptBudget.tokens(r.get("content").toString())+20).sum()<6200);assertThrows(IllegalArgumentException.class,()->PromptBudget.assemble("背景",rows,"长".repeat(4000),""));}
}
