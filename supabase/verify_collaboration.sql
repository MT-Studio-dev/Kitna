-- Read-only checks after 202611250001_simplified_collaboration.sql.
-- Execute as the SQL-editor administrator. Every column should be true.
select
  (public.kitna_status()->>'schemaVersion')::integer >= 3 as collaboration_update_installed,
  not has_function_privilege('anon','public.kitna_preview_invite(text)','execute') as group_preview_requires_sign_in,
  not has_function_privilege('anon','public.kitna_friends()','execute') as friends_require_sign_in,
  not has_function_privilege('anon','public.kitna_group_invitations()','execute') as group_inbox_requires_sign_in,
  not has_function_privilege('anon','public.kitna_accept_group_email(uuid)','execute') as group_acceptance_requires_sign_in,
  not has_function_privilege('authenticated','kitna_private.accept_friend(uuid)','execute') as private_claim_helper_denied,
  not has_table_privilege('authenticated','kitna_private.friend_invites','select') as friend_tokens_private,
  not has_table_privilege('authenticated','kitna_private.friendships','insert') as direct_membership_writes_denied,
  to_regclass('kitna_private.kitna_friend_invite_sender') is not null as invitation_sender_lookup_indexed,
  to_regclass('kitna_private.kitna_friendships_second_user') is not null as friendship_reverse_lookup_indexed,
  has_function_privilege('authenticated','public.kitna_add_members(uuid,jsonb)','execute') as batch_member_rpc_available;
