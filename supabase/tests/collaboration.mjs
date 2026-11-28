import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { test, after } from 'node:test';

// Actual PostgreSQL and pgcrypto. Auth's identity catalog and Storage are small
// local stand-ins; this does not exercise hosted HTTP or real Google sign-in.
const db = new PGlite({ extensions: { pgcrypto } });
await db.exec(`
create role anon nologin; create role authenticated nologin;
create schema auth;
create table auth.users(id uuid primary key,email text,email_confirmed_at timestamptz,raw_user_meta_data jsonb default '{}');
create table auth.identities(id uuid primary key default gen_random_uuid(),user_id uuid references auth.users(id),provider text,identity_data jsonb);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to anon,authenticated; grant execute on function auth.uid() to anon,authenticated;
create schema storage;
create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text references storage.buckets(id),name text not null,metadata jsonb default '{}',unique(bucket_id,name));
alter table storage.objects enable row level security; grant usage on schema public,storage to anon,authenticated; grant select,insert,update,delete on storage.objects to authenticated;
`);
const migrations = await Promise.all(['202611240001_shared_groups.sql','202611240002_invitation_codes.sql','202611250001_simplified_collaboration.sql'].map(name => readFile(new URL('../migrations/'+name,import.meta.url),'utf8')));
await db.exec(migrations[0]); await db.exec(migrations[1]);
const owner=randomUUID(), friend=randomUUID(), stranger=randomUUID(), unverified=randomUUID();
for(const [id,email,verified] of [[owner,'owner@example.com',true],[friend,'friend@example.com',true],[stranger,'stranger@example.com',true],[unverified,'unverified@example.com',false]]) {
  await db.query('insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values($1,$2,$3,$4)',[id,email,verified?'2026-11-25T00:00:00Z':null,{full_name:email.split('@')[0]}]);
  await db.query('insert into auth.identities(user_id,provider,identity_data) values($1,$2,$3)',[id,'google',{email,email_verified:verified}]);
}
async function as(id,role='authenticated') { await db.exec('reset role'); await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id??'']); await db.exec('set role '+role); }
const types={kitna_status:[],kitna_share_group:['uuid','jsonb','jsonb','jsonb'],kitna_preview_invite:['text'],kitna_accept_invite:['text'],kitna_group_code:['uuid'],kitna_invite:['uuid','uuid'],kitna_invite_email:['uuid','uuid','text','text'],kitna_add_members:['uuid','jsonb'],kitna_revoke_invites:['uuid'],kitna_remove_member:['uuid','uuid'],kitna_sync:['jsonb'],kitna_apply:['uuid','uuid','text','uuid','bigint','jsonb'],kitna_friend_invite:['text','text'],kitna_preview_friend_invite:['text'],kitna_accept_friend_invite:['text'],kitna_accept_friend_email:['uuid'],kitna_revoke_friend_invite:['uuid'],kitna_friends:[]};
types.kitna_group_invitations=[]; types.kitna_accept_group_email=['uuid'];
async function rpc(name,args=[]) { const sig=types[name]; return (await db.query(`select public.${name}(${args.map((_,i)=>'$'+(i+1)+'::'+sig[i]).join(',')}) as data`,args.map((v,i)=>sig[i]==='jsonb'?JSON.stringify(v):v))).rows[0].data; }
async function denied(action,code='42501') { await assert.rejects(action,e=>e.code===code); }
async function admin(sql,args=[]) { await db.exec('reset role'); return db.query(sql,args); }
async function share({kind='Trip',others=[],records=[]}={}) { await as(owner); const id=randomUUID(),self=randomUUID(); const participants=[{id:self,name:'Owner',isSelf:true},...others]; const snapshot=await rpc('kitna_share_group',[randomUUID(),{id,name:'Vrindavan',kind,icon:'plane',color:'orange',simplifyDebts:true},participants,records]); return {id,self,snapshot}; }
after(()=>db.close());
let legacyTrip,legacyToken,trip,code,groupToken,friendInvite;

test('upgrade retains pre-v3 ledger rows and disallows unverified legacy participant claims',async()=>{
  legacyTrip=await share({others:[{id:randomUUID(),name:'Existing local friend'}]});
  const otherId=legacyTrip.snapshot.participants.find(p=>!p.userId).id;
  const bill={id:randomUUID(),groupId:legacyTrip.id,description:'Historical dinner',amount:10001,payers:{[legacyTrip.self]:10001},shares:{[legacyTrip.self]:5001,[otherId]:5000},category:'Food',date:'2026-11-24',splitMode:'Equal',splitWeights:{},receipts:[],notes:'Original'};
  await rpc('kitna_apply',[randomUUID(),legacyTrip.id,'expense',bill.id,0,bill]);
  await rpc('kitna_apply',[randomUUID(),legacyTrip.id,'expense',bill.id,1,{...bill,notes:'Corrected'}]);
  legacyToken=(await rpc('kitna_invite',[legacyTrip.id,otherId])).token;
  const before=(await admin('select count(*) n from public.kitna_participants')).rows[0].n;
  await db.exec(migrations[2]);
  assert.equal((await admin('select count(*) n from public.kitna_participants')).rows[0].n,before);
  await as(owner);const restored=(await rpc('kitna_sync',[{}])).groups.find(g=>g.group.id===legacyTrip.id);
  assert.equal(restored.records[0].payload.amount,10001);assert.deepEqual(restored.records[0].payload.shares,bill.shares);assert.equal(restored.records[0].version,2);assert.equal(restored.revisions[0].expense.notes,'Original');
  await denied(()=>rpc('kitna_apply',[randomUUID(),legacyTrip.id,'expense',bill.id,1,{...bill,notes:'Stale offline draft'}]),'40001');
  await as(stranger);await denied(()=>rpc('kitna_accept_invite',[legacyToken]));
  assert.equal((await rpc('kitna_preview_invite',[legacyToken])).error,'verified_claim_required');
});

test('protocol v3 exposes no anonymous invitations, friendship tables or helper functions',async()=>{
  await as(null,'anon');assert.equal((await rpc('kitna_status')).schemaVersion,3);
  for(const [name,args] of [['kitna_preview_invite',['AAAA-AAAA-AAAA']],['kitna_friends',[]],['kitna_friend_invite',['A',null]],['kitna_add_members',[randomUUID(),[]]]]) await denied(()=>rpc(name,args));
  await as(owner);await denied(()=>db.query('select * from kitna_private.friend_invites'));await denied(()=>db.query('select kitna_private.friend_result($1::uuid,$2::uuid)',[randomUUID(),stranger]));
});

test('one-member groups and all supported types import without a second mandatory member',async()=>{
  for(const kind of ['Trip','Friends','Other','Non-group']) { const g=await share({kind}); assert.equal(g.snapshot.participants.length,1);assert.equal(g.snapshot.group.kind,kind); }
  await as(owner);await denied(()=>rpc('kitna_share_group',[randomUUID(),{id:randomUUID(),name:'Bad',kind:'Direct'},[{id:randomUUID(),name:'Owner',isSelf:true}],[]]),'22023');
});

test('member batches preserve IDs, allow local participants, retry safely and enforce ownership',async()=>{
  trip=await share(); const members=[{id:randomUUID(),name:'Rahul'},{id:randomUUID(),name:'Riya'}];
  const added=await rpc('kitna_add_members',[trip.id,members]);assert.equal(added.participants.length,3);assert.ok(added.participants.some(p=>p.id===members[0].id&&!p.userId));
  const retried=await rpc('kitna_add_members',[trip.id,members]);assert.equal(retried.participants.length,3);assert.equal(retried.group.version,added.group.version);
  await as(stranger);await denied(()=>rpc('kitna_add_members',[trip.id,[{id:randomUUID(),name:'Attack'}]]));
});

test('member IDs cannot be moved to a different group or silently reactivated',async()=>{
  await as(owner); const other=await share(); const member=trip.snapshot.myParticipantId;
  await denied(()=>rpc('kitna_add_members',[other.id,[{id:member,name:'Owner'}]]),'22023');
  const snapshot=await rpc('kitna_add_members',[trip.id,[{id:randomUUID(),name:'Removed'}]]);const p=snapshot.participants.find(p=>p.name==='Removed');await rpc('kitna_remove_member',[trip.id,p.id]);
  await denied(()=>rpc('kitna_add_members',[trip.id,[{id:p.id,name:p.name}]]),'22023');
});

test('valid group preview shows basic information with no unauthorized financial data',async()=>{
  await as(owner);code=(await rpc('kitna_group_code',[trip.id])).code;groupToken=(await rpc('kitna_invite',[trip.id,null])).token;
  await as(stranger);const preview=await rpc('kitna_preview_invite',[code]);assert.equal(preview.name,'Vrindavan');assert.equal(preview.alreadyMember,false);
  assert.deepEqual(Object.keys(preview).sort(),['alreadyMember','claim','expiresAt','groupId','kind','memberCount','name'].sort());
  assert.equal((await rpc('kitna_sync',[{}])).memberGroupIds.includes(trip.id),false);
  assert.equal((await db.query('select * from public.kitna_records where group_id=$1',[trip.id])).rows.length,0);
});

test('explicit link acceptance is idempotent and opening a group link as a member reuses membership',async()=>{
  await as(stranger);const joined=await rpc('kitna_accept_invite',[groupToken]);const again=await rpc('kitna_accept_invite',[groupToken]);assert.equal(joined.myParticipantId,again.myParticipantId);
  assert.equal((await rpc('kitna_preview_invite',[code])).alreadyMember,true);
  await as(owner);const fresh=(await rpc('kitna_invite',[trip.id,null])).token;assert.equal((await rpc('kitna_accept_invite',[fresh])).myParticipantId,trip.self);
});

test('removed members cannot rejoin via a fresh general token or code',async()=>{
  await as(owner);const snapshot=await rpc('kitna_add_members',[trip.id,[{id:randomUUID(),name:'Another local'}]]);const p=snapshot.participants.find(p=>p.userId===stranger);await rpc('kitna_remove_member',[trip.id,p.id]);const token=(await rpc('kitna_invite',[trip.id,null])).token;
  await as(stranger);await denied(()=>rpc('kitna_accept_invite',[token]),'22023');assert.equal((await rpc('kitna_accept_invite',[code])).error,'targeted_invitation_required');
});

test('targeted participant claims require verified matching email and preserve the historical ID',async()=>{
  await as(owner);const p=legacyTrip.snapshot.participants.find(p=>!p.userId);
  await rpc('kitna_invite_email',[legacyTrip.id,p.id,p.name,'friend@example.com']);const token=(await rpc('kitna_invite',[legacyTrip.id,p.id])).token;
  await as(stranger);await denied(()=>rpc('kitna_accept_invite',[token]));
  await as(friend);const joined=await rpc('kitna_accept_invite',[token]);assert.equal(joined.myParticipantId,p.id);assert.equal(joined.participants.length,2);
  assert.equal((await rpc('kitna_accept_invite',[token])).myParticipantId,p.id);
});

test('revoked or expired group invitations cannot preview or join',async()=>{
  const g=await share();const token=(await rpc('kitna_invite',[g.id,null])).token;const c=(await rpc('kitna_group_code',[g.id])).code;await rpc('kitna_revoke_invites',[g.id]);
  await as(friend);assert.equal((await rpc('kitna_preview_invite',[token])).error,'invalid_invite');assert.equal((await rpc('kitna_accept_invite',[c])).error,'invalid_invite');await denied(()=>rpc('kitna_accept_invite',[token]),'22023');
  await as(owner);const expired=(await rpc('kitna_invite',[g.id,null])).token;await admin("update kitna_private.invites set expires_at=now()-interval '1 second' where group_id=$1",[g.id]);await as(friend);assert.equal((await rpc('kitna_preview_invite',[expired])).error,'invalid_invite');
});

test('v3 synchronization never accepts email invitations and the inbox has no financial data',async()=>{
  const first=await share({others:[{id:randomUUID(),name:'Rahul',email:'friend@example.com'}]});
  const second=await share({others:[{id:randomUUID(),name:'Rahul',email:'friend@example.com'}]});
  await as(friend);const before=await rpc('kitna_sync',[{}]);assert.ok(!before.memberGroupIds.includes(first.id));assert.ok(!before.memberGroupIds.includes(second.id));
  const inbox=await rpc('kitna_group_invitations');const target=inbox.find(i=>i.groupId===first.id);assert.equal(target.name,'Vrindavan');assert.deepEqual(Object.keys(target).sort(),['expiresAt','groupId','id','name','participantId'].sort());
  const accepted=await rpc('kitna_accept_group_email',[first.id]);assert.equal(accepted.myParticipantId,first.snapshot.participants.find(p=>!p.userId).id);
  assert.equal((await rpc('kitna_accept_group_email',[first.id])).myParticipantId,accepted.myParticipantId);
  const after=await rpc('kitna_sync',[{}]);assert.ok(after.memberGroupIds.includes(first.id));assert.ok(!after.memberGroupIds.includes(second.id));
  await as(owner);const code=(await rpc('kitna_group_code',[second.id])).code;
  await as(friend);assert.equal((await rpc('kitna_accept_invite',[code])).myParticipantId,second.snapshot.participants.find(p=>!p.userId).id);
});

test('foreign, unverified, revoked and removed accounts cannot claim an email inbox invitation',async()=>{
  const g=await share({others:[{id:randomUUID(),name:'Rahul',email:'friend@example.com'}]});
  for(const id of [stranger,unverified]) { await as(id);assert.ok(!(await rpc('kitna_group_invitations')).some(i=>i.groupId===g.id));await denied(()=>rpc('kitna_accept_group_email',[g.id])); }
  await as(owner);await rpc('kitna_revoke_invites',[g.id]);await as(friend);await denied(()=>rpc('kitna_accept_group_email',[g.id]));
  await as(owner);const slot=g.snapshot.participants.find(p=>!p.userId).id;await rpc('kitna_invite_email',[g.id,slot,'Rahul','friend@example.com']);
  await as(friend);await rpc('kitna_accept_group_email',[g.id]);await as(owner);await rpc('kitna_remove_member',[g.id,slot]);
  await as(friend);await denied(()=>rpc('kitna_accept_group_email',[g.id]));assert.ok(!(await rpc('kitna_sync',[{}])).memberGroupIds.includes(g.id));
});

test('expired email invitations are absent from the inbox and cannot accept',async()=>{
  const g=await share({others:[{id:randomUUID(),name:'Rahul',email:'friend@example.com'}]});
  await admin("update kitna_private.email_invites set expires_at=now()-interval '1 second' where group_id=$1",[g.id]);
  await as(friend);assert.ok(!(await rpc('kitna_group_invitations')).some(i=>i.groupId===g.id));await denied(()=>rpc('kitna_accept_group_email',[g.id]));
});

test('inviting an exact email returns the same shape for registered and unregistered people',async()=>{
  await as(owner);friendInvite=await rpc('kitna_friend_invite',['Rahul',' FRIEND@example.com ']);const unknown=await rpc('kitna_friend_invite',['New person','unknown@example.com']);
  assert.deepEqual(Object.keys(friendInvite).sort(),Object.keys(unknown).sort());assert.equal(friendInvite.status,'pending');assert.match(friendInvite.token,/^[a-f0-9]{64}$/);
  assert.equal((await rpc('kitna_friend_invite',['Changed display','friend@example.com'])).id,friendInvite.id);
  await as(stranger);assert.deepEqual((await rpc('kitna_friends')).invitations,[]);
});

test('friend invitations have a private exact-email inbox and explicit acceptance',async()=>{
  await as(friend);const before=await rpc('kitna_friends');assert.equal(before.friends.length,0);assert.ok(before.invitations.some(i=>i.id===friendInvite.id&&i.incoming));
  const preview=await rpc('kitna_preview_friend_invite',[friendInvite.token]);assert.equal(preview.kind,'friend');assert.equal(preview.name,'owner');assert.equal(preview.email,undefined);
  const accepted=await rpc('kitna_accept_friend_email',[friendInvite.id]);assert.equal(accepted.userId,owner);assert.equal(accepted.email,'owner@example.com');assert.equal(accepted.status,'accepted');
  assert.equal((await rpc('kitna_accept_friend_invite',[friendInvite.token])).userId,owner);
  await as(owner);const sender=(await rpc('kitna_friends')).friends;assert.equal(sender.length,1);assert.equal(sender[0].id,friendInvite.id);assert.equal(sender[0].userId,friend);assert.equal(sender[0].email,'friend@example.com');
});

test('wrong and unverified accounts cannot accept private email friend invitations',async()=>{
  await as(owner);const invite=await rpc('kitna_friend_invite',['Not verified','unverified@example.com']);
  for(const id of [stranger,unverified]) { await as(id);await denied(()=>rpc('kitna_accept_friend_invite',[invite.token]));await denied(()=>rpc('kitna_accept_friend_email',[invite.id])); }
});

test('general friend link requires explicit acceptance, prevents self/second claims and deduplicates relationships',async()=>{
  await as(owner);const invite=await rpc('kitna_friend_invite',['Another Rahul',null]);await denied(()=>rpc('kitna_accept_friend_invite',[invite.token]),'22023');
  await as(friend);await rpc('kitna_accept_friend_invite',[invite.token]);assert.equal((await rpc('kitna_friends')).friends.length,1);
  await as(stranger);await denied(()=>rpc('kitna_accept_friend_invite',[invite.token]),'22023');
  await as(friend);const reverse=await rpc('kitna_friend_invite',['Owner',null]);await as(owner);await rpc('kitna_accept_friend_invite',[reverse.token]);assert.equal((await rpc('kitna_friends')).friends.length,1);
});

test('only the sender can revoke a pending friend invitation and revoked/expired tokens fail',async()=>{
  await as(owner);const revoked=await rpc('kitna_friend_invite',['Revoked',null]);await as(friend);await denied(()=>rpc('kitna_revoke_friend_invite',[revoked.id]));await as(owner);await rpc('kitna_revoke_friend_invite',[revoked.id]);
  await as(friend);assert.equal((await rpc('kitna_preview_friend_invite',[revoked.token])).error,'invalid_invite');await denied(()=>rpc('kitna_accept_friend_invite',[revoked.token]),'22023');
  await as(owner);const expired=await rpc('kitna_friend_invite',['Expired',null]);await admin("update kitna_private.friend_invites set expires_at=now()-interval '1 second' where id=$1",[expired.id]);await as(friend);assert.equal((await rpc('kitna_preview_friend_invite',[expired.token])).error,'invalid_invite');await denied(()=>rpc('kitna_accept_friend_invite',[expired.token]),'22023');
});

test('migration reapplication preserves existing financial history and friendship relationships',async()=>{
  await admin('select 1');const before=(await db.query('select count(*) n from public.kitna_participants')).rows[0].n;const friendships=(await db.query('select count(*) n from kitna_private.friendships')).rows[0].n;
  await db.exec(migrations[2]);assert.equal((await db.query('select count(*) n from public.kitna_participants')).rows[0].n,before);assert.equal((await db.query('select count(*) n from kitna_private.friendships')).rows[0].n,friendships);
  const deploymentChecks=(await db.query(await readFile(new URL('../verify_collaboration.sql',import.meta.url),'utf8'))).rows[0];
  assert.ok(Object.values(deploymentChecks).every(value=>value===true),JSON.stringify(deploymentChecks));
});
