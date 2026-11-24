# Supabase setup for shared Kitna groups

Status: Kitna 0.5 implements Google browser sign-in, shared groups and foreground conditional synchronization. **Manga Panels** was selected. The user has configured Google, added the app redirect and deployed the shared-group migration to `hoevqcscyvjgwrqxfevc`. The hosted preflight in `cloud-preflight.log` passes. Final Gradle verification in `verify-v05-complete.log` passed 117 tests (50 core + 67 app), assembly and lint with 0 errors/26 warnings; 16 local SQL tests pass separately. Actual Google consent/app callback and a two-account phone flow still need verification.

The integration uses Google sign-in in the system browser, returning to Kitna with PKCE. This needs a Google **Web application** OAuth client because Supabase handles Google's callback. It does not require you to buy a domain, configure SMS, or set up an email delivery service for sign-in. A future native Google account picker would have additional Android client/fingerprint setup. The steps below document how to recreate or inspect the configuration; they are not a request to create another project.

## 1. Create the free Supabase project

1. Open [Supabase Dashboard](https://supabase.com/dashboard) and sign in.
2. Create an organization on the **Free** plan, or select an existing Free organization.
3. Select **New project** and name it **Kitna**.
4. Generate a strong database password and keep it in your password manager. It stays private; it is not an Android app setting.
5. Choose a region close to the group, such as an available India region for friends in India.
6. Create the project and wait until provisioning completes. Keep the Free plan; no paid upgrade is required for the initial friends test.

## 2. Find the two client settings

Open the project's **Connect** dialog. Copy:

```text
Project URL: https://<project-ref>.supabase.co
Publishable key: sb_publishable_...
```

Keys are also under **Settings → API Keys**. Use the publishable key, which is designed for mobile clients. If a tutorial asks for a `service_role` or `sb_secret_...` key, do not put it in the app. The database password, personal Supabase access tokens and server keys are not needed in chat.

The public client key identifies the application. A user's signed-in session and the deployed membership rules control whose records they can access. The client does not expose every table to every signed-in user. Android build settings are read from ignored `local.properties` entries `kitna.supabaseUrl` and `kitna.supabasePublishableKey`; secrets stay outside the build. [Supabase Android quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin), [API key guidance](https://supabase.com/docs/guides/getting-started/api-keys).

## 3. Copy Supabase's Google callback

Open **Authentication → Sign In / Providers → Google**. Depending on the dashboard version, the section may be named **Providers**.

Copy the callback URL shown there. It should have this form:

```text
https://<project-ref>.supabase.co/auth/v1/callback
```

Use the value displayed for your own project, not the placeholder above. Keep this tab open.

## 4. Set up Google Auth Platform

1. Open [Google Cloud Console](https://console.cloud.google.com/) using the Google account that will own the app configuration.
2. Create or select a project named **Kitna**. This is a separate project from the Supabase project.
3. Open **Google Auth Platform**. If you see the older navigation, look under **APIs & Services → OAuth consent screen / Credentials**.
4. Complete **Branding** with app name **Kitna**, a support email you can access, and the requested contact information.
5. Set the audience to **External**, since friends will use their own Google accounts. Do not choose a one-person/personal-only configuration for an app you intend to share.
6. During development, leave the app in testing and add your Google address and friends' addresses as test users where the console provides that section. Basic identity-only OAuth has exceptions to the usual test-user restrictions; we will verify the actual login behavior with two accounts.
7. Request only basic sign-in information: `openid`, email and profile. Kitna does not need Gmail, Contacts, Drive or Calendar access to track shared expenses.

Before a public launch, complete the production branding/privacy setup and any verification Google requests. Testing configuration is not proof of public launch readiness. [Google Auth Platform setup](https://support.google.com/cloud/answer/15544987?hl=en), [OAuth app states](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview), [brand verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification).

## 5. Create and connect the Google OAuth client

1. In **Google Auth Platform → Clients**, select **Create client**.
2. Select **Web application** and name it **Kitna Supabase**.
3. Under **Authorized redirect URIs**, add the exact HTTPS callback copied in step 3.
4. Leave JavaScript origins empty for this Android browser sign-in flow; there is no Kitna web frontend to register.
5. Create the client. Copy the Google **Client ID** and **Client Secret** directly into the Supabase Google provider settings.
6. Enable the Google provider and save.
7. Leave security defaults such as nonce verification enabled unless the chosen SDK flow explicitly requires a reviewed change.

The Google Client Secret stays in the Supabase dashboard. Do not paste it into this chat or an Android source file. [Supabase Google sign-in guide](https://supabase.com/docs/guides/auth/social-sign-in/auth-google).

## 6. Allow the app return address

In Supabase, open **Authentication → URL Configuration → Redirect URLs** and add this exact additional redirect:

```text
app.kitna://auth/callback
```

This is the app return address implemented in Kitna 0.5. It belongs in **Supabase's redirect allowlist**. Google's redirect field keeps the HTTPS Supabase callback from step 3. For the selected project that HTTPS callback is `https://hoevqcscyvjgwrqxfevc.supabase.co/auth/v1/callback`.

The return path will be:

```text
Kitna → Google → Supabase HTTPS callback → app.kitna://auth/callback → Kitna
```

The app implements browser-based PKCE, validates the callback and exchanges its authorization code. Simply opening the app return address without a valid sign-in request/code will not sign you in. Completing the real-account flow remains part of device acceptance testing. [Mobile deep links](https://supabase.com/docs/guides/auth/native-mobile-deep-linking).

## 7. Client configuration and safe handoff

The selected project's public client configuration has already been supplied. For a future configuration handoff, only these values/statuses are needed:

```text
Chosen design: Manga Panels
Project URL: https://hoevqcscyvjgwrqxfevc.supabase.co
Publishable key: sb_publishable_...
Google provider enabled: yes / not yet
App redirect added: yes / not yet
```

If a setup step fails, report the field name and error message after removing passwords, secrets and access tokens. On another development machine, configure the same public URL/key in ignored `local.properties`; do not copy elevated credentials into Android build settings.

## Implemented integration and remaining verification

- Version-controlled tables, transactional commands and membership rules for groups, participants, invites, expense payer/share records, repayments, comments and history.
- A **private** receipts bucket with group-member access rules and immutable photo paths.
- Browser PKCE sign-in with encrypted Android Keystore sessions, invitation acceptance, local caching, durable offline commands and visible edit conflicts.
- Foreground conditional HTTP revision checks every 20 seconds/manual request/reconnect, rather than WebSocket Realtime or an always-running background service.
- Explicit mapping of existing local participants when sharing a group; display names are not proof of identity.
- First-run guidance, faster entry, accessibility, Help/About/support and the selected Manga Panels direction.
- Remaining acceptance: complete Google sign-in and shared financial/photo journeys with two member accounts plus an unrelated account. The final v0.5 Android test/build/lint run passed; it does not substitute for that real-account journey.

The user has deployed the exact migration documented in [CLOUD_DEPLOYMENT.md](CLOUD_DEPLOYMENT.md). Tables should not be recreated manually with permissive policies. A public receipts bucket or blanket “all authenticated users” access would not provide private groups. See [SHARED_GROUPS_PLAN.md](SHARED_GROUPS_PLAN.md).

## Free-plan expectations

As checked on 24 November 2026, the Free plan lists 500 MB database space, 1 GB file storage, 50,000 monthly active users, 5 GB egress and 5 GB cached egress. It permits two active Free projects and pauses inactive projects after one week. Kitna's current conditional HTTP synchronization still consumes normal API/storage bandwidth; it does not use Realtime connection/message allowances.

These limits are a reasonable starting point for a small friends group. Receipt photos and repeated downloads consume storage/bandwidth, so public growth cannot be promised unlimited free hosting. Keep complete ZIP backups as well as cloud data. [Current Supabase pricing](https://supabase.com/pricing).
