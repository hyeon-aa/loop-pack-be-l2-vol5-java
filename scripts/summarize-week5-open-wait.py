#!/usr/bin/env python3
"""Summarize recorded OPEN-wait comparisons. Sends no HTTP requests."""
import argparse
from pathlib import Path
from collections import defaultdict
import json,math
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--measurements-root', type=Path, default=Path('docs/week5/measurements'))
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = args.measurements_root
output = args.output or root / 'open10-summary.json'
def p99(xs):
 xs=sorted(xs);return xs[math.ceil(len(xs)*.99)-1] if xs else None
def summarize(calls,begin,end):
 rows=[x for x in calls if begin<=x['plannedOffsetMs']<end];out={}
 for group in ['lookup','create','product']:
  xs=[x for x in rows if x['group']==group];out[group]={'count':len(xs),'checked':sum(x.get('checked') is True for x in xs),'accepted':sum(x.get('paymentStatus')=='PENDING' for x in xs),'p99Ms':p99([x['elapsedMs'] for x in xs]),'transportErrors':sum('transportError' in x for x in xs)}
 return out
rows=[]
for folder in ['review-open10','review-open10-after-probe']:
 for p in sorted((root/folder).glob('*-result.json')):
  r=json.loads(p.read_text())
  if 'client' not in r:
   rows.append({'file':str(p.relative_to(root)),'completed':False,'incomplete':True});continue
  c=json.loads(p.with_name(p.name.replace('-result.json','-client.json')).read_text());cfg=json.loads(p.with_name(p.name.replace('-result.json','-config.json')).read_text());post=folder.endswith('after-probe');burstStart=12000 if post else 6000
  expected=r['plannedLookups']+24*r['seconds'];actual=sum(x['count'] for x in r['client'].values());assert actual==expected
  assert r['circuitOpenWaitMs']==10000 and r['seconds']==25 and r['droppedIterations']==0
  assert all(x['transportErrors']==0 for x in r['client'].values())
  row={'file':str(p.relative_to(root)),'scene':r['scene'],'policy':r['policy'],'repeat':int(r['scenario'].rsplit('-',1)[1]),'completed':r['completed'],'dataVerified':r.get('dataConsistencyVerified',False),'apiRequests':actual,'environment':r['environment'],'full25':r['client'],'first14':summarize(c['calls'],0,14000),'burstBeginMs':burstStart if 'BURST' in r['scene'] else None,'burst4':summarize(c['calls'],burstStart,burstStart+4000) if 'BURST' in r['scene'] else None,'circuitRejected':r['circuitRejected'],'entryRejected':r['concurrencyRejected'],'circuitTransitions':r['circuitTransitions'],'runtime':r['runtime'],'recoveryAcceptance':r.get('recoveryAcceptance'),'firstCheckedAfterSixSecondsMs':min((x['startOffsetMs']+x['elapsedMs'] for x in c['calls'] if x['group']=='lookup' and x['plannedOffsetMs']>=6000 and x['checked']),default=None)}
  if not post:
   old=root/'additional-scenes'/p.name
   base=json.loads(old.read_text());oldc=json.loads(old.with_name(old.name.replace('-result.json','-client.json')).read_text());row['open1First14']=summarize(oldc['calls'],0,14000);row['open1Burst4']=summarize(oldc['calls'],6000,10000) if 'BURST' in r['scene'] else None
  rows.append(row)
valid=[x for x in rows if not x.get('incomplete')]
summary={'scope':'New OPEN 10s/25s actual k6 runs; matched planned-start windows for comparison with old OPEN 1s/14s runs','cases':len(rows),'completed':sum(x['completed'] for x in rows),'dataVerified':sum(x.get('dataVerified',False) for x in rows),'apiRequests':sum(x.get('apiRequests',0) for x in rows),'rows':rows}
output.write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print({k:v for k,v in summary.items() if k!='rows'})
groups=defaultdict(list)
for r in valid:groups[r['scene'],r['policy']].append(r)
for (scene,policy),rs in groups.items():
 def span(f):
  vs=[f(x) for x in rs];return (min(vs),max(vs))
 print(scene,policy,len(rs),'first14 accepted/checked',span(lambda r:r['first14']['create']['accepted']),span(lambda r:r['first14']['lookup']['checked']),'burst accepted/checked',span(lambda r:r['burst4']['create']['accepted']) if rs[0]['burst4'] else None,span(lambda r:r['burst4']['lookup']['checked']) if rs[0]['burst4'] else None,'full25 checked',span(lambda r:r['full25']['lookup']['checkedTrue']),'CB',span(lambda r:r['circuitRejected']))
