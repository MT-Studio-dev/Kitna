-- Kitna shared-group protocol v1. Run once in the project's SQL editor as postgres.
-- App clients receive SELECT only and call the explicitly granted RPCs below.
begin;

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create schema if not exists kitna_private;
revoke all on schema kitna_private from public, anon;
grant usage on schema kitna_private to authenticated;

create table if not exists public.kitna_groups (
  id uuid primary key,
  owner_id uuid not null references auth.users(id),
  settings jsonb not null check (jsonb_typeof(settings) = 'object'),
  version bigint not null default 1 check (version > 0),
  revision bigint not null default 1 check (revision > 0),
  created_at timestamptz not null default now()
);
create table if not exists public.kitna_participants (
  id uuid primary key,
  group_id uuid not null references public.kitna_groups(id),
  name text not null check (char_length(btrim(name)) between 1 and 100),
  user_id uuid references auth.users(id),
  active boolean not null default true,
  unique (group_id, user_id),
  unique (group_id, id)
);
create index if not exists kitna_participant_user_groups on public.kitna_participants(user_id, group_id) where active;
create table if not exists public.kitna_records (
  group_id uuid not null references public.kitna_groups(id),
  kind text not null check (kind in ('expense','settlement','comment')),
  id uuid not null,
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  version bigint not null default 1 check (version > 0),
  actor_id uuid not null references auth.users(id),
  imported boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (group_id, kind, id)
);
create table if not exists public.kitna_revisions (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.kitna_groups(id),
  expense jsonb not null,
  reason text not null,
  time bigint not null,
  actor_id uuid not null references auth.users(id),
  imported boolean not null default false
);
create table if not exists public.kitna_activity (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.kitna_groups(id),
  message text not null,
  time bigint not null,
  actor_id uuid not null references auth.users(id),
  imported boolean not null default false
);
create index if not exists kitna_revision_group_time on public.kitna_revisions(group_id,time,id);
create index if not exists kitna_activity_group_time on public.kitna_activity(group_id,time,id);
create table if not exists public.kitna_receipt_metadata (
  group_id uuid not null references public.kitna_groups(id),
  id text not null,
  metadata jsonb not null,
  primary key (group_id, id)
);
create unique index if not exists kitna_receipt_case_unique on public.kitna_receipt_metadata(group_id,lower(id));
create table if not exists kitna_private.commands (
  actor_id uuid not null references auth.users(id),
  id uuid not null,
  group_id uuid not null references public.kitna_groups(id),
  fingerprint text not null,
  created_at timestamptz not null default now(),
  primary key (actor_id, id)
);
create table if not exists kitna_private.invites (
  token_hash text primary key,
  group_id uuid not null references public.kitna_groups(id),
  participant_id uuid references public.kitna_participants(id),
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null,
  accepted_by uuid references auth.users(id),
  revoked boolean not null default false
);

create or replace function kitna_private.require_user() returns uuid
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := auth.uid();
begin
  if v_user is null then raise exception using errcode='42501', message='Sign in to use shared groups.'; end if;
  return v_user;
end $$;

create or replace function kitna_private.is_member(p_group_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.kitna_participants where group_id=p_group_id and user_id=auth.uid() and active)
$$;

create or replace function kitna_private.require_member(p_group_id uuid) returns uuid
language plpgsql stable security definer set search_path = '' as $$
declare v_person uuid;
begin
  perform kitna_private.require_user();
  select id into v_person from public.kitna_participants where group_id=p_group_id and user_id=auth.uid() and active;
  if v_person is null then raise exception using errcode='42501', message='You are not a member of this shared group.'; end if;
  return v_person;
end $$;

create or replace function kitna_private.require_owner(p_group_id uuid) returns void
language plpgsql stable security definer set search_path = '' as $$
begin
  perform kitna_private.require_member(p_group_id);
  if not exists(select 1 from public.kitna_groups where id=p_group_id and owner_id=auth.uid()) then
    raise exception using errcode='42501', message='Only the group owner can manage this group.';
  end if;
end $$;

create or replace function kitna_private.number(p_value jsonb, p_min numeric, p_max numeric) returns bigint
language plpgsql immutable set search_path = '' as $$
begin
  if p_value is null or jsonb_typeof(p_value)<>'number' or p_value::text !~ '^-?[0-9]+$' then
    raise exception using errcode='22023', message='Money and split values must be whole integer paise.';
  end if;
  if p_value::numeric not between p_min and p_max then raise exception using errcode='22023', message='An amount is outside the supported range.'; end if;
  return p_value::text::bigint;
end $$;

create or replace function kitna_private.checked_text(p_value jsonb, p_max integer, p_allow_empty boolean default false) returns text
language plpgsql immutable set search_path = '' as $$
declare v_text text;
begin
  if p_value is null or jsonb_typeof(p_value)<>'string' then raise exception using errcode='22023', message='A required text field is missing.'; end if;
  v_text := p_value #>> '{}';
  if char_length(v_text)>p_max or (not p_allow_empty and v_text !~ '[^[:space:]]') then raise exception using errcode='22023', message='A text field is empty or too long.'; end if;
  return v_text;
end $$;

create or replace function kitna_private.checked_date(p_value jsonb) returns void
language plpgsql immutable set search_path = '' as $$
declare v_date text := kitna_private.checked_text(p_value,10);
begin
  if v_date !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' or to_char(v_date::date,'YYYY-MM-DD')<>v_date then
    raise exception using errcode='22023', message='Choose a valid date.';
  end if;
end $$;

create or replace function kitna_private.clean_group(p_value jsonb) returns jsonb
language plpgsql immutable set search_path = '' as $$
declare v_kind text := coalesce(p_value->>'kind','Group'); v_icon text := coalesce(p_value->>'icon','group'); v_color text := coalesce(p_value->>'color','teal'); v_budget jsonb := coalesce(p_value->'budget','null'::jsonb);
begin
  if jsonb_typeof(p_value)<>'object' then raise exception using errcode='22023', message='Invalid group settings.'; end if;
  if v_kind not in ('Group','Trip','Home','Couple','Direct') or v_icon not in ('group','home','plane','car','food','heart','work','beach','gift','games','shopping','coffee') or v_color not in ('teal','blue','purple','orange','red','pink','indigo','amber','green','slate') then
    raise exception using errcode='22023', message='Invalid group type, icon or color.';
  end if;
  if v_budget<>'null'::jsonb then perform kitna_private.number(v_budget,1,100000000000); end if;
  if jsonb_typeof(coalesce(p_value->'archived','false'::jsonb))<>'boolean' or jsonb_typeof(coalesce(p_value->'simplifyDebts','true'::jsonb))<>'boolean' then raise exception using errcode='22023', message='Invalid group options.'; end if;
  return jsonb_build_object('name',btrim(kitna_private.checked_text(p_value->'name',100)),'kind',v_kind,'icon',v_icon,'color',v_color,'budget',v_budget,'archived',coalesce(p_value->'archived','false'::jsonb),'simplifyDebts',coalesce(p_value->'simplifyDebts','true'::jsonb));
end $$;

create or replace function kitna_private.validate_expense(p_group_id uuid,p_value jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare v_amount bigint; v_pair record; v_sum numeric; v_photo jsonb; v_old jsonb;
begin
  v_amount := kitna_private.number(p_value->'amount',1,100000000000);
  perform kitna_private.checked_text(p_value->'description',500);
  perform kitna_private.checked_text(p_value->'category',100);
  perform kitna_private.checked_text(coalesce(p_value->'notes','""'::jsonb),4000,true);
  perform kitna_private.checked_date(p_value->'date');
  if p_value->>'groupId' is distinct from p_group_id::text then raise exception using errcode='22023', message='Expense belongs to a different group.'; end if;
  if coalesce(p_value->>'splitMode','Exact amounts') not in ('Equal','Exact amounts','Percentages','Shares','Adjustments') then raise exception using errcode='22023', message='Invalid split method.'; end if;
  if jsonb_typeof(coalesce(p_value->'deleted','false'::jsonb))<>'boolean' then raise exception using errcode='22023', message='Invalid deleted flag.'; end if;
  if jsonb_typeof(p_value->'payers') is distinct from 'object' or p_value->'payers'='{}'::jsonb or jsonb_typeof(p_value->'shares') is distinct from 'object' or p_value->'shares'='{}'::jsonb then
    raise exception using errcode='22023', message='Choose payers and participants.';
  end if;
  v_sum := 0;
  for v_pair in select * from jsonb_each(p_value->'payers') loop
    if not exists(select 1 from public.kitna_participants where group_id=p_group_id and id=v_pair.key::uuid) then raise exception using errcode='22023', message='Payer is not a group participant.'; end if;
    v_sum := v_sum + kitna_private.number(v_pair.value,0,v_amount);
  end loop;
  if v_sum<>v_amount then raise exception using errcode='22023', message='Payer contributions must equal the expense amount.'; end if;
  v_sum := 0;
  for v_pair in select * from jsonb_each(p_value->'shares') loop
    if not exists(select 1 from public.kitna_participants where group_id=p_group_id and id=v_pair.key::uuid) then raise exception using errcode='22023', message='Share belongs to an unknown participant.'; end if;
    v_sum := v_sum + kitna_private.number(v_pair.value,0,v_amount);
  end loop;
  if v_sum<>v_amount then raise exception using errcode='22023', message='Split amounts must equal the expense amount.'; end if;
  if jsonb_typeof(coalesce(p_value->'splitWeights','{}'::jsonb))<>'object' then raise exception using errcode='22023', message='Invalid split settings.'; end if;
  for v_pair in select * from jsonb_each(coalesce(p_value->'splitWeights','{}'::jsonb)) loop
    if not (p_value->'shares' ? v_pair.key) then raise exception using errcode='22023', message='Split settings contain an unknown participant.'; end if;
    perform kitna_private.number(v_pair.value,-100000000000,100000000000);
  end loop;
  if jsonb_typeof(coalesce(p_value->'receipts','[]'::jsonb))<>'array' or jsonb_array_length(coalesce(p_value->'receipts','[]'::jsonb))>10 then raise exception using errcode='22023', message='Attach at most ten receipt photos.'; end if;
  if (select count(*) from jsonb_array_elements(coalesce(p_value->'receipts','[]'::jsonb)))<>(select count(distinct value->>'id') from jsonb_array_elements(coalesce(p_value->'receipts','[]'::jsonb))) then raise exception using errcode='22023', message='A receipt is attached more than once.'; end if;
  for v_photo in select value from jsonb_array_elements(coalesce(p_value->'receipts','[]'::jsonb)) loop
    if coalesce(v_photo->>'id','') !~ '^[A-Za-z0-9_-]{1,100}$' or coalesce(v_photo->>'mimeType','')<>'image/jpeg' or coalesce(v_photo->>'sha256','') !~ '^[a-f0-9]{64}$' then raise exception using errcode='22023', message='Invalid receipt metadata.'; end if;
    perform kitna_private.checked_text(v_photo->'name',200);
    perform kitna_private.number(v_photo->'sizeBytes',1,2097152);
    select metadata into v_old from public.kitna_receipt_metadata where group_id=p_group_id and id=v_photo->>'id';
    if found and v_old<>v_photo then raise exception using errcode='22023', message='A receipt identifier cannot refer to different image data.'; end if;
    insert into public.kitna_receipt_metadata(group_id,id,metadata) values(p_group_id,v_photo->>'id',v_photo) on conflict do nothing;
  end loop;
end $$;

create or replace function kitna_private.validate_record(p_group_id uuid,p_kind text,p_id uuid,p_value jsonb,p_import boolean default false) returns void
language plpgsql security definer set search_path = '' as $$
declare v_person uuid;
begin
  if p_value is null or jsonb_typeof(p_value)<>'object' or octet_length(p_value::text)>262144 or p_value->>'id' is distinct from p_id::text then raise exception using errcode='22023', message='Invalid record identifier or record size.'; end if;
  if p_kind='expense' then perform kitna_private.validate_expense(p_group_id,p_value);
  elsif p_kind='settlement' then
    if p_value->>'groupId' is distinct from p_group_id::text or p_value->>'from'=p_value->>'to' then raise exception using errcode='22023', message='Choose two different group participants.'; end if;
    if not exists(select 1 from public.kitna_participants where group_id=p_group_id and id=(p_value->>'from')::uuid) or not exists(select 1 from public.kitna_participants where group_id=p_group_id and id=(p_value->>'to')::uuid) then raise exception using errcode='22023', message='Repayment participants must belong to the group.'; end if;
    perform kitna_private.number(p_value->'amount',1,100000000000);
    perform kitna_private.checked_text(p_value->'method',100);
    perform kitna_private.checked_text(coalesce(p_value->'reference','""'::jsonb),1000,true);
    perform kitna_private.checked_date(p_value->'date');
    if jsonb_typeof(coalesce(p_value->'deleted','false'::jsonb))<>'boolean' then raise exception using errcode='22023', message='Invalid deleted flag.'; end if;
  elsif p_kind='comment' then
    perform kitna_private.checked_text(p_value->'text',2000);
    perform kitna_private.number(p_value->'time',0,253402300799999);
    if not exists(select 1 from public.kitna_records where group_id=p_group_id and kind='expense' and id=(p_value->>'expenseId')::uuid and (p_import or not coalesce((payload->>'deleted')::boolean,false))) then raise exception using errcode='22023', message='Comment expense is unavailable.'; end if;
    if not exists(select 1 from public.kitna_participants where group_id=p_group_id and id=(p_value->>'authorId')::uuid) then raise exception using errcode='22023', message='Comment author must be a group participant.'; end if;
  else raise exception using errcode='22023', message='Unsupported record type.';
  end if;
end $$;

create or replace function kitna_private.validate_totals(p_group_id uuid) returns void
language plpgsql stable security definer set search_path = '' as $$
begin
  if (select coalesce(sum((payload->>'amount')::numeric),0) from public.kitna_records where group_id=p_group_id and kind='expense' and not coalesce((payload->>'deleted')::boolean,false))>9223372036854775807 then raise exception using errcode='22023', message='Combined spending exceeds the supported range.'; end if;
  if exists(
    select 1 from (
      select person,sum(amount) as balance from (
        select pair.key as person,(pair.value #>> '{}')::numeric as amount from public.kitna_records r cross join lateral jsonb_each(r.payload->'payers') pair where r.group_id=p_group_id and r.kind='expense' and not coalesce((r.payload->>'deleted')::boolean,false)
        union all select pair.key,-(pair.value #>> '{}')::numeric from public.kitna_records r cross join lateral jsonb_each(r.payload->'shares') pair where r.group_id=p_group_id and r.kind='expense' and not coalesce((r.payload->>'deleted')::boolean,false)
        union all select payload->>'from',(payload->>'amount')::numeric from public.kitna_records where group_id=p_group_id and kind='settlement' and not coalesce((payload->>'deleted')::boolean,false)
        union all select payload->>'to',-(payload->>'amount')::numeric from public.kitna_records where group_id=p_group_id and kind='settlement' and not coalesce((payload->>'deleted')::boolean,false)
      ) ledger group by person
    ) balances where balance not between -9223372036854775807 and 9223372036854775807
  ) then raise exception using errcode='22023', message='A combined balance exceeds the supported range.'; end if;
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
    'activity',(select coalesce(jsonb_agg(jsonb_build_object('id',a.id,'message',a.message,'time',a.time,'actorId',a.actor_id,'imported',a.imported) order by a.time,a.id),'[]'::jsonb) from public.kitna_activity a where a.group_id=g.id)
  ) into v_result from public.kitna_groups g where g.id=p_group_id;
  return v_result;
end $$;

create or replace function kitna_private.command_seen(p_command_id uuid,p_fingerprint text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v_command kitna_private.commands; v_user uuid := kitna_private.require_user();
begin
  if p_command_id is null then raise exception using errcode='22023', message='A command identifier is required.'; end if;
  perform pg_advisory_xact_lock(hashtextextended(v_user::text || ':' || p_command_id::text,0));
  select * into v_command from kitna_private.commands where actor_id=v_user and id=p_command_id;
  if found then
    if v_command.fingerprint<>p_fingerprint then raise exception using errcode='22023', message='This command identifier was already used for different data.'; end if;
    perform kitna_private.require_member(v_command.group_id);
    return v_command.group_id;
  end if;
  return null;
end $$;

create or replace function public.kitna_status() returns jsonb
language sql immutable set search_path = '' as $$ select jsonb_build_object('schemaVersion',1,'ready',true,'receiptBucket','kitna-receipts') $$;

create or replace function public.kitna_list_groups() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_result jsonb;
begin
  select coalesce(jsonb_agg(kitna_private.snapshot(p.group_id) order by p.group_id),'[]'::jsonb) into v_result from public.kitna_participants p where p.user_id=v_user and p.active;
  return v_result;
end $$;

create or replace function public.kitna_sync(p_revisions jsonb default '{}'::jsonb) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_pair record; v_result jsonb;
begin
  if p_revisions is null or jsonb_typeof(p_revisions)<>'object' or octet_length(p_revisions::text)>262144 then raise exception using errcode='22023', message='Invalid synchronization cursor.'; end if;
  for v_pair in select * from jsonb_each(p_revisions) loop
    perform kitna_private.number(v_pair.value,0,9223372036854775807);
  end loop;
  select jsonb_build_object(
    'memberGroupIds',coalesce(jsonb_agg(g.id order by g.id),'[]'::jsonb),
    'groups',coalesce(jsonb_agg(kitna_private.snapshot(g.id) order by g.id) filter(where (p_revisions->>g.id::text)::bigint is distinct from g.revision),'[]'::jsonb)
  ) into v_result from public.kitna_groups g join public.kitna_participants p on p.group_id=g.id where p.user_id=v_user and p.active;
  return v_result;
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

create or replace function public.kitna_apply(p_command_id uuid,p_group_id uuid,p_kind text,p_entity_id uuid,p_expected_version bigint,p_payload jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := kitna_private.require_user(); v_person uuid; v_group public.kitna_groups; v_old public.kitna_records; v_payload jsonb := p_payload; v_fingerprint text; v_seen uuid; v_time bigint := floor(extract(epoch from clock_timestamp())*1000)::bigint; v_message text;
begin
  v_fingerprint := encode(extensions.digest(jsonb_build_array('apply',p_group_id,p_kind,p_entity_id,p_expected_version,p_payload)::text,'sha256'),'hex');
  v_seen := kitna_private.command_seen(p_command_id,v_fingerprint);
  if v_seen is not null then return kitna_private.snapshot(v_seen); end if;
  select * into v_group from public.kitna_groups where id=p_group_id for update;
  v_person := kitna_private.require_member(p_group_id);
  if p_kind='group' then
    perform kitna_private.require_owner(p_group_id);
    if p_entity_id is null or p_expected_version is null or p_entity_id<>p_group_id or p_expected_version<>v_group.version then raise exception using errcode='40001', message='Group settings changed on another phone. Refresh and review your edit.'; end if;
    if p_payload ? 'members' then
      if jsonb_typeof(p_payload->'members')<>'array' or
         (select coalesce(jsonb_agg(value order by value),'[]'::jsonb) from jsonb_array_elements_text(p_payload->'members')) is distinct from
         (select coalesce(jsonb_agg(id::text order by id::text),'[]'::jsonb) from public.kitna_participants where group_id=p_group_id)
      then raise exception using errcode='22023', message='Use group invitations or member removal to change shared participants.'; end if;
    end if;
    v_payload := kitna_private.clean_group(p_payload);
    if v_payload->>'kind'='Direct' and (select count(*) from public.kitna_participants where group_id=p_group_id)<>2 then raise exception using errcode='22023', message='Direct groups require two participants.'; end if;
    update public.kitna_groups set settings=v_payload,version=version+1 where id=p_group_id;
    v_message := 'Updated group settings';
  else
    if coalesce((v_group.settings->>'archived')::boolean,false) then raise exception using errcode='22023', message='Restore this archived group before changing expenses.'; end if;
    if p_kind not in ('expense','settlement','comment') or p_entity_id is null then raise exception using errcode='22023', message='Unsupported shared record.'; end if;
    select * into v_old from public.kitna_records where group_id=p_group_id and kind=p_kind and id=p_entity_id;
    if (v_old.id is null and p_expected_version<>0) or (v_old.id is not null and p_expected_version<>v_old.version) or p_expected_version is null then raise exception using errcode='40001', message='This record changed on another phone. Refresh and review both versions.'; end if;
    if p_kind='comment' then
      if v_old.id is null then v_payload := p_payload || jsonb_build_object('authorId',v_person,'time',v_time);
      else
        if v_old.payload->>'authorId'<>v_person::text and v_group.owner_id<>v_user then raise exception using errcode='42501', message='Only the author or group owner can edit this comment.'; end if;
        v_payload := p_payload || jsonb_build_object('authorId',v_old.payload->'authorId','time',v_old.payload->'time');
      end if;
    end if;
    perform kitna_private.validate_record(p_group_id,p_kind,p_entity_id,v_payload);
    if v_old.id is not null and p_kind='expense' then
      insert into public.kitna_revisions(group_id,expense,reason,time,actor_id) values(p_group_id,v_old.payload,case when coalesce((v_payload->>'deleted')::boolean,false) then 'Before deletion' when coalesce((v_old.payload->>'deleted')::boolean,false) then 'Before restore' else 'Before edit' end,v_time,v_user);
    end if;
    insert into public.kitna_records(group_id,kind,id,payload,version,actor_id) values(p_group_id,p_kind,p_entity_id,v_payload,1,v_user)
      on conflict (group_id,kind,id) do update set payload=excluded.payload,version=public.kitna_records.version+1,actor_id=v_user,imported=false,updated_at=clock_timestamp();
    perform kitna_private.validate_totals(p_group_id);
    v_message := case when v_old.id is null then 'Added ' else 'Updated ' end || case p_kind when 'expense' then coalesce(v_payload->>'description','expense') when 'settlement' then 'repayment' else 'comment' end;
  end if;
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  insert into public.kitna_activity(group_id,message,time,actor_id) values(p_group_id,v_message,v_time,v_user);
  insert into kitna_private.commands(actor_id,id,group_id,fingerprint) values(v_user,p_command_id,p_group_id,v_fingerprint);
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function public.kitna_invite(p_group_id uuid,p_participant_id uuid default null) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_token text := encode(extensions.gen_random_bytes(32),'hex'); v_expires timestamptz := now()+interval '7 days';
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if p_participant_id is null and exists(select 1 from public.kitna_groups where id=p_group_id and settings->>'kind'='Direct') then raise exception using errcode='22023', message='Invite the existing person in a direct group.'; end if;
  if p_participant_id is not null and not exists(select 1 from public.kitna_participants where id=p_participant_id and group_id=p_group_id and (user_id is null or not active)) then raise exception using errcode='22023', message='Choose an unclaimed or removed participant to invite.'; end if;
  insert into kitna_private.invites(token_hash,group_id,participant_id,created_by,expires_at) values(encode(extensions.digest(v_token,'sha256'),'hex'),p_group_id,p_participant_id,auth.uid(),v_expires);
  return jsonb_build_object('token',v_token,'expires_at',v_expires,'groupId',p_group_id,'participantId',p_participant_id);
end $$;

create or replace function public.kitna_accept_invite(p_token text) returns jsonb
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

create or replace function public.kitna_revoke_invites(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  update kitna_private.invites set revoked=true where group_id=p_group_id and accepted_by is null;
  return jsonb_build_object('revoked',true);
end $$;

create or replace function public.kitna_remove_member(p_group_id uuid,p_participant_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_owner(p_group_id);
  if exists(select 1 from public.kitna_participants where id=p_participant_id and group_id=p_group_id and user_id=auth.uid()) then raise exception using errcode='22023', message='The owner cannot remove their own membership.'; end if;
  update public.kitna_participants set active=false where id=p_participant_id and group_id=p_group_id;
  if not found then raise exception using errcode='22023', message='Participant unavailable.'; end if;
  update kitna_private.invites set revoked=true where group_id=p_group_id and participant_id=p_participant_id and accepted_by is null;
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  return kitna_private.snapshot(p_group_id);
end $$;

create or replace function public.kitna_leave_group(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform 1 from public.kitna_groups where id=p_group_id for update;
  perform kitna_private.require_member(p_group_id);
  if exists(select 1 from public.kitna_groups where id=p_group_id and owner_id=auth.uid()) then raise exception using errcode='22023', message='The group owner must keep ownership of this group.'; end if;
  update public.kitna_participants set active=false where group_id=p_group_id and user_id=auth.uid();
  update public.kitna_groups set revision=revision+1 where id=p_group_id;
  return jsonb_build_object('left',true,'groupId',p_group_id);
end $$;

-- RLS applies to direct REST reads. All direct mutations remain ungranted.
alter table public.kitna_groups enable row level security;
alter table public.kitna_participants enable row level security;
alter table public.kitna_records enable row level security;
alter table public.kitna_revisions enable row level security;
alter table public.kitna_activity enable row level security;
alter table public.kitna_receipt_metadata enable row level security;
alter table kitna_private.commands enable row level security;
alter table kitna_private.invites enable row level security;
drop policy if exists kitna_group_read on public.kitna_groups;
create policy kitna_group_read on public.kitna_groups for select to authenticated using (kitna_private.is_member(id));
drop policy if exists kitna_participant_read on public.kitna_participants;
create policy kitna_participant_read on public.kitna_participants for select to authenticated using (kitna_private.is_member(group_id));
drop policy if exists kitna_record_read on public.kitna_records;
create policy kitna_record_read on public.kitna_records for select to authenticated using (kitna_private.is_member(group_id));
drop policy if exists kitna_revision_read on public.kitna_revisions;
create policy kitna_revision_read on public.kitna_revisions for select to authenticated using (kitna_private.is_member(group_id));
drop policy if exists kitna_activity_read on public.kitna_activity;
create policy kitna_activity_read on public.kitna_activity for select to authenticated using (kitna_private.is_member(group_id));
drop policy if exists kitna_receipt_metadata_read on public.kitna_receipt_metadata;
create policy kitna_receipt_metadata_read on public.kitna_receipt_metadata for select to authenticated using (kitna_private.is_member(group_id));
revoke all on public.kitna_groups,public.kitna_participants,public.kitna_records,public.kitna_revisions,public.kitna_activity,public.kitna_receipt_metadata from public,anon,authenticated;
grant select on public.kitna_groups,public.kitna_participants,public.kitna_records,public.kitna_revisions,public.kitna_activity,public.kitna_receipt_metadata to authenticated;
revoke all on all tables in schema kitna_private from public,anon,authenticated;
revoke execute on all functions in schema kitna_private from public,anon,authenticated;
grant execute on function kitna_private.is_member(uuid) to authenticated;

-- Immutable, private receipt objects. Members may insert/download, not overwrite.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('kitna-receipts','kitna-receipts',false,2097152,array['image/jpeg'])
on conflict (id) do update set public=false,file_size_limit=2097152,allowed_mime_types=array['image/jpeg'];
create or replace function kitna_private.receipt_access(p_name text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  if p_name is null or p_name !~ '^[a-f0-9-]{36}/[A-Za-z0-9_-]{1,100}\.jpg$' then return false; end if;
  return kitna_private.is_member(split_part(p_name,'/',1)::uuid);
exception when invalid_text_representation then return false;
end $$;
revoke all on function kitna_private.receipt_access(text) from public,anon;
grant execute on function kitna_private.receipt_access(text) to authenticated;
drop policy if exists kitna_receipt_download on storage.objects;
create policy kitna_receipt_download on storage.objects for select to authenticated using(bucket_id='kitna-receipts' and kitna_private.receipt_access(name));
drop policy if exists kitna_receipt_upload on storage.objects;
create policy kitna_receipt_upload on storage.objects for insert to authenticated with check(bucket_id='kitna-receipts' and kitna_private.receipt_access(name));

revoke all on function public.kitna_status() from public;
grant execute on function public.kitna_status() to anon,authenticated;
revoke all on function public.kitna_list_groups(),public.kitna_sync(jsonb),public.kitna_share_group(uuid,jsonb,jsonb,jsonb),public.kitna_apply(uuid,uuid,text,uuid,bigint,jsonb),public.kitna_invite(uuid,uuid),public.kitna_accept_invite(text),public.kitna_revoke_invites(uuid),public.kitna_remove_member(uuid,uuid),public.kitna_leave_group(uuid) from public,anon;
grant execute on function public.kitna_list_groups(),public.kitna_sync(jsonb),public.kitna_share_group(uuid,jsonb,jsonb,jsonb),public.kitna_apply(uuid,uuid,text,uuid,bigint,jsonb),public.kitna_invite(uuid,uuid),public.kitna_accept_invite(text),public.kitna_revoke_invites(uuid),public.kitna_remove_member(uuid,uuid),public.kitna_leave_group(uuid) to authenticated;

notify pgrst, 'reload schema';
commit;
