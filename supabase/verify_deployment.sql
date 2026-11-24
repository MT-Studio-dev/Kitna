-- Read-only configuration verification after deploying the migration.
select public.kitna_status() as protocol;
select tablename,rowsecurity from pg_tables where schemaname='public' and tablename like 'kitna_%' order by tablename;
select id,public,file_size_limit,allowed_mime_types from storage.buckets where id='kitna-receipts';
select schemaname,tablename,policyname,roles,cmd from pg_policies where (schemaname='public' and tablename like 'kitna_%') or (schemaname='storage' and policyname like 'kitna_%') order by schemaname,tablename,policyname;
select
  has_function_privilege('anon','public.kitna_status()','execute') as anonymous_health_allowed,
  has_function_privilege('anon','public.kitna_list_groups()','execute') as anonymous_group_rpc_must_be_false,
  has_function_privilege('authenticated','public.kitna_apply(uuid,uuid,text,uuid,bigint,jsonb)','execute') as signed_in_command_allowed,
  has_table_privilege('authenticated','public.kitna_records','insert,update,delete') as direct_record_write_must_be_false;
select p.proname,p.prosecdef,p.proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like 'kitna_%' order by p.proname;
-- Supabase shows the last result by default. This is the readiness summary.
-- The detailed metadata above intentionally shows kitna_status.prosecdef=false:
-- this public health function does not need elevated privileges.
select
  (public.kitna_status()->>'ready')::boolean as ready,
  public.kitna_status()->>'schemaVersion' as schema_version,
  (select count(*)=6 and bool_and(rowsecurity) from pg_tables where schemaname='public' and tablename in ('kitna_groups','kitna_participants','kitna_records','kitna_revisions','kitna_activity','kitna_receipt_metadata')) as all_group_tables_protected,
  (select not public from storage.buckets where id='kitna-receipts') as receipts_private,
  has_function_privilege('anon','public.kitna_list_groups()','execute') as anonymous_group_access_must_be_false,
  has_table_privilege('authenticated','public.kitna_records','insert,update,delete') as direct_record_write_must_be_false;
