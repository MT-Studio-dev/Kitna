"""Read-only deployment checks. Uses only the Android publishable configuration."""
import base64
import hashlib
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

config = {}
for line in (Path(__file__).resolve().parents[1] / "local.properties").read_text().splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        config[key.strip()] = value.strip()
base = config["kitna.supabaseUrl"].rstrip("/")
headers = {"apikey": config["kitna.supabasePublishableKey"], "Content-Type": "application/json"}


def request(path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


status, protocol = request("/rest/v1/rpc/kitna_status", {})
auth_status, settings = request("/auth/v1/settings")
groups_status, _ = request("/rest/v1/rpc/kitna_list_groups", {})
table_status, _ = request("/rest/v1/kitna_groups?select=id&limit=1")
checks = {
    "protocol_http": status,
    "ready": status == 200 and protocol.get("ready") is True,
    "schema_version": protocol.get("schemaVersion"),
    "receipt_bucket": protocol.get("receiptBucket"),
    "google_enabled": auth_status == 200 and settings.get("external", {}).get("google") is True,
    "anonymous_group_rpc_denied": groups_status in (401, 403),
    "anonymous_group_table_denied": table_status in (401, 403),
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


verifier = secrets.token_urlsafe(32)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
query = urllib.parse.urlencode({"provider": "google", "redirect_to": "app.kitna://auth/callback", "code_challenge": challenge, "code_challenge_method": "s256"})
try:
    urllib.request.build_opener(NoRedirect).open(base + "/auth/v1/authorize?" + query, timeout=20)
    checks["google_oauth_redirect"] = False
except urllib.error.HTTPError as error:
    location = urllib.parse.urlparse(error.headers.get("Location", ""))
    checks["google_oauth_redirect"] = error.code in (302, 303) and location.hostname == "accounts.google.com"
print(json.dumps(checks, indent=2))
assert checks["ready"] and checks["google_enabled"] and checks["anonymous_group_rpc_denied"] and checks["anonymous_group_table_denied"] and checks["google_oauth_redirect"]
