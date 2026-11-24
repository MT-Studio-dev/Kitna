-- Kitna protocol v2. Additive upgrade: keep all existing groups, records and receipts.
-- Apply after 202611240001_shared_groups.sql in the same Supabase project.
begin;

create table if not exists kitna_private.group_codes (
  group_id uuid primary key references public.kitna_groups(id),
  code text not null,
  code_hash text not null unique,
  expires_at timestamptz not null,
  revoked boolean not null default false
);
create table if not exists kitna_private.email_invites (
  group_id uuid not null references public.kitna_groups(id),
  participant_id uuid not null references public.kitna_participants(id),
  email text not null,
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null,
  accepted_by uuid references auth.users(id),
  revoked boolean not null default false,
  primary key (group_id, participant_id),
  unique (group_id, email)
);
create index if not exists kitna_email_invite_pending on kitna_private.email_invites(email,group_id) where accepted_by is null and not revoked;
create table if not exists kitna_private.join_attempts (
  user_id uuid primary key references auth.users(id),
  window_start timestamptz not null,
  attempts integer not null
);
alter table kitna_private.group_codes enable row level security;
alter table kitna_private.email_invites enable row level security;
alter table kitna_private.join_attempts enable row level security;
revoke all on kitna_private.group_codes,kitna_private.email_invites,kitna_private.join_attempts from public,anon,authenticated;

create or replace function kitna_private.normalized_email(p_email text) returns text
language plpgsql immutable set search_path = '' as $$
declare v_email text := lower(btrim(p_email));
begin
  if v_email is null or char_length(v_email)>254 or v_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$' then
    raise exception using errcode='22023',message='Enter a valid email address.';
  end if;
  return v_email;
end $$;

-- Identity data comes from Google through Auth, not editable user_metadata.
create or replace function kitna_private.verified_google_email() returns text
language sql stable security definer set search_path = '' as $$
  select lower(btrim(u.email)) from auth.users u
  where u.id=auth.uid() and u.email_confirmed_at is not null
    and exists(select 1 from auth.identities i where i.user_id=u.id and i.provider='google'
      and lower(btrim(i.identity_data->>'email'))=lower(btrim(u.email))
      and i.identity_data->>'email_verified'='true')
$$;

create or replace function public.kitna_group_code(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_current kitna_private.group_codes; v_bytes bytea; v_code text; v_alphabet text := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'; v_i integer;
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  select * into v_current from kitna_private.group_codes where group_id=p_group_id;
  if not found or v_current.revoked or v_current.expires_at<=now() then
    v_bytes:=extensions.gen_random_bytes(12); v_code:='';
    for v_i in 0..11 loop v_code:=v_code||substr(v_alphabet,(get_byte(v_bytes,v_i)%32)+1,1); end loop;
    insert into kitna_private.group_codes(group_id,code,code_hash,expires_at,revoked)
    values(p_group_id,v_code,encode(extensions.digest(v_code,'sha256'),'hex'),now()+interval '7 days',false)
    on conflict(group_id) do update set code=excluded.code,code_hash=excluded.code_hash,expires_at=excluded.expires_at,revoked=false
    returning * into v_current;
  end if;
  return jsonb_build_object('code',substr(v_current.code,1,4)||'-'||substr(v_current.code,5,4)||'-'||substr(v_current.code,9,4),'expiresAt',v_current.expires_at);
end $$;

create or replace function public.kitna_invite_email(p_group_id uuid,p_participant_id uuid,p_name text,p_email text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.normalized_email(p_email); v_person public.kitna_participants; v_existing uuid; v_id uuid:=p_participant_id; v_accepted uuid;
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if exists(select 1 from public.kitna_groups where id=p_group_id and coalesce((settings->>'archived')::boolean,false)) then raise exception using errcode='22023',message='Restore this archived group before adding people.'; end if;
  if exists(select 1 from auth.users where id=v_user and lower(btrim(email))=v_email) then raise exception using errcode='22023',message='You are already in this group. Enter your friend''s email.'; end if;
  select participant_id into v_existing from kitna_private.email_invites where group_id=p_group_id and email=v_email;
  if v_id is null then v_id:=v_existing; end if;
  if v_existing is not null and v_existing is distinct from v_id then raise exception using errcode='22023',message='This email is already linked to another participant in this group.'; end if;
  if v_id is null then
    if exists(select 1 from public.kitna_groups where id=p_group_id and settings->>'kind'='Direct') then raise exception using errcode='22023',message='Use the existing person in this direct group.'; end if;
    if (select count(*) from public.kitna_participants where group_id=p_group_id)>=100 then raise exception using errcode='22023',message='This group has reached its participant limit.'; end if;
    v_id:=gen_random_uuid();
    insert into public.kitna_participants(id,group_id,name) values(v_id,p_group_id,btrim(kitna_private.checked_text(to_jsonb(p_name),100)));
    update public.kitna_groups set version=version+1 where id=p_group_id;
  end if;
  select * into v_person from public.kitna_participants where group_id=p_group_id and id=v_id;
  if not found or v_person.user_id=v_user then raise exception using errcode='22023',message='Choose your friend''s group participant.'; end if;
  if v_person.user_id is not null and not exists(select 1 from auth.users where id=v_person.user_id and lower(btrim(email))=v_email) then raise exception using errcode='22023',message='This participant is linked to a different account. Their historical identity cannot be reassigned.'; end if;
  if v_person.active and v_person.user_id is not null then v_accepted:=v_person.user_id; end if;
  insert into kitna_private.email_invites(group_id,participant_id,email,created_by,expires_at,accepted_by,revoked)
  values(p_group_id,v_id,v_email,v_user,now()+interval '30 days',v_accepted,false)
  on conflict(group_id,participant_id) do update set email=excluded.email,created_by=excluded.created_by,expires_at=excluded.expires_at,accepted_by=excluded.accepted_by,revoked=false;
  -- A prior bearer link must not bypass the new email binding.
  update kitna_private.invites set revoked=true where group_id=p_group_id and participant_id=v_id and accepted_by is null;
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function kitna_private.claim_email_invites(p_group_id uuid default null) returns void
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.verified_google_email(); v_item record; v_invite kitna_private.email_invites; v_person public.kitna_participants;
begin
  if v_email is null then return; end if;
  for v_item in select group_id,participant_id from kitna_private.email_invites where email=v_email and not revoked and accepted_by is null and expires_at>now() and (p_group_id is null or group_id=p_group_id) order by group_id,participant_id loop
    perform 1 from public.kitna_groups where id=v_item.group_id for update;
    select * into v_invite from kitna_private.email_invites where group_id=v_item.group_id and participant_id=v_item.participant_id for update;
    if v_invite.revoked or v_invite.accepted_by is not null or v_invite.expires_at<=now() or v_invite.email<>v_email then continue; end if;
    select * into v_person from public.kitna_participants where group_id=v_item.group_id and id=v_item.participant_id;
    if not found or (v_person.user_id is not null and v_person.user_id<>v_user) then continue; end if;
    if exists(select 1 from public.kitna_participants where group_id=v_item.group_id and user_id=v_user and id<>v_person.id) then continue; end if;
    update public.kitna_participants set user_id=v_user,active=true where id=v_person.id;
    update kitna_private.email_invites set accepted_by=v_user where group_id=v_item.group_id and participant_id=v_person.id;
    update public.kitna_groups set revision=revision+1 where id=v_item.group_id;
    insert into public.kitna_activity(group_id,message,time,actor_id) values(v_item.group_id,'Joined the group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  end loop;
end $$;

create or replace function kitna_private.snapshot(p_group_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_person uuid := kitna_private.require_member(p_group_id); v_result jsonb;
begin
  select jsonb_build_object(
    'group',g.settings || jsonb_build_object('id',g.id,'ownerId',g.owner_id,'version',g.version,'members',(select coalesce(jsonb_agg(p.id order by p.id),'[]'::jsonb) from public.kitna_participants p where p.group_id=g.id)),
    'revision',g.revision,'myParticipantId',v_person,
    'participants',(select coalesce(jsonb_agg(jsonb_build_object('id',p.id,'name',p.name,'userId',p.user_id,'active',p.active) order by p.id),'[]'::jsonb) from public.kitna_participants p where p.group_id=g.id),
    'records',(select coalesce(jsonb_agg(jsonb_build_object('kind',r.kind,'id',r.id,'payload',r.payload,'version',r.version,'actorId',r.actor_id,'imported',r.imported) order by r.kind,r.id),'[]'::jsonb) from public.kitna_records r where r.group_id=g.id),
    'revisions',(select coalesce(jsonb_agg(jsonb_build_object('id',r.id,'expense',r.expense,'reason',r.reason,'time',r.time,'actorId',r.actor_id,'imported',r.imported) order by r.time,r.id),'[]'::jsonb) from public.kitna_revisions r where r.group_id=g.id),
    'activity',(select coalesce(jsonb_agg(jsonb_build_object('id',a.id,'message',a.message,'time',a.time,'actorId',case when a.imported then null else a.actor_id end,'imported',a.imported) order by a.time,a.id),'[]'::jsonb) from public.kitna_activity a where a.group_id=g.id)
  ) into v_result from public.kitna_groups g where g.id=p_group_id;
  return v_result;
end $$;

create or replace function kitna_private.accept_legacy_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_invite kitna_private.invites; v_person uuid; v_name text;
begin
  if p_token is null or p_token !~ '^[a-f0-9]{64}$' then raise exception using errcode='22023', message='This invitation is invalid.'; end if;
  -- Lock the group before the invitation, matching all other membership mutations.
  select * into v_invite from kitna_private.invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex');
  if not found then raise exception using errcode='22023', message='This invitation is invalid or unavailable.'; end if;
  perform 1 from public.kitna_groups where id=v_invite.group_id for update;
  select * into v_invite from kitna_private.invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex') for update;
  if v_invite.accepted_by=v_user and not v_invite.revoked and kitna_private.is_member(v_invite.group_id) then return kitna_private.snapshot(v_invite.group_id); end if;
  if v_invite.revoked or v_invite.expires_at<=now() or v_invite.accepted_by is not null then raise exception using errcode='22023', message='This invitation expired or was already used.'; end if;
  if exists(select 1 from public.kitna_participants where group_id=v_invite.group_id and user_id=v_user and (active or id is distinct from v_invite.participant_id)) then raise exception using errcode='22023', message='Your account is already linked to a participant in this group.'; end if;
  if v_invite.participant_id is not null then
    update public.kitna_participants set user_id=v_user,active=true where id=v_invite.participant_id and group_id=v_invite.group_id and (user_id is null or (user_id=v_user and not active)) returning id into v_person;
    if v_person is null then raise exception using errcode='22023', message='This participant was already claimed.'; end if;
  else
    if exists(select 1 from public.kitna_groups where id=v_invite.group_id and settings->>'kind'='Direct') then raise exception using errcode='22023', message='A direct group cannot add another participant.'; end if;
    if (select count(*) from public.kitna_participants where group_id=v_invite.group_id)>=100 then raise exception using errcode='22023', message='This group has reached its participant limit.'; end if;
    select left(coalesce(nullif(raw_user_meta_data->>'full_name',''),nullif(raw_user_meta_data->>'name',''),nullif(split_part(email,'@',1),''),'New member'),100) into v_name from auth.users where id=v_user;
    insert into public.kitna_participants(id,group_id,name,user_id) values(gen_random_uuid(),v_invite.group_id,coalesce(v_name,'New member'),v_user) returning id into v_person;
  end if;
  update kitna_private.invites set accepted_by=v_user where token_hash=v_invite.token_hash;
  update public.kitna_groups set revision=revision+1 where id=v_invite.group_id;
  insert into public.kitna_activity(group_id,message,time,actor_id) values(v_invite.group_id,'Joined the group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  return kitna_private.snapshot(v_invite.group_id);
end $$;

create or replace function public.kitna_accept_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_code text:=upper(regexp_replace(coalesce(p_token,''),'[-[:space:]]','','g')); v_invite kitna_private.group_codes; v_person uuid; v_name text; v_attempts integer;
begin
  if p_token ~ '^[a-f0-9]{64}$' then return kitna_private.accept_legacy_invite(p_token); end if;
  -- Invalid attempts return a JSON error rather than raising: a SQL exception
  -- would roll back the throttle update and permit unlimited guessing.
  perform pg_advisory_xact_lock(hashtextextended('kitna-join:'||v_user::text,0));
  insert into kitna_private.join_attempts(user_id,window_start,attempts) values(v_user,clock_timestamp(),1)
  on conflict(user_id) do update set
    attempts=case when kitna_private.join_attempts.window_start<=clock_timestamp()-interval '15 minutes' then 1 else kitna_private.join_attempts.attempts+1 end,
    window_start=case when kitna_private.join_attempts.window_start<=clock_timestamp()-interval '15 minutes' then clock_timestamp() else kitna_private.join_attempts.window_start end
  returning attempts into v_attempts;
  if v_attempts>20 then return jsonb_build_object('error','rate_limited','message','Too many code attempts. Try again in 15 minutes.'); end if;
  if v_code !~ '^[2-9A-HJ-NP-Z]{12}$' then return jsonb_build_object('error','invalid_invite','message','Enter the 12-character group code.'); end if;
  select * into v_invite from kitna_private.group_codes where code_hash=encode(extensions.digest(v_code,'sha256'),'hex');
  if not found or v_invite.revoked or v_invite.expires_at<=now() then return jsonb_build_object('error','invalid_invite','message','This group code is invalid or expired. Ask the owner for a fresh code.'); end if;
  perform 1 from public.kitna_groups where id=v_invite.group_id for update;
  select * into v_invite from kitna_private.group_codes where group_id=v_invite.group_id;
  if v_invite.revoked or v_invite.expires_at<=now() or v_invite.code<>v_code then return jsonb_build_object('error','invalid_invite','message','This group code is invalid or expired. Ask the owner for a fresh code.'); end if;
  perform kitna_private.claim_email_invites(v_invite.group_id);
  select id into v_person from public.kitna_participants where group_id=v_invite.group_id and user_id=v_user and active;
  if v_person is not null then return kitna_private.snapshot(v_invite.group_id); end if;
  if exists(select 1 from public.kitna_participants where group_id=v_invite.group_id and user_id=v_user) then return jsonb_build_object('error','targeted_invitation_required','message','You previously left or were removed. Ask the owner to invite your email again.'); end if;
  if exists(select 1 from public.kitna_groups where id=v_invite.group_id and (settings->>'kind'='Direct' or coalesce((settings->>'archived')::boolean,false))) then return jsonb_build_object('error','group_unavailable','message','This group cannot add a member with a general code. Ask the owner to invite your email.'); end if;
  if (select count(*) from public.kitna_participants where group_id=v_invite.group_id)>=100 then return jsonb_build_object('error','group_full','message','This group has reached its participant limit.'); end if;
  select left(coalesce(nullif(raw_user_meta_data->>'full_name',''),nullif(raw_user_meta_data->>'name',''),nullif(split_part(email,'@',1),''),'New member'),100) into v_name from auth.users where id=v_user;
  insert into public.kitna_participants(id,group_id,name,user_id) values(gen_random_uuid(),v_invite.group_id,coalesce(v_name,'New member'),v_user);
  update public.kitna_groups set revision=revision+1,version=version+1 where id=v_invite.group_id;
  insert into public.kitna_activity(group_id,message,time,actor_id) values(v_invite.group_id,'Joined the group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  return kitna_private.snapshot(v_invite.group_id);
end $$;

create or replace function public.kitna_sync(p_revisions jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_pair record; v_result jsonb;
begin
  if p_revisions is null or jsonb_typeof(p_revisions)<>'object' or octet_length(p_revisions::text)>262144 then raise exception using errcode='22023',message='Invalid synchronization cursor.'; end if;
  for v_pair in select * from jsonb_each(p_revisions) loop perform kitna_private.number(v_pair.value,0,9223372036854775807); end loop;
  perform kitna_private.claim_email_invites();
  select jsonb_build_object(
    'memberGroupIds',coalesce(jsonb_agg(g.id order by g.id),'[]'::jsonb),
    'groups',coalesce(jsonb_agg(kitna_private.snapshot(g.id) order by g.id) filter(where (p_revisions->>g.id::text)::bigint is distinct from g.revision),'[]'::jsonb)
  ) into v_result from public.kitna_groups g join public.kitna_participants p on p.group_id=g.id where p.user_id=v_user and p.active;
  return v_result;
end $$;

create or replace function public.kitna_revoke_invites(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  update kitna_private.invites set revoked=true where group_id=p_group_id and accepted_by is null;
  update kitna_private.group_codes set revoked=true where group_id=p_group_id;
  update kitna_private.email_invites set revoked=true where group_id=p_group_id and accepted_by is null;
  return jsonb_build_object('revoked',true);
end $$;

create or replace function public.kitna_remove_member(p_group_id uuid,p_participant_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if exists(select 1 from public.kitna_participants where id=p_participant_id and group_id=p_group_id and user_id=auth.uid()) then raise exception using errcode='22023',message='Use Leave group to hand ownership to another joined member.'; end if;
  update public.kitna_participants set active=false where id=p_participant_id and group_id=p_group_id;
  if not found then raise exception using errcode='22023',message='Participant unavailable.'; end if;
  update kitna_private.invites set revoked=true where group_id=p_group_id and participant_id=p_participant_id and accepted_by is null;
  update kitna_private.email_invites set revoked=true where group_id=p_group_id and participant_id=p_participant_id;
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function public.kitna_leave_group(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_person uuid; v_owner uuid; v_next uuid;
begin
  select owner_id into v_owner from public.kitna_groups where id=p_group_id for update;
  v_person:=kitna_private.require_member(p_group_id);
  if v_owner=v_user then
    select user_id into v_next from public.kitna_participants where group_id=p_group_id and active and user_id is not null and user_id<>v_user order by id limit 1;
    if v_next is null then raise exception using errcode='22023',message='Ask another friend to join before you leave. The group needs a signed-in owner to keep its records accessible.'; end if;
    update public.kitna_groups set owner_id=v_next,version=version+1 where id=p_group_id;
    update kitna_private.invites set revoked=true where group_id=p_group_id and accepted_by is null;
    update kitna_private.group_codes set revoked=true where group_id=p_group_id;
    update kitna_private.email_invites set revoked=true where group_id=p_group_id and accepted_by is null;
  end if;
  update public.kitna_participants set active=false where id=v_person;
  update kitna_private.invites set revoked=true where group_id=p_group_id and participant_id=v_person and accepted_by is null;
  update kitna_private.email_invites set revoked=true where group_id=p_group_id and participant_id=v_person;
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  insert into public.kitna_activity(group_id,message,time,actor_id) values(p_group_id,'Left the group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  return jsonb_build_object('left',true,'groupId',p_group_id,'newOwnerId',v_next);
end $$;

create or replace function public.kitna_share_group(p_command_id uuid,p_group jsonb,p_participants jsonb,p_records jsonb default '[]'::jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_id uuid := (p_group->>'id')::uuid; v_fingerprint text; v_seen uuid; v_item jsonb; v_payload jsonb; v_kind text; v_entity uuid; v_settings jsonb;
begin
  v_fingerprint := encode(extensions.digest(jsonb_build_array('share',p_group,p_participants,p_records)::text,'sha256'),'hex');
  v_seen := kitna_private.command_seen(p_command_id,v_fingerprint);
  if v_seen is not null then return kitna_private.snapshot(v_seen); end if;
  v_settings := kitna_private.clean_group(p_group);
  if v_id is null or jsonb_typeof(p_participants) is distinct from 'array' or jsonb_array_length(p_participants) not between 2 and 100 or jsonb_typeof(p_records) is distinct from 'array' or jsonb_array_length(p_records)>10000 or octet_length(p_records::text)>20971520 then raise exception using errcode='22023', message='Invalid group import or group size.'; end if;
  if (select count(*) from jsonb_array_elements(p_participants) x where x->'isSelf'='true'::jsonb)<>1 then raise exception using errcode='22023', message='Map exactly one participant to your account.'; end if;
  if v_settings->>'kind'='Direct' and jsonb_array_length(p_participants)<>2 then raise exception using errcode='22023', message='Direct groups require two participants.'; end if;
  insert into public.kitna_groups(id,owner_id,settings) values(v_id,v_user,v_settings);
  for v_item in select value from jsonb_array_elements(p_participants) loop
    insert into public.kitna_participants(id,group_id,name,user_id) values((v_item->>'id')::uuid,v_id,btrim(kitna_private.checked_text(v_item->'name',100)),case when v_item->'isSelf'='true'::jsonb then v_user else null end);
  end loop;
  for v_item in select value from jsonb_array_elements(p_participants) loop
    if v_item->'isSelf' is distinct from 'true'::jsonb and nullif(btrim(v_item->>'email'),'') is not null then
      perform public.kitna_invite_email(v_id,(v_item->>'id')::uuid,v_item->>'name',v_item->>'email');
    end if;
  end loop;
  perform public.kitna_group_code(v_id);
  -- Expenses first, so imported comments and revisions can safely reference them.
  for v_item in select value from jsonb_array_elements(p_records) order by case value->>'kind' when 'expense' then 0 when 'settlement' then 1 when 'comment' then 2 when 'revision' then 3 else 4 end loop
    v_kind := v_item->>'kind'; v_payload := v_item->'payload'; v_entity := (v_item->>'id')::uuid;
    if v_kind in ('expense','settlement','comment') then
      perform kitna_private.validate_record(v_id,v_kind,v_entity,v_payload,true);
      insert into public.kitna_records(group_id,kind,id,payload,actor_id,imported) values(v_id,v_kind,v_entity,v_payload,v_user,true);
    elsif v_kind='revision' then
      perform kitna_private.validate_record(v_id,'expense',(v_payload->'expense'->>'id')::uuid,v_payload->'expense',true);
      if not exists(select 1 from public.kitna_records where group_id=v_id and kind='expense' and id=(v_payload->'expense'->>'id')::uuid) then raise exception using errcode='22023', message='History references an unknown expense.'; end if;
      insert into public.kitna_revisions(id,group_id,expense,reason,time,actor_id,imported) values(v_entity,v_id,v_payload->'expense',kitna_private.checked_text(v_payload->'reason',500),kitna_private.number(v_payload->'time',0,253402300799999),v_user,true);
    elsif v_kind='activity' then
      insert into public.kitna_activity(id,group_id,message,time,actor_id,imported) values(v_entity,v_id,kitna_private.checked_text(v_payload->'message',2000),kitna_private.number(v_payload->'time',0,253402300799999),v_user,true);
    else raise exception using errcode='22023', message='Unsupported import record.';
    end if;
  end loop;
  perform kitna_private.validate_totals(v_id);
  insert into kitna_private.commands(actor_id,id,group_id,fingerprint) values(v_user,p_command_id,v_id,v_fingerprint);
  insert into public.kitna_activity(group_id,message,time,actor_id) values(v_id,'Shared this group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  return kitna_private.snapshot(v_id);
end $$;

create or replace function public.kitna_status() returns jsonb
language sql immutable set search_path = '' as $$ select jsonb_build_object('schemaVersion',2,'ready',true,'receiptBucket','kitna-receipts','invitationsReady',true) $$;

revoke all on function kitna_private.normalized_email(text),kitna_private.verified_google_email(),kitna_private.claim_email_invites(uuid),kitna_private.accept_legacy_invite(text) from public,anon,authenticated;
revoke all on function public.kitna_group_code(uuid),public.kitna_invite_email(uuid,uuid,text,text) from public,anon;
grant execute on function public.kitna_group_code(uuid),public.kitna_invite_email(uuid,uuid,text,text) to authenticated;
-- Existing public RPC signatures retain their explicit v1 grants.
notify pgrst, 'reload schema';
commit;
