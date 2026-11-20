import importlib.util,json,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('merge_exports',Path(__file__).resolve().parents[1]/'release/merge-exports.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class MergeTests(unittest.TestCase):
 def test_collisions_preserve_every_reference_and_input(self):
  source={'roles':[{'id':1,'name':'角色'}],'conversations':[{'id':'same','role_id':1}],'history':[{'id':1,'conversation_id':'same','sender':'assistant','content':'原文'}],'rp_world':[{'id':'world','role_id':1}],'rp_session':[{'conversation_id':'same','world_id':'world'}],'rp_turn':[{'id':1,'world_id':'world','conversation_id':'same','history_id':1,'images_json':'["attachment:image"]'}],'attachments':[{'id':'image','dataUrl':'data:image/png;base64,AA=='}]}
  before=json.dumps(source);result=module.merge([source,source]);self.assertEqual(before,json.dumps(source));self.assertEqual(2,len(result['history']));self.assertNotEqual(result['conversations'][0]['id'],result['conversations'][1]['id'])
  for i in [0,1]:
   turn=result['rp_turn'][i];self.assertEqual(result['history'][i]['id'],turn['history_id']);self.assertEqual(result['rp_world'][i]['id'],turn['world_id']);self.assertEqual(result['conversations'][i]['id'],turn['conversation_id']);self.assertEqual(['attachment:'+result['attachments'][i]['id']],json.loads(turn['images_json']));self.assertEqual(result['roles'][i]['id'],result['conversations'][i]['role_id'])
 def test_missing_reference_fails_instead_of_silent_data_loss(self):
  with self.assertRaises(KeyError):module.merge([{'conversations':[{'id':'broken','role_id':999}]}])
 def test_interests_and_companion_settings_survive_merge(self):
  current={'companion_interests':[{'id':'new','topic':'摄影','enabled':False,'sentiment':'dislike'}],'companion_settings':[{'settings_json':'{"timezone":"Asia/Shanghai"}'}]}
  legacy={'proactive_interest':[{'id':1,'topic':'摄影','enabled':True},{'id':2,'topic':'开源模型','enabled':True}]}
  result=module.merge([current,legacy]);self.assertEqual(2,len(result['companion_interests']));self.assertFalse(result['companion_interests'][0]['enabled']);self.assertEqual('dislike',result['companion_interests'][0]['sentiment']);self.assertEqual(current['companion_settings'],result['companion_settings'])
 def test_capability_references_and_skill_names_survive_collisions(self):
  source={'roles':[{'id':1,'name':'黍'}],'conversations':[{'id':'chapter','role_id':1}],'history':[{'id':1,'conversation_id':'chapter','sender':'assistant','content':'资料'}],'rp_world':[{'id':'world','role_id':1}],'proactive_candidate':[{'id':'candidate','conversation_id':'chapter','history_id':1}],'message_context':[{'history_id':1,'conversation_id':'chapter','context_json':'{"candidateId":"candidate","isProactive":true}'}],'character_profile':[{'role_id':1,'profile_json':'{"card":{"name":"黍"}}'}],'memory_feedback':[{'history_id':1,'world_id':'world','correction':'纠正'}],'companion_skill':[{'name':'fixture','manifest_json':'{"name":"fixture","instructions":"指令"}'}]}
  result=module.merge([source,source]);self.assertEqual(2,len(result['proactive_candidate']));self.assertNotEqual(result['proactive_candidate'][0]['id'],result['proactive_candidate'][1]['id']);self.assertEqual(result['proactive_candidate'][1]['id'],json.loads(result['message_context'][1]['context_json'])['candidateId']);self.assertNotEqual(result['companion_skill'][0]['name'],result['companion_skill'][1]['name']);self.assertEqual(result['character_profile'][1]['role_id'],result['roles'][1]['id']);self.assertEqual(result['memory_feedback'][1]['history_id'],result['history'][1]['id'])
if __name__=='__main__':unittest.main()
