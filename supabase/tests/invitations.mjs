import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { test, after } from 'node:test';

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
const v1 = await readFile(new URL('../migrations/202611240001_shared_groups.sql', import.meta.url), 'utf8');
const v2 = await readFile(new URL('../migrations/202611240002_invitation_codes.sql', import.meta.url), 'utf8');
await db.exec(v1); await db.exec(v2);
after(() => db.close());
const owner = randomUUID(), friend = randomUUID(), stranger = randomUUID(), unverified = randomUUID(), impostor = randomUUID();
for (const [id,email,verified,google] of [[owner,'owner@example.com',true,true],[friend,'friend@example.com',true,true],[stranger,'stranger@example.com',true,true],[unverified,'unverified@example.com',false,true],[impostor,'impostor@example.com',true,false]]) {
  await db.query('insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values($1,$2,$3,$4)', [id,email,verified?'2026-11-24T00:00:00Z':null,{name:email.split('@')[0],email_verified:true}]);
  if (google) await db.query('insert into auth.identities(user_id,provider,identity_data) values($1,$2,$3)',[id,'google',{email,email_verified:verified}]);
}
async function as(id,role='authenticated') { await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id??'']); await db.exec(`set role ${role}`); }
const types={kitna_status:[],kitna_group_code:['uuid'],kitna_sync:['jsonb'],kitna_accept_invite:['text'],kitna_invite_email:['uuid','uuid','text','text'],kitna_share_group:['uuid','jsonb','jsonb','jsonb'],kitna_remove_member:['uuid','uuid'],kitna_leave_group:['uuid'],kitna_revoke_invites:['uuid'],kitna_invite:['uuid','uuid']};
async function rpc(name,args=[]) {const sig=types[name];return (await db.query(`select public.${name}(${args.map((_,i)=>'$'+(i+1)+'::'+sig[i]).join(',')}) as data`,args.map((v,i)=>sig[i]==='jsonb'?JSON.stringify(v):v))).rows[0].data;}
async function denied(action,code='42501') {await assert.rejects(action,e=>e.code===code);}
async function share(email='friend@example.com') {await as(owner);const id=randomUUID(),self=randomUUID(),other=randomUUID();const snapshot=await rpc('kitna_share_group',[randomUUID(),{id,name:'Trip',kind:'Trip',icon:'plane',color:'orange',simplifyDebts:true},[{id:self,name:'Owner',isSelf:true},{id:other,name:'Friend',isSelf:false,email}],[]]);return {id,self,other,snapshot};}
let trip, code;
test('protocol v2 is anonymous but invitation tables and new RPCs are private',async()=>{
  await as(null,'anon');assert.equal((await rpc('kitna_status')).schemaVersion,2);
  await denied(()=>rpc('kitna_group_code',[randomUUID()]));await denied(()=>rpc('kitna_invite_email',[randomUUID(),null,'A','a@example.com']));await denied(()=>db.query('select * from kitna_private.email_invites'));
});
test('sharing preserves a stable owner-only reusable 12-character code',async()=>{
  trip=await share();await as(owner);code=(await rpc('kitna_group_code',[trip.id])).code;assert.match(code,/^[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){2}$/);assert.equal((await rpc('kitna_group_code',[trip.id])).code,code);
  await as(stranger);await denied(()=>rpc('kitna_group_code',[trip.id]));
});
test('verified matching Google email automatically claims the existing participant',async()=>{
  await as(friend);const sync=await rpc('kitna_sync',[{}]);const snapshot=sync.groups.find(g=>g.group.id===trip.id);assert.equal(snapshot.myParticipantId,trip.other);assert.equal(snapshot.participants.length,2);assert.ok(!JSON.stringify(snapshot).includes('friend@example.com'));
  assert.equal((await rpc('kitna_accept_invite',[code.toLowerCase().replaceAll('-',' ')])).myParticipantId,trip.other);
});
test('a different email does not gain access from automatic synchronization',async()=>{await as(stranger);assert.ok(!(await rpc('kitna_sync',[{}])).memberGroupIds.includes(trip.id));});
test('unverified email and editable profile flags cannot claim an invitation',async()=>{
  await as(owner);await rpc('kitna_invite_email',[trip.id,null,'Unverified','unverified@example.com']);await rpc('kitna_invite_email',[trip.id,null,'Impostor','impostor@example.com']);
  for(const id of [unverified,impostor]){await as(id);assert.ok(!(await rpc('kitna_sync',[{}])).memberGroupIds.includes(trip.id));}
});
test('a code joins a new member once and preserves the email-bound participant',async()=>{
  await as(stranger);const first=await rpc('kitna_accept_invite',[code]);const second=await rpc('kitna_accept_invite',[code]);assert.equal(first.myParticipantId,second.myParticipantId);assert.equal(first.participants.find(p=>p.id===trip.other).userId,friend);
});
test('members cannot edit email bindings or see group codes',async()=>{await as(friend);await denied(()=>rpc('kitna_invite_email',[trip.id,trip.other,'Friend','changed@example.com']));await denied(()=>rpc('kitna_group_code',[trip.id]));});
test('owner cannot reassign a connected historical participant to another email',async()=>{await as(owner);await denied(()=>rpc('kitna_invite_email',[trip.id,trip.other,'Friend','other@example.com']),'22023');});
test('removed users cannot rejoin through a general code or stale email binding',async()=>{
  await as(owner);await rpc('kitna_remove_member',[trip.id,trip.other]);await as(friend);assert.ok(!(await rpc('kitna_sync',[{}])).memberGroupIds.includes(trip.id));assert.equal((await rpc('kitna_accept_invite',[code])).error,'targeted_invitation_required');
});
test('an explicit email reinvitation restores the original participant and history',async()=>{
  await as(owner);await rpc('kitna_invite_email',[trip.id,trip.other,'Friend',' FRIEND@example.com ']);await as(friend);const snapshot=(await rpc('kitna_sync',[{}])).groups.find(g=>g.group.id===trip.id);assert.equal(snapshot.myParticipantId,trip.other);
});
test('revoking invitations invalidates the code and pending email claims',async()=>{
  await as(owner);await rpc('kitna_revoke_invites',[trip.id]);const fresh=(await rpc('kitna_group_code',[trip.id])).code;assert.notEqual(fresh,code);await as(stranger);assert.equal((await rpc('kitna_accept_invite',[code])).error,'invalid_invite');
});
test('code throttling persists despite rejected attempts',async()=>{
  await as(impostor);for(let i=0;i<20;i++)assert.equal((await rpc('kitna_accept_invite',['ZZZZ-ZZZZ-ZZZZ'])).error,'invalid_invite');assert.equal((await rpc('kitna_accept_invite',['ZZZZ-ZZZZ-ZZZZ'])).error,'rate_limited');
});
test('an owner can leave after ownership moves to another joined account',async()=>{
  await as(owner);const left=await rpc('kitna_leave_group',[trip.id]);assert.ok([friend,stranger].includes(left.newOwnerId));assert.ok(!(await rpc('kitna_sync',[{}])).memberGroupIds.includes(trip.id));await as(left.newOwnerId);assert.match((await rpc('kitna_group_code',[trip.id])).code,/-/);
});
test('a sole joined owner cannot abandon the group',async()=>{const solo=await share('');await as(owner);await denied(()=>rpc('kitna_leave_group',[solo.id]),'22023');});
test('the invitation migration can run again without removing existing records',async()=>{
  await db.exec('reset role');const before=(await db.query('select count(*) as n from public.kitna_groups')).rows[0].n;await db.exec(v2);assert.equal((await db.query('select count(*) as n from public.kitna_groups')).rows[0].n,before);
});
