import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { test, after } from 'node:test';

// Actual PostgreSQL/PLpgSQL, including pgcrypto and RLS. Only Supabase's auth
// session adapter and Storage catalog are stubbed; no hosted user data is used.
const db = new PGlite({ extensions: { pgcrypto } });
await db.exec(`
  create role anon nologin;
  create role authenticated nologin;
  create schema auth;
  create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}');
  create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
  grant usage on schema auth to anon,authenticated;
  grant execute on function auth.uid() to anon,authenticated;
  create schema storage;
  create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
  create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text references storage.buckets(id),name text not null,metadata jsonb default '{}',unique(bucket_id,name));
  alter table storage.objects enable row level security;
  grant usage on schema public,storage to anon,authenticated;
  grant select,insert,update,delete on storage.objects to authenticated;
`);
const migration = await readFile(new URL('../migrations/202611240001_shared_groups.sql', import.meta.url), 'utf8');
await db.exec(migration);
const userA = randomUUID(), userB = randomUUID(), outsider = randomUUID(), userD = randomUUID();
const groupId = randomUUID(), selfId = randomUUID(), alexId = randomUUID(), billId = randomUUID();
for (const [id, name] of [[userA,'Owner'],[userB,'Alex Google'],[outsider,'Outsider'],[userD,'Riya Google']]) {
  await db.query('insert into auth.users(id,email,raw_user_meta_data) values($1,$2,$3::jsonb)', [id,name.replaceAll(' ','')+'@example.test',JSON.stringify({full_name:name})]);
}
after(async () => { await db.close(); });

async function asUser(id, role = 'authenticated') {
  await db.exec('reset role');
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [id || '']);
  await db.exec(`set role ${role}`);
}
const types = {
  kitna_status: [], kitna_list_groups: [], kitna_sync:['jsonb'],
  kitna_share_group: ['uuid','jsonb','jsonb','jsonb'],
  kitna_apply: ['uuid','uuid','text','uuid','bigint','jsonb'],
  kitna_invite: ['uuid','uuid'], kitna_accept_invite: ['text'],
  kitna_revoke_invites: ['uuid'],kitna_remove_member: ['uuid','uuid'],kitna_leave_group: ['uuid']
};
async function rpc(name, values = []) {
  const signature = types[name]; assert.ok(signature, 'Known RPC');
  const params = values.map((value, i) => signature[i] === 'jsonb' ? JSON.stringify(value) : value);
  return (await db.query(`select public.${name}(${params.map((_, i) => '$'+(i+1)+'::'+signature[i]).join(',')}) as data`, params)).rows[0].data;
}
async function rejected(action, code) {
  await assert.rejects(action, error => { if (code) assert.equal(error.code,code,error.message); return true; });
}
const settings = {id:groupId,name:'Goa trip',kind:'Trip',budget:200000,icon:'plane',color:'orange',simplifyDebts:false,archived:false};
const people = [{id:selfId,name:'Owner',isSelf:true},{id:alexId,name:'Alex',isSelf:false}];
const bill = {id:billId,groupId,description:'Dinner',amount:100000,payers:{[selfId]:100000},shares:{[selfId]:50000,[alexId]:50000},category:'Food',date:'2026-11-24',deleted:false,notes:'Original',splitMode:'Equal',splitWeights:{},receipts:[]};
const records = [{kind:'expense',id:billId,payload:bill}];
const shareId = randomUUID();
let originalSnapshot, invite;

test('health is anonymous but group RPCs and private data are not', async () => {
  await asUser(null,'anon');
  assert.deepEqual(await rpc('kitna_status'),{ready:true,schemaVersion:1,receiptBucket:'kitna-receipts'});
  await rejected(() => rpc('kitna_list_groups'), '42501');
  await rejected(() => db.query('select * from public.kitna_groups'),'42501');
  await rejected(() => db.query('select * from kitna_private.invites'),'42501');
});

test('owner shares an atomic group with explicit self identity and retries safely', async () => {
  await asUser(userA);
  originalSnapshot = await rpc('kitna_share_group',[shareId,settings,people,records]);
  assert.equal(originalSnapshot.myParticipantId,selfId);
  assert.equal(originalSnapshot.group.ownerId,userA);
  assert.deepEqual(new Set(originalSnapshot.group.members),new Set([selfId,alexId]));
  assert.equal(originalSnapshot.records[0].payload.date,'2026-11-24');
  assert.equal(originalSnapshot.records[0].version,1);
  assert.equal(originalSnapshot.participants.find(p => p.id===alexId).userId,null);
  const retried = await rpc('kitna_share_group',[shareId,settings,people,records]);
  assert.equal(retried.activity.length,1);
  assert.equal(retried.records.length,1);
  await rejected(() => rpc('kitna_share_group',[shareId,{...settings,name:'Different'},people,records]),'22023');
});

test('outsider cannot read, write, invite, or claim membership directly', async () => {
  await asUser(outsider);
  assert.deepEqual(await rpc('kitna_list_groups'),[]);
  assert.equal((await db.query('select * from public.kitna_groups')).rows.length,0);
  assert.equal((await db.query('select * from public.kitna_participants')).rows.length,0);
  await rejected(() => rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,1,{...bill,notes:'Attack'}]),'42501');
  await rejected(() => rpc('kitna_invite',[groupId,null]),'42501');
  await rejected(() => db.query('insert into public.kitna_participants(id,group_id,name,user_id) values($1,$2,$3,$4)',[randomUUID(),groupId,'Attack',outsider]),'42501');
});

test('invitation claims an existing ledger participant, is retryable, and is single-use', async () => {
  await asUser(userA); invite = await rpc('kitna_invite',[groupId,alexId]);
  assert.match(invite.token,/^[a-f0-9]{64}$/);
  await asUser(userB);
  const joined = await rpc('kitna_accept_invite',[invite.token]);
  assert.equal(joined.myParticipantId,alexId);
  assert.equal(joined.participants.find(p => p.id===alexId).userId,userB);
  assert.equal((await rpc('kitna_accept_invite',[invite.token])).participants.length,2);
  assert.equal((await rpc('kitna_list_groups')).length,1);
  await asUser(outsider);
  await rejected(() => rpc('kitna_accept_invite',[invite.token]),'22023');
});

test('member adds another expense and an idempotent retry does not duplicate it', async () => {
  await asUser(userB);
  const id=randomUUID(), command=randomUUID();
  const payload={...bill,id,description:'Taxi',amount:45000,payers:{[alexId]:45000},shares:{[selfId]:22500,[alexId]:22500}};
  const first=await rpc('kitna_apply',[command,groupId,'expense',id,0,payload]);
  const next=await rpc('kitna_apply',[command,groupId,'expense',id,0,payload]);
  assert.equal(next.records.filter(r=>r.id===id).length,1);
  assert.equal(first.activity.length,next.activity.length);
  assert.equal(next.records.find(r=>r.id===id).actorId,userB);
});

test('stale edits are rejected and accepted edits keep an immutable expense revision', async () => {
  await asUser(userB);
  const updated=await rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,1,{...bill,notes:'Corrected'}]);
  assert.equal(updated.records.find(r=>r.id===billId).version,2);
  assert.equal(updated.revisions.length,1);
  assert.equal(updated.revisions[0].expense.notes,'Original');
  assert.equal(updated.revisions[0].actorId,userB);
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,1,{...bill,notes:'Stale phone'}]),'40001');
  const snapshot=(await rpc('kitna_list_groups'))[0];
  assert.equal(snapshot.records.find(r=>r.id===billId).payload.notes,'Corrected');
  assert.equal(snapshot.revisions.length,1);
});

test('server rejects wrong sums, foreign participants, noninteger paise and invalid dates atomically', async () => {
  await asUser(userB);
  const before=(await rpc('kitna_list_groups'))[0];
  for (const bad of [
    {...bill,shares:{[selfId]:1}},
    {...bill,payers:{[outsider]:100000}},
    {...bill,amount:100000.5},
    {...bill,date:'2026-02-30'},
    {...bill,budget:-1,amount:100000000001},
  ]) await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,2,bad]));
  const after=(await rpc('kitna_list_groups'))[0];
  assert.equal(after.revision,before.revision);
  assert.equal(after.activity.length,before.activity.length);
  assert.equal(after.revisions.length,before.revisions.length);
});

test('comment author and time come from the authenticated actor, not a supplied impersonation', async () => {
  await asUser(userB);
  const id=randomUUID();
  const snapshot=await rpc('kitna_apply',[randomUUID(),groupId,'comment',id,0,{id,expenseId:billId,text:'Thanks!',authorId:selfId,time:0}]);
  const comment=snapshot.records.find(r=>r.id===id);
  assert.equal(comment.payload.authorId,alexId);
  assert.ok(comment.payload.time>0);
  assert.equal(comment.actorId,userB);
  await asUser(userD);
  assert.deepEqual(await rpc('kitna_list_groups'),[]);
});

test('non-owner cannot change settings and null versions cannot bypass compare-and-set', async () => {
  await asUser(userB);
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'group',groupId,1,{...settings,budget:300000}]),'42501');
  await asUser(userA);
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'group',groupId,null,settings]),'40001');
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'group',groupId,1,{...settings,members:[selfId]}]),'22023');
  const updated=await rpc('kitna_apply',[randomUUID(),groupId,'group',groupId,1,{...settings,budget:300000}]);
  assert.equal(updated.group.budget,300000);
  assert.equal(updated.group.version,2);
});

test('receipt metadata is bounded and immutable, storage reads/inserts are member scoped', async () => {
  await asUser(userA);
  const photo={id:'receipt-safe_1',name:'Dinner receipt',mimeType:'image/jpeg',sizeBytes:500,sha256:'ab'.repeat(32)};
  const current=(await rpc('kitna_list_groups'))[0].records.find(r=>r.id===billId);
  await rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,current.version,{...current.payload,receipts:[photo]}]);
  const latest=(await rpc('kitna_list_groups'))[0].records.find(r=>r.id===billId);
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'expense',billId,latest.version,{...latest.payload,receipts:[{...photo,sha256:'cd'.repeat(32)}]}]),'22023');
  await db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['kitna-receipts',groupId+'/'+photo.id+'.jpg']);
  assert.equal((await db.query('select * from storage.objects')).rows.length,1);
  const deniedUpdate=await db.query('update storage.objects set metadata=$1::jsonb where bucket_id=$2 returning id',[JSON.stringify({changed:true}),'kitna-receipts']);
  assert.equal(deniedUpdate.rows.length,0);
  assert.deepEqual((await db.query('select metadata from storage.objects')).rows[0].metadata,{});
  await asUser(outsider);
  assert.equal((await db.query('select * from storage.objects')).rows.length,0);
  await rejected(()=>db.query('insert into storage.objects(bucket_id,name) values($1,$2)',['kitna-receipts',groupId+'/attack.jpg']),'42501');
});

test('an invalid group import rolls back participants, history and its group row', async () => {
  await asUser(userA);
  const id=randomUUID(), badId=randomUUID();
  await rejected(()=>rpc('kitna_share_group',[randomUUID(),{...settings,id},people.map(p=>({...p,id:randomUUID()})),[{kind:'expense',id:badId,payload:{...bill,id:badId,groupId:id}}]]),'22023');
  assert.equal((await db.query('select * from public.kitna_groups where id=$1',[id])).rows.length,0);
});

test('removal denies subsequent reads, writes and photo access; a targeted re-invite restores the same identity', async () => {
  await asUser(userA);
  await rpc('kitna_remove_member',[groupId,alexId]);
  await asUser(userB);
  assert.deepEqual(await rpc('kitna_list_groups'),[]);
  assert.equal((await db.query('select * from storage.objects')).rows.length,0);
  await rejected(()=>rpc('kitna_apply',[randomUUID(),groupId,'expense',randomUUID(),0,bill]),'42501');
  await asUser(userA);
  const again=await rpc('kitna_invite',[groupId,alexId]);
  await asUser(outsider); await rejected(()=>rpc('kitna_accept_invite',[again.token]),'22023');
  await asUser(userB);
  assert.equal((await rpc('kitna_accept_invite',[again.token])).myParticipantId,alexId);
});

test('a new-slot invite uses the authenticated Google profile name and owner can revoke unused tokens', async () => {
  await asUser(userA);
  const newInvite=await rpc('kitna_invite',[groupId,null]);
  await asUser(userD);
  const joined=await rpc('kitna_accept_invite',[newInvite.token]);
  assert.equal(joined.participants.find(p=>p.userId===userD).name,'Riya Google');
  await asUser(userA);
  const revoked=await rpc('kitna_invite',[groupId,null]);
  await rpc('kitna_revoke_invites',[groupId]);
  await asUser(outsider); await rejected(()=>rpc('kitna_accept_invite',[revoked.token]),'22023');
});

test('leaving revokes future access while retaining historical participant and financial rows', async () => {
  await asUser(userB);
  assert.equal((await rpc('kitna_leave_group',[groupId])).left,true);
  assert.deepEqual(await rpc('kitna_list_groups'),[]);
  await asUser(userA);
  const snapshot=(await rpc('kitna_list_groups'))[0];
  assert.equal(snapshot.participants.find(p=>p.id===alexId).active,false);
  assert.equal(snapshot.records.find(r=>r.id===billId).payload.shares[alexId],50000);
  await rejected(()=>rpc('kitna_leave_group',[groupId]),'22023');
});

test('migration can be re-applied without resetting records or widening privileges', async () => {
  await asUser(userA);
  const before=(await rpc('kitna_list_groups'))[0];
  await db.exec('reset role');
  await db.exec(migration);
  await asUser(userA);
  const after=(await rpc('kitna_list_groups'))[0];
  assert.deepEqual(after,before);
  await asUser(null,'anon');
  await rejected(()=>rpc('kitna_list_groups'),'42501');
});

test('conditional sync omits unchanged ledgers and announces membership removal', async () => {
  await asUser(userA);
  const initial=await rpc('kitna_sync',[{}]);
  assert.equal(initial.groups.length,1);
  assert.deepEqual(initial.memberGroupIds,[groupId]);
  const known={[groupId]:initial.groups[0].revision};
  const noChange=await rpc('kitna_sync',[known]);
  assert.deepEqual(noChange.groups,[]);
  await rpc('kitna_apply',[randomUUID(),groupId,'group',groupId,2,{...settings,budget:400000}]);
  assert.equal((await rpc('kitna_sync',[known])).groups[0].group.budget,400000);
  await asUser(userB);
  const removed=await rpc('kitna_sync',[known]);
  assert.deepEqual(removed,{groups:[],memberGroupIds:[]});
});
