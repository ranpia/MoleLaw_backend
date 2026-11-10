"""Single opt-in provider diagnostic using the captured synthetic request and durable budget."""
import fcntl
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener
from model_gateway import Ledger, NoRedirect, PRICES

lock=Path('/results/live-ledger.lock').open('a')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
ledger=Ledger('/results/live-ledger.json',limit=8,budget=5)
body=json.loads(Path('/results/diagnostic-tool-request.json').read_text())
body['messages']=body['messages'][:2]
body['max_completion_tokens']=128
body['store']=False
rates=PRICES[body['model']]
bound=len(json.dumps(body,ensure_ascii=False).encode())+1024*len(body['messages'])+1024
key=ledger.reserve((bound*rates[0]+128*rates[1])/1e6)
secret=Path('/run/secrets/openai-key').read_text().strip()
result={'attempt':key,'model':body['model'],'reasoningEffort':body.get('reasoning_effort')}
try:
    request=Request('https://api.openai.com/v1/chat/completions',data=json.dumps(body).encode(),
        headers={'Authorization':'Bearer '+secret,'Content-Type':'application/json'})
    with build_opener(NoRedirect()).open(request,timeout=30) as response:
        payload=json.load(response)
        usage=payload['usage']
        ledger.settle(key,(usage['prompt_tokens']*rates[0]+usage['completion_tokens']*rates[1])/1e6)
        result.update(status=response.status,usage=usage)
except HTTPError as error:
    payload=json.loads(error.read(65536))
    result.update(status=error.code,error=payload.get('error',{}))
    # The diagnostic request contains only synthetic fixtures; never expose the credential.
    result=json.loads(json.dumps(result).replace(secret,'[REDACTED]'))
    ledger.fail()
except Exception:
    ledger.fail()
    raise
finally:
    Path('/results/provider-diagnostic.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
