"""Read-only contract probes plus synthetic DEV sign-in/refresh/logout; no application approval."""
import json
import pathlib
import requests

runtime = pathlib.Path(__file__).resolve().parents[2] / ".runtime/iiam"
account = dict(line.split("=", 1) for line in (runtime / "core-operator.properties").read_text().splitlines() if "=" in line)
if account["IDENTIFIER"] != "logimarui-dev-user":
    raise RuntimeError("Probe requires the synthetic DEV consumer")
base = "https://localhost:8443"
iam = base + "/api/iam"
session = requests.Session()
session.verify = str(runtime / "localhost.crt")
checks = []

def call(method, path, expected=200, **kwargs):
    response = session.request(method, base + path, timeout=30, **kwargs)
    if response.status_code != expected:
        (runtime / "identity-probe-error.json").write_text(response.text, encoding="utf-8")
        raise RuntimeError(f"Identity probe returned HTTP {response.status_code}; inspect private report")
    return response.json() if response.content else None

def csrf():
    return call("GET", "/api/iam/authentication/csrf")["token"]

def logout():
    call("POST", "/api/iam/authentication/logout", expected=204, headers={"X-XSRF-TOKEN": csrf()})

spec = call("GET", "/api/iam/v3/api-docs")
assert "post" not in spec["paths"].get("/api/v1/identity-applications", {})
for entry in ("users", "services"):
    operation = spec["paths"]["/api/v1/identity-applications/" + entry]["post"]
    assert operation.get("security", []) == [] and "201" in operation["responses"]
checks.append("separate-public-entrypoints-and-retired-polymorphic-post")
assert "approvedKind" in spec["components"]["schemas"]["IdentityApplicationResponse"]["properties"]
checks.append("approved-kind-exposed-separately-from-original-type")
identity = None
for identifier in (account["IDENTIFIER"], "DEV-USER@LOCALHOST.INVALID"):
    tokens = call("POST", "/api/iam/authentication/login", headers={"X-XSRF-TOKEN": csrf()},
                  json={"identifier": identifier, "password": account["PASSWORD"]})
    try:
        profile = call("GET", "/api/v1/gateway/me", headers={"Authorization": "Bearer " + tokens["accessToken"]})
        if identity is None:
            identity = profile["identityId"]
        assert profile["identityId"] == identity and profile["sessionActive"]
        checks.append("automatic-login-by-" + ("nickname" if "@" not in identifier else "email"))
        renewed = call("POST", "/api/iam/authentication/refresh", headers={"X-XSRF-TOKEN": csrf()})
        assert renewed["accessToken"] != tokens["accessToken"]
        checks.append("rotating-refresh-after-" + ("nickname" if "@" not in identifier else "email") + "-login")
    finally:
        logout()
report = {"passed": len(checks), "checks": checks}
(runtime / "identity-entrypoints-verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report, indent=2))
