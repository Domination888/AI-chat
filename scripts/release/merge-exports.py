#!/usr/bin/env python3
"""Merge portable exports without reusing numeric identifiers or overwriting source files."""
import argparse, copy, hashlib, json
from pathlib import Path

TABLES = ['roles','conversations','history','rp_world','rp_session','rp_turn','rp_attachment','rp_audio_sentence','attachments','message_context','character_profile','memory_feedback','companion_skill','proactive_candidate']
def merge(exports):
    result={'format':'story-space-v1', **{table:[] for table in TABLES}}
    used={key:set() for key in ['conversations','rp_world','attachments','proactive_candidate']}
    for source_index, original in enumerate(exports):
        data=copy.deepcopy(original)
        maps={}
        for table in ['roles','history','rp_turn']:
            maps[table]={int(row['id']):len(result[table])+i+1 for i,row in enumerate(data.get(table,[]))}
        for table in used:
            maps[table]={}
            for row in data.get(table,[]):
                old=str(row['id']);value=old
                if value in used[table] or (table=='conversations' and len(value)>64):value='import_'+hashlib.sha256(f'{source_index}:{table}:{old}'.encode()).hexdigest()[:40]
                if value in used[table]:raise ValueError('Duplicate source identifier')
                used[table].add(value);maps[table][old]=value
        for table in TABLES:
            for row in data.get(table,[]):
                if table in maps and 'id' in row:row['id']=maps[table][int(row['id']) if table in ['roles','history','rp_turn'] else str(row['id'])]
                for column,target in [('role_id','roles'),('conversation_id','conversations'),('world_id','rp_world'),('history_id','history')]:
                    if column in row and row[column] is not None:row[column]=maps[target][int(row[column]) if target in ['roles','history'] else str(row[column])]
                if 'images_json' in row:
                    refs=json.loads(row['images_json'] or '[]');row['images_json']=json.dumps(['attachment:'+maps['attachments'][r[11:]] if r.startswith('attachment:') else r for r in refs],ensure_ascii=False)
                if table=='message_context' and row.get('context_json'):
                    context=json.loads(row['context_json'])
                    if context.get('candidateId') in maps['proactive_candidate']:context['candidateId']=maps['proactive_candidate'][context['candidateId']]
                    row['context_json']=json.dumps(context,ensure_ascii=False)
                if table=='companion_skill' and any(s['name']==row['name'] for s in result[table]):
                    row['name']=f'import{source_index}_'+row['name']
                    manifest=json.loads(row['manifest_json']);manifest['name']=row['name'];row['manifest_json']=json.dumps(manifest,ensure_ascii=False)
                if 'stream_id' in row:row['stream_id']=f'import{source_index}_'+str(row['stream_id'])
                result[table].append(row)
        # Keep explicit interests independently from story identifiers. The first source
        # wins on a duplicate topic so a later legacy export cannot re-enable muted data.
        interests=result.setdefault('companion_interests',[])
        topics={row['topic'] for row in interests}
        for row in data.get('companion_interests',data.get('proactive_interest',[])):
            if row.get('topic') and row['topic'] not in topics:
                interests.append(row);topics.add(row['topic'])
        if data.get('companion_settings') and not result.get('companion_settings'):
            result['companion_settings']=data['companion_settings']
        if data.get('legacy_memories'):result.setdefault('legacy_memories',[]).extend(data['legacy_memories'] if isinstance(data['legacy_memories'],list) else [data['legacy_memories']])
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('sources',type=Path,nargs='+');args=p.parse_args()
    if args.output.exists():raise SystemExit('Output exists; choose a new private file')
    data=merge([json.loads(source.read_text()) for source in args.sources]);args.output.write_text(json.dumps(data,ensure_ascii=False,indent=2));args.output.chmod(0o600)
    print({table:len(data[table]) for table in TABLES})
