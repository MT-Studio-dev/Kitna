-- Run after the invitation migrations. All four columns should be true.
select
  (public.kitna_status()->>'schemaVersion')::integer >= 2 as invitation_update_installed,
  (public.kitna_status()->>'invitationsReady')::boolean as invitations_ready,
  not has_function_privilege('anon','public.kitna_group_code(uuid)','execute') as codes_private,
  not has_function_privilege('anon','public.kitna_invite_email(uuid,uuid,text,text)','execute') as email_invitations_private;
