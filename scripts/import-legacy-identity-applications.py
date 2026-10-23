"""Read-only MySQL source -> pending IIAM applications; never identities, grants or passwords.

Requires Python requests and pymysql. Default is inventory only. Private runtime
directory must already have restricted ACLs from initialize-iiam-dev.ps1.
"""
import argparse
import collections
import datetime as dt
import json
import pathlib
import re
import sys
import urllib.parse

import pymysql
import requests


def properties(path):
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def valid_cpf(value):
    if len(value) != 11 or len(set(value)) == 1:
        return False
    for size in (9, 10):
        check = (sum(int(value[i]) * (size + 1 - i) for i in range(size)) * 10) % 11 % 10
        if check != int(value[size]):
            return False
    return True


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2] / ".runtime/iiam")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    private = args.runtime.resolve()
    core = pathlib.Path(__file__).resolve().parents[1]
    verification = json.loads((private / "gateway-verification.json").read_text(encoding="utf-8-sig"))
    if verification["passed"] < 16 or verification["gateway"] != "https://localhost:8443":
        raise RuntimeError("Integrated DEV verification is required before source inventory/import")
    config = properties(core / "src/main/resources/application-dev.properties")
    url = urllib.parse.urlparse(config["spring.datasource.url"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=url.hostname, port=url.port or 3306, database=url.path.strip("/"),
                                 user=config["spring.datasource.username"], password=config["spring.datasource.password"],
                                 connect_timeout=10, read_timeout=20, charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            # Explicit projection deliberately excludes password_hash, tokens and authorization tables.
            cursor.execute("SELECT id,name,cpf,email,birth_data,phone_number,status FROM authentication_users ORDER BY id")
            users = cursor.fetchall()
        connection.rollback()
    finally:
        connection.close()
    save(private / "legacy-users-private.json", users)
    state_file = private / "legacy-applications-state.json"
    state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}
    prepared, issues = [], []
    for user in users:
        cpf = re.sub(r"\D", "", str(user.get("cpf") or ""))
        full_name, email = str(user.get("name") or "").strip(), str(user.get("email") or "").strip()
        errors = []
        if not valid_cpf(cpf):
            errors.append("invalid-or-missing-cpf")
        if not full_name or len(full_name) > 150:
            errors.append("invalid-or-missing-full-name")
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email) or len(email) > 254:
            errors.append("invalid-or-missing-email")
        if errors:
            issues.append({"legacyUserId": user["id"], "reasons": errors})
            continue
        payload = {"type": "PERSON", "fullName": full_name, "cpf": cpf, "email": email,
                   "nickname": f"legacy-core-{user['id']}"}
        birth = user.get("birth_data")
        if isinstance(birth, dt.date) and birth < dt.date.today():
            payload["birthDate"] = birth.isoformat()
        phone = str(user.get("phone_number") or "").strip()
        if phone and re.fullmatch(r"[0-9+()\-\s]+", phone) and 10 <= len(re.sub(r"\D", "", phone)) <= 15:
            payload["phoneNumber"] = phone
        prepared.append((user, payload))
    created, matched = 0, 0
    if args.apply:
        session = requests.Session()
        session.verify = str(private / "localhost.crt")
        base = verification["gateway"] + "/api/iam"
        def call(method, path, **kwargs):
            response = session.request(method, base + path, timeout=30, **kwargs)
            if response.status_code >= 400:
                # Response may contain personal data: keep only in the restricted local directory.
                (private / "legacy-import-last-error.json").write_text(response.text, encoding="utf-8")
                raise RuntimeError(f"IIAM {method} {path} returned {response.status_code}; inspect private error")
            return response.json() if response.content else None
        csrf = call("GET", "/authentication/csrf")["token"]
        operator = properties(private / "operator.properties")
        token = call("POST", "/authentication/login", headers={"X-XSRF-TOKEN": csrf},
                     json={"identityKind": operator["IDENTITY_KIND"], "identifier": operator["IDENTIFIER"], "password": operator["PASSWORD"]})["accessToken"]
        existing = call("GET", "/api/v1/admin/identity-applications", headers={"Authorization": "Bearer " + token})
        try:
            for user, payload in prepared:
                key = str(user["id"])
                prior = next((item for item in existing if item.get("nickname") == payload["nickname"]
                              or re.sub(r"\D", "", str(item.get("details", {}).get("cpf") or "")) == payload["cpf"]), None)
                if prior:
                    if re.sub(r"\D", "", str(prior.get("details", {}).get("cpf") or "")) != payload["cpf"]:
                        raise RuntimeError("Existing application nickname belongs to another document")
                    if prior.get("status") != "PENDING":
                        issues.append({"legacyUserId": user["id"], "reasons": ["existing-application-not-pending"]})
                        continue
                    matched += 1
                    application = prior
                else:
                    application = call("POST", "/api/v1/identity-applications", json=payload)
                    created += 1
                    existing.append(application)
                if application.get("status") != "PENDING" or any(application.get(field) for field in (
                        "resolvedIdentityId", "assignedTenantId", "resultingAffiliationId", "reviewedByIdentityId")):
                    raise RuntimeError("Import did not produce a pending application")
                state[key] = {"applicationId": application["id"], "status": application["status"],
                              "nickname": application.get("nickname"), "legacyStatus": user["status"]}
                save(state_file, state)
        finally:
            csrf = call("GET", "/authentication/csrf")["token"]
            call("POST", "/authentication/logout", headers={"X-XSRF-TOKEN": csrf})
    report = {"applied": args.apply, "sourceUsers": len(users), "eligible": len(prepared), "created": created,
              "matchedPending": matched, "mapped": len(state), "issues": issues,
              "sourceStatusCounts": dict(collections.Counter(user["status"] for user in users)),
              "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    save(private / "legacy-import-report.json", report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"Import stopped ({type(failure).__name__}); inspect private runtime/configuration. No passwords or grants migrated.", file=sys.stderr)
        sys.exit(1)
