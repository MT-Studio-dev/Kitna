"""Optional recovery for slow interrupted Android dependency downloads.

Uses ranged HTTPS requests to Google Maven or Maven Central, verifies published
checksums (SHA-512 from module metadata where available, otherwise SHA-1 over
HTTPS), and warms the content-addressed Gradle cache. Requires curl. Not needed
on a normal connection; no third-party mirrors used. Optional arguments are
group:artifact:version coordinates for unclassified JAR/AAR artifacts.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import re

CACHE = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
ARTIFACTS = [
    ("androidx.compose.material3", "material3-android", "1.3.2"),
    ("androidx.compose.material", "material-android", "1.8.0"),
    ("androidx.compose.foundation", "foundation-android", "1.8.0"),
    ("androidx.compose.ui", "ui-android", "1.8.0"),
    ("androidx.compose.runtime", "runtime-android", "1.8.0"),
]

def fetch_chunk(url, start, end):
    for attempt in range(3):
        try:
            result = subprocess.run(["curl.exe" if os.name == "nt" else "curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "30", "--range", f"{start}-{end}", url], capture_output=True, check=True, timeout=35)
            data = result.stdout
            if len(data) != end - start + 1:
                raise ValueError("Server did not honor byte range or truncated response")
            return data
        except Exception:
            if attempt == 2:
                raise

def main():
    artifacts = [tuple(item.split(":")) for item in sys.argv[1:]] if len(sys.argv) > 1 else ARTIFACTS
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        jobs = []
        for group, artifact, version in artifacts:
            base = CACHE / group / artifact / version
            repository = "https://dl.google.com/dl/android/maven2/" if group.startswith(("androidx.", "com.android.")) else "https://repo.maven.apache.org/maven2/"
            prefix = repository + group.replace(".", "/") + f"/{artifact}/{version}/"
            modules = list(base.glob("*/*.module"))
            if modules:
                module = json.loads(modules[0].read_text(encoding="utf-8"))
                file = next(f for v in module["variants"] if v.get("attributes", {}).get("org.gradle.category") == "library" for f in v.get("files", []) if f["url"].endswith((".aar", ".jar")))
            else:
                name = f"{artifact}-{version}.jar"
                file = {"name": name, "url": name}
            if not file.get("size") or not file.get("sha1"):
                def curl_text(*args):
                    return subprocess.check_output(["curl.exe" if os.name == "nt" else "curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "20", *args], text=True)
                headers = curl_text("--head", prefix + file["url"])
                size = int(re.findall(r"(?im)^content-length:\s*(\d+)", headers)[-1])
                file = {**file, "size": size, "sha1": curl_text(prefix + file["url"] + ".sha1").strip().split()[0]}
            assert file.get("sha1") and file["size"] > 0
            target = base / file["sha1"] / file["name"]
            if target.exists() and hashlib.sha1(target.read_bytes()).hexdigest() == file["sha1"]:
                continue
            url = prefix + file["url"]
            chunks = [pool.submit(fetch_chunk, url, start, min(start + 16383, file["size"] - 1)) for start in range(0, file["size"], 16384)]
            jobs.append((file, target, chunks))
        for file, target, chunks in jobs:
            print(f"Fetching {file['name']}: {len(chunks)} small ranges", flush=True)
            data = b"".join(future.result() for future in chunks)
            assert len(data) == file["size"]
            if file.get("sha512"):
                assert hashlib.sha512(data).hexdigest() == file["sha512"], "Artifact hash mismatch"
            assert hashlib.sha1(data).hexdigest() == file["sha1"], "Artifact hash mismatch"
            target.parent.mkdir(parents=True, exist_ok=True)
            temp = target.with_suffix(".tmp")
            temp.write_bytes(data)
            temp.replace(target)
            print(f"Verified {file['name']}: {len(data):,} bytes", flush=True)

if __name__ == "__main__":
    main()
