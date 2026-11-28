-- Kitna protocol v3. Additive upgrade after both 20261124 migrations.
-- No ledger rows, IDs, allocations, receipts or command versions are replaced.
begin;

create table if not exists kitna_private.friend_invites (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id),
  invitee_name text not null check (char_length(btrim(invitee_name)) between 1 and 100),
  email text,
  token text not null unique,
  token_hash text not null unique,
  expires_at timestamptz not null default now()+interval '7 days',
  accepted_by uuid references auth.users(id),
  revoked boolean not null default false,
  created_at timestamptz not null default now(),
  check (accepted_by is distinct from sender_id)
);
create index if not exists kitna_friend_invite_inbox on kitna_private.friend_invites(email) where accepted_by is null and not revoked;
-- Both sides of the private inbox need indexed lookups. The full sender index
-- also covers accepted/expired invitation history and pending-link retry order.
create index if not exists kitna_friend_invite_sender on kitna_private.friend_invites(sender_id,created_at);
create table if not exists kitna_private.friendships (
  first_user uuid not null references auth.users(id),
  second_user uuid not null references auth.users(id),
  invitation_id uuid not null references kitna_private.friend_invites(id),
  primary key (first_user,second_user),
  check (first_user < second_user)
);
-- The primary key covers first_user; friendships are queried from either side.
create index if not exists kitna_friendships_second_user on kitna_private.friendships(second_user);
alter table kitna_private.friend_invites enable row level security;
alter table kitna_private.friendships enable row level security;
revoke all on kitna_private.friend_invites,kitna_private.friendships from public,anon,authenticated;

create or replace function kitna_private.invite_attempt() returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_attempts integer;
begin
  perform pg_advisory_xact_lock(hashtextextended('kitna-join:'||v_user::text,0));
  insert into kitna_private.join_attempts(user_id,window_start,attempts) values(v_user,clock_timestamp(),1)
  on conflict(user_id) do update set
    attempts=case when kitna_private.join_attempts.window_start<=clock_timestamp()-interval '15 minutes' then 1 else kitna_private.join_attempts.attempts+1 end,
    window_start=case when kitna_private.join_attempts.window_start<=clock_timestamp()-interval '15 minutes' then clock_timestamp() else kitna_private.join_attempts.window_start end
  returning attempts into v_attempts;
  return v_attempts<=20;
end $$;

-- Preview only harmless group information after possession of a valid secret.
-- Authentication is required, and no expense, balance, receipt or member name is returned.
create or replace function public.kitna_preview_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_id uuid; v_expires timestamptz; v_person uuid; v_code text:=upper(regexp_replace(coalesce(p_token,''),'[-[:space:]]','','g')); v_invite kitna_private.invites;
begin
  if not kitna_private.invite_attempt() then return jsonb_build_object('error','rate_limited','message','Too many invitation attempts. Try again in 15 minutes.'); end if;
  if p_token ~ '^[a-f0-9]{64}$' then
    select * into v_invite from kitna_private.invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex') and not revoked and expires_at>now() and (accepted_by is null or accepted_by=v_user);
    if found then
      v_id:=v_invite.group_id; v_expires:=v_invite.expires_at; v_person:=v_invite.participant_id;
      if v_person is not null and not exists(select 1 from public.kitna_participants where id=v_person and group_id=v_id and user_id=v_user and active) and not exists(select 1 from kitna_private.email_invites where group_id=v_id and participant_id=v_person and not revoked and expires_at>now() and email=kitna_private.verified_google_email()) then
        return jsonb_build_object('error','verified_claim_required','message','Ask the owner to invite the email verified on your Google account to connect an existing participant.');
      end if;
    end if;
  elsif v_code ~ '^[2-9A-HJ-NP-Z]{12}$' then
    select group_id,expires_at into v_id,v_expires from kitna_private.group_codes where code_hash=encode(extensions.digest(v_code,'sha256'),'hex') and not revoked and expires_at>now();
  end if;
  if v_id is null then return jsonb_build_object('error','invalid_invite','message','This invitation is invalid, expired or revoked. Ask for a fresh invitation.'); end if;
  return (select jsonb_build_object('kind','group','groupId',g.id,'name',g.settings->>'name','memberCount',(select count(*) from public.kitna_participants where group_id=g.id and active),'alreadyMember',kitna_private.is_member(g.id),'expiresAt',v_expires,'claim',v_person is not null) from public.kitna_groups g where g.id=v_id);
end $$;

-- A bearer link can add a NEW slot, but it cannot prove ownership of an existing
-- participant. Targeted links require the matching verified Google email.
create or replace function public.kitna_invite(p_group_id uuid,p_participant_id uuid default null) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_token text:=encode(extensions.gen_random_bytes(32),'hex'); v_expires timestamptz:=now()+interval '7 days';
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if exists(select 1 from public.kitna_groups where id=p_group_id and coalesce((settings->>'archived')::boolean,false)) then raise exception using errcode='22023',message='Restore the group before inviting people.'; end if;
  if p_participant_id is null and exists(select 1 from public.kitna_groups where id=p_group_id and settings->>'kind'='Direct') then raise exception using errcode='22023',message='Invite the existing person by verified email in a direct expense.'; end if;
  if p_participant_id is not null then
    if not exists(select 1 from public.kitna_participants where id=p_participant_id and group_id=p_group_id and (user_id is null or not active)) then raise exception using errcode='22023',message='Choose an unclaimed or removed participant to invite.'; end if;
    if not exists(select 1 from kitna_private.email_invites where group_id=p_group_id and participant_id=p_participant_id and not revoked and expires_at>now()) then raise exception using errcode='22023',message='Invite this person by email before creating a participant claim link.'; end if;
  end if;
  insert into kitna_private.invites(token_hash,group_id,participant_id,created_by,expires_at) values(encode(extensions.digest(v_token,'sha256'),'hex'),p_group_id,p_participant_id,auth.uid(),v_expires);
  return jsonb_build_object('token',v_token,'expires_at',v_expires,'groupId',p_group_id,'participantId',p_participant_id);
end $$;

create or replace function kitna_private.accept_legacy_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_invite kitna_private.invites; v_person uuid; v_name text;
begin
  if p_token is null or p_token !~ '^[a-f0-9]{64}$' then raise exception using errcode='22023',message='This invitation is invalid.'; end if;
  select * into v_invite from kitna_private.invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex');
  if not found then raise exception using errcode='22023',message='This invitation is invalid or unavailable.'; end if;
  perform 1 from public.kitna_groups where id=v_invite.group_id for update;
  select * into v_invite from kitna_private.invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex') for update;
  if v_invite.revoked or v_invite.expires_at<=now() then raise exception using errcode='22023',message='This invitation expired or was revoked.'; end if;
  if kitna_private.is_member(v_invite.group_id) and (v_invite.accepted_by is null or v_invite.accepted_by=v_user) then return kitna_private.snapshot(v_invite.group_id); end if;
  if v_invite.accepted_by is not null then raise exception using errcode='22023',message='This invitation was already used.'; end if;
  if exists(select 1 from public.kitna_participants where group_id=v_invite.group_id and user_id=v_user and id is distinct from v_invite.participant_id) then raise exception using errcode='22023',message='You previously left or were removed. Ask the owner to invite your email again.'; end if;
  if exists(select 1 from public.kitna_groups where id=v_invite.group_id and coalesce((settings->>'archived')::boolean,false)) then raise exception using errcode='22023',message='This group is archived.'; end if;
  if v_invite.participant_id is not null then
    if not exists(select 1 from kitna_private.email_invites where group_id=v_invite.group_id and participant_id=v_invite.participant_id and not revoked and expires_at>now() and accepted_by is null and email=kitna_private.verified_google_email()) then raise exception using errcode='42501',message='This participant can only be claimed by its verified invited Google email.'; end if;
    update public.kitna_participants set user_id=v_user,active=true where id=v_invite.participant_id and group_id=v_invite.group_id and (user_id is null or (user_id=v_user and not active)) returning id into v_person;
    if v_person is null then raise exception using errcode='22023',message='This participant was already claimed.'; end if;
    update kitna_private.email_invites set accepted_by=v_user where group_id=v_invite.group_id and participant_id=v_person;
  else
    if exists(select 1 from public.kitna_groups where id=v_invite.group_id and settings->>'kind'='Direct') then raise exception using errcode='22023',message='A direct expense cannot add another participant.'; end if;
    if (select count(*) from public.kitna_participants where group_id=v_invite.group_id)>=100 then raise exception using errcode='22023',message='This group has reached its participant limit.'; end if;
    select left(coalesce(nullif(raw_user_meta_data->>'full_name',''),nullif(raw_user_meta_data->>'name',''),'New member'),100) into v_name from auth.users where id=v_user;
    insert into public.kitna_participants(id,group_id,name,user_id) values(gen_random_uuid(),v_invite.group_id,coalesce(v_name,'New member'),v_user) returning id into v_person;
  end if;
  update kitna_private.invites set accepted_by=v_user where token_hash=v_invite.token_hash;
  update public.kitna_groups set revision=revision+1,version=version+1 where id=v_invite.group_id;
  insert into public.kitna_activity(group_id,message,time,actor_id) values(v_invite.group_id,'Joined the group',floor(extract(epoch from clock_timestamp())*1000)::bigint,v_user);
  return kitna_private.snapshot(v_invite.group_id);
end $$;

-- Stable client participant IDs make a retried batch idempotent. An existing ID
-- is never silently renamed, reactivated, or moved between groups.
create or replace function public.kitna_add_members(p_group_id uuid,p_members jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_item jsonb; v_id uuid; v_name text; v_added integer:=0;
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if jsonb_typeof(p_members) is distinct from 'array' or jsonb_array_length(p_members) not between 1 and 100 then raise exception using errcode='22023',message='Choose between one and 100 people.'; end if;
  if exists(select 1 from public.kitna_groups where id=p_group_id and (settings->>'kind'='Direct' or coalesce((settings->>'archived')::boolean,false))) then raise exception using errcode='22023',message='Members cannot be added to this expense or archived group.'; end if;
  for v_item in select value from jsonb_array_elements(p_members) loop
    v_id:=(v_item->>'id')::uuid; v_name:=btrim(kitna_private.checked_text(v_item->'name',100));
    if v_id is null then raise exception using errcode='22023',message='A stable participant identity is required.'; end if;
    if exists(select 1 from public.kitna_participants where id=v_id) then
      if not exists(select 1 from public.kitna_participants where id=v_id and group_id=p_group_id and name=v_name and active) then raise exception using errcode='22023',message='This participant identity is already used. Invite a removed person by their verified email.'; end if;
    else
      if (select count(*) from public.kitna_participants where group_id=p_group_id)>=100 then raise exception using errcode='22023',message='This group has reached its participant limit.'; end if;
      insert into public.kitna_participants(id,group_id,name) values(v_id,p_group_id,v_name); v_added:=v_added+1;
    end if;
    if nullif(btrim(v_item->>'email'),'') is not null then perform public.kitna_invite_email(p_group_id,v_id,v_name,v_item->>'email'); end if;
  end loop;
  if v_added>0 then update public.kitna_groups set version=version+1,revision=revision+1 where id=p_group_id; end if;
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function kitna_private.account_name(p_user uuid) returns text
language sql stable security definer set search_path = '' as $$
  select left(coalesce(nullif(raw_user_meta_data->>'full_name',''),nullif(raw_user_meta_data->>'name',''),'Kitna friend'),100) from auth.users where id=p_user
$$;
create or replace function kitna_private.friend_result(p_id uuid,p_peer uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('id',p_id,'userId',u.id,'name',kitna_private.account_name(u.id),'email',case when u.email_confirmed_at is not null and exists(select 1 from auth.identities i where i.user_id=u.id and i.provider='google' and i.identity_data->>'email_verified'='true' and lower(btrim(i.identity_data->>'email'))=lower(btrim(u.email))) then lower(btrim(u.email)) else null end,'status','accepted') from auth.users u where u.id=p_peer
$$;

create or replace function public.kitna_friend_invite(p_name text,p_email text default null) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_name text:=btrim(kitna_private.checked_text(to_jsonb(p_name),100)); v_email text; v_invite kitna_private.friend_invites; v_token text;
begin
  if nullif(btrim(p_email),'') is not null then v_email:=kitna_private.normalized_email(p_email); end if;
  if v_email=kitna_private.verified_google_email() then raise exception using errcode='22023',message='Enter your friend''s email.'; end if;
  -- Serializes duplicate taps without revealing whether the email is registered.
  perform 1 from auth.users where id=v_user for update;
  select * into v_invite from kitna_private.friend_invites where sender_id=v_user and not revoked and expires_at>now() and accepted_by is null and (case when v_email is null then email is null and lower(invitee_name)=lower(v_name) else email=v_email end) order by created_at limit 1;
  if not found then
    v_token:=encode(extensions.gen_random_bytes(32),'hex');
    insert into kitna_private.friend_invites(sender_id,invitee_name,email,token,token_hash) values(v_user,v_name,v_email,v_token,encode(extensions.digest(v_token,'sha256'),'hex')) returning * into v_invite;
  end if;
  return jsonb_build_object('id',v_invite.id,'token',v_invite.token,'expiresAt',v_invite.expires_at,'status','pending');
end $$;

create or replace function public.kitna_preview_friend_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_invite kitna_private.friend_invites;
begin
  if not kitna_private.invite_attempt() then return jsonb_build_object('error','rate_limited','message','Too many invitation attempts. Try again in 15 minutes.'); end if;
  if p_token ~ '^[a-f0-9]{64}$' then select * into v_invite from kitna_private.friend_invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex') and not revoked and expires_at>now() and (accepted_by is null or accepted_by=v_user); end if;
  if v_invite.id is null then return jsonb_build_object('error','invalid_invite','message','This friend invitation is invalid, expired or revoked.'); end if;
  if v_invite.email is not null and v_invite.email is distinct from kitna_private.verified_google_email() and v_invite.sender_id<>v_user then return jsonb_build_object('error','email_mismatch','message','Sign in with the Google account that received this invitation.'); end if;
  return jsonb_build_object('id',v_invite.id,'kind','friend','name',kitna_private.account_name(v_invite.sender_id),'inviteeName',v_invite.invitee_name,'expiresAt',v_invite.expires_at,'alreadyAccepted',v_invite.accepted_by=v_user);
end $$;

create or replace function kitna_private.accept_friend(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_invite kitna_private.friend_invites;
begin
  select * into v_invite from kitna_private.friend_invites where id=p_id for update;
  if not found or v_invite.revoked or v_invite.expires_at<=now() then raise exception using errcode='22023',message='This friend invitation expired or was revoked.'; end if;
  if v_invite.sender_id=v_user then raise exception using errcode='22023',message='Share this invitation with your friend.'; end if;
  if kitna_private.verified_google_email() is null or (v_invite.email is not null and v_invite.email is distinct from kitna_private.verified_google_email()) then raise exception using errcode='42501',message='Use the verified Google account that received this invitation.'; end if;
  if v_invite.accepted_by is not null and v_invite.accepted_by<>v_user then raise exception using errcode='22023',message='This friend invitation was already accepted.'; end if;
  insert into kitna_private.friendships(first_user,second_user,invitation_id) values(least(v_user,v_invite.sender_id),greatest(v_user,v_invite.sender_id),v_invite.id) on conflict(first_user,second_user) do nothing;
  update kitna_private.friend_invites set accepted_by=v_user where id=p_id;
  return kitna_private.friend_result(v_invite.id,v_invite.sender_id);
end $$;

create or replace function public.kitna_accept_friend_invite(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  perform kitna_private.require_user();
  if p_token ~ '^[a-f0-9]{64}$' then select id into v_id from kitna_private.friend_invites where token_hash=encode(extensions.digest(p_token,'sha256'),'hex'); end if;
  if v_id is null then raise exception using errcode='22023',message='This friend invitation is invalid.'; end if;
  return kitna_private.accept_friend(v_id);
end $$;
create or replace function public.kitna_accept_friend_email(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform kitna_private.require_user();
  if not exists(select 1 from kitna_private.friend_invites where id=p_id and email=kitna_private.verified_google_email()) then raise exception using errcode='42501',message='This invitation belongs to another verified Google email.'; end if;
  return kitna_private.accept_friend(p_id);
end $$;
create or replace function public.kitna_revoke_friend_invite(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user();
begin
  update kitna_private.friend_invites set revoked=true where id=p_id and sender_id=v_user and accepted_by is null;
  if not found then raise exception using errcode='42501',message='Only the sender can revoke a pending invitation.'; end if;
  return jsonb_build_object('revoked',true);
end $$;
create or replace function public.kitna_friends() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.verified_google_email();
begin
  return jsonb_build_object(
    'friends',(select coalesce(jsonb_agg(kitna_private.friend_result(f.invitation_id,case when f.first_user=v_user then f.second_user else f.first_user end)),'[]'::jsonb) from kitna_private.friendships f where v_user in (f.first_user,f.second_user)),
    'invitations',(select coalesce(jsonb_agg(jsonb_build_object('id',i.id,'name',case when i.sender_id=v_user then i.invitee_name else kitna_private.account_name(i.sender_id) end,'email',case when i.sender_id=v_user then i.email else null end,'incoming',i.sender_id<>v_user,'status',case when i.revoked then 'revoked' when i.accepted_by is not null then 'accepted' when i.expires_at<=now() then 'expired' else 'pending' end,'expiresAt',i.expires_at)),'[]'::jsonb) from kitna_private.friend_invites i where i.sender_id=v_user or (i.email=v_email and not i.revoked and i.expires_at>now() and i.accepted_by is null))
  );
end $$;

-- Synchronization never accepts an invitation. A code, link or inbox action
-- explicitly accepts only one group and preserves its existing participant slot.
create or replace function kitna_private.claim_email_invites(p_group_id uuid default null) returns void
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.verified_google_email(); v_item record; v_invite kitna_private.email_invites; v_person public.kitna_participants;
begin
  if p_group_id is null then raise exception using errcode='22023',message='Choose one invitation to accept explicitly.'; end if;
  if v_email is null then return; end if;
  for v_item in select group_id,participant_id from kitna_private.email_invites where email=v_email and not revoked and accepted_by is null and expires_at>now() and group_id=p_group_id and exists(select 1 from public.kitna_groups g where g.id=p_group_id and not coalesce((g.settings->>'archived')::boolean,false)) order by group_id,participant_id loop
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

create or replace function public.kitna_sync(p_revisions jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_pair record; v_result jsonb;
begin
  if p_revisions is null or jsonb_typeof(p_revisions)<>'object' or octet_length(p_revisions::text)>262144 then raise exception using errcode='22023',message='Invalid synchronization cursor.'; end if;
  for v_pair in select * from jsonb_each(p_revisions) loop perform kitna_private.number(v_pair.value,0,9223372036854775807); end loop;
  select jsonb_build_object(
    'memberGroupIds',coalesce(jsonb_agg(g.id order by g.id),'[]'::jsonb),
    'groups',coalesce(jsonb_agg(kitna_private.snapshot(g.id) order by g.id) filter(where (p_revisions->>g.id::text)::bigint is distinct from g.revision),'[]'::jsonb)
  ) into v_result from public.kitna_groups g join public.kitna_participants p on p.group_id=g.id where p.user_id=v_user and p.active;
  return v_result;
end $$;

-- Retain existing import/CAS semantics while allowing name-only and hidden friend ledgers.
create or replace function kitna_private.clean_group(p_value jsonb) returns jsonb
language plpgsql immutable set search_path = '' as $$
declare v_kind text := coalesce(p_value->>'kind','Group'); v_icon text := coalesce(p_value->>'icon','group'); v_color text := coalesce(p_value->>'color','teal'); v_budget jsonb := coalesce(p_value->'budget','null'::jsonb);
begin
  if jsonb_typeof(p_value)<>'object' then raise exception using errcode='22023', message='Invalid group settings.'; end if;
  if v_kind not in ('Group','Trip','Home','Couple','Direct','Friends','Other','Non-group') or v_icon not in ('group','home','plane','car','food','heart','work','beach','gift','games','shopping','coffee') or v_color not in ('teal','blue','purple','orange','red','pink','indigo','amber','green','slate') then
    raise exception using errcode='22023', message='Invalid group type, icon or color.';
  end if;
  if v_budget<>'null'::jsonb then perform kitna_private.number(v_budget,1,100000000000); end if;
  if jsonb_typeof(coalesce(p_value->'archived','false'::jsonb))<>'boolean' or jsonb_typeof(coalesce(p_value->'simplifyDebts','true'::jsonb))<>'boolean' then raise exception using errcode='22023', message='Invalid group options.'; end if;
  return jsonb_build_object('name',btrim(kitna_private.checked_text(p_value->'name',100)),'kind',v_kind,'icon',v_icon,'color',v_color,'budget',v_budget,'archived',coalesce(p_value->'archived','false'::jsonb),'simplifyDebts',coalesce(p_value->'simplifyDebts','true'::jsonb));
end $$;

create or replace function public.kitna_share_group(p_command_id uuid,p_group jsonb,p_participants jsonb,p_records jsonb default '[]'::jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_id uuid := (p_group->>'id')::uuid; v_fingerprint text; v_seen uuid; v_item jsonb; v_payload jsonb; v_kind text; v_entity uuid; v_settings jsonb;
begin
  v_fingerprint := encode(extensions.digest(jsonb_build_array('share',p_group,p_participants,p_records)::text,'sha256'),'hex');
  v_seen := kitna_private.command_seen(p_command_id,v_fingerprint);
  if v_seen is not null then return kitna_private.snapshot(v_seen); end if;
  v_settings := kitna_private.clean_group(p_group);
  if v_id is null or jsonb_typeof(p_participants) is distinct from 'array' or jsonb_array_length(p_participants) not between 1 and 100 or jsonb_typeof(p_records) is distinct from 'array' or jsonb_array_length(p_records)>10000 or octet_length(p_records::text)>20971520 then raise exception using errcode='22023', message='Invalid group import or group size.'; end if;
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

create or replace function public.kitna_group_invitations() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.verified_google_email();
begin
  return (select coalesce(jsonb_agg(jsonb_build_object('id',i.participant_id,'groupId',g.id,'participantId',i.participant_id,'name',g.settings->>'name','expiresAt',i.expires_at) order by g.created_at),'[]'::jsonb)
    from kitna_private.email_invites i join public.kitna_groups g on g.id=i.group_id
    where i.email=v_email and not i.revoked and i.expires_at>now() and i.accepted_by is null
      and not coalesce((g.settings->>'archived')::boolean,false) and not kitna_private.is_member(g.id));
end $$;

create or replace function public.kitna_accept_group_email(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid:=kitna_private.require_user(); v_email text:=kitna_private.verified_google_email();
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  if kitna_private.is_member(p_group_id) then return kitna_private.snapshot(p_group_id); end if;
  if v_email is null or not exists(select 1 from kitna_private.email_invites where group_id=p_group_id and email=v_email and accepted_by is null and not revoked and expires_at>now()) then raise exception using errcode='42501',message='This group invitation is not available for your verified Google account.'; end if;
  if exists(select 1 from public.kitna_groups where id=p_group_id and coalesce((settings->>'archived')::boolean,false)) then raise exception using errcode='22023',message='This group is archived. Ask the owner to restore it.'; end if;
  perform kitna_private.claim_email_invites(p_group_id);
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function public.kitna_status() returns jsonb
language sql immutable set search_path = '' as $$ select jsonb_build_object('schemaVersion',3,'ready',true,'receiptBucket','kitna-receipts','invitationsReady',true,'friendInvitationsReady',true) $$;

revoke all on function kitna_private.invite_attempt(),kitna_private.account_name(uuid),kitna_private.friend_result(uuid,uuid),kitna_private.accept_friend(uuid) from public,anon,authenticated;
revoke all on function public.kitna_preview_invite(text),public.kitna_add_members(uuid,jsonb),public.kitna_friend_invite(text,text),public.kitna_preview_friend_invite(text),public.kitna_accept_friend_invite(text),public.kitna_accept_friend_email(uuid),public.kitna_revoke_friend_invite(uuid),public.kitna_friends(),public.kitna_group_invitations(),public.kitna_accept_group_email(uuid) from public,anon;
grant execute on function public.kitna_preview_invite(text),public.kitna_add_members(uuid,jsonb),public.kitna_friend_invite(text,text),public.kitna_preview_friend_invite(text),public.kitna_accept_friend_invite(text),public.kitna_accept_friend_email(uuid),public.kitna_revoke_friend_invite(uuid),public.kitna_friends(),public.kitna_group_invitations(),public.kitna_accept_group_email(uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
