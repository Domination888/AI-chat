package org.example.aichat.release;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.example.aichat.rp.RpState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class OfflineImportIntegrationTest {
  static final Path DATA;
  static {try{DATA=Files.createTempDirectory("story-import-identity-");}catch(Exception e){throw new RuntimeException(e);}}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){
    r.add("story.mode",()->"offline");r.add("story.data-dir",()->DATA.toString());
    r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.sqlite")+"?busy_timeout=10000&journal_mode=WAL");
    r.add("story.local-token",()->"import-test-only-local-token-1234567890123456");
  }
  @Autowired StoryStore stories;@Autowired ObjectMapper json;@Autowired CharacterProfiles characters;@Autowired OfflineImport importer;
  @MockitoBean OpenAiTransport models;
  int seed()throws Exception{
    var pack=json.readTree("{\"card\":{\"persona\":{\"identity\":\"温和的农业研究者\",\"speech\":\"自然简短\"}},\"greeting\":\"回来啦，我们慢慢说。\"}");
    return json.valueToTree(characters.importShu(1,pack)).path("roleId").asInt();
  }
  ObjectNode backup(int role)throws Exception{
    var data=json.createObjectNode();data.put("format","story-space-v1");
    var imported=json.valueToTree(stories.db().queryForMap("SELECT * FROM role_card WHERE id=?",role));((ObjectNode)imported).put("id",700).put("owner_id",77);
    data.putArray("roles").add(imported);
    String profile=stories.db().queryForObject("SELECT profile_json FROM character_profile WHERE role_id=?",String.class,role);
    data.putArray("character_profile").addObject().put("role_id",700).put("profile_json",profile);
    data.putArray("conversations").addObject().put("id","restored-story").put("role_id",700).put("title","恢复的故事");
    var state=new RpState();state.location="恢复的茶桌";String encoded=stories.sessions().encode(state);
    data.putArray("rp_world").addObject().put("id","restored-world").put("role_id",700).put("state_json",encoded).put("initial_json",encoded);
    data.putArray("rp_session").addObject().put("conversation_id","restored-story").put("world_id","restored-world");
    data.putArray("history").addObject().put("id",901).put("conversation_id","restored-story").put("sender","assistant").put("content","备份中的台词");
    data.putArray("rp_turn").addObject().put("id",801).put("conversation_id","restored-story").put("world_id","restored-world").put("history_id",901).put("state_json",encoded).put("user_text","").put("assistant_text","备份中的台词").put("status","complete");
    return data;
  }
  int restoredRole(){return stories.db().queryForObject("SELECT role_id FROM conversation WHERE id='restored-story'",Integer.class);}
  @Test void identicalPrivateCharacterPackIsReusedAndAllStoryAssociationsSurvive()throws Exception{
    int local=seed();var data=backup(local);
    // JSON property order is not a character change; exported owner ids are remapped locally.
    var profile=json.readTree(data.path("character_profile").get(0).path("profile_json").asText());
    var reordered=json.createObjectNode();reordered.set("greeting",profile.path("greeting"));reordered.set("card",profile.path("card"));
    ((ObjectNode)data.path("character_profile").get(0)).put("profile_json",reordered.toString());
    importer.load(data);
    assertEquals(local,restoredRole());assertEquals(1,stories.db().queryForObject("SELECT COUNT(*) FROM role_card WHERE owner_id=1 AND name='黍'",Integer.class));
    assertEquals(local,stories.db().queryForObject("SELECT role_id FROM rp_world WHERE id='restored-world'",Integer.class));
    assertEquals("restored-world",stories.sessions().worldId("restored-story"));assertEquals("恢复的茶桌",stories.sessions().state("restored-story").location);
    assertEquals("备份中的台词",stories.db().queryForObject("SELECT content FROM history WHERE id=901",String.class));
    assertEquals(1,stories.db().queryForObject("SELECT COUNT(*) FROM rp_turn WHERE history_id=901 AND world_id='restored-world'",Integer.class));
    assertEquals(profile,json.readTree(stories.db().queryForObject("SELECT profile_json FROM character_profile WHERE role_id=?",String.class,local)));
  }
  @Test void sameVisibleCardWithDifferentFullPersonaRemainsSeparateAndDoesNotOverwriteLocalPack()throws Exception{
    int local=seed();var data=backup(local);String original=stories.db().queryForObject("SELECT profile_json FROM character_profile WHERE role_id=?",String.class,local);
    var edited=(ObjectNode)json.readTree(original);edited.put("extraLore","备份独有的新背景");((ObjectNode)data.path("character_profile").get(0)).put("profile_json",edited.toString());
    importer.load(data);assertNotEquals(local,restoredRole());
    assertEquals(original,stories.db().queryForObject("SELECT profile_json FROM character_profile WHERE role_id=?",String.class,local));
    assertEquals(edited,json.readTree(stories.db().queryForObject("SELECT profile_json FROM character_profile WHERE role_id=?",String.class,restoredRole())));
  }
  @ParameterizedTest @ValueSource(strings={"voice_id","example_dialogue","greeting","role_code","avatar"})
  void modifiedCharacterFieldsAreNotMerged(String field)throws Exception{
    int local=seed();var data=backup(local);String changed=field.equals("avatar")?"/release-art/imported.svg":"备份修改值";((ObjectNode)data.path("roles").get(0)).put(field,changed);
    importer.load(data);assertNotEquals(local,restoredRole());
    assertEquals(changed,stories.db().queryForObject("SELECT "+field+" FROM role_card WHERE id=?",String.class,restoredRole()));
    assertNotEquals(changed,stories.db().queryForObject("SELECT "+field+" FROM role_card WHERE id=?",String.class,local));
  }
  @Test void identicalRoleOwnedByAnotherUserIsNeverReused()throws Exception{
    int other=seed();var data=backup(other);stories.db().update("UPDATE role_card SET owner_id=2 WHERE id=?",other);
    importer.load(data);assertNotEquals(other,restoredRole());assertEquals(1,stories.db().queryForObject("SELECT owner_id FROM role_card WHERE id=?",Integer.class,restoredRole()));
  }
  @Test void backupRestoreStillIncludesInterestsAfterMigrationRoutesAreRemoved()throws Exception{
    var backup=json.createObjectNode().put("format","story-space-v1");backup.putArray("companion_interests").addObject().put("topic","备份中的书签兴趣").put("sentiment","dislike").put("enabled",false);
    importer.load(backup);var interest=stories.db().queryForMap("SELECT topic,sentiment,enabled FROM companion_interest WHERE user_id=1 AND topic='备份中的书签兴趣'");assertEquals("dislike",interest.get("sentiment"));assertEquals(0,((Number)interest.get("enabled")).intValue());
  }
  @Test void sharedPhotoIsRestoredOnceAndLinkedAcrossMessagesTurnsAndBranches()throws Exception{
    int local=seed();var data=backup(local);String refs="[\"attachment:source-photo\"]";
    data.withArray("history").insertObject(0).put("id",900).put("conversation_id","restored-story").put("sender","user").put("content","这张照片留在相册里。");
    data.putArray("attachments").addObject().put("id","source-photo").put("dataUrl","data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2dS8AAAAASUVORK5CYII=");
    data.putArray("rp_attachment").addObject().put("history_id",900).put("conversation_id","restored-story").put("images_json",refs);
    ((ObjectNode)data.path("rp_turn").get(0)).put("images_json",refs);
    data.withArray("conversations").addObject().put("id","restored-branch").put("role_id",700).put("title","照片分支");
    var world=(ObjectNode)data.path("rp_world").get(0).deepCopy();world.put("id","branch-world");data.withArray("rp_world").add(world);
    data.withArray("rp_session").addObject().put("conversation_id","restored-branch").put("world_id","branch-world");
    data.withArray("history").addObject().put("id",902).put("conversation_id","restored-branch").put("sender","assistant").put("content","分支里仍然留着照片。");
    var turn=(ObjectNode)data.path("rp_turn").get(0).deepCopy();turn.put("id",802).put("conversation_id","restored-branch").put("world_id","branch-world").put("history_id",902);data.withArray("rp_turn").add(turn);
    importer.load(data);
    assertEquals(1,stories.db().queryForObject("SELECT COUNT(*) FROM attachment WHERE user_id=1",Integer.class));
    assertEquals(2,stories.db().queryForObject("SELECT COUNT(*) FROM attachment_link",Integer.class));
    String stored=stories.db().queryForObject("SELECT images_json FROM rp_attachment WHERE history_id=900",String.class);
    assertEquals(stored,stories.db().queryForObject("SELECT images_json FROM rp_turn WHERE history_id=901",String.class));
    assertEquals(stored,stories.db().queryForObject("SELECT images_json FROM rp_turn WHERE history_id=902",String.class));
    assertNotEquals(refs,stored);
  }

  @Test void importedBeforeSnapshotSurvivesRestoreAndRewind()throws Exception{
    int role=seed();var data=backup(role);var before=new RpState();before.location="imported manual checkpoint";before.commitments=List.of("kept promise");
    ((ObjectNode)data.path("rp_turn").get(0)).put("before_state_json",stories.sessions().encode(before));
    importer.load(data);stories.sessions().rewind("restored-story",901);
    assertEquals("imported manual checkpoint",stories.sessions().state("restored-story").location);assertEquals(List.of("kept promise"),stories.sessions().state("restored-story").commitments);
  }
}
