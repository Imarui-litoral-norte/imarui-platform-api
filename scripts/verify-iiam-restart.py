"""Explicit opt-in: restart the managed IIAM in local DEV and verify the same session survives."""
import argparse
import json
import pathlib
import sys
import requests

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--restart",action="store_true",help="authorize the actual local DEV IIAM restart")
args=parser.parse_args()
if not args.restart:
    parser.error("Pass --restart to exercise the local DEV supervisor")
runtime=pathlib.Path(__file__).resolve().parents[2]/".runtime/iiam"
credentials=dict(line.split("=",1) for line in (runtime/"core-operator.properties").read_text().splitlines() if "=" in line)
session=requests.Session();session.verify=str(runtime/"localhost.crt")
base="https://localhost:8443";iam=base+"/api/iam"
def call(method,url,**kwargs):
    response=session.request(method,url,timeout=45,**kwargs)
    if response.status_code>=400:
        (runtime/"restart-last-error.json").write_text(response.text,encoding="utf-8")
        raise RuntimeError("Local DEV runtime probe HTTP "+str(response.status_code))
    return response.json() if response.content else None
csrf=call("GET",iam+"/authentication/csrf")["token"]
tokens=call("POST",iam+"/authentication/login",headers={"X-XSRF-TOKEN":csrf},json={
    "identifier":credentials["IDENTIFIER"],"password":credentials["PASSWORD"]})
try:
    headers={"Authorization":"Bearer "+tokens["accessToken"]}
    before=call("GET",base+"/api/v1/admin/services/runtime",headers=headers)
    old=next(item for item in before if item["serviceId"]=="iiam")
    call("POST",base+"/api/v1/admin/services/iiam/restart",headers=headers)
    current=call("GET",base+"/api/v1/admin/services/runtime",headers=headers)
    new=next(item for item in current if item["serviceId"]=="iiam")
    assert new["rootPid"]!=old["rootPid"] and new["port"]==old["port"]
    me=call("GET",base+"/api/v1/gateway/me",headers=headers)
    assert me["sessionActive"]
    result={"iiamRestart":200,"samePort":True,"processReplaced":True,"existingSessionPreserved":True}
    (runtime/"supervisor-restart-verification.json").write_text(json.dumps(result,indent=2))
    print(json.dumps(result))
finally:
    primary_failed=sys.exc_info()[0] is not None
    try:
        csrf=call("GET",iam+"/authentication/csrf")["token"]
        call("POST",iam+"/authentication/logout",headers={"X-XSRF-TOKEN":csrf})
    except (requests.RequestException,RuntimeError):
        if not primary_failed:
            raise
